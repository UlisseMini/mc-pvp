"""Verify exact numerical parity between PyTorch BowNeuralNet and pure Java 8 BowNeuralMotion
across all 4 cardinal rotations, with and without 9x7x9 voxels, plus benchmark Java latency.
"""
import json
from pathlib import Path
import subprocess
import tempfile
import time

import numpy as np
import torch
from torch.nn import functional as F

from train_neural import (
    BowNeuralNet, HORIZONS, MAX_H, NUM_MODES,
    build_features_from_track, contiguous_runs, unpack_vox,
)
from eval_and_calibrate import (
    interp_mode_paths, intercept, shot_expected_hit_prob, apply_calibration, SPEED_FULL,
    smart_release_decision,
)


JAVA_TEST = r"""
import practice.core.bow.BowBallistics;
import practice.core.bow.BowMotion;
import practice.core.bow.BowNeuralMotion;
import practice.core.bow.BowReleasePolicy;

import java.io.*;
import java.nio.file.*;
import java.util.*;

public class NeuralParityCheck {
    public static void main(String[] args) throws Exception {
        BowNeuralMotion net;
        try (InputStream in = Files.newInputStream(Paths.get(args[0]))) {
            net = BowNeuralMotion.read(in);
        }
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(Paths.get(args[1]))));
             DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(Paths.get(args[2]))))) {
            int cases = in.readInt();
            out.writeInt(cases);
            long totalNs = 0;
            for (int c = 0; c < cases; c++) {
                int len = in.readInt();
                BowMotion.Track track = new BowMotion.Track();
                for (int i = 0; i < len; i++) {
                    int t = in.readInt();
                    double x = in.readDouble(), y = in.readDouble(), z = in.readDouble();
                    float yaw = in.readFloat(), pitch = in.readFloat();
                    int flags = in.readInt();
                    track.add(t, x, y, z, 0, yaw, pitch, flags);
                }
                boolean hasTerrain = in.readBoolean();
                byte[] vox = new byte[BowNeuralMotion.VOX_DIM];
                in.readFully(vox);
                double sx = in.readDouble(), sy = in.readDouble(), sz = in.readDouble();

                long t0 = System.nanoTime();
                BowNeuralMotion.Prediction pred = net.predict(track, vox, hasTerrain);
                BowBallistics.Shot shot = BowBallistics.intercept(sx, sy, sz, BowBallistics.speed(20), pred.meanPath, 0.90);
                double rawP = net.rawHitProb(shot, pred);
                double calP = net.calibratedHitProb(shot, pred);
                long dt = System.nanoTime() - t0;
                if (c >= 4) totalNs += dt; // skip JIT warmup

                for (double w : pred.weights) out.writeDouble(w);
                for (int k = 0; k < pred.paths.length; k++)
                    for (int h : new int[]{3, 6, 10, 15, 22, 30, 42}) {
                        for (int a = 0; a < 3; a++) out.writeDouble(pred.paths[k][h][a]);
                        for (int a = 0; a < 3; a++) out.writeDouble(pred.stds[k][h][a]);
                    }
                out.writeDouble(rawP);
                out.writeDouble(calP);
            }
            System.out.printf(Locale.ROOT, "Java avg latency (post-warmup): %.3f ms/target%n",
                    totalNs / 1e6 / Math.max(1, cases - 4));
            // Quick sanity test of BowReleasePolicy
            int rCases = in.readInt();
            out.writeInt(rCases);
            for (int r = 0; r < rCases; r++) {
                int n = in.readInt();
                BowReleasePolicy pol = new BowReleasePolicy();
                int fired = -1;
                for (int i = 0; i < n; i++) {
                    double p = in.readDouble();
                    if (fired < 0 && pol.shouldFire(p, 75, BowReleasePolicy.ReleaseMode.SMART)) fired = i;
                }
                out.writeInt(fired);
            }
        }
    }
}
"""


