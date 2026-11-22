"""Evaluate BowNeuralNet against exact BowMotion baselines, calibrate P*(hit) on val,
and benchmark Principled Optimal-Stopping (Smart Peak-Spike) Auto-Release on sealed test sets.
"""
import argparse
import json
import math
from pathlib import Path
import struct
import time

import numpy as np
import torch
from torch.nn import functional as F

from train_neural import (
    BowNeuralNet, HORIZONS, NUM_H, MAX_H, NUM_MODES, VOX_DIM,
    build_features_from_track, contiguous_runs, inv_rot_xz, export_java_binary,
)

# Exact 1.8.9 BowBallistics constants
DRAG = 0.99
GRAVITY = 0.05
OFFSET = 0.16
SPREAD = 0.007499999832361937
SPEED_FULL = 3.0
TRAVEL = np.zeros(MAX_H + 5, dtype=np.float64)
_v = 1.0
for _t in range(1, len(TRAVEL)):
    TRAVEL[_t] = TRAVEL[_t - 1] + _v
    _v *= DRAG


def travel(t):
    t = max(0.0, min(float(MAX_H + 4), float(t)))
    i = int(t)
    if i >= len(TRAVEL) - 1:
        return float(TRAVEL[-1])
    return float(TRAVEL[i] + (t - i) * (TRAVEL[i + 1] - TRAVEL[i]))


def fall(t):
    return GRAVITY * (t - travel(t)) / (1.0 - DRAG)


def sample_path(path, t):
    lo = int(t)
    if lo >= len(path) - 1:
        return path[-1]
    frac = t - lo
    if frac == 0.0:
        return path[lo]
    return path[lo] + frac * (path[lo + 1] - path[lo])


def intercept(sx, eye_y, sz, speed, path, aim_height=0.90):
    """Exact port of BowBallistics.intercept()."""
    origin_y = eye_y - 0.1
    def need(t):
        pt = sample_path(path, t)
        dx, dz = pt[0] - sx, pt[2] - sz
        dy = pt[1] + aim_height - origin_y + fall(t)
        return math.sqrt(max(0.0, dx * dx + dz * dz - OFFSET * OFFSET) + dy * dy) / travel(t)

    prev = float('inf')
    for t in range(1, len(path)):
        req = need(t)
        if req <= speed and prev > speed:
            lo, hi = max(0.01, t - 1.0), float(t)
            for _ in range(17):
                mid = 0.5 * (lo + hi)
                if need(mid) > speed:
                    lo = mid
                else:
                    hi = mid
            pt = sample_path(path, hi)
            dx, dz = pt[0] - sx, pt[2] - sz
            fwd = math.sqrt(max(0.0, dx * dx + dz * dz - OFFSET * OFFSET))
            yaw = math.atan2(-dx, dz) - math.atan2(OFFSET, fwd)
            dy = pt[1] + aim_height - origin_y + fall(hi)
            pitch = -math.atan2(dy, fwd)
            x0 = sx - OFFSET * math.cos(yaw)
            y0 = origin_y
            z0 = sz - OFFSET * math.sin(yaw)
            vx = -math.sin(yaw) * math.cos(pitch) * speed
            vy = -math.sin(pitch) * speed
            vz = math.cos(yaw) * math.cos(pitch) * speed
            return (yaw, pitch, hi, x0, y0, z0, vx, vy, vz)
        prev = req
    return None


def intersects_box(ax, ay, az, bx, by, bz, tx, ty, tz):
    """Exact Minecraft arrow-segment vs expanded player hitbox [-0.6,+0.6]x[-0.3,+2.1]x[-0.6,+0.6]."""
    near, far = 0.0, 1.0
    for p0, d, lo, hi in (
        (ax - tx, bx - ax, -0.6, 0.6),
        (ay - ty, by - ay, -0.3, 2.1),
        (az - tz, bz - az, -0.6, 0.6),
    ):
        if abs(d) < 1e-9:
            if p0 < lo or p0 > hi:
                return False
        else:
            p = (lo - p0) / d
            q = (hi - p0) / d
            if p > q:
                p, q = q, p
            if p > near:
                near = p
            if q < far:
                far = q
            if near > far:
                return False
    return True


