package practice.core.bow;

import practice.core.bow.BowBallistics.Shot;
import practice.core.bow.BowMotion.Track;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.function.Predicate;

/**
 * Pure Java 8 inference for BowNeuralNetV2 (BNN2):
 * - 48-tick (2.4s) temporal Conv1d over 26 raw channels (pos, vel, unsmoothed serverPos delta, rot, flags, relational threat)
 * - 30-dim per-player online style & periodicity summary
 * - Multi-scale 3D voxels: fine 9x7x9 (+-4b) + coarse 11x5x11 (+-11b)
 * - K=6 correlated-horizontal modes (sigma_x, sigma_z, rho_xz) + exact 5-point Gauss-Legendre hitbox probability
 * - Continuous multi-mode (yaw, pitch) coordinate-ascent refinement & own-ping lead compensation
 */
public final class BowNeuralMotion {
    public static final int MAGIC = 0x424E4E32; // 'BNN2'
    public static final int HIST_LEN = 48, HIST_CH = 26, CTX_DIM = 30;
    public static final int FINE_DIM = 7 * 9 * 9;      // 567
    public static final int COARSE_DIM = 5 * 11 * 11;  // 605
    public static final int VOX_DIM = FINE_DIM;        // backward alias
    private static final int[] AUTOCORR_LAGS = {12, 16, 20, 24, 28, 34, 42, 52};
    private static final double SPREAD = 0.007499999832361937;
    private static final double INV_SQRT2 = 0.7071067811865475;
    private static final double INV_SQRT2PI = 0.3989422804014327;
    private static final double[] GL_T = {-0.9061798459, -0.5384693101, 0.0, 0.5384693101, 0.9061798459};
    private static final double[] GL_W = {0.2369268851, 0.4786286705, 0.5688888889, 0.4786286705, 0.2369268851};

    private final int numModes;
    private final int[] horizons;
    private final float[] calibration;

    private final float[] c1w, c1b, c2w, c2b, c3w, c3b, c4w, c4b;
    private final float[] hpW, hpB, vfW, vfB, vcW, vcB, vFuseW, vFuseB;
    private final float[] trW, trB, r1aW, r1aB, r1bW, r1bB, r2aW, r2aB, r2bW, r2bB;
    private final float[] logW, logB, meanW, meanB, stdW, stdB, rhoW, rhoB;

    public static final class Prediction {
        public final double[] weights;
        public final double[][][] paths; // [K][maxHorizon+1][3]
        public final double[][][] stds;  // [K][maxHorizon+1][3]
        public final double[][] rhos;    // [K][maxHorizon+1]
        public final double[][] meanPath;
        public final float[] trunk224;   // trunk embedding (224 for v2, 256 for v4)
        public final int cardinalQ;
        public final float[] futBlk245;  // optional 5x7x7 future-block-placement probs (v4)

        public Prediction(double[] weights, double[][][] paths, double[][][] stds, double[][] rhos,
                          double[][] meanPath, float[] trunk, int cardinalQ) {
            this(weights, paths, stds, rhos, meanPath, trunk, cardinalQ, null);
        }

        public Prediction(double[] weights, double[][][] paths, double[][][] stds, double[][] rhos,
                          double[][] meanPath, float[] trunk, int cardinalQ, float[] futBlk245) {
            this.weights = weights;
            this.paths = paths;
            this.stds = stds;
            this.rhos = rhos;
            this.meanPath = meanPath;
            this.trunk224 = trunk;
            this.cardinalQ = cardinalQ;
            this.futBlk245 = futBlk245;
        }

        public int maxHorizon() { return paths[0].length - 1; }
    }

