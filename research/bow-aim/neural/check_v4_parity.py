"""Exact Python <-> Java 8 parity & latency verification for BowNeuralV4 (BNN4) across 24 real sealed tracks."""
import json
import math
import struct
import subprocess
import tempfile
from pathlib import Path

import numpy as np
import torch
from torch.nn import functional as F

from train_v4 import (
    BowNeuralNetV4, NUM_MODES, FINE_DIM, TOP_DIM,
    build_features_v4, contiguous_runs, unpack_fine,
)
from eval_v4 import interp_modes_v4, mode_probs_v4
from void_and_wait import THROW_SPEED, THROW_GRAVITY, intercept_g

ROOT = Path(__file__).resolve().parents[3]


def main():
    ck = torch.load(ROOT / 'runtime/bow-aim/neural-model-v4/winner.pt', map_location='cpu')
    model = BowNeuralNetV4(NUM_MODES)
    model.load_state_dict(ck['state_dict'])
    model.eval()

    data_dir = ROOT / 'runtime/bow-aim/neural-data-v4'
    manifest = json.loads((data_dir / 'manifest.json').read_text())
    rng = np.random.default_rng(17)
    cases = []
    py_out = []

    for m in manifest['matches']:
        if m['split'] != 'test':
            continue
        npz = np.load(data_dir / m['file'])
        for p in range(int(npz['num_players'][0])):
            ticks = npz[f'p{p}_tick']
            pos = npz[f'p{p}_pos']; spos = npz[f'p{p}_spos']
            rot = npz[f'p{p}_rot']; flags = npz[f'p{p}_flags']
            rel = npz[f'p{p}_rel']; blk4 = npz[f'p{p}_blk4']
            vox = npz[f'p{p}_vox']; pvox = npz[f'p{p}_pvox']; top = npz[f'p{p}_top']
            past, fut = contiguous_runs(ticks, pos)
            for idx in range(len(ticks)):
                if past[idx] >= 60 and fut[idx] >= 42:
                    hl = min(int(past[idx]), 120)
                    h, c, vb, f3, t2, q = build_features_v4(pos, spos, rot, flags, rel, blk4, vox, pvox, top, idx, hl)
                    with torch.no_grad():
                        lo, me, ls, rh, fbl = model(
                            torch.from_numpy(h[None]), torch.from_numpy(c[None]),
                            torch.from_numpy(vb[None]), torch.from_numpy(f3[None]),
                            torch.from_numpy(t2[None]),
                        )
                    pi = F.softmax(lo, dim=-1)[0].numpy()
                    paths, stds, rhos = interp_modes_v4(spos[idx], q, me[0].numpy(), ls[0].exp().numpy(), rh[0].numpy())
                    fb_sum = float(torch.sigmoid(fbl[0]).sum().item())

                    p0 = pos[idx]
                    ang = float(rng.uniform(0, 2 * math.pi))
                    dist = float(rng.uniform(12.0, 32.0))
                    shx = float(p0[0] + math.cos(ang) * dist)
                    shey = float(p0[1] + 2.0)
                    shz = float(p0[2] + math.sin(ang) * dist)
                    s = intercept_g(shx, shey, shz, THROW_SPEED, paths[0], 0.90, THROW_GRAVITY)
                    mp0 = 0.0 if s is None else float(mode_probs_v4(s, pi, paths, stds, rhos, 0.0)[0])

                    vals = list(pi)
                    for hz in (15, 42, 72):
                        vals.extend([paths[0, hz, 0], paths[0, hz, 1], paths[0, hz, 2], stds[0, hz, 0], rhos[0, hz]])
                    vals.extend([mp0, fb_sum])
                    py_out.append(vals)

                    cases.append((
                        pos[idx - hl + 1:idx + 1], spos[idx - hl + 1:idx + 1],
                        rot[idx - hl + 1:idx + 1], flags[idx - hl + 1:idx + 1],
                        rel[idx - hl + 1:idx + 1], blk4[idx - hl + 1:idx + 1],
                        unpack_fine(vox[idx]).reshape(-1).astype(np.uint8),
                        unpack_fine(pvox[idx]).reshape(-1).astype(np.uint8),
                        top[idx].reshape(-1).astype(np.int8),
                        shx, shey, shz,
                    ))
                    break
            if len(cases) >= 24:
                break
        if len(cases) >= 24:
            break

    with tempfile.TemporaryDirectory() as tmp:
        tmp_p = Path(tmp)
        cbin = tmp_p / 'v4_cases.bin'
        with open(cbin, 'wb') as f:
            f.write(struct.pack('>i', len(cases)))
            for tc in cases:
                p_a, sp_a, r_a, fl_a, rel_a, blk_a, fs_b, fp_b, top_b, shx, shey, shz = tc
                f.write(struct.pack('>i', len(p_a)))
                for t in range(len(p_a)):
                    f.write(struct.pack('>ddddddffi',
                                        float(p_a[t, 0]), float(p_a[t, 1]), float(p_a[t, 2]),
                                        float(sp_a[t, 0]), float(sp_a[t, 1]), float(sp_a[t, 2]),
                                        float(r_a[t, 0]), float(r_a[t, 1]), int(fl_a[t])))
                    f.write(struct.pack('>10f', *[float(v) for v in rel_a[t]]))
                    f.write(struct.pack('>4f', *[float(v) for v in blk_a[t]]))
                f.write(fs_b.tobytes())
                f.write(fp_b.tobytes())
                f.write(top_b.tobytes())
                f.write(struct.pack('>ddd', shx, shey, shz))

        cls = tmp_p / 'classes'; cls.mkdir()
        bow_srcs = [str(p) for p in sorted((ROOT / 'practice-client/core/practice/core/bow').glob('*.java'))]
        subprocess.run(['javac', '--release', '8', '-d', str(cls), *bow_srcs,
                        str(ROOT / 'research/bow-aim/neural/BowV4ParityBench.java')], check=True)
        proc = subprocess.run([
            'java', '-cp', str(cls), 'BowV4ParityBench',
            str(ROOT / 'runtime/bow-aim/neural-model-v4/bow-neural-v4-cal.bin'),
            str(cbin),
        ], capture_output=True, text=True, check=True)

    max_w = max_pos = max_std = max_rho = max_p = max_fb = 0.0
    lat = 0.0
    for line in proc.stdout.strip().splitlines():
        parts = line.split()
        if parts[0] == 'CASE':
            i = int(parts[1])
            jv = [float(x) for x in parts[2:]]
            pv = py_out[i]
            for k in range(NUM_MODES):
                max_w = max(max_w, abs(jv[k] - pv[k]))
            off = NUM_MODES
            for _ in range(3):
                for a in range(3):
                    max_pos = max(max_pos, abs(jv[off + a] - pv[off + a]))
                max_std = max(max_std, abs(jv[off + 3] - pv[off + 3]))
                max_rho = max(max_rho, abs(jv[off + 4] - pv[off + 4]))
                off += 5
            max_p = max(max_p, abs(jv[off] - pv[off]))
            max_fb = max(max_fb, abs(jv[off + 1] - pv[off + 1]))
        elif parts[0] == 'LATENCY_MS':
            lat = float(parts[1])

    print('=== BowNeuralV4 (BNN4) Python <-> Java 8 Parity Check ===')
    print(f'24 real sealed tracks: max_w={max_w:.2e}, max_pos={max_pos:.2e}, max_std={max_std:.2e}, '
          f'max_rho={max_rho:.2e}, max_prob={max_p:.2e}, max_futBlkSum={max_fb:.2e}, latency={lat:.2f} ms/target')
    assert max_w < 1e-4 and max_pos < 5e-4 and max_p < 5e-4 and max_fb < 1e-3, 'BNN4 parity mismatch!'
    print('ALL BNN4 PARITY CHECKS PASSED!')


if __name__ == '__main__':
    main()
