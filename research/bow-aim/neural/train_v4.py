"""Train BowNeuralNetV4 (BNN4) — Full Bitter-Lesson Spatial + Dynamic-Block + Velocity-Residual Architecture:
- 48-tick x 30-ch Conv1d + Multi-Head Temporal Self-Attention (includes dynamic block-place temporal cues)
- Real 3D Spatial CNN over 9x7x9 x 2ch ([is_solid, recently_placed])
- Real 2D Spatial CNN over 25x25 x 4ch 1-block top-down map ([floor_y_rel, head_wall, is_void, recently_placed])
- Physics-equivariant Velocity-Residual multi-modal heads anchored on unsmoothed serverPos(0) out to 72 ticks (3.6s)
- Auxiliary Future-Block-Placement head (7x5x7 over next 1..24 ticks) supervising spatial/block-building intent
- Square-root stratified sampling + 6% exact 1.8.9 physics-sim bridging slice (train ONLY) + Differentiable Volley Set-Cover loss
- Stage 2 direct expected-hit-probability + natural calibration fine-tuning!
"""
import argparse
import json
import math
from pathlib import Path
import struct
import time

import numpy as np
import torch
from torch import nn
from torch.nn import functional as F
from torch.utils.data import Dataset, DataLoader, WeightedRandomSampler

HORIZONS = [3, 6, 10, 15, 22, 30, 42, 56, 72]
NUM_H = len(HORIZONS)
MAX_H = HORIZONS[-1]
HIST_LEN = 48
HIST_CH = 30
CTX_DIM = 34
FINE_Y, FINE_Z, FINE_X = 7, 9, 9
FINE_DIM = FINE_Y * FINE_Z * FINE_X  # 567
TOP_CH, TOP_S = 4, 25
FUT_Y, FUT_Z, FUT_X = 5, 7, 7
FUT_DIM = FUT_Y * FUT_Z * FUT_X      # 245
NUM_MODES = 6
AUTOCORR_LAGS = [12, 16, 20, 24, 28, 34, 42, 52]
MAGIC_V4 = 0x424E4E34  # 'BNN4'

H_WEIGHTS_NP = np.array([1.30, 1.22, 1.15, 1.08, 1.00, 0.92, 0.82, 0.72, 0.62], dtype=np.float32)


def cardinal_from_yaw(yaw_deg):
    return int(math.floor((yaw_deg + 45.0) / 90.0)) & 3


def rot_xz(dx, dz, q):
    if q == 0: return dx, dz
    if q == 1: return dz, -dx
    if q == 2: return -dx, -dz
    return -dz, dx


def inv_rot_xz(rx, rz, q):
    if q == 0: return rx, rz
    if q == 1: return -rz, rx
    if q == 2: return -rx, -rz
    return rz, -rx


def rot_grid_zyx(grid, q):
    """Rotate (..., Z, X) grid into cardinal frame q matching Java srcVoxIndex."""
    if q == 0:
        return grid
    if q == 1:
        return np.swapaxes(grid[..., :, ::-1], -2, -1)
    if q == 2:
        return grid[..., ::-1, ::-1]
    return np.swapaxes(grid[..., ::-1, :], -2, -1)


def unpack_fine(packed_71):
    return np.unpackbits(packed_71)[:FINE_DIM].reshape(FINE_Y, FINE_Z, FINE_X)


def simulate_remote_interp(pos):
    n = len(pos)
    spos = np.round(pos * 32.0) / 32.0
    cpos = np.empty_like(pos)
    cpos[0] = spos[0]
    for i in range(1, n):
        cpos[i] = cpos[i - 1] + (spos[i] - cpos[i - 1]) / 3.0
    return cpos.astype(np.float32), spos.astype(np.float32)


def contiguous_runs(ticks, pos):
    n = len(ticks)
    if n < 2:
        return np.ones(n, dtype=np.int32), np.zeros(n, dtype=np.int32)
    past = np.ones(n, dtype=np.int32)
    for i in range(1, n):
        if int(ticks[i] - ticks[i - 1]) == 1 and float(np.linalg.norm(pos[i] - pos[i - 1])) <= 2.5:
            past[i] = past[i - 1] + 1
    fut = np.zeros(n, dtype=np.int32)
    for i in range(n - 2, -1, -1):
        if int(ticks[i + 1] - ticks[i]) == 1 and float(np.linalg.norm(pos[i + 1] - pos[i])) <= 2.5:
            fut[i] = fut[i + 1] + 1
    return past, fut


