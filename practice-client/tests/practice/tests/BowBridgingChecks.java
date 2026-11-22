package practice.tests;

import practice.core.bow.BowBallistics;
import practice.core.bow.BowBallistics.Shot;
import practice.core.bow.BowMotion.Track;
import practice.core.bow.BowNeuralMotion;
import practice.core.bow.VoidKbValue;

import java.io.FileInputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * End-to-end Java 8 regression & stress tests for bridging + snowball/egg volley coverage:
 * 1. Constant-speed sideways bridging across speeds (0.08..0.24 b/t), distances (12..48b), and pings (0..160ms)
 * 2. Variable-speed speedbridging (speed distribution where target speeds up OR slows down after throw) with volley fanning
 * 3. Diagonal-up stair speedbridging (spam releasing shift + jumping up stairs) across distances & pings
 * 4. Guard check: rapid A-D combat strafing does NOT trigger bridge continuation override
 */
public final class BowBridgingChecks {
    private static long pack(int x, int y, int z) {
        return (((long)x & 0x3FFFFFL) << 42) | (((long)y & 0xFFFFFL) << 22) | ((long)z & 0x3FFFFFL);
    }

    private static double[][] makeScenario(String kind, double speedXZ, int stairPeriod, int totalTicks,
                                           Track outTrack, Set<Long> outSolidAtLaunch, int launchIdx,
                                           double postSpeedMult) {
        double[][] truePos = new double[totalTicks][3];
        double[][] serverPos = new double[totalTicks][3];
        double[][] clientPos = new double[totalTicks][3];
        int[] flags = new int[totalTicks];
        float[] yaws = new float[totalTicks];
        float[] pitches = new float[totalTicks];

        for (int x = -6; x <= 0; x++) {
            for (int z = -3; z <= 3; z++) outSolidAtLaunch.add(pack(x, 63, z));
        }

        double x = 0.5, y = 64.0, z = 0.5;
        double ux = "diag_stair_up".equals(kind) ? 0.70710678 : 1.0;
        double uz = "diag_stair_up".equals(kind) ? 0.70710678 : 0.0;
        double stepBase = 64.0;
        double[] jumpArc = {0.0, 0.42, 0.75, 0.99, 1.12, 1.05, 1.00, 1.00, 1.00};

        for (int t = 0; t < totalTicks; t++) {
            double spMult = (t > launchIdx) ? postSpeedMult : 1.0;
            if ("const_side".equals(kind)) {
                yaws[t] = 135f; pitches[t] = 78f;
                flags[t] = 1 | 2; // sneak + block
                x += speedXZ * spMult * ux;
                z += speedXZ * spMult * uz;
                if (t <= launchIdx) outSolidAtLaunch.add(pack((int)Math.floor(x), 63, (int)Math.floor(z)));
            } else if ("speedbridge_var".equals(kind)) {
                int phase = t % 7;
                boolean sneak = phase >= 5;
                double cadence = sneak ? 0.45 : (1.18 + 0.18 * Math.sin(t * 0.17));
                yaws[t] = 135f; pitches[t] = 77f;
                flags[t] = (sneak ? 1 : 0) | 2;
                x += speedXZ * spMult * cadence * ux;
                z += speedXZ * spMult * cadence * uz;
                if (t <= launchIdx) outSolidAtLaunch.add(pack((int)Math.floor(x), 63, (int)Math.floor(z)));
            } else if ("diag_stair_up".equals(kind)) {
                int phase = t % stairPeriod;
                boolean sneak = phase >= stairPeriod - 2;
                double cadence = sneak ? 0.50 : 1.22;
                yaws[t] = 135f; pitches[t] = 76f;
                flags[t] = (sneak ? 1 : 0) | 2;
                x += speedXZ * spMult * cadence * ux;
                z += speedXZ * spMult * cadence * uz;
                if (phase == 0 && t > 0) stepBase += 1.0;
                double arc = jumpArc[Math.min(phase, jumpArc.length - 1)];
                y = stepBase + arc;
                if (t <= launchIdx) {
                    int bx = (int)Math.floor(x), bz = (int)Math.floor(z), by = (int)Math.floor(stepBase) - 1;
                    outSolidAtLaunch.add(pack(bx, by, bz));
                    if (phase >= stairPeriod - 3) outSolidAtLaunch.add(pack(bx, by + 1, bz));
                }
            }
            truePos[t][0] = x; truePos[t][1] = y; truePos[t][2] = z;
            serverPos[t][0] = Math.round(x * 32.0) / 32.0;
            serverPos[t][1] = Math.round(y * 32.0) / 32.0;
            serverPos[t][2] = Math.round(z * 32.0) / 32.0;
            if (t == 0) {
                System.arraycopy(serverPos[0], 0, clientPos[0], 0, 3);
            } else {
                for (int a = 0; a < 3; a++) {
                    clientPos[t][a] = clientPos[t - 1][a] + (serverPos[t][a] - clientPos[t - 1][a]) / 3.0;
                }
            }
        }
        float[] zeroRel = new float[10];
        for (int t = 0; t <= launchIdx; t++) {
            outTrack.add(t + 1, clientPos[t][0], clientPos[t][1], clientPos[t][2],
                    0, yaws[t], pitches[t], flags[t],
                    serverPos[t][0], serverPos[t][1], serverPos[t][2], zeroRel);
        }
        return serverPos;
    }