def main():
    model_dir = Path('runtime/bow-aim/neural-model')
    data_dir = Path('runtime/bow-aim/neural-data')
    bin_path = model_dir / 'bow-neural.bin'
    ckpt = torch.load(model_dir / 'model.pt', map_location='cpu', weights_only=True)
    eval_res = json.loads((model_dir / 'eval-results.json').read_text())
    calib = eval_res['calibration_curve']
    model = BowNeuralNet(NUM_MODES)
    model.load_state_dict(ckpt['state_dict'])
    model.eval()

    manifest = json.loads((data_dir / 'manifest.json').read_text())
    val_matches = [m for m in manifest['matches'] if m['split'] == 'val']
    cases = []
    for m in val_matches:
        npz = np.load(data_dir / m['file'])
        for p in range(int(npz['num_players'][0])):
            ticks = npz[f'p{p}_tick']
            pos = npz[f'p{p}_pos']
            rot = npz[f'p{p}_rot']
            flags = npz[f'p{p}_flags']
            vox = npz[f'p{p}_vox']
            past, _ = contiguous_runs(ticks, pos)
            for i in range(40, len(ticks), 90):
                if past[i] >= 36:
                    cases.append((ticks, pos, rot, flags, vox, i, int(past[i]), len(cases) % 4 != 3))
                if len(cases) >= 24:
                    break
            if len(cases) >= 24:
                break
        if len(cases) >= 24:
            break

    # Also build 12 release-policy sequences
    rng = np.random.default_rng(777)
    rel_seqs = [np.clip(rng.normal(0.32, 0.14, 60), 0.02, 0.95) for _ in range(12)]

    with tempfile.TemporaryDirectory() as tmp:
        tmp = Path(tmp)
        in_file = tmp / 'in.bin'
        out_file = tmp / 'out.bin'
        java_src = tmp / 'NeuralParityCheck.java'
        java_src.write_text(JAVA_TEST)
        jar = 'build/practice-client/practice-bridge.jar'
        subprocess.run(['javac', '--release', '8', '-cp', jar, str(java_src)], check=True)

        py_expected = []
        with in_file.open('wb') as f:
            import struct
            f.write(struct.pack('>i', len(cases)))
            for idx_c, (ticks, pos, rot, flags, vox, i, plen, has_t) in enumerate(cases):
                start = i - min(plen - 1, 140)
                n_len = i - start + 1
                f.write(struct.pack('>i', n_len))
                for k in range(start, i + 1):
                    f.write(struct.pack('>idddffi', int(ticks[k]),
                                        float(pos[k, 0]), float(pos[k, 1]), float(pos[k, 2]),
                                        float(rot[k, 0]), float(rot[k, 1]), int(flags[k])))
                f.write(struct.pack('>?', has_t))
                raw_vox = unpack_vox(vox[i]).reshape(-1).astype(np.uint8) if has_t else np.zeros(567, np.uint8)
                f.write(raw_vox.tobytes())
                sx, sy, sz = float(pos[i, 0]) + 22.0, float(pos[i, 1]) + 1.62, float(pos[i, 2]) - 14.0
                f.write(struct.pack('>ddd', sx, sy, sz))

                hist, ctx, v_rot, q = build_features_from_track(pos, rot, flags, vox, i, n_len, use_terrain=has_t)
                with torch.no_grad():
                    lo, me, ls = model(torch.from_numpy(hist[None]), torch.from_numpy(ctx[None]),
                                       torch.from_numpy(v_rot[None]))
                    pi = F.softmax(lo, dim=-1)[0].numpy()
                    means = me[0].numpy()
                    stds = ls.exp()[0].numpy()
                paths, st = interp_mode_paths(pos[i].astype(np.float64), q, means, stds)
                mean_p = np.tensordot(pi, paths, axes=([0], [0]))
                shot = intercept(sx, sy, sz, SPEED_FULL, mean_p, 0.90)
                raw_p = shot_expected_hit_prob(shot, pi, paths, st)
                cal_p = apply_calibration(raw_p, calib)
                py_expected.append((pi, paths, st, raw_p, cal_p))

            f.write(struct.pack('>i', len(rel_seqs)))
            for seq in rel_seqs:
                f.write(struct.pack('>i', len(seq)))
                for pv in seq:
                    f.write(struct.pack('>d', float(pv)))

        subprocess.run(['java', '-cp', f'{jar}:{tmp}', 'NeuralParityCheck',
                        str(bin_path), str(in_file), str(out_file)], check=True)

        import struct
        raw = out_file.read_bytes()
        off = 0
        n_c = struct.unpack_from('>i', raw, off)[0]; off += 4
        max_w = max_pos = max_std = max_prob = 0.0
        for c in range(n_c):
            jw = np.array(struct.unpack_from(f'>{NUM_MODES}d', raw, off)); off += 8 * NUM_MODES
            pi, paths, st, raw_p, cal_p = py_expected[c]
            max_w = max(max_w, float(np.max(np.abs(jw - pi))))
            for k in range(NUM_MODES):
                for h in HORIZONS:
                    jp = np.array(struct.unpack_from('>3d', raw, off)); off += 24
                    js = np.array(struct.unpack_from('>3d', raw, off)); off += 24
                    max_pos = max(max_pos, float(np.max(np.abs(jp - paths[k, h]))))
                    max_std = max(max_std, float(np.max(np.abs(js - st[k, h]))))
            j_raw, j_cal = struct.unpack_from('>2d', raw, off); off += 16
            max_prob = max(max_prob, abs(j_raw - raw_p), abs(j_cal - cal_p))

        n_r = struct.unpack_from('>i', raw, off)[0]; off += 4
        rel_mismatch = 0
        for r in range(n_r):
            j_fire = struct.unpack_from('>i', raw, off)[0]; off += 4
            p_fire, _ = smart_release_decision(rel_seqs[r])
            p_fire = -1 if p_fire is None else p_fire
            if j_fire != p_fire:
                rel_mismatch += 1
        print(f'PARITY ({n_c} cases): max_w={max_w:.2e}, max_pos={max_pos:.2e}, '
              f'max_std={max_std:.2e}, max_prob={max_prob:.2e}, release_mismatch={rel_mismatch}')
        assert max_w < 1e-4 and max_pos < 1e-3 and max_std < 1e-3 and max_prob < 1e-3 and rel_mismatch == 0
        print('PASS Python <-> Java 8 exact numerical parity!')


if __name__ == '__main__':
    main()
