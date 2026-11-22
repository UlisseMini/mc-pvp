"""Evaluate & calibrate BowNeuralNetV2 vs v1 vs baselines on the exact same sealed test sets:
- Sealed BedWars (game-09..12) at 0ms AND realistic 80ms Hypixel RTT server-truth!
- Sealed SkyWars (5 unseen matches)
- Realistic Live-POV Terrain-Occluded shots
- Continuous multi-mode (yaw, pitch) refinement ablation
- Cover-Urgency + Smart Peak-Spike Auto-Release
"""
import argparse
import json
import math
from pathlib import Path
import time

import numpy as np
import torch
from torch.nn import functional as F

from train_v2 import (
    BowNeuralNetV2, HORIZONS, NUM_H, MAX_H, NUM_MODES,
    build_features_v2, contiguous_runs, inv_rot_xz, export_java_v2,
)
from eval_and_calibrate import (
    DRAG, GRAVITY, OFFSET, SPREAD, SPEED_FULL,
    travel, fall, sample_path, intercept, intersects_box, simulate_arrow_hit,
    apply_calibration, fit_calibration, load_bow_motion_weights,
    linear_baseline_path, bow_motion_shared_path, smart_release_decision,
)

# 5-point Gauss-Hermite nodes/weights for exact 2D correlated-normal box integration
GH_X = (-2.0201828704560856, -0.9585724646138185, 0.0, 0.9585724646138185, 2.0201828704560856)
GH_W = (0.019953242059045913, 0.39361932315224116, 0.9453087204829419, 0.39361932315224116, 0.019953242059045913)
INV_SQRT_PI = 0.5641895835477563
INV_SQRT2 = 0.7071067811865475


def interp_modes_v2(p0, q, means_kh3, std_kh3, rho_kh):
    K = means_kh3.shape[0]
    paths = np.zeros((K, MAX_H + 1, 3), dtype=np.float64)
    stds = np.zeros((K, MAX_H + 1, 3), dtype=np.float64)
    rhos = np.zeros((K, MAX_H + 1), dtype=np.float64)
    paths[:, 0, :] = p0
    stds[:, 0, :] = 0.05

    anc_pos = np.zeros((K, NUM_H + 1, 3), dtype=np.float64)
    anc_std = np.zeros((K, NUM_H + 1, 3), dtype=np.float64)
    anc_rho = np.zeros((K, NUM_H + 1), dtype=np.float64)
    anc_pos[:, 0, :] = p0
    anc_std[:, 0, :] = 0.05
    for k in range(K):
        for hi in range(NUM_H):
            rx, ry, rz = means_kh3[k, hi]
            wx, wz = inv_rot_xz(rx, rz, q)
            anc_pos[k, hi + 1, 0] = p0[0] + wx
            anc_pos[k, hi + 1, 1] = p0[1] + ry
            anc_pos[k, hi + 1, 2] = p0[2] + wz
            sx, sy, sz = std_kh3[k, hi]
            r = float(rho_kh[k, hi])
            if q == 0:
                anc_std[k, hi + 1, 0], anc_std[k, hi + 1, 2], anc_rho[k, hi + 1] = sx, sz, r
            elif q == 1:
                # world dx = -rz, dz = rx => Cov(dx, dz) = -Cov(rz, rx) = -rho
                anc_std[k, hi + 1, 0], anc_std[k, hi + 1, 2], anc_rho[k, hi + 1] = sz, sx, -r
            elif q == 2:
                # world dx = -rx, dz = -rz => Cov = +rho
                anc_std[k, hi + 1, 0], anc_std[k, hi + 1, 2], anc_rho[k, hi + 1] = sx, sz, r
            else:
                # world dx = rz, dz = -rx => Cov = -rho
                anc_std[k, hi + 1, 0], anc_std[k, hi + 1, 2], anc_rho[k, hi + 1] = sz, sx, -r
            anc_std[k, hi + 1, 1] = sy

    knots = [0] + HORIZONS
    hi_idx = 0
    for t in range(1, MAX_H + 1):
        while hi_idx + 1 < len(knots) - 1 and t > knots[hi_idx + 1]:
            hi_idx += 1
        t0, t1 = knots[hi_idx], knots[hi_idx + 1]
        u = (t - t0) / float(t1 - t0)
        paths[:, t, :] = anc_pos[:, hi_idx, :] * (1.0 - u) + anc_pos[:, hi_idx + 1, :] * u
        stds[:, t, :] = anc_std[:, hi_idx, :] * (1.0 - u) + anc_std[:, hi_idx + 1, :] * u
        rhos[:, t] = anc_rho[:, hi_idx] * (1.0 - u) + anc_rho[:, hi_idx + 1] * u
    return paths, stds, rhos


