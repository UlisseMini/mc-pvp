"""BowAim v3 Tactical Projectile Engine — Offline Physics, Learned V_wait Training, and Sealed Benchmarks.

Components:
1. Exact 1.8.9 Void-KB Value Scorer (1-to-1 mirror of VoidKbValue.java)
2. Exact 1.8.9 Throwable (Snowball/Egg) Ballistics + Airborne Volley Coverage
3. Two-Round Causal Longstaff-Schwartz Fitted Value Iteration (FVI) for BowWaitNet (V_wait):
   - Zero oracle inflation (uses BowNeuralNetV2's actual shot!)
   - Zero coin-flip Jensen bias (smooth spread-integrated true hit prob!)
   - Zero hindsight clairvoyance bias (Round 1 & 2 targets are exact realized causal continuation rewards!)
   - Shooter-direction conditioned in target's cardinal frame q + 2x horizontal mirror symmetry
4. Validation tuning of fireAlpha strictly on val (bw-08 + 3 val SkyWars)
5. Sealed Test Benchmarks on untouched bw-09..12 + 5 sealed SkyWars:
   - Bow Tactical Benchmark: Void Kills / 10 Arrows & Hit-Equivalent EV / Arrow
   - Snowball/Egg 3-Throw Burst Benchmark: Joint Void-Kill Rate & Joint Hit Rate per 3-throw burst
"""
import argparse
import json
import math
import struct
import time
from pathlib import Path

import numpy as np
import torch
from torch import nn
from torch.nn import functional as F

from train_v2 import (
    BowNeuralNetV2, HORIZONS, NUM_H, MAX_H, NUM_MODES, FINE_DIM, COARSE_DIM,
    build_features_v2, contiguous_runs, rot_xz, inv_rot_xz,
)
from eval_and_calibrate import (
    DRAG, GRAVITY, OFFSET, SPREAD, SPEED_FULL,
    travel, sample_path, linear_baseline_path, smart_release_decision, apply_calibration,
)
from eval_v2 import (
    interp_modes_v2, correlated_box_prob, prob1d, shift_path,
)

THROW_SPEED = 1.5
THROW_GRAVITY = 0.03
BOW_BASE = 1.0
BOW_VOID = 8.0
THROW_BASE = 0.4
THROW_VOID = 10.0

SIM_TICKS = 14
VOID_SCAN_DEPTH = 18
HALF_W = 0.29
AIR_COUNTER_ACCEL = 0.015
GROUND_COUNTER_ACCEL = 0.05

MAGIC_WAIT = 0x42574154  # 'BWAT'
TRUNK_DIM = 224
TACTICAL_DIM = 10
WAIT_IN_DIM = TRUNK_DIM + TACTICAL_DIM  # 234


def fall_g(t, gravity=GRAVITY):
    return gravity * (t - travel(t)) / (1.0 - DRAG)


def shot_from_angles_g(sx, eye_y, sz, speed, yaw, pitch, t_hit, gravity=GRAVITY):
    origin_y = eye_y - 0.1
    x0 = sx - OFFSET * math.cos(yaw)
    y0 = origin_y
    z0 = sz - OFFSET * math.sin(yaw)
    vx = -math.sin(yaw) * math.cos(pitch) * speed
    vy = -math.sin(pitch) * speed
    vz = math.cos(yaw) * math.cos(pitch) * speed
    return (yaw, pitch, t_hit, x0, y0, z0, vx, vy, vz, gravity)


def _need_g(x, y, z, feet, height, t, gravity):
    p = sample_path(feet, t)
    dx = p[0] - x
    dz = p[2] - z
    dy = p[1] + height - y + fall_g(t, gravity)
    return math.sqrt(max(0.0, dx * dx + dz * dz - OFFSET * OFFSET) + dy * dy) / travel(t)


def intercept_g(x, eye_y, z, speed, feet, height, gravity=GRAVITY):
    if speed <= 0.0:
        return None
    origin_y = eye_y - 0.1
    initial = float('inf')
    n = len(feet)
    for t in range(1, n):
        req = _need_g(x, origin_y, z, feet, height, float(t), gravity)
        if req <= speed and initial > speed:
            lo = max(0.01, t - 1.0)
            hi = float(t)
            for _ in range(17):
                mid = 0.5 * (lo + hi)
                if _need_g(x, origin_y, z, feet, height, mid, gravity) > speed:
                    lo = mid
                else:
                    hi = mid
            p = sample_path(feet, hi)
            dx = p[0] - x
            dz = p[2] - z
            fwd = math.sqrt(max(0.0, dx * dx + dz * dz - OFFSET * OFFSET))
            yaw = math.atan2(-dx, dz) - math.atan2(OFFSET, fwd)
            dy = p[1] + height - origin_y + fall_g(hi, gravity)
            pitch = -math.atan2(dy, fwd)
            return shot_from_angles_g(x, eye_y, z, speed, yaw, pitch, hi, gravity)
        initial = req
    return None


def ray_blocked_g(shot, solid_set):
    if solid_set is None or shot is None:
        return False
    _, _, t_hit, x0, y0, z0, vx, vy, vz, gravity = shot
    steps = max(2, int(t_hit * 2))
    for st in range(1, steps):
        tt = t_hit * st / steps
        tr = travel(tt)
        if (int(math.floor(x0 + vx * tr)),
            int(math.floor(y0 + vy * tr - fall_g(tt, gravity))),
            int(math.floor(z0 + vz * tr))) in solid_set:
            return True
    return False


# ---- Exact 1-to-1 mirror of VoidKbValue.java ----

def _has_floor(x, y, z, solid_set):
    by = int(math.floor(y - 0.05))
    if by < 0:
        return False
    x0, x1 = int(math.floor(x - HALF_W)), int(math.floor(x + HALF_W))
    z0, z1 = int(math.floor(z - HALF_W)), int(math.floor(z + HALF_W))
    return ((x0, by, z0) in solid_set or (x1, by, z0) in solid_set
            or (x0, by, z1) in solid_set or (x1, by, z1) in solid_set)


def _hits_wall(x, y, z, solid_set):
    y_lo = int(math.floor(y + 0.25))
    y_hi = int(math.floor(y + 1.45))
    x0, x1 = int(math.floor(x - HALF_W)), int(math.floor(x + HALF_W))
    z0, z1 = int(math.floor(z - HALF_W)), int(math.floor(z + HALF_W))
    for by in range(y_lo, y_hi + 1):
        if by < 0:
            continue
        if ((x0, by, z0) in solid_set or (x1, by, z0) in solid_set
                or (x0, by, z1) in solid_set or (x1, by, z1) in solid_set):
            return True
    return False


def _hits_ceiling(x, y, z, solid_set):
    by = int(math.floor(y + 1.80))
    if by < 0:
        return False
    x0, x1 = int(math.floor(x - HALF_W)), int(math.floor(x + HALF_W))
    z0, z1 = int(math.floor(z - HALF_W)), int(math.floor(z + HALF_W))
    return ((x0, by, z0) in solid_set or (x1, by, z0) in solid_set
            or (x0, by, z1) in solid_set or (x1, by, z1) in solid_set)


def _scan_floor_distance(x, base_y, z, solid_set):
    x0, x1 = int(math.floor(x - HALF_W)), int(math.floor(x + HALF_W))
    z0, z1 = int(math.floor(z - HALF_W)), int(math.floor(z + HALF_W))
    for dy in range(VOID_SCAN_DEPTH + 1):
        by = base_y - dy
        if by < 0:
            return -1
        if ((x0, by, z0) in solid_set or (x1, by, z0) in solid_set
                or (x0, by, z1) in solid_set or (x1, by, z1) in solid_set):
            return dy
    return -1


