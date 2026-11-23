package practice.tests;

import practice.core.bow.BowBallistics;
import practice.core.bow.BowBallistics.Shot;
import practice.core.bow.BowMotion.Track;
import practice.core.bow.BowNeuralMotion;
import practice.core.bow.BowNeuralMotion.Prediction;
import practice.core.bow.BowNeuralV4;
import practice.core.bow.VoidKbValue;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.FileInputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * End-to-end Java 8 regression & stress tests for bridging + snowball/egg volley coverage:
 * 1. Constant-speed sideways bridging across speeds (0.08..0.24 b/t), distances (12..48b), and pings (0..160ms)
 * 2. Variable-speed speedbridging (speed distribution where target speeds up OR slows down after throw) with volley fanning
 * 3. Diagonal-up stair speedbridging (spam releasing shift + jumping up stairs) across distances & pings
 * Supports both BNN4 (testing pure learned bridgeHack=false AND optional bridgeHack=true) and legacy BNN2.
 */
public final class BowBridgingChecks {
    private static long pack(int x, int y, int z) {
        return (((long)x & 0x3FFFFFL) << 42) | (((long)y & 0xFFFFFL) << 22) | ((long)z & 0x3FFFFFL);
    }

    private static double[][] makeScenario(String kind, double speedXZ, int stairPeriod, int totalTicks,
                                           Track outTrack, Map<Long, Integer> outSolidAtLaunch, int launchIdx,
                                           double postSpeedMult) {
        double[][] truePos = new double[totalTicks][3];
        double[][] serverPos = new double[totalTicks][3];
        double[][] clientPos = new double[totalTicks][3];
        int[] flags = new int[totalTicks];
        float[] yaws = new float[totalTicks];
        float[] pitches = new float[totalTicks];
        float[][] blk4 = new float[totalTicks][4];
        List<int[]> places = new ArrayList<>();

        for (int x = -6; x <= 0; x++) {
            for (int z = -3; z <= 3; z++) outSolidAtLaunch.put(pack(x, 63, z), -999);
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
                flags[t] = 1 | 2;
                x += speedXZ * spMult * ux;
                z += speedXZ * spMult * uz;
                int bx = (int)Math.floor(x), bz = (int)Math.floor(z);
                long key = pack(bx, 63, bz);
                if (!outSolidAtLaunch.containsKey(key) && t <= launchIdx) {
                    outSolidAtLaunch.put(key, t);
                    places.add(new int[]{t, bx, 63, bz});
                }
            } else if ("speedbridge_var".equals(kind)) {
                int phase = t % 7;
                boolean sneak = phase >= 5;
                double cadence = sneak ? 0.45 : (1.18 + 0.18 * Math.sin(t * 0.17));
                yaws[t] = 135f; pitches[t] = 77f;
                flags[t] = (sneak ? 1 : 0) | 2;
                x += speedXZ * spMult * cadence * ux;
                z += speedXZ * spMult * cadence * uz;
                int bx = (int)Math.floor(x), bz = (int)Math.floor(z);
                long key = pack(bx, 63, bz);
                if (!outSolidAtLaunch.containsKey(key) && t <= launchIdx) {
                    outSolidAtLaunch.put(key, t);
                    places.add(new int[]{t, bx, 63, bz});
                }
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
                    long k1 = pack(bx, by, bz);
                    if (!outSolidAtLaunch.containsKey(k1)) {
                        outSolidAtLaunch.put(k1, t);
                        places.add(new int[]{t, bx, by, bz});
                    }
                    if (phase >= stairPeriod - 3) {
                        long k2 = pack(bx, by + 1, bz);
                        if (!outSolidAtLaunch.containsKey(k2)) {
                            outSolidAtLaunch.put(k2, t);
                            places.add(new int[]{t, bx, by + 1, bz});
                        }
                    }
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
            int bx = (int)Math.floor(x), by = (int)Math.floor(y), bz = (int)Math.floor(z);
            int cnt = 0;
            float sdx = 0f, sdy = 0f, sdz = 0f;
            for (int pi = Math.max(0, places.size() - 12); pi < places.size(); pi++) {
                int[] ev = places.get(pi);
                int age = t - ev[0];
                if (age >= 0 && age <= 10 && Math.abs(ev[1] - bx) <= 3 && Math.abs(ev[3] - bz) <= 3 && Math.abs(ev[2] - by) <= 2) {
                    cnt++; sdx += ev[1] - bx; sdy += ev[2] - by; sdz += ev[3] - bz;
                }
            }
            if (cnt > 0) {
                blk4[t][0] = Math.min(1f, cnt / 4f);
                blk4[t][1] = sdx / cnt;
                blk4[t][2] = sdy / cnt;
                blk4[t][3] = sdz / cnt;
            }
        }
        float[] zeroRel = new float[10];
        for (int t = 0; t <= launchIdx; t++) {
            outTrack.add(t + 1, clientPos[t][0], clientPos[t][1], clientPos[t][2],
                    0, yaws[t], pitches[t], flags[t],
                    serverPos[t][0], serverPos[t][1], serverPos[t][2], zeroRel, blk4[t]);
        }
        return serverPos;
    }