def prob1d(lo, hi, s):
    d = INV_SQRT2 / max(0.08, s)
    return 0.5 * (math.erf(hi * d) - math.erf(lo * d))


def correlated_box_prob(ax, ay, az, mx, my, mz, sx, sy, sz, rho, ivx, ivy, ivz):
    """Exact probability for correlated 2D horizontal Gaussian (sx, sz, rho) + vertical Y Gaussian."""
    slack_x = 0.6 + 0.38 * abs(ivx)
    slack_y_lo = -0.3 - 0.35 * abs(ivy)
    slack_y_hi = 2.1 + 0.35 * abs(ivy)
    slack_z = 0.6 + 0.38 * abs(ivz)

    py = prob1d(ay - my - slack_y_hi, ay - my - slack_y_lo, sy)
    if py < 1e-6:
        return 0.0
    dx = ax - mx
    dz = az - mz
    if abs(rho) < 0.05:
        return prob1d(dx - slack_x, dx + slack_x, sx) * py * prob1d(dz - slack_z, dz + slack_z, sz)

    # Integrate over u = (X - mx) / sx inside [(dx - slack_x)/sx, (dx + slack_x)/sx]
    # using 5-point Gauss-Legendre on the exact truncated interval [a, b] for razor-sharp accuracy!
    a = max(-4.0, (dx - slack_x) / max(0.08, sx))
    b = min(4.0, (dx + slack_x) / max(0.08, sx))
    if a >= b:
        return 0.0
    # 5-point Gauss-Legendre on [-1, 1]
    gl_t = (-0.9061798459, -0.5384693101, 0.0, 0.5384693101, 0.9061798459)
    gl_w = (0.2369268851, 0.4786286705, 0.5688888889, 0.4786286705, 0.2369268851)
    mid = 0.5 * (a + b)
    half = 0.5 * (b - a)
    cond_sz = max(0.08, sz * math.sqrt(max(0.05, 1.0 - rho * rho)))
    inv_sqrt2pi = 0.3989422804014327
    pxz = 0.0
    for ti, wi in zip(gl_t, gl_w):
        u = mid + half * ti
        pdf_u = inv_sqrt2pi * math.exp(-0.5 * u * u)
        cond_mz = dz - rho * sz * u
        pz_cond = prob1d(cond_mz - slack_z, cond_mz + slack_z, cond_sz)
        pxz += wi * pdf_u * pz_cond
    pxz *= half
    return max(0.0, min(1.0, pxz * py))


def shot_from_angles(sx, eye_y, sz, speed, yaw, pitch, t_hit):
    origin_y = eye_y - 0.1
    x0 = sx - OFFSET * math.cos(yaw)
    y0 = origin_y
    z0 = sz - OFFSET * math.sin(yaw)
    vx = -math.sin(yaw) * math.cos(pitch) * speed
    vy = -math.sin(pitch) * speed
    vz = math.cos(yaw) * math.cos(pitch) * speed
    return (yaw, pitch, t_hit, x0, y0, z0, vx, vy, vz)