def simulate_arrow_hit(shot, future_pos, rng, solid_set=None):
    """Simulate exact 1.8.9 arrow flight with native spread and optional terrain occlusion."""
    if shot is None:
        return False
    _, _, _, x, y, z, vx, vy, vz = shot
    spd = math.sqrt(vx * vx + vy * vy + vz * vz)
    def disp():
        return rng.normal() * (1.0 if rng.random() < 0.5 else -1.0)
    vx += disp() * SPREAD * spd
    vy += disp() * SPREAD * spd
    vz += disp() * SPREAD * spd
    for t in range(1, min(MAX_H, len(future_pos) - 1) + 1):
        nx, ny, nz = x + vx, y + vy, z + vz
        if solid_set is not None:
            # Check intermediate block collisions along tick segment
            for f in (0.25, 0.5, 0.75, 1.0):
                bx = int(math.floor(x + (nx - x) * f))
                by = int(math.floor(y + (ny - y) * f))
                bz = int(math.floor(z + (nz - z) * f))
                if (bx, by, bz) in solid_set:
                    return False
        ft = future_pos[t]
        if intersects_box(x, y, z, nx, ny, nz, float(ft[0]), float(ft[1]), float(ft[2])):
            return True
        x, y, z = nx, ny, nz
        vx *= DRAG
        vy = vy * DRAG - GRAVITY
        vz *= DRAG
    return False


def interp_mode_paths(p0, q, means_kh3, std_kh3):
    """Expand (K, 7, 3) anchor displacements & stds to world (K, MAX_H+1, 3) paths."""
    K = means_kh3.shape[0]
    paths = np.zeros((K, MAX_H + 1, 3), dtype=np.float64)
    stds = np.zeros((K, MAX_H + 1, 3), dtype=np.float64)
    paths[:, 0, :] = p0
    stds[:, 0, :] = 0.05

    # World-space anchor positions and stds
    anc_pos = np.zeros((K, NUM_H + 1, 3), dtype=np.float64)
    anc_std = np.zeros((K, NUM_H + 1, 3), dtype=np.float64)
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
            # Swap horizontal std axes for 90/270 deg rotations
            if q in (1, 3):
                anc_std[k, hi + 1, 0] = sz
                anc_std[k, hi + 1, 2] = sx
            else:
                anc_std[k, hi + 1, 0] = sx
                anc_std[k, hi + 1, 2] = sz
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
    return paths, stds


def normal_box_prob(ax, ay, az, mu_x, mu_y, mu_z, sx, sy, sz, vx, vy, vz):
    """Fast analytic probability that arrow passing through (ax,ay,az) with velocity (vx,vy,vz)
    hits a player whose feet ~ N(mu, diag(s^2)) with hitbox [-0.6,+0.6]x[-0.3,+2.1]x[-0.6,+0.6].
    Accounts for arrow sweeping along its velocity vector during +-0.5 ticks!
    """
    inv_sqrt2 = 0.7071067811865475
    # Longitudinal sweep slack: arrow sweeps roughly 0.45*|v_a| along each axis around t_hit
    slack_x = 0.6 + 0.38 * abs(vx)
    slack_y_lo = -0.3 - 0.35 * abs(vy)
    slack_y_hi = 2.1 + 0.35 * abs(vy)
    slack_z = 0.6 + 0.38 * abs(vz)

    def p1d(lo, hi, s):
        d = 1.0 / (max(0.08, s) * 1.4142135623730951)
        return 0.5 * (math.erf(hi * d) - math.erf(lo * d))

    px = p1d(ax - mu_x - slack_x, ax - mu_x + slack_x, sx)
    py = p1d(ay - mu_y - slack_y_hi, ay - mu_y - slack_y_lo, sy)
    pz = p1d(az - mu_z - slack_z, az - mu_z + slack_z, sz)
    return px * py * pz


def shot_expected_hit_prob(shot, pi, paths, stds):
    """Expected hit probability P(hit | shot) integrated across all K modes + arrow spread."""
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
    # Arrow velocity at impact t_hit
    drag_t = DRAG ** t_hit
    ivx = vx * drag_t
    ivy = vy * drag_t - GRAVITY * (1.0 - drag_t) / (1.0 - DRAG)
    ivz = vz * drag_t
    # Arrow dispersion std at impact
    sig_arrow = SPREAD * spd * tr
    var_arrow = sig_arrow * sig_arrow

    total_p = 0.0
    K = len(pi)
    lo = int(t_hit)
    hi = min(MAX_H, lo + 1)
    frac = t_hit - lo
    for k in range(K):
        w = float(pi[k])
        if w < 1e-4:
            continue
        mu = paths[k, lo] * (1.0 - frac) + paths[k, hi] * frac
        st = stds[k, lo] * (1.0 - frac) + stds[k, hi] * frac
        sx = math.sqrt(st[0] * st[0] + var_arrow)
        sy = math.sqrt(st[1] * st[1] + var_arrow)
        sz = math.sqrt(st[2] * st[2] + var_arrow)
        total_p += w * normal_box_prob(ax, ay, az, mu[0], mu[1], mu[2], sx, sy, sz, ivx, ivy, ivz)
    return min(1.0, max(0.0, total_p))


