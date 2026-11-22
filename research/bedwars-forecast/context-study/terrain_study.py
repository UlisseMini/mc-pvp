"""Evaluate causal walkable-terrain reweighting on held-out BedWars matches (game-09..12).
Tunes the single airborne/void residual alpha ONLY on development matches (game-07..08),
then measures exact 3D foot-block NLL and P(Y | X, Z) NLL on held-out games 09..12."""
import argparse
import gzip
import json
import sys
from pathlib import Path

import numpy as np
import torch

PARENT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(PARENT))
sys.path.insert(0, str(Path(__file__).resolve().parent))

from forecast import HORIZONS, PlayerModel, cube_mass, load_games, observed, take
from heatmap import EDGE, HEIGHT_MIN, HEIGHTS, SIZE, WIDTH, Heatmap, data_for
from context_models import (
    EXPERTS,
    Gate,
    collect_tracks,
    make_motion,
    route_matches,
    windows,
)

# Non-solid blocks in 1.8.9 that players can pass through (air, flowers, tall grass, torches, rails, fire, liquids, signs, pressure plates, snow layer, carpet, etc.)
PASSABLE_IDS = {
    0, 6, 8, 9, 10, 11, 27, 28, 30, 31, 32, 37, 38, 39, 40, 50, 51, 55, 59,
    63, 65, 66, 68, 69, 70, 72, 75, 76, 77, 78, 83, 90, 93, 94, 104, 105,
    106, 115, 131, 132, 141, 142, 143, 147, 148, 149, 150, 157, 171, 175, 176, 177,
}


class CausalTerrain:
    """Reconstruct exact causal solid/walkable voxel grid at any match second t."""

    def __init__(self, path):
        with gzip.open(path, "rt") as f:
            raw = json.load(f)
        self.game = raw["game"]
        blocks = np.asarray(raw["blocks"], dtype=np.int32).reshape(-1, 5)
        changes = np.asarray(raw["changes"], dtype=np.int32).reshape(-1, 5) if raw["changes"] else np.empty((0, 5), dtype=np.int32)
        # Keep only blocks inside the [-128, 127] x [0, 191] x [-128, 127] region
        self.y0 = 0
        self.ny = 192
        self.solid = np.zeros((256, self.ny, 256), dtype=bool)
        # Sort static blocks by appear time and changes by event time
        order_b = np.argsort(blocks[:, 4], kind="stable")
        self.blocks = blocks[order_b]
        self.b_ptr = 0
        order_c = np.argsort(changes[:, 0], kind="stable") if len(changes) else np.empty(0, dtype=int)
        self.changes = changes[order_c]
        self.c_ptr = 0
        self.t = -1

    @staticmethod
    def _is_solid(state):
        bid = int(state) >> 4
        return bid != 0 and bid not in PASSABLE_IDS

    def advance(self, t):
        if t < self.t:
            raise ValueError("CausalTerrain must advance monotonically")
        while self.b_ptr < len(self.blocks) and self.blocks[self.b_ptr, 4] <= t:
            x, y, z, state, _ = self.blocks[self.b_ptr]
            self.b_ptr += 1
            if -128 <= x < 128 and 0 <= y < self.ny and -128 <= z < 128:
                self.solid[x + 128, y, z + 128] = self._is_solid(state)
        while self.c_ptr < len(self.changes) and self.changes[self.c_ptr, 0] <= t:
            _, x, y, z, state = self.changes[self.c_ptr]
            self.c_ptr += 1
            if -128 <= x < 128 and 0 <= y < self.ny and -128 <= z < 128:
                self.solid[x + 128, y, z + 128] = self._is_solid(state)
        self.t = t

    def walkable_grid(self):
        """Return (walkable_3d[256, 192, 256], non_void_xz[256, 256]) at current time t."""
        s = self.solid
        # Feet block (x, y, z) is walkable if solid below (y-1) and passable at (y) and (y+1),
        # or 1-block jump/hop above a solid block (solid at y-2, passable at y-1, y, y+1).
        stand = np.zeros_like(s)
        stand[:, 1:-1, :] = s[:, :-2, :] & (~s[:, 1:-1, :]) & (~s[:, 2:, :])
        hop = np.zeros_like(s)
        hop[:, 2:-1, :] = s[:, :-3, :] & (~s[:, 1:-2, :]) & (~s[:, 2:-1, :]) & (~s[:, 3:, :])
        walk = stand | hop
        non_void = s.any(axis=1)
        return walk, non_void