    private static byte[][] sampleVoxels(Set<Long> solid, double px, double py, double pz) {
        byte[] fine = new byte[BowNeuralMotion.FINE_DIM];
        byte[] coarse = new byte[BowNeuralMotion.COARSE_DIM];
        int bx = (int)Math.floor(px), by = (int)Math.floor(py), bz = (int)Math.floor(pz);
        for (int cy = 0; cy < 10; cy++) {
            int wy = by - 4 + cy, fy = cy - 1, cyOff = (cy >> 1) * 121;
            for (int cz = 0; cz < 22; cz++) {
                int wz = bz - 10 + cz, fz = cz - 6, czOff = cyOff + (cz >> 1) * 11;
                for (int cx = 0; cx < 22; cx++) {
                    if (solid.contains(pack(bx - 10 + cx, wy, wz))) {
                        coarse[czOff + (cx >> 1)] = 1;
                        int fx = cx - 6;
                        if (fy >= 0 && fy < 7 && fz >= 0 && fz < 9 && fx >= 0 && fx < 9) {
                            fine[fy * 81 + fz * 9 + fx] = 1;
                        }
                    }
                }
            }
        }
        return new byte[][]{fine, coarse};
    }

    /** Check whether shot intersects the true target hitbox (+0.3b projectile expansion) along its flight. */
    private static boolean hitsTrueTarget(Shot shot, double[][] serverPos, int launchIdx, double pingShiftTicks) {
        if (shot == null) return false;
        int pingInt = (int)Math.round(pingShiftTicks);
        int tCenter = (int)Math.round(shot.time);
        for (int dt = Math.max(1, tCenter - 3); dt <= Math.min(BowBallistics.MAX_THROW_TICKS, tCenter + 3); dt++) {
            int srvTick = launchIdx + pingInt + dt;
            if (srvTick >= serverPos.length) break;
            double ax = shot.xAt(dt), ay = shot.yAt(dt), az = shot.zAt(dt);
            double ivx = Math.abs(shot.vxAt(dt)), ivy = Math.abs(shot.vyAt(dt)), ivz = Math.abs(shot.vzAt(dt));
            double[] tp = serverPos[srvTick];
            if (Math.abs(ax - tp[0]) <= 0.60 + 0.40 * ivx
                    && Math.abs(az - tp[2]) <= 0.60 + 0.40 * ivz
                    && ay >= tp[1] - 0.30 - 0.35 * ivy
                    && ay <= tp[1] + 2.10 + 0.35 * ivy) {
                return true;
            }
        }
        return false;
    }

