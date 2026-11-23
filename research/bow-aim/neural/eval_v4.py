"""Evaluate & calibrate BowNeuralNetV4 (BNN4) strictly on the untouched frozen sealed test sets:
1. Sealed BedWars (game-09..12) @ 0ms & 100ms RTT vs true serverPos
2. Sealed SkyWars (5 unseen matches) @ 0ms RTT
3. REAL Sealed Bridging Slice (actual Hypixel ticks where targets place blocks / bridge!)
4. Auxiliary Future-Block-Placement prediction metrics (precision/recall/F1 on real sealed matches)
5. Pure Learned v4 (bridgehack OFF!) on ALL 3 Bridging Stress Tests:
   - Constant sideways bridging (0.08..0.24 b/t, 12..48b, 0..160ms ping)
   - Diagonal-up stair speedbridging (periods 6/7/9t, 12..36b, 0..100ms ping)
   - Variable-speed speedbridging 4-throw snowball volley (0.68x / 1.00x / 1.28x @ 18..34b, 80ms ping)
6. Fit validation-only isotonic calibration curve and export final calibrated bow-neural.bin!
"""
import argparse
import json
import math
from pathlib import Path
import time

import numpy as np
import torch
from torch.nn import functional as F

from train_v4 import (
    BowNeuralNetV4, HORIZONS, NUM_H, MAX_H, NUM_MODES, FINE_Y, FINE_Z, FINE_X, TOP_CH, TOP_S,
    build_features_v4, contiguous_runs, inv_rot_xz, export_java_v4, build_fut_blk_grid,
    simulate_remote_interp, cardinal_from_yaw,
)
from eval_and_calibrate import (
    DRAG, GRAVITY, OFFSET, SPREAD, SPEED_FULL,
    travel, simulate_arrow_hit, fit_calibration, apply_calibration,
)
from eval_v2 import correlated_box_prob, shift_path
from void_and_wait import (
    THROW_SPEED, THROW_GRAVITY, fall_g, intercept_g, shot_from_angles_g,
)


def interp_modes_v4(spos0, q, means_kh3, std_kh3, rho_kh):
    """Interpolate BNN4 modes anchored on unsmoothed serverPos(0) out to MAX_H = 72 ticks!"""
    K = means_kh3.shape[0]
    paths = np.zeros((K, MAX_H + 1, 3), dtype=np.float64)
    stds = np.zeros((K, MAX_H + 1, 3), dtype=np.float64)
    rhos = np.zeros((K, MAX_H + 1), dtype=np.float64)
    paths[:, 0, :] = spos0
    stds[:, 0, :] = 0.05

    anc_pos = np.zeros((K, NUM_H + 1, 3), dtype=np.float64)
    anc_std = np.zeros((K, NUM_H + 1, 3), dtype=np.float64)
    anc_rho = np.zeros((K, NUM_H + 1), dtype=np.float64)
    anc_pos[:, 0, :] = spos0
    anc_std[:, 0, :] = 0.05
    for k in range(K):
        for hi in range(NUM_H):
            rx, ry, rz = means_kh3[k, hi]
            wx, wz = inv_rot_xz(rx, rz, q)
            anc_pos[k, hi + 1] = [spos0[0] + wx, spos0[1] + ry, spos0[2] + wz]
            sx, sy, sz = std_kh3[k, hi]
            r = float(rho_kh[k, hi])
            if q in (1, 3):
                anc_std[k, hi + 1] = [sz, sy, sx]
                anc_rho[k, hi + 1] = -r
            else:
                anc_std[k, hi + 1] = [sx, sy, sz]
                anc_rho[k, hi + 1] = r

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