def eval_game_with_terrain(data, game, terrain_path, hmap, motion, gate_model, alphas, stride=5):
    """Evaluate exact 3D NLL of personal-gate before and after causal walkable-terrain masking."""
    rows = data.raw
    seen = observed(rows)
    gt = collect_tracks(game)
    terrain = CausalTerrain(terrain_path)

    # Group all eligible query rows by issue second t so we advance terrain monotonically once!
    here = np.flatnonzero((rows["game"] == game["id"]) & seen & (rows["time"] % stride == 0))
    if not len(here):
        return {}

    # Precompute full-track personal gate weights and expert components per (uid, hindex)
    buckets = {}
    for idx in np.flatnonzero(rows["game"] == game["id"]):
        buckets.setdefault((rows["uid"][idx], int(rows["hindex"][idx])), []).append(idx)

    # Store per-row metadata needed for exact full-grid evaluation
    row_info = {}
    with torch.no_grad():
        for (uid, hi), track_list in buckets.items():
            track = np.asarray(track_list)
            query = track[seen[track] & (rows["time"][track] % stride == 0)]
            if not len(query):
                continue
            horizon = HORIZONS[hi]
            t = rows["time"][query]
            pieces, context = windows(gt[uid], t, horizon)
            route_pts, route_w, route_features = route_matches(rows, query, track, horizon)
            # Availability of 7 experts
            avail = np.ones((len(query), 7), dtype=bool)
            avail[:, 3] = pieces[0][1].sum(1) > 0
            avail[:, 4] = pieces[1][1].sum(1) > 0
            avail[:, 5] = route_w.sum(1) > 0
            avail[:, 6] = pieces[2][1].sum(1) > 0
            feat33 = np.concatenate((rows["features"][query], context, route_features), axis=1)
            gw = gate_model(
                torch.tensor(feat33, dtype=torch.float32),
                torch.full((len(query),), hi, dtype=torch.long),
                torch.tensor(avail, dtype=torch.bool),
            ).numpy()

            # Precompute heatmap coarse + fine parameters and motion mixture for query
            logits, enc = hmap.coarse(data.batch(query))
            coarse_p = logits.softmax(1).numpy()
            law = motion[hi].predict(take(rows, query))["Motion mixture"][0]

            for q_i, idx in enumerate(query):
                row_info[int(idx)] = (
                    int(t[q_i]),
                    hi,
                    gw[q_i],
                    coarse_p[q_i],
                    enc[q_i],
                    rows["features"][idx],
                    rows["xyz"][idx],
                    data.label[idx].numpy(),
                    (law.centers[q_i], law.scales[q_i], law.weights[q_i]),
                    (pieces[0][0][q_i], pieces[0][1][q_i], pieces[0][2]),
                    (pieces[1][0][q_i], pieces[1][1][q_i], pieces[1][2]),
                    (route_pts[q_i], route_w[q_i], [2.2 + 0.027 * horizon, 0.8 + 0.007 * horizon, 2.2 + 0.027 * horizon]),
                    (pieces[2][0][q_i], pieces[2][1][q_i], pieces[2][2]),
                )

    # Evaluate second by second with exact causal terrain!
    # To keep runtime fast (<15s per game), evaluate exact 3D block probability + exact normalization
    # across the 256 x 192 x 256 lattice using separable 1D Gaussian marginals + per-tile fine head!
    xs = np.arange(-128, 128, dtype=np.float64)
    ys = np.arange(0, 192, dtype=np.float64)
    from scipy.special import ndtr

    def gauss_1d(centers, scales, grid):
        # centers: (K,), scales: (K,) -> (K, len(grid))
        lo = (grid[None, :] - centers[:, None]) / scales[:, None]
        hi = lo + 1.0 / scales[:, None]
        return np.where(lo >= 0, ndtr(-lo) - ndtr(-hi), ndtr(hi) - ndtr(lo))

    def sep_mix_mass(pts, wts, scale, walk_idx, nv_idx, truth):
        """Exact 3D probability at truth AND exact total probability mass on walkable_3d & non_void_xz via sparse indices!"""
        s = wts.sum()
        if s <= 0:
            return 0.0, 0.0, 0.0
        w = wts / s
        nz = w > 1e-6
        pts = np.floor(pts[nz]) + 0.5
        w = w[nz]
        if np.ndim(scale) == 1:
            sx = np.full(len(w), scale[0])
            sy = np.full(len(w), scale[1])
            sz = np.full(len(w), scale[2])
        else:
            sx, sy, sz = scale[nz, 0], scale[nz, 1], scale[nz, 2]
        px = gauss_1d(pts[:, 0], sx, xs)  # (K, 256)
        py = gauss_1d(pts[:, 1], sy, ys)  # (K, 192)
        pz = gauss_1d(pts[:, 2], sz, xs)  # (K, 256)
        tx, ty, tz = int(truth[0]) + 128, int(truth[1]), int(truth[2]) + 128
        p_truth = float(np.sum(w * px[:, tx] * py[:, ty] * pz[:, tz])) if 0 <= ty < 192 else 0.0
        # Sparse walkable 3D evaluation: wx, wy, wz each ~15k ints -> vectorized in <0.5ms!
        wx, wy, wz = walk_idx
        p_walk = float(np.sum(w[:, None] * px[:, wx] * py[:, wy] * pz[:, wz])) if len(wx) else 0.0
        nx_i, nz_i = nv_idx
        p_xz = float(np.sum(w[:, None] * px[:, nx_i] * pz[:, nz_i])) if len(nx_i) else 0.0
        return p_truth, p_walk, p_xz

    by_time = {}
    for idx, info in row_info.items():
        by_time.setdefault(info[0], []).append((idx, info))

    nll_base = {hi: [] for hi in range(len(HORIZONS))}
    nll_walk = {(hi, a): [] for hi in range(len(HORIZONS)) for a in alphas}
    nll_xz = {(hi, a): [] for hi in range(len(HORIZONS)) for a in alphas}
    walk_hit = {hi: [0, 0] for hi in range(len(HORIZONS))}
    nonvoid_hit = {hi: [0, 0] for hi in range(len(HORIZONS))}

    tiles_all = torch.arange(SIZE * SIZE, dtype=torch.long)

    for sec in sorted(by_time):
        terrain.advance(sec)
        walk_3d, non_void_xz = terrain.walkable_grid()
        wx, wy, wz = np.nonzero(walk_3d)
        wt = (wx // WIDTH) * SIZE + (wz // WIDTH)
        wfx, wfz = wx % WIDTH, wz % WIDTH
        walk_idx = (wx, wy, wz)
        nx_i, nz_i = np.nonzero(non_void_xz)
        nt = (nx_i // WIDTH) * SIZE + (nz_i // WIDTH)
        nfx, nfz = nx_i % WIDTH, nz_i % WIDTH
        nv_idx = (nx_i, nz_i)

        for idx, info in by_time[sec]:
            _, hi, gw, coarse_p, enc, feat, xyz, truth, mot, p_rec, p_old, p_rt, p_hom = info
            tx, ty, tz = int(truth[0]) + 128, int(truth[1]), int(truth[2]) + 128
            in_bounds = 0 <= ty < 192
            is_walk = bool(walk_3d[tx, ty, tz]) if in_bounds else False
            is_nv = bool(non_void_xz[tx, tz])
            walk_hit[hi][0] += int(is_walk)
            walk_hit[hi][1] += 1
            nonvoid_hit[hi][0] += int(is_nv)
            nonvoid_hit[hi][1] += 1

            # 1. Expert 0: Heatmap exact 3D mass at truth & on sparse walkable_3d / non_void_xz
            with torch.no_grad():
                n_t = SIZE * SIZE
                horiz_l, y_l, y_w = hmap.fine_components(
                    enc[None].expand(n_t, -1),
                    torch.tensor(xyz, dtype=torch.float32)[None].expand(n_t, -1),
                    tiles_all,
                    torch.tensor(feat, dtype=torch.float32)[None].expand(n_t, -1),
                )
                hx = horiz_l[:, 0, :].exp().numpy()  # (256, 16)
                hz = horiz_l[:, 1, :].exp().numpy()  # (256, 16)
                hy = torch.logsumexp(y_l + y_w[:, :, None], dim=1).exp().numpy()
                hy_sub = hy[:, -HEIGHT_MIN : -HEIGHT_MIN + 192]  # (256, 192)
                t_tile = ((int(truth[0]) - EDGE) // WIDTH) * SIZE + ((int(truth[2]) - EDGE) // WIDTH)
                fx, fz = (int(truth[0]) - EDGE) % WIDTH, (int(truth[2]) - EDGE) % WIDTH
                p0_truth = float(coarse_p[t_tile] * hx[t_tile, fx] * hz[t_tile, fz] * (hy_sub[t_tile, ty] if in_bounds else 0.0))
                p0_walk = float(np.sum(coarse_p[wt] * hx[wt, wfx] * hz[wt, wfz] * hy_sub[wt, wy])) if len(wt) else 0.0
                p0_xz = float(np.sum(coarse_p[nt] * hx[nt, nfx] * hz[nt, nfz])) if len(nt) else 0.0

            # 2. Expert 1: Motion mixture
            p1_truth, p1_walk, p1_xz = sep_mix_mass(mot[0], mot[2], mot[1], walk_idx, nv_idx, truth)

            # 3. Expert 2: Stay tightly (3 components)
            stay_pts = np.repeat(xyz[None, :], 3, axis=0)
            stay_w = np.array([0.68, 0.24, 0.08])
            stay_s = np.array([[0.5, 0.46, 0.5], [1.9, 0.87, 1.9], [6.5, 2.1, 6.5]])
            p2_truth, p2_walk, p2_xz = sep_mix_mass(stay_pts, stay_w, stay_s, walk_idx, nv_idx, truth)

            # 4-7. Personal experts: recent, older, route, home
            p3_truth, p3_walk, p3_xz = sep_mix_mass(p_rec[0], p_rec[1], p_rec[2], walk_idx, nv_idx, truth)
            p4_truth, p4_walk, p4_xz = sep_mix_mass(p_old[0], p_old[1], p_old[2], walk_idx, nv_idx, truth)
            p5_truth, p5_walk, p5_xz = sep_mix_mass(p_rt[0], p_rt[1], p_rt[2], walk_idx, nv_idx, truth)
            p6_truth, p6_walk, p6_xz = sep_mix_mass(p_hom[0], p_hom[1], p_hom[2], walk_idx, nv_idx, truth)

            p_t = np.array([p0_truth, p1_truth, p2_truth, p3_truth, p4_truth, p5_truth, p6_truth])
            p_w = np.array([p0_walk, p1_walk, p2_walk, p3_walk, p4_walk, p5_walk, p6_walk])
            p_n = np.array([p0_xz, p1_xz, p2_xz, p3_xz, p4_xz, p5_xz, p6_xz])

            base_p = float(np.dot(gw, p_t))
            mass_walk = float(np.dot(gw, p_w))
            mass_nv = float(np.dot(gw, p_n))

            nll_base[hi].append(-np.log(max(base_p, 1e-40)))
            for a in alphas:
                # P_masked(x) = P(x) * (1 if mask(x) else a) / (mass_mask + a * (1 - mass_mask))
                z_w = mass_walk + a * max(0.0, 1.0 - mass_walk)
                p_masked_w = base_p * (1.0 if is_walk else a) / max(z_w, 1e-40)
                nll_walk[(hi, a)].append(-np.log(max(p_masked_w, 1e-40)))

                z_n = mass_nv + a * max(0.0, 1.0 - mass_nv)
                p_masked_n = base_p * (1.0 if is_nv else a) / max(z_n, 1e-40)
                nll_xz[(hi, a)].append(-np.log(max(p_masked_n, 1e-40)))

    return {
        "game": game["id"],
        "base": {HORIZONS[hi]: float(np.mean(v)) for hi, v in nll_base.items() if v},
        "walk": {(HORIZONS[hi], a): float(np.mean(v)) for (hi, a), v in nll_walk.items() if v},
        "nonvoid": {(HORIZONS[hi], a): float(np.mean(v)) for (hi, a), v in nll_xz.items() if v},
        "walk_coverage": {HORIZONS[hi]: c[0] / max(1, c[1]) for hi, c in walk_hit.items()},
        "nonvoid_coverage": {HORIZONS[hi]: c[0] / max(1, c[1]) for hi, c in nonvoid_hit.items()},
    }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--heatmap", type=Path, required=True)
    ap.add_argument("--gates", type=Path, required=True)
    ap.add_argument("--tracks", type=Path, default=Path("runtime/bedwars-forecast/advanced/tracks.json.gz"))
    ap.add_argument("--terrain-dir", type=Path, default=Path("research/bedwars-forecast/viewer/dist/assets"))
    ap.add_argument("--stride", type=int, default=7)
    ap.add_argument("--out", type=Path, required=True)
    args = ap.parse_args()

    games = load_games(args.tracks)
    keyed = {g["id"]: g for g in games}
    data = data_for(games)
    dev_train = [f"game-{i:02}" for i in range(1, 9)]
    motion = make_motion(data, dev_train)

    pack = torch.load(args.heatmap, map_location="cpu", weights_only=True)
    hmap = Heatmap()
    hmap.load_state_dict(pack["heatmap"])
    hmap.eval()

    gates_pack = torch.load(args.gates, map_location="cpu", weights_only=True)
    gate = Gate("full", slice(None), 33)
    gate.load_state_dict(gates_pack["full personal gate"])
    gate.eval()

    alphas = [0.005, 0.01, 0.02, 0.04, 0.08, 0.15, 0.25, 0.40, 0.60]
    dev_games = ["game-07", "game-08"]
    test_games = ["game-09", "game-10", "game-11", "game-12"]

    dev_res = [eval_game_with_terrain(data, keyed[g], args.terrain_dir / f"terrain-{g}.json.gz", hmap, motion, gate, alphas, args.stride) for g in dev_games]
    # Select best alpha per mask type on dev_games ONLY (zero test leakage!)
    best_walk_alpha = min(alphas, key=lambda a: np.mean([r["walk"][(h, a)] for r in dev_res for h in HORIZONS if (h, a) in r["walk"]]))
    best_nv_alpha = min(alphas, key=lambda a: np.mean([r["nonvoid"][(h, a)] for r in dev_res for h in HORIZONS if (h, a) in r["nonvoid"]]))
    print(f"Selected on dev (game-07..08): walk_alpha={best_walk_alpha}, nonvoid_alpha={best_nv_alpha}", flush=True)

    test_res = [eval_game_with_terrain(data, keyed[g], args.terrain_dir / f"terrain-{g}.json.gz", hmap, motion, gate, [best_walk_alpha, best_nv_alpha], args.stride) for g in test_games]
    summary = {"dev_walk_alpha": best_walk_alpha, "dev_nonvoid_alpha": best_nv_alpha, "horizons": {}}
    print("\n=== HELD-OUT GAMES 09..12 (exact 3D block NLL, lower is better) ===")
    print(f"{'H':>4s} | {'Personal Gate':>13s} | {'+ NonVoid 2D':>13s} | {'+ Walkable 3D':>13s} | {'Gain (nats)':>11s} | {'Walk cov':>8s}")
    for h in HORIZONS:
        b = float(np.mean([r["base"][h] for r in test_res if h in r["base"]]))
        nv = float(np.mean([r["nonvoid"][(h, best_nv_alpha)] for r in test_res if (h, best_nv_alpha) in r["nonvoid"]]))
        wk = float(np.mean([r["walk"][(h, best_walk_alpha)] for r in test_res if (h, best_walk_alpha) in r["walk"]]))
        cov = float(np.mean([r["walk_coverage"][h] for r in test_res]))
        summary["horizons"][str(h)] = {"base": b, "nonvoid": nv, "walkable": wk, "gain_nats": b - wk, "walk_coverage": cov}
        print(f"{h:>3d}s | {b:13.4f} | {nv:13.4f} | {wk:13.4f} | {b - wk:+11.4f} | {100*cov:7.1f}%")

    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(summary, indent=2) + "\n")
    print("Saved terrain study to", args.out)


if __name__ == "__main__":
    main()
