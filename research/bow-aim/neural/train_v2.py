"""Train BowNeuralNetV2:
- 48-tick (2.4s) temporal Conv1d over 26 raw channels (pos, vel, unsmoothed serverPos delta, rot, flags, relational threat)
- 30-dim per-player online style & periodicity summary
- Multi-scale 3D voxels: fine 9x7x9 (+-4b) + coarse 11x5x11 (+-11b)
- K=6 coherent trajectory modes with correlated 2D horizontal Gaussian (sigma_x, sigma_z, rho_xz)
- Stage 1: Exact correlated bivariate Gaussian Mixture NLL + 2x mirror aug + simulated-remote self-tracks
- Stage 2: Direct differentiable expected-hit-probability fine-tuning!
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

HORIZONS = [3, 6, 10, 15, 22, 30, 42]
NUM_H = len(HORIZONS)
MAX_H = HORIZONS[-1]
HIST_LEN = 48
HIST_CH = 26
CTX_DIM = 30
FINE_DIM = 7 * 9 * 9       # 567
COARSE_DIM = 5 * 11 * 11   # 605
NUM_MODES = 6
AUTOCORR_LAGS = [12, 16, 20, 24, 28, 34, 42, 52]
MAGIC_V2 = 0x424E4E32  # 'BNN2'


def cardinal_from_yaw(yaw_deg):
    return int(math.floor((yaw_deg + 45.0) / 90.0)) & 3


def rot_xz(dx, dz, q):
    if q == 0:
        return dx, dz
    if q == 1:
        return dz, -dx
    if q == 2:
        return -dx, -dz
    return -dz, dx


def inv_rot_xz(rx, rz, q):
    if q == 0:
        return rx, rz
    if q == 1:
        return -rz, rx
    if q == 2:
        return -rx, -rz
    return rz, -rx


def rot_vox3d(vox_y_z_x, q):
    if q == 0:
        return vox_y_z_x
    if q == 1:
        return np.transpose(vox_y_z_x[:, :, ::-1], (0, 2, 1))
    if q == 2:
        return vox_y_z_x[:, ::-1, ::-1]
    return np.transpose(vox_y_z_x[:, ::-1, :], (0, 2, 1))


def unpack_fine(packed_71):
    return np.unpackbits(packed_71)[:FINE_DIM].reshape(7, 9, 9)


def unpack_coarse(packed_76):
    return np.unpackbits(packed_76)[:COARSE_DIM].reshape(5, 11, 11)


def simulate_remote_interp(pos):
    """Simulate Minecraft 1.8.9 EntityOtherPlayerMP 3-tick S14/S18 quantization + interpolation
    on EntityPlayerSP (self) tracks so train-only self data matches remote entity dynamics!
    """
    n = len(pos)
    spos = np.round(pos * 32.0) / 32.0
    cpos = np.empty_like(pos)
    cpos[0] = spos[0]
    for i in range(1, n):
        # 1.8.9 client steps 1/3 of the way toward serverPos each tick
        cpos[i] = cpos[i - 1] + (spos[i] - cpos[i - 1]) / 3.0
    return cpos.astype(np.float32), spos.astype(np.float32)


def build_features_v2(pos, spos, rot, flags, rel, fine_packed, coarse_packed, i, valid_past,
                      q=None, use_terrain=True, use_relational=True):
    """Exact v2 feature builder mirrored 1-to-1 in Java 8 BowNeuralMotion.java."""
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

        # Priority 2: Unsmoothed serverPos delta (instantaneous packet lead over client interpolation!)
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

        # Priority 1: Relational threat context (8 channels)
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

    # Long-term style & periodicity summary (30 floats)
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

    v04 = mean_vel(0, 4)
    v412 = mean_vel(4, 12)
    v1224 = mean_vel(12, 24)
    v2448 = mean_vel(24, 48)
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
        vx3 = vx3 - vx3.mean()
        vz3 = vz3 - vz3.mean()
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
    ctx[21] = 1.0 if (use_terrain and fine_packed is not None) else 0.0

    # Per-player online style stats over up to 160 past ticks
    n_st = min(160, valid_past - 1)
    if n_st >= 4:
        sl_pos = pos[i - n_st:i + 1]
        d_all = sl_pos[1:] - sl_pos[:-1]
        ctx[22] = float(np.mean(d_all[:, 1] > 0.25))  # jump rate
        if n_st >= 8:
            v4 = sl_pos[4:] - sl_pos[:-4]
            dots = v4[4:, 0] * v4[:-4, 0] + v4[4:, 2] * v4[:-4, 2]
            mags = np.hypot(v4[4:, 0], v4[4:, 2]) * np.hypot(v4[:-4, 0], v4[:-4, 2])
            ctx[23] = float(np.mean((mags > 0.04) & (dots < 0.0)))  # direction reversal rate
        fl_sl = flags[i - n_st:i + 1]
        ctx[24] = float(np.mean((fl_sl & 1) != 0))   # sneak rate
        ctx[25] = float(np.mean((fl_sl & 2) != 0))   # held-block rate
        ctx[26] = float(np.mean((fl_sl & 32) != 0))  # hurt/combat rate
    ctx[27] = math.hypot(float(spos[i, 0] - pos[i, 0]), float(spos[i, 2] - pos[i, 2])) * 3.0
    ctx[28] = 1.0 if (use_relational and rel is not None) else 0.0
    ctx[29] = float(rel[i, 3]) if (use_relational and rel is not None) else 0.0
    np.clip(ctx, -4.0, 4.0, out=ctx)

    if use_terrain and fine_packed is not None:
        vf = rot_vox3d(unpack_fine(fine_packed[i]), q).reshape(-1).astype(np.float32)
        vc = rot_vox3d(unpack_coarse(coarse_packed[i]), q).reshape(-1).astype(np.float32)
    else:
        vf = np.zeros(FINE_DIM, dtype=np.float32)
        vc = np.zeros(COARSE_DIM, dtype=np.float32)
    return hist, ctx, vf, vc, q


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


def load_split_v2(data_dir: Path, split: str, stride: int = 2, include_self: bool = False):
    manifest = json.loads((data_dir / 'manifest.json').read_text())
    hists, ctxs, vf_l, vc_l, targets, masks = [], [], [], [], [], []

    def add_track(ticks, pos, spos, rot, flags, rel, vox, cvox, use_stride):
        past, fut = contiguous_runs(ticks, pos)
        for i in range(0, len(ticks), use_stride):
            if past[i] < 6 or fut[i] < HORIZONS[3]:
                continue
            h, c, vf, vc, q = build_features_v2(pos, spos, rot, flags, rel, vox, cvox, i, int(past[i]))
            tgt = np.zeros((NUM_H, 3), dtype=np.float32)
            msk = np.zeros(NUM_H, dtype=np.float32)
            p0 = pos[i]
            for hi, hz in enumerate(HORIZONS):
                if fut[i] >= hz:
                    # Target: true unsmoothed future server position relative to current client p0!
                    d = spos[i + hz] - p0
                    rx, rz = rot_xz(float(d[0]), float(d[2]), q)
                    tgt[hi, 0], tgt[hi, 1], tgt[hi, 2] = rx, float(d[1]), rz
                    msk[hi] = 1.0
            hists.append(h)
            ctxs.append(c)
            vf_l.append(vf.astype(np.uint8))
            vc_l.append(vc.astype(np.uint8))
            targets.append(tgt)
            masks.append(msk)

    for m in manifest['matches']:
        if m['split'] != split:
            continue
        npz = np.load(data_dir / m['file'])
        for p in range(int(npz['num_players'][0])):
            add_track(npz[f'p{p}_tick'], npz[f'p{p}_pos'], npz[f'p{p}_spos'],
                      npz[f'p{p}_rot'], npz[f'p{p}_flags'], npz[f'p{p}_rel'],
                      npz[f'p{p}_vox'], npz[f'p{p}_cvox'], stride)
        if include_self and 'self_tick' in npz:
            cpos, spos = simulate_remote_interp(npz['self_pos'])
            add_track(npz['self_tick'], cpos, spos,
                      npz['self_rot'], npz['self_flags'], npz['self_rel'],
                      npz['self_vox'], npz['self_cvox'], max(stride, 3))

    return (
        np.asarray(hists, dtype=np.float32),
        np.asarray(ctxs, dtype=np.float32),
        np.asarray(vf_l, dtype=np.uint8),
        np.asarray(vc_l, dtype=np.uint8),
        np.asarray(targets, dtype=np.float32),
        np.asarray(masks, dtype=np.float32),
    )


class BowNeuralNetV2(nn.Module):
    def __init__(self, num_modes=NUM_MODES):
        super().__init__()
        self.num_modes = num_modes
        # 4-layer strided temporal conv over 48 ticks (length 48 -> 48 -> 24 -> 12 -> 6)
        self.conv1 = nn.Conv1d(HIST_CH, 48, kernel_size=3, stride=1, padding=1)
        self.conv2 = nn.Conv1d(48, 64, kernel_size=3, stride=2, padding=1)
        self.conv3 = nn.Conv1d(64, 80, kernel_size=3, stride=2, padding=1)
        self.conv4 = nn.Conv1d(80, 96, kernel_size=3, stride=2, padding=1)
        self.hist_proj = nn.Linear(96 * 3, 128)

        # Multi-scale 3D voxel branch: fine 9x7x9 + coarse 11x5x11
        self.vf_fc = nn.Linear(FINE_DIM, 64)
        self.vc_fc = nn.Linear(COARSE_DIM, 64)
        self.vox_fuse = nn.Linear(128, 96)

        # 2-block residual fusion trunk
        self.trunk_in = nn.Linear(128 + CTX_DIM + 96, 224)
        self.res1a = nn.Linear(224, 224)
        self.res1b = nn.Linear(224, 224)
        self.res2a = nn.Linear(224, 224)
        self.res2b = nn.Linear(224, 224)

        # Correlated multi-modal heads
        self.head_logits = nn.Linear(224, num_modes)
        self.head_means = nn.Linear(224, num_modes * NUM_H * 3)
        self.head_logstd = nn.Linear(224, num_modes * NUM_H * 3)
        self.head_rho = nn.Linear(224, num_modes * NUM_H)

        with torch.no_grad():
            init_sig = torch.tensor([0.28, 0.48, 0.78, 1.15, 1.65, 2.25, 3.00], dtype=torch.float32)
            bias = init_sig.log().view(1, NUM_H, 1).expand(num_modes, NUM_H, 3).reshape(-1)
            self.head_logstd.bias.copy_(bias)
            nn.init.normal_(self.head_means.weight, std=0.02)
            nn.init.normal_(self.head_means.bias, std=0.15)
            nn.init.zeros_(self.head_rho.weight)
            nn.init.zeros_(self.head_rho.bias)

    def forward(self, hist, ctx, vf, vc):
        h = F.silu(self.conv1(hist))
        h = F.silu(self.conv2(h))
        h = F.silu(self.conv3(h))
        h = F.silu(self.conv4(h))  # (B, 96, 6)
        h_cat = torch.cat([h[:, :, 0], h.mean(dim=-1), h.amax(dim=-1)], dim=-1)
        h_emb = F.silu(self.hist_proj(h_cat))

        v = torch.cat([F.silu(self.vf_fc(vf)), F.silu(self.vc_fc(vc))], dim=-1)
        v_emb = F.silu(self.vox_fuse(v))

        x = F.silu(self.trunk_in(torch.cat([h_emb, ctx, v_emb], dim=-1)))
        x = F.silu(x + self.res1b(F.silu(self.res1a(x))))
        x = F.silu(x + self.res2b(F.silu(self.res2a(x))))

        logits = self.head_logits(x)
        means = self.head_means(x).view(-1, self.num_modes, NUM_H, 3)
        logstd = self.head_logstd(x).view(-1, self.num_modes, NUM_H, 3).clamp(-2.1, 2.1)
        rho = 0.90 * torch.tanh(self.head_rho(x).view(-1, self.num_modes, NUM_H))
        return logits, means, logstd, rho


H_WEIGHTS = torch.tensor([1.35, 1.25, 1.15, 1.05, 0.95, 0.80, 0.65], dtype=torch.float32)


def correlated_mixture_loss(logits, means, logstd, rho, target, mask, h_weights,
                            wta_weight=0.05, ent_weight=0.0, hit_weight=0.0):
    """Exact bivariate correlated-XZ + vertical-Y Gaussian Mixture NLL + optional differentiable hit-prob loss."""
    diff = target[:, None, :, :] - means  # (B, K, H, 3)
    inv_std = (-logstd).exp()
    zx = diff[..., 0] * inv_std[..., 0]
    zy = diff[..., 1] * inv_std[..., 1]
    zz = diff[..., 2] * inv_std[..., 2]

    one_m_r2 = (1.0 - rho * rho).clamp(min=0.10)
    # 2D bivariate Gaussian NLL for (x, z)
    quad_xz = (zx * zx - 2.0 * rho * zx * zz + zz * zz) / one_m_r2
    nll_xz = 0.5 * (quad_xz + 2.0 * (logstd[..., 0] + logstd[..., 2]) + torch.log(one_m_r2))
    # 1D Gaussian NLL for y
    nll_y = 0.5 * (zy * zy + 2.0 * logstd[..., 1])
    nll_h = nll_xz + nll_y  # (B, K, H)

    w = mask[:, None, :] * h_weights[None, None, :]
    w_sum = w.sum(dim=-1).clamp(min=1e-5)
    joint_logp = -(nll_h * w).sum(dim=-1)  # (B, K)
    log_pi = F.log_softmax(logits, dim=-1)
    mix_nll = -(torch.logsumexp(log_pi + joint_logp, dim=-1) / w_sum.squeeze(-1)).mean()

    l1_k = (torch.sqrt(diff.square() + 1e-6).sum(dim=-1) * w).sum(dim=-1) / w_sum
    wta = l1_k.min(dim=-1).values.mean()
    loss = mix_nll + wta_weight * wta

    if ent_weight > 0:
        pi = log_pi.exp()
        loss = loss - ent_weight * (-(pi * log_pi).sum(dim=-1).mean())

    if hit_weight > 0:
        # Stage 2: Direct differentiable expected hit probability over arrow-flight horizons!
        # Approximate arrow-swept player hitbox hit probability for each mode aiming at its own mean
        # vs actual target position: smooth box indicator around +-0.85b XZ, +-1.25b Y
        # (including arrow spread ~0.0075 * 3 * t_h).
        pi = log_pi.exp()  # (B, K)
        # Smooth hitbox capture probability if aiming at mode k's mean when truth is target
        # P(hit | aim=mu_k) ~ prod_a sigmoid((half_a - |diff_a|) / temp)
        temp = 0.25
        px = torch.sigmoid((0.88 - torch.sqrt(diff[..., 0].square() + 1e-5)) / temp)
        py = torch.sigmoid((1.25 - torch.sqrt(diff[..., 1].square() + 1e-5)) / temp)
        pz = torch.sigmoid((0.88 - torch.sqrt(diff[..., 2].square() + 1e-5)) / temp)
        p_hit_kh = px * py * pz  # (B, K, H)
        # Expected hit probability weighted by mode probability pi_k across valid horizons
        # (gives gradients directly to both pi_k and mu_k to put mass inside the exact hitbox!)
        exp_hit_h = (pi[:, :, None] * p_hit_kh).sum(dim=1)  # (B, H)
        hit_loss = -torch.log(exp_hit_h.clamp(min=1e-4))
        hit_obj = ((hit_loss * mask * h_weights[None, :]).sum(dim=-1) / mask.sum(dim=-1).clamp(min=1e-5)).mean()
        loss = loss + hit_weight * hit_obj

    return loss, mix_nll.detach()


def augment_v2(hist, ctx, vf, vc, target, terrain_drop=0.15, rel_drop=0.10):
    B = hist.shape[0]
    device = hist.device
    flip = torch.rand(B, device=device) < 0.5
    if flip.any():
        hist = hist.clone()
        ctx = ctx.clone()
        vf = vf.clone()
        vc = vc.clone()
        target = target.clone()
        # Flip rx (0), vrx (3), srx (6), sin(rel_yaw) (9), nrx (18), rrx (23)
        for ch in (0, 3, 6, 9, 18, 23):
            hist[flip, ch] *= -1.0
        for ci in (0, 3, 6, 8):
            ctx[flip, ci] *= -1.0
        vf[flip] = vf[flip].view(-1, 7, 9, 9).flip(dims=[-1]).reshape(-1, FINE_DIM)
        vc[flip] = vc[flip].view(-1, 5, 11, 11).flip(dims=[-1]).reshape(-1, COARSE_DIM)
        target[flip, :, 0] *= -1.0

    if terrain_drop > 0:
        drop = torch.rand(B, device=device) < terrain_drop
        if drop.any():
            vf = vf.clone(); vc = vc.clone(); ctx = ctx.clone()
            vf[drop] = 0.0; vc[drop] = 0.0; ctx[drop, 21] = 0.0
    if rel_drop > 0:
        rdrop = torch.rand(B, device=device) < rel_drop
        if rdrop.any():
            hist = hist.clone(); ctx = ctx.clone()
            hist[rdrop, 18:26] = 0.0
            ctx[rdrop, 28] = 0.0
            ctx[rdrop, 29] = 0.0
    return hist, ctx, vf, vc, target


def export_java_v2(model: BowNeuralNetV2, out_path: Path, calibration_curve=None):
    model.eval()
    sd = model.state_dict()
    order = [
        'conv1.weight', 'conv1.bias',
        'conv2.weight', 'conv2.bias',
        'conv3.weight', 'conv3.bias',
        'conv4.weight', 'conv4.bias',
        'hist_proj.weight', 'hist_proj.bias',
        'vf_fc.weight', 'vf_fc.bias',
        'vc_fc.weight', 'vc_fc.bias',
        'vox_fuse.weight', 'vox_fuse.bias',
        'trunk_in.weight', 'trunk_in.bias',
        'res1a.weight', 'res1a.bias',
        'res1b.weight', 'res1b.bias',
        'res2a.weight', 'res2a.bias',
        'res2b.weight', 'res2b.bias',
        'head_logits.weight', 'head_logits.bias',
        'head_means.weight', 'head_means.bias',
        'head_logstd.weight', 'head_logstd.bias',
        'head_rho.weight', 'head_rho.bias',
    ]
    if calibration_curve is None:
        calibration_curve = [i / 10.0 for i in range(11)]
    out_path.parent.mkdir(parents=True, exist_ok=True)
    with out_path.open('wb') as f:
        f.write(struct.pack('>IIII', MAGIC_V2, NUM_MODES, NUM_H, len(calibration_curve)))
        for h in HORIZONS:
            f.write(struct.pack('>I', h))
        for c in calibration_curve:
            f.write(struct.pack('>f', float(c)))
        total_floats = 0
        for key in order:
            arr = sd[key].detach().cpu().numpy().astype('>f4').reshape(-1)
            f.write(arr.tobytes())
            total_floats += arr.size
    print(f'Exported Java v2 model to {out_path} ({total_floats:,} floats, {out_path.stat().st_size / 1024:.1f} KB)', flush=True)


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--data', type=Path, default=Path('runtime/bow-aim/neural-data-v2'))
    p.add_argument('--out', type=Path, default=Path('runtime/bow-aim/neural-model-v2'))
    p.add_argument('--epochs', type=int, default=26)
    p.add_argument('--ft-epochs', type=int, default=6)
    p.add_argument('--batch', type=int, default=512)
    p.add_argument('--lr', type=float, default=1.4e-3)
    p.add_argument('--stride', type=int, default=2)
    p.add_argument('--seed', type=int, default=202602)
    args = p.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)

    torch.manual_seed(args.seed)
    np.random.seed(args.seed)
    device = torch.device('mps' if torch.backends.mps.is_available() else 'cpu')
    print(f'Using device: {device}', flush=True)

    t0 = time.monotonic()
    cache = args.data / f'cache_v2_s{args.stride}.npz'
    if cache.exists():
        c = np.load(cache)
        tr_h, tr_c, tr_vf, tr_vc, tr_y, tr_m = c['tr_h'], c['tr_c'], c['tr_vf'], c['tr_vc'], c['tr_y'], c['tr_m']
        va_h, va_c, va_vf, va_vc, va_y, va_m = c['va_h'], c['va_c'], c['va_vf'], c['va_vc'], c['va_y'], c['va_m']
        print(f'Loaded cached v2 train={len(tr_h):,}, val={len(va_h):,} in {time.monotonic()-t0:.1f}s', flush=True)
    else:
        tr_h, tr_c, tr_vf, tr_vc, tr_y, tr_m = load_split_v2(args.data, 'train', stride=args.stride, include_self=True)
        va_h, va_c, va_vf, va_vc, va_y, va_m = load_split_v2(args.data, 'val', stride=args.stride, include_self=False)
        np.savez(cache, tr_h=tr_h, tr_c=tr_c, tr_vf=tr_vf, tr_vc=tr_vc, tr_y=tr_y, tr_m=tr_m,
                 va_h=va_h, va_c=va_c, va_vf=va_vf, va_vc=va_vc, va_y=va_y, va_m=va_m)
        print(f'Built & cached v2 train={len(tr_h):,}, val={len(va_h):,} in {time.monotonic()-t0:.1f}s', flush=True)

    model = BowNeuralNetV2(NUM_MODES).to(device)
    print(f'BowNeuralNetV2 parameters: {sum(x.numel() for x in model.parameters()):,}', flush=True)
    hw = H_WEIGHTS.to(device)
    n_tr, n_va = len(tr_h), len(va_h)

    def eval_val():
        model.eval()
        va_nll_sum = va_no_vox = va_no_rel = va_err15 = 0.0
        va_cnt = 0
        mode_u = np.zeros(NUM_MODES, dtype=np.float64)
        with torch.no_grad():
            for s in range(0, n_va, 1024):
                bh = torch.from_numpy(va_h[s:s + 1024]).to(device)
                bc = torch.from_numpy(va_c[s:s + 1024]).to(device)
                bvf = torch.from_numpy(va_vf[s:s + 1024].astype(np.float32)).to(device)
                bvc = torch.from_numpy(va_vc[s:s + 1024].astype(np.float32)).to(device)
                by = torch.from_numpy(va_y[s:s + 1024]).to(device)
                bm = torch.from_numpy(va_m[s:s + 1024]).to(device)
                lo, me, ls, rh = model(bh, bc, bvf, bvc)
                _, nll = correlated_mixture_loss(lo, me, ls, rh, by, bm, hw, wta_weight=0.0)
                va_nll_sum += float(nll) * len(bh)
                pi = F.softmax(lo, dim=-1)
                mode_u += pi.sum(dim=0).cpu().numpy()
                pm = (pi[:, :, None, None] * me).sum(dim=1)
                va_err15 += float(torch.sqrt((pm[:, 3, 0] - by[:, 3, 0]).square()
                                             + (pm[:, 3, 2] - by[:, 3, 2]).square()).sum())
                # Ablation: no terrain
                bc0 = bc.clone(); bc0[:, 21] = 0.0
                l0, m0, s0, r0 = model(bh, bc0, torch.zeros_like(bvf), torch.zeros_like(bvc))
                _, n0 = correlated_mixture_loss(l0, m0, s0, r0, by, bm, hw, wta_weight=0.0)
                va_no_vox += float(n0) * len(bh)
                # Ablation: no relational
                bh1 = bh.clone(); bh1[:, 18:26] = 0.0
                bc1 = bc.clone(); bc1[:, 28:30] = 0.0
                l1, m1, s1, r1 = model(bh1, bc1, bvf, bvc)
                _, n1 = correlated_mixture_loss(l1, m1, s1, r1, by, bm, hw, wta_weight=0.0)
                va_no_rel += float(n1) * len(bh)
                va_cnt += len(bh)
        return (va_nll_sum / va_cnt, va_no_vox / va_cnt, va_no_rel / va_cnt,
                va_err15 / va_cnt, mode_u / va_cnt)

    # Stage 1: Correlated Mixture NLL Pretraining
    opt = torch.optim.AdamW(model.parameters(), lr=args.lr, weight_decay=2.5e-4)
    sched = torch.optim.lr_scheduler.CosineAnnealingLR(opt, T_max=args.epochs, eta_min=1.2e-4)
    best_val = float('inf')
    best_state = None

    for epoch in range(1, args.epochs + 1):
        model.train()
        perm = np.random.permutation(n_tr)
        tr_sum = steps = 0
        ent_w = 0.03 * max(0.0, 1.0 - epoch / (0.4 * args.epochs))
        t_ep = time.monotonic()
        for s in range(0, n_tr, args.batch):
            idx = perm[s:s + args.batch]
            bh = torch.from_numpy(tr_h[idx]).to(device)
            bc = torch.from_numpy(tr_c[idx]).to(device)
            bvf = torch.from_numpy(tr_vf[idx].astype(np.float32)).to(device)
            bvc = torch.from_numpy(tr_vc[idx].astype(np.float32)).to(device)
            by = torch.from_numpy(tr_y[idx]).to(device)
            bm = torch.from_numpy(tr_m[idx]).to(device)
            bh, bc, bvf, bvc, by = augment_v2(bh, bc, bvf, bvc, by)
            lo, me, ls, rh = model(bh, bc, bvf, bvc)
            loss, nll = correlated_mixture_loss(lo, me, ls, rh, by, bm, hw, wta_weight=0.05, ent_weight=ent_w)
            opt.zero_grad()
            loss.backward()
            nn.utils.clip_grad_norm_(model.parameters(), 2.0)
            opt.step()
            tr_sum += float(nll); steps += 1
        sched.step()
        vn, vnv, vnr, e15, mu = eval_val()
        mark = ''
        if vn < best_val:
            best_val = vn
            best_state = {k: v.detach().cpu().clone() for k, v in model.state_dict().items()}
            mark = ' *BEST*'
        us = ' '.join(f'{u:.2f}' for u in mu)
        print(f'S1 ep {epoch:02d}/{args.epochs} ({time.monotonic()-t_ep:.1f}s) tr={tr_sum/steps:.4f} '
              f'va={vn:.4f} (no_vox={vnv:.4f}, no_rel={vnr:.4f}) xz@15={e15:.3f} pi=[{us}]{mark}', flush=True)

    # Save Stage 1 checkpoint too for ablation comparison
    model.load_state_dict(best_state)
    torch.save({'state_dict': best_state, 'val_nll': best_val}, args.out / 'model_s1.pt')

    # Stage 2: Direct Differentiable Expected-Hit-Probability Fine-Tuning!
    print('\n--- Stage 2: Direct Differentiable Hit-Probability Fine-Tuning ---', flush=True)
    opt_ft = torch.optim.AdamW(model.parameters(), lr=2.0e-4, weight_decay=2e-4)
    sched_ft = torch.optim.lr_scheduler.CosineAnnealingLR(opt_ft, T_max=args.ft_epochs, eta_min=3e-5)
    best_ft_state = {k: v.detach().cpu().clone() for k, v in model.state_dict().items()}
    best_ft_val = best_val
    for epoch in range(1, args.ft_epochs + 1):
        model.train()
        perm = np.random.permutation(n_tr)
        tr_sum = steps = 0
        t_ep = time.monotonic()
        for s in range(0, n_tr, args.batch):
            idx = perm[s:s + args.batch]
            bh = torch.from_numpy(tr_h[idx]).to(device)
            bc = torch.from_numpy(tr_c[idx]).to(device)
            bvf = torch.from_numpy(tr_vf[idx].astype(np.float32)).to(device)
            bvc = torch.from_numpy(tr_vc[idx].astype(np.float32)).to(device)
            by = torch.from_numpy(tr_y[idx]).to(device)
            bm = torch.from_numpy(tr_m[idx]).to(device)
            bh, bc, bvf, bvc, by = augment_v2(bh, bc, bvf, bvc, by)
            lo, me, ls, rh = model(bh, bc, bvf, bvc)
            loss, nll = correlated_mixture_loss(lo, me, ls, rh, by, bm, hw, wta_weight=0.03, hit_weight=0.30)
            opt_ft.zero_grad()
            loss.backward()
            nn.utils.clip_grad_norm_(model.parameters(), 1.5)
            opt_ft.step()
            tr_sum += float(nll); steps += 1
        sched_ft.step()
        vn, vnv, vnr, e15, mu = eval_val()
        us = ' '.join(f'{u:.2f}' for u in mu)
        mark = ''
        if vn <= best_ft_val + 0.015:  # keep FT model if NLL stays sharp while hit-prob improves
            best_ft_val = min(best_ft_val, vn)
            best_ft_state = {k: v.detach().cpu().clone() for k, v in model.state_dict().items()}
            mark = ' *KEEP*'
        print(f'S2 ep {epoch:02d}/{args.ft_epochs} ({time.monotonic()-t_ep:.1f}s) tr={tr_sum/steps:.4f} '
              f'va={vn:.4f} (no_vox={vnv:.4f}, no_rel={vnr:.4f}) xz@15={e15:.3f} pi=[{us}]{mark}', flush=True)

    model.load_state_dict(best_ft_state)
    torch.save({'state_dict': best_ft_state, 'val_nll': best_ft_val, 'horizons': HORIZONS, 'num_modes': NUM_MODES},
               args.out / 'model.pt')
    export_java_v2(model, args.out / 'bow-neural.bin')


if __name__ == '__main__':
    main()
