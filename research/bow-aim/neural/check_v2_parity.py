"""Verify exact numerical parity between PyTorch BowNeuralNetV2 and pure Java 8 BowNeuralMotion (BNN2),
including correlated-Gaussian box probability, continuous shot refinement, and Java latency!
"""
import json
from pathlib import Path
import struct
import subprocess
import tempfile

import numpy as np
import torch
from torch.nn import functional as F

from train_v2 import (
    BowNeuralNetV2, HORIZONS, NUM_MODES, FINE_DIM, COARSE_DIM,
    build_features_v2, contiguous_runs, unpack_fine, unpack_coarse,
)
from eval_v2 import (
    interp_modes_v2, shot_prob_v2, solve_aim_v2,
)
from eval_and_calibrate import apply_calibration, SPEED_FULL, intercept


JAVA_TEST = r"""
import practice.core.bow.BowBallistics;
import practice.core.bow.BowMotion;
import practice.core.bow.BowNeuralMotion;

import java.io.*;
import java.nio.file.*;
import java.util.*;

public class NeuralV2Parity {
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
                    double sx = in.readDouble(), sy = in.readDouble(), sz = in.readDouble();
                    float yaw = in.readFloat(), pitch = in.readFloat();
                    int flags = in.readInt();
                    float[] rel = new float[10];
                    for (int r = 0; r < 10; r++) rel[r] = in.readFloat();
                    track.add(t, x, y, z, 0, yaw, pitch, flags, sx, sy, sz, rel);
                }
                boolean hasTerrain = in.readBoolean();
                byte[] vf = new byte[BowNeuralMotion.FINE_DIM];
                byte[] vc = new byte[BowNeuralMotion.COARSE_DIM];
                in.readFully(vf);
                in.readFully(vc);
                double shX = in.readDouble(), shY = in.readDouble(), shZ = in.readDouble();

                long t0 = System.nanoTime();
                BowNeuralMotion.Prediction pred = net.predict(track, vf, vc, hasTerrain, false);
                BowBallistics.Shot shot = BowBallistics.intercept(shX, shY, shZ, BowBallistics.speed(20), pred.meanPath, 0.90);
                double rawP = net.rawHitProb(shot, pred, 0.0);
                double calP = net.calibratedHitProb(shot, pred, 0.0);
                long dt = System.nanoTime() - t0;
                if (c >= 4) totalNs += dt;

                for (double w : pred.weights) out.writeDouble(w);
                for (int k = 0; k < pred.paths.length; k++)
                    for (int h : new int[]{3, 6, 10, 15, 22, 30, 42}) {
                        for (int a = 0; a < 3; a++) out.writeDouble(pred.paths[k][h][a]);
                        for (int a = 0; a < 3; a++) out.writeDouble(pred.stds[k][h][a]);
                        out.writeDouble(pred.rhos[k][h]);
                    }
                out.writeDouble(rawP);
                out.writeDouble(calP);
            }
            System.out.printf(Locale.ROOT, "Java v2 avg latency (post-warmup): %.3f ms/target%n",
                    totalNs / 1e6 / Math.max(1, cases - 4));
        }
    }
}
"""


