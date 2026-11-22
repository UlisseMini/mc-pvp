"""Fast, balanced sealed benchmark for Snowball / Egg Volleys:
- 3-throw quick burst (ticks 0, 5, 10)
- 10-throw sustained high-EV burst (ticks 0, 4, 8, ..., 36)
Across all 9 sealed BedWars (bw-09..12) + SkyWars (5 unseen) maps!
"""
import json
import time
from pathlib import Path

import numpy as np
import torch
from torch.nn import functional as F

from train_v2 import BowNeuralNetV2, NUM_MODES, NUM_H, MAX_H, build_features_v2, contiguous_runs
from eval_and_calibrate import linear_baseline_path
from eval_v2 import interp_modes_v2
from void_and_wait import (
    THROW_SPEED, THROW_GRAVITY, THROW_BASE, THROW_VOID,
    build_solid_set, collect_vantage_pool, check_los_ray,
    intercept_g, ray_blocked_g, mode_hit_prob_at_g, solve_aim_ev,
    eval_shot_truth_smooth, forward_with_trunk,
)


def run_volley_suite(model, device, data_dir: Path, burst_specs, max_per_match=60, seed=123):
    rng = np.random.default_rng(seed)
    manifest = json.loads((data_dir / 'manifest.json').read_text())
    arms = ('linear', 'v2_single', 'v3_ev_novolley', 'v3_ev_volley')
    results = {
        name: {
            'all': {a: {'n': 0, 'hit': 0.0, 'vk': 0.0, 'ev': 0.0} for a in arms},
            'void_opp': {a: {'n': 0, 'hit': 0.0, 'vk': 0.0, 'ev': 0.0} for a in arms},
        }
        for name in burst_specs
    }
    max_off = max(max(offs) for offs in burst_specs.values())

    for m in manifest['matches']:
        if m['split'] != 'test':
            continue
        npz = np.load(data_dir / m['file'])
        solid_set = build_solid_set(npz)
        if solid_set is None:
            continue
        vantages = collect_vantage_pool(npz, step=35)
        if len(vantages) < 5:
            continue

        # Collect balanced starts across all players in this match
        cand_starts = []
        for p_idx in range(int(npz['num_players'][0])):
            ticks = npz[f'p{p_idx}_tick']
            pos = npz[f'p{p_idx}_pos']
            past, fut = contiguous_runs(ticks, pos)
            for i in range(0, len(ticks), 40):
                if past[i] >= 10 and fut[i] >= max_off + MAX_H:
                    cand_starts.append((p_idx, i))
        if not cand_starts:
            continue
        rng.shuffle(cand_starts)
        # Filter down to valid LOS vantages up to max_per_match
        selected = []
        for (p_idx, start) in cand_starts:
            pos = npz[f'p{p_idx}_pos']
            p_mid = pos[start + 8]
            dists = np.linalg.norm(vantages - p_mid[None, :], axis=1)
            valid_v = np.where((dists >= 10.0) & (dists <= 26.0))[0]
            if len(valid_v) == 0:
                continue
            rng.shuffle(valid_v)
            for vi in valid_v[:15]:
                vp = vantages[vi]
                sx, eye_y, sz = float(vp[0]), float(vp[1]) + 1.62, float(vp[2])
                if check_los_ray(sx, eye_y, sz, float(p_mid[0]), float(p_mid[1]) + 1.1, float(p_mid[2]), solid_set):
                    selected.append((p_idx, start, sx, eye_y, sz))
                    break
            if len(selected) >= max_per_match:
                break
        if not selected:
            continue

        # Group by player for batched GPU inference
        all_offs = sorted({dt for offs in burst_specs.values() for dt in offs})
        by_player = {}
        for item in selected:
            by_player.setdefault(item[0], []).append(item)

        for p_idx, items in by_player.items():
            ticks = npz[f'p{p_idx}_tick']
            pos = npz[f'p{p_idx}_pos']
            spos = npz[f'p{p_idx}_spos']
            rot = npz[f'p{p_idx}_rot']
            flags = npz[f'p{p_idx}_flags']
            rel = npz[f'p{p_idx}_rel']
            vox = npz[f'p{p_idx}_vox']
            cvox = npz[f'p{p_idx}_cvox']
            past, _ = contiguous_runs(ticks, pos)

            needed = sorted({start + dt for (_, start, _, _, _) in items for dt in all_offs})
            hl, cl, vfl, vcl, ql = [], [], [], [], []
            for idx in needed:
                h, c, vf, vc, q = build_features_v2(pos, spos, rot, flags, rel, vox, cvox, idx, int(past[idx]))
                hl.append(h); cl.append(c); vfl.append(vf); vcl.append(vc); ql.append(q)
            n_tot = len(needed)
            pi_all = np.empty((n_tot, NUM_MODES), dtype=np.float32)
            mean_all = np.empty((n_tot, NUM_MODES, NUM_H, 3), dtype=np.float32)
            std_all = np.empty((n_tot, NUM_MODES, NUM_H, 3), dtype=np.float32)
            rho_all = np.empty((n_tot, NUM_MODES, NUM_H), dtype=np.float32)
            for b0 in range(0, n_tot, 512):
                b1 = min(n_tot, b0 + 512)
                bh = torch.from_numpy(np.asarray(hl[b0:b1])).to(device)
                bc = torch.from_numpy(np.asarray(cl[b0:b1])).to(device)
                bvf = torch.from_numpy(np.asarray(vfl[b0:b1])).to(device)
                bvc = torch.from_numpy(np.asarray(vcl[b0:b1])).to(device)
                _, lo, me, ls, rh = forward_with_trunk(model, bh, bc, bvf, bvc)
                pi_all[b0:b1] = F.softmax(lo, dim=-1).cpu().numpy()
                mean_all[b0:b1] = me.cpu().numpy()
                std_all[b0:b1] = ls.exp().cpu().numpy()
                rho_all[b0:b1] = rh.cpu().numpy()
            idx_map = {idx: k for k, idx in enumerate(needed)}

            for (_, start, sx, eye_y, sz) in items:
                for b_name, offs in burst_specs.items():
                    arm_shots = {a: [] for a in arms}
                    volley_airborne = []
                    for dt in offs:
                        idx = start + dt
                        k = idx_map[idx]
                        p0 = pos[idx]
                        q = ql[k]
                        pi = pi_all[k]
                        paths, stds, rhos = interp_modes_v2(p0, q, mean_all[k], std_all[k], rho_all[k])

                        lin_path = linear_baseline_path(pos, idx, MAX_H)
                        s_lin = intercept_g(sx, eye_y, sz, THROW_SPEED, lin_path, 0.90, THROW_GRAVITY)
                        if s_lin is not None and ray_blocked_g(s_lin, solid_set):
                            s_lin = None
                        arm_shots['linear'].append((idx, s_lin))

                        s_v2, _, _, _, _, _ = solve_aim_ev(
                            sx, eye_y, sz, THROW_SPEED, THROW_GRAVITY, pi, paths, stds, rhos,
                            solid_set, is_throwable=True, use_void_ev=False, refine=False,
                        )
                        arm_shots['v2_single'].append((idx, s_v2))

                        s_nv, _, _, _, _, _ = solve_aim_ev(
                            sx, eye_y, sz, THROW_SPEED, THROW_GRAVITY, pi, paths, stds, rhos,
                            solid_set, is_throwable=True, use_void_ev=True, refine=False,
                        )
                        arm_shots['v3_ev_novolley'].append((idx, s_nv))

                        eff_w = pi.copy().astype(np.float64)
                        for (t_prev, prev_shot) in volley_airborne:
                            rem = prev_shot[2] - (dt - t_prev)
                            if 0.5 < rem <= MAX_H:
                                for mk in range(NUM_MODES):
                                    p_cov = mode_hit_prob_at_g(prev_shot, paths, stds, rhos, mk, rem)
                                    eff_w[mk] *= (1.0 - p_cov)
                        s_vol, _, _, _, _, _ = solve_aim_ev(
                            sx, eye_y, sz, THROW_SPEED, THROW_GRAVITY, pi, paths, stds, rhos,
                            solid_set, is_throwable=True, eff_weights=eff_w, use_void_ev=True, refine=False,
                        )
                        arm_shots['v3_ev_volley'].append((idx, s_vol))
                        if s_vol is not None:
                            volley_airborne.append((dt, s_vol))

                    if arm_shots['v3_ev_volley'][0][1] is None and arm_shots['v2_single'][0][1] is None:
                        continue

                    # Evaluate all arms on this burst
                    arm_evals = {}
                    any_void_opp = False
                    for a in arms:
                        p_miss = 1.0
                        p_novk = 1.0
                        for (idx_t, sh) in arm_shots[a]:
                            pt, vk, _ = eval_shot_truth_smooth(sh, spos, idx_t, solid_set, is_throwable=True)
                            p_miss *= (1.0 - pt)
                            if vk:
                                p_novk *= (1.0 - pt)
                                if pt > 0.05:
                                    any_void_opp = True
                        p_hit = 1.0 - p_miss
                        p_vk = 1.0 - p_novk
                        burst_ev = p_vk * THROW_VOID + max(0.0, p_hit - p_vk) * THROW_BASE
                        arm_evals[a] = (p_hit, p_vk, burst_ev)

                    for a in arms:
                        p_hit, p_vk, burst_ev = arm_evals[a]
                        for bucket in (('all', 'void_opp') if any_void_opp else ('all',)):
                            st = results[b_name][bucket][a]
                            st['n'] += 1
                            st['hit'] += p_hit
                            st['vk'] += p_vk
                            st['ev'] += burst_ev
        print(f'  [{m["file"]}] done ({len(selected)} bursts)', flush=True)

    return results


