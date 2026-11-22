"""Train BowWaitNet (V_wait) on top of frozen BowNeuralNetV4's 256-dim spatial+void+dynamic-block trunk!
Uses the exact same 2-Round Causal Longstaff-Schwartz FVI + validation fireAlpha tuning,
and evaluates sealed BedWars + SkyWars bow tactical EV & Void Kills / 10 arrows.
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
    BowNeuralNetV4, NUM_MODES, FINE_Y, FINE_Z, FINE_X, TOP_CH, TOP_S, MAX_H,
    build_features_v4, contiguous_runs,
)
from eval_and_calibrate import SPEED_FULL, GRAVITY, apply_calibration
from eval_v4 import interp_modes_v4
from void_and_wait import (
    THROW_SPEED, THROW_GRAVITY, TACTICAL_DIM, MAGIC_WAIT,
    BowWaitNet, build_tactical_vec, build_solid_set, collect_vantage_pool,
    check_los_ray, solve_aim_ev, eval_shot_truth_smooth,
    rollout_ev_policy, rollout_smart_policy,
)
import struct

TRUNK_DIM_V4 = 256
WAIT_IN_DIM_V4 = TRUNK_DIM_V4 + TACTICAL_DIM  # 266


def mirror_v4_inputs(bh, bc, bvb, bf3, bt2):
    h = bh.clone(); c = bc.clone(); vb = bvb.clone()
    for ch in (0, 3, 6, 9, 18, 23, 27):
        h[:, ch] *= -1.0
    for ci in (0, 3, 6, 8, 32):
        c[:, ci] *= -1.0
    vb[:, 0] *= -1.0
    return h, c, vb, bf3.flip(dims=[-1]), bt2.flip(dims=[-1])


def export_wait_v4(model: BowWaitNet, fire_alpha: float, out_path: Path):
    out_path.parent.mkdir(parents=True, exist_ok=True)
    with open(out_path, 'wb') as f:
        f.write(struct.pack('>iiiif', MAGIC_WAIT, WAIT_IN_DIM_V4, model.h1, model.h2, float(fire_alpha)))
        for t in (model.fc1.weight, model.fc1.bias, model.fc2.weight, model.fc2.bias,
                  model.fc3.weight, model.fc3.bias):
            f.write(t.detach().cpu().numpy().astype('>f4').ravel().tobytes())
    print(f'Exported V4 BowWaitNet to {out_path} ({out_path.stat().st_size:,} bytes, in={WAIT_IN_DIM_V4}, alpha={fire_alpha:.3f})', flush=True)


def extract_episodes_v4(model, cal_curve, device, data_dir: Path, split: str,
                        win_len=60, win_stride=75, max_vantages=2,
                        include_throwables=True, refine=False, compute_mirrors=False,
                        seed=42, max_per_match=25):
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
            pos = npz[f'p{p_idx}_pos']; spos = npz[f'p{p_idx}_spos']
            rot = npz[f'p{p_idx}_rot']; flags = npz[f'p{p_idx}_flags']
            rel = npz[f'p{p_idx}_rel']; blk4 = npz[f'p{p_idx}_blk4']
            vox = npz[f'p{p_idx}_vox']; pvox = npz[f'p{p_idx}_pvox']; top = npz[f'p{p_idx}_top']
            past, fut = contiguous_runs(ticks, pos)

            starts = []
            i = 0
            while i < len(ticks):
                if past[i] >= 10 and fut[i] >= win_len + 42:
                    starts.append(i); i += win_stride
                else:
                    i += 5
            if not starts:
                continue

            needed = sorted({s + dt for s in starts for dt in range(win_len)})
            hl, cl, vbl, f3l, t2l, ql = [], [], [], [], [], []
            for idx in needed:
                h, c, vb, f3, t2, q = build_features_v4(pos, spos, rot, flags, rel, blk4, vox, pvox, top, idx, int(past[idx]))
                hl.append(h); cl.append(c); vbl.append(vb); f3l.append(f3); t2l.append(t2); ql.append(q)

            n_tot = len(needed)
            trunks = np.empty((n_tot, TRUNK_DIM_V4), dtype=np.float32)
            trunks_m = np.empty((n_tot, TRUNK_DIM_V4), dtype=np.float32) if compute_mirrors else None
            pi_all = np.empty((n_tot, NUM_MODES), dtype=np.float32)
            mean_all = np.empty((n_tot, NUM_MODES, 9, 3), dtype=np.float32)
            std_all = np.empty((n_tot, NUM_MODES, 9, 3), dtype=np.float32)
            rho_all = np.empty((n_tot, NUM_MODES, 9), dtype=np.float32)

            for b0 in range(0, n_tot, 256):
                b1 = min(n_tot, b0 + 256)
                bh = torch.from_numpy(np.asarray(hl[b0:b1])).to(device)
                bc = torch.from_numpy(np.asarray(cl[b0:b1])).to(device)
                bvb = torch.from_numpy(np.asarray(vbl[b0:b1])).to(device)
                bf3 = torch.from_numpy(np.asarray(f3l[b0:b1])).to(device)
                bt2 = torch.from_numpy(np.asarray(t2l[b0:b1])).to(device)
                with torch.no_grad():
                    x, lo, me, ls, rh, _ = model(bh, bc, bvb, bf3, bt2, return_trunk=True)
                    trunks[b0:b1] = x.cpu().numpy()
                    pi_all[b0:b1] = F.softmax(lo, dim=-1).cpu().numpy()
                    mean_all[b0:b1] = me.cpu().numpy()
                    std_all[b0:b1] = ls.exp().cpu().numpy()
                    rho_all[b0:b1] = rh.cpu().numpy()
                    if compute_mirrors:
                        bhm, bcm, bvbm, bf3m, bt2m = mirror_v4_inputs(bh, bc, bvb, bf3, bt2)
                        xm, _, _, _, _, _ = model(bhm, bcm, bvbm, bf3m, bt2m, return_trunk=True)
                        trunks_m[b0:b1] = xm.cpu().numpy()
            idx_map = {idx: k for k, idx in enumerate(needed)}

            for start in starts:
                mid_pos = pos[start + win_len // 2]
                dists = np.linalg.norm(vantages - mid_pos[None, :], axis=1)
                valid_v = np.where((dists >= 11.0) & (dists <= 44.0))[0]
                if len(valid_v) == 0: continue
                rng.shuffle(valid_v)
                chosen_v = []
                for vi in valid_v[:25]:
                    vp = vantages[vi]
                    sx, sy, sz = float(vp[0]), float(vp[1]), float(vp[2])
                    eye_y = sy + 1.62
                    los_hits = sum(
                        1 for dt in (0, 15, 30, 45, 59)
                        if check_los_ray(sx, eye_y, sz, float(pos[start + dt, 0]),
                                         float(pos[start + dt, 1]) + 1.1, float(pos[start + dt, 2]), solid_set)
                    )
                    if los_hits >= 2:
                        chosen_v.append((sx, sy, sz, float(dists[vi])))
                        if len(chosen_v) >= max_vantages: break
                if not chosen_v: continue

                for (sx, sy, sz, dist_mid) in chosen_v:
                    eye_y = sy + 1.62
                    weapons = [(False, SPEED_FULL, GRAVITY)]
                    if include_throwables and dist_mid <= 26.0:
                        weapons.append((True, THROW_SPEED, THROW_GRAVITY))
                    for (is_throw, spd, grav) in weapons:
                        ep_trunk = np.empty((win_len, TRUNK_DIM_V4), dtype=np.float32)
                        ep_trunk_m = np.empty((win_len, TRUNK_DIM_V4), dtype=np.float32) if compute_mirrors else None
                        ep_tac = np.empty((win_len, TACTICAL_DIM), dtype=np.float32)
                        ep_q_model = np.zeros(win_len, dtype=np.float64)
                        ep_p_model = np.zeros(win_len, dtype=np.float64)
                        ep_r_true = np.zeros(win_len, dtype=np.float64)
                        ep_p_true = np.zeros(win_len, dtype=np.float64)
                        ep_vk_true = np.zeros(win_len, dtype=np.bool_)
                        ep_has_los = np.zeros(win_len, dtype=np.bool_)
                        ep_losing_cover = np.zeros(win_len, dtype=np.bool_)

                        for dt in range(win_len):
                            idx = start + dt; k = idx_map[idx]
                            p0 = pos[idx]; q = ql[k]; pi = pi_all[k]
                            # Slice to 43 ticks for solve_aim_ev compatibility (bow max = 42t)
                            paths72, stds72, rhos72 = interp_modes_v4(spos[idx], q, mean_all[k], std_all[k], rho_all[k])
                            paths = paths72[:, :43, :]; stds = stds72[:, :43, :]; rhos = rhos72[:, :43]
                            shot_ev, ev_m, ph_m, pv_m, _, _ = solve_aim_ev(
                                sx, eye_y, sz, spd, grav, pi, paths, stds, rhos,
                                solid_set, is_throwable=is_throw, punch_level=0, use_void_ev=True, refine=refine,
                            )
                            has_los = shot_ev is not None
                            cal_p = apply_calibration(ph_m, cal_curve) if cal_curve is not None else ph_m
                            cal_scale = (cal_p / ph_m) if ph_m > 1e-4 else 1.0
                            ev_cal = ev_m * cal_scale
                            pv_cal = min(1.0, pv_m * cal_scale)

                            ep_trunk[dt] = trunks[k]
                            if compute_mirrors: ep_trunk_m[dt] = trunks_m[k]
                            ep_tac[dt] = build_tactical_vec(sx, eye_y, sz, float(p0[0]), float(p0[1]), float(p0[2]),
                                                            q, ev_cal, cal_p, pv_cal, is_throw, has_los)
                            ep_q_model[dt] = ev_cal; ep_p_model[dt] = cal_p; ep_has_los[dt] = has_los
                            ahead = np.tensordot(pi, paths, axes=([0], [0]))[4]
                            ep_losing_cover[dt] = not check_los_ray(sx, eye_y, sz, float(ahead[0]), float(ahead[1]) + 0.9, float(ahead[2]), solid_set)
                            if has_los:
                                pt, vkt, rt = eval_shot_truth_smooth(shot_ev, spos, idx, solid_set, is_throwable=is_throw)
                                ep_p_true[dt] = pt; ep_vk_true[dt] = vkt; ep_r_true[dt] = rt

                        if ep_has_los.sum() >= 6:
                            episodes.append({
                                'mode': m['mode'], 'is_throw': is_throw,
                                'trunk': ep_trunk, 'trunk_m': ep_trunk_m, 'tac': ep_tac,
                                'q_model': ep_q_model, 'p_model': ep_p_model,
                                'r_true': ep_r_true, 'p_true': ep_p_true, 'vk_true': ep_vk_true,
                                'has_los': ep_has_los, 'losing_cover': ep_losing_cover,
                            })
                            match_eps += 1
                    if max_per_match and match_eps >= max_per_match: break
                if max_per_match and match_eps >= max_per_match: break
    return episodes


def main():
    p = argparse.ArgumentParser()
    p.add_argument('--data', type=Path, default=Path('runtime/bow-aim/neural-data-v4'))
    p.add_argument('--model-v4', type=Path, default=Path('runtime/bow-aim/neural-model-v4/winner.pt'))
    p.add_argument('--out-bin', type=Path, default=Path('practice-client/assets/practice/bow-wait.bin'))
    args = p.parse_args()

    t0 = time.time()
    device = torch.device('mps' if torch.backends.mps.is_available() else 'cpu')
    ck = torch.load(args.model_v4, map_location='cpu')
    v4 = BowNeuralNetV4(NUM_MODES).to(device); v4.load_state_dict(ck['state_dict']); v4.eval()
    cal = np.asarray(ck['calibration'], dtype=np.float64) if ck.get('calibration') is not None else None

    print('Extracting V4 wait train episodes...', flush=True)
    tr_eps = extract_episodes_v4(v4, cal, device, args.data, 'train', win_len=60, win_stride=85,
                                 max_vantages=2, include_throwables=True, refine=False,
                                 compute_mirrors=True, seed=11, max_per_match=12)
    print(f'  Train episodes: {len(tr_eps)} ({time.time()-t0:.1f}s)', flush=True)
    val_eps = extract_episodes_v4(v4, cal, device, args.data, 'val', win_len=60, win_stride=65,
                                  max_vantages=2, include_throwables=False, refine=True,
                                  compute_mirrors=False, seed=22, max_per_match=55)
    print(f'  Val episodes: {len(val_eps)} ({time.time()-t0:.1f}s)', flush=True)

    # 2-Round Causal Longstaff-Schwartz FVI on 266-dim input!
    xs_o, xs_m, slices = [], [], []
    off = 0
    for ep in tr_eps:
        T = len(ep['q_model'])
        xo = np.concatenate([ep['trunk'][:-1], ep['tac'][:-1]], axis=1)
        tm = ep['tac'][:-1].copy(); tm[:, 0] *= -1.0
        xm = np.concatenate([ep['trunk_m'][:-1], tm], axis=1)
        xs_o.append(xo); xs_m.append(xm); slices.append((off, off + T - 1)); off += T - 1
    X_o = np.concatenate(xs_o, axis=0)
    X_all = np.concatenate([X_o, np.concatenate(xs_m, axis=0)], axis=0)
    N = len(X_o)
    X_ot = torch.from_numpy(X_o).to(device)
    val_feats = [torch.from_numpy(np.concatenate([ep['trunk'], ep['tac']], axis=1)).to(device) for ep in val_eps]

    wm = BowWaitNet(WAIT_IN_DIM_V4, 96, 48).to(device)

    def fit(y_base, lr, epochs=18):
        y_all = np.concatenate([y_base, y_base], axis=0).astype(np.float32)
        ds_x = torch.from_numpy(X_all); ds_y = torch.from_numpy(y_all)
        opt = torch.optim.AdamW(wm.parameters(), lr=lr, weight_decay=2e-4)
        sched = torch.optim.lr_scheduler.CosineAnnealingLR(opt, T_max=epochs)
        for _ in range(epochs):
            wm.train(); perm = torch.randperm(len(ds_x))
            for b0 in range(0, len(ds_x), 1024):
                idx = perm[b0:b0 + 1024]
                loss = F.smooth_l1_loss(wm(ds_x[idx].to(device)), ds_y[idx].to(device), beta=0.5)
                opt.zero_grad(); loss.backward()
                torch.nn.utils.clip_grad_norm_(wm.parameters(), 2.0); opt.step()
            sched.step()

    y0 = np.zeros(N, dtype=np.float32)
    for ep, (s0, s1) in zip(tr_eps, slices):
        r = ep['r_true']; V = np.zeros(len(r))
        V[-1] = r[-1]
        for t in range(len(r) - 2, -1, -1): V[t] = max(r[t], 0.955 * V[t + 1])
        y0[s0:s1] = (0.955 * V[1:]).astype(np.float32)
    fit(y0, 2e-3)
    print(f'  Round 0 warm-start: mean_y={y0.mean():.3f}', flush=True)

    for rnd in (1, 2):
        wm.eval()
        with torch.no_grad():
            vp = np.concatenate([wm(X_ot[b0:b0 + 4096]).cpu().numpy() for b0 in range(0, N, 4096)], axis=0)
        yc = np.zeros(N, dtype=np.float32)
        for ep, (s0, s1) in zip(tr_eps, slices):
            r = ep['r_true']; qm = ep['q_model']; pm = ep['p_model']; los = ep['has_los']; lc = ep['losing_cover']
            T = len(r); vp_ep = np.empty(T); vp_ep[:-1] = vp[s0:s1]; vp_ep[-1] = 0.0
            real = np.zeros(T); real[-1] = r[-1] if los[-1] else 0.0
            for tau in range(T - 2, -1, -1):
                ft = los[tau] and pm[tau] >= 0.18 and (qm[tau] >= max(0.24, vp_ep[tau]) or (lc[tau] and pm[tau] >= 0.22 and qm[tau] >= 0.25))
                real[tau] = r[tau] if ft else 0.985 * real[tau + 1]
            yc[s0:s1] = (0.985 * real[1:]).astype(np.float32)
        fit(yc, 1.2e-3 if rnd == 1 else 8e-4)
        print(f'  Round {rnd} causal FVI: mean_y={yc.mean():.3f}', flush=True)

    wm.eval()
    with torch.no_grad():
        val_vw = [wm(xf).cpu().numpy() for xf in val_feats]
    best_alpha, best_sc = 0.85, -1.0
    for alpha in (0.75, 0.85, 0.95, 1.05, 1.15):
        tot_ev = tot_vk = 0.0; fired = 0
        for ep, vw in zip(val_eps, val_vw):
            tf = rollout_ev_policy(ep, vw, alpha)
            if tf is not None:
                fired += 1; tot_ev += ep['r_true'][tf]
                if ep['vk_true'][tf]: tot_vk += ep['p_true'][tf]
        fr = fired / max(1, len(val_eps)); evw = tot_ev / max(1, len(val_eps))
        print(f'  val alpha={alpha:.2f}: fire={fr*100:.1f}% EV/win={evw:.3f}x VK/10={10*tot_vk/max(1,fired):.2f}', flush=True)
        if evw > best_sc and fr >= 0.85: best_sc = evw; best_alpha = alpha

    torch.save({'state_dict': wm.state_dict(), 'fire_alpha': best_alpha, 'in_dim': WAIT_IN_DIM_V4, 'h1': 96, 'h2': 48},
               args.model_v4.parent / 'wait_model_v4.pt')
    export_wait_v4(wm, best_alpha, args.out_bin)
    print(f'Done V4 BowWaitNet in {time.time()-t0:.1f}s!', flush=True)


if __name__ == '__main__':
    main()