def simulate_kb_void(px, py, pz, tvx, tvy, tvz, ivx, ivz, punch_level, solid_set):
    """Exact 1-to-1 mirror of VoidKbValue.simulate."""
    if solid_set is None:
        return False, 0.0
    vx = max(-0.75, min(0.75, tvx))
    vy = max(-1.50, min(0.55, tvy))
    vz = max(-0.75, min(0.75, tvz))

    h = math.hypot(ivx, ivz)
    dx = ivx / h if h > 1e-6 else 0.0
    dz = ivz / h if h > 1e-6 else 0.0

    vx = vx * 0.5 + dx * 0.4
    vz = vz * 0.5 + dz * 0.4
    vy = min(0.4, vy * 0.5 + 0.4)

    if punch_level > 0:
        vx += dx * (0.6 * punch_level)
        vz += dz * (0.6 * punch_level)
        vy += 0.1

    x, y, z = px, py, pz
    on_ground = _has_floor(x, y, z, solid_set)

    for _ in range(SIM_TICKS):
        counter = GROUND_COUNTER_ACCEL if on_ground else AIR_COUNTER_ACCEL
        vx -= dx * counter
        vz -= dz * counter

        nx = x + vx
        if not _hits_wall(nx, y, z, solid_set):
            x = nx
        else:
            vx = 0.0
        nz = z + vz
        if not _hits_wall(x, y, nz, solid_set):
            z = nz
        else:
            vz = 0.0

        ny = y + vy
        if vy <= 0.0:
            if _has_floor(x, ny, z, solid_set):
                y = math.floor(ny - 0.05) + 1.0
                vy = 0.0
                on_ground = True
            else:
                y = ny
                on_ground = False
        else:
            if _hits_ceiling(x, ny, z, solid_set):
                vy = 0.0
            else:
                y = ny
            on_ground = False

        vy = (vy - 0.08) * 0.98
        drag = 0.546 if on_ground else 0.91
        vx *= drag
        vz *= drag

        if y < -4.0:
            return True, py - y

    drop = max(0.0, py - y)
    if on_ground:
        return False, drop

    fx = x + vx * 1.5
    fz = z + vz * 1.5
    if not _hits_wall(fx, y, fz, solid_set):
        x, z = fx, fz
    base_y = int(math.floor(y))
    floor_dist = _scan_floor_distance(x, base_y, z, solid_set)
    if floor_dist < 0:
        return True, max(drop, float(VOID_SCAN_DEPTH))
    total_drop = max(0.0, py - (base_y - floor_dist + 1.0))
    return False, total_drop


def _drop_bonus(drop_blocks):
    if drop_blocks >= 9.0:
        return 0.8
    if drop_blocks >= 5.0:
        return 0.4
    return 0.0


def score_mode_void(mx, my, mz, tvx, tvy, tvz, sx, sz, ivx, ivz,
                    is_throwable, punch_level, solid_set):
    """Exact 1-to-1 mirror of VoidKbValue.scoreMode (without HP finish override for offline parity)."""
    if solid_set is None:
        base = THROW_BASE if is_throwable else BOW_BASE
        return 0.0, 0.0, base

    h = math.hypot(ivx, ivz)
    dx = ivx / h if h > 1e-6 else 0.0
    dz = ivz / h if h > 1e-6 else 0.0
    sigma_along = min(1.2, math.hypot(dx * sx, dz * sz))

    void_prob = 0.0
    fall_bonus = 0.0
    if sigma_along < 0.18:
        vk, drop = simulate_kb_void(mx, my, mz, tvx, tvy, tvz, ivx, ivz, punch_level, solid_set)
        void_prob = 1.0 if vk else 0.0
        fall_bonus = _drop_bonus(drop)
    else:
        step = 0.55 * sigma_along
        for off, w in ((-step, 0.25), (0.0, 0.50), (step, 0.25)):
            vk, drop = simulate_kb_void(mx + dx * off, my, mz + dz * off,
                                        tvx, tvy, tvz, ivx, ivz, punch_level, solid_set)
            if vk:
                void_prob += w
            else:
                fall_bonus += w * _drop_bonus(drop)

    if is_throwable:
        val = THROW_BASE + (THROW_VOID - THROW_BASE) * void_prob + (1.0 - void_prob) * (0.4 * fall_bonus)
    else:
        val = BOW_BASE + (BOW_VOID - BOW_BASE) * void_prob + (1.0 - void_prob) * fall_bonus
    return void_prob, fall_bonus, val


# ---- Per-mode probabilities & EV aiming ----

def mode_hit_probs_g(shot, pi, paths, stds, rhos, lead_shift=0.0):
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
    sig_p = SPREAD * spd * tr
    var_p = sig_p * sig_p

    t_eval = min(float(MAX_H), t_hit + max(0.0, lead_shift))
    lo = int(t_eval)
    hi = min(MAX_H, lo + 1)
    frac = t_eval - lo
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


def mode_hit_prob_at_g(shot, paths, stds, rhos, k, eval_tick):
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
    sig_p = SPREAD * spd * tr
    var_p = sig_p * sig_p

    lo = int(eval_tick)
    hi = min(MAX_H, lo + 1)
    frac = eval_tick - lo
    mu = paths[k, lo] * (1.0 - frac) + paths[k, hi] * frac
    st = stds[k, lo] * (1.0 - frac) + stds[k, hi] * frac
    r0 = rhos[k, lo] * (1.0 - frac) + rhos[k, hi] * frac
    cov_xz = r0 * st[0] * st[2]
    sx = math.sqrt(st[0] * st[0] + var_p)
    sy = math.sqrt(st[1] * st[1] + var_p)
    sz = math.sqrt(st[2] * st[2] + var_p)
    rho_eff = max(-0.92, min(0.92, cov_xz / max(1e-6, sx * sz)))
    return correlated_box_prob(ax, ay, az, mu[0], mu[1], mu[2], sx, sy, sz, rho_eff, ivx, ivy, ivz)


def compute_mode_values(shot, pi, paths, stds, is_throwable, punch_level, solid_set, lead_shift=0.0):
    K = len(pi)
    vals = np.full(K, THROW_BASE if is_throwable else BOW_BASE, dtype=np.float64)
    voids = np.zeros(K, dtype=np.float64)
    if shot is None:
        return vals, voids
    _, _, t_hit, _, _, _, vx, _, vz, _ = shot
    drag_t = DRAG ** t_hit
    ivx = vx * drag_t
    ivz = vz * drag_t
    t_eval = min(float(MAX_H), t_hit + max(0.0, lead_shift))
    lo = int(t_eval)
    hi = min(MAX_H, lo + 1)
    frac = t_eval - lo
    prev_t = max(0, lo - 1)
    for k in range(K):
        if pi[k] < 0.02:
            continue
        mu = paths[k, lo] * (1.0 - frac) + paths[k, hi] * frac
        st = stds[k, lo] * (1.0 - frac) + stds[k, hi] * frac
        vel = paths[k, hi] - paths[k, prev_t] if hi > prev_t else np.zeros(3)
        dt = max(1, hi - prev_t)
        tvx, tvy, tvz = vel[0] / dt, vel[1] / dt, vel[2] / dt
        vp, _, val = score_mode_void(mu[0], mu[1], mu[2], tvx, tvy, tvz,
                                     st[0], st[2], ivx, ivz,
                                     is_throwable, punch_level, solid_set)
        voids[k] = vp
        vals[k] = val
    return vals, voids