def main():
    t0 = time.time()
    device = torch.device('mps' if torch.backends.mps.is_available() else 'cpu')
    ckpt = torch.load('runtime/bow-aim/neural-model-v2/winner.pt', map_location='cpu')
    v2 = BowNeuralNetV2(NUM_MODES).to(device)
    v2.load_state_dict(ckpt['state_dict'])
    v2.eval()

    burst_specs = {
        '3_throw_quick': (0, 5, 10),
        '10_throw_sustained': tuple(range(0, 40, 4)),
    }
    res = run_volley_suite(v2, device, Path('runtime/bow-aim/neural-data-v2'), burst_specs, max_per_match=60)

    out = {}
    for b_name, buckets in res.items():
        out[b_name] = {}
        for bucket in ('all', 'void_opp'):
            n_b = buckets[bucket]['v3_ev_volley']['n']
            print(f'\n=== SEALED SNOWBALL/EGG {b_name.upper()} [{bucket}] (n={n_b}) ===')
            out[b_name][bucket] = {}
            for a, st in buckets[bucket].items():
                n = max(1, st['n'])
                r = {'n': st['n'], 'hit_rate': st['hit'] / n, 'vk_rate': st['vk'] / n, 'burst_ev': st['ev'] / n}
                out[b_name][bucket][a] = r
                print(f'  {a:16s} | Joint Hit={r["hit_rate"]*100:6.2f}% | Joint VoidKill={r["vk_rate"]*100:6.2f}% | Burst EV={r["burst_ev"]:.3f}x')

    p = Path('runtime/bow-aim/neural-model-v3/volley_benchmark.json')
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(json.dumps(out, indent=2))
    print(f'\nSaved to {p} in {time.time()-t0:.1f}s')


if __name__ == '__main__':
    main()