def solve_multimode_aim(sx, eye_y, sz, speed, pi, paths, stds, aim_height=0.90, solid_set=None):
    """Pick the candidate shot maximizing expected hit probability across all K modes."""
    K = len(pi)
    mean_path = np.tensordot(pi, paths, axes=([0], [0]))
    candidates = [paths[k] for k in range(K)] + [mean_path]
    best_shot = None
    best_prob = -1.0
    for path in candidates:
        s = intercept(sx, eye_y, sz, speed, path, aim_height)
        if s is None:
            continue
        if solid_set is not None:
            # Verify clear trajectory up to t_hit
            _, _, t_hit, x0, y0, z0, vx, vy, vz = s
            blocked = False
            steps = max(2, int(t_hit * 2))
            for st in range(1, steps):
                tt = t_hit * st / steps
                tr = travel(tt)
                bx = int(math.floor(x0 + vx * tr))
                by = int(math.floor(y0 + vy * tr - fall(tt)))
                bz = int(math.floor(z0 + vz * tr))
                if (bx, by, bz) in solid_set:
                    blocked = True
                    break
            if blocked:
                continue
        p = shot_expected_hit_prob(s, pi, paths, stds)
        if p > best_prob:
            best_prob = p
            best_shot = s
    return best_shot, max(0.0, best_prob)


def apply_calibration(raw_p, curve):
    if raw_p <= 0.0:
        return 0.0
    idx = raw_p * (len(curve) - 1)
    lo = int(idx)
    if lo >= len(curve) - 1:
        return float(curve[-1])
    return float(curve[lo] + (idx - lo) * (curve[lo + 1] - curve[lo]))


def fit_calibration(raw_probs, hits, steps=11):
    """Fit monotone isotonic 11-point lookup curve on validation data."""
    raw = np.asarray(raw_probs, dtype=np.float64)
    y = np.asarray(hits, dtype=np.float64)
    # Bin centers 0.0, 0.1, ..., 1.0 using overlapping kernels for smooth estimates
    xs = np.linspace(0.0, 1.0, steps)
    ys = np.zeros(steps, dtype=np.float64)
    ws = np.zeros(steps, dtype=np.float64)
    for i, c in enumerate(xs):
        if i == 0:
            m = raw < 0.05
        elif i == steps - 1:
            m = raw >= 0.95
        else:
            m = np.abs(raw - c) <= 0.08
        if m.sum() >= 15:
            ys[i] = y[m].mean()
            ws[i] = float(m.sum())
        else:
            ys[i] = c
            ws[i] = 1.0
    # Pool Adjacent Violators (PAV) monotonicity
    blocks = [[ys[i], ws[i], i, i] for i in range(steps)]
    i = 0
    while i < len(blocks) - 1:
        if blocks[i][0] > blocks[i + 1][0]:
            w_tot = blocks[i][1] + blocks[i + 1][1]
            val = (blocks[i][0] * blocks[i][1] + blocks[i + 1][0] * blocks[i + 1][1]) / w_tot
            blocks[i] = [val, w_tot, blocks[i][2], blocks[i + 1][3]]
            blocks.pop(i + 1)
            if i > 0:
                i -= 1
        else:
            i += 1
    curve = np.zeros(steps, dtype=np.float64)
    for val, _, a, b in blocks:
        curve[a:b + 1] = np.clip(val, 0.0, 1.0)
    curve[0] = min(curve[0], 0.05)
    return curve.tolist()


def linear_baseline_path(pos, i, past_len):
    """Exact port of BowMotion Mode.LINEAR: t * (pos[0] - pos[min(5, len-1)]) / dt."""
    dt = min(5, past_len - 1)
    vel = (pos[i] - pos[i - dt]) / float(dt) if dt > 0 else np.zeros(3)
    ts = np.arange(MAX_H + 1, dtype=np.float64)[:, None]
    return pos[i][None, :] + ts * vel[None, :]


def load_bow_motion_weights(path=Path('practice-client/assets/practice/bow-motion.bin')):
    raw = path.read_bytes()
    magic = struct.unpack('>i', raw[:4])[0]
    assert magic == 0x424f574d
    w = np.frombuffer(raw[4:], dtype='>f8').reshape(8, 2, 8).copy()
    return w