def solve_aim_ev(sx, eye_y, sz, speed, gravity, pi, paths, stds, rhos,
                 solid_set, is_throwable=False, punch_level=0,
                 eff_weights=None, aim_height=0.90, lead_shift=0.0,
                 use_void_ev=True, refine=True):
    """Unified Bow/Throwable EV-optimal aim solver with optional Volley Coverage weights."""
    K = len(pi)
    w_eff = pi if eff_weights is None else eff_weights
    mean_path = np.tensordot(pi, paths, axes=([0], [0]))

    best_shot = None
    best_obj = -1.0
    cached_vals = None
    cached_voids = None

    # Pre-check each discrete mode + mean path (cache VoidKbValue across candidates at the same tick!)
    for c in range(K + 1):
        if c < K and w_eff[c] < 0.02:
            continue
        cand_path = paths[c] if c < K else mean_path
        if lead_shift > 0.0:
            cand_path = shift_path(cand_path, lead_shift)
        s = intercept_g(sx, eye_y, sz, speed, cand_path, aim_height, gravity)
        if s is None or ray_blocked_g(s, solid_set):
            continue
        mp = mode_hit_probs_g(s, pi, paths, stds, rhos, lead_shift=lead_shift)
        if use_void_ev:
            if cached_vals is None:
                cached_vals, cached_voids = compute_mode_values(
                    s, pi, paths, stds, is_throwable, punch_level, solid_set, lead_shift
                )
            obj = float(np.sum(w_eff * mp * cached_vals))
        else:
            obj = float(np.sum(w_eff * mp))
        if obj > best_obj:
            best_obj = obj
            best_shot = s

    if best_shot is None:
        return None, 0.0, 0.0, 0.0, np.zeros(K), np.zeros(K)

    if cached_vals is None:
        cached_vals, cached_voids = compute_mode_values(
            best_shot, pi, paths, stds, is_throwable, punch_level, solid_set, lead_shift
        )

    if refine:
        yaw, pitch, t_hit = best_shot[0], best_shot[1], best_shot[2]
        for step_deg in (0.65, 0.28):
            step = math.radians(step_deg)
            for dy, dp in ((step, 0.0), (-step, 0.0), (0.0, step), (0.0, -step)):
                cand = shot_from_angles_g(sx, eye_y, sz, speed, yaw + dy, pitch + dp, t_hit, gravity)
                if ray_blocked_g(cand, solid_set):
                    continue
                mp = mode_hit_probs_g(cand, pi, paths, stds, rhos, lead_shift=lead_shift)
                obj = float(np.sum(w_eff * mp * cached_vals)) if use_void_ev else float(np.sum(w_eff * mp))
                if obj > best_obj + 1e-4:
                    best_obj = obj
                    best_shot = cand
                    yaw, pitch = yaw + dy, pitch + dp

    # Always report true EV, p_hit, p_void of the selected shot!
    mp = mode_hit_probs_g(best_shot, pi, paths, stds, rhos, lead_shift=lead_shift)
    ev = float(np.sum(w_eff * mp * cached_vals))
    p_hit = float(np.sum(pi * mp))
    p_void = float(np.sum(pi * mp * cached_voids))
    return best_shot, ev, p_hit, p_void, cached_vals, cached_voids


# ---- Smooth Ground-Truth Outcome Evaluator (Zero Coin-Flip Variance!) ----

def eval_shot_truth_smooth(shot, true_spos, idx, solid_set, is_throwable=False, punch_level=0):
    """Evaluate a shot deterministically against the true future trajectory using analytic
    projectile-spread integration over the true hitbox at t_hit -> (p_hit_true, void_kill_true, ev_true).
    """
    if shot is None:
        return 0.0, False, 0.0
    _, _, t_hit, x0, y0, z0, vx, vy, vz, gravity = shot
    if t_hit <= 0.0 or idx + int(math.ceil(t_hit)) >= len(true_spos):
        return 0.0, False, 0.0
    if ray_blocked_g(shot, solid_set):
        return 0.0, False, 0.0

    # Check best contact tick near t_hit (within +-1 tick to allow exact swept intercept)
    best_p = 0.0
    best_t = t_hit
    t_center = int(round(t_hit))
    spd = math.sqrt(vx * vx + vy * vy + vz * vz)
    for tc in range(max(1, t_center - 1), min(len(true_spos) - 1 - idx, t_center + 2)):
        tr = travel(float(tc))
        ax = x0 + vx * tr
        ay = y0 + vy * tr - fall_g(float(tc), gravity)
        az = z0 + vz * tr
        drag_t = DRAG ** tc
        ivx = vx * drag_t
        ivy = vy * drag_t - gravity * (1.0 - drag_t) / (1.0 - DRAG)
        ivz = vz * drag_t
        sig = max(0.05, SPREAD * spd * tr)

        tp = true_spos[idx + tc]
        slack_x = 0.6 + 0.35 * abs(ivx)
        slack_y_lo = -0.3 - 0.30 * abs(ivy)
        slack_y_hi = 2.1 + 0.30 * abs(ivy)
        slack_z = 0.6 + 0.35 * abs(ivz)
        px = prob1d(ax - tp[0] - slack_x, ax - tp[0] + slack_x, sig)
        py = prob1d(ay - tp[1] - slack_y_hi, ay - tp[1] - slack_y_lo, sig)
        pz = prob1d(az - tp[2] - slack_z, az - tp[2] + slack_z, sig)
        p = px * py * pz
        if p > best_p:
            best_p = p
            best_t = tc

    if best_p < 1e-4:
        return 0.0, False, 0.0

    tc = int(round(best_t))
    tp = true_spos[idx + tc]
    tp_prev = true_spos[max(0, idx + tc - 1)]
    tvx, tvy, tvz = float(tp[0] - tp_prev[0]), float(tp[1] - tp_prev[1]), float(tp[2] - tp_prev[2])
    drag_t = DRAG ** tc
    ivx = vx * drag_t
    ivz = vz * drag_t
    vk, drop = simulate_kb_void(float(tp[0]), float(tp[1]), float(tp[2]),
                                tvx, tvy, tvz, ivx, ivz, punch_level, solid_set)
    fb = _drop_bonus(drop)
    if is_throwable:
        val = THROW_VOID if vk else (THROW_BASE + 0.4 * fb)
    else:
        val = BOW_VOID if vk else (BOW_BASE + fb)
    return best_p, vk, best_p * val


# ---- Forward Trunk Extraction & BowWaitNet ----

@torch.no_grad()
def forward_with_trunk(model: BowNeuralNetV2, hist, ctx, vf, vc):
    h = F.silu(model.conv1(hist))
    h = F.silu(model.conv2(h))
    h = F.silu(model.conv3(h))
    h = F.silu(model.conv4(h))
    h_cat = torch.cat([h[:, :, 0], h.mean(dim=-1), h.amax(dim=-1)], dim=-1)
    h_emb = F.silu(model.hist_proj(h_cat))
    v = torch.cat([F.silu(model.vf_fc(vf)), F.silu(model.vc_fc(vc))], dim=-1)
    v_emb = F.silu(model.vox_fuse(v))
    x = F.silu(model.trunk_in(torch.cat([h_emb, ctx, v_emb], dim=-1)))
    x = F.silu(x + model.res1b(F.silu(model.res1a(x))))
    x = F.silu(x + model.res2b(F.silu(model.res2a(x))))

    logits = model.head_logits(x)
    means = model.head_means(x).view(-1, model.num_modes, NUM_H, 3)
    logstd = model.head_logstd(x).view(-1, model.num_modes, NUM_H, 3).clamp(-2.1, 2.1)
    rho = 0.90 * torch.tanh(model.head_rho(x).view(-1, model.num_modes, NUM_H))
    return x, logits, means, logstd, rho


def mirror_batch_inputs(hist, ctx, vf, vc):
    h = hist.clone()
    c = ctx.clone()
    for ch in (0, 3, 6, 9, 18, 23):
        h[:, ch] *= -1.0
    for ci in (0, 3, 6, 8):
        c[:, ci] *= -1.0
    vf_m = vf.view(-1, 7, 9, 9).flip(dims=[-1]).reshape(-1, FINE_DIM)
    vc_m = vc.view(-1, 5, 11, 11).flip(dims=[-1]).reshape(-1, COARSE_DIM)
    return h, c, vf_m, vc_m


class BowWaitNet(nn.Module):
    def __init__(self, in_dim=WAIT_IN_DIM, h1=96, h2=48):
        super().__init__()
        self.h1 = h1
        self.h2 = h2
        self.fc1 = nn.Linear(in_dim, h1)
        self.fc2 = nn.Linear(h1, h2)
        self.fc3 = nn.Linear(h2, 1)

    def forward(self, x):
        h = F.silu(self.fc1(x))
        h = F.silu(self.fc2(h))
        return F.softplus(self.fc3(h)).squeeze(-1)