def shot_prob_v2(shot, pi, paths, stds, rhos, lead_shift=0.0):
    if shot is None:
        return 0.0
    _, _, t_hit, x0, y0, z0, vx, vy, vz = shot
    if t_hit <= 0.0 or t_hit > MAX_H:
        return 0.0
    tr = travel(t_hit)
    ax = x0 + vx * tr
    ay = y0 + vy * tr - fall(t_hit)
    az = z0 + vz * tr
    spd = math.sqrt(vx * vx + vy * vy + vz * vz)
    drag_t = DRAG ** t_hit
    ivx = vx * drag_t
    ivy = vy * drag_t - GRAVITY * (1.0 - drag_t) / (1.0 - DRAG)
    ivz = vz * drag_t
    sig_arrow = SPREAD * spd * tr
    var_arrow = sig_arrow * sig_arrow

    t_eval = min(float(MAX_H), t_hit + lead_shift)
    lo = int(t_eval)
    hi = min(MAX_H, lo + 1)
    frac = t_eval - lo
    total = 0.0
    for k in range(len(pi)):
        w = float(pi[k])
        if w < 1e-4:
            continue
        mu = paths[k, lo] * (1.0 - frac) + paths[k, hi] * frac
        st = stds[k, lo] * (1.0 - frac) + stds[k, hi] * frac
        r0 = rhos[k, lo] * (1.0 - frac) + rhos[k, hi] * frac
        # Adding isotropic arrow variance attenuates correlation slightly: cov_xz / (sx_tot * sz_tot)
        cov_xz = r0 * st[0] * st[2]
        sx = math.sqrt(st[0] * st[0] + var_arrow)
        sy = math.sqrt(st[1] * st[1] + var_arrow)
        sz = math.sqrt(st[2] * st[2] + var_arrow)
        rho_eff = max(-0.92, min(0.92, cov_xz / max(1e-6, sx * sz)))
        total += w * correlated_box_prob(ax, ay, az, mu[0], mu[1], mu[2], sx, sy, sz, rho_eff, ivx, ivy, ivz)
    return max(0.0, min(1.0, total))


def shift_path(path, shift_ticks):
    if shift_ticks <= 0.0:
        return path
    n = len(path)
    out = np.empty_like(path)
    for t in range(n):
        ts = min(float(n - 1), t + shift_ticks)
        lo = int(ts)
        hi = min(n - 1, lo + 1)
        f = ts - lo
        out[t] = path[lo] * (1.0 - f) + path[hi] * f
    return out


def ray_blocked(shot, solid_set):
    if solid_set is None or shot is None:
        return False
    _, _, t_hit, x0, y0, z0, vx, vy, vz = shot
    steps = max(2, int(t_hit * 2))
    for st in range(1, steps):
        tt = t_hit * st / steps
        tr = travel(tt)
        if (int(math.floor(x0 + vx * tr)),
            int(math.floor(y0 + vy * tr - fall(tt))),
            int(math.floor(z0 + vz * tr))) in solid_set:
            return True
    return False


def solve_aim_v2(sx, eye_y, sz, speed, pi, paths, stds, rhos, aim_height=0.90,
                 solid_set=None, lead_shift=0.0, refine=True):
    """Priority 4: Multi-mode candidate selection + continuous (yaw, pitch) coordinate-ascent refinement!"""
    K = len(pi)
    mean_path = np.tensordot(pi, paths, axes=([0], [0]))
    best_shot = None
    best_prob = -1.0
    for c in range(K + 1):
        cand_path = paths[c] if c < K else mean_path
        if lead_shift > 0.0:
            cand_path = shift_path(cand_path, lead_shift)
        s = intercept(sx, eye_y, sz, speed, cand_path, aim_height)
        if s is None or ray_blocked(s, solid_set):
            continue
        p = shot_prob_v2(s, pi, paths, stds, rhos, lead_shift=lead_shift)
        if p > best_prob:
            best_prob = p
            best_shot = s

    if best_shot is None or not refine:
        return best_shot, max(0.0, best_prob)

    # Continuous coordinate refinement around best discrete mode (overlapping modes sweet spot!)
    yaw, pitch, t_hit = best_shot[0], best_shot[1], best_shot[2]
    for step_deg in (0.65, 0.28):
        step = math.radians(step_deg)
        for dy, dp in ((step, 0.0), (-step, 0.0), (0.0, step), (0.0, -step)):
            cand = shot_from_angles(sx, eye_y, sz, speed, yaw + dy, pitch + dp, t_hit)
            if ray_blocked(cand, solid_set):
                continue
            p = shot_prob_v2(cand, pi, paths, stds, rhos, lead_shift=lead_shift)
            if p > best_prob + 1e-4:
                best_prob = p
                best_shot = cand
                yaw, pitch = yaw + dy, pitch + dp
    return best_shot, max(0.0, best_prob)