def bow_motion_shared_path(pos, i, past_len, weights):
    """Exact port of BowMotion Mode.SHARED (ridge baseline)."""
    H_OLD = [3, 5, 8, 12, 16, 22, 30, 42]
    def vel(a, b, axis):
        fa = min(past_len - 1, a)
        fb = min(past_len - 1, b)
        return 0.0 if fa == fb else float(pos[i - fa, axis] - pos[i - fb, axis]) / (fb - fa)

    def feat(axis):
        a, b = vel(0, 2, axis), vel(2, 5, axis)
        c, d = vel(5, 10, axis), vel(10, 20, axis)
        vx, vz = vel(0, 4, 0), vel(0, 4, 2)
        px, pz = vel(4, 10, 0), vel(4, 10, 2)
        mismatch = min(1.0, math.hypot(vx - px, vz - pz) / 0.30)
        speed = min(1.0, math.hypot(vx, vz) / 0.30)
        mean = 0.5 * (a + b)
        if axis == 1:
            return np.array([a, b, c, d, mean * min(1.0, abs(mean) / 0.2),
                             abs(mean), mean * mismatch,
                             math.copysign(min(0.4, abs(a - b)), a - b)])
        return np.array([a, b, c, d, mean * mismatch, mean * speed, c * mismatch, mean * speed * speed])

    feats = [feat(0), feat(1), feat(2)]
    anchors = np.zeros((9, 3), dtype=np.float64)
    for hi in range(8):
        for ax in range(3):
            anchors[hi + 1, ax] = float(np.dot(weights[hi, 1 if ax == 1 else 0], feats[ax]))
    path = np.zeros((MAX_H + 1, 3), dtype=np.float64)
    hi_i, lo_t = 0, 0
    for t in range(MAX_H + 1):
        while hi_i + 1 < 8 and t > H_OLD[hi_i]:
            lo_t = H_OLD[hi_i]
            hi_i += 1
        u = min(1.0, (t - lo_t) / float(H_OLD[hi_i] - lo_t))
        path[t] = pos[i] + anchors[hi_i] * (1.0 - u) + anchors[hi_i + 1] * u
    return path


def smart_release_decision(p_seq, threshold=0.75, min_floor=0.22, spike_margin=0.07,
                           spike_ratio=1.25, patience_start=30, max_wait=60):
    """Principled Optimal-Stopping / Peak-Spike Auto-Release Policy.
    Returns (fire_tick, fire_prob) or (None, None) if never fired within max_wait.
    """
    p_ema = p_seq[0]
    p_peak = p_seq[0]
    p_best = p_seq[0]
    in_spike = False
    plateau = 0
    for w in range(min(len(p_seq), max_wait)):
        p = float(p_seq[w])
        if w == 0:
            p_ema = p
        else:
            p_ema = 0.88 * p_ema + 0.12 * p
        if p > p_best:
            p_best = p
        # 1. Instant layup
        if p >= threshold:
            return w, p
        # 2. Spike tracking: detect meaningful predictability surge over engagement baseline
        spike_bar = max(min_floor, min(p_ema + spike_margin, p_ema * spike_ratio))
        if p >= spike_bar:
            if not in_spike:
                in_spike = True
                p_peak = p
                plateau = 1
            elif p > p_peak + 0.008:
                p_peak = p
                plateau = 1
            elif p >= p_peak - 0.015:
                plateau += 1
                if plateau >= 3:
                    return w, p
            else:
                # Turning over right after peak -> fire!
                return w, p
        elif in_spike:
            # Just dropped off the spike -> fire if still decent!
            if p >= max(min_floor * 0.85, p_ema + 0.02):
                return w, p
            in_spike = False
            p_peak = p
            plateau = 0
        # 3. Patience-decayed relaxation after holding full draw > 1.5s
        if w >= patience_start:
            relaxed = max(0.18, p_best * (0.94 - 0.009 * (w - patience_start)))
            if p >= relaxed and p >= p_ema:
                return w, p
    return None, None