    private static byte[][] sampleV2Voxels(Map<Long, Integer> solid, double px, double py, double pz) {
        byte[] fine = new byte[BowNeuralMotion.FINE_DIM];
        byte[] coarse = new byte[BowNeuralMotion.COARSE_DIM];
        int bx = (int)Math.floor(px), by = (int)Math.floor(py), bz = (int)Math.floor(pz);
        for (int cy = 0; cy < 10; cy++) {
            int wy = by - 4 + cy, fy = cy - 1, cyOff = (cy >> 1) * 121;
            for (int cz = 0; cz < 22; cz++) {
                int wz = bz - 10 + cz, fz = cz - 6, czOff = cyOff + (cz >> 1) * 11;
                for (int cx = 0; cx < 22; cx++) {
                    if (solid.containsKey(pack(bx - 10 + cx, wy, wz))) {
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

    private static byte[][] sampleV4Spatial(Map<Long, Integer> solid, double px, double py, double pz, int launchIdx) {
        byte[] fineSolid = new byte[BowNeuralV4.FINE_DIM];
        byte[] finePlaced = new byte[BowNeuralV4.FINE_DIM];
        byte[] top = new byte[BowNeuralV4.TOP_DIM];
        int plane = 25 * 25;
        Arrays.fill(top, 0, plane, (byte)-127);
        Arrays.fill(top, 2 * plane, 3 * plane, (byte)100);
        int[] hiY = new int[plane];
        Arrays.fill(hiY, -999);

        int bx = (int)Math.floor(px), by = (int)Math.floor(py), bz = (int)Math.floor(pz);
        for (Map.Entry<Long, Integer> e : solid.entrySet()) {
            int pt = e.getValue();
            if (pt > launchIdx) continue;
            long k = e.getKey();
            int wx = (int)((k >> 42) & 0x3FFFFFL); if ((wx & 0x200000) != 0) wx |= ~0x3FFFFF;
            int wy = (int)((k >> 22) & 0xFFFFFL);  if ((wy & 0x80000) != 0) wy |= ~0xFFFFF;
            int wz = (int)(k & 0x3FFFFFL);         if ((wz & 0x200000) != 0) wz |= ~0x3FFFFF;
            int dx = wx - bx, dy = wy - by, dz = wz - bz;
            if (dx >= -12 && dx <= 12 && dz >= -12 && dz <= 12 && dy >= -12 && dy <= 4) {
                int idx = (dz + 12) * 25 + (dx + 12);
                top[2 * plane + idx] = 0;
                if (dy == 1 || dy == 2) top[plane + idx] = 100;
                if (dy >= -6 && dy <= 4 && dy > hiY[idx]) {
                    hiY[idx] = dy;
                    top[idx] = (byte)Math.max(-120, Math.min(100, (dy + 1) * 20));
                }
                if (pt >= 0 && launchIdx - pt <= 120 && dy >= -4 && dy <= 3) {
                    byte st = (byte)Math.round(100.0 * Math.exp(-(launchIdx - pt) / 60.0));
                    if (st > top[3 * plane + idx]) top[3 * plane + idx] = st;
                    if (dx >= -4 && dx <= 4 && dz >= -4 && dz <= 4 && dy >= -3 && dy <= 3) {
                        finePlaced[(dy + 3) * 81 + (dz + 4) * 9 + (dx + 4)] = 1;
                    }
                }
            }
            if (dx >= -4 && dx <= 4 && dz >= -4 && dz <= 4 && dy >= -3 && dy <= 3) {
                fineSolid[(dy + 3) * 81 + (dz + 4) * 9 + (dx + 4)] = 1;
            }
        }
        return new byte[][]{fineSolid, finePlaced, top};
    }

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

    private static Shot solveThrow(BowNeuralV4 v4, BowNeuralMotion v2, Prediction pred,
                                   double sx, double eyeY, double sz, double shift,
                                   double[] effWeights, VoidKbValue.SolidQuery solid) {
        int K = pred.weights.length;
        int maxH = pred.maxHorizon();
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
                    int tClamped = Math.min(maxH, (int)tEval);
                    double[] p = pred.paths[k][tClamped];
                    VoidKbValue.Score sc = VoidKbValue.scoreMode(p[0], p[1], p[2], 0.1, 0, 0, 0.3, 0.3,
                            ivx, ivz, true, 0, 20.0, 0.0, solid);
                    vals[k] = sc.value;
                }
                scored = true;
            }
            double ev = v4 != null ? v4.expectedValue(s, pred, shift, effWeights, vals)
                    : v2.expectedValue(s, pred, shift, effWeights, vals);
            if (ev > bestEv) { bestEv = ev; best = s; }
        }
        if (best != null) {
            best = v4 != null ? v4.refineShotEv(sx, eyeY, sz, BowBallistics.THROW_SPEED, best, pred, shift, effWeights, vals, null)
                    : v2.refineShotEv(sx, eyeY, sz, BowBallistics.THROW_SPEED, best, pred, shift, effWeights, vals, null);
        }
        return best;
    }

    private static Prediction runPredict(BowNeuralV4 v4, BowNeuralMotion v2, Track tr,
                                         Map<Long, Integer> solid, int launchIdx, boolean bridgeHack) {
        if (v4 != null) {
            byte[][] sp = sampleV4Spatial(solid, tr.get(0, 0), tr.get(0, 1), tr.get(0, 2), launchIdx);
            return v4.predict(tr, sp[0], sp[1], sp[2], true, bridgeHack);
        }
        byte[][] vox = sampleV2Voxels(solid, tr.get(0, 0), tr.get(0, 1), tr.get(0, 2));
        return v2.predict(tr, vox[0], vox[1], true, bridgeHack);
    }

    private static int[] runSuite(BowNeuralV4 v4, BowNeuralMotion v2, boolean bridgeHack, String label) {
        int constTotal = 0, constHits = 0;
        for (double spd : new double[]{0.08, 0.14, 0.19, 0.24}) {
            for (double dist : new double[]{12.0, 24.0, 36.0, 48.0}) {
                for (double pingMs : new double[]{0.0, 80.0, 160.0}) {
                    Track tr = new Track();
                    Map<Long, Integer> solid = new HashMap<>();
                    int launch = 60;
                    double[][] spos = makeScenario("const_side", spd, 7, 160, tr, solid, launch, 1.0);
                    Prediction pred = runPredict(v4, v2, tr, solid, launch, bridgeHack);
                    double sx = tr.get(0, 0), eyeY = tr.get(0, 1) + 1.62, sz = tr.get(0, 2) - dist;
                    double shift = pingMs / 50.0;
                    Shot shot = solveThrow(v4, v2, pred, sx, eyeY, sz, shift, pred.weights,
                            (x, y, z) -> solid.containsKey(pack(x, y, z)));
                    constTotal++;
                    if (hitsTrueTarget(shot, spos, launch, shift)) constHits++;
                }
            }
        }

        int stairTotal = 0, stairHits = 0;
        for (int period : new int[]{6, 7, 9}) {
            for (double dist : new double[]{12.0, 24.0, 36.0}) {
                for (double pingMs : new double[]{0.0, 100.0}) {
                    Track tr = new Track();
                    Map<Long, Integer> solid = new HashMap<>();
                    int launch = 60;
                    double[][] spos = makeScenario("diag_stair_up", 0.16, period, 160, tr, solid, launch, 1.0);
                    Prediction pred = runPredict(v4, v2, tr, solid, launch, bridgeHack);
                    double sx = tr.get(0, 0), eyeY = tr.get(0, 1) + 1.62, sz = tr.get(0, 2) - dist;
                    double shift = pingMs / 50.0;
                    Shot shot = solveThrow(v4, v2, pred, sx, eyeY, sz, shift, pred.weights,
                            (x, y, z) -> solid.containsKey(pack(x, y, z)));
                    stairTotal++;
                    if (hitsTrueTarget(shot, spos, launch, shift)) stairHits++;
                }
            }
        }

        int varCovered = 0, varTotal = 0;
        for (double dist : new double[]{18.0, 26.0, 34.0}) {
            for (double postMult : new double[]{0.68, 1.00, 1.28}) {
                int launch = 60;
                double shift = 1.6;
                List<Shot> volley = new ArrayList<>();
                List<Integer> throwTicks = new ArrayList<>();
                boolean anyHit = false;
                for (int b = 0; b < 4; b++) {
                    int dt = b * 3;
                    Track tr = new Track();
                    Map<Long, Integer> solid = new HashMap<>();
                    double[][] spos = makeScenario("speedbridge_var", 0.16, 7, 170, tr, solid, launch + dt, postMult);
                    Prediction pred = runPredict(v4, v2, tr, solid, launch + dt, bridgeHack);
                    double sx = spos[launch][0], eyeY = spos[launch][1] + 1.62, sz = spos[launch][2] - dist;
                    double[] effW = pred.weights.clone();
                    for (int j = 0; j < volley.size(); j++) {
                        Shot prev = volley.get(j);
                        double rem = (throwTicks.get(j) + shift + prev.time) - (launch + dt);
                        if (rem > 0.5 && rem <= BowBallistics.MAX_THROW_TICKS) {
                            for (int k = 0; k < effW.length; k++) {
                                double pCov = v4 != null ? v4.modeHitProbAt(prev, pred, k, rem)
                                        : v2.modeHitProbAt(prev, pred, k, rem);
                                effW[k] *= Math.max(0.0, 1.0 - pCov);
                            }
                        }
                    }
                    Shot s = solveThrow(v4, v2, pred, sx, eyeY, sz, shift, effW,
                            (x, y, z) -> solid.containsKey(pack(x, y, z)));
                    if (s != null) {
                        volley.add(s);
                        throwTicks.add(launch + dt);
                        if (hitsTrueTarget(s, spos, launch + dt, shift)) anyHit = true;
                    }
                }
                varTotal++;
                if (anyHit) varCovered++;
            }
        }
        System.out.printf(Locale.ROOT, "[%s] Const sideways: %d/%d (%.1f%%) | Diag-up stair: %d/%d (%.1f%%) | Var-speed volley: %d/%d (%.1f%%)%n",
                label, constHits, constTotal, 100.0 * constHits / constTotal,
                stairHits, stairTotal, 100.0 * stairHits / stairTotal,
                varCovered, varTotal, 100.0 * varCovered / varTotal);
        return new int[]{constHits, constTotal, stairHits, stairTotal, varCovered, varTotal};
    }

    public static void main(String[] args) throws Exception {
        Path modelPath = Paths.get("practice-client/assets/practice/bow-neural.bin");
        int magic;
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(modelPath.toFile())))) {
            magic = in.readInt();
        }
        BowNeuralV4 v4 = null;
        BowNeuralMotion v2 = null;
        if (magic == BowNeuralV4.MAGIC) {
            try (FileInputStream in = new FileInputStream(modelPath.toFile())) { v4 = BowNeuralV4.read(in); }
        } else {
            try (FileInputStream in = new FileInputStream(modelPath.toFile())) { v2 = BowNeuralMotion.read(in); }
        }

        if (v4 != null) {
            int[] pure = runSuite(v4, null, false, "BNN4 PURE LEARNED (bridgehack=OFF)");
            assert pure[0] >= (int)(pure[1] * 0.85) : "Pure BNN4 const sideways too low: " + pure[0] + "/" + pure[1];
            assert pure[2] >= (int)(pure[3] * 0.78) : "Pure BNN4 diag-up stair too low: " + pure[2] + "/" + pure[3];
            assert pure[4] >= pure[5] - 1 : "Pure BNN4 var-speed volley too low: " + pure[4] + "/" + pure[5];
        }
        int[] hack = runSuite(v4, v2, true, (v4 != null ? "BNN4" : "BNN2") + " + bridgehack=ON");
        assert hack[0] == hack[1] : "Expected 100% on const sideways with bridgehack=ON, got " + hack[0] + "/" + hack[1];
        assert hack[2] >= hack[3] - 1 : "Expected >=94% on diag-up stair with bridgehack=ON, got " + hack[2] + "/" + hack[3];
        assert hack[4] == hack[5] : "Expected 100% on var-speed volley with bridgehack=ON, got " + hack[4] + "/" + hack[5];
        System.out.println("BowBridgingChecks passed ALL scenarios!");
    }
}