def mode_probs_v4(shot, pi, paths, stds, rhos, lead_shift=0.0):
    K = len(pi)
    out = np.zeros(K, dtype=np.float64)
    if shot is None:
        return out
    _, _, t_hit, x0, y0, z0, vx, vy, vz, gravity = shot
    if t_hit <= 0.0 or t_hit > MAX_H:
        return out
    tr = travel(t_hit)
    ax = x0 + vx * tr
    ay = y0 + vy * tr - fall_g(t_hit, gravity)
    az = z0 + vz * tr
    spd = math.sqrt(vx * vx + vy * vy + vz * vz)
    drag_t = DRAG ** t_hit
    ivx = vx * drag_t
    ivy = vy * drag_t - gravity * (1.0 - drag_t) / (1.0 - DRAG)
    ivz = vz * drag_t
    var_p = (SPREAD * spd * tr) ** 2

    t_eval = min(float(MAX_H), t_hit + max(0.0, lead_shift))
    lo = int(t_eval); hi = min(MAX_H, lo + 1); frac = t_eval - lo
    for k in range(K):
        if pi[k] < 1e-4:
            continue
        mu = paths[k, lo] * (1.0 - frac) + paths[k, hi] * frac
        st = stds[k, lo] * (1.0 - frac) + stds[k, hi] * frac
        r0 = rhos[k, lo] * (1.0 - frac) + rhos[k, hi] * frac
        cov_xz = r0 * st[0] * st[2]
        sx = math.sqrt(st[0] * st[0] + var_p)
        sy = math.sqrt(st[1] * st[1] + var_p)
        sz = math.sqrt(st[2] * st[2] + var_p)
        rho_eff = max(-0.92, min(0.92, cov_xz / max(1e-6, sx * sz)))
        out[k] = correlated_box_prob(ax, ay, az, mu[0], mu[1], mu[2], sx, sy, sz, rho_eff, ivx, ivy, ivz)
    return out


def mode_prob_at_v4(shot, paths, stds, rhos, k, eval_tick):
    if shot is None or eval_tick <= 0.0 or eval_tick > MAX_H:
        return 0.0
    _, _, t_hit, x0, y0, z0, vx, vy, vz, gravity = shot
    if t_hit <= 0.0 or t_hit > MAX_H:
        return 0.0
    tr = travel(t_hit)
    ax = x0 + vx * tr
    ay = y0 + vy * tr - fall_g(t_hit, gravity)
    az = z0 + vz * tr
    spd = math.sqrt(vx * vx + vy * vy + vz * vz)
    drag_t = DRAG ** t_hit
    ivx = vx * drag_t
    ivy = vy * drag_t - gravity * (1.0 - drag_t) / (1.0 - DRAG)
    ivz = vz * drag_t
    var_p = (SPREAD * spd * tr) ** 2

    lo = int(eval_tick); hi = min(MAX_H, lo + 1); frac = eval_tick - lo
    mu = paths[k, lo] * (1.0 - frac) + paths[k, hi] * frac
    st = stds[k, lo] * (1.0 - frac) + stds[k, hi] * frac
    r0 = rhos[k, lo] * (1.0 - frac) + rhos[k, hi] * frac
    cov_xz = r0 * st[0] * st[2]
    sx = math.sqrt(st[0] * st[0] + var_p)
    sy = math.sqrt(st[1] * st[1] + var_p)
    sz = math.sqrt(st[2] * st[2] + var_p)
    rho_eff = max(-0.92, min(0.92, cov_xz / max(1e-6, sx * sz)))
    return correlated_box_prob(ax, ay, az, mu[0], mu[1], mu[2], sx, sy, sz, rho_eff, ivx, ivy, ivz)


