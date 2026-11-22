"""Train unified end-to-end multi-modal (K=6) neural trajectory + 9x7x9 voxel predictor
for Minecraft 1.8.9 Bow Aim on pure offline Hypixel BedWars + SkyWars captures.
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
HIST_LEN = 24
HIST_CH = 15
CTX_DIM = 22
VOX_DIM = 7 * 9 * 9  # 567
NUM_MODES = 6
AUTOCORR_LAGS = [12, 16, 20, 24, 28, 34, 42, 52]
MAGIC = 0x424E4E31  # 'BNN1'


def cardinal_from_yaw(yaw_deg):
    """Snap Minecraft yaw (deg, 0=+Z, 90=-X, 180=-Z, 270=+X) to nearest cardinal q in {0,1,2,3}."""
    return int(math.floor((yaw_deg + 45.0) / 90.0)) & 3


def rot_xz(dx, dz, q):
    """Rotate world (dx, dz) into cardinal frame q (0=+Z, 1=-X, 2=-Z, 3=+X)."""
    if q == 0:
        return dx, dz
    if q == 1:
        return dz, -dx
    if q == 2:
        return -dx, -dz
    return -dz, dx


def inv_rot_xz(rx, rz, q):
    """Inverse of rot_xz: cardinal frame (rx, rz) back to world (dx, dz)."""
    if q == 0:
        return rx, rz
    if q == 1:
        return -rz, rx
    if q == 2:
        return -rx, -rz
    return rz, -rx


def rot_vox(vox_7x9x9, q):
    """Exact lossless rotation of (7, 9, 9) patch [dy, dz, dx] into cardinal frame q.
    After rotation, axis 1 = rz (-4..+4) and axis 2 = rx (-4..+4) matching rot_xz!
    """
    if q == 0:
        return vox_7x9x9
    if q == 1:
        # rx = dz, rz = -dx => new[y, rz, rx] = old[y, rx, -rz]
        return np.transpose(vox_7x9x9[:, :, ::-1], (0, 2, 1))
    if q == 2:
        # rx = -dx, rz = -dz
        return vox_7x9x9[:, ::-1, ::-1]
    # q == 3: rx = -dz, rz = dx => new[y, rz, rx] = old[y, -rx, rz]
    return np.transpose(vox_7x9x9[:, ::-1, :], (0, 2, 1))


def unpack_vox(packed_71):
    bits = np.unpackbits(packed_71)[:VOX_DIM]
    return bits.reshape(7, 9, 9)


def build_features_from_track(pos, rot, flags, vox_packed, i, valid_past, q=None, use_terrain=True):
    """Exact feature builder shared with Java 8 inference (zero future leakage!).
    pos: (T, 3), rot: (T, 2) [yaw_deg, pitch_deg], flags: (T,) uint8, vox_packed: (T, 71) or None
    i: current index in track; valid_past: number of consecutive valid past ticks ending at i (>= 2).
    Returns: hist (15, 24), ctx (22,), vox (567,), q (int).
    """
    p0 = pos[i]
    yaw0 = float(rot[i, 0])
    if q is None:
        q = cardinal_from_yaw(yaw0)
    q_deg = q * 90.0

    # History sequence (channels first: 15 x 24, age 0 at index 0 .. age 23 at index 23)
    hist = np.zeros((HIST_CH, HIST_LEN), dtype=np.float32)
    max_age = min(valid_past - 1, HIST_LEN - 1)
    for age in range(HIST_LEN):
        clamped = min(age, max_age)
        idx = i - clamped
        prev_idx = i - min(clamped + 1, valid_past - 1)
        dx = float(pos[idx, 0] - p0[0])
        dy = float(pos[idx, 1] - p0[1])
        dz = float(pos[idx, 2] - p0[2])
        rx, rz = rot_xz(dx, dz, q)

        vdx = float(pos[idx, 0] - pos[prev_idx, 0])
        vdy = float(pos[idx, 1] - pos[prev_idx, 1])
        vdz = float(pos[idx, 2] - pos[prev_idx, 2])
        vrx, vrz = rot_xz(vdx, vdz, q)

        rel_yaw = math.radians(float(rot[idx, 0]) - q_deg)
        pitch = float(rot[idx, 1]) / 90.0
        fl = int(flags[idx])

        hist[0, age] = max(-4.0, min(4.0, rx / 4.0))
        hist[1, age] = max(-4.0, min(4.0, dy / 4.0))
        hist[2, age] = max(-4.0, min(4.0, rz / 4.0))
        hist[3, age] = max(-3.0, min(3.0, vrx * 3.0))
        hist[4, age] = max(-3.0, min(3.0, vdy * 3.0))
        hist[5, age] = max(-3.0, min(3.0, vrz * 3.0))
        hist[6, age] = math.sin(rel_yaw)
        hist[7, age] = math.cos(rel_yaw)
        hist[8, age] = max(-1.0, min(1.0, pitch))
        hist[9, age] = 1.0 if (fl & 1) else 0.0        # sneaking
        hist[10, age] = 1.0 if (fl & 2) else 0.0       # held_block
        hist[11, age] = 1.0 if (fl & 12) else 0.0      # sword or bow
        hist[12, age] = 1.0 if (fl & 32) else 0.0      # hurt
        hist[13, age] = 1.0 if (fl & 80) else 0.0      # using or swinging
        hist[14, age] = 1.0 if age <= max_age else 0.0  # valid

    # Context & periodicity summary (22 floats)
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

    # Speed mean & std over past min(32, valid_past - 1) ticks
    n_sp = min(32, valid_past - 1)
    if n_sp >= 2:
        d = pos[i - n_sp + 1:i + 1] - pos[i - n_sp:i]
        spd = np.hypot(d[:, 0], d[:, 2])
        ctx[10] = float(spd.mean()) * 3.0
        ctx[11] = float(spd.std()) * 3.0

    # Horizontal velocity autocorrelation at 8 lags (captures periodic strafers!)
    n_ac = min(110, valid_past - 4)
    if n_ac >= 16:
        # 3-tick smoothed horizontal velocity
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
                    denom = float(np.sqrt(
                        np.sum(vx3[:-lag] ** 2 + vz3[:-lag] ** 2) *
                        np.sum(vx3[lag:] ** 2 + vz3[lag:] ** 2)
                    ))
                    if denom > 1e-5:
                        ctx[12 + li] = max(-1.0, min(1.0, num / denom))

    ctx[20] = math.log1p(min(256, valid_past)) / 5.5
    ctx[21] = 1.0 if (use_terrain and vox_packed is not None) else 0.0
    np.clip(ctx, -4.0, 4.0, out=ctx)

    if use_terrain and vox_packed is not None:
        v = rot_vox(unpack_vox(vox_packed[i]), q).reshape(-1).astype(np.float32)
    else:
        v = np.zeros(VOX_DIM, dtype=np.float32)
    return hist, ctx, v, q


def contiguous_runs(ticks, pos):
    """Return past_len[i] (consecutive ticks ending at i) and future_len[i] (starting at i).
    Interpolates tiny 1-2 tick gaps identically to BowMotion.Track.add().
    """
    n = len(ticks)
    if n < 2:
        return np.ones(n, dtype=np.int32), np.zeros(n, dtype=np.int32)
    past = np.ones(n, dtype=np.int32)
    for i in range(1, n):
        gap = int(ticks[i] - ticks[i - 1])
        dist = float(np.linalg.norm(pos[i] - pos[i - 1]))
        if gap == 1 and dist <= 2.5:
            past[i] = past[i - 1] + 1
        else:
            past[i] = 1
    fut = np.zeros(n, dtype=np.int32)
    for i in range(n - 2, -1, -1):
        gap = int(ticks[i + 1] - ticks[i])
        dist = float(np.linalg.norm(pos[i + 1] - pos[i]))
        if gap == 1 and dist <= 2.5:
            fut[i] = fut[i + 1] + 1
    return past, fut


def load_split_dataset(data_dir: Path, split: str, stride: int = 3):
    manifest = json.loads((data_dir / 'manifest.json').read_text())
    hists, ctxs, voxs, targets, masks, qs = [], [], [], [], [], []
    for m in manifest['matches']:
        if m['split'] != split:
            continue
        npz = np.load(data_dir / m['file'])
        n_players = int(npz['num_players'][0])
        for p in range(n_players):
            ticks = npz[f'p{p}_tick']
            pos = npz[f'p{p}_pos']
            rot = npz[f'p{p}_rot']
            flags = npz[f'p{p}_flags']
            vox = npz[f'p{p}_vox']
            past, fut = contiguous_runs(ticks, pos)
            for i in range(0, len(ticks), stride):
                if past[i] < 6 or fut[i] < HORIZONS[3]:  # require >= 15 future ticks
                    continue
                hist, ctx, v, q = build_features_from_track(pos, rot, flags, vox, i, int(past[i]))
                tgt = np.zeros((NUM_H, 3), dtype=np.float32)
                msk = np.zeros(NUM_H, dtype=np.float32)
                p0 = pos[i]
                for hi, h in enumerate(HORIZONS):
                    if fut[i] >= h:
                        d = pos[i + h] - p0
                        rx, rz = rot_xz(float(d[0]), float(d[2]), q)
                        tgt[hi, 0] = rx
                        tgt[hi, 1] = float(d[1])
                        tgt[hi, 2] = rz
                        msk[hi] = 1.0
                hists.append(hist)
                ctxs.append(ctx)
                voxs.append(v.astype(np.uint8))  # store uint8 in RAM (4x smaller!)
                targets.append(tgt)
                masks.append(msk)
                qs.append(q)
    return (
        np.asarray(hists, dtype=np.float32),
        np.asarray(ctxs, dtype=np.float32),
        np.asarray(voxs, dtype=np.uint8),
        np.asarray(targets, dtype=np.float32),
        np.asarray(masks, dtype=np.float32),
    )


class BowNeuralNet(nn.Module):
    def __init__(self, num_modes=NUM_MODES):
        super().__init__()
        self.num_modes = num_modes
        self.conv1 = nn.Conv1d(HIST_CH, 32, kernel_size=3, stride=1, padding=1)
        self.conv2 = nn.Conv1d(32, 48, kernel_size=3, stride=2, padding=1)
        self.conv3 = nn.Conv1d(48, 64, kernel_size=3, stride=2, padding=1)
        self.hist_proj = nn.Linear(64 * 3, 96)

        self.vox_fc1 = nn.Linear(VOX_DIM, 64)
        self.vox_fc2 = nn.Linear(64, 64)

        self.trunk_in = nn.Linear(96 + CTX_DIM + 64, 192)
        self.res1 = nn.Linear(192, 192)
        self.res2 = nn.Linear(192, 192)

        self.head_logits = nn.Linear(192, num_modes)
        self.head_means = nn.Linear(192, num_modes * NUM_H * 3)
        self.head_logstd = nn.Linear(192, num_modes * NUM_H * 3)

        # Initialize logstd bias around reasonable horizon uncertainties (~0.4 to 2.5 blocks)
        with torch.no_grad():
            init_sig = torch.tensor([0.28, 0.48, 0.78, 1.15, 1.65, 2.25, 3.00], dtype=torch.float32)
            bias = init_sig.log().view(1, NUM_H, 1).expand(num_modes, NUM_H, 3).reshape(-1)
            self.head_logstd.bias.copy_(bias)
            # Spread initial mode means slightly so modes break symmetry cleanly
            nn.init.normal_(self.head_means.weight, std=0.02)
            nn.init.normal_(self.head_means.bias, std=0.15)

    def forward(self, hist, ctx, vox):
        h = F.silu(self.conv1(hist))
        h = F.silu(self.conv2(h))
        h = F.silu(self.conv3(h))  # (B, 64, 6)
        h_cat = torch.cat([h[:, :, 0], h.mean(dim=-1), h.amax(dim=-1)], dim=-1)
        h_emb = F.silu(self.hist_proj(h_cat))

        v = F.silu(self.vox_fc1(vox))
        v_emb = F.silu(self.vox_fc2(v))

        x = F.silu(self.trunk_in(torch.cat([h_emb, ctx, v_emb], dim=-1)))
        x = F.silu(x + self.res2(F.silu(self.res1(x))))

        logits = self.head_logits(x)
        means = self.head_means(x).view(-1, self.num_modes, NUM_H, 3)
        logstd = self.head_logstd(x).view(-1, self.num_modes, NUM_H, 3).clamp(-2.1, 2.1)
        return logits, means, logstd


# Horizon weights: emphasize short-to-mid horizons (3..22 ticks) where most hits happen while keeping 30..42 sharp
H_WEIGHTS = torch.tensor([1.35, 1.25, 1.15, 1.05, 0.95, 0.80, 0.65], dtype=torch.float32)


def mixture_nll_loss(logits, means, logstd, target, mask, h_weights, wta_weight=0.05, ent_weight=0.0):
    # target: (B, H, 3), mask: (B, H)
    diff = target[:, None, :, :] - means  # (B, K, H, 3)
    inv_std = (-logstd).exp()
    sq_err = (diff * inv_std).square()
    # Gaussian NLL per (B, K, H): sum over 3 axes
    nll_h = 0.5 * (sq_err + 2.0 * logstd).sum(dim=-1)  # (B, K, H)
    w = mask[:, None, :] * h_weights[None, None, :]  # (B, 1, H)
    w_sum = w.sum(dim=-1).clamp(min=1e-5)  # (B, 1)
    # Joint trajectory log-likelihood for each mode k (scaled by active horizons)
    joint_logp = -(nll_h * w).sum(dim=-1)  # (B, K)
    log_pi = F.log_softmax(logits, dim=-1)  # (B, K)
    # Mixture NLL normalized by total horizon weight
    mix_nll = -(torch.logsumexp(log_pi + joint_logp, dim=-1) / w_sum.squeeze(-1)).mean()

    # Auxiliary winner-take-all smooth-L1 error (MPS-native, sharpens modes & prevents collapse)
    l1_k = ((torch.sqrt(diff.square() + 1e-6).sum(dim=-1) * w).sum(dim=-1) / w_sum)  # (B, K)
    wta = l1_k.min(dim=-1).values.mean()

    loss = mix_nll + wta_weight * wta
    if ent_weight > 0:
        pi = log_pi.exp()
        ent = -(pi * log_pi).sum(dim=-1).mean()
        loss = loss - ent_weight * ent
    return loss, mix_nll.detach()


def augment_batch(hist, ctx, vox, target, terrain_drop_prob=0.15):
    """Exact horizontal mirror symmetry across forward axis + terrain dropout."""
    B = hist.shape[0]
    device = hist.device
    flip = torch.rand(B, device=device) < 0.5
    if flip.any():
        hist = hist.clone()
        ctx = ctx.clone()
        vox = vox.clone()
        target = target.clone()
        # Flip rx (ch 0), vrx (ch 3), sin(rel_yaw) (ch 6)
        hist[flip, 0] *= -1.0
        hist[flip, 3] *= -1.0
        hist[flip, 6] *= -1.0
        # Flip ctx rx velocities: indices 0, 3, 6, 8
        ctx[flip, 0] *= -1.0
        ctx[flip, 3] *= -1.0
        ctx[flip, 6] *= -1.0
        ctx[flip, 8] *= -1.0
        # Flip voxel patch along dx axis (last axis of 7x9x9)
        v_flip = vox[flip].view(-1, 7, 9, 9).flip(dims=[-1]).reshape(-1, VOX_DIM)
        vox[flip] = v_flip
        # Flip target rx
        target[flip, :, 0] *= -1.0

    if terrain_drop_prob > 0:
        drop = torch.rand(B, device=device) < terrain_drop_prob
        if drop.any():
            vox = vox.clone()
            ctx = ctx.clone()
            vox[drop] = 0.0
            ctx[drop, 21] = 0.0
    return hist, ctx, vox, target


def export_java_binary(model: BowNeuralNet, out_path: Path, calibration_curve=None):
    """Write compact big-endian float32 weights for pure Java 8 inference."""
    model.eval()
    sd = model.state_dict()
    order = [
        'conv1.weight', 'conv1.bias',
        'conv2.weight', 'conv2.bias',
        'conv3.weight', 'conv3.bias',
        'hist_proj.weight', 'hist_proj.bias',
        'vox_fc1.weight', 'vox_fc1.bias',
        'vox_fc2.weight', 'vox_fc2.bias',
        'trunk_in.weight', 'trunk_in.bias',
        'res1.weight', 'res1.bias',
        'res2.weight', 'res2.bias',
        'head_logits.weight', 'head_logits.bias',
        'head_means.weight', 'head_means.bias',
        'head_logstd.weight', 'head_logstd.bias',
    ]
    if calibration_curve is None:
        calibration_curve = [i / 10.0 for i in range(11)]
    out_path.parent.mkdir(parents=True, exist_ok=True)
    with out_path.open('wb') as f:
        f.write(struct.pack('>IIII', MAGIC, NUM_MODES, NUM_H, len(calibration_curve)))
        for h in HORIZONS:
            f.write(struct.pack('>I', h))
        for c in calibration_curve:
            f.write(struct.pack('>f', float(c)))
        total_floats = 0
        for key in order:
            arr = sd[key].detach().cpu().numpy().astype('>f4').reshape(-1)
            f.write(arr.tobytes())
            total_floats += arr.size
    print(f'Exported Java model to {out_path} ({total_floats:,} floats, {out_path.stat().st_size / 1024:.1f} KB)', flush=True)


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--data', type=Path, default=Path('runtime/bow-aim/neural-data'))
    p.add_argument('--out', type=Path, default=Path('runtime/bow-aim/neural-model'))
    p.add_argument('--epochs', type=int, default=28)
    p.add_argument('--batch', type=int, default=512)
    p.add_argument('--lr', type=float, default=1.5e-3)
    p.add_argument('--stride', type=int, default=3)
    p.add_argument('--seed', type=int, default=2026)
    args = p.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)

    torch.manual_seed(args.seed)
    np.random.seed(args.seed)
    device = torch.device('mps' if torch.backends.mps.is_available() else 'cpu')
    print(f'Using device: {device}', flush=True)

    t0 = time.monotonic()
    cache_path = args.data / f'cache_s{args.stride}.npz'
    if cache_path.exists():
        c = np.load(cache_path)
        tr_h, tr_c, tr_v, tr_y, tr_m = c['tr_h'], c['tr_c'], c['tr_v'], c['tr_y'], c['tr_m']
        va_h, va_c, va_v, va_y, va_m = c['va_h'], c['va_c'], c['va_v'], c['va_y'], c['va_m']
        print(f'Loaded cached train={len(tr_h):,}, val={len(va_h):,} in {time.monotonic() - t0:.1f}s', flush=True)
    else:
        tr_h, tr_c, tr_v, tr_y, tr_m = load_split_dataset(args.data, 'train', stride=args.stride)
        va_h, va_c, va_v, va_y, va_m = load_split_dataset(args.data, 'val', stride=args.stride)
        np.savez(cache_path, tr_h=tr_h, tr_c=tr_c, tr_v=tr_v, tr_y=tr_y, tr_m=tr_m,
                 va_h=va_h, va_c=va_c, va_v=va_v, va_y=va_y, va_m=va_m)
        print(f'Loaded & cached train={len(tr_h):,}, val={len(va_h):,} in {time.monotonic() - t0:.1f}s', flush=True)

    model = BowNeuralNet(NUM_MODES).to(device)
    n_params = sum(p_.numel() for p_ in model.parameters())
    print(f'BowNeuralNet parameters: {n_params:,}', flush=True)

    opt = torch.optim.AdamW(model.parameters(), lr=args.lr, weight_decay=2e-4)
    sched = torch.optim.lr_scheduler.CosineAnnealingLR(opt, T_max=args.epochs, eta_min=1e-4)
    hw = H_WEIGHTS.to(device)

    best_val = float('inf')
    best_state = None
    n_tr = len(tr_h)
    n_va = len(va_h)

    for epoch in range(1, args.epochs + 1):
        model.train()
        perm = np.random.permutation(n_tr)
        tr_nll_sum = 0.0
        steps = 0
        ent_w = 0.03 * max(0.0, 1.0 - epoch / (0.4 * args.epochs))
        t_ep = time.monotonic()
        for s in range(0, n_tr, args.batch):
            idx = perm[s:s + args.batch]
            bh = torch.from_numpy(tr_h[idx]).to(device)
            bc = torch.from_numpy(tr_c[idx]).to(device)
            bv = torch.from_numpy(tr_v[idx].astype(np.float32)).to(device)
            by = torch.from_numpy(tr_y[idx]).to(device)
            bm = torch.from_numpy(tr_m[idx]).to(device)
            bh, bc, bv, by = augment_batch(bh, bc, bv, by, terrain_drop_prob=0.15)
            logits, means, logstd = model(bh, bc, bv)
            loss, nll = mixture_nll_loss(logits, means, logstd, by, bm, hw, wta_weight=0.05, ent_weight=ent_w)
            opt.zero_grad()
            loss.backward()
            nn.utils.clip_grad_norm_(model.parameters(), 2.0)
            opt.step()
            tr_nll_sum += float(nll)
            steps += 1
        sched.step()

        # Validation
        model.eval()
        va_nll_sum = 0.0
        va_nll_noterrain = 0.0
        va_steps = 0
        va_err15_mean = 0.0
        va_err15_best = 0.0
        mode_usage = np.zeros(NUM_MODES, dtype=np.float64)
        with torch.no_grad():
            for s in range(0, n_va, 1024):
                bh = torch.from_numpy(va_h[s:s + 1024]).to(device)
                bc = torch.from_numpy(va_c[s:s + 1024]).to(device)
                bv = torch.from_numpy(va_v[s:s + 1024].astype(np.float32)).to(device)
                by = torch.from_numpy(va_y[s:s + 1024]).to(device)
                bm = torch.from_numpy(va_m[s:s + 1024]).to(device)
                logits, means, logstd = model(bh, bc, bv)
                _, nll = mixture_nll_loss(logits, means, logstd, by, bm, hw, wta_weight=0.0)
                va_nll_sum += float(nll) * len(bh)
                pi = F.softmax(logits, dim=-1)
                mode_usage += pi.sum(dim=0).cpu().numpy()
                # Check h=15 (index 3) XZ error of weighted mean and top-1 mode
                pred_mean = (pi[:, :, None, None] * means).sum(dim=1)
                top_k = pi.argmax(dim=-1)
                pred_top = means[torch.arange(len(bh), device=device), top_k]
                va_err15_mean += float(torch.sqrt((pred_mean[:, 3, 0] - by[:, 3, 0]).square()
                                                  + (pred_mean[:, 3, 2] - by[:, 3, 2]).square()).sum())
                va_err15_best += float(torch.sqrt((pred_top[:, 3, 0] - by[:, 3, 0]).square()
                                                  + (pred_top[:, 3, 2] - by[:, 3, 2]).square()).sum())
                # Also check without terrain (open-air ablation)
                bc0 = bc.clone(); bc0[:, 21] = 0.0
                l0, m0, s0 = model(bh, bc0, torch.zeros_like(bv))
                _, nll0 = mixture_nll_loss(l0, m0, s0, by, bm, hw, wta_weight=0.0)
                va_nll_noterrain += float(nll0) * len(bh)
                va_steps += len(bh)

        va_nll = va_nll_sum / max(1, va_steps)
        va_nll_nt = va_nll_noterrain / max(1, va_steps)
        e15_m = va_err15_mean / max(1, va_steps)
        e15_t = va_err15_best / max(1, va_steps)
        usage_str = ' '.join(f'{u / max(1, va_steps):.2f}' for u in mode_usage)
        mark = ''
        if va_nll < best_val:
            best_val = va_nll
            best_state = {k: v.detach().cpu().clone() for k, v in model.state_dict().items()}
            mark = ' *BEST*'
        print(f'ep {epoch:02d}/{args.epochs} ({time.monotonic()-t_ep:.1f}s) '
              f'tr_nll={tr_nll_sum/steps:.4f} va_nll={va_nll:.4f} (no_vox={va_nll_nt:.4f}) '
              f'xz@15t mean={e15_m:.3f} top1={e15_t:.3f} pi=[{usage_str}]{mark}', flush=True)

    model.load_state_dict(best_state)
    torch.save({'state_dict': best_state, 'val_nll': best_val, 'horizons': HORIZONS, 'num_modes': NUM_MODES},
               args.out / 'model.pt')
    export_java_binary(model, args.out / 'bow-neural.bin')


if __name__ == '__main__':
    main()