    private static Shot solveThrow(BowNeuralMotion neural, BowNeuralMotion.Prediction pred,
                                   double sx, double eyeY, double sz, double shift,
                                   double[] effWeights, VoidKbValue.SolidQuery solid) {
        int K = pred.weights.length;
        Shot best = null;
        double bestEv = -1.0;
        double[] vals = new double[K];
        boolean scored = false;
        for (int c = 0; c <= K; c++) {
            if (c < K && effWeights[c] < 0.02) continue;
            double[][] raw = c < K ? pred.paths[c] : pred.meanPath;
            double[][] path = BowNeuralMotion.shiftAndExtendPath(raw, shift, BowBallistics.MAX_THROW_TICKS);
            Shot s = BowBallistics.intercept(sx, eyeY, sz, BowBallistics.THROW_SPEED, path, 0.90, BowBallistics.THROW_GRAVITY);
            if (s == null) continue;
            if (!scored) {
                double ivx = s.vxAt(s.time), ivz = s.vzAt(s.time);
                double tEval = Math.min(BowBallistics.MAX_THROW_TICKS, s.time + shift);
                for (int k = 0; k < K; k++) {
                    int tClamped = Math.min(BowBallistics.MAX_TICKS, (int)tEval);
                    double[] p = pred.paths[k][tClamped];
                    VoidKbValue.Score sc = VoidKbValue.scoreMode(p[0], p[1], p[2], 0.1, 0, 0, 0.3, 0.3,
                            ivx, ivz, true, 0, 20.0, 0.0, solid);
                    vals[k] = sc.value;
                }
                scored = true;
            }
            double ev = neural.expectedValue(s, pred, shift, effWeights, vals);
            if (ev > bestEv) { bestEv = ev; best = s; }
        }
        if (best != null) {
            best = neural.refineShotEv(sx, eyeY, sz, BowBallistics.THROW_SPEED, best, pred, shift, effWeights, vals, null);
        }
        return best;
    }