def solve_aim_v4(sx, eye_y, sz, speed, gravity, pi, paths, stds, rhos,
                 eff_weights=None, aim_height=0.90, lead_shift=0.0, refine=True):
    K = len(pi)
    w_eff = pi if eff_weights is None else eff_weights
    mean_p = np.tensordot(pi, paths, axes=([0], [0]))
    best_shot = None; best_p = -1.0
    for c in range(K + 1):
        cand_p = paths[c] if c < K else mean_p
        if lead_shift > 0.0:
            cand_p = shift_path(cand_p, lead_shift)
        s = intercept_g(sx, eye_y, sz, speed, cand_p, aim_height, gravity)
        if s is None:
            continue
        p = float(np.sum(w_eff * mode_probs_v4(s, pi, paths, stds, rhos, lead_shift)))
        if p > best_p:
            best_p = p; best_shot = s
    if best_shot is not None and refine:
        yaw, pitch, t_hit = best_shot[0], best_shot[1], best_shot[2]
        for step_deg in (0.65, 0.28):
            step = math.radians(step_deg)
            for dy, dp in ((step, 0.0), (-step, 0.0), (0.0, step), (0.0, -step)):
                cand = shot_from_angles_g(sx, eye_y, sz, speed, yaw + dy, pitch + dp, t_hit, gravity)
                p = float(np.sum(w_eff * mode_probs_v4(cand, pi, paths, stds, rhos, lead_shift)))
                if p > best_p + 1e-4:
                    best_p = p; best_shot = cand; yaw += dy; pitch += dp
    return best_shot, max(0.0, best_p)


