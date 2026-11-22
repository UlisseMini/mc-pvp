package practice.core.bow;

import practice.core.bow.BowBallistics.Shot;
import practice.core.bow.BowMotion.Track;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Pure Java 8 inference for the unified multi-modal neural trajectory + 9x7x9 voxel predictor.
 * Produces K coherent trajectory modes (weight, path, per-tick std) and analytic expected hit probabilities.
 */
public final class BowNeuralMotion {
    public static final int MAGIC = 0x424E4E31; // 'BNN1'
    public static final int HIST_LEN = 24, HIST_CH = 15, CTX_DIM = 22, VOX_DIM = 7 * 9 * 9;
    private static final int[] AUTOCORR_LAGS = {12, 16, 20, 24, 28, 34, 42, 52};
    private static final double SPREAD = 0.007499999832361937;
    private static final double INV_SQRT2 = 0.7071067811865475;

    private final int numModes;
    private final int[] horizons;
    private final float[] calibration;

    private final float[] c1w, c1b, c2w, c2b, c3w, c3b;
    private final float[] hpW, hpB, v1W, v1B, v2W, v2B;
    private final float[] trW, trB, r1W, r1B, r2W, r2B;
    private final float[] logW, logB, meanW, meanB, stdW, stdB;

    public static final class Prediction {
        public final double[] weights;
        public final double[][][] paths; // [K][MAX_TICKS+1][3]
        public final double[][][] stds;  // [K][MAX_TICKS+1][3]
        public final double[][] meanPath;

        Prediction(double[] weights, double[][][] paths, double[][][] stds, double[][] meanPath) {
            this.weights = weights;
            this.paths = paths;
            this.stds = stds;
            this.meanPath = meanPath;
        }
    }

    private BowNeuralMotion(DataInputStream in) throws IOException {
        if (in.readInt() != MAGIC) throw new IOException("Invalid bow neural model magic");
        numModes = in.readInt();
        int numH = in.readInt();
        int calLen = in.readInt();
        if (numModes < 1 || numModes > 16 || numH < 1 || numH > 16 || calLen < 2 || calLen > 64)
            throw new IOException("Invalid bow neural model header");
        horizons = new int[numH];
        for (int i = 0; i < numH; i++) horizons[i] = in.readInt();
        calibration = readFloats(in, calLen);

        c1w = readFloats(in, 32 * HIST_CH * 3); c1b = readFloats(in, 32);
        c2w = readFloats(in, 48 * 32 * 3);      c2b = readFloats(in, 48);
        c3w = readFloats(in, 64 * 48 * 3);      c3b = readFloats(in, 64);
        hpW = readFloats(in, 96 * 192);         hpB = readFloats(in, 96);
        v1W = readFloats(in, 64 * VOX_DIM);     v1B = readFloats(in, 64);
        v2W = readFloats(in, 64 * 64);          v2B = readFloats(in, 64);
        trW = readFloats(in, 192 * (96 + CTX_DIM + 64)); trB = readFloats(in, 192);
        r1W = readFloats(in, 192 * 192);        r1B = readFloats(in, 192);
        r2W = readFloats(in, 192 * 192);        r2B = readFloats(in, 192);
        logW = readFloats(in, numModes * 192);  logB = readFloats(in, numModes);
        meanW = readFloats(in, numModes * numH * 3 * 192); meanB = readFloats(in, numModes * numH * 3);
        stdW = readFloats(in, numModes * numH * 3 * 192);  stdB = readFloats(in, numModes * numH * 3);
    }