def build_tactical_vec(sx, sy, sz, tx, ty, tz, q, q_shoot, p_hit, p_void, is_throwable, has_los):
    dx, dy, dz = sx - tx, sy - ty, sz - tz
    srx, srz = rot_xz(dx, dz, q)
    dist = math.sqrt(dx * dx + dy * dy + dz * dz)
    return np.array([
        max(-2.0, min(2.0, srx / 32.0)),
        max(-2.0, min(2.0, dy / 32.0)),
        max(-2.0, min(2.0, srz / 32.0)),
        max(0.0, min(2.5, dist / 32.0)),
        math.log1p(dist) / 4.0,
        max(0.0, min(2.0, q_shoot / 8.0)),
        max(0.0, min(1.0, p_hit)),
        max(0.0, min(1.0, p_void)),
        1.0 if is_throwable else 0.0,
        1.0 if has_los else 0.0,
    ], dtype=np.float32)


def export_wait_bin(model: BowWaitNet, fire_alpha: float, out_path: Path):
    out_path.parent.mkdir(parents=True, exist_ok=True)
    with open(out_path, 'wb') as f:
        f.write(struct.pack('>iiiif', MAGIC_WAIT, WAIT_IN_DIM, model.h1, model.h2, float(fire_alpha)))
        for t in (model.fc1.weight, model.fc1.bias, model.fc2.weight, model.fc2.bias,
                  model.fc3.weight, model.fc3.bias):
            arr = t.detach().cpu().numpy().astype('>f4').ravel()
            f.write(arr.tobytes())
    print(f'Exported BowWaitNet to {out_path} ({out_path.stat().st_size:,} bytes, alpha={fire_alpha:.3f})')


# ---- Episode Builder on Real Map Vantage Points ----

def build_solid_set(npz):
    if 'terrain_xyz' not in npz or len(npz['terrain_xyz']) == 0:
        return None
    xyz = npz['terrain_xyz']
    return set(map(tuple, xyz.tolist()))


def collect_vantage_pool(npz, step=40):
    pts = []
    for p in range(int(npz['num_players'][0])):
        pos = npz[f'p{p}_pos']
        if len(pos) > 0:
            pts.append(pos[::step])
    if 'self_pos' in npz and len(npz['self_pos']) > 0:
        pts.append(npz['self_pos'][::step])
    if not pts:
        return np.zeros((0, 3), dtype=np.float32)
    return np.concatenate(pts, axis=0)


def check_los_ray(sx, eye_y, sz, tx, ty, tz, solid_set):
    dx, dy, dz = tx - sx, ty - eye_y, tz - sz
    dist = math.sqrt(dx * dx + dy * dy + dz * dz)
    steps = max(4, int(dist * 1.5))
    for i in range(1, steps):
        u = i / float(steps)
        if (int(math.floor(sx + dx * u)),
            int(math.floor(eye_y + dy * u)),
            int(math.floor(sz + dz * u))) in solid_set:
            return False
    return True