def eval_split_v4(model, device, data_dir: Path, split: str, mode_filter=None, stride=10, rtt_ticks=0.0):
    manifest = json.loads((data_dir / 'manifest.json').read_text())
    ranges = [12.0, 24.0, 38.0, 54.0]
    stats = {r: [0, 0] for r in ranges}
    bridge_stats = [0, 0]  # real ticks where player is placing blocks / bridging!
    raw_probs, actual_hits = [], []
    fblk_tp = fblk_fp = fblk_fn = 0
    rtt_int = int(round(rtt_ticks))
    rng = np.random.default_rng(42)

    for m in manifest['matches']:
        if m['split'] != split:
            continue
        if mode_filter and m['mode'] != mode_filter:
            continue
        npz = np.load(data_dir / m['file'])
        pl = npz['place_log'] if 'place_log' in npz else np.empty((0, 4), dtype=np.int32)
        if len(pl) > 1:
            pl = pl[np.argsort(pl[:, 0])]

        for p_idx in range(int(npz['num_players'][0])):
            ticks = npz[f'p{p_idx}_tick']
            pos = npz[f'p{p_idx}_pos']
            spos = npz[f'p{p_idx}_spos']
            rot = npz[f'p{p_idx}_rot']
            flags = npz[f'p{p_idx}_flags']
            rel = npz[f'p{p_idx}_rel']
            blk4 = npz[f'p{p_idx}_blk4']
            vox = npz[f'p{p_idx}_vox']
            pvox = npz[f'p{p_idx}_pvox']
            top = npz[f'p{p_idx}_top']
            past, fut = contiguous_runs(ticks, pos)
            indices = [i for i in range(len(ticks))
                       if past[i] >= 8 and fut[i] >= 42 + rtt_int and (int(ticks[i]) + p_idx) % stride == 0]
            if not indices:
                continue

            hl, cl, vbl, f3l, t2l, ql = [], [], [], [], [], []
            for i in indices:
                h, c, vb, f3, t2, q = build_features_v4(pos, spos, rot, flags, rel, blk4, vox, pvox, top, i, int(past[i]))
                hl.append(h); cl.append(c); vbl.append(vb); f3l.append(f3); t2l.append(t2); ql.append(q)

            n_tot = len(indices)
            pi_a = np.empty((n_tot, NUM_MODES), dtype=np.float32)
            m_a = np.empty((n_tot, NUM_MODES, NUM_H, 3), dtype=np.float32)
            s_a = np.empty((n_tot, NUM_MODES, NUM_H, 3), dtype=np.float32)
            r_a = np.empty((n_tot, NUM_MODES, NUM_H), dtype=np.float32)
            fb_a = np.empty((n_tot, 245), dtype=np.float32)

            for b0 in range(0, n_tot, 256):
                b1 = min(n_tot, b0 + 256)
                bh = torch.from_numpy(np.asarray(hl[b0:b1])).to(device)
                bc = torch.from_numpy(np.asarray(cl[b0:b1])).to(device)
                bvb = torch.from_numpy(np.asarray(vbl[b0:b1])).to(device)
                bf3 = torch.from_numpy(np.asarray(f3l[b0:b1])).to(device)
                bt2 = torch.from_numpy(np.asarray(t2l[b0:b1])).to(device)
                with torch.no_grad():
                    lo, me, ls, rh, fbl = model(bh, bc, bvb, bf3, bt2)
                    pi_a[b0:b1] = F.softmax(lo, dim=-1).cpu().numpy()
                    m_a[b0:b1] = me.cpu().numpy()
                    s_a[b0:b1] = ls.exp().cpu().numpy()
                    r_a[b0:b1] = rh.cpu().numpy()
                    fb_a[b0:b1] = torch.sigmoid(fbl).cpu().numpy()

            for k_i, idx in enumerate(indices):
                q = ql[k_i]
                paths, stds, rhos = interp_modes_v4(spos[idx], q, m_a[k_i], s_a[k_i], r_a[k_i])
                pi = pi_a[k_i]
                is_real_bridge = (cl[k_i][30] > 0.05) or ((int(flags[idx]) & 2) != 0 and float(rot[idx, 1]) >= 34.0)

                # Auxiliary future-block placement metrics on real sealed matches
                if len(pl) > 0:
                    bx, by, bz = int(math.floor(pos[idx, 0])), int(math.floor(pos[idx, 1])), int(math.floor(pos[idx, 2]))
                    gt_fb = build_fut_blk_grid(pl, int(ticks[idx]), bx, by, bz, q).reshape(-1) > 0
                    pr_fb = fb_a[k_i] > 0.5
                    fblk_tp += int(np.sum(gt_fb & pr_fb))
                    fblk_fp += int(np.sum((~gt_fb) & pr_fb))
                    fblk_fn += int(np.sum(gt_fb & (~pr_fb)))

                p0 = spos[idx]
                ang = float(rng.uniform(0, 2 * math.pi))
                for rng_d in ranges:
                    sx = float(p0[0] + math.cos(ang) * rng_d)
                    sz = float(p0[2] + math.sin(ang) * rng_d)
                    eye_y = float(p0[1] + 1.62 + rng.uniform(-2.0, 2.0))
                    shot, prob = solve_aim_v4(sx, eye_y, sz, SPEED_FULL, GRAVITY, pi, paths, stds, rhos,
                                              lead_shift=rtt_ticks, refine=True)
                    hit = 0
                    if shot is not None:
                        # simulate_arrow_hit expects (shot[:9], future_pos_from_launch, rng)
                        hit = 1 if simulate_arrow_hit(shot[:9], spos[idx + rtt_int:idx + rtt_int + 43], rng) else 0
                    stats[rng_d][0] += hit; stats[rng_d][1] += 1
                    raw_probs.append(prob); actual_hits.append(hit)
                    if is_real_bridge:
                        bridge_stats[0] += hit; bridge_stats[1] += 1

    tot_h = sum(v[0] for v in stats.values()); tot_n = sum(v[1] for v in stats.values())
    fblk_prec = fblk_tp / max(1, fblk_tp + fblk_fp)
    fblk_rec = fblk_tp / max(1, fblk_tp + fblk_fn)
    return {
        'by_range': {r: v[0] / max(1, v[1]) for r, v in stats.items()},
        'total_hit': tot_h / max(1, tot_n),
        'total_shots': tot_n,
        'real_bridge_hit': bridge_stats[0] / max(1, bridge_stats[1]),
        'real_bridge_shots': bridge_stats[1],
        'fblk_prec': fblk_prec,
        'fblk_rec': fblk_rec,
        'raw_probs': np.asarray(raw_probs, dtype=np.float64),
        'actual_hits': np.asarray(actual_hits, dtype=np.float64),
    }