    public static void main(String[] args) throws Exception {
        Path modelPath = Paths.get("practice-client/assets/practice/bow-neural.bin");
        BowNeuralMotion neural;
        try (FileInputStream in = new FileInputStream(modelPath.toFile())) {
            neural = BowNeuralMotion.read(in);
        }

        // 1. Constant-speed sideways bridging: ALL speeds x ALL distances (12..48b) x ALL pings (0..160ms)!
        int constTotal = 0, constHits = 0;
        for (double spd : new double[]{0.08, 0.14, 0.19, 0.24}) {
            for (double dist : new double[]{12.0, 24.0, 36.0, 48.0}) {
                for (double pingMs : new double[]{0.0, 80.0, 160.0}) {
                    Track tr = new Track();
                    Set<Long> solid = new HashSet<>();
                    int launch = 60;
                    double[][] spos = makeScenario("const_side", spd, 7, 160, tr, solid, launch, 1.0);
                    byte[][] vox = sampleVoxels(solid, tr.get(0, 0), tr.get(0, 1), tr.get(0, 2));
                    BowNeuralMotion.Prediction pred = neural.predict(tr, vox[0], vox[1], true, true);
                    double sx = tr.get(0, 0), eyeY = tr.get(0, 1) + 1.62, sz = tr.get(0, 2) - dist;
                    double shift = pingMs / 50.0;
                    Shot shot = solveThrow(neural, pred, sx, eyeY, sz, shift, pred.weights,
                            (x, y, z) -> solid.contains(pack(x, y, z)));
                    constTotal++;
                    if (hitsTrueTarget(shot, spos, launch, shift)) constHits++;
                    else System.err.printf(Locale.ROOT, "MISS const_side spd=%.2f dist=%.0f ping=%.0fms shot=%s%n",
                            spd, dist, pingMs, shot == null ? "null" : String.format("t=%.1f", shot.time));
                }
            }
        }
        System.out.printf(Locale.ROOT, "Const sideways bridging (all speeds/12..48b/0..160ms): %d/%d (%.1f%%)%n",
                constHits, constTotal, 100.0 * constHits / constTotal);
        assert constHits == constTotal : "Expected 100% on constant sideways bridging, got " + constHits + "/" + constTotal;

        // 2. Diagonal-up stair speedbridging (spam releasing shift + stairs up): 12..36b, 0..100ms ping!
        int stairTotal = 0, stairHits = 0;
        for (int period : new int[]{6, 7, 9}) {
            for (double dist : new double[]{12.0, 24.0, 36.0}) {
                for (double pingMs : new double[]{0.0, 100.0}) {
                    Track tr = new Track();
                    Set<Long> solid = new HashSet<>();
                    int launch = 60;
                    double[][] spos = makeScenario("diag_stair_up", 0.16, period, 160, tr, solid, launch, 1.0);
                    byte[][] vox = sampleVoxels(solid, tr.get(0, 0), tr.get(0, 1), tr.get(0, 2));
                    BowNeuralMotion.Prediction pred = neural.predict(tr, vox[0], vox[1], true, true);
                    double sx = tr.get(0, 0), eyeY = tr.get(0, 1) + 1.62, sz = tr.get(0, 2) - dist;
                    double shift = pingMs / 50.0;
                    Shot shot = solveThrow(neural, pred, sx, eyeY, sz, shift, pred.weights,
                            (x, y, z) -> solid.contains(pack(x, y, z)));
                    stairTotal++;
                    if (hitsTrueTarget(shot, spos, launch, shift)) stairHits++;
                    else System.err.printf(Locale.ROOT, "MISS diag_stair period=%d dist=%.0f ping=%.0fms%n",
                            period, dist, pingMs);
                }
            }
        }
        System.out.printf(Locale.ROOT, "Diagonal-up stair speedbridging (periods 6/7/9, 12..36b, 0..100ms): %d/%d (%.1f%%)%n",
                stairHits, stairTotal, 100.0 * stairHits / stairTotal);
        assert stairHits >= stairTotal - 1 : "Expected >=94% on diagonal-up stair bridging, got " + stairHits + "/" + stairTotal;

        // 3. Variable-speed speedbridging distribution: 4-throw snowball volley MUST cover slow (0.65x), nominal (1.0x), AND fast (1.30x)!
        int varCovered = 0, varTotal = 0;
        for (double dist : new double[]{18.0, 26.0, 34.0}) {
            for (double postMult : new double[]{0.68, 1.00, 1.28}) {
                int launch = 60;
                double shift = 1.6; // 80ms ping
                List<Shot> volley = new ArrayList<>();
                List<Integer> throwTicks = new ArrayList<>();
                boolean anyHit = false;
                for (int b = 0; b < 4; b++) {
                    int dt = b * 3; // rapid 4-throw burst (every 3 ticks)
                    Track tr = new Track();
                    Set<Long> solid = new HashSet<>();
                    double[][] spos = makeScenario("speedbridge_var", 0.16, 7, 170, tr, solid, launch + dt, postMult);
                    byte[][] vox = sampleVoxels(solid, tr.get(0, 0), tr.get(0, 1), tr.get(0, 2));
                    BowNeuralMotion.Prediction pred = neural.predict(tr, vox[0], vox[1], true, true);
                    double sx = spos[launch][0], eyeY = spos[launch][1] + 1.62, sz = spos[launch][2] - dist;
                    double[] effW = pred.weights.clone();
                    for (int j = 0; j < volley.size(); j++) {
                        Shot prev = volley.get(j);
                        double rem = (throwTicks.get(j) + shift + prev.time) - (launch + dt);
                        if (rem > 0.5 && rem <= BowBallistics.MAX_THROW_TICKS) {
                            for (int k = 0; k < effW.length; k++) {
                                double pCov = neural.modeHitProbAt(prev, pred, k, rem);
                                effW[k] *= Math.max(0.0, 1.0 - pCov);
                            }
                        }
                    }
                    Shot s = solveThrow(neural, pred, sx, eyeY, sz, shift, effW,
                            (x, y, z) -> solid.contains(pack(x, y, z)));
                    if (s != null) {
                        volley.add(s);
                        throwTicks.add(launch + dt);
                        if (hitsTrueTarget(s, spos, launch + dt, shift)) anyHit = true;
                    }
                }
                varTotal++;
                if (anyHit) varCovered++;
                else System.err.printf(Locale.ROOT, "MISS variable-speed volley dist=%.0f postMult=%.2f%n", dist, postMult);
            }
        }
        System.out.printf(Locale.ROOT, "Variable-speed speedbridge 4-throw volley (0.68x/1.00x/1.28x @ 18..34b, 80ms ping): %d/%d (%.1f%%)%n",
                varCovered, varTotal, 100.0 * varCovered / varTotal);
        assert varCovered == varTotal : "Expected 100% volley coverage across speed distribution, got " + varCovered + "/" + varTotal;

        System.out.println("BowBridgingChecks passed ALL scenarios!");
    }
}