def run_eval(model, device, data_dir: Path, split: str, mode_filter: str,
             use_terrain: bool, old_weights, stride=9, calib_curve=None):
    manifest = json.loads((data_dir / 'manifest.json').read_text())
    ranges = [12.0, 24.0, 38.0, 54.0]
    stats = {
        k: {r: [0, 0] for r in ranges}
        for k in ('linear', 'shared', 'neural_mean', 'neural_mm', 'neural_mm_novox')
    }
    xz_err = {k: {h: [] for h in HORIZONS} for k in ('linear', 'shared', 'neural_mean', 'neural_top1')}
    raw_probs, actual_hits = [], []

    # Batch inference per player for speed
    for m in manifest['matches']:
        if m['split'] != split:
            continue
        if mode_filter and m['mode'] != mode_filter:
            continue
        npz = np.load(data_dir / m['file'])
        n_players = int(npz['num_players'][0])
        for p_idx in range(n_players):
            ticks = npz[f'p{p_idx}_tick']
            pos = npz[f'p{p_idx}_pos']
            rot = npz[f'p{p_idx}_rot']
            flags = npz[f'p{p_idx}_flags']
            vox = npz[f'p{p_idx}_vox']
            past, fut = contiguous_runs(ticks, pos)
            indices = [i for i in range(len(ticks))
                       if past[i] >= 8 and fut[i] >= MAX_H and (int(ticks[i]) + p_idx) % stride == 0]
            if not indices:
                continue
            h_list, c_list, v_list, q_list = [], [], [], []
            for i in indices:
                h, c, v, q = build_features_from_track(pos, rot, flags, vox, i, int(past[i]), use_terrain=use_terrain)
                h_list.append(h); c_list.append(c); v_list.append(v); q_list.append(q)
            bh = torch.from_numpy(np.asarray(h_list)).to(device)
            bc = torch.from_numpy(np.asarray(c_list)).to(device)
            bv = torch.from_numpy(np.asarray(v_list)).to(device)
            with torch.no_grad():
                logits, means, logstd = model(bh, bc, bv)
                pi_all = F.softmax(logits, dim=-1).cpu().numpy()
                means_all = means.cpu().numpy()
                stds_all = logstd.exp().cpu().numpy()
                # Also no-voxel ablation
                bc0 = bc.clone(); bc0[:, 21] = 0.0
                l0, m0, s0 = model(bh, bc0, torch.zeros_like(bv))
                pi0_all = F.softmax(l0, dim=-1).cpu().numpy()
                m0_all = m0.cpu().numpy()
                s0_all = s0.exp().cpu().numpy()

            for j, i in enumerate(indices):
                q = q_list[j]
                p0 = pos[i].astype(np.float64)
                paths, stds = interp_mode_paths(p0, q, means_all[j], stds_all[j])
                paths0, stds0 = interp_mode_paths(p0, q, m0_all[j], s0_all[j])
                pi = pi_all[j]
                pi0 = pi0_all[j]
                mean_path = np.tensordot(pi, paths, axes=([0], [0]))
                top_path = paths[int(np.argmax(pi))]
                lin_path = linear_baseline_path(pos, i, int(past[i]))
                sh_path = bow_motion_shared_path(pos, i, int(past[i]), old_weights)

                for h in HORIZONS:
                    act = pos[i + h]
                    for name, pth in (('linear', lin_path), ('shared', sh_path),
                                      ('neural_mean', mean_path), ('neural_top1', top_path)):
                        xz_err[name][h].append(math.hypot(pth[h, 0] - act[0], pth[h, 2] - act[2]))

                seed = (int(ticks[i]) * 9859 + p_idx * 107 + hash(m['match_id']) % 997) & 0x7fffffff
                rng_ang = np.random.default_rng(seed)
                angle = float(rng_ang.random() * 2.0 * math.pi)
                future_slice = pos[i:i + MAX_H + 1]
                for r in ranges:
                    sx = p0[0] + r * math.sin(angle)
                    sz = p0[2] + r * math.cos(angle)
                    eye = p0[1] + 1.62
                    shot_seed = seed ^ int(r * 131)
                    s_lin = intercept(sx, eye, sz, SPEED_FULL, lin_path, 0.90)
                    s_sh = intercept(sx, eye, sz, SPEED_FULL, sh_path, 0.90)
                    s_nm = intercept(sx, eye, sz, SPEED_FULL, mean_path, 0.90)
                    s_mm, prob_mm = solve_multimode_aim(sx, eye, sz, SPEED_FULL, pi, paths, stds, 0.90)
                    s_mm0, _ = solve_multimode_aim(sx, eye, sz, SPEED_FULL, pi0, paths0, stds0, 0.90)

                    for name, sh in (('linear', s_lin), ('shared', s_sh), ('neural_mean', s_nm),
                                     ('neural_mm', s_mm), ('neural_mm_novox', s_mm0)):
                        hit = simulate_arrow_hit(sh, future_slice, np.random.default_rng(shot_seed))
                        stats[name][r][0] += int(hit)
                        stats[name][r][1] += 1
                        if name == 'neural_mm':
                            raw_probs.append(prob_mm)
                            actual_hits.append(int(hit))

    return stats, xz_err, raw_probs, actual_hits