def eval_pure_nn_bridging_stress(model, device):
    """Test PURE LEARNED BowNeuralNetV4 (bridgehack OFF!) on the EXACT BowBridgingChecks stress suite!"""
    def make_sim(kind, speed_xz, stair_p, total_t, launch_idx, post_mult=1.0):
        pos = np.zeros((total_t, 3), dtype=np.float32)
        rot = np.zeros((total_t, 2), dtype=np.float32)
        flags = np.zeros(total_t, dtype=np.int32)
        rel = np.zeros((total_t, 10), dtype=np.float32)
        blk4 = np.zeros((total_t, 4), dtype=np.float32)
        solid = {}
        for ix in range(-6, 1):
            for iz in range(-3, 4):
                solid[(ix, 63, iz)] = -999
        x, y, z = 0.5, 64.0, 0.5
        ux, uz = (0.70710678, 0.70710678) if kind == 'diag_stair_up' else (1.0, 0.0)
        step_base = 64.0
        jump_arc = [0.0, 0.42, 0.75, 0.99, 1.12, 1.05, 1.00, 1.00, 1.00]
        places = []
        for t in range(total_t):
            sm = post_mult if t > launch_idx else 1.0
            if kind == 'const_side':
                rot[t] = [135.0, 78.0]; flags[t] = 1 | 2
                x += speed_xz * sm * ux; z += speed_xz * sm * uz
                bx, bz = int(math.floor(x)), int(math.floor(z))
                if (bx, 63, bz) not in solid:
                    solid[(bx, 63, bz)] = t; places.append((t, bx, 63, bz))
            elif kind == 'speedbridge_var':
                ph = t % 7; sn = ph >= 5
                cad = 0.45 if sn else (1.18 + 0.18 * math.sin(t * 0.17))
                rot[t] = [135.0, 77.0]; flags[t] = (1 if sn else 0) | 2
                x += speed_xz * sm * cad * ux; z += speed_xz * sm * cad * uz
                bx, bz = int(math.floor(x)), int(math.floor(z))
                if (bx, 63, bz) not in solid:
                    solid[(bx, 63, bz)] = t; places.append((t, bx, 63, bz))
            else:
                ph = t % stair_p; sn = ph >= stair_p - 2
                cad = 0.50 if sn else 1.22
                rot[t] = [135.0, 76.0]; flags[t] = (1 if sn else 0) | 2
                x += speed_xz * sm * cad * ux; z += speed_xz * sm * cad * uz
                if ph == 0 and t > 0: step_base += 1.0
                y = step_base + jump_arc[min(ph, len(jump_arc) - 1)]
                bx, bz = int(math.floor(x)), int(math.floor(z))
                by = int(math.floor(step_base)) - 1
                if (bx, by, bz) not in solid:
                    solid[(bx, by, bz)] = t; places.append((t, bx, by, bz))
                if ph >= stair_p - 3 and (bx, by + 1, bz) not in solid:
                    solid[(bx, by + 1, bz)] = t; places.append((t, bx, by + 1, bz))
            pos[t] = [x, y, z]
            bx, by, bz = int(math.floor(x)), int(math.floor(y)), int(math.floor(z))
            cnt = 0; sdx = sdy = sdz = 0.0
            for (pt, px_, py_, pz_) in places[-12:]:
                if 0 <= t - pt <= 10 and abs(px_ - bx) <= 3 and abs(pz_ - bz) <= 3 and abs(py_ - by) <= 2:
                    cnt += 1; sdx += px_ - bx; sdy += py_ - by; sdz += pz_ - bz
            if cnt > 0:
                blk4[t] = [min(1.0, cnt / 4.0), sdx / cnt, sdy / cnt, sdz / cnt]
        cpos, spos = simulate_remote_interp(pos)
        return cpos, spos, rot, flags, rel, blk4, solid

    def predict_at(cpos, spos, rot, flags, rel, blk4, solid, idx):
        bx, by, bz = int(math.floor(cpos[idx, 0])), int(math.floor(cpos[idx, 1])), int(math.floor(cpos[idx, 2]))
        fs = np.zeros((FINE_Y, FINE_Z, FINE_X), dtype=np.uint8)
        fp = np.zeros((FINE_Y, FINE_Z, FINE_X), dtype=np.uint8)
        top = np.zeros((TOP_CH, TOP_S, TOP_S), dtype=np.int8)
        top[0] = -127; top[2] = 100
        hi_y = np.full((TOP_S, TOP_S), -999, dtype=np.int32)
        for (wx, wy, wz), pt in solid.items():
            if pt > idx: continue
            dx, dy, dz = wx - bx, wy - by, wz - bz
            if -12 <= dx <= 12 and -12 <= dz <= 12 and -12 <= dy <= 4:
                tz, tx = dz + 12, dx + 12
                top[2, tz, tx] = 0
                if dy in (1, 2): top[1, tz, tx] = 100
                if -6 <= dy <= 4 and dy > hi_y[tz, tx]:
                    hi_y[tz, tx] = dy; top[0, tz, tx] = int(max(-120, min(100, (dy + 1) * 20)))
                if pt >= 0 and idx - pt <= 120 and -4 <= dy <= 3:
                    st = int(round(100.0 * math.exp(-(idx - pt) / 60.0)))
                    if st > top[3, tz, tx]: top[3, tz, tx] = st
                    if -4 <= dx <= 4 and -4 <= dz <= 4 and -3 <= dy <= 3:
                        fp[dy + 3, dz + 4, dx + 4] = 1
            if -4 <= dx <= 4 and -4 <= dz <= 4 and -3 <= dy <= 3:
                fs[dy + 3, dz + 4, dx + 4] = 1
        va = np.zeros((idx + 1, 71), dtype=np.uint8); va[idx] = np.packbits(fs.reshape(-1))
        pva = np.zeros((idx + 1, 71), dtype=np.uint8); pva[idx] = np.packbits(fp.reshape(-1))
        ta = np.zeros((idx + 1, TOP_CH, TOP_S, TOP_S), dtype=np.int8); ta[idx] = top
        h, c, vb, f3, t2, q = build_features_v4(cpos, spos, rot, flags, rel, blk4, va, pva, ta, idx, idx + 1)
        with torch.no_grad():
            lo, me, ls, rh, _ = model(
                torch.from_numpy(h[None]).to(device), torch.from_numpy(c[None]).to(device),
                torch.from_numpy(vb[None]).to(device), torch.from_numpy(f3[None]).to(device),
                torch.from_numpy(t2[None]).to(device),
            )
        pi = F.softmax(lo, dim=-1)[0].cpu().numpy()
        paths, stds, rhos = interp_modes_v4(spos[idx], q, me[0].cpu().numpy(), ls[0].exp().cpu().numpy(), rh[0].cpu().numpy())
        return pi, paths, stds, rhos

    def hits_true(shot, spos, launch, shift):
        if shot is None: return False
        _, _, t_hit, x0, y0, z0, vx, vy, vz, grav = shot
        ping_i = int(round(shift)); tc = int(round(t_hit))
        for dt in range(max(1, tc - 3), min(MAX_H, tc + 3) + 1):
            st_i = launch + ping_i + dt
            if st_i >= len(spos): break
            tr = travel(float(dt))
            ax = x0 + vx * tr; ay = y0 + vy * tr - fall_g(float(dt), grav); az = z0 + vz * tr
            drag_t = DRAG ** dt
            ivx = abs(vx * drag_t); ivy = abs(vy * drag_t - grav * (1.0 - drag_t) / (1.0 - DRAG)); ivz = abs(vz * drag_t)
            tp = spos[st_i]
            if (abs(ax - tp[0]) <= 0.60 + 0.40 * ivx and abs(az - tp[2]) <= 0.60 + 0.40 * ivz
                    and tp[1] - 0.30 - 0.35 * ivy <= ay <= tp[1] + 2.10 + 0.35 * ivy):
                return True
        return False

    c_h = c_n = 0
    for spd in (0.08, 0.14, 0.19, 0.24):
        for dist in (12.0, 24.0, 36.0, 48.0):
            for ping_ms in (0.0, 80.0, 160.0):
                cpos, spos, rot, flags, rel, blk4, solid = make_sim('const_side', spd, 7, 160, 60)
                pi, paths, stds, rhos = predict_at(cpos, spos, rot, flags, rel, blk4, solid, 60)
                sx, eye_y, sz = float(cpos[60, 0]), float(cpos[60, 1]) + 1.62, float(cpos[60, 2]) - dist
                sh, _ = solve_aim_v4(sx, eye_y, sz, THROW_SPEED, THROW_GRAVITY, pi, paths, stds, rhos, lead_shift=ping_ms / 50.0)
                c_n += 1
                if hits_true(sh, spos, 60, ping_ms / 50.0): c_h += 1

    s_h = s_n = 0
    for per in (6, 7, 9):
        for dist in (12.0, 24.0, 36.0):
            for ping_ms in (0.0, 100.0):
                cpos, spos, rot, flags, rel, blk4, solid = make_sim('diag_stair_up', 0.16, per, 160, 60)
                pi, paths, stds, rhos = predict_at(cpos, spos, rot, flags, rel, blk4, solid, 60)
                sx, eye_y, sz = float(cpos[60, 0]), float(cpos[60, 1]) + 1.62, float(cpos[60, 2]) - dist
                sh, _ = solve_aim_v4(sx, eye_y, sz, THROW_SPEED, THROW_GRAVITY, pi, paths, stds, rhos, lead_shift=ping_ms / 50.0)
                s_n += 1
                if hits_true(sh, spos, 60, ping_ms / 50.0): s_h += 1

    v_h = v_n = 0
    for dist in (18.0, 26.0, 34.0):
        for pm in (0.68, 1.00, 1.28):
            shift = 1.6
            volley = []
            any_h = False
            for b in range(4):
                dt = b * 3
                cpos, spos, rot, flags, rel, blk4, solid = make_sim('speedbridge_var', 0.16, 7, 170, 60 + dt, pm)
                pi, paths, stds, rhos = predict_at(cpos, spos, rot, flags, rel, blk4, solid, 60 + dt)
                sx, eye_y, sz = float(spos[60, 0]), float(spos[60, 1]) + 1.62, float(spos[60, 2]) - dist
                eff_w = pi.copy().astype(np.float64)
                for (t_prev, psh) in volley:
                    rem = (t_prev + shift + psh[2]) - (60 + dt)
                    if 0.5 < rem <= MAX_H:
                        for mk in range(NUM_MODES):
                            eff_w[mk] *= max(0.0, 1.0 - mode_prob_at_v4(psh, paths, stds, rhos, mk, rem))
                sh, _ = solve_aim_v4(sx, eye_y, sz, THROW_SPEED, THROW_GRAVITY, pi, paths, stds, rhos,
                                     eff_weights=eff_w, lead_shift=shift)
                if sh is not None:
                    volley.append((60 + dt, sh))
                    if hits_true(sh, spos, 60 + dt, shift): any_h = True
            v_n += 1
            if any_h: v_h += 1

    return {
        'const_side': (c_h, c_n),
        'diag_stair_up': (s_h, s_n),
        'var_volley': (v_h, v_n),
    }