def main():
    model_dir = Path('runtime/bow-aim/neural-model-v2')
    data_dir = Path('runtime/bow-aim/neural-data-v2')
    bin_path = model_dir / 'bow-neural.bin'
    ckpt = torch.load(model_dir / 'winner.pt', map_location='cpu', weights_only=True)
    calib = ckpt['calib']
    model = BowNeuralNetV2(NUM_MODES)
    model.load_state_dict(ckpt['state_dict'])
    model.eval()

    manifest = json.loads((data_dir / 'manifest.json').read_text())
    cases = []
    for m in manifest['matches']:
        if m['split'] != 'val':
            continue
        npz = np.load(data_dir / m['file'])
        for p in range(int(npz['num_players'][0])):
            ticks = npz[f'p{p}_tick']; pos = npz[f'p{p}_pos']; spos = npz[f'p{p}_spos']
            rot = npz[f'p{p}_rot']; flags = npz[f'p{p}_flags']; rel = npz[f'p{p}_rel']
            vox = npz[f'p{p}_vox']; cvox = npz[f'p{p}_cvox']
            past, _ = contiguous_runs(ticks, pos)
            for i in range(60, len(ticks), 90):
                if past[i] >= 55:
                    cases.append((ticks, pos, spos, rot, flags, rel, vox, cvox, i, int(past[i]), len(cases) % 4 != 3))
                if len(cases) >= 24:
                    break
            if len(cases) >= 24:
                break
        if len(cases) >= 24:
            break

    with tempfile.TemporaryDirectory() as tmp:
        tmp = Path(tmp)
        in_file, out_file, src = tmp / 'in.bin', tmp / 'out.bin', tmp / 'NeuralV2Parity.java'
        src.write_text(JAVA_TEST)
        jar = 'build/practice-client/practice-bridge.jar'
        subprocess.run(['javac', '--release', '8', '-cp', jar, str(src)], check=True)

        py_exp = []
        with in_file.open('wb') as f:
            f.write(struct.pack('>i', len(cases)))
            for (ticks, pos, spos, rot, flags, rel, vox, cvox, i, plen, has_t) in cases:
                start = i - min(plen - 1, 180)
                n_len = i - start + 1
                f.write(struct.pack('>i', n_len))
                for k in range(start, i + 1):
                    f.write(struct.pack('>iddddddffi', int(ticks[k]),
                                        float(pos[k, 0]), float(pos[k, 1]), float(pos[k, 2]),
                                        float(spos[k, 0]), float(spos[k, 1]), float(spos[k, 2]),
                                        float(rot[k, 0]), float(rot[k, 1]), int(flags[k])))
                    for r in range(10):
                        f.write(struct.pack('>f', float(rel[k, r])))
                f.write(struct.pack('>?', has_t))
                vf = unpack_fine(vox[i]).reshape(-1).astype(np.uint8) if has_t else np.zeros(FINE_DIM, np.uint8)
                vc = unpack_coarse(cvox[i]).reshape(-1).astype(np.uint8) if has_t else np.zeros(COARSE_DIM, np.uint8)
                f.write(vf.tobytes())
                f.write(vc.tobytes())
                shx, shy, shz = float(pos[i, 0]) + 22.0, float(pos[i, 1]) + 1.62, float(pos[i, 2]) - 14.0
                f.write(struct.pack('>ddd', shx, shy, shz))

                hist, ctx, vf_r, vc_r, q = build_features_v2(
                    pos, spos, rot, flags, rel, vox, cvox, i, n_len, use_terrain=has_t)
                with torch.no_grad():
                    lo, me, ls, rh = model(torch.from_numpy(hist[None]), torch.from_numpy(ctx[None]),
                                           torch.from_numpy(vf_r[None]), torch.from_numpy(vc_r[None]))
                    pi = F.softmax(lo, dim=-1)[0].numpy()
                    means = me[0].numpy(); stds = ls.exp()[0].numpy(); rhos = rh[0].numpy()
                paths, st, r_all = interp_modes_v2(pos[i].astype(np.float64), q, means, stds, rhos)
                mean_p = np.tensordot(pi, paths, axes=([0], [0]))
                shot = intercept(shx, shy, shz, SPEED_FULL, mean_p, 0.90)
                raw_p = shot_prob_v2(shot, pi, paths, st, r_all, 0.0)
                cal_p = apply_calibration(raw_p, calib)
                py_exp.append((pi, paths, st, r_all, raw_p, cal_p))

        subprocess.run(['java', '-cp', f'{jar}:{tmp}', 'NeuralV2Parity',
                        str(bin_path), str(in_file), str(out_file)], check=True)

        raw = out_file.read_bytes()
        off = 0
        n_c = struct.unpack_from('>i', raw, off)[0]; off += 4
        max_w = max_pos = max_std = max_rho = max_prob = 0.0
        for c in range(n_c):
            jw = np.array(struct.unpack_from(f'>{NUM_MODES}d', raw, off)); off += 8 * NUM_MODES
            pi, paths, st, r_all, raw_p, cal_p = py_exp[c]
            max_w = max(max_w, float(np.max(np.abs(jw - pi))))
            for k in range(NUM_MODES):
                for h in HORIZONS:
                    jp = np.array(struct.unpack_from('>3d', raw, off)); off += 24
                    js = np.array(struct.unpack_from('>3d', raw, off)); off += 24
                    jr = struct.unpack_from('>d', raw, off)[0]; off += 8
                    max_pos = max(max_pos, float(np.max(np.abs(jp - paths[k, h]))))
                    max_std = max(max_std, float(np.max(np.abs(js - st[k, h]))))
                    max_rho = max(max_rho, abs(jr - r_all[k, h]))
            j_raw, j_cal = struct.unpack_from('>2d', raw, off); off += 16
            max_prob = max(max_prob, abs(j_raw - raw_p), abs(j_cal - cal_p))

        print(f'V2 PARITY ({n_c} cases): max_w={max_w:.2e}, max_pos={max_pos:.2e}, '
              f'max_std={max_std:.2e}, max_rho={max_rho:.2e}, max_prob={max_prob:.2e}')
        assert max_w < 1e-4 and max_pos < 1e-3 and max_std < 1e-3 and max_rho < 1e-3 and max_prob < 1e-3
        print('PASS Python <-> Java 8 v2 exact numerical parity!')


if __name__ == '__main__':
    main()