def eval_terrain_occluded_and_release(model, device, data_dir: Path, split: str, old_weights, calib_curve):
    """Realistic Live-POV evaluation with actual terrain occlusion + Optimal-Stopping Auto-Release."""
    manifest = json.loads((data_dir / 'manifest.json').read_text())
    occ_stats = {k: [0, 0] for k in ('linear', 'shared', 'neural_mm')}
    release_results = {
        'immediate': {'shots': 0, 'hits': 0, 'wait': 0, 'low_shots': 0, 'low_hits': 0},
        'thresh_75': {'shots': 0, 'hits': 0, 'wait': 0, 'low_shots': 0, 'low_hits': 0},
        'thresh_50': {'shots': 0, 'hits': 0, 'wait': 0, 'low_shots': 0, 'low_hits': 0},
        'smart_peak': {'shots': 0, 'hits': 0, 'wait': 0, 'low_shots': 0, 'low_hits': 0},
    }
    total_windows = 0
    low_windows = 0

    for m in manifest['matches']:
        if m['split'] != split:
            continue
        npz = np.load(data_dir / m['file'])
        solid_set = None
        if 'terrain_xyz' in npz and len(npz['terrain_xyz']) > 0:
            t_xyz = npz['terrain_xyz']
            solid_set = set(zip(t_xyz[:, 0].tolist(), t_xyz[:, 1].tolist(), t_xyz[:, 2].tolist()))
        n_players = int(npz['num_players'][0])
        for p_idx in range(n_players):
            ticks = npz[f'p{p_idx}_tick']
            pos = npz[f'p{p_idx}_pos']
            rot = npz[f'p{p_idx}_rot']
            flags = npz[f'p{p_idx}_flags']
            vox = npz[f'p{p_idx}_vox']
            self_pos = npz[f'p{p_idx}_self']
            los = npz[f'p{p_idx}_los']
            past, fut = contiguous_runs(ticks, pos)

            # 1) Realistic Live-POV Occluded Shots
            occ_idx = []
            for i in range(0, len(ticks), 12):
                if past[i] < 8 or fut[i] < MAX_H or los[i] == 0:
                    continue
                d = float(np.linalg.norm(pos[i] - self_pos[i]))
                if 8.0 <= d <= 56.0:
                    occ_idx.append(i)
            if occ_idx:
                h_l, c_l, v_l, q_l = [], [], [], []
                for i in occ_idx:
                    h, c, v, q = build_features_from_track(pos, rot, flags, vox, i, int(past[i]), use_terrain=True)
                    h_l.append(h); c_l.append(c); v_l.append(v); q_l.append(q)
                with torch.no_grad():
                    logits, means, logstd = model(
                        torch.from_numpy(np.asarray(h_l)).to(device),
                        torch.from_numpy(np.asarray(c_l)).to(device),
                        torch.from_numpy(np.asarray(v_l)).to(device),
                    )
                    pi_a = F.softmax(logits, dim=-1).cpu().numpy()
                    m_a = means.cpu().numpy()
                    s_a = logstd.exp().cpu().numpy()
                for j, i in enumerate(occ_idx):
                    p0 = pos[i].astype(np.float64)
                    sp = self_pos[i].astype(np.float64)
                    paths, stds = interp_mode_paths(p0, q_l[j], m_a[j], s_a[j])
                    lin_p = linear_baseline_path(pos, i, int(past[i]))
                    sh_p = bow_motion_shared_path(pos, i, int(past[i]), old_weights)
                    s_lin = intercept(sp[0], sp[1] + 1.62, sp[2], SPEED_FULL, lin_p, 0.90)
                    s_sh = intercept(sp[0], sp[1] + 1.62, sp[2], SPEED_FULL, sh_p, 0.90)
                    s_mm, _ = solve_multimode_aim(sp[0], sp[1] + 1.62, sp[2], SPEED_FULL,
                                                  pi_a[j], paths, stds, 0.90, solid_set=solid_set)
                    seed = (int(ticks[i]) * 7919 + p_idx * 53) & 0x7fffffff
                    fut_sl = pos[i:i + MAX_H + 1]
                    for name, sh in (('linear', s_lin), ('shared', s_sh), ('neural_mm', s_mm)):
                        hit = simulate_arrow_hit(sh, fut_sl, np.random.default_rng(seed), solid_set=solid_set)
                        occ_stats[name][0] += int(hit)
                        occ_stats[name][1] += 1

            # 2) Optimal-Stopping Auto-Release Benchmark: 60-tick (3.0s) continuous windows!
            win_starts = []
            i = 10
            while i + 60 + MAX_H < len(ticks):
                if past[i] >= 10 and fut[i] >= 60 + MAX_H:
                    win_starts.append(i)
                    i += 75
                else:
                    i += 5
            for w0 in win_starts:
                # Batch predict all 60 ticks of this draw window
                h_l, c_l, v_l, q_l = [], [], [], []
                for dt in range(60):
                    idx = w0 + dt
                    h, c, v, q = build_features_from_track(pos, rot, flags, vox, idx, int(past[idx]), use_terrain=True)
                    h_l.append(h); c_l.append(c); v_l.append(v); q_l.append(q)
                with torch.no_grad():
                    logits, means, logstd = model(
                        torch.from_numpy(np.asarray(h_l)).to(device),
                        torch.from_numpy(np.asarray(c_l)).to(device),
                        torch.from_numpy(np.asarray(v_l)).to(device),
                    )
                    pi_a = F.softmax(logits, dim=-1).cpu().numpy()
                    m_a = means.cpu().numpy()
                    s_a = logstd.exp().cpu().numpy()
                # Fixed shooter standing at a realistic medium-long range (24-44 blocks)
                rng_w = np.random.default_rng((int(ticks[w0]) * 104729 + p_idx * 97) & 0x7fffffff)
                ang = float(rng_w.random() * 2 * math.pi)
                rng_dist = float(rng_w.choice([20.0, 28.0, 36.0, 44.0]))
                sx = float(pos[w0, 0]) + rng_dist * math.sin(ang)
                sz = float(pos[w0, 2]) + rng_dist * math.cos(ang)
                eye = float(pos[w0, 1]) + 1.62

                p_seq = []
                shots_seq = []
                for dt in range(60):
                    idx = w0 + dt
                    paths, stds = interp_mode_paths(pos[idx].astype(np.float64), q_l[dt], m_a[dt], s_a[dt])
                    sh, raw_p = solve_multimode_aim(sx, eye, sz, SPEED_FULL, pi_a[dt], paths, stds, 0.90)
                    cal_p = apply_calibration(raw_p, calib_curve)
                    p_seq.append(cal_p)
                    shots_seq.append(sh)

                total_windows += 1
                is_low = p_seq[0] < 0.45
                if is_low:
                    low_windows += 1

                def record(policy_name, fire_dt):
                    if fire_dt is None:
                        return
                    idx = w0 + fire_dt
                    hit = simulate_arrow_hit(shots_seq[fire_dt], pos[idx:idx + MAX_H + 1],
                                             np.random.default_rng((int(ticks[idx]) * 31337 + p_idx) & 0x7fffffff))
                    r = release_results[policy_name]
                    r['shots'] += 1
                    r['hits'] += int(hit)
                    r['wait'] += fire_dt
                    if is_low:
                        r['low_shots'] += 1
                        r['low_hits'] += int(hit)

                record('immediate', 0)
                t75 = next((dt for dt, pv in enumerate(p_seq) if pv >= 0.75), None)
                t50 = next((dt for dt, pv in enumerate(p_seq) if pv >= 0.50), None)
                tsm, _ = smart_release_decision(p_seq)
                record('thresh_75', t75)
                record('thresh_50', t50)
                record('smart_peak', tsm)

    return occ_stats, release_results, total_windows, low_windows