def main():
    p = argparse.ArgumentParser()
    p.add_argument('--data', type=Path, default=Path('runtime/bow-aim/neural-data-v4'))
    p.add_argument('--ckpt', type=Path, default=Path('runtime/bow-aim/neural-model-v4/stage2.pt'))
    p.add_argument('--out-bin', type=Path, default=Path('practice-client/assets/practice/bow-neural.bin'))
    args = p.parse_args()

    t0 = time.time()
    device = torch.device('mps' if torch.backends.mps.is_available() else 'cpu')
    ck = torch.load(args.ckpt, map_location='cpu')
    model = BowNeuralNetV4(NUM_MODES).to(device)
    model.load_state_dict(ck['state_dict'])
    model.eval()

    print('Fitting validation-only calibration curve...', flush=True)
    val_res = eval_split_v4(model, device, args.data, 'val', stride=12, rtt_ticks=0.0)
    cal_curve = fit_calibration(val_res['raw_probs'], val_res['actual_hits'], steps=21)
    print(f'  Val hit={val_res["total_hit"]*100:.2f}%, real-bridge={val_res["real_bridge_hit"]*100:.2f}% '
          f'({val_res["real_bridge_shots"]} shots), fut_blk P={val_res["fblk_prec"]*100:.1f}% R={val_res["fblk_rec"]*100:.1f}%', flush=True)

    # Save winner.pt with calibration curve & export calibrated Java binary
    torch.save({'state_dict': ck['state_dict'], 'calibration': cal_curve}, args.ckpt.parent / 'winner.pt')
    export_java_v4(model, args.out_bin, cal_curve)

    print('\nEvaluating SEALED BedWars (game-09..12, 0ms & 100ms RTT)...', flush=True)
    bw0 = eval_split_v4(model, device, args.data, 'test', mode_filter='bedwars', stride=10, rtt_ticks=0.0)
    bw100 = eval_split_v4(model, device, args.data, 'test', mode_filter='bedwars', stride=14, rtt_ticks=2.0)
    sw0 = eval_split_v4(model, device, args.data, 'test', mode_filter='skywars', stride=10, rtt_ticks=0.0)

    cal_p = np.array([apply_calibration(x, cal_curve) for x in bw0['raw_probs']])
    brier = float(np.mean((cal_p - bw0['actual_hits']) ** 2))

    print(f'  Sealed BedWars 0ms  ({bw0["total_shots"]:,} shots): {bw0["total_hit"]*100:.2f}% | '
          f'real-bridge: {bw0["real_bridge_hit"]*100:.2f}% ({bw0["real_bridge_shots"]}) | '
          f'Brier={brier:.4f} (pred={cal_p.mean()*100:.1f}% vs act={bw0["actual_hits"].mean()*100:.1f}%)', flush=True)
    print(f'  Sealed BedWars 100ms({bw100["total_shots"]:,} shots): {bw100["total_hit"]*100:.2f}% | '
          f'real-bridge: {bw100["real_bridge_hit"]*100:.2f}%', flush=True)
    print(f'  Sealed SkyWars 0ms  ({sw0["total_shots"]:,} shots): {sw0["total_hit"]*100:.2f}% | '
          f'real-bridge: {sw0["real_bridge_hit"]*100:.2f}% ({sw0["real_bridge_shots"]})', flush=True)
    print(f'  Future Block Placement (Sealed BW): Prec={bw0["fblk_prec"]*100:.1f}% Recall={bw0["fblk_rec"]*100:.1f}%', flush=True)

    print('\nEvaluating PURE LEARNED v4 (bridgehack OFF!) on Bridging Stress Suite...', flush=True)
    br = eval_pure_nn_bridging_stress(model, device)
    print(f'  Const sideways (12..48b, 0..160ms):      {br["const_side"][0]}/{br["const_side"][1]} ({100*br["const_side"][0]/br["const_side"][1]:.1f}%)')
    print(f'  Diagonal-up stair (12..36b, 0..100ms):   {br["diag_stair_up"][0]}/{br["diag_stair_up"][1]} ({100*br["diag_stair_up"][0]/br["diag_stair_up"][1]:.1f}%)')
    print(f'  Variable-speed 4-throw volley (80ms):    {br["var_volley"][0]}/{br["var_volley"][1]} ({100*br["var_volley"][0]/br["var_volley"][1]:.1f}%)')

    out = {
        'bw0_hit': bw0['total_hit'], 'bw0_bridge': bw0['real_bridge_hit'], 'bw0_shots': bw0['total_shots'],
        'bw0_by_range': bw0['by_range'], 'brier': brier,
        'bw100_hit': bw100['total_hit'], 'bw100_bridge': bw100['real_bridge_hit'],
        'sw0_hit': sw0['total_hit'], 'sw0_bridge': sw0['real_bridge_hit'], 'sw0_shots': sw0['total_shots'],
        'fblk_prec': bw0['fblk_prec'], 'fblk_rec': bw0['fblk_rec'],
        'pure_nn_bridging': br,
        'elapsed_s': round(time.time() - t0, 1),
    }
    (args.ckpt.parent / 'v4_eval.json').write_text(json.dumps(out, indent=2))
    print(f'\nSaved eval summary in {time.time()-t0:.1f}s!', flush=True)


if __name__ == '__main__':
    main()
