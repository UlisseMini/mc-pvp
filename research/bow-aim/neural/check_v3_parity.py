"""Exact Python <-> Java 8 parity check for BowAim v3:
- VoidKbValue.simulate & VoidKbValue.scoreMode across 40 bridge/edge/wall/island cases
- BowBallistics.interceptThrowable + BowNeuralMotion.modeHitProbAt (Volley Coverage)
- BowWaitModel.predictWaitEv across 24 real sealed Hypixel tracks
"""
import json
import math
import struct
import subprocess
import tempfile
from pathlib import Path

import numpy as np
import torch
from torch.nn import functional as F

from train_v2 import (
    BowNeuralNetV2, NUM_MODES, FINE_DIM, COARSE_DIM,
    build_features_v2, contiguous_runs, unpack_fine, unpack_coarse,
)
from eval_v2 import interp_modes_v2
from void_and_wait import (
    BowWaitNet, WAIT_IN_DIM, THROW_SPEED, THROW_GRAVITY,
    simulate_kb_void, score_mode_void, intercept_g, mode_hit_prob_at_g,
    forward_with_trunk, build_tactical_vec,
)

ROOT = Path(__file__).resolve().parents[3]


def main():
    device = torch.device('cpu')
    v2_ckpt = torch.load(ROOT / 'runtime/bow-aim/neural-model-v2/winner.pt', map_location='cpu')
    v2 = BowNeuralNetV2(NUM_MODES)
    v2.load_state_dict(v2_ckpt['state_dict'])
    v2.eval()

    w_ckpt = torch.load(ROOT / 'runtime/bow-aim/neural-model-v3/wait_model.pt', map_location='cpu')
    wait_net = BowWaitNet(WAIT_IN_DIM, w_ckpt['h1'], w_ckpt['h2'])
    wait_net.load_state_dict(w_ckpt['state_dict'])
    wait_net.eval()

    # Build a synthetic SkyWars island + 1-wide bridge + backstop wall + lower ledge
    solid = set()
    # Main island: x in [-4..4], z in [-4..4], y = 63
    for x in range(-4, 5):
        for z in range(-4, 5):
            solid.add((x, 63, z))
    # 1-wide bridge: x = 0, z in [5..16], y = 63
    for z in range(5, 17):
        solid.add((0, 63, z))
    # Backstop wall on island: x = -4, z in [-2..2], y = 64..66
    for z in range(-2, 3):
        for y in range(64, 67):
            solid.add((-4, y, z))
    # Lower ledge under x in [5..9], z in [-2..2], y = 57 (drop = 7 blocks, not void!)
    for x in range(5, 10):
        for z in range(-2, 3):
            solid.add((x, 57, z))

    rng = np.random.default_rng(7)
    void_cases = []
    # Edge, bridge, wall-blocked, center-island, lower-ledge, Punch 0/1/2, bow & throwable
    presets = [
        (0.5, 64.0, 10.5, 0.0, 0.0, 0.22, 0.4, 0.4, 2.5, 0.0, 0, 0),   # bridge side KB -> void
        (0.5, 64.0, 10.5, 0.0, 0.0, 0.22, 0.4, 0.4, 2.5, 0.0, 1, 0),   # snowball on bridge -> void (10x)
        (0.5, 64.0, 10.5, 0.0, 0.0, 0.22, 0.4, 0.4, 0.0, 2.5, 0, 0),   # bridge along-bridge -> safe
        (-3.2, 64.0, 0.5, 0.0, 0.0, 0.0, 0.3, 0.3, -2.5, 0.0, 0, 1),   # into backstop wall -> safe!
        (4.3, 64.0, 0.5, 0.1, 0.0, 0.0, 0.3, 0.3, 2.5, 0.0, 0, 0),     # onto lower ledge (7b drop) -> fall bonus, not void
        (4.3, 64.0, 0.5, 0.1, 0.0, 0.0, 0.3, 0.3, 2.5, 0.0, 0, 2),     # Punch II clears lower ledge -> void!
        (0.5, 64.0, 0.5, 0.0, 0.0, 0.0, 0.2, 0.2, 2.5, 0.0, 0, 0),     # center of island -> safe
    ]
    void_cases.extend(presets)
    for _ in range(33):
        px = float(rng.uniform(-4.0, 5.0))
        pz = float(rng.uniform(-4.0, 16.0))
        py = 64.0 + float(rng.choice([0.0, 0.0, 0.5, 1.2]))
        tvx, tvy, tvz = float(rng.uniform(-0.3, 0.3)), float(rng.uniform(-0.3, 0.3)), float(rng.uniform(-0.3, 0.3))
        sx, sz = float(rng.uniform(0.1, 1.2)), float(rng.uniform(0.1, 1.2))
        ivx, ivz = float(rng.uniform(-2.8, 2.8)), float(rng.uniform(-2.8, 2.8))
        is_t = int(rng.integers(0, 2))
        punch = 0 if is_t else int(rng.integers(0, 3))
        void_cases.append((px, py, pz, tvx, tvy, tvz, sx, sz, ivx, ivz, is_t, punch))

    py_void = []
    for c in void_cases:
        px, py, pz, tvx, tvy, tvz, sx, sz, ivx, ivz, is_t, punch = c
        vk, drop = simulate_kb_void(px, py, pz, tvx, tvy, tvz, ivx, ivz, punch, solid)
        vp, _, val = score_mode_void(px, py, pz, tvx, tvy, tvz, sx, sz, ivx, ivz, bool(is_t), punch, solid)
        py_void.append((1 if vk else 0, drop, vp, val))

    # Load 24 real sealed Hypixel tracks
    data_dir = ROOT / 'runtime/bow-aim/neural-data-v2'
    manifest = json.loads((data_dir / 'manifest.json').read_text())
    track_cases = []
    py_track = []
    for m in manifest['matches']:
        if m['split'] != 'test':
            continue
        npz = np.load(data_dir / m['file'])
        for p in range(int(npz['num_players'][0])):
            ticks = npz[f'p{p}_tick']
            pos = npz[f'p{p}_pos']
            spos = npz[f'p{p}_spos']
            rot = npz[f'p{p}_rot']
            flags = npz[f'p{p}_flags']
            rel = npz[f'p{p}_rel']
            vox = npz[f'p{p}_vox']
            cvox = npz[f'p{p}_cvox']
            past, fut = contiguous_runs(ticks, pos)
            for idx in range(len(ticks)):
                if past[idx] >= 60 and fut[idx] >= 42:
                    hist_len = min(int(past[idx]), 120)
                    h, c, vf, vc, q = build_features_v2(pos, spos, rot, flags, rel, vox, cvox, idx, hist_len)
                    with torch.no_grad():
                        tr_t, lo, me, ls, rh = forward_with_trunk(
                            v2,
                            torch.from_numpy(h[None]),
                            torch.from_numpy(c[None]),
                            torch.from_numpy(vf[None]),
                            torch.from_numpy(vc[None]),
                        )
                    pi = F.softmax(lo, dim=-1)[0].numpy()
                    paths, stds, rhos = interp_modes_v2(pos[idx], q, me[0].numpy(), ls[0].exp().numpy(), rh[0].numpy())

                    p0 = pos[idx]
                    ang = float(rng.uniform(0, 2 * math.pi))
                    dist = float(rng.uniform(11.0, 24.0))
                    shx = float(p0[0] + math.cos(ang) * dist)
                    shey = float(p0[1] + 2.0 + rng.uniform(-2.0, 3.0))
                    shz = float(p0[2] + math.sin(ang) * dist)
                    q_sh = float(rng.uniform(0.2, 5.5))
                    p_hit = float(rng.uniform(0.15, 0.90))
                    p_void = float(rng.uniform(0.0, p_hit))
                    is_t = int(rng.integers(0, 2))
                    has_los = 1

                    tshot = intercept_g(shx, shey, shz, THROW_SPEED, paths[0], 0.90, THROW_GRAVITY)
                    t_time = -1.0 if tshot is None else tshot[2]
                    t_yaw = 0.0 if tshot is None else math.degrees(tshot[0])
                    t_pitch = 0.0 if tshot is None else math.degrees(tshot[1])
                    cov0 = 0.0 if tshot is None else mode_hit_prob_at_g(
                        tshot, paths, stds, rhos, 0, max(1.0, tshot[2] - 5.0)
                    )
                    tac = build_tactical_vec(shx, shey, shz, float(p0[0]), float(p0[1]), float(p0[2]),
                                             q, q_sh, p_hit, p_void, bool(is_t), bool(has_los))
                    with torch.no_grad():
                        x_w = torch.from_numpy(np.concatenate([tr_t[0].numpy(), tac])[None])
                        vw = float(wait_net(x_w)[0].item())

                    fine_raw = unpack_fine(vox[idx]).reshape(-1).astype(np.uint8)
                    coarse_raw = unpack_coarse(cvox[idx]).reshape(-1).astype(np.uint8)
                    track_cases.append((
                        pos[idx - hist_len + 1:idx + 1],
                        spos[idx - hist_len + 1:idx + 1],
                        rot[idx - hist_len + 1:idx + 1],
                        flags[idx - hist_len + 1:idx + 1],
                        rel[idx - hist_len + 1:idx + 1],
                        fine_raw, coarse_raw,
                        shx, shey, shz, q_sh, p_hit, p_void, is_t, has_los,
                    ))
                    py_track.append((t_time, t_yaw, t_pitch, cov0, vw))
                    break
            if len(track_cases) >= 24:
                break
        if len(track_cases) >= 24:
            break

    with tempfile.TemporaryDirectory() as tmp:
        tmp_p = Path(tmp)
        cases_bin = tmp_p / 'v3_cases.bin'
        with open(cases_bin, 'wb') as f:
            f.write(struct.pack('>i', len(solid)))
            for (x, y, z) in sorted(solid):
                f.write(struct.pack('>iii', x, y, z))
            f.write(struct.pack('>i', len(void_cases)))
            for c in void_cases:
                f.write(struct.pack('>ddddddddddii', *c))
            f.write(struct.pack('>i', len(track_cases)))
            for tc in track_cases:
                p_arr, sp_arr, r_arr, fl_arr, rel_arr, fine_b, coarse_b, shx, shey, shz, q_sh, p_hit, p_void, is_t, has_los = tc
                f.write(struct.pack('>i', len(p_arr)))
                for t in range(len(p_arr)):
                    f.write(struct.pack('>ddddddff i',
                                        float(p_arr[t, 0]), float(p_arr[t, 1]), float(p_arr[t, 2]),
                                        float(sp_arr[t, 0]), float(sp_arr[t, 1]), float(sp_arr[t, 2]),
                                        float(r_arr[t, 0]), float(r_arr[t, 1]), int(fl_arr[t])))
                    f.write(struct.pack('>10f', *[float(v) for v in rel_arr[t]]))
                f.write(fine_b.tobytes())
                f.write(coarse_b.tobytes())
                f.write(struct.pack('>ddddddii', shx, shey, shz, q_sh, p_hit, p_void, is_t, has_los))

        # Compile core/bow + BowV3ParityBench.java with Java 8
        core_out = tmp_p / 'classes'
        core_out.mkdir()
        bow_srcs = [str(p) for p in sorted((ROOT / 'practice-client/core/practice/core/bow').glob('*.java'))]
        bench_src = str(ROOT / 'research/bow-aim/neural/BowV3ParityBench.java')
        subprocess.run(['javac', '--release', '8', '-d', str(core_out), *bow_srcs, bench_src], check=True)

        proc = subprocess.run([
            'java', '-cp', str(core_out), 'BowV3ParityBench',
            str(ROOT / 'practice-client/assets/practice/bow-neural.bin'),
            str(ROOT / 'practice-client/assets/practice/bow-wait.bin'),
            str(cases_bin),
        ], capture_output=True, text=True, check=True)

    max_drop_err = max_vp_err = max_val_err = 0.0
    vk_mismatches = 0
    max_time_err = max_yaw_err = max_pitch_err = max_cov_err = max_vw_err = 0.0
    void_us = wait_us = 0.0
    for line in proc.stdout.strip().splitlines():
        parts = line.split()
        if parts[0] == 'VOID':
            i = int(parts[1])
            j_vk, j_drop, j_vp, j_val = int(parts[2]), float(parts[3]), float(parts[4]), float(parts[5])
            p_vk, p_drop, p_vp, p_val = py_void[i]
            if j_vk != p_vk:
                vk_mismatches += 1
            max_drop_err = max(max_drop_err, abs(j_drop - p_drop))
            max_vp_err = max(max_vp_err, abs(j_vp - p_vp))
            max_val_err = max(max_val_err, abs(j_val - p_val))
        elif parts[0] == 'VOID_US':
            void_us = float(parts[1])
        elif parts[0] == 'TRACK':
            i = int(parts[1])
            jt, jy, jp, jc, jw = map(float, parts[2:7])
            pt, py_, pp, pc, pw = py_track[i]
            max_time_err = max(max_time_err, abs(jt - pt))
            max_yaw_err = max(max_yaw_err, abs(jy - py_))
            max_pitch_err = max(max_pitch_err, abs(jp - pp))
            max_cov_err = max(max_cov_err, abs(jc - pc))
            max_vw_err = max(max_vw_err, abs(jw - pw))
        elif parts[0] == 'WAIT_US':
            wait_us = float(parts[1])

    print('=== BowAim v3 Python <-> Java 8 Parity Check ===')
    print(f'VoidKbValue ({len(void_cases)} cases): vk_mismatch={vk_mismatches}, '
          f'max_drop_err={max_drop_err:.2e}, max_vp_err={max_vp_err:.2e}, max_val_err={max_val_err:.2e}, '
          f'latency={void_us:.2f} us/case')
    print(f'Throwable+Volley+V_wait ({len(track_cases)} real tracks): '
          f'max_t={max_time_err:.2e}, max_yaw={max_yaw_err:.2e}, max_pitch={max_pitch_err:.2e}, '
          f'max_cov={max_cov_err:.2e}, max_V_wait={max_vw_err:.2e}, V_wait latency={wait_us:.2f} us')
    assert vk_mismatches == 0, f'Void kill mismatch: {vk_mismatches}'
    assert max_val_err < 1e-4, f'Void value mismatch: {max_val_err}'
    assert max_time_err < 1e-4 and max_cov_err < 1e-4, 'Throwable/Volley mismatch!'
    assert max_vw_err < 5e-4, f'V_wait mismatch: {max_vw_err}'
    print('ALL v3 PARITY CHECKS PASSED!')


if __name__ == '__main__':
    main()