def print_range_table(title, stats):
    ranges = [12.0, 24.0, 38.0, 54.0]
    print(f'\n=== {title} ===')
    print(f'{"Model":20s} | {"12b":>7s} | {"24b":>7s} | {"38b":>7s} | {"54b":>7s} | {"TOTAL":>8s}')
    print('-' * 72)
    for name in ('linear', 'shared', 'neural_mean', 'neural_mm_novox', 'neural_mm'):
        parts = []
        tot_h = tot_n = 0
        for r in ranges:
            h, n = stats[name][r]
            tot_h += h; tot_n += n
            parts.append(f'{100*h/max(1,n):6.2f}%')
        print(f'{name:20s} | ' + ' | '.join(parts) + f' | {100*tot_h/max(1,tot_n):6.2f}% ({tot_h}/{tot_n})')


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--data', type=Path, default=Path('runtime/bow-aim/neural-data'))
    p.add_argument('--model-dir', type=Path, default=Path('runtime/bow-aim/neural-model'))
    p.add_argument('--export-asset', type=Path, default=Path('practice-client/assets/practice/bow-neural.bin'))
    args = p.parse_args()

    device = torch.device('mps' if torch.backends.mps.is_available() else 'cpu')
    ckpt = torch.load(args.model_dir / 'model.pt', map_location='cpu', weights_only=True)
    model = BowNeuralNet(NUM_MODES).to(device)
    model.load_state_dict(ckpt['state_dict'])
    model.eval()
    old_weights = load_bow_motion_weights()

    t0 = time.monotonic()
    print('1) Calibrating P*(hit) on VALIDATION split (strictly separate from test)...', flush=True)
    val_stats, _, val_raw, val_hits = run_eval(model, device, args.data, 'val', None, True, old_weights, stride=9)
    calib_curve = fit_calibration(val_raw, val_hits, steps=11)
    print('   Fitted calibration curve (0%..100%):', [round(x, 3) for x in calib_curve], flush=True)
    print_range_table('VALIDATION (BW-08 + SW-val)', val_stats)

    # Export calibrated binary to both model dir and mod assets!
    export_java_binary(model, args.model_dir / 'bow-neural.bin', calib_curve)
    export_java_binary(model, args.export_asset, calib_curve)

    print('\n2) Evaluating on SEALED TEST BedWars (game-09..12)...', flush=True)
    bw_stats, bw_err, bw_raw, bw_hits = run_eval(model, device, args.data, 'test', 'bedwars', True, old_weights, stride=9)
    print_range_table('SEALED TEST — BedWars (game-09..12)', bw_stats)
    print('   XZ mean error (blocks) at horizons [3, 6, 10, 15, 22, 30, 42]:')
    for name in ('linear', 'shared', 'neural_mean', 'neural_top1'):
        vals = [f'{np.mean(bw_err[name][h]):.3f}' for h in HORIZONS]
        print(f'     {name:14s}: ' + '  '.join(vals))

    print('\n3) Evaluating on SEALED TEST SkyWars...', flush=True)
    sw_stats, _, sw_raw, sw_hits = run_eval(model, device, args.data, 'test', 'skywars', True, old_weights, stride=6)
    print_range_table('SEALED TEST — SkyWars (held-out matches)', sw_stats)

    # Calibration quality (Brier score & ECE) on sealed test
    all_raw = np.asarray(bw_raw + sw_raw)
    all_cal = np.asarray([apply_calibration(x, calib_curve) for x in all_raw])
    all_hit = np.asarray(bw_hits + sw_hits, dtype=np.float64)
    brier_raw = float(np.mean((all_raw - all_hit) ** 2))
    brier_cal = float(np.mean((all_cal - all_hit) ** 2))
    print(f'\n   Sealed Test Calibration Brier: raw={brier_raw:.4f} -> calibrated={brier_cal:.4f} '
          f'(mean pred={all_cal.mean():.3f} vs actual={all_hit.mean():.3f})', flush=True)

    print('\n4) Evaluating Live-POV Terrain-Occluded Shots & Optimal-Stopping Auto-Release on SEALED TEST...', flush=True)
    occ_stats, rel, tot_w, low_w = eval_terrain_occluded_and_release(
        model, device, args.data, 'test', old_weights, calib_curve)
    print('\n=== SEALED TEST — Realistic Live-POV Terrain-Occluded Shots ===')
    for name in ('linear', 'shared', 'neural_mm'):
        h, n = occ_stats[name]
        print(f'  {name:14s}: {100*h/max(1,n):6.2f}% ({h}/{n})')

    print(f'\n=== SEALED TEST — Auto-Release Optimal Stopping ({tot_w} windows, {low_w} low-baseline <45%) ===')
    print(f'{"Policy":14s} | {"Fire%":>6s} | {"Hit|Fire":>8s} | {"Hits/Win":>8s} | {"Wait(t)":>7s} | {"Low-Base Fire%":>14s} | {"Low-Base Hit|Fire":>17s}')
    print('-' * 92)
    for name in ('immediate', 'thresh_75', 'thresh_50', 'smart_peak'):
        r = rel[name]
        fire_pct = 100.0 * r['shots'] / max(1, tot_w)
        hit_fire = 100.0 * r['hits'] / max(1, r['shots'])
        hits_win = 100.0 * r['hits'] / max(1, tot_w)
        avg_wait = r['wait'] / max(1, r['shots'])
        l_fire = 100.0 * r['low_shots'] / max(1, low_w)
        l_hit = 100.0 * r['low_hits'] / max(1, r['low_shots'])
        print(f'{name:14s} | {fire_pct:5.1f}% | {hit_fire:7.2f}% | {hits_win:7.2f}% | {avg_wait:7.1f} | {l_fire:13.1f}% | {l_hit:16.2f}%')

    report = {
        'calibration_curve': calib_curve,
        'brier_raw': brier_raw,
        'brier_cal': brier_cal,
        'bw_test': {k: {str(int(r)): v for r, v in d.items()} for k, d in bw_stats.items()},
        'sw_test': {k: {str(int(r)): v for r, v in d.items()} for k, d in sw_stats.items()},
        'occluded_test': occ_stats,
        'release_test': rel,
        'release_windows': tot_w,
        'release_low_windows': low_w,
        'elapsed_s': round(time.monotonic() - t0, 1),
    }
    (args.model_dir / 'eval-results.json').write_text(json.dumps(report, indent=2) + '\n')
    print(f'\nEvaluation complete in {time.monotonic() - t0:.1f}s!', flush=True)


if __name__ == '__main__':
    main()