    private BowNeuralMotion(DataInputStream in) throws IOException {
        if (in.readInt() != MAGIC) throw new IOException("Invalid bow neural v2 model magic");
        numModes = in.readInt();
        int numH = in.readInt();
        int calLen = in.readInt();
        if (numModes < 1 || numModes > 16 || numH < 1 || numH > 16 || calLen < 2 || calLen > 64)
            throw new IOException("Invalid bow neural v2 header");
        horizons = new int[numH];
        for (int i = 0; i < numH; i++) horizons[i] = in.readInt();
        calibration = readFloats(in, calLen);

        c1w = readFloats(in, 48 * HIST_CH * 3); c1b = readFloats(in, 48);
        c2w = readFloats(in, 64 * 48 * 3);      c2b = readFloats(in, 64);
        c3w = readFloats(in, 80 * 64 * 3);      c3b = readFloats(in, 80);
        c4w = readFloats(in, 96 * 80 * 3);      c4b = readFloats(in, 96);
        hpW = readFloats(in, 128 * (96 * 3));   hpB = readFloats(in, 128);
        vfW = readFloats(in, 64 * FINE_DIM);    vfB = readFloats(in, 64);
        vcW = readFloats(in, 64 * COARSE_DIM);  vcB = readFloats(in, 64);
        vFuseW = readFloats(in, 96 * 128);      vFuseB = readFloats(in, 96);
        trW = readFloats(in, 224 * (128 + CTX_DIM + 96)); trB = readFloats(in, 224);
        r1aW = readFloats(in, 224 * 224);       r1aB = readFloats(in, 224);
        r1bW = readFloats(in, 224 * 224);       r1bB = readFloats(in, 224);
        r2aW = readFloats(in, 224 * 224);       r2aB = readFloats(in, 224);
        r2bW = readFloats(in, 224 * 224);       r2bB = readFloats(in, 224);
        logW = readFloats(in, numModes * 224);  logB = readFloats(in, numModes);
        meanW = readFloats(in, numModes * numH * 3 * 224); meanB = readFloats(in, numModes * numH * 3);
        stdW = readFloats(in, numModes * numH * 3 * 224);  stdB = readFloats(in, numModes * numH * 3);
        rhoW = readFloats(in, numModes * numH * 224);      rhoB = readFloats(in, numModes * numH);
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
            if (!Float.isFinite(v)) throw new IOException("Non-finite weight in bow neural v2 model");
            arr[i] = v;
        }
        return arr;
    }

    public int modes() { return numModes; }

    public Prediction predict(Track track, byte[] fineVox567, byte[] coarseVox605, boolean hasTerrain) {
        return predict(track, fineVox567, coarseVox605, hasTerrain, true);
    }

    public Prediction predict(Track track, byte[] fineVox567, byte[] coarseVox605, boolean hasTerrain, boolean withCycle) {
        double p0x = track.get(0, 0), p0y = track.get(0, 1), p0z = track.get(0, 2);
        float yaw0 = track.yaw(0);
        int q = ((int)Math.floor((yaw0 + 45.0) / 90.0)) & 3;
        double qDeg = q * 90.0;
        int validPast = track.length();
        int maxAge = Math.min(validPast - 1, HIST_LEN - 1);
        boolean hasRel = track.hasRel(0);

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

            double sdx = track.serverGet(cAge, 0) - track.get(cAge, 0);
            double sdy = track.serverGet(cAge, 1) - track.get(cAge, 1);
            double sdz = track.serverGet(cAge, 2) - track.get(cAge, 2);
            double srx = rotX(sdx, sdz, q), srz = rotZ(sdx, sdz, q);

            double relYaw = Math.toRadians(track.yaw(cAge) - qDeg);
            double pit = track.pitch(cAge) / 90.0;
            int fl = track.flags(cAge);

            hist[0 * HIST_LEN + age] = clampF(rx / 4.0, -4f, 4f);
            hist[1 * HIST_LEN + age] = clampF(dy / 4.0, -4f, 4f);
            hist[2 * HIST_LEN + age] = clampF(rz / 4.0, -4f, 4f);
            hist[3 * HIST_LEN + age] = clampF(vrx * 3.0, -3f, 3f);
            hist[4 * HIST_LEN + age] = clampF(vdy * 3.0, -3f, 3f);
            hist[5 * HIST_LEN + age] = clampF(vrz * 3.0, -3f, 3f);
            hist[6 * HIST_LEN + age] = clampF(srx * 3.0, -3f, 3f);
            hist[7 * HIST_LEN + age] = clampF(sdy * 3.0, -3f, 3f);
            hist[8 * HIST_LEN + age] = clampF(srz * 3.0, -3f, 3f);
            hist[9 * HIST_LEN + age] = (float)Math.sin(relYaw);
            hist[10 * HIST_LEN + age] = (float)Math.cos(relYaw);
            hist[11 * HIST_LEN + age] = clampF(pit, -1f, 1f);
            hist[12 * HIST_LEN + age] = (fl & 1) != 0 ? 1f : 0f;
            hist[13 * HIST_LEN + age] = (fl & 2) != 0 ? 1f : 0f;
            hist[14 * HIST_LEN + age] = (fl & 12) != 0 ? 1f : 0f;
            hist[15 * HIST_LEN + age] = (fl & 32) != 0 ? 1f : 0f;
            hist[16 * HIST_LEN + age] = (fl & 80) != 0 ? 1f : 0f;
            hist[17 * HIST_LEN + age] = age <= maxAge ? 1f : 0f;

            if (hasRel && track.hasRel(cAge)) {
                float[] r = track.rel(cAge);
                double nrx = rotX(r[0], r[2], q), nrz = rotZ(r[0], r[2], q);
                double rrx = rotX(r[5], r[7], q), rrz = rotZ(r[5], r[7], q);
                double rHd = Math.max(1.0, Math.hypot(rrx, rrz));
                double rProx = r[8];
                hist[18 * HIST_LEN + age] = clampF(nrx / 8.0, -4f, 4f);
                hist[19 * HIST_LEN + age] = clampF(r[1] / 8.0, -4f, 4f);
                hist[20 * HIST_LEN + age] = clampF(nrz / 8.0, -4f, 4f);
                hist[21 * HIST_LEN + age] = r[3];
                hist[22 * HIST_LEN + age] = r[4];
                hist[23 * HIST_LEN + age] = (float)((rrx / rHd) * rProx);
                hist[24 * HIST_LEN + age] = (float)((rrz / rHd) * rProx);
                hist[25 * HIST_LEN + age] = r[9];
            }
        }

        // 2) Long-term style & periodicity summary (30 floats)
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
                sum += s; sumSq += s * s;
            }
            double mean = sum / nSp;
            ctx[10] = (float)(mean * 3.0);
            ctx[11] = (float)(Math.sqrt(Math.max(0.0, sumSq / nSp - mean * mean)) * 3.0);
        }

        int nAc = Math.min(110, validPast - 4);
        if (nAc >= 16) {
            double[] vx3 = new double[nAc], vz3 = new double[nAc];
            double mx = 0, mz = 0;
            for (int a = 0; a < nAc; a++) {
                vx3[a] = track.get(a, 0) - track.get(a + 3, 0);
                vz3[a] = track.get(a, 2) - track.get(a + 3, 2);
                mx += vx3[a]; mz += vz3[a];
            }
            mx /= nAc; mz /= nAc;
            double energy = 0;
            for (int a = 0; a < nAc; a++) {
                vx3[a] -= mx; vz3[a] -= mz;
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
        ctx[21] = (hasTerrain && fineVox567 != null) ? 1f : 0f;

        int nSt = Math.min(160, validPast - 1);
        if (nSt >= 4) {
            int jumps = 0, sneak = 0, blk = 0, hurt = 0;
            for (int a = 0; a < nSt; a++) {
                if (track.get(a, 1) - track.get(a + 1, 1) > 0.25) jumps++;
            }
            for (int a = 0; a <= nSt; a++) {
                int fl = track.flags(a);
                if ((fl & 1) != 0) sneak++;
                if ((fl & 2) != 0) blk++;
                if ((fl & 32) != 0) hurt++;
            }
            ctx[22] = (float)jumps / nSt;
            if (nSt >= 8) {
                int rev = 0, totRev = nSt - 7;
                for (int a = 0; a < totRev; a++) {
                    double v1x = track.get(a, 0) - track.get(a + 4, 0);
                    double v1z = track.get(a, 2) - track.get(a + 4, 2);
                    double v2x = track.get(a + 4, 0) - track.get(a + 8, 0);
                    double v2z = track.get(a + 4, 2) - track.get(a + 8, 2);
                    double mags = Math.hypot(v1x, v1z) * Math.hypot(v2x, v2z);
                    if (mags > 0.04 && (v1x * v2x + v1z * v2z) < 0.0) rev++;
                }
                ctx[23] = (float)rev / totRev;
            }
            ctx[24] = (float)sneak / (nSt + 1);
            ctx[25] = (float)blk / (nSt + 1);
            ctx[26] = (float)hurt / (nSt + 1);
        }
        ctx[27] = (float)(Math.hypot(track.serverGet(0, 0) - p0x, track.serverGet(0, 2) - p0z) * 3.0);
        ctx[28] = hasRel ? 1f : 0f;
        ctx[29] = hasRel ? track.rel(0)[3] : 0f;
        for (int i = 0; i < CTX_DIM; i++) ctx[i] = clampF(ctx[i], -4f, 4f);

        // 3) 4-layer temporal Conv1d
        float[] h1 = conv1dSilu(hist, HIST_CH, HIST_LEN, c1w, c1b, 48, 1); // 48 x 48
        float[] h2 = conv1dSilu(h1, 48, 48, c2w, c2b, 64, 2);              // 64 x 24
        float[] h3 = conv1dSilu(h2, 64, 24, c3w, c3b, 80, 2);              // 80 x 12
        float[] h4 = conv1dSilu(h3, 80, 12, c4w, c4b, 96, 2);              // 96 x 6
        float[] hPool = new float[96 * 3];
        for (int c = 0; c < 96; c++) {
            int base = c * 6;
            float first = h4[base], sum = 0f, mx = -Float.MAX_VALUE;
            for (int t = 0; t < 6; t++) {
                float v = h4[base + t]; sum += v; if (v > mx) mx = v;
            }
            hPool[c] = first;
            hPool[96 + c] = sum / 6f;
            hPool[192 + c] = mx;
        }
        float[] hEmb = linearSilu(hPool, hpW, hpB, 128);

        // 4) Multi-scale sparse binary 3D voxel branch
        float[] vfOut = new float[64];
        System.arraycopy(vfB, 0, vfOut, 0, 64);
        if (hasTerrain && fineVox567 != null && fineVox567.length == FINE_DIM) {
            for (int dy = 0; dy < 7; dy++) {
                int yOff = dy * 81;
                for (int rz = 0; rz < 9; rz++) {
                    for (int rx = 0; rx < 9; rx++) {
                        if (fineVox567[yOff + srcVoxIndex(rz, rx, 9, q)] != 0) {
                            int feat = yOff + rz * 9 + rx;
                            for (int o = 0; o < 64; o++) vfOut[o] += vfW[o * FINE_DIM + feat];
                        }
                    }
                }
            }
        }
        for (int o = 0; o < 64; o++) vfOut[o] = silu(vfOut[o]);

        float[] vcOut = new float[64];
        System.arraycopy(vcB, 0, vcOut, 0, 64);
        if (hasTerrain && coarseVox605 != null && coarseVox605.length == COARSE_DIM) {
            for (int dy = 0; dy < 5; dy++) {
                int yOff = dy * 121;
                for (int rz = 0; rz < 11; rz++) {
                    for (int rx = 0; rx < 11; rx++) {
                        if (coarseVox605[yOff + srcVoxIndex(rz, rx, 11, q)] != 0) {
                            int feat = yOff + rz * 11 + rx;
                            for (int o = 0; o < 64; o++) vcOut[o] += vcW[o * COARSE_DIM + feat];
                        }
                    }
                }
            }
        }
        for (int o = 0; o < 64; o++) vcOut[o] = silu(vcOut[o]);

        float[] vBoth = new float[128];
        System.arraycopy(vfOut, 0, vBoth, 0, 64);
        System.arraycopy(vcOut, 0, vBoth, 64, 64);
        float[] vEmb = linearSilu(vBoth, vFuseW, vFuseB, 96);

        // 5) 2-block residual fusion trunk
        float[] fused = new float[128 + CTX_DIM + 96];
        System.arraycopy(hEmb, 0, fused, 0, 128);
        System.arraycopy(ctx, 0, fused, 128, CTX_DIM);
        System.arraycopy(vEmb, 0, fused, 128 + CTX_DIM, 96);

        float[] x = linearSilu(fused, trW, trB, 224);
        float[] r1 = linear(linearSilu(x, r1aW, r1aB, 224), r1bW, r1bB, 224);
        for (int i = 0; i < 224; i++) x[i] = silu(x[i] + r1[i]);
        float[] r2 = linear(linearSilu(x, r2aW, r2aB, 224), r2bW, r2bB, 224);
        for (int i = 0; i < 224; i++) x[i] = silu(x[i] + r2[i]);

        // 6) Correlated multi-modal heads
        float[] logits = linear(x, logW, logB, numModes);
        int numH = horizons.length;
        float[] meansFlat = linear(x, meanW, meanB, numModes * numH * 3);
        float[] logstdFlat = linear(x, stdW, stdB, numModes * numH * 3);
        float[] rhoFlat = linear(x, rhoW, rhoB, numModes * numH);

        double[] weights = new double[numModes];
        double maxL = Double.NEGATIVE_INFINITY;
        for (int k = 0; k < numModes; k++) if (logits[k] > maxL) maxL = logits[k];
        double sumW = 0;
        for (int k = 0; k < numModes; k++) { weights[k] = Math.exp(logits[k] - maxL); sumW += weights[k]; }
        for (int k = 0; k < numModes; k++) weights[k] /= sumW;

        int maxT = BowBallistics.MAX_TICKS;
        double[][][] paths = new double[numModes][maxT + 1][3];
        double[][][] stds = new double[numModes][maxT + 1][3];
        double[][] rhos = new double[numModes][maxT + 1];
        double[][] meanPath = new double[maxT + 1][3];
        double[][] ancPos = new double[numH + 1][3];
        double[][] ancStd = new double[numH + 1][3];
        double[] ancRho = new double[numH + 1];

        for (int k = 0; k < numModes; k++) {
            ancPos[0][0] = p0x; ancPos[0][1] = p0y; ancPos[0][2] = p0z;
            ancStd[0][0] = 0.05; ancStd[0][1] = 0.05; ancStd[0][2] = 0.05;
            ancRho[0] = 0.0;
            for (int hi = 0; hi < numH; hi++) {
                int base = (k * numH + hi) * 3;
                double rx = meansFlat[base], ry = meansFlat[base + 1], rz = meansFlat[base + 2];
                ancPos[hi + 1][0] = p0x + invRotX(rx, rz, q);
                ancPos[hi + 1][1] = p0y + ry;
                ancPos[hi + 1][2] = p0z + invRotZ(rx, rz, q);
                double sx = Math.exp(Math.max(-2.1, Math.min(2.1, logstdFlat[base])));
                double sy = Math.exp(Math.max(-2.1, Math.min(2.1, logstdFlat[base + 1])));
                double sz = Math.exp(Math.max(-2.1, Math.min(2.1, logstdFlat[base + 2])));
                double rVal = 0.90 * Math.tanh(rhoFlat[k * numH + hi]);
                if (q == 1 || q == 3) {
                    ancStd[hi + 1][0] = sz; ancStd[hi + 1][2] = sx; ancRho[hi + 1] = -rVal;
                } else {
                    ancStd[hi + 1][0] = sx; ancStd[hi + 1][2] = sz; ancRho[hi + 1] = rVal;
                }
                ancStd[hi + 1][1] = sy;
            }
            paths[k][0][0] = p0x; paths[k][0][1] = p0y; paths[k][0][2] = p0z;
            stds[k][0][0] = 0.05; stds[k][0][1] = 0.05; stds[k][0][2] = 0.05;
            rhos[k][0] = 0.0;
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
                rhos[k][t] = ancRho[hi] * (1.0 - u) + ancRho[hi + 1] * u;
            }
        }
        meanPath[0][0] = p0x; meanPath[0][1] = p0y; meanPath[0][2] = p0z;
        if (withCycle) {
            if (!applyBridgeAndSteadyContinuation(track, weights, paths, stds, rhos, meanPath)) {
                applyPeriodicCycle(track, paths, meanPath, 0.22);
            }
        }
        return new Prediction(weights, paths, stds, rhos, meanPath, x, q);
    }

    /**
     * Extend a 43-tick path out to maxTicks (e.g. 72 for long-range snowballs up to 55b)
     * with smooth velocity continuation from ticks [36..42], plus optional RTT shift.
     */
    public static double[][] shiftAndExtendPath(double[][] path, double shiftTicks, int maxTicks) {
        int srcLast = path.length - 1;
        int ref = Math.max(0, srcLast - 6);
        double dtRef = Math.max(1, srcLast - ref);
        double evx = (path[srcLast][0] - path[ref][0]) / dtRef;
        double evy = (path[srcLast][1] - path[ref][1]) / dtRef;
        double evz = (path[srcLast][2] - path[ref][2]) / dtRef;
        double[][] out = new double[maxTicks + 1][3];
        for (int t = 0; t <= maxTicks; t++) {
            double ts = t + Math.max(0.0, shiftTicks);
            if (ts <= srcLast) {
                int lo = (int)ts, hi = Math.min(srcLast, lo + 1);
                double f = ts - lo;
                for (int a = 0; a < 3; a++) out[t][a] = path[lo][a] * (1.0 - f) + path[hi][a] * f;
            } else {
                double extra = ts - srcLast;
                out[t][0] = path[srcLast][0] + evx * extra;
                out[t][1] = path[srcLast][1] + evy * extra;
                out[t][2] = path[srcLast][2] + evz * extra;
            }
        }
        return out;
    }

    /** Sample mode k's predicted (pos, std, rho) at any tick in [0..MAX_THROW_TICKS] with clean extrapolation past maxHorizon. */
    private static void sampleModeState(Prediction pred, int k, double tEval,
                                        double[] outPos, double[] outStd, double[] outRho) {
        int maxT = pred.maxHorizon();
        double tc = Math.max(0.0, tEval);
        if (tc <= maxT) {
            int lo = (int)tc, hi = Math.min(maxT, lo + 1);
            double frac = tc - lo;
            double[] pLo = pred.paths[k][lo], pHi = pred.paths[k][hi];
            double[] sLo = pred.stds[k][lo], sHi = pred.stds[k][hi];
            for (int a = 0; a < 3; a++) {
                outPos[a] = pLo[a] + frac * (pHi[a] - pLo[a]);
                outStd[a] = sLo[a] + frac * (sHi[a] - sLo[a]);
            }
            outRho[0] = pred.rhos[k][lo] + frac * (pred.rhos[k][hi] - pred.rhos[k][lo]);
            return;
        }
        double extra = tc - maxT;
        int ref = Math.max(0, maxT - 6);
        double dtRef = Math.max(1.0, maxT - ref);
        double[] pLast = pred.paths[k][maxT], pRef = pred.paths[k][ref];
        double[] sLast = pred.stds[k][maxT];
        for (int a = 0; a < 3; a++) {
            double v = (pLast[a] - pRef[a]) / dtRef;
            outPos[a] = pLast[a] + v * extra;
            outStd[a] = sLast[a] * (1.0 + extra / (double)maxT);
        }
        outRho[0] = pred.rhos[k][maxT];
    }

    /** Public static access to the optional explicit bridging/steady continuation overlay (/p bowaim bridgehack). */
    public static boolean applyBridgeHack(Track track, Prediction pred) {
        return applyBridgeAndSteadyContinuation(track, pred.weights, pred.paths, pred.stds, pred.rhos, pred.meanPath);
    }

    /**
     * Exact physics-grounded multi-speed continuation for active bridging (sideways constant-speed,
     * variable speedbridging, and diagonal-up stair speedbridging into empty void air where the
     * future blocks do not exist yet in the voxel grid).
     *
     * Anchors at unsmoothed serverPos(0) to remove 1.8.9's 2*v 3-tick remote interpolation lag,
     * uses floor-envelope climb rate for periodic stair jumps, and emits a calibrated speed-bracket
     * distribution across modes [1.00x nominal, 1.28x fast, 0.68x slow, 0.15x pause] so rapid
     * snowball/egg volleys automatically cover the entire speed distribution for a confirmed hit.
     */
    private static boolean applyBridgeAndSteadyContinuation(Track track, double[] weights,
                                                            double[][][] paths, double[][][] stds,
                                                            double[][] rhos, double[][] meanPath) {
        int len = track.length();
        if (len < 16 || paths.length < 4) return false;

        // 1. Detect active bridging (holding block + looking down >= 32 deg, or sneaking with block)
        int lookDownBlock = 0, blockTicks = 0, sneakTicks = 0;
        int win = Math.min(18, len);
        for (int a = 0; a < win; a++) {
            int fl = track.flags(a);
            boolean hasBlk = (fl & 2) != 0;
            boolean sneak = (fl & 1) != 0;
            if (hasBlk) blockTicks++;
            if (sneak) sneakTicks++;
            if (hasBlk && track.pitch(a) >= 32f) lookDownBlock++;
        }
        boolean activeBridgePose = (track.flags(0) & 2) != 0
                && (lookDownBlock >= Math.min(6, win / 2)
                    || (blockTicks >= Math.min(10, win - 2) && sneakTicks >= 3));

        // 2. Measure steady velocity & straightness over W = 16..24 ticks using unsmoothed serverPos!
        int W = Math.min(24, len - 1);
        int half = W / 2;
        double sx0 = track.serverGet(0, 0), sy0 = track.serverGet(0, 1), sz0 = track.serverGet(0, 2);
        double netDx = sx0 - track.serverGet(W, 0);
        double netDz = sz0 - track.serverGet(W, 2);
        double netDist = Math.hypot(netDx, netDz);
        double vxAll = netDx / W, vzAll = netDz / W;
        double spdAll = netDist / W;

        // Compare recent half vs older half to ensure they are truly continuing steadily (not turning/stopping!)
        double vxRec = (sx0 - track.serverGet(half, 0)) / half;
        double vzRec = (sz0 - track.serverGet(half, 2)) / half;
        double vxOld = (track.serverGet(half, 0) - track.serverGet(W, 0)) / (W - half);
        double vzOld = (track.serverGet(half, 2) - track.serverGet(W, 2)) / (W - half);
        double spdRec = Math.hypot(vxRec, vzRec);
        double spdOld = Math.hypot(vxOld, vzOld);

        // Require meaningful horizontal movement (0.045..0.35 b/t) and consistent direction!
        if (spdAll < 0.045 || spdAll > 0.36 || spdRec < 0.035 || spdOld < 0.030) return false;
        double dirCos = (vxRec * vxOld + vzRec * vzOld) / Math.max(1e-6, spdRec * spdOld);

        // Straightness check: path length vs net displacement (allows small zig-zag / shift-tap cadence)
        double pathLen = 0.0;
        for (int a = 0; a < W; a++) {
            pathLen += Math.hypot(track.get(a, 0) - track.get(a + 1, 0), track.get(a, 2) - track.get(a + 1, 2));
        }
        double straightness = netDist / Math.max(1e-6, pathLen);

        // Floor-envelope vertical climb rate (robust to periodic stair-jump arcs!)
        double minYRecent = Double.POSITIVE_INFINITY, minYOld = Double.POSITIVE_INFINITY;
        int fWin = Math.min(8, half);
        for (int a = 0; a < fWin; a++) minYRecent = Math.min(minYRecent, track.serverGet(a, 1));
        for (int a = W - fWin; a <= W; a++) minYOld = Math.min(minYOld, track.serverGet(a, 1));
        double floorDt = Math.max(1.0, W - fWin);
        double stairVy = (minYRecent - minYOld) / floorDt;
        // Also check mid-window monotonic rise for stair bridging
        boolean stairClimbing = activeBridgePose && stairVy >= 0.055 && stairVy <= 0.32;
        if (!stairClimbing) stairVy = 0.0;

        // Gate: either active bridging pose (relaxed straightness for ninja/stair cadence)
        // OR ultra-clean constant-speed straight-line running (e.g. sprinting down a bridge without block in hand)
        boolean steadyStraight = !activeBridgePose && dirCos >= 0.97 && straightness >= 0.94
                && Math.abs(spdRec - spdOld) / Math.max(0.05, spdAll) <= 0.22
                && Math.abs(minYRecent - minYOld) < 0.45;
        boolean bridgeMatch = activeBridgePose && dirCos >= 0.86 && straightness >= 0.80;
        if (!bridgeMatch && !steadyStraight) return false;

        // Estimate observed speed variability to calibrate how wide the speed-bracket fan should be
        double spdDiff = Math.abs(spdRec - spdOld) / Math.max(0.05, spdAll);
        // Check 3-tick speed oscillation (classic speedbridge shift-tap rhythm)
        double spdSum = 0, spdSq = 0;
        int nSeg = Math.min(18, len - 3);
        for (int a = 0; a < nSeg; a++) {
            double s3 = Math.hypot(track.serverGet(a, 0) - track.serverGet(a + 3, 0),
                    track.serverGet(a, 2) - track.serverGet(a + 3, 2)) / 3.0;
            spdSum += s3; spdSq += s3 * s3;
        }
        double sMean = spdSum / Math.max(1, nSeg);
        double sCv = Math.sqrt(Math.max(0.0, spdSq / Math.max(1, nSeg) - sMean * sMean)) / Math.max(0.05, sMean);

        // Use weighted blend of recent and full-window velocity (fast response + smooth cadence averaging)
        double vx = 0.55 * vxRec + 0.45 * vxAll;
        double vz = 0.55 * vzRec + 0.45 * vzAll;
        double baseY = stairClimbing ? (minYRecent + 0.30) : sy0;

        // Calibrate multi-speed bracket multipliers using physical Minecraft bridging speed bounds
        // (a slow sneak-bridger can burst +0.07 b/t by unsneaking, while max walking/bridging speed caps ~0.27 b/t)
        double curSpd = Math.hypot(vx, vz);
        double spreadScale = Math.max(0.22, Math.min(0.42, 0.20 + 0.45 * sCv + 0.25 * spdDiff));
        double fastSpd = bridgeMatch
                ? Math.min(0.275, Math.max(curSpd * (1.0 + spreadScale), curSpd + 0.075))
                : Math.min(0.32, curSpd * (1.0 + spreadScale));
        double fastMult = Math.max(1.08, fastSpd / Math.max(0.04, curSpd));
        double slowMult = Math.max(0.42, 1.00 - spreadScale);
        double[] mults = {1.00, fastMult, slowMult, 0.12};
        double[] bridgeWeights = bridgeMatch
                ? new double[]{0.38, 0.26, 0.24, 0.12}
                : new double[]{0.44, 0.24, 0.20, 0.12};
        // Blend strength: 1.0 for clear active bridging / stair bridging, 0.65 for general steady straight line
        double blend = bridgeMatch ? 1.0 : 0.65;

        int maxT = paths[0].length - 1;
        int K = paths.length;
        double ux = vx / Math.max(1e-6, Math.hypot(vx, vz));
        double uz = vz / Math.max(1e-6, Math.hypot(vx, vz));

        // Reassign weights: first 4 modes carry the calibrated multi-speed bridge brackets, remaining keep neural diversity
        double neuralTailW = 0.0;
        for (int k = 4; k < K; k++) neuralTailW += weights[k];
        double keepTail = bridgeMatch ? Math.min(0.10, neuralTailW) : Math.min(0.25, neuralTailW);
        double bridgeMass = 1.0 - keepTail;
        for (int k = 0; k < 4; k++) weights[k] = bridgeMass * bridgeWeights[k];
        if (K > 4) {
            if (neuralTailW > 1e-6) {
                for (int k = 4; k < K; k++) weights[k] = keepTail * (weights[k] / neuralTailW);
            } else {
                for (int k = 4; k < K; k++) weights[k] = keepTail / (K - 4);
            }
        }

        for (int k = 0; k < 4; k++) {
            double m = mults[k];
            for (int t = 1; t <= maxT; t++) {
                double bx = sx0 + vx * m * t;
                double by = baseY + stairVy * m * t;
                double bz = sz0 + vz * m * t;
                paths[k][t][0] = paths[k][t][0] * (1.0 - blend) + bx * blend;
                paths[k][t][1] = paths[k][t][1] * (1.0 - blend) + by * blend;
                paths[k][t][2] = paths[k][t][2] * (1.0 - blend) + bz * blend;

                // Tight perpendicular uncertainty, realistic along-bridge speed uncertainty
                double sigAlong = 0.14 + (0.012 + 0.025 * sCv) * t;
                double sigPerp = 0.12 + 0.008 * t;
                double sigY = stairClimbing ? (0.30 + 0.014 * t) : (0.14 + 0.006 * t);
                double sX = Math.hypot(ux * sigAlong, uz * sigPerp);
                double sZ = Math.hypot(uz * sigAlong, ux * sigPerp);
                stds[k][t][0] = stds[k][t][0] * (1.0 - blend) + sX * blend;
                stds[k][t][1] = stds[k][t][1] * (1.0 - blend) + sigY * blend;
                stds[k][t][2] = stds[k][t][2] * (1.0 - blend) + sZ * blend;
                double rhoBridge = (ux * uz * (sigAlong * sigAlong - sigPerp * sigPerp))
                        / Math.max(1e-5, sX * sZ);
                rhoBridge = Math.max(-0.85, Math.min(0.85, rhoBridge));
                rhos[k][t] = rhos[k][t] * (1.0 - blend) + rhoBridge * blend;
            }
        }
        // Recompute meanPath cleanly from updated weights & paths
        for (int t = 1; t <= maxT; t++) {
            double mx = 0, my = 0, mz = 0;
            for (int k = 0; k < K; k++) {
                mx += weights[k] * paths[k][t][0];
                my += weights[k] * paths[k][t][1];
                mz += weights[k] * paths[k][t][2];
            }
            meanPath[t][0] = mx; meanPath[t][1] = my; meanPath[t][2] = mz;
        }
        return true;
    }

    /** Shift a trajectory forward by leadShiftTicks (e.g. ownPingMs / 50.0) for server RTT compensation. */
    public static double[][] shiftPath(double[][] path, double shiftTicks) {
        if (shiftTicks <= 1e-4) return path;
        int n = path.length;
        double[][] out = new double[n][3];
        for (int t = 0; t < n; t++) {
            double ts = Math.min(n - 1, t + shiftTicks);
            int lo = (int)ts, hi = Math.min(n - 1, lo + 1);
            double f = ts - lo;
            for (int a = 0; a < 3; a++) out[t][a] = path[lo][a] * (1.0 - f) + path[hi][a] * f;
        }
        return out;
    }

    public double rawHitProb(Shot shot, Prediction pred) {
        return rawHitProb(shot, pred, 0.0);
    }

    public double rawHitProb(Shot shot, Prediction pred, double leadShiftTicks) {
        double[] mp = modeHitProbs(shot, pred, leadShiftTicks);
        if (mp == null) return 0.0;
        double total = 0.0;
        for (int k = 0; k < numModes; k++) total += pred.weights[k] * mp[k];
        return Math.max(0.0, Math.min(1.0, total));
    }

    /** Per-mode hit probabilities P_k(hit | shot) for both arrows and throwables (supports up to MAX_THROW_TICKS). */
    public double[] modeHitProbs(Shot shot, Prediction pred, double leadShiftTicks) {
        if (shot == null || pred == null) return null;
        double tHit = shot.time;
        int maxAllowed = shot.gravity < 0.04 ? BowBallistics.MAX_THROW_TICKS : BowBallistics.MAX_TICKS;
        if (tHit <= 0.0 || tHit > maxAllowed) return null;
        double tr = BowBallistics.travel(tHit);
        double ax = shot.x + shot.vx * tr;
        double ay = shot.y + shot.vy * tr - BowBallistics.fall(tHit, shot.gravity);
        double az = shot.z + shot.vz * tr;
        double spd = Math.sqrt(shot.vx * shot.vx + shot.vy * shot.vy + shot.vz * shot.vz);
        double dragT = Math.pow(BowBallistics.DRAG, tHit);
        double ivx = shot.vx * dragT;
        double ivy = shot.vy * dragT - shot.gravity * (1.0 - dragT) / (1.0 - BowBallistics.DRAG);
        double ivz = shot.vz * dragT;
        double sigProj = SPREAD * spd * tr;
        double varProj = sigProj * sigProj;

        double tEval = Math.min(maxAllowed, tHit + Math.max(0.0, leadShiftTicks));
        double[] out = new double[numModes];
        double[] mu = new double[3], st = new double[3], rhoArr = new double[1];
        for (int k = 0; k < numModes; k++) {
            if (pred.weights[k] < 1e-4) continue;
            sampleModeState(pred, k, tEval, mu, st, rhoArr);
            double covXz = rhoArr[0] * st[0] * st[2];
            double sx = Math.sqrt(st[0] * st[0] + varProj);
            double sy = Math.sqrt(st[1] * st[1] + varProj);
            double sz = Math.sqrt(st[2] * st[2] + varProj);
            double rhoEff = Math.max(-0.92, Math.min(0.92, covXz / Math.max(1e-6, sx * sz)));
            out[k] = correlatedBoxProb(ax, ay, az, mu[0], mu[1], mu[2], sx, sy, sz, rhoEff, ivx, ivy, ivz);
        }
        return out;
    }

    /**
     * Evaluate probability that an already-airborne projectile (landing at shot.time) hits mode k
     * at future offset evalTick relative to the target's current predicted trajectory.
     */
    public double modeHitProbAt(Shot shot, Prediction pred, int k, double evalTick) {
        if (shot == null || pred == null || k < 0 || k >= numModes) return 0.0;
        double tHit = shot.time;
        int maxAllowed = shot.gravity < 0.04 ? BowBallistics.MAX_THROW_TICKS : BowBallistics.MAX_TICKS;
        if (tHit <= 0.0 || tHit > maxAllowed || evalTick <= 0.0 || evalTick > maxAllowed) {
            return 0.0;
        }
        double tr = BowBallistics.travel(tHit);
        double ax = shot.x + shot.vx * tr;
        double ay = shot.y + shot.vy * tr - BowBallistics.fall(tHit, shot.gravity);
        double az = shot.z + shot.vz * tr;
        double spd = Math.sqrt(shot.vx * shot.vx + shot.vy * shot.vy + shot.vz * shot.vz);
        double dragT = Math.pow(BowBallistics.DRAG, tHit);
        double ivx = shot.vx * dragT;
        double ivy = shot.vy * dragT - shot.gravity * (1.0 - dragT) / (1.0 - BowBallistics.DRAG);
        double ivz = shot.vz * dragT;
        double sigProj = SPREAD * spd * tr;
        double varProj = sigProj * sigProj;

        double[] mu = new double[3], st = new double[3], rhoArr = new double[1];
        sampleModeState(pred, k, evalTick, mu, st, rhoArr);
        double covXz = rhoArr[0] * st[0] * st[2];
        double sx = Math.sqrt(st[0] * st[0] + varProj);
        double sy = Math.sqrt(st[1] * st[1] + varProj);
        double sz = Math.sqrt(st[2] * st[2] + varProj);
        double rhoEff = Math.max(-0.92, Math.min(0.92, covXz / Math.max(1e-6, sx * sz)));
        return correlatedBoxProb(ax, ay, az, mu[0], mu[1], mu[2], sx, sy, sz, rhoEff, ivx, ivy, ivz);
    }

    /** Expected Hit-Equivalent value: sum_k effWeights[k] * P_k(hit | shot) * modeValues[k]. */
    public double expectedValue(Shot shot, Prediction pred, double leadShiftTicks,
                                double[] effWeights, double[] modeValues) {
        double[] mp = modeHitProbs(shot, pred, leadShiftTicks);
        if (mp == null) return 0.0;
        double ev = 0.0;
        for (int k = 0; k < numModes; k++) {
            double w = effWeights != null ? effWeights[k] : pred.weights[k];
            double v = modeValues != null ? modeValues[k] : 1.0;
            ev += w * mp[k] * v;
        }
        return ev;
    }

    /** Continuous multi-mode (yaw, pitch) coordinate-ascent refinement around best discrete mode shot. */
    public Shot refineShot(double shooterX, double eyeY, double shooterZ, double speed,
                           Shot initial, Prediction pred, double leadShiftTicks, Predicate<Shot> isClear) {
        return refineShotEv(shooterX, eyeY, shooterZ, speed, initial, pred, leadShiftTicks, null, null, isClear);
    }

    /** Continuous (yaw, pitch) coordinate-ascent refinement maximizing EV (supports arrows & throwables + volley weights). */
    public Shot refineShotEv(double shooterX, double eyeY, double shooterZ, double speed,
                             Shot initial, Prediction pred, double leadShiftTicks,
                             double[] effWeights, double[] modeValues, Predicate<Shot> isClear) {
        if (initial == null || pred == null) return initial;
        Shot best = initial;
        double bestScore = expectedValue(initial, pred, leadShiftTicks, effWeights, modeValues);
        float yaw = initial.yaw, pitch = initial.pitch;
        double tHit = initial.time;
        double gravity = initial.gravity;
        final float[] steps = {0.65f, 0.28f};
        final float[][] dirs = {{1f, 0f}, {-1f, 0f}, {0f, 1f}, {0f, -1f}};
        for (float step : steps) {
            for (float[] d : dirs) {
                float ny = yaw + d[0] * step;
                float np = Math.max(-90f, Math.min(90f, pitch + d[1] * step));
                Shot cand = BowBallistics.withTime(
                        BowBallistics.fromAim(shooterX, eyeY, shooterZ, speed, ny, np, gravity), tHit);
                if (isClear != null && !isClear.test(cand)) continue;
                double sc = expectedValue(cand, pred, leadShiftTicks, effWeights, modeValues);
                if (sc > bestScore + 1e-4) {
                    bestScore = sc;
                    best = cand;
                    yaw = ny;
                    pitch = np;
                }
            }
        }
        return best;
    }

    public double calibratedHitProb(Shot shot, Prediction pred) {
        return calibrate(rawHitProb(shot, pred, 0.0));
    }

    public double calibratedHitProb(Shot shot, Prediction pred, double leadShiftTicks) {
        return calibrate(rawHitProb(shot, pred, leadShiftTicks));
    }

    public double calibrate(double raw) {
        if (raw <= 0.0) return 0.0;
        double idx = raw * (calibration.length - 1);
        int lo = (int)idx;
        if (lo >= calibration.length - 1) return calibration[calibration.length - 1];
        return calibration[lo] + (idx - lo) * (calibration[lo + 1] - calibration[lo]);
    }

    private static double correlatedBoxProb(double ax, double ay, double az,
                                            double mx, double my, double mz,
                                            double sx, double sy, double sz, double rho,
                                            double ivx, double ivy, double ivz) {
        double slackX = 0.6 + 0.38 * Math.abs(ivx);
        double slackYLo = -0.3 - 0.35 * Math.abs(ivy);
        double slackYHi = 2.1 + 0.35 * Math.abs(ivy);
        double slackZ = 0.6 + 0.38 * Math.abs(ivz);

        double py = prob1d(ay - my - slackYHi, ay - my - slackYLo, sy);
        if (py < 1e-6) return 0.0;
        double dx = ax - mx, dz = az - mz;
        if (Math.abs(rho) < 0.05) {
            return prob1d(dx - slackX, dx + slackX, sx) * py * prob1d(dz - slackZ, dz + slackZ, sz);
        }
        double safeSx = Math.max(0.08, sx);
        double a = Math.max(-4.0, (dx - slackX) / safeSx);
        double b = Math.min(4.0, (dx + slackX) / safeSx);
        if (a >= b) return 0.0;
        double mid = 0.5 * (a + b), half = 0.5 * (b - a);
        double condSz = Math.max(0.08, sz * Math.sqrt(Math.max(0.05, 1.0 - rho * rho)));
        double pxz = 0.0;
        for (int i = 0; i < 5; i++) {
            double u = mid + half * GL_T[i];
            double pdfU = INV_SQRT2PI * Math.exp(-0.5 * u * u);
            double condMz = dz - rho * sz * u;
            pxz += GL_W[i] * pdfU * prob1d(condMz - slackZ, condMz + slackZ, condSz);
        }
        return Math.max(0.0, Math.min(1.0, pxz * half * py));
    }

    private static double prob1d(double lo, double hi, double sigma) {
        double d = INV_SQRT2 / Math.max(0.08, sigma);
        return 0.5 * (erf(hi * d) - erf(lo * d));
    }

    private static double erf(double x) {
        double sign = x < 0 ? -1.0 : 1.0;
        double ax = Math.abs(x);
        double t = 1.0 / (1.0 + 0.3275911 * ax);
        double y = 1.0 - (((((1.061405429 * t - 1.453152027) * t) + 1.421413741) * t
                - 0.284496736) * t + 0.254829592) * t * Math.exp(-ax * ax);
        return sign * y;
    }

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
            if (penalized < bestError) { bestError = penalized; bestPeriod = period; }
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
                for (int k = 0; k < paths.length; k++)
                    paths[k][t][axis] = paths[k][t][axis] * (1.0 - conf) + repeat * conf;
            }
        }
    }

    private static int srcVoxIndex(int rz, int rx, int side, int q) {
        int last = side - 1;
        switch (q) {
            case 0: return rz * side + rx;
            case 1: return rx * side + (last - rz);
            case 2: return (last - rz) * side + (last - rx);
            default: return (last - rx) * side + rz;
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