    public static BowNeuralMotion read(InputStream stream) throws IOException {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(stream))) {
            return new BowNeuralMotion(in);
        }
    }

    private static float[] readFloats(DataInputStream in, int n) throws IOException {
        float[] arr = new float[n];
        for (int i = 0; i < n; i++) {
            float v = in.readFloat();
            if (!Float.isFinite(v)) throw new IOException("Non-finite weight in bow neural model");
            arr[i] = v;
        }
        return arr;
    }

    public int modes() { return numModes; }

    /**
     * Predict K coherent future trajectories in world coordinates.
     * @param track target observation history
     * @param rawVox567 optional 7x9x9 solid-voxel occupancy centered on floor(x,y,z) in world order [dy*81 + dz*9 + dx]
     * @param hasTerrain true if rawVox567 contains valid loaded chunk occupancy
     */
    public Prediction predict(Track track, byte[] rawVox567, boolean hasTerrain) {
        double p0x = track.get(0, 0), p0y = track.get(0, 1), p0z = track.get(0, 2);
        float yaw0 = track.yaw(0);
        int q = ((int)Math.floor((yaw0 + 45.0) / 90.0)) & 3;
        double qDeg = q * 90.0;
        int validPast = track.length();
        int maxAge = Math.min(validPast - 1, HIST_LEN - 1);

        // 1) History tensor [HIST_CH * HIST_LEN] (ch * HIST_LEN + age)
        float[] hist = new float[HIST_CH * HIST_LEN];
        for (int age = 0; age < HIST_LEN; age++) {
            int cAge = Math.min(age, maxAge);
            int pAge = Math.min(cAge + 1, validPast - 1);
            double dx = track.get(cAge, 0) - p0x;
            double dy = track.get(cAge, 1) - p0y;
            double dz = track.get(cAge, 2) - p0z;
            double rx = rotX(dx, dz, q), rz = rotZ(dx, dz, q);

            double vdx = track.get(cAge, 0) - track.get(pAge, 0);
            double vdy = track.get(cAge, 1) - track.get(pAge, 1);
            double vdz = track.get(cAge, 2) - track.get(pAge, 2);
            double vrx = rotX(vdx, vdz, q), vrz = rotZ(vdx, vdz, q);

            double relYaw = Math.toRadians(track.yaw(cAge) - qDeg);
            double pit = track.pitch(cAge) / 90.0;
            int fl = track.flags(cAge);

            hist[0 * HIST_LEN + age] = clampF(rx / 4.0, -4f, 4f);
            hist[1 * HIST_LEN + age] = clampF(dy / 4.0, -4f, 4f);
            hist[2 * HIST_LEN + age] = clampF(rz / 4.0, -4f, 4f);
            hist[3 * HIST_LEN + age] = clampF(vrx * 3.0, -3f, 3f);
            hist[4 * HIST_LEN + age] = clampF(vdy * 3.0, -3f, 3f);
            hist[5 * HIST_LEN + age] = clampF(vrz * 3.0, -3f, 3f);
            hist[6 * HIST_LEN + age] = (float)Math.sin(relYaw);
            hist[7 * HIST_LEN + age] = (float)Math.cos(relYaw);
            hist[8 * HIST_LEN + age] = clampF(pit, -1f, 1f);
            hist[9 * HIST_LEN + age] = (fl & 1) != 0 ? 1f : 0f;
            hist[10 * HIST_LEN + age] = (fl & 2) != 0 ? 1f : 0f;
            hist[11 * HIST_LEN + age] = (fl & 12) != 0 ? 1f : 0f;
            hist[12 * HIST_LEN + age] = (fl & 32) != 0 ? 1f : 0f;
            hist[13 * HIST_LEN + age] = (fl & 80) != 0 ? 1f : 0f;
            hist[14 * HIST_LEN + age] = age <= maxAge ? 1f : 0f;
        }

        // 2) Context summary [CTX_DIM]
        float[] ctx = new float[CTX_DIM];
        fillMeanVel(track, validPast, 0, 4, q, ctx, 0, true);
        fillMeanVel(track, validPast, 4, 12, q, ctx, 3, true);
        fillMeanVel(track, validPast, 12, 24, q, ctx, 6, false);
        fillMeanVel(track, validPast, 24, 48, q, ctx, 8, false);

        int nSp = Math.min(32, validPast - 1);
        if (nSp >= 2) {
            double sum = 0, sumSq = 0;
            for (int a = 0; a < nSp; a++) {
                double s = Math.hypot(track.get(a, 0) - track.get(a + 1, 0),
                        track.get(a, 2) - track.get(a + 1, 2));
                sum += s;
                sumSq += s * s;
            }
            double mean = sum / nSp;
            double var = Math.max(0.0, sumSq / nSp - mean * mean);
            ctx[10] = (float)(mean * 3.0);
            ctx[11] = (float)(Math.sqrt(var) * 3.0);
        }

        int nAc = Math.min(110, validPast - 4);
        if (nAc >= 16) {
            double[] vx3 = new double[nAc], vz3 = new double[nAc];
            double mx = 0, mz = 0;
            for (int a = 0; a < nAc; a++) {
                vx3[a] = track.get(a, 0) - track.get(a + 3, 0);
                vz3[a] = track.get(a, 2) - track.get(a + 3, 2);
                mx += vx3[a];
                mz += vz3[a];
            }
            mx /= nAc; mz /= nAc;
            double energy = 0;
            for (int a = 0; a < nAc; a++) {
                vx3[a] -= mx;
                vz3[a] -= mz;
                energy += vx3[a] * vx3[a] + vz3[a] * vz3[a];
            }
            if (energy > 1e-4) {
                for (int li = 0; li < AUTOCORR_LAGS.length; li++) {
                    int lag = AUTOCORR_LAGS[li];
                    if (lag + 8 < nAc) {
                        double num = 0, e1 = 0, e2 = 0;
                        int m = nAc - lag;
                        for (int a = 0; a < m; a++) {
                            double x1 = vx3[a], z1 = vz3[a], x2 = vx3[a + lag], z2 = vz3[a + lag];
                            num += x1 * x2 + z1 * z2;
                            e1 += x1 * x1 + z1 * z1;
                            e2 += x2 * x2 + z2 * z2;
                        }
                        double den = Math.sqrt(e1 * e2);
                        if (den > 1e-5) ctx[12 + li] = clampF(num / den, -1f, 1f);
                    }
                }
            }
        }
        ctx[20] = (float)(Math.log1p(Math.min(256, validPast)) / 5.5);
        ctx[21] = (hasTerrain && rawVox567 != null) ? 1f : 0f;
        for (int i = 0; i < CTX_DIM; i++) ctx[i] = clampF(ctx[i], -4f, 4f);

        // 3) History 1D Conv branch
        float[] h1 = conv1dSilu(hist, HIST_CH, HIST_LEN, c1w, c1b, 32, 1);   // 32 x 24
        float[] h2 = conv1dSilu(h1, 32, HIST_LEN, c2w, c2b, 48, 2);          // 48 x 12
        float[] h3 = conv1dSilu(h2, 48, 12, c3w, c3b, 64, 2);                // 64 x 6
        float[] hPool = new float[192];
        for (int c = 0; c < 64; c++) {
            int base = c * 6;
            float first = h3[base], sum = 0f, mx = -Float.MAX_VALUE;
            for (int t = 0; t < 6; t++) {
                float v = h3[base + t];
                sum += v;
                if (v > mx) mx = v;
            }
            hPool[c] = first;
            hPool[64 + c] = sum / 6f;
            hPool[128 + c] = mx;
        }
        float[] hEmb = linearSilu(hPool, hpW, hpB, 96);

        // 4) Sparse binary 9x7x9 Voxel branch (rotated losslessly by q!)
        float[] v1 = new float[64];
        System.arraycopy(v1B, 0, v1, 0, 64);
        if (hasTerrain && rawVox567 != null && rawVox567.length == VOX_DIM) {
            for (int dy = 0; dy < 7; dy++) {
                int yOff = dy * 81;
                for (int rz = 0; rz < 9; rz++) {
                    for (int rx = 0; rx < 9; rx++) {
                        int srcIdx = yOff + srcVoxIndex(rz, rx, q);
                        if (rawVox567[srcIdx] != 0) {
                            int feat = yOff + rz * 9 + rx;
                            for (int o = 0; o < 64; o++) v1[o] += v1W[o * VOX_DIM + feat];
                        }
                    }
                }
            }
        }
        for (int o = 0; o < 64; o++) v1[o] = silu(v1[o]);
        float[] vEmb = linearSilu(v1, v2W, v2B, 64);

        // 5) Residual Fusion Trunk
        float[] fused = new float[96 + CTX_DIM + 64];
        System.arraycopy(hEmb, 0, fused, 0, 96);
        System.arraycopy(ctx, 0, fused, 96, CTX_DIM);
        System.arraycopy(vEmb, 0, fused, 96 + CTX_DIM, 64);

        float[] x = linearSilu(fused, trW, trB, 192);
        float[] r = linearSilu(x, r1W, r1B, 192);
        float[] rOut = linear(r, r2W, r2B, 192);
        for (int i = 0; i < 192; i++) x[i] = silu(x[i] + rOut[i]);

        // 6) Multi-modal output heads
        float[] logits = linear(x, logW, logB, numModes);
        float[] meansFlat = linear(x, meanW, meanB, numModes * horizons.length * 3);
        float[] logstdFlat = linear(x, stdW, stdB, numModes * horizons.length * 3);

        double[] weights = new double[numModes];
        double maxL = Double.NEGATIVE_INFINITY;
        for (int k = 0; k < numModes; k++) if (logits[k] > maxL) maxL = logits[k];
        double sumW = 0;
        for (int k = 0; k < numModes; k++) {
            weights[k] = Math.exp(logits[k] - maxL);
            sumW += weights[k];
        }
        for (int k = 0; k < numModes; k++) weights[k] /= sumW;

        int maxT = BowBallistics.MAX_TICKS;
        double[][][] paths = new double[numModes][maxT + 1][3];
        double[][][] stds = new double[numModes][maxT + 1][3];
        double[][] meanPath = new double[maxT + 1][3];
        int numH = horizons.length;
        double[][] ancPos = new double[numH + 1][3];
        double[][] ancStd = new double[numH + 1][3];

        for (int k = 0; k < numModes; k++) {
            ancPos[0][0] = p0x; ancPos[0][1] = p0y; ancPos[0][2] = p0z;
            ancStd[0][0] = 0.05; ancStd[0][1] = 0.05; ancStd[0][2] = 0.05;
            for (int hi = 0; hi < numH; hi++) {
                int base = (k * numH + hi) * 3;
                double rx = meansFlat[base], ry = meansFlat[base + 1], rz = meansFlat[base + 2];
                ancPos[hi + 1][0] = p0x + invRotX(rx, rz, q);
                ancPos[hi + 1][1] = p0y + ry;
                ancPos[hi + 1][2] = p0z + invRotZ(rx, rz, q);
                double sx = Math.exp(Math.max(-2.1, Math.min(2.1, logstdFlat[base])));
                double sy = Math.exp(Math.max(-2.1, Math.min(2.1, logstdFlat[base + 1])));
                double sz = Math.exp(Math.max(-2.1, Math.min(2.1, logstdFlat[base + 2])));
                if (q == 1 || q == 3) {
                    ancStd[hi + 1][0] = sz; ancStd[hi + 1][2] = sx;
                } else {
                    ancStd[hi + 1][0] = sx; ancStd[hi + 1][2] = sz;
                }
                ancStd[hi + 1][1] = sy;
            }
            paths[k][0][0] = p0x; paths[k][0][1] = p0y; paths[k][0][2] = p0z;
            stds[k][0][0] = 0.05; stds[k][0][1] = 0.05; stds[k][0][2] = 0.05;
            int hi = 0, lower = 0;
            for (int t = 1; t <= maxT; t++) {
                while (hi + 1 < numH && t > horizons[hi]) lower = horizons[hi++];
                double u = Math.min(1.0, (t - lower) / (double)(horizons[hi] - lower));
                for (int a = 0; a < 3; a++) {
                    double pv = ancPos[hi][a] * (1.0 - u) + ancPos[hi + 1][a] * u;
                    double sv = ancStd[hi][a] * (1.0 - u) + ancStd[hi + 1][a] * u;
                    paths[k][t][a] = pv;
                    stds[k][t][a] = sv;
                    meanPath[t][a] += weights[k] * pv;
                }
            }
        }
        meanPath[0][0] = p0x; meanPath[0][1] = p0y; meanPath[0][2] = p0z;
        applyPeriodicCycle(track, paths, meanPath, 0.22);
        return new Prediction(weights, paths, stds, meanPath);
    }

    /**
     * When a target repeats a literal periodic horizontal strafe (period 15-58 ticks) for 2+ full cycles,
     * blend horizontal predictions toward the exact repeating cycle (never triggers on normal varied human play).
     */
    private static void applyPeriodicCycle(Track track, double[][][] paths, double[][] meanPath, double gate) {
        if (track.length() < 46) return;
        double variation = 0;
        for (int age = 0; age < 24; age += 3)
            for (int axis : new int[]{0, 2})
                variation += sq((track.get(age, axis) - track.get(age + 3, axis))
                        - (track.get(24, axis) - track.get(27, axis))) * 0.125;
        if (variation < 0.06) return;
        int bestPeriod = 0;
        double bestError = Double.POSITIVE_INFINITY;
        for (int period = 15; period <= 58 && 2 * period + 12 < track.length(); period++) {
            double error = 0;
            for (int age = 0; age < period + 8; age += 2) {
                for (int axis : new int[]{0, 2}) {
                    double now = track.get(age, axis) - track.get(age + 3, axis);
                    double before = track.get(age + period, axis) - track.get(age + period + 3, axis);
                    error += sq(now - before);
                }
            }
            error /= ((period + 9) / 2) * variation;
            double penalized = error + 0.0009 * period;
            if (penalized < bestError) {
                bestError = penalized;
                bestPeriod = period;
            }
        }
        if (bestPeriod == 0 || bestError >= gate) return;
        double conf = 0.9 * (1.0 - bestError / gate);
        int maxT = meanPath.length - 1;
        for (int t = 1; t <= maxT; t++) {
            for (int axis : new int[]{0, 2}) {
                double full = track.get(0, axis) - track.get(bestPeriod, axis);
                double part = track.get(bestPeriod - t % bestPeriod, axis) - track.get(bestPeriod, axis);
                double repeat = track.get(0, axis) + full * (t / bestPeriod) + part;
                meanPath[t][axis] = meanPath[t][axis] * (1.0 - conf) + repeat * conf;
                for (int k = 0; k < paths.length; k++) {
                    paths[k][t][axis] = paths[k][t][axis] * (1.0 - conf) + repeat * conf;
                }
            }
        }
    }

    /** Analytic expected hit probability P(hit | shot) integrated across all K modes + arrow spread. */
    public double rawHitProb(Shot shot, Prediction pred) {
        if (shot == null || pred == null) return 0.0;
        double tHit = shot.time;
        if (tHit <= 0.0 || tHit > BowBallistics.MAX_TICKS) return 0.0;
        double tr = BowBallistics.travel(tHit);
        double ax = shot.x + shot.vx * tr;
        double ay = shot.y + shot.vy * tr - BowBallistics.fall(tHit);
        double az = shot.z + shot.vz * tr;
        double spd = Math.sqrt(shot.vx * shot.vx + shot.vy * shot.vy + shot.vz * shot.vz);
        double dragT = Math.pow(BowBallistics.DRAG, tHit);
        double ivx = shot.vx * dragT;
        double ivy = shot.vy * dragT - BowBallistics.GRAVITY * (1.0 - dragT) / (1.0 - BowBallistics.DRAG);
        double ivz = shot.vz * dragT;
        double sigArrow = SPREAD * spd * tr;
        double varArrow = sigArrow * sigArrow;

        double slackX = 0.6 + 0.38 * Math.abs(ivx);
        double slackYLo = -0.3 - 0.35 * Math.abs(ivy);
        double slackYHi = 2.1 + 0.35 * Math.abs(ivy);
        double slackZ = 0.6 + 0.38 * Math.abs(ivz);

        int lo = (int)tHit;
        int hi = Math.min(BowBallistics.MAX_TICKS, lo + 1);
        double frac = tHit - lo;
        double total = 0.0;
        for (int k = 0; k < numModes; k++) {
            double w = pred.weights[k];
            if (w < 1e-4) continue;
            double[] pLo = pred.paths[k][lo], pHi = pred.paths[k][hi];
            double[] sLo = pred.stds[k][lo], sHi = pred.stds[k][hi];
            double mx = pLo[0] + frac * (pHi[0] - pLo[0]);
            double my = pLo[1] + frac * (pHi[1] - pLo[1]);
            double mz = pLo[2] + frac * (pHi[2] - pLo[2]);
            double sx = Math.sqrt(sq(sLo[0] + frac * (sHi[0] - sLo[0])) + varArrow);
            double sy = Math.sqrt(sq(sLo[1] + frac * (sHi[1] - sLo[1])) + varArrow);
            double sz = Math.sqrt(sq(sLo[2] + frac * (sHi[2] - sLo[2])) + varArrow);

            double px = prob1d(ax - mx - slackX, ax - mx + slackX, sx);
            double py = prob1d(ay - my - slackYHi, ay - my - slackYLo, sy);
            double pz = prob1d(az - mz - slackZ, az - mz + slackZ, sz);
            total += w * px * py * pz;
        }
        return Math.max(0.0, Math.min(1.0, total));
    }

    public double calibratedHitProb(Shot shot, Prediction pred) {
        return calibrate(rawHitProb(shot, pred));
    }

    public double calibrate(double raw) {
        if (raw <= 0.0) return 0.0;
        double idx = raw * (calibration.length - 1);
        int lo = (int)idx;
        if (lo >= calibration.length - 1) return calibration[calibration.length - 1];
        return calibration[lo] + (idx - lo) * (calibration[lo + 1] - calibration[lo]);
    }

    private static double prob1d(double lo, double hi, double sigma) {
        double d = INV_SQRT2 / Math.max(0.08, sigma);
        return 0.5 * (erf(hi * d) - erf(lo * d));
    }

    /** Abramowitz & Stegun 7.1.26 error function approximation (max error < 1.5e-7). */
    private static double erf(double x) {
        double sign = x < 0 ? -1.0 : 1.0;
        double ax = Math.abs(x);
        double t = 1.0 / (1.0 + 0.3275911 * ax);
        double y = 1.0 - (((((1.061405429 * t - 1.453152027) * t) + 1.421413741) * t
                - 0.284496736) * t + 0.254829592) * t * Math.exp(-ax * ax);
        return sign * y;
    }

    private static int srcVoxIndex(int rz, int rx, int q) {
        // Matches train_neural.py rot_vox(vox[dy, dz, dx], q) exactly!
        switch (q) {
            case 0: return rz * 9 + rx;
            case 1: return rx * 9 + (8 - rz);
            case 2: return (8 - rz) * 9 + (8 - rx);
            default: return (8 - rx) * 9 + rz;
        }
    }

    private static double rotX(double dx, double dz, int q) {
        switch (q) { case 0: return dx; case 1: return dz; case 2: return -dx; default: return -dz; }
    }

    private static double rotZ(double dx, double dz, int q) {
        switch (q) { case 0: return dz; case 1: return -dx; case 2: return -dz; default: return dx; }
    }

    private static double invRotX(double rx, double rz, int q) {
        switch (q) { case 0: return rx; case 1: return -rz; case 2: return -rx; default: return rz; }
    }

    private static double invRotZ(double rx, double rz, int q) {
        switch (q) { case 0: return rz; case 1: return rx; case 2: return -rz; default: return -rx; }
    }

    private static void fillMeanVel(Track track, int validPast, int a, int b, int q,
                                    float[] ctx, int offset, boolean withY) {
        int fa = Math.min(a, validPast - 1), fb = Math.min(b, validPast - 1);
        if (fb <= fa) return;
        double dt = fb - fa;
        double mx = (track.get(fa, 0) - track.get(fb, 0)) / dt;
        double my = (track.get(fa, 1) - track.get(fb, 1)) / dt;
        double mz = (track.get(fa, 2) - track.get(fb, 2)) / dt;
        double rx = rotX(mx, mz, q), rz = rotZ(mx, mz, q);
        if (withY) {
            ctx[offset] = (float)(rx * 3.0);
            ctx[offset + 1] = (float)(my * 3.0);
            ctx[offset + 2] = (float)(rz * 3.0);
        } else {
            ctx[offset] = (float)(rx * 3.0);
            ctx[offset + 1] = (float)(rz * 3.0);
        }
    }

    private static float[] conv1dSilu(float[] in, int inC, int inL, float[] w, float[] b, int outC, int stride) {
        int outL = (inL + 2 - 3) / stride + 1;
        float[] out = new float[outC * outL];
        for (int oc = 0; oc < outC; oc++) {
            float bias = b[oc];
            int wOcBase = oc * inC * 3;
            int outOcBase = oc * outL;
            for (int ot = 0; ot < outL; ot++) {
                float sum = bias;
                int center = ot * stride;
                for (int ic = 0; ic < inC; ic++) {
                    int inBase = ic * inL;
                    int wBase = wOcBase + ic * 3;
                    int t0 = center - 1, t1 = center, t2 = center + 1;
                    if (t0 >= 0) sum += in[inBase + t0] * w[wBase];
                    sum += in[inBase + t1] * w[wBase + 1];
                    if (t2 < inL) sum += in[inBase + t2] * w[wBase + 2];
                }
                out[outOcBase + ot] = silu(sum);
            }
        }
        return out;
    }

    private static float[] linear(float[] in, float[] w, float[] b, int outDim) {
        int inDim = in.length;
        float[] out = new float[outDim];
        for (int o = 0; o < outDim; o++) {
            float sum = b[o];
            int row = o * inDim;
            for (int i = 0; i < inDim; i++) sum += in[i] * w[row + i];
            out[o] = sum;
        }
        return out;
    }

    private static float[] linearSilu(float[] in, float[] w, float[] b, int outDim) {
        float[] out = linear(in, w, b, outDim);
        for (int i = 0; i < outDim; i++) out[i] = silu(out[i]);
        return out;
    }

    private static float silu(float x) { return (float)(x / (1.0 + Math.exp(-x))); }
    private static float clampF(double x, float lo, float hi) { return (float)Math.max(lo, Math.min(hi, x)); }
    private static double sq(double x) { return x * x; }
}