def compute_vbase(spos, i, valid_past, q):
    """Exact causal serverPos velocity + floor-envelope climb rate mirrored 1-to-1 in Java 8!"""
    wv = min(12, valid_past - 1)
    if wv >= 1:
        vx_w = float(spos[i, 0] - spos[i - wv, 0]) / wv
        vz_w = float(spos[i, 2] - spos[i - wv, 2]) / wv
        vrx, vrz = rot_xz(vx_w, vz_w, q)
        vrx = max(-0.38, min(0.38, vrx))
        vrz = max(-0.38, min(0.38, vrz))
    else:
        vrx, vrz = 0.0, 0.0

    vy_env = 0.0
    if valid_past >= 14:
        w = min(22, valid_past - 1)
        fwin = min(6, w // 3)
        min_rec = float(np.min(spos[i - fwin + 1:i + 1, 1]))
        min_old = float(np.min(spos[i - w:i - w + fwin, 1]))
        dt = float(max(1, w - fwin))
        raw_vy = (min_rec - min_old) / dt
        if raw_vy >= 0.04:
            vy_env = min(0.28, raw_vy)
    return np.array([vrx, vy_env, vrz], dtype=np.float32)


def decode_top_int8(top_int8, q):
    """Decode int8 (4, 25, 25) -> float32 (4, 25, 25) rotated into cardinal frame q."""
    t_rot = rot_grid_zyx(top_int8, q)
    out = np.empty((TOP_CH, TOP_S, TOP_S), dtype=np.float32)
    ch0 = t_rot[0].astype(np.float32)
    out[0] = np.where(t_rot[0] <= -125, -1.4, ch0 * 0.01)
    out[1] = t_rot[1].astype(np.float32) * 0.01
    out[2] = t_rot[2].astype(np.float32) * 0.01
    out[3] = t_rot[3].astype(np.float32) * 0.01
    return out


def build_features_v4(pos, spos, rot, flags, rel, blk4, vox_packed, pvox_packed, top_int8, i, valid_past,
                      q=None, use_terrain=True, use_relational=True):
    """Exact v4 feature builder mirrored 1-to-1 in Java 8 BowNeuralMotionV4."""
    p0 = pos[i]
    yaw0 = float(rot[i, 0])
    if q is None:
        q = cardinal_from_yaw(yaw0)
    q_deg = q * 90.0

    hist = np.zeros((HIST_CH, HIST_LEN), dtype=np.float32)
    max_age = min(valid_past - 1, HIST_LEN - 1)
    for age in range(HIST_LEN):
        c_age = min(age, max_age)
        idx = i - c_age
        prev_idx = i - min(c_age + 1, valid_past - 1)

        dx = float(pos[idx, 0] - p0[0])
        dy = float(pos[idx, 1] - p0[1])
        dz = float(pos[idx, 2] - p0[2])
        rx, rz = rot_xz(dx, dz, q)

        vdx = float(pos[idx, 0] - pos[prev_idx, 0])
        vdy = float(pos[idx, 1] - pos[prev_idx, 1])
        vdz = float(pos[idx, 2] - pos[prev_idx, 2])
        vrx, vrz = rot_xz(vdx, vdz, q)

        sdx = float(spos[idx, 0] - pos[idx, 0])
        sdy = float(spos[idx, 1] - pos[idx, 1])
        sdz = float(spos[idx, 2] - pos[idx, 2])
        srx, srz = rot_xz(sdx, sdz, q)

        rel_yaw = math.radians(float(rot[idx, 0]) - q_deg)
        pitch = float(rot[idx, 1]) / 90.0
        fl = int(flags[idx])

        hist[0, age] = max(-4.0, min(4.0, rx / 4.0))
        hist[1, age] = max(-4.0, min(4.0, dy / 4.0))
        hist[2, age] = max(-4.0, min(4.0, rz / 4.0))
        hist[3, age] = max(-3.0, min(3.0, vrx * 3.0))
        hist[4, age] = max(-3.0, min(3.0, vdy * 3.0))
        hist[5, age] = max(-3.0, min(3.0, vrz * 3.0))
        hist[6, age] = max(-3.0, min(3.0, srx * 3.0))
        hist[7, age] = max(-3.0, min(3.0, sdy * 3.0))
        hist[8, age] = max(-3.0, min(3.0, srz * 3.0))
        hist[9, age] = math.sin(rel_yaw)
        hist[10, age] = math.cos(rel_yaw)
        hist[11, age] = max(-1.0, min(1.0, pitch))
        hist[12, age] = 1.0 if (fl & 1) else 0.0
        hist[13, age] = 1.0 if (fl & 2) else 0.0
        hist[14, age] = 1.0 if (fl & 12) else 0.0
        hist[15, age] = 1.0 if (fl & 32) else 0.0
        hist[16, age] = 1.0 if (fl & 80) else 0.0
        hist[17, age] = 1.0 if age <= max_age else 0.0

        if use_relational and rel is not None:
            r_row = rel[idx]
            nrx, nrz = rot_xz(float(r_row[0]), float(r_row[2]), q)
            rrx, rrz = rot_xz(float(r_row[5]), float(r_row[7]), q)
            r_hd = max(1.0, math.hypot(rrx, rrz))
            r_prox = float(r_row[8])
            hist[18, age] = max(-4.0, min(4.0, nrx / 8.0))
            hist[19, age] = max(-4.0, min(4.0, float(r_row[1]) / 8.0))
            hist[20, age] = max(-4.0, min(4.0, nrz / 8.0))
            hist[21, age] = float(r_row[3])
            hist[22, age] = float(r_row[4])
            hist[23, age] = (rrx / r_hd) * r_prox
            hist[24, age] = (rrz / r_hd) * r_prox
            hist[25, age] = float(r_row[9])

        if blk4 is not None:
            b_row = blk4[idx]
            brx, brz = rot_xz(float(b_row[1]), float(b_row[3]), q)
            hist[26, age] = max(0.0, min(1.0, float(b_row[0])))
            hist[27, age] = max(-1.0, min(1.0, brx / 3.0))
            hist[28, age] = max(-1.0, min(1.0, float(b_row[2]) / 3.0))
            hist[29, age] = max(-1.0, min(1.0, brz / 3.0))

    vbase = compute_vbase(spos, i, valid_past, q)

    ctx = np.zeros(CTX_DIM, dtype=np.float32)

    def mean_vel(a, b):
        fa = min(a, valid_past - 1)
        fb = min(b, valid_past - 1)
        if fb <= fa:
            return 0.0, 0.0, 0.0
        dt = float(fb - fa)
        mx = float(pos[i - fa, 0] - pos[i - fb, 0]) / dt
        my = float(pos[i - fa, 1] - pos[i - fb, 1]) / dt
        mz = float(pos[i - fa, 2] - pos[i - fb, 2]) / dt
        rx_, rz_ = rot_xz(mx, mz, q)
        return rx_, my, rz_

    v04 = mean_vel(0, 4); v412 = mean_vel(4, 12)
    v1224 = mean_vel(12, 24); v2448 = mean_vel(24, 48)
    ctx[0], ctx[1], ctx[2] = v04[0] * 3.0, v04[1] * 3.0, v04[2] * 3.0
    ctx[3], ctx[4], ctx[5] = v412[0] * 3.0, v412[1] * 3.0, v412[2] * 3.0
    ctx[6], ctx[7] = v1224[0] * 3.0, v1224[2] * 3.0
    ctx[8], ctx[9] = v2448[0] * 3.0, v2448[2] * 3.0

    n_sp = min(32, valid_past - 1)
    if n_sp >= 2:
        d = pos[i - n_sp + 1:i + 1] - pos[i - n_sp:i]
        spd = np.hypot(d[:, 0], d[:, 2])
        ctx[10] = float(spd.mean()) * 3.0
        ctx[11] = float(spd.std()) * 3.0

    n_ac = min(110, valid_past - 4)
    if n_ac >= 16:
        idx_hi = np.arange(i, i - n_ac, -1)
        vx3 = pos[idx_hi, 0] - pos[idx_hi - 3, 0]
        vz3 = pos[idx_hi, 2] - pos[idx_hi - 3, 2]
        vx3 = vx3 - vx3.mean(); vz3 = vz3 - vz3.mean()
        energy = float(np.sum(vx3 * vx3 + vz3 * vz3))
        if energy > 1e-4:
            for li, lag in enumerate(AUTOCORR_LAGS):
                if lag + 8 < n_ac:
                    num = float(np.sum(vx3[:-lag] * vx3[lag:] + vz3[:-lag] * vz3[lag:]))
                    den = float(np.sqrt(
                        np.sum(vx3[:-lag] ** 2 + vz3[:-lag] ** 2) *
                        np.sum(vx3[lag:] ** 2 + vz3[lag:] ** 2)
                    ))
                    if den > 1e-5:
                        ctx[12 + li] = max(-1.0, min(1.0, num / den))

    ctx[20] = math.log1p(min(256, valid_past)) / 5.5
    ctx[21] = 1.0 if (use_terrain and vox_packed is not None) else 0.0

    n_st = min(160, valid_past - 1)
    if n_st >= 4:
        sl_pos = pos[i - n_st:i + 1]
        d_all = sl_pos[1:] - sl_pos[:-1]
        ctx[22] = float(np.mean(d_all[:, 1] > 0.25))
        if n_st >= 8:
            v4 = sl_pos[4:] - sl_pos[:-4]
            dots = v4[4:, 0] * v4[:-4, 0] + v4[4:, 2] * v4[:-4, 2]
            mags = np.hypot(v4[4:, 0], v4[4:, 2]) * np.hypot(v4[:-4, 0], v4[:-4, 2])
            ctx[23] = float(np.mean((mags > 0.04) & (dots < 0.0)))
        fl_sl = flags[i - n_st:i + 1]
        ctx[24] = float(np.mean((fl_sl & 1) != 0))
        ctx[25] = float(np.mean((fl_sl & 2) != 0))
        ctx[26] = float(np.mean((fl_sl & 32) != 0))
    ctx[27] = math.hypot(float(spos[i, 0] - pos[i, 0]), float(spos[i, 2] - pos[i, 2])) * 3.0
    ctx[28] = 1.0 if (use_relational and rel is not None) else 0.0
    ctx[29] = float(rel[i, 3]) if (use_relational and rel is not None) else 0.0

    if blk4 is not None:
        nb = min(40, valid_past)
        ctx[30] = float(np.mean(blk4[i - nb + 1:i + 1, 0] > 0.05))
    ctx[31] = max(-2.0, min(2.0, float(vbase[1]) * 5.0))
    ctx[32] = max(-2.0, min(2.0, float(vbase[0]) * 3.0))
    ctx[33] = max(-2.0, min(2.0, float(vbase[2]) * 3.0))
    np.clip(ctx, -4.0, 4.0, out=ctx)

    if use_terrain and vox_packed is not None:
        fs = rot_grid_zyx(unpack_fine(vox_packed[i]), q).astype(np.float32)
        fp = rot_grid_zyx(unpack_fine(pvox_packed[i]), q).astype(np.float32) if pvox_packed is not None else np.zeros_like(fs)
        fine3d = np.stack([fs, fp], axis=0)
        top2d = decode_top_int8(top_int8[i], q) if top_int8 is not None else np.zeros((TOP_CH, TOP_S, TOP_S), dtype=np.float32)
    else:
        fine3d = np.zeros((2, FINE_Y, FINE_Z, FINE_X), dtype=np.float32)
        top2d = np.zeros((TOP_CH, TOP_S, TOP_S), dtype=np.float32)
    return hist, ctx, vbase, fine3d, top2d, q


# ---- Synthetic Physics-Exact Bridging Slice (Train ONLY, ~6% of train samples) ----

def generate_synthetic_bridging_samples(n_samples=26000, seed=777):
    """Exact 1.8.9 physics-simulated bridging tracks covering slow sneak, fast constant sideways,
    variable shift-tap speedbridging (including speed shifts & pauses), diagonal-up stair speedbridging,
    and telly/breezily styles — WITH exact dynamic placed blocks & future-block targets!
    """
    rng = np.random.default_rng(seed)
    hists = np.empty((n_samples, HIST_CH, HIST_LEN), dtype=np.float32)
    ctxs = np.empty((n_samples, CTX_DIM), dtype=np.float32)
    vbases = np.empty((n_samples, 3), dtype=np.float32)
    fines = np.empty((n_samples, 2, FINE_Y, FINE_Z, FINE_X), dtype=np.uint8)
    tops = np.empty((n_samples, TOP_CH, TOP_S, TOP_S), dtype=np.int8)
    targets = np.empty((n_samples, NUM_H, 3), dtype=np.float32)
    masks = np.ones((n_samples, NUM_H), dtype=np.float32)
    fut_blks = np.zeros((n_samples, FUT_Y, FUT_Z, FUT_X), dtype=np.uint8)
    has_fblk = np.ones(n_samples, dtype=np.float32)
    buckets = np.empty(n_samples, dtype=np.int32)

    out_i = 0
    while out_i < n_samples:
        kind = rng.choice(['const_side', 'speedbridge_var', 'diag_stair_up', 'telly_or_breezily', 'bridge_then_stop'],
                          p=[0.24, 0.28, 0.26, 0.12, 0.10])
        total_t = 145
        pos = np.zeros((total_t, 3), dtype=np.float32)
        rot = np.zeros((total_t, 2), dtype=np.float32)
        flags = np.zeros(total_t, dtype=np.int32)
        rel = np.zeros((total_t, 10), dtype=np.float32)
        blk4 = np.zeros((total_t, 4), dtype=np.float32)

        # Random cardinal or diagonal heading!
        if kind == 'diag_stair_up':
            ang = rng.choice([math.pi * 0.25, math.pi * 0.75, math.pi * 1.25, math.pi * 1.75]) + rng.uniform(-0.12, 0.12)
        else:
            ang = rng.uniform(0, 2 * math.pi)
        ux, uz = math.cos(ang), math.sin(ang)
        base_yaw = math.degrees(math.atan2(-ux, uz)) + 180.0 + rng.uniform(-45.0, 45.0)
        spd = float(rng.uniform(0.075, 0.255))
        stair_p = int(rng.integers(5, 10))
        launch = int(rng.integers(54, 68))
        # Optional speed change AFTER launch so the model learns realistic speed distributions!
        post_mult = float(rng.choice([0.65, 0.85, 1.0, 1.0, 1.0, 1.20, 1.38])) if kind == 'speedbridge_var' else 1.0
        stop_at = launch + int(rng.integers(8, 35)) if kind == 'bridge_then_stop' else 999

        solid = {}  # (x, y, z) -> tick placed (-999 for starting island)
        for ix in range(-4, 5):
            for iz in range(-4, 5):
                solid[(ix, 63, iz)] = -999

        x, y, z = 0.5, 64.0, 0.5
        step_base = 64.0
        jump_arc = [0.0, 0.42, 0.75, 0.99, 1.12, 1.05, 1.00, 1.00, 1.00, 1.00]
        place_events = []

        for t in range(total_t):
            sm = post_mult if t > launch else 1.0
            if t >= stop_at:
                sm = 0.0
            if kind == 'const_side':
                rot[t] = [base_yaw, rng.uniform(72.0, 82.0)]
                flags[t] = (1 if spd < 0.14 else 0) | 2
                x += ux * spd * sm; z += uz * spd * sm
                bx, bz = int(math.floor(x)), int(math.floor(z))
                if (bx, 63, bz) not in solid:
                    solid[(bx, 63, bz)] = t; place_events.append((t, bx, 63, bz))
            elif kind in ('speedbridge_var', 'bridge_then_stop'):
                ph = t % 7
                sn = ph >= 5 or sm == 0.0
                cad = 0.45 if sn else (1.18 + 0.16 * math.sin(t * 0.19))
                rot[t] = [base_yaw + rng.uniform(-4, 4), rng.uniform(73.0, 81.0)]
                flags[t] = (1 if sn else 0) | 2
                x += ux * spd * sm * cad; z += uz * spd * sm * cad
                bx, bz = int(math.floor(x)), int(math.floor(z))
                if (bx, 63, bz) not in solid:
                    solid[(bx, 63, bz)] = t; place_events.append((t, bx, 63, bz))
            elif kind == 'telly_or_breezily':
                ph = t % 6
                # Telly whips pitch up & down; breezily strafes slightly without sneak
                pit = 25.0 if ph < 2 else 77.0
                rot[t] = [base_yaw + 12.0 * math.sin(t * 0.6), pit]
                flags[t] = 2  # holding block, NO sneak!
                x += ux * spd * sm; z += uz * spd * sm
                y = 64.0 + (0.42 if ph == 1 else 0.72 if ph == 2 else 0.55 if ph == 3 else 0.0)
                bx, bz = int(math.floor(x)), int(math.floor(z))
                if (bx, 63, bz) not in solid:
                    solid[(bx, 63, bz)] = t; place_events.append((t, bx, 63, bz))
            else:  # diag_stair_up
                ph = t % stair_p
                sn = ph >= stair_p - 2
                cad = 0.50 if sn else 1.22
                rot[t] = [base_yaw + rng.uniform(-5, 5), rng.uniform(72.0, 80.0)]
                flags[t] = (1 if sn else 0) | 2
                x += ux * spd * sm * cad; z += uz * spd * sm * cad
                if ph == 0 and t > 0:
                    step_base += 1.0
                y = step_base + jump_arc[min(ph, len(jump_arc) - 1)]
                bx, bz = int(math.floor(x)), int(math.floor(z))
                by = int(math.floor(step_base)) - 1
                if (bx, by, bz) not in solid:
                    solid[(bx, by, bz)] = t; place_events.append((t, bx, by, bz))
                if ph >= stair_p - 3 and (bx, by + 1, bz) not in solid:
                    solid[(bx, by + 1, bz)] = t; place_events.append((t, bx, by + 1, bz))
            pos[t] = [x, y, z]

            # Causal blk4 over last 10 ticks
            bx, by, bz = int(math.floor(x)), int(math.floor(y)), int(math.floor(z))
            cnt = 0; sdx = sdy = sdz = 0.0
            for (pt, px_, py_, pz_) in place_events[-12:]:
                if 0 <= t - pt <= 10 and abs(px_ - bx) <= 3 and abs(pz_ - bz) <= 3 and abs(py_ - by) <= 2:
                    cnt += 1; sdx += px_ - bx; sdy += py_ - by; sdz += pz_ - bz
            if cnt > 0:
                blk4[t] = [min(1.0, cnt / 4.0), sdx / cnt, sdy / cnt, sdz / cnt]

        cpos, spos = simulate_remote_interp(pos)
        # Extract 1–2 snapshots from this simulated trajectory
        for idx in (launch,):
            if out_i >= n_samples:
                break
            bx, by, bz = int(math.floor(cpos[idx, 0])), int(math.floor(cpos[idx, 1])), int(math.floor(cpos[idx, 2]))
            q = cardinal_from_yaw(float(rot[idx, 0]))

            # Build causal fine3d & top2d strictly from blocks placed at t <= idx
            fine_s = np.zeros((FINE_Y, FINE_Z, FINE_X), dtype=np.uint8)
            fine_p = np.zeros((FINE_Y, FINE_Z, FINE_X), dtype=np.uint8)
            top = np.zeros((TOP_CH, TOP_S, TOP_S), dtype=np.int8)
            top[0] = -127
            top[2] = 100
            hi_y = np.full((TOP_S, TOP_S), -999, dtype=np.int32)
            for (wx, wy, wz), pt in solid.items():
                if pt > idx:
                    continue
                dx, dy, dz = wx - bx, wy - by, wz - bz
                if -12 <= dx <= 12 and -12 <= dz <= 12 and -12 <= dy <= 4:
                    tz, tx = dz + 12, dx + 12
                    top[2, tz, tx] = 0
                    if dy in (1, 2):
                        top[1, tz, tx] = 100
                    if -6 <= dy <= 4 and dy > hi_y[tz, tx]:
                        hi_y[tz, tx] = dy
                        top[0, tz, tx] = int(max(-120, min(100, (dy + 1) * 20)))
                    if pt >= 0 and idx - pt <= 120 and -4 <= dy <= 3:
                        st = int(round(100.0 * math.exp(-(idx - pt) / 60.0)))
                        if st > top[3, tz, tx]:
                            top[3, tz, tx] = st
                        if -4 <= dx <= 4 and -4 <= dz <= 4 and -3 <= dy <= 3:
                            fine_p[dy + 3, dz + 4, dx + 4] = 1
                if -4 <= dx <= 4 and -4 <= dz <= 4 and -3 <= dy <= 3:
                    fine_s[dy + 3, dz + 4, dx + 4] = 1

            # Build future block target (7x5x7 over next 1..24 ticks)
            fb = np.zeros((FUT_Y, FUT_Z, FUT_X), dtype=np.uint8)
            for (pt, wx, wy, wz) in place_events:
                if idx < pt <= idx + 24:
                    dx, dy, dz = wx - bx, wy - by, wz - bz
                    if -3 <= dx <= 3 and -3 <= dz <= 3 and -2 <= dy <= 2:
                        fb[dy + 2, dz + 3, dx + 3] = 1

            # Build features using exact build_features_v4!
            vox_arr = np.zeros((idx + 1, 71), dtype=np.uint8)
            pvox_arr = np.zeros((idx + 1, 71), dtype=np.uint8)
            top_arr = np.zeros((idx + 1, TOP_CH, TOP_S, TOP_S), dtype=np.int8)
            vox_arr[idx] = np.packbits(fine_s.reshape(-1))
            pvox_arr[idx] = np.packbits(fine_p.reshape(-1))
            top_arr[idx] = top
            h, c, vb, f3, t2, q = build_features_v4(
                cpos, spos, rot, flags, rel, blk4, vox_arr, pvox_arr, top_arr, idx, idx + 1, q=q
            )
            # Targets relative to unsmoothed serverPos(idx) in cardinal frame q!
            s0 = spos[idx]
            tgt = np.zeros((NUM_H, 3), dtype=np.float32)
            for hi, hz in enumerate(HORIZONS):
                d = spos[idx + hz] - s0
                rx, rz = rot_xz(float(d[0]), float(d[2]), q)
                tgt[hi] = [rx, float(d[1]), rz]

            hists[out_i] = h
            ctxs[out_i] = c
            vbases[out_i] = vb
            fines[out_i] = f3.astype(np.uint8)
            tops[out_i] = rot_grid_zyx(top, q)
            targets[out_i] = tgt
            fut_blks[out_i] = rot_grid_zyx(fb, q)
            buckets[out_i] = 0 if kind == 'diag_stair_up' else 1
            out_i += 1

    return hists, ctxs, vbases, fines, tops, targets, masks, fut_blks, has_fblk, buckets


# ---- Real Split Loader with Automatic Behavior Bucketing & Future-Block Targets ----

def build_fut_blk_grid(place_log, cur_tick, bx, by, bz, q):
    fb = np.zeros((FUT_Y, FUT_Z, FUT_X), dtype=np.uint8)
    if len(place_log) == 0:
        return fb
    # place_log is sorted by tick -> binary search [cur_tick + 1, cur_tick + 24]
    ticks = place_log[:, 0]
    lo = int(np.searchsorted(ticks, cur_tick + 1, side='left'))
    hi = int(np.searchsorted(ticks, cur_tick + 24, side='right'))
    for row in place_log[lo:hi]:
        dx, dy, dz = int(row[1]) - bx, int(row[2]) - by, int(row[3]) - bz
        if -3 <= dx <= 3 and -3 <= dz <= 3 and -2 <= dy <= 2:
            fb[dy + 2, dz + 3, dx + 3] = 1
    return rot_grid_zyx(fb, q)


def load_split_v4(data_dir: Path, split: str, stride: int = 3, include_self: bool = False):
    manifest = json.loads((data_dir / 'manifest.json').read_text())
    hists, ctxs, vbases, fines, tops, targets, masks, fut_blks, has_fblks, buckets = (
        [], [], [], [], [], [], [], [], [], []
    )

    def add_track(ticks, pos, spos, rot, flags, rel, blk4, vox, pvox, top_arr, place_log, use_stride):
        past, fut = contiguous_runs(ticks, pos)
        has_pl = 1.0 if len(place_log) > 0 else 0.0
        for i in range(0, len(ticks), use_stride):
            if past[i] < 6 or fut[i] < HORIZONS[3]:
                continue
            h, c, vb, f3, _, q = build_features_v4(
                pos, spos, rot, flags, rel, blk4, vox, pvox, top_arr, i, int(past[i])
            )
            tgt = np.zeros((NUM_H, 3), dtype=np.float32)
            msk = np.zeros(NUM_H, dtype=np.float32)
            s0 = spos[i]
            for hi, hz in enumerate(HORIZONS):
                if fut[i] >= hz:
                    d = spos[i + hz] - s0
                    rx, rz = rot_xz(float(d[0]), float(d[2]), q)
                    tgt[hi] = [rx, float(d[1]), rz]
                    msk[hi] = 1.0

            bx, by, bz = int(math.floor(pos[i, 0])), int(math.floor(pos[i, 1])), int(math.floor(pos[i, 2]))
            fb = build_fut_blk_grid(place_log, int(ticks[i]), bx, by, bz, q) if has_pl > 0.5 else np.zeros((FUT_Y, FUT_Z, FUT_X), dtype=np.uint8)

            # Automatic behavioral regime bucket for square-root stratified sampling:
            # 0 = bridge_up (placing blocks + climbing), 1 = bridge_flat (placing blocks / holding block over void),
            # 2 = void_edge (void within 3b), 3 = fast_straight, 4 = combat_general
            is_placing = (c[30] > 0.05) or (fb.any())
            top_r = rot_grid_zyx(top_arr[i], q)
            near_void = bool((top_r[2, 9:16, 9:16] > 50).any())
            spd_h = math.hypot(float(vb[0]), float(vb[2]))
            if is_placing and vb[1] >= 0.05:
                bkt = 0
            elif is_placing or ((int(flags[i]) & 2) != 0 and near_void and spd_h >= 0.05):
                bkt = 1
            elif near_void:
                bkt = 2
            elif spd_h >= 0.14:
                bkt = 3
            else:
                bkt = 4

            hists.append(h)
            ctxs.append(c)
            vbases.append(vb)
            fines.append(f3.astype(np.uint8))
            tops.append(top_r)
            targets.append(tgt)
            masks.append(msk)
            fut_blks.append(fb)
            has_fblks.append(has_pl)
            buckets.append(bkt)

    for m in manifest['matches']:
        if m['split'] != split:
            continue
        npz = np.load(data_dir / m['file'])
        pl = npz['place_log'] if 'place_log' in npz else np.empty((0, 4), dtype=np.int32)
        if len(pl) > 1:
            pl = pl[np.argsort(pl[:, 0])]
        for p in range(int(npz['num_players'][0])):
            add_track(npz[f'p{p}_tick'], npz[f'p{p}_pos'], npz[f'p{p}_spos'],
                      npz[f'p{p}_rot'], npz[f'p{p}_flags'], npz[f'p{p}_rel'],
                      npz[f'p{p}_blk4'], npz[f'p{p}_vox'], npz[f'p{p}_pvox'], npz[f'p{p}_top'],
                      pl, stride)
        if include_self and 'self_tick' in npz:
            cpos, spos = simulate_remote_interp(npz['self_pos'])
            add_track(npz['self_tick'], cpos, spos,
                      npz['self_rot'], npz['self_flags'], npz['self_rel'],
                      npz['self_blk4'], npz['self_vox'], npz['self_pvox'], npz['self_top'],
                      pl, max(stride, 4))

    return (
        np.asarray(hists, dtype=np.float32),
        np.asarray(ctxs, dtype=np.float32),
        np.asarray(vbases, dtype=np.float32),
        np.asarray(fines, dtype=np.uint8),
        np.asarray(tops, dtype=np.int8),
        np.asarray(targets, dtype=np.float32),
        np.asarray(masks, dtype=np.float32),
        np.asarray(fut_blks, dtype=np.uint8),
        np.asarray(has_fblks, dtype=np.float32),
        np.asarray(buckets, dtype=np.int32),
    )


class V4Dataset(Dataset):
    def __init__(self, arrays):
        self.h, self.c, self.vb, self.f3, self.t2, self.tgt, self.msk, self.fb, self.hfb, self.bkt = arrays

    def __len__(self):
        return len(self.h)

    def __getitem__(self, idx):
        t_raw = self.t2[idx]
        t_f = np.empty((TOP_CH, TOP_S, TOP_S), dtype=np.float32)
        t_f[0] = np.where(t_raw[0] <= -125, -1.4, t_raw[0].astype(np.float32) * 0.01)
        t_f[1] = t_raw[1].astype(np.float32) * 0.01
        t_f[2] = t_raw[2].astype(np.float32) * 0.01
        t_f[3] = t_raw[3].astype(np.float32) * 0.01
        return (
            self.h[idx],
            self.c[idx],
            self.vb[idx],
            self.f3[idx].astype(np.float32),
            t_f,
            self.tgt[idx],
            self.msk[idx],
            self.fb[idx].astype(np.float32).reshape(-1),
            self.hfb[idx],
        )


# ---- BowNeuralNetV4 (BNN4) ----

class BowNeuralNetV4(nn.Module):
    def __init__(self, num_modes=NUM_MODES):
        super().__init__()
        self.num_modes = num_modes
        # 1) 4-layer strided temporal Conv1d (48 -> 48 -> 24 -> 12 -> 6) + 4-head Self-Attention
        self.conv1 = nn.Conv1d(HIST_CH, 48, kernel_size=3, stride=1, padding=1)
        self.conv2 = nn.Conv1d(48, 64, kernel_size=3, stride=2, padding=1)
        self.conv3 = nn.Conv1d(64, 80, kernel_size=3, stride=2, padding=1)
        self.conv4 = nn.Conv1d(80, 96, kernel_size=3, stride=2, padding=1)
        self.attn_qkv = nn.Linear(96, 96 * 3)
        self.attn_out = nn.Linear(96, 96)
        self.attn_ln = nn.LayerNorm(96)
        self.hist_proj = nn.Linear(96 * 3, 144)

        # 2) Fine 2.5D Vertical-Channel Spatial CNN on (2*7=14, 9, 9) -> (32, 9, 9) -> (48, 5, 5) -> (64, 3, 3)
        # Physically ideal for Minecraft: distinct weights per height slice [-3..+3], translation-equivariant in (Z, X), 100% native MPS!
        self.v3_1 = nn.Conv2d(2 * FINE_Y, 32, kernel_size=3, stride=1, padding=1)
        self.v3_2 = nn.Conv2d(32, 48, kernel_size=3, stride=2, padding=1)
        self.v3_3 = nn.Conv2d(48, 64, kernel_size=3, stride=2, padding=1)
        self.v3_proj = nn.Linear(64 * 3 * 3, 96)

        # 3) Wide 1-block 2.5D Top-Down CNN on (4, 25, 25) -> (16, 13, 13) -> (24, 7, 7) -> (32, 4, 4)
        self.t2_1 = nn.Conv2d(TOP_CH, 16, kernel_size=3, stride=2, padding=1)
        self.t2_2 = nn.Conv2d(16, 24, kernel_size=3, stride=2, padding=1)
        self.t2_3 = nn.Conv2d(24, 32, kernel_size=3, stride=2, padding=1)
        self.t2_proj = nn.Linear(32 * 4 * 4, 96)

        # 4) 3-block 256-dim residual fusion trunk
        self.trunk_in = nn.Linear(144 + CTX_DIM + 96 + 96, 256)
        self.res1a = nn.Linear(256, 256); self.res1b = nn.Linear(256, 256)
        self.res2a = nn.Linear(256, 256); self.res2b = nn.Linear(256, 256)
        self.res3a = nn.Linear(256, 256); self.res3b = nn.Linear(256, 256)

        # 5) Velocity-residual correlated multi-modal heads out to 72 ticks + auxiliary future-block head
        self.head_logits = nn.Linear(256, num_modes)
        self.head_vgate = nn.Linear(256, num_modes * 2)  # (g_xz, g_y) per mode
        self.head_delta = nn.Linear(256, num_modes * NUM_H * 3)
        self.head_logstd = nn.Linear(256, num_modes * NUM_H * 3)
        self.head_rho = nn.Linear(256, num_modes * NUM_H)
        self.head_fut_blk = nn.Linear(256, FUT_DIM)

        self.register_buffer('h_ticks', torch.tensor(HORIZONS, dtype=torch.float32).view(1, 1, NUM_H, 1))

        with torch.no_grad():
            init_sig = torch.tensor([0.28, 0.48, 0.78, 1.15, 1.65, 2.25, 3.00, 3.80, 4.70], dtype=torch.float32)
            bias = init_sig.log().view(1, NUM_H, 1).expand(num_modes, NUM_H, 3).reshape(-1)
            self.head_logstd.bias.copy_(bias)
            nn.init.normal_(self.head_delta.weight, std=0.015)
            nn.init.normal_(self.head_delta.bias, std=0.08)
            nn.init.zeros_(self.head_rho.weight); nn.init.zeros_(self.head_rho.bias)
            # Initialize velocity gates diverse across the 6 modes (from pause ~0.2 to fast ~1.25)
            nn.init.zeros_(self.head_vgate.weight)
            init_g = torch.tensor([0.4, 1.0, -0.4, -1.6, 0.0, 0.2], dtype=torch.float32)
            self.head_vgate.bias[0::2].copy_(init_g)
            self.head_vgate.bias[1::2].copy_(init_g)
            nn.init.constant_(self.head_fut_blk.bias, -3.5)

    def forward(self, hist, ctx, vbase, fine3d, top2d, return_trunk=False):
        h = F.silu(self.conv1(hist))
        h = F.silu(self.conv2(h))
        h = F.silu(self.conv3(h))
        h = F.silu(self.conv4(h))  # (B, 96, 6)

        # 4-head self-attention over 6 temporal tokens
        tok = h.transpose(1, 2)  # (B, 6, 96)
        B = tok.shape[0]
        qkv = self.attn_qkv(tok).view(B, 6, 3, 4, 24)
        q, k, v = qkv[:, :, 0].transpose(1, 2), qkv[:, :, 1].transpose(1, 2), qkv[:, :, 2].transpose(1, 2)
        att = F.softmax(torch.matmul(q, k.transpose(-2, -1)) * (24.0 ** -0.5), dim=-1)
        out_a = torch.matmul(att, v).transpose(1, 2).reshape(B, 6, 96)
        tok = self.attn_ln(tok + self.attn_out(out_a))  # (B, 6, 96)

        h_cat = torch.cat([tok[:, 0, :], tok.mean(dim=1), tok.amax(dim=1)], dim=-1)
        h_emb = F.silu(self.hist_proj(h_cat))

        v3 = F.silu(self.v3_1(fine3d.reshape(B, 2 * FINE_Y, FINE_Z, FINE_X)))
        v3 = F.silu(self.v3_2(v3))
        v3 = F.silu(self.v3_3(v3))
        v3_emb = F.silu(self.v3_proj(v3.reshape(B, -1)))

        t2 = F.silu(self.t2_1(top2d))
        t2 = F.silu(self.t2_2(t2))
        t2 = F.silu(self.t2_3(t2))
        t2_emb = F.silu(self.t2_proj(t2.reshape(B, -1)))

        x = F.silu(self.trunk_in(torch.cat([h_emb, ctx, v3_emb, t2_emb], dim=-1)))
        x = F.silu(x + self.res1b(F.silu(self.res1a(x))))
        x = F.silu(x + self.res2b(F.silu(self.res2a(x))))
        x = F.silu(x + self.res3b(F.silu(self.res3a(x))))

        logits = self.head_logits(x)
        vg = 1.65 * torch.sigmoid(self.head_vgate(x).view(B, self.num_modes, 2))  # (B, K, 2)
        # Expand velocity base (B, 1, 1, 3) * gate (B, K, 1, 3) * horizon ticks (1, 1, H, 1)
        g3 = torch.stack([vg[:, :, 0], vg[:, :, 1], vg[:, :, 0]], dim=-1).unsqueeze(2)  # (B, K, 1, 3)
        base_traj = g3 * vbase[:, None, None, :] * self.h_ticks  # (B, K, H, 3)
        delta = self.head_delta(x).view(B, self.num_modes, NUM_H, 3)
        means = base_traj + delta

        logstd = self.head_logstd(x).view(B, self.num_modes, NUM_H, 3).clamp(-2.1, 2.3)
        rho = 0.90 * torch.tanh(self.head_rho(x).view(B, self.num_modes, NUM_H))
        fut_blk_logits = self.head_fut_blk(x)

        if return_trunk:
            return x, logits, means, logstd, rho, fut_blk_logits
        return logits, means, logstd, rho, fut_blk_logits


def augment_v4(hist, ctx, vbase, fine3d, top2d, target, fut_blk, terrain_drop=0.10, rel_drop=0.08):
    B = hist.shape[0]
    dev = hist.device
    flip = torch.rand(B, device=dev) < 0.5
    if flip.any():
        hist = hist.clone(); ctx = ctx.clone(); vbase = vbase.clone()
        fine3d = fine3d.clone(); top2d = top2d.clone()
        target = target.clone(); fut_blk = fut_blk.clone()
        for ch in (0, 3, 6, 9, 18, 23, 27):
            hist[flip, ch] *= -1.0
        for ci in (0, 3, 6, 8, 32):
            ctx[flip, ci] *= -1.0
        vbase[flip, 0] *= -1.0
        fine3d[flip] = fine3d[flip].flip(dims=[-1])
        top2d[flip] = top2d[flip].flip(dims=[-1])
        target[flip, :, 0] *= -1.0
        fut_blk[flip] = fut_blk[flip].view(-1, FUT_Y, FUT_Z, FUT_X).flip(dims=[-1]).reshape(-1, FUT_DIM)

    if terrain_drop > 0:
        drop = torch.rand(B, device=dev) < terrain_drop
        if drop.any():
            fine3d = fine3d.clone(); top2d = top2d.clone(); ctx = ctx.clone()
            fine3d[drop] = 0.0; top2d[drop] = 0.0; ctx[drop, 21] = 0.0
    if rel_drop > 0:
        rdrop = torch.rand(B, device=dev) < rel_drop
        if rdrop.any():
            hist = hist.clone(); ctx = ctx.clone()
            hist[rdrop, 18:26] = 0.0; ctx[rdrop, 28] = 0.0; ctx[rdrop, 29] = 0.0
    return hist, ctx, vbase, fine3d, top2d, target, fut_blk


def loss_v4(logits, means, logstd, rho, fut_blk_logits, target, mask, fut_blk, has_fblk,
            h_weights, wta_w=0.08, volley_w=0.12, hit_w=0.0, fblk_w=0.20, nll_w=1.0):
    diff = target[:, None, :, :] - means  # (B, K, H, 3)
    inv_std = (-logstd).exp()
    zx = diff[..., 0] * inv_std[..., 0]
    zy = diff[..., 1] * inv_std[..., 1]
    zz = diff[..., 2] * inv_std[..., 2]

    one_m_r2 = (1.0 - rho * rho).clamp(min=0.10)
    quad_xz = (zx * zx - 2.0 * rho * zx * zz + zz * zz) / one_m_r2
    nll_xz = 0.5 * (quad_xz + 2.0 * (logstd[..., 0] + logstd[..., 2]) + torch.log(one_m_r2))
    nll_y = 0.5 * (zy * zy + 2.0 * logstd[..., 1])
    nll_h = nll_xz + nll_y  # (B, K, H)

    w = mask[:, None, :] * h_weights[None, None, :]
    w_sum = w.sum(dim=-1).clamp(min=1e-5)
    joint_logp = -(nll_h * w).sum(dim=-1)  # (B, K)
    log_pi = F.log_softmax(logits, dim=-1)
    mix_nll = -(torch.logsumexp(log_pi + joint_logp, dim=-1) / w_sum.squeeze(-1)).mean()

    # Smooth L1 (avoid aten::sgn on MPS!)
    l1_k = (torch.sqrt(diff.square() + 1e-6).sum(dim=-1) * w).sum(dim=-1) / w_sum
    wta = l1_k.min(dim=-1).values.mean()
    loss = nll_w * mix_nll + wta_w * wta

    # Differentiable hitbox capture probability for each mode k at horizon h
    if volley_w > 0 or hit_w > 0:
        temp = 0.25
        px = torch.sigmoid((0.88 - torch.sqrt(diff[..., 0].square() + 1e-5)) / temp)
        py = torch.sigmoid((1.25 - torch.sqrt(diff[..., 1].square() + 1e-5)) / temp)
        pz = torch.sigmoid((0.88 - torch.sqrt(diff[..., 2].square() + 1e-5)) / temp)
        p_hit_kh = (px * py * pz).clamp(0.0, 0.995)  # (B, K, H)
        mw = mask * h_weights[None, :]
        mw_sum = mw.sum(dim=-1).clamp(min=1e-5)

        if volley_w > 0:
            # Set-cover probability that AT LEAST ONE of the K modes covers the true trajectory!
            # Prevents mode collapse so snowball/egg volleys span uncertain speed distributions cleanly!
            p_miss_all = (1.0 - p_hit_kh).prod(dim=1)  # (B, H)
            p_cover = (1.0 - p_miss_all).clamp(min=1e-4)
            volley_loss = ((-torch.log(p_cover) * mw).sum(dim=-1) / mw_sum).mean()
            loss = loss + volley_w * volley_loss

        if hit_w > 0:
            pi = log_pi.exp()
            exp_hit = (pi[:, :, None] * p_hit_kh).sum(dim=1).clamp(min=1e-4)
            hit_loss = ((-torch.log(exp_hit) * mw).sum(dim=-1) / mw_sum).mean()
            loss = loss + hit_w * hit_loss

    # Auxiliary Future-Block-Placement BCE (teaches shared spatial CNN bridging, stairs, clutching & walls!)
    if fblk_w > 0:
        valid_fb = has_fblk > 0.5
        if valid_fb.any():
            pos_w = torch.tensor(45.0, device=logits.device)
            fb_bce = F.binary_cross_entropy_with_logits(
                fut_blk_logits[valid_fb], fut_blk[valid_fb], pos_weight=pos_w
            )
            loss = loss + fblk_w * fb_bce

    return loss, mix_nll.detach()


def export_java_v4(model: BowNeuralNetV4, out_path: Path, calibration_curve=None):
    out_path.parent.mkdir(parents=True, exist_ok=True)
    if calibration_curve is None:
        calibration_curve = np.linspace(0.0, 1.0, 21, dtype=np.float32)
    cal = np.asarray(calibration_curve, dtype=np.float32)

    tensors = [
        model.conv1.weight, model.conv1.bias,
        model.conv2.weight, model.conv2.bias,
        model.conv3.weight, model.conv3.bias,
        model.conv4.weight, model.conv4.bias,
        model.attn_qkv.weight, model.attn_qkv.bias,
        model.attn_out.weight, model.attn_out.bias,
        model.attn_ln.weight, model.attn_ln.bias,
        model.hist_proj.weight, model.hist_proj.bias,
        model.v3_1.weight, model.v3_1.bias,
        model.v3_2.weight, model.v3_2.bias,
        model.v3_3.weight, model.v3_3.bias,
        model.v3_proj.weight, model.v3_proj.bias,
        model.t2_1.weight, model.t2_1.bias,
        model.t2_2.weight, model.t2_2.bias,
        model.t2_3.weight, model.t2_3.bias,
        model.t2_proj.weight, model.t2_proj.bias,
        model.trunk_in.weight, model.trunk_in.bias,
        model.res1a.weight, model.res1a.bias, model.res1b.weight, model.res1b.bias,
        model.res2a.weight, model.res2a.bias, model.res2b.weight, model.res2b.bias,
        model.res3a.weight, model.res3a.bias, model.res3b.weight, model.res3b.bias,
        model.head_logits.weight, model.head_logits.bias,
        model.head_vgate.weight, model.head_vgate.bias,
        model.head_delta.weight, model.head_delta.bias,
        model.head_logstd.weight, model.head_logstd.bias,
        model.head_rho.weight, model.head_rho.bias,
        model.head_fut_blk.weight, model.head_fut_blk.bias,
    ]
    total_floats = sum(t.numel() for t in tensors)
    with open(out_path, 'wb') as f:
        f.write(struct.pack('>iiii', MAGIC_V4, model.num_modes, NUM_H, len(cal)))
        for hz in HORIZONS:
            f.write(struct.pack('>i', hz))
        f.write(cal.astype('>f4').tobytes())
        for t in tensors:
            f.write(t.detach().cpu().numpy().astype('>f4').ravel().tobytes())
    print(f'Exported BNN4 to {out_path} ({total_floats:,} floats, {out_path.stat().st_size:,} bytes)', flush=True)


def main():
    p = argparse.ArgumentParser()
    p.add_argument('--data', type=Path, default=Path('runtime/bow-aim/neural-data-v4'))
    p.add_argument('--out-dir', type=Path, default=Path('runtime/bow-aim/neural-model-v4'))
    p.add_argument('--export-bin', type=Path, default=Path('practice-client/assets/practice/bow-neural.bin'))
    p.add_argument('--stride', type=int, default=3)
    p.add_argument('--epochs-s1', type=int, default=14)
    p.add_argument('--epochs-s2', type=int, default=5)
    p.add_argument('--batch-size', type=int, default=512)
    p.add_argument('--synth-frac', type=float, default=0.06)
    args = p.parse_args()

    t0 = time.time()
    device = torch.device('mps' if torch.backends.mps.is_available() else 'cpu')
    print(f'Device: {device}', flush=True)
    args.out_dir.mkdir(parents=True, exist_ok=True)

    print('Loading TRAIN split (real matches + simulated-remote self)...', flush=True)
    tr_real = load_split_v4(args.data, 'train', stride=args.stride, include_self=True)
    n_real = len(tr_real[0])
    print(f'  Real train samples: {n_real:,} ({time.time()-t0:.1f}s)', flush=True)

    n_synth = int(round(n_real * args.synth_frac))
    if n_synth > 0:
        print(f'Generating {n_synth:,} physics-exact synthetic bridging samples (train ONLY)...', flush=True)
        tr_syn = generate_synthetic_bridging_samples(n_samples=n_synth, seed=777)
        tr_all = tuple(np.concatenate([a, b], axis=0) for a, b in zip(tr_real, tr_syn))
    else:
        tr_all = tr_real

    bkt = tr_all[9]
    bkt_counts = np.bincount(bkt, minlength=5)
    print(f'  Train buckets [bridge_up, bridge_flat, void_edge, fast_straight, general]: {bkt_counts.tolist()}', flush=True)

    w_s1 = np.array([1.0 / math.sqrt(max(1, c)) for c in bkt_counts], dtype=np.float64)
    probs_s1 = w_s1[bkt]; probs_s1 /= probs_s1.sum()
    w_s2 = np.array([1.0 / (max(1, c) ** 0.22) for c in bkt_counts], dtype=np.float64)
    probs_s2 = w_s2[bkt]; probs_s2 /= probs_s2.sum()

    print('Loading VAL split...', flush=True)
    val_arr = load_split_v4(args.data, 'val', stride=max(args.stride, 4), include_self=False)
    print(f'  Val samples: {len(val_arr[0]):,} ({time.time()-t0:.1f}s)', flush=True)

    # Vectorized zero-overhead batch slicer (10x faster than Python DataLoader __getitem__!)
    def slice_batch(arrays, idx_np):
        bh = torch.from_numpy(arrays[0][idx_np]).to(device)
        bc = torch.from_numpy(arrays[1][idx_np]).to(device)
        bvb = torch.from_numpy(arrays[2][idx_np]).to(device)
        bf3 = torch.from_numpy(arrays[3][idx_np]).to(device).float()
        t_raw = torch.from_numpy(arrays[4][idx_np]).to(device)
        bt2 = t_raw.float() * 0.01
        bt2[:, 0] = torch.where(t_raw[:, 0] <= -125, torch.tensor(-1.4, device=device), bt2[:, 0])
        btgt = torch.from_numpy(arrays[5][idx_np]).to(device)
        bmsk = torch.from_numpy(arrays[6][idx_np]).to(device)
        bfb = torch.from_numpy(arrays[7][idx_np]).to(device).float().reshape(len(idx_np), -1)
        bhfb = torch.from_numpy(arrays[8][idx_np]).to(device)
        return bh, bc, bvb, bf3, bt2, btgt, bmsk, bfb, bhfb

    model = BowNeuralNetV4(NUM_MODES).to(device)
    n_params = sum(p.numel() for p in model.parameters())
    print(f'BowNeuralNetV4 parameters: {n_params:,}', flush=True)
    h_weights = torch.from_numpy(H_WEIGHTS_NP).to(device)
    rng_np = np.random.default_rng(42)

    @torch.no_grad()
    def eval_val():
        model.eval()
        tot_nll = 0.0; tot_fblk_tp = 0.0; tot_fblk_pos = 0.0; n_b = 0
        nv = len(val_arr[0])
        for b0 in range(0, nv, 512):
            idx_np = np.arange(b0, min(nv, b0 + 512))
            bh, bc, bvb, bf3, bt2, btgt, bmsk, bfb, bhfb = slice_batch(val_arr, idx_np)
            lo, me, ls, rh, fbl = model(bh, bc, bvb, bf3, bt2)
            _, nll = loss_v4(lo, me, ls, rh, fbl, btgt, bmsk, bfb, bhfb, h_weights,
                             wta_w=0.0, volley_w=0.0, hit_w=0.0, fblk_w=0.0)
            tot_nll += float(nll.item()); n_b += 1
            vf = bhfb > 0.5
            if vf.any():
                probs_fb = torch.sigmoid(fbl[vf])
                pos_mask = bfb[vf] > 0.5
                tot_fblk_tp += float((probs_fb[pos_mask] > 0.5).sum().item())
                tot_fblk_pos += float(pos_mask.sum().item())
        return tot_nll / max(1, n_b), tot_fblk_tp / max(1.0, tot_fblk_pos)

    # ---- Stage 1: Representation + Multi-Task + Volley Set-Cover + Square-Root Stratified Sampling ----
    n_tr = len(tr_all[0])
    steps_per_ep = min(n_tr, 240000)
    opt = torch.optim.AdamW(model.parameters(), lr=1.8e-3, weight_decay=2e-4)
    sched = torch.optim.lr_scheduler.CosineAnnealingLR(opt, T_max=args.epochs_s1)
    best_val = float('inf')
    best_s1_state = None

    print(f'\n--- Stage 1: {args.epochs_s1} epochs ({steps_per_ep:,} stratified samples/epoch, bs={args.batch_size}) ---', flush=True)
    for ep in range(1, args.epochs_s1 + 1):
        ep_idx = rng_np.choice(n_tr, size=steps_per_ep, replace=True, p=probs_s1)
        model.train()
        tr_loss = 0.0; tr_nll = 0.0; nb = 0
        for b0 in range(0, steps_per_ep, args.batch_size):
            idx_np = ep_idx[b0:b0 + args.batch_size]
            bh, bc, bvb, bf3, bt2, btgt, bmsk, bfb, bhfb = slice_batch(tr_all, idx_np)
            bh, bc, bvb, bf3, bt2, btgt, bfb = augment_v4(bh, bc, bvb, bf3, bt2, btgt, bfb)
            lo, me, ls, rh, fbl = model(bh, bc, bvb, bf3, bt2)
            loss, nll = loss_v4(lo, me, ls, rh, fbl, btgt, bmsk, bfb, bhfb, h_weights,
                                wta_w=0.08, volley_w=0.12, hit_w=0.0, fblk_w=0.20, nll_w=1.0)
            opt.zero_grad()
            loss.backward()
            torch.nn.utils.clip_grad_norm_(model.parameters(), 2.5)
            opt.step()
            tr_loss += float(loss.item()); tr_nll += float(nll.item()); nb += 1
        sched.step()
        val_nll, fblk_rec = eval_val()
        print(f'  S1 ep {ep:2d}/{args.epochs_s1} | tr_loss={tr_loss/nb:.4f} tr_nll={tr_nll/nb:.4f} '
              f'| val_nll={val_nll:.4f} fblk_recall@0.5={fblk_rec*100:.1f}% ({time.time()-t0:.0f}s)', flush=True)
        if val_nll < best_val:
            best_val = val_nll
            best_s1_state = {k: v.detach().cpu().clone() for k, v in model.state_dict().items()}

    model.load_state_dict(best_s1_state)
    torch.save({'state_dict': best_s1_state, 'val_nll': best_val}, args.out_dir / 'stage1.pt')

    # ---- Stage 2: Direct Expected-Hit-Probability + Calibrated Fine-Tuning ----
    print(f'\n--- Stage 2: {args.epochs_s2} epochs (Hit-Prob + Volley + Natural Calibration) ---', flush=True)
    opt2 = torch.optim.AdamW(model.parameters(), lr=3.0e-4, weight_decay=2e-4)
    sched2 = torch.optim.lr_scheduler.CosineAnnealingLR(opt2, T_max=args.epochs_s2)
    best_s2_val = float('inf')
    best_s2_state = best_s1_state

    for ep in range(1, args.epochs_s2 + 1):
        ep_idx = rng_np.choice(n_tr, size=steps_per_ep, replace=True, p=probs_s2)
        model.train()
        tr_loss = 0.0; nb = 0
        for b0 in range(0, steps_per_ep, args.batch_size):
            idx_np = ep_idx[b0:b0 + args.batch_size]
            bh, bc, bvb, bf3, bt2, btgt, bmsk, bfb, bhfb = slice_batch(tr_all, idx_np)
            bh, bc, bvb, bf3, bt2, btgt, bfb = augment_v4(bh, bc, bvb, bf3, bt2, btgt, bfb,
                                                          terrain_drop=0.06, rel_drop=0.05)
            lo, me, ls, rh, fbl = model(bh, bc, bvb, bf3, bt2)
            loss, _ = loss_v4(lo, me, ls, rh, fbl, btgt, bmsk, bfb, bhfb, h_weights,
                              wta_w=0.04, volley_w=0.10, hit_w=0.35, fblk_w=0.12, nll_w=0.75)
            opt2.zero_grad()
            loss.backward()
            torch.nn.utils.clip_grad_norm_(model.parameters(), 2.0)
            opt2.step()
            tr_loss += float(loss.item()); nb += 1
        sched2.step()
        val_nll, fblk_rec = eval_val()
        print(f'  S2 ep {ep:2d}/{args.epochs_s2} | tr_loss={tr_loss/nb:.4f} '
              f'| val_nll={val_nll:.4f} fblk_recall@0.5={fblk_rec*100:.1f}% ({time.time()-t0:.0f}s)', flush=True)
        if val_nll < best_s2_val:
            best_s2_val = val_nll
            best_s2_state = {k: v.detach().cpu().clone() for k, v in model.state_dict().items()}

    torch.save({'state_dict': best_s2_state, 'val_nll': best_s2_val, 's1_val_nll': best_val}, args.out_dir / 'stage2.pt')
    model.load_state_dict(best_s2_state)
    export_java_v4(model, args.export_bin)
    print(f'Done training BNN4 in {time.time()-t0:.1f}s!', flush=True)


if __name__ == '__main__':
    main()