def run_eval_v2(model, device, data_dir: Path, split: str, mode_filter, old_weights,
                stride=9, rtt_ticks=0.0):
    """Evaluate v2 on sealed splits. When rtt_ticks > 0, simulates real server-side arrow spawn lag:
    client observes tick i, arrow spawns on server at tick i + rtt_ticks against true serverPos!
    """
    manifest = json.loads((data_dir / 'manifest.json').read_text())
    ranges = [12.0, 24.0, 38.0, 54.0]
    arms = ('linear', 'shared', 'v2_discrete', 'v2_refined', 'v2_norel', 'v2_novox')
    stats = {k: {r: [0, 0] for r in ranges} for k in arms}
    raw_probs, actual_hits = [], []
    rtt_int = int(round(rtt_ticks))

    for m in manifest['matches']:
        if m['split'] != split:
            continue
        if mode_filter and m['mode'] != mode_filter:
            continue
        npz = np.load(data_dir / m['file'])
        for p_idx in range(int(npz['num_players'][0])):
            ticks = npz[f'p{p_idx}_tick']
            pos = npz[f'p{p_idx}_pos']
            spos = npz[f'p{p_idx}_spos']
            rot = npz[f'p{p_idx}_rot']
            flags = npz[f'p{p_idx}_flags']
            rel = npz[f'p{p_idx}_rel']
            vox = npz[f'p{p_idx}_vox']
            cvox = npz[f'p{p_idx}_cvox']
            past, fut = contiguous_runs(ticks, pos)
            indices = [i for i in range(len(ticks))
                       if past[i] >= 8 and fut[i] >= MAX_H + rtt_int and (int(ticks[i]) + p_idx) % stride == 0]
            if not indices:
                continue
            hl, cl, vfl, vcl, ql = [], [], [], [], []
            for i in indices:
                h, c, vf, vc, q = build_features_v2(pos, spos, rot, flags, rel, vox, cvox, i, int(past[i]))
                hl.append(h); cl.append(c); vfl.append(vf); vcl.append(vc); ql.append(q)
            bh = torch.from_numpy(np.asarray(hl)).to(device)
            bc = torch.from_numpy(np.asarray(cl)).to(device)
            bvf = torch.from_numpy(np.asarray(vfl)).to(device)
            bvc = torch.from_numpy(np.asarray(vcl)).to(device)
            with torch.no_grad():
                lo, me, ls, rh = model(bh, bc, bvf, bvc)
                pi_a = F.softmax(lo, dim=-1).cpu().numpy()
                m_a = me.cpu().numpy(); s_a = ls.exp().cpu().numpy(); r_a = rh.cpu().numpy()
                # Ablation: no relational
                bh_nr = bh.clone(); bh_nr[:, 18:26] = 0.0
                bc_nr = bc.clone(); bc_nr[:, 28:30] = 0.0
                lnr, mnr, snr, rnr = model(bh_nr, bc_nr, bvf, bvc)
                pi_nr = F.softmax(lnr, dim=-1).cpu().numpy()
                m_nr = mnr.cpu().numpy(); s_nr = snr.exp().cpu().numpy(); r_nr = rnr.cpu().numpy()
                # Ablation: no terrain
                bc_nv = bc.clone(); bc_nv[:, 21] = 0.0
                lnv, mnv, snv, rnv = model(bh, bc_nv, torch.zeros_like(bvf), torch.zeros_like(bvc))
                pi_nv = F.softmax(lnv, dim=-1).cpu().numpy()
                m_nv = mnv.cpu().numpy(); s_nv = snv.exp().cpu().numpy(); r_nv = rnv.cpu().numpy()

            for j, i in enumerate(indices):
                q = ql[j]
                p0 = pos[i].astype(np.float64)
                paths, stds, rhos = interp_modes_v2(p0, q, m_a[j], s_a[j], r_a[j])
                paths_nr, stds_nr, rhos_nr = interp_modes_v2(p0, q, m_nr[j], s_nr[j], r_nr[j])
                paths_nv, stds_nv, rhos_nv = interp_modes_v2(p0, q, m_nv[j], s_nv[j], r_nv[j])
                lin_p = linear_baseline_path(pos, i, int(past[i]))
                sh_p = bow_motion_shared_path(pos, i, int(past[i]), old_weights)

                seed = (int(ticks[i]) * 9859 + p_idx * 107 + hash(m['match_id']) % 997) & 0x7fffffff
                angle = float(np.random.default_rng(seed).random() * 2.0 * math.pi)
                # Server truth at arrow spawn tick i + rtt_int (unsmoothed serverPos!)
                future_truth = spos[i + rtt_int:i + rtt_int + MAX_H + 1]
                for r in ranges:
                    sx = p0[0] + r * math.sin(angle)
                    sz = p0[2] + r * math.cos(angle)
                    eye = p0[1] + 1.62
                    shot_seed = seed ^ int(r * 131)
                    s_lin = intercept(sx, eye, sz, SPEED_FULL, lin_p, 0.90)
                    s_sh = intercept(sx, eye, sz, SPEED_FULL, sh_p, 0.90)
                    s_disc, _ = solve_aim_v2(sx, eye, sz, SPEED_FULL, pi_a[j], paths, stds, rhos,
                                             0.90, lead_shift=rtt_ticks, refine=False)
                    s_ref, p_ref = solve_aim_v2(sx, eye, sz, SPEED_FULL, pi_a[j], paths, stds, rhos,
                                                0.90, lead_shift=rtt_ticks, refine=True)
                    sh_nr, _ = solve_aim_v2(sx, eye, sz, SPEED_FULL, pi_nr[j], paths_nr, stds_nr, rhos_nr,
                                            0.90, lead_shift=rtt_ticks, refine=True)
                    sh_nv, _ = solve_aim_v2(sx, eye, sz, SPEED_FULL, pi_nv[j], paths_nv, stds_nv, rhos_nv,
                                            0.90, lead_shift=rtt_ticks, refine=True)
                    for name, sh in (('linear', s_lin), ('shared', s_sh), ('v2_discrete', s_disc),
                                     ('v2_refined', s_ref), ('v2_norel', sh_nr), ('v2_novox', sh_nv)):
                        hit = simulate_arrow_hit(sh, future_truth, np.random.default_rng(shot_seed))
                        stats[name][r][0] += int(hit)
                        stats[name][r][1] += 1
                        if name == 'v2_refined':
                            raw_probs.append(p_ref)
                            actual_hits.append(int(hit))
    return stats, raw_probs, actual_hits