def extract_episodes(model, cal_curve, device, data_dir: Path, split: str,
                     win_len=60, win_stride=45, max_vantages=2,
                     include_throwables=True, refine=False, compute_mirrors=False,
                     seed=42, max_per_match=None):
    """Extract continuous 60-tick (3.0s) tactical engagement windows on real map vantage points."""
    rng = np.random.default_rng(seed)
    manifest = json.loads((data_dir / 'manifest.json').read_text())
    episodes = []

    for m in manifest['matches']:
        if m['split'] != split:
            continue
        npz = np.load(data_dir / m['file'])
        solid_set = build_solid_set(npz)
        if solid_set is None:
            continue
        vantages = collect_vantage_pool(npz, step=35)
        if len(vantages) < 5:
            continue
        match_eps = 0

        for p_idx in range(int(npz['num_players'][0])):
            if max_per_match and match_eps >= max_per_match:
                break
            ticks = npz[f'p{p_idx}_tick']
            pos = npz[f'p{p_idx}_pos']
            spos = npz[f'p{p_idx}_spos']
            rot = npz[f'p{p_idx}_rot']
            flags = npz[f'p{p_idx}_flags']
            rel = npz[f'p{p_idx}_rel']
            vox = npz[f'p{p_idx}_vox']
            cvox = npz[f'p{p_idx}_cvox']
            past, fut = contiguous_runs(ticks, pos)

            # Collect candidate window starts
            starts = []
            i = 0
            while i < len(ticks):
                if past[i] >= 10 and fut[i] >= win_len + MAX_H:
                    starts.append(i)
                    i += win_stride
                else:
                    i += 5
            if not starts:
                continue

            # Precompute BowNeuralNetV2 features & trunk embeddings for all ticks needed
            needed = sorted({s + dt for s in starts for dt in range(win_len)})
            hl, cl, vfl, vcl, ql = [], [], [], [], []
            for idx in needed:
                h, c, vf, vc, q = build_features_v2(pos, spos, rot, flags, rel, vox, cvox, idx, int(past[idx]))
                hl.append(h); cl.append(c); vfl.append(vf); vcl.append(vc); ql.append(q)

            n_tot = len(needed)
            trunks = np.empty((n_tot, TRUNK_DIM), dtype=np.float32)
            trunks_m = np.empty((n_tot, TRUNK_DIM), dtype=np.float32) if compute_mirrors else None
            pi_all = np.empty((n_tot, NUM_MODES), dtype=np.float32)
            mean_all = np.empty((n_tot, NUM_MODES, NUM_H, 3), dtype=np.float32)
            std_all = np.empty((n_tot, NUM_MODES, NUM_H, 3), dtype=np.float32)
            rho_all = np.empty((n_tot, NUM_MODES, NUM_H), dtype=np.float32)

            bs = 512
            for b0 in range(0, n_tot, bs):
                b1 = min(n_tot, b0 + bs)
                bh = torch.from_numpy(np.asarray(hl[b0:b1])).to(device)
                bc = torch.from_numpy(np.asarray(cl[b0:b1])).to(device)
                bvf = torch.from_numpy(np.asarray(vfl[b0:b1])).to(device)
                bvc = torch.from_numpy(np.asarray(vcl[b0:b1])).to(device)
                x, lo, me, ls, rh = forward_with_trunk(model, bh, bc, bvf, bvc)
                trunks[b0:b1] = x.cpu().numpy()
                pi_all[b0:b1] = F.softmax(lo, dim=-1).cpu().numpy()
                mean_all[b0:b1] = me.cpu().numpy()
                std_all[b0:b1] = ls.exp().cpu().numpy()
                rho_all[b0:b1] = rh.cpu().numpy()
                if compute_mirrors:
                    bhm, bcm, bvfm, bvcm = mirror_batch_inputs(bh, bc, bvf, bvc)
                    xm, _, _, _, _ = forward_with_trunk(model, bhm, bcm, bvfm, bvcm)
                    trunks_m[b0:b1] = xm.cpu().numpy()

            idx_map = {idx: k for k, idx in enumerate(needed)}

            for start in starts:
                mid_pos = pos[start + win_len // 2]
                dists = np.linalg.norm(vantages - mid_pos[None, :], axis=1)
                valid_v = np.where((dists >= 11.0) & (dists <= 44.0))[0]
                if len(valid_v) == 0:
                    continue
                rng.shuffle(valid_v)
                chosen_v = []
                for vi in valid_v[:30]:
                    vp = vantages[vi]
                    sx, sy, sz = float(vp[0]), float(vp[1]), float(vp[2])
                    eye_y = sy + 1.62
                    # Quick LOS check at 5 sample points in window
                    los_hits = sum(
                        1 for dt in (0, 15, 30, 45, 59)
                        if check_los_ray(sx, eye_y, sz, float(pos[start + dt, 0]),
                                         float(pos[start + dt, 1]) + 1.1,
                                         float(pos[start + dt, 2]), solid_set)
                    )
                    if los_hits >= 2:
                        chosen_v.append((sx, sy, sz, float(dists[vi])))
                        if len(chosen_v) >= max_vantages:
                            break
                if not chosen_v:
                    continue

                for (sx, sy, sz, dist_mid) in chosen_v:
                    eye_y = sy + 1.62
                    # Decide weapon types for this vantage
                    weapons = [(False, SPEED_FULL, GRAVITY)]
                    if include_throwables and dist_mid <= 26.0:
                        weapons.append((True, THROW_SPEED, THROW_GRAVITY))

                    for (is_throw, spd, grav) in weapons:
                        ep_trunk = np.empty((win_len, TRUNK_DIM), dtype=np.float32)
                        ep_trunk_m = np.empty((win_len, TRUNK_DIM), dtype=np.float32) if compute_mirrors else None
                        ep_tac = np.empty((win_len, TACTICAL_DIM), dtype=np.float32)
                        ep_q_model = np.zeros(win_len, dtype=np.float64)
                        ep_p_model = np.zeros(win_len, dtype=np.float64)
                        ep_pv_model = np.zeros(win_len, dtype=np.float64)
                        ep_r_true = np.zeros(win_len, dtype=np.float64)
                        ep_p_true = np.zeros(win_len, dtype=np.float64)
                        ep_vk_true = np.zeros(win_len, dtype=np.bool_)
                        ep_has_los = np.zeros(win_len, dtype=np.bool_)
                        ep_losing_cover = np.zeros(win_len, dtype=np.bool_)

                        # Also track v2 myopic P(hit)-only baseline on the exact same episode!
                        ep_v2_p_model = np.zeros(win_len, dtype=np.float64)
                        ep_v2_r_true = np.zeros(win_len, dtype=np.float64)
                        ep_v2_p_true = np.zeros(win_len, dtype=np.float64)
                        ep_v2_vk_true = np.zeros(win_len, dtype=np.bool_)

                        for dt in range(win_len):
                            idx = start + dt
                            k = idx_map[idx]
                            p0 = pos[idx]
                            q = ql[k]
                            pi = pi_all[k]
                            paths, stds, rhos = interp_modes_v2(p0, q, mean_all[k], std_all[k], rho_all[k])

                            # v3 EV-optimal shot
                            shot_ev, ev_m, ph_m, pv_m, _, _ = solve_aim_ev(
                                sx, eye_y, sz, spd, grav, pi, paths, stds, rhos,
                                solid_set, is_throwable=is_throw, punch_level=0,
                                use_void_ev=True, refine=refine,
                            )
                            has_los = shot_ev is not None
                            cal_p = apply_calibration(ph_m, cal_curve) if cal_curve is not None else ph_m
                            # Scale EV and p_void by calibration ratio (same as live Java)
                            cal_scale = (cal_p / ph_m) if ph_m > 1e-4 else 1.0
                            ev_cal = ev_m * cal_scale
                            pv_cal = min(1.0, pv_m * cal_scale)

                            ep_trunk[dt] = trunks[k]
                            if compute_mirrors:
                                ep_trunk_m[dt] = trunks_m[k]
                            ep_tac[dt] = build_tactical_vec(
                                sx, eye_y, sz, float(p0[0]), float(p0[1]), float(p0[2]),
                                q, ev_cal, cal_p, pv_cal, is_throw, has_los,
                            )
                            ep_q_model[dt] = ev_cal
                            ep_p_model[dt] = cal_p
                            ep_pv_model[dt] = pv_cal
                            ep_has_los[dt] = has_los

                            mean_p = np.tensordot(pi, paths, axes=([0], [0]))
                            ahead = mean_p[4]
                            ep_losing_cover[dt] = not check_los_ray(
                                sx, eye_y, sz, float(ahead[0]), float(ahead[1]) + 0.9, float(ahead[2]), solid_set
                            )

                            if has_los:
                                pt, vkt, rt = eval_shot_truth_smooth(
                                    shot_ev, spos, idx, solid_set, is_throwable=is_throw, punch_level=0
                                )
                                ep_p_true[dt] = pt
                                ep_vk_true[dt] = vkt
                                ep_r_true[dt] = rt

                            # v2 myopic P(hit)-only shot (only needed for bow test/val comparison)
                            if not is_throw and split != 'train':
                                shot_v2, _, ph_v2, _, _, _ = solve_aim_ev(
                                    sx, eye_y, sz, spd, grav, pi, paths, stds, rhos,
                                    solid_set, is_throwable=False, punch_level=0,
                                    use_void_ev=False, refine=refine,
                                )
                                if shot_v2 is not None:
                                    ep_v2_p_model[dt] = apply_calibration(ph_v2, cal_curve) if cal_curve is not None else ph_v2
                                    pt2, vk2, rt2 = eval_shot_truth_smooth(
                                        shot_v2, spos, idx, solid_set, is_throwable=False, punch_level=0
                                    )
                                    ep_v2_p_true[dt] = pt2
                                    ep_v2_vk_true[dt] = vk2
                                    ep_v2_r_true[dt] = rt2

                        if ep_has_los.sum() >= 6:
                            episodes.append({
                                'mode': m['mode'],
                                'is_throw': is_throw,
                                'trunk': ep_trunk,
                                'trunk_m': ep_trunk_m,
                                'tac': ep_tac,
                                'q_model': ep_q_model,
                                'p_model': ep_p_model,
                                'pv_model': ep_pv_model,
                                'r_true': ep_r_true,
                                'p_true': ep_p_true,
                                'vk_true': ep_vk_true,
                                'has_los': ep_has_los,
                                'losing_cover': ep_losing_cover,
                                'v2_p_model': ep_v2_p_model,
                                'v2_r_true': ep_v2_r_true,
                                'v2_p_true': ep_v2_p_true,
                                'v2_vk_true': ep_v2_vk_true,
                            })
                            match_eps += 1
                    if max_per_match and match_eps >= max_per_match:
                        break
                if max_per_match and match_eps >= max_per_match:
                    break
        print(f'    [{split}] {m["file"]}: +{match_eps} eps (total {len(episodes)})', flush=True)
    return episodes


# ---- Causal Policy Rollout (exact mirror of BowReleasePolicy.shouldFireEv) ----

def rollout_ev_policy(ep, v_wait_arr, fire_alpha):
    n = len(ep['q_model'])
    hold_ticks = 0
    for t in range(n):
        if not ep['has_los'][t]:
            continue
        ev_now = ep['q_model'][t]
        prob = ep['p_model'][t]
        if ev_now <= 0.0 or prob <= 0.0:
            continue
        w = hold_ticks
        hold_ticks += 1
        bar = max(0.24, fire_alpha * max(0.0, float(v_wait_arr[t])))
        fire = False
        if ev_now >= bar and prob >= 0.18:
            fire = True
        elif ev_now >= 3.2 and prob >= 0.42 and ev_now >= 0.82 * bar:
            fire = True
        elif ep['losing_cover'][t] and prob >= 0.22 and ev_now >= 0.25:
            fire = True
        elif w >= 30:
            decay = max(0.55, 1.0 - 0.015 * (w - 30))
            if ev_now >= bar * decay and prob >= 0.20:
                fire = True
        if fire:
            return t
    return None


def rollout_smart_policy(p_model, has_los, losing_cover, layup=0.75):
    """Exact mirror of BowReleasePolicy SMART mode."""
    ema = peak = best = 0.0
    spiking = False
    plateau = 0
    hold_ticks = 0
    for t in range(len(p_model)):
        if not has_los[t]:
            continue
        prob = float(p_model[t])
        if prob <= 0.0:
            continue
        w = hold_ticks
        hold_ticks += 1
        ema = prob if w == 0 else (0.88 * ema + 0.12 * prob)
        if prob > best:
            best = prob
        if prob >= layup:
            return t
        if losing_cover[t] and prob >= 0.22:
            return t
        spike_bar = max(0.22, min(ema + 0.07, ema * 1.25))
        if prob >= spike_bar:
            if not spiking:
                spiking = True; peak = prob; plateau = 1
            elif prob > peak + 0.008:
                peak = prob; plateau = 1
            elif prob >= peak - 0.015:
                plateau += 1
                if plateau >= 3:
                    return t
            else:
                return t
        elif spiking:
            if prob >= max(0.22 * 0.85, ema + 0.02):
                return t
            spiking = False; peak = prob; plateau = 0
        if w >= 30:
            relaxed = max(0.18, best * (0.94 - 0.009 * (w - 30)))
            if prob >= relaxed and prob >= ema:
                return t
    return None


# ---- Training BowWaitNet with 2-Round Causal Longstaff-Schwartz FVI ----

def train_wait_fvi(train_eps, val_eps, device, epochs_per_round=18):
    print(f'\nBuilding BowWaitNet training arrays from {len(train_eps)} episodes (with 2x mirror symmetry)...')
    # Flatten original + mirrored features (T-1 steps per episode, since V_wait predicts continuation from t+1)
    xs_orig, xs_mir, ep_slices = [], [], []
    offset = 0
    for ep in train_eps:
        T = len(ep['q_model'])
        x_o = np.concatenate([ep['trunk'][:-1], ep['tac'][:-1]], axis=1)
        tac_m = ep['tac'][:-1].copy()
        tac_m[:, 0] *= -1.0  # flip shooter_rx
        x_m = np.concatenate([ep['trunk_m'][:-1], tac_m], axis=1)
        xs_orig.append(x_o)
        xs_mir.append(x_m)
        ep_slices.append((offset, offset + T - 1))
        offset += T - 1

    X_orig = np.concatenate(xs_orig, axis=0)
    X_all = np.concatenate([X_orig, np.concatenate(xs_mir, axis=0)], axis=0)
    N = len(X_orig)
    print(f'Total wait samples: {N:,} base ({2 * N:,} with mirror symmetry)')

    # Precompute validation features
    val_feats = [torch.from_numpy(np.concatenate([ep['trunk'], ep['tac']], axis=1)).to(device) for ep in val_eps]
    X_orig_t = torch.from_numpy(X_orig).to(device)

    model = BowWaitNet(WAIT_IN_DIM, 96, 48).to(device)

    def fit_targets(y_base, lr=1.5e-3, epochs=epochs_per_round):
        y_all = np.concatenate([y_base, y_base], axis=0).astype(np.float32)
        ds_x = torch.from_numpy(X_all)
        ds_y = torch.from_numpy(y_all)
        opt = torch.optim.AdamW(model.parameters(), lr=lr, weight_decay=2e-4)
        sched = torch.optim.lr_scheduler.CosineAnnealingLR(opt, T_max=epochs)
        bs = 1024
        for ep_i in range(epochs):
            model.train()
            perm = torch.randperm(len(ds_x))
            tot_loss = 0.0
            nb = 0
            for b0 in range(0, len(ds_x), bs):
                idx = perm[b0:b0 + bs]
                bx = ds_x[idx].to(device)
                by = ds_y[idx].to(device)
                pred = model(bx)
                loss = F.smooth_l1_loss(pred, by, beta=0.5)
                opt.zero_grad()
                loss.backward()
                torch.nn.utils.clip_grad_norm_(model.parameters(), 2.0)
                opt.step()
                tot_loss += float(loss.item())
                nb += 1
            sched.step()
        return tot_loss / max(1, nb)

    # Round 0: Warm-start discounted DP (gamma=0.955 to limit initial hindsight optimism)
    y0 = np.zeros(N, dtype=np.float32)
    gamma0 = 0.955
    for ep, (s0, s1) in zip(train_eps, ep_slices):
        r = ep['r_true']
        T = len(r)
        V = np.zeros(T, dtype=np.float64)
        V[-1] = r[-1]
        for t in range(T - 2, -1, -1):
            V[t] = max(r[t], gamma0 * V[t + 1])
        y0[s0:s1] = (gamma0 * V[1:]).astype(np.float32)

    loss0 = fit_targets(y0, lr=2e-3, epochs=epochs_per_round)
    print(f'  Round 0 (Warm-start DP, gamma={gamma0}): mean_y={y0.mean():.3f}, loss={loss0:.4f}')

    # Round 1 & Round 2: Exact Causal Longstaff-Schwartz Fitted Value Iteration!
    # Target y_causal[t] = actual discounted reward realized by following the causal policy from t+1 onward!
    gamma_causal = 0.985
    for rnd in (1, 2):
        model.eval()
        with torch.no_grad():
            preds = []
            for b0 in range(0, N, 4096):
                preds.append(model(X_orig_t[b0:b0 + 4096]).cpu().numpy())
            v_pred = np.concatenate(preds, axis=0)

        y_causal = np.zeros(N, dtype=np.float32)
        for ep, (s0, s1) in zip(train_eps, ep_slices):
            r = ep['r_true']
            qm = ep['q_model']
            pm = ep['p_model']
            los = ep['has_los']
            lc = ep['losing_cover']
            T = len(r)
            vp_ep = np.empty(T, dtype=np.float64)
            vp_ep[:-1] = v_pred[s0:s1]
            vp_ep[-1] = 0.0

            # Compute causal stopping time & realized discounted reward from every tick tau
            # Backward pass: if causal rule fires at tau, realized value from tau is r[tau];
            # otherwise realized value from tau is gamma * realized_value[tau + 1]!
            realized = np.zeros(T, dtype=np.float64)
            realized[-1] = r[-1] if los[-1] else 0.0
            for tau in range(T - 2, -1, -1):
                fire_tau = (
                    los[tau] and pm[tau] >= 0.18
                    and (qm[tau] >= max(0.24, vp_ep[tau])
                         or (lc[tau] and pm[tau] >= 0.22 and qm[tau] >= 0.25))
                )
                if fire_tau:
                    realized[tau] = r[tau]
                else:
                    realized[tau] = gamma_causal * realized[tau + 1]
            y_causal[s0:s1] = (gamma_causal * realized[1:]).astype(np.float32)

        # Blend slightly with previous round? No, pure causal Longstaff-Schwartz is unbiased!
        loss_r = fit_targets(y_causal, lr=1.2e-3 if rnd == 1 else 8e-4, epochs=epochs_per_round)
        print(f'  Round {rnd} (Causal Longstaff-Schwartz FVI, gamma={gamma_causal}): '
              f'mean_y={y_causal.mean():.3f}, loss={loss_r:.4f}')

    # Tune fire_alpha STRICTLY on validation bow episodes!
    model.eval()
    val_vwait = []
    with torch.no_grad():
        for xf in val_feats:
            val_vwait.append(model(xf).cpu().numpy())

    val_bow_idx = [i for i, ep in enumerate(val_eps) if not ep['is_throw']]
    print(f'\nTuning fireAlpha strictly on {len(val_bow_idx)} validation bow episodes...')
    best_alpha = 1.0
    best_score = -1.0
    for alpha in (0.75, 0.85, 0.90, 0.95, 1.00, 1.05, 1.12, 1.20):
        tot_ev = 0.0
        tot_vk = 0.0
        fired = 0
        for i in val_bow_idx:
            t_fire = rollout_ev_policy(val_eps[i], val_vwait[i], alpha)
            if t_fire is not None:
                fired += 1
                tot_ev += val_eps[i]['r_true'][t_fire]
                if val_eps[i]['vk_true'][t_fire]:
                    tot_vk += val_eps[i]['p_true'][t_fire]
        fire_rate = fired / max(1, len(val_bow_idx))
        ev_per_arrow = tot_ev / max(1, fired)
        ev_per_win = tot_ev / max(1, len(val_bow_idx))
        vk10 = 10.0 * tot_vk / max(1, fired)
        # Optimize realized EV per window (penalizes stalling automatically!)
        score = ev_per_win
        print(f'  alpha={alpha:.2f}: fire={fire_rate*100:5.1f}%  EV/arrow={ev_per_arrow:.3f}  '
              f'EV/win={ev_per_win:.3f}  VoidKills/10={vk10:.2f}')
        if score > best_score and fire_rate >= 0.80:
            best_score = score
            best_alpha = alpha

    print(f'Selected validation-optimal fireAlpha = {best_alpha:.2f}')
    return model, best_alpha


# ---- Sealed Bow Tactical Benchmark ----

def benchmark_bow_tactical(test_eps, wait_model, fire_alpha, device):
    bow_eps = [ep for ep in test_eps if not ep['is_throw']]
    wait_model.eval()
    vwaits = []
    with torch.no_grad():
        for ep in bow_eps:
            xf = torch.from_numpy(np.concatenate([ep['trunk'], ep['tac']], axis=1)).to(device)
            vwaits.append(wait_model(xf).cpu().numpy())

    def summarize(name, fire_times, use_v2=False):
        fired = 0
        tot_ev = 0.0
        tot_p = 0.0
        tot_vk = 0.0
        holds = []
        # Also track sub-slice of episodes where a void kill is actually reachable at some tick!
        void_eps_n = 0
        void_eps_vk = 0.0
        void_eps_fired = 0
        for ep, tf in zip(bow_eps, fire_times):
            has_void_opp = bool(ep['vk_true'].any() or ep['v2_vk_true'].any())
            if has_void_opp:
                void_eps_n += 1
            if tf is None:
                continue
            fired += 1
            holds.append(tf)
            ev = ep['v2_r_true'][tf] if use_v2 else ep['r_true'][tf]
            pt = ep['v2_p_true'][tf] if use_v2 else ep['p_true'][tf]
            vk = (ep['v2_vk_true'][tf] if use_v2 else ep['vk_true'][tf])
            tot_ev += ev
            tot_p += pt
            if vk:
                tot_vk += pt
            if has_void_opp:
                void_eps_fired += 1
                if vk:
                    void_eps_vk += pt

        n = max(1, len(bow_eps))
        f = max(1, fired)
        vf = max(1, void_eps_fired)
        res = {
            'episodes': len(bow_eps),
            'fire_rate': fired / n,
            'hit_rate': tot_p / f,
            'ev_per_arrow': tot_ev / f,
            'ev_per_window': tot_ev / n,
            'void_kills_per_10': 10.0 * tot_vk / f,
            'void_slice_n': void_eps_n,
            'void_slice_vk_per_10': 10.0 * void_eps_vk / vf,
            'avg_hold_ticks': float(np.mean(holds)) if holds else 0.0,
        }
        print(f'  {name:24s} | fire={res["fire_rate"]*100:5.1f}% | hit={res["hit_rate"]*100:5.1f}% '
              f'| EV/arr={res["ev_per_arrow"]:.3f}x | EV/win={res["ev_per_window"]:.3f}x '
              f'| VK/10={res["void_kills_per_10"]:4.2f} | VK/10(void-opp)={res["void_slice_vk_per_10"]:4.2f} '
              f'| hold={res["avg_hold_ticks"]:4.1f}t')
        return res

    # First visible tick for immediate arms
    first_los = [int(np.argmax(ep['has_los'])) if ep['has_los'].any() else None for ep in bow_eps]
    v2_smart = [rollout_smart_policy(ep['v2_p_model'], ep['has_los'], ep['losing_cover']) for ep in bow_eps]
    v3_smart = [rollout_smart_policy(ep['p_model'], ep['has_los'], ep['losing_cover']) for ep in bow_eps]
    v3_vwait = [rollout_ev_policy(ep, vw, fire_alpha) for ep, vw in zip(bow_eps, vwaits)]

    print(f'\n=== SEALED TEST BOW TACTICAL BENCHMARK ({len(bow_eps)} unseen BedWars+SkyWars windows) ===')
    out = {}
    out['v2_myopic_immediate'] = summarize('v2_myopic_immediate', first_los, use_v2=True)
    out['v2_myopic_smart']     = summarize('v2_myopic_smart', v2_smart, use_v2=True)
    out['v3_ev_immediate']     = summarize('v3_ev_immediate', first_los, use_v2=False)
    out['v3_ev_smart']         = summarize('v3_ev_smart', v3_smart, use_v2=False)
    out['v3_ev_vwait']         = summarize('v3_ev_vwait (FULL v3)', v3_vwait, use_v2=False)

    # Also check by game mode (BedWars vs SkyWars)
    for gm in ('bedwars', 'skywars'):
        sub_idx = [i for i, ep in enumerate(bow_eps) if ep['mode'] == gm]
        if not sub_idx:
            continue
        print(f'  -- {gm.upper()} ({len(sub_idx)} windows):')
        for label, fts, is_v2 in (
            ('v2_myopic_smart', v2_smart, True),
            ('v3_ev_immediate', first_los, False),
            ('v3_ev_vwait', v3_vwait, False),
        ):
            fired = sum(1 for i in sub_idx if fts[i] is not None)
            ev_s = sum((bow_eps[i]['v2_r_true'] if is_v2 else bow_eps[i]['r_true'])[fts[i]] for i in sub_idx if fts[i] is not None)
            vk_s = sum(((bow_eps[i]['v2_p_true'] if is_v2 else bow_eps[i]['p_true'])[fts[i]]
                        if (bow_eps[i]['v2_vk_true'] if is_v2 else bow_eps[i]['vk_true'])[fts[i]] else 0.0)
                       for i in sub_idx if fts[i] is not None)
            print(f'     {label:18s}: fire={fired/len(sub_idx)*100:5.1f}%  EV/arr={ev_s/max(1,fired):.3f}x  '
                  f'EV/win={ev_s/len(sub_idx):.3f}x  VK/10={10*vk_s/max(1,fired):.2f}')
    return out


# ---- Sealed Snowball / Egg 3-Throw Volley Benchmark ----

def benchmark_volley_3throws(model, device, data_dir: Path, split='test', seed=123):
    """Benchmark 3-throw snowball/egg burst (ticks 0, 5, 10) at SkyWars/BedWars ranges (10..26b)."""
    rng = np.random.default_rng(seed)
    manifest = json.loads((data_dir / 'manifest.json').read_text())
    throw_offsets = (0, 5, 10)
    arms = ('linear_3throw', 'v2_single_3throw', 'v3_ev_novolley', 'v3_ev_volley')
    stats = {a: {'n': 0, 'hit_joint': 0.0, 'vk_joint': 0.0, 'ev_joint': 0.0} for a in arms}

    for m in manifest['matches']:
        if m['split'] != split:
            continue
        npz = np.load(data_dir / m['file'])
        solid_set = build_solid_set(npz)
        if solid_set is None:
            continue
        vantages = collect_vantage_pool(npz, step=35)
        if len(vantages) < 5:
            continue

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

            starts = [i for i in range(0, len(ticks), 28) if past[i] >= 10 and fut[i] >= 12 + MAX_H]
            if not starts:
                continue

            # Precompute predictions for all 3 throw ticks per burst
            needed = sorted({s + dt for s in starts for dt in throw_offsets})
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

            for start in starts:
                p0_mid = pos[start + 5]
                dists = np.linalg.norm(vantages - p0_mid[None, :], axis=1)
                valid_v = np.where((dists >= 10.0) & (dists <= 26.0))[0]
                if len(valid_v) == 0:
                    continue
                rng.shuffle(valid_v)
                vantage = None
                for vi in valid_v[:20]:
                    vp = vantages[vi]
                    if check_los_ray(float(vp[0]), float(vp[1]) + 1.62, float(vp[2]),
                                     float(p0_mid[0]), float(p0_mid[1]) + 1.1, float(p0_mid[2]), solid_set):
                        vantage = (float(vp[0]), float(vp[1]) + 1.62, float(vp[2]))
                        break
                if vantage is None:
                    continue
                sx, eye_y, sz = vantage

                # Simulate each arm's 3-throw burst
                arm_shots = {a: [] for a in arms}
                volley_airborne = []

                for dt in throw_offsets:
                    idx = start + dt
                    k = idx_map[idx]
                    p0 = pos[idx]
                    q = ql[k]
                    pi = pi_all[k]
                    paths, stds, rhos = interp_modes_v2(p0, q, mean_all[k], std_all[k], rho_all[k])

                    # 1. Linear lead
                    lin_path = linear_baseline_path(pos, idx, MAX_H)
                    s_lin = intercept_g(sx, eye_y, sz, THROW_SPEED, lin_path, 0.90, THROW_GRAVITY)
                    if s_lin is not None and ray_blocked_g(s_lin, solid_set):
                        s_lin = None
                    arm_shots['linear_3throw'].append((idx, s_lin))

                    # 2. v2 naive single-mode P(hit)
                    s_v2, _, _, _, _, _ = solve_aim_ev(
                        sx, eye_y, sz, THROW_SPEED, THROW_GRAVITY, pi, paths, stds, rhos,
                        solid_set, is_throwable=True, use_void_ev=False, refine=True,
                    )
                    arm_shots['v2_single_3throw'].append((idx, s_v2))

                    # 3. v3 EV without volley coverage
                    s_nv, _, _, _, _, _ = solve_aim_ev(
                        sx, eye_y, sz, THROW_SPEED, THROW_GRAVITY, pi, paths, stds, rhos,
                        solid_set, is_throwable=True, use_void_ev=True, refine=True,
                    )
                    arm_shots['v3_ev_novolley'].append((idx, s_nv))

                    # 4. v3 EV WITH Airborne Volley Coverage!
                    eff_w = pi.copy().astype(np.float64)
                    for (t_prev, prev_shot) in volley_airborne:
                        rem = prev_shot[2] - (dt - t_prev)
                        if 0.5 < rem <= MAX_H:
                            for mk in range(NUM_MODES):
                                p_cov = mode_hit_prob_at_g(prev_shot, paths, stds, rhos, mk, rem)
                                eff_w[mk] *= (1.0 - p_cov)
                    s_vol, _, _, _, _, _ = solve_aim_ev(
                        sx, eye_y, sz, THROW_SPEED, THROW_GRAVITY, pi, paths, stds, rhos,
                        solid_set, is_throwable=True, eff_weights=eff_w, use_void_ev=True, refine=True,
                    )
                    arm_shots['v3_ev_volley'].append((idx, s_vol))
                    if s_vol is not None:
                        volley_airborne.append((dt, s_vol))

                # Evaluate joint burst hit rate, joint void-kill rate, and best burst EV
                # Only count bursts where at least one arm had LOS on tick 0
                if arm_shots['v3_ev_volley'][0][1] is None and arm_shots['v2_single_3throw'][0][1] is None:
                    continue
                for a in arms:
                    p_miss_all = 1.0
                    p_novk_all = 1.0
                    ev_sum = 0.0
                    # Joint hit/void probability across independent projectile spread draws
                    # (note: if throw 1 knocks them off, throw 2/3 can't double-count the void kill!)
                    for (idx_t, sh) in arm_shots[a]:
                        pt, vk, ev_t = eval_shot_truth_smooth(sh, spos, idx_t, solid_set, is_throwable=True, punch_level=0)
                        p_miss_all *= (1.0 - pt)
                        if vk:
                            p_novk_all *= (1.0 - pt)
                        ev_sum += ev_t
                    p_any_hit = 1.0 - p_miss_all
                    p_any_vk = 1.0 - p_novk_all
                    # Exact burst EV (credit at most one void kill of 10.0x + remaining normal hit value)
                    burst_ev = p_any_vk * THROW_VOID + max(0.0, p_any_hit - p_any_vk) * THROW_BASE
                    stats[a]['n'] += 1
                    stats[a]['hit_joint'] += p_any_hit
                    stats[a]['vk_joint'] += p_any_vk
                    stats[a]['ev_joint'] += burst_ev

    print(f'\n=== SEALED TEST SNOWBALL / EGG 3-THROW VOLLEY BENCHMARK (n={stats["v3_ev_volley"]["n"]} bursts) ===')
    out = {}
    for a in arms:
        n = max(1, stats[a]['n'])
        r = {
            'bursts': stats[a]['n'],
            'joint_hit_rate': stats[a]['hit_joint'] / n,
            'joint_vk_rate': stats[a]['vk_joint'] / n,
            'burst_ev': stats[a]['ev_joint'] / n,
        }
        out[a] = r
        print(f'  {a:18s} | 3-throw Hit={r["joint_hit_rate"]*100:5.2f}% '
              f'| 3-throw VoidKill={r["joint_vk_rate"]*100:5.2f}% '
              f'| Burst EV={r["burst_ev"]:.3f}x')
    return out


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--data', type=Path, default=Path('runtime/bow-aim/neural-data-v2'))
    parser.add_argument('--model-v2', type=Path, default=Path('runtime/bow-aim/neural-model-v2/winner.pt'))
    parser.add_argument('--out-dir', type=Path, default=Path('runtime/bow-aim/neural-model-v3'))
    parser.add_argument('--export-bin', type=Path, default=Path('practice-client/assets/practice/bow-wait.bin'))
    args = parser.parse_args()

    t0 = time.time()
    device = torch.device('mps' if torch.backends.mps.is_available() else 'cpu')
    print(f'Device: {device}')

    ckpt = torch.load(args.model_v2, map_location='cpu')
    v2 = BowNeuralNetV2(NUM_MODES).to(device)
    v2.load_state_dict(ckpt['state_dict'])
    v2.eval()
    cal_curve = ckpt.get('calibration', None)
    if cal_curve is not None:
        cal_curve = np.asarray(cal_curve, dtype=np.float64)

    print('Extracting TRAIN episodes (with 2x mirrored trunks)...', flush=True)
    train_eps = extract_episodes(v2, cal_curve, device, args.data, 'train',
                                 win_len=60, win_stride=75, max_vantages=2,
                                 include_throwables=True, refine=False, compute_mirrors=True,
                                 seed=11, max_per_match=30)
    print(f'  Train episodes: {len(train_eps)} ({time.time()-t0:.1f}s)', flush=True)

    print('Extracting VAL episodes (for fireAlpha tuning)...', flush=True)
    val_eps = extract_episodes(v2, cal_curve, device, args.data, 'val',
                               win_len=60, win_stride=60, max_vantages=2,
                               include_throwables=False, refine=True, compute_mirrors=False,
                               seed=22, max_per_match=65)
    print(f'  Val episodes: {len(val_eps)} ({time.time()-t0:.1f}s)', flush=True)

    wait_model, fire_alpha = train_wait_fvi(train_eps, val_eps, device, epochs_per_round=18)

    args.out_dir.mkdir(parents=True, exist_ok=True)
    torch.save({
        'state_dict': wait_model.state_dict(),
        'fire_alpha': fire_alpha,
        'in_dim': WAIT_IN_DIM,
        'h1': 96,
        'h2': 48,
    }, args.out_dir / 'wait_model.pt')
    export_wait_bin(wait_model, fire_alpha, args.export_bin)

    print('\nExtracting SEALED TEST episodes (bw-09..12 + 5 sealed SkyWars)...', flush=True)
    test_eps = extract_episodes(v2, cal_curve, device, args.data, 'test',
                                win_len=60, win_stride=60, max_vantages=2,
                                include_throwables=False, refine=True, compute_mirrors=False,
                                seed=33, max_per_match=55)
    print(f'  Test episodes: {len(test_eps)} ({time.time()-t0:.1f}s)', flush=True)

    bow_res = benchmark_bow_tactical(test_eps, wait_model, fire_alpha, device)
    volley_res = benchmark_volley_3throws(v2, device, args.data, split='test', seed=123)

    summary = {
        'fire_alpha': fire_alpha,
        'train_episodes': len(train_eps),
        'val_episodes': len(val_eps),
        'test_bow_episodes': len(test_eps),
        'bow_tactical': bow_res,
        'volley_3throws': volley_res,
        'elapsed_s': round(time.time() - t0, 1),
    }
    (args.out_dir / 'v3_benchmark.json').write_text(json.dumps(summary, indent=2))
    print(f'\nDone in {time.time()-t0:.1f}s! Saved summary to {args.out_dir / "v3_benchmark.json"}')


if __name__ == '__main__':
    main()