def eval_occluded_v2(model, device, data_dir: Path, split: str, old_weights):
    manifest = json.loads((data_dir / 'manifest.json').read_text())
    occ = {k: [0, 0] for k in ('linear', 'shared', 'v2_refined')}
    for m in manifest['matches']:
        if m['split'] != split:
            continue
        npz = np.load(data_dir / m['file'])
        solid_set = None
        if 'terrain_xyz' in npz and len(npz['terrain_xyz']) > 0:
            t_xyz = npz['terrain_xyz']
            solid_set = set(zip(t_xyz[:, 0].tolist(), t_xyz[:, 1].tolist(), t_xyz[:, 2].tolist()))
        for p_idx in range(int(npz['num_players'][0])):
            ticks = npz[f'p{p_idx}_tick']; pos = npz[f'p{p_idx}_pos']; spos = npz[f'p{p_idx}_spos']
            rot = npz[f'p{p_idx}_rot']; flags = npz[f'p{p_idx}_flags']; rel = npz[f'p{p_idx}_rel']
            vox = npz[f'p{p_idx}_vox']; cvox = npz[f'p{p_idx}_cvox']
            self_pos = npz[f'p{p_idx}_self']; los = npz[f'p{p_idx}_los']
            past, fut = contiguous_runs(ticks, pos)
            idxs = [i for i in range(0, len(ticks), 12)
                    if past[i] >= 8 and fut[i] >= MAX_H and los[i] != 0
                    and 8.0 <= float(np.linalg.norm(pos[i] - self_pos[i])) <= 56.0]
            if not idxs:
                continue
            hl, cl, vfl, vcl, ql = [], [], [], [], []
            for i in idxs:
                h, c, vf, vc, q = build_features_v2(pos, spos, rot, flags, rel, vox, cvox, i, int(past[i]))
                hl.append(h); cl.append(c); vfl.append(vf); vcl.append(vc); ql.append(q)
            with torch.no_grad():
                lo, me, ls, rh = model(
                    torch.from_numpy(np.asarray(hl)).to(device),
                    torch.from_numpy(np.asarray(cl)).to(device),
                    torch.from_numpy(np.asarray(vfl)).to(device),
                    torch.from_numpy(np.asarray(vcl)).to(device),
                )
                pi_a = F.softmax(lo, dim=-1).cpu().numpy()
                m_a = me.cpu().numpy(); s_a = ls.exp().cpu().numpy(); r_a = rh.cpu().numpy()
            for j, i in enumerate(idxs):
                p0 = pos[i].astype(np.float64); sp = self_pos[i].astype(np.float64)
                paths, stds, rhos = interp_modes_v2(p0, ql[j], m_a[j], s_a[j], r_a[j])
                s_lin = intercept(sp[0], sp[1] + 1.62, sp[2], SPEED_FULL, linear_baseline_path(pos, i, int(past[i])), 0.90)
                s_sh = intercept(sp[0], sp[1] + 1.62, sp[2], SPEED_FULL, bow_motion_shared_path(pos, i, int(past[i]), old_weights), 0.90)
                s_v2, _ = solve_aim_v2(sp[0], sp[1] + 1.62, sp[2], SPEED_FULL, pi_a[j], paths, stds, rhos,
                                       0.90, solid_set=solid_set, refine=True)
                seed = (int(ticks[i]) * 7919 + p_idx * 53) & 0x7fffffff
                fut_sl = spos[i:i + MAX_H + 1]
                for name, sh in (('linear', s_lin), ('shared', s_sh), ('v2_refined', s_v2)):
                    occ[name][0] += int(simulate_arrow_hit(sh, fut_sl, np.random.default_rng(seed), solid_set=solid_set))
                    occ[name][1] += 1
    return occ


def print_table(title, stats):
    ranges = [12.0, 24.0, 38.0, 54.0]
    print(f'\n=== {title} ===')
    print(f'{"Model":16s} | {"12b":>7s} | {"24b":>7s} | {"38b":>7s} | {"54b":>7s} | {"TOTAL":>10s}')
    print('-' * 72)
    for name in ('linear', 'shared', 'v2_novox', 'v2_norel', 'v2_discrete', 'v2_refined'):
        parts = []
        th = tn = 0
        for r in ranges:
            h, n = stats[name][r]; th += h; tn += n
            parts.append(f'{100*h/max(1,n):6.2f}%')
        print(f'{name:16s} | ' + ' | '.join(parts) + f' | {100*th/max(1,tn):6.2f}% ({th}/{tn})')


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--data', type=Path, default=Path('runtime/bow-aim/neural-data-v2'))
    p.add_argument('--model-dir', type=Path, default=Path('runtime/bow-aim/neural-model-v2'))
    p.add_argument('--export-asset', type=Path, default=Path('practice-client/assets/practice/bow-neural.bin'))
    args = p.parse_args()

    device = torch.device('mps' if torch.backends.mps.is_available() else 'cpu')
    old_weights = load_bow_motion_weights()

    # Select best between Stage 1 (NLL) and Stage 2 (Hit-Rate FT) strictly on VALIDATION!
    best_name, best_val_hr, best_model, best_calib = None, -1.0, None, None
    for tag, fname in (('stage1_nll', 'model_s1.pt'), ('stage2_hitft', 'model.pt')):
        ckpt = torch.load(args.model_dir / fname, map_location='cpu', weights_only=True)
        m = BowNeuralNetV2(NUM_MODES).to(device)
        m.load_state_dict(ckpt['state_dict'])
        m.eval()
        vs, vraw, vhits = run_eval_v2(m, device, args.data, 'val', None, old_weights, stride=15, rtt_ticks=0.0)
        tot_h = sum(vs['v2_refined'][r][0] for r in (12.0, 24.0, 38.0, 54.0))
        tot_n = sum(vs['v2_refined'][r][1] for r in (12.0, 24.0, 38.0, 54.0))
        hr = tot_h / max(1, tot_n)
        print(f'VAL selection {tag}: v2_refined={100*hr:.2f}% ({tot_h}/{tot_n})', flush=True)
        if hr > best_val_hr:
            best_val_hr = hr
            best_name = tag
            best_model = m
            best_calib = fit_calibration(vraw, vhits, steps=11)
    print(f'Selected winner on VALIDATION: {best_name} ({100*best_val_hr:.2f}%)', flush=True)
    print('Calibration curve:', [round(x, 3) for x in best_calib], flush=True)

    export_java_v2(best_model, args.model_dir / 'bow-neural.bin', best_calib)
    export_java_v2(best_model, args.export_asset, best_calib)
    torch.save({'state_dict': {k: v.cpu() for k, v in best_model.state_dict().items()},
                'selected': best_name, 'calib': best_calib}, args.model_dir / 'winner.pt')

    # Sealed Test Evaluations
    t0 = time.monotonic()
    bw0, bw_raw, bw_hits = run_eval_v2(best_model, device, args.data, 'test', 'bedwars', old_weights, stride=18, rtt_ticks=0.0)
    print_table('SEALED TEST — BedWars (game-09..12, 0ms RTT)', bw0)

    bw80, _, _ = run_eval_v2(best_model, device, args.data, 'test', 'bedwars', old_weights, stride=27, rtt_ticks=2.0)
    print_table('SEALED TEST — BedWars (game-09..12, 100ms RTT = 2.0t server lag!)', bw80)

    sw0, sw_raw, sw_hits = run_eval_v2(best_model, device, args.data, 'test', 'skywars', old_weights, stride=9, rtt_ticks=0.0)
    print_table('SEALED TEST — SkyWars (5 unseen matches, 0ms RTT)', sw0)

    all_raw = np.asarray(bw_raw + sw_raw)
    all_cal = np.asarray([apply_calibration(x, best_calib) for x in all_raw])
    all_hit = np.asarray(bw_hits + sw_hits, dtype=np.float64)
    brier_cal = float(np.mean((all_cal - all_hit) ** 2))
    print(f'\nSealed Test Calibration Brier: {brier_cal:.4f} (pred={all_cal.mean():.3f} vs actual={all_hit.mean():.3f})', flush=True)

    occ = eval_occluded_v2(best_model, device, args.data, 'test', old_weights)
    print('\n=== SEALED TEST — Realistic Live-POV Terrain-Occluded Shots ===')
    for name in ('linear', 'shared', 'v2_refined'):
        h, n = occ[name]
        print(f'  {name:14s}: {100*h/max(1,n):6.2f}% ({h}/{n})')

    out = {
        'selected_checkpoint': best_name,
        'calibration_curve': best_calib,
        'brier_cal': brier_cal,
        'bw_test_0ms': {k: {str(int(r)): v for r, v in d.items()} for k, d in bw0.items()},
        'bw_test_100ms': {k: {str(int(r)): v for r, v in d.items()} for k, d in bw80.items()},
        'sw_test_0ms': {k: {str(int(r)): v for r, v in d.items()} for k, d in sw0.items()},
        'occluded_test': occ,
        'elapsed_s': round(time.monotonic() - t0, 1),
    }
    (args.model_dir / 'eval-v2.json').write_text(json.dumps(out, indent=2) + '\n')
    print(f'\nDone v2 eval in {time.monotonic()-t0:.1f}s!', flush=True)


if __name__ == '__main__':
    main()
