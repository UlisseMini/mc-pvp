package practice.core.bow;

import practice.core.bow.BowBallistics.Shot;
import practice.core.bow.BowMotion.Track;
import practice.core.bow.BowNeuralMotion.Prediction;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.function.Predicate;

/**
 * Pure Java 8 inference for BowNeuralNetV4 (BNN4):
 * - 48-tick x 30-ch Conv1d + 4-head Temporal Self-Attention + LayerNorm
 * - Real 3D Spatial CNN on 9x7x9 x 2ch ([is_solid, recently_placed])
 * - Real 2D Spatial CNN on 25x25 x 4ch 1-block top-down map ([floor_y_rel, head_wall, is_void, recently_placed])
 * - 3-block 256-dim residual fusion trunk
 * - Physics-equivariant Velocity-Residual multi-modal heads anchored on serverPos(0) out to 72 ticks (3.6s)
 * - Auxiliary 5x7x7 Future-Block-Placement head (245 voxels over next 1..24 ticks)
 */
public final class BowNeuralV4 {
    public static final int MAGIC = 0x424E4E34; // 'BNN4'
    public static final int HIST_LEN = 48, HIST_CH = 30, CTX_DIM = 34;
    public static final int FINE_Y = 7, FINE_Z = 9, FINE_X = 9, FINE_DIM = 567;
    public static final int TOP_CH = 4, TOP_S = 25, TOP_DIM = TOP_CH * TOP_S * TOP_S;
    public static final int FUT_DIM = 5 * 7 * 7; // 245
    private static final int[] AUTOCORR_LAGS = {12, 16, 20, 24, 28, 34, 42, 52};
    private static final double SPREAD = 0.007499999832361937;
    private static final double INV_SQRT2 = 0.7071067811865475;
    private static final double INV_SQRT2PI = 0.3989422804014327;
    private static final double[] GL_T = {-0.9061798459, -0.5384693101, 0.0, 0.5384693101, 0.9061798459};
    private static final double[] GL_W = {0.2369268851, 0.4786286705, 0.5688888889, 0.4786286705, 0.2369268851};

    private final int numModes, numH, maxH;
    private final int[] horizons;
    private final float[] calibration;

    private final float[] c1w, c1b, c2w, c2b, c3w, c3b, c4w, c4b;
    private final float[] aqkvW, aqkvB, aoutW, aoutB, alnW, alnB, hpW, hpB;
    private final float[] v31W, v31B, v32W, v32B, v33W, v33B, v3pW, v3pB;
    private final float[] t21W, t21B, t22W, t22B, t23W, t23B, t2pW, t2pB;
    private final float[] trW, trB;
    private final float[] r1aW, r1aB, r1bW, r1bB, r2aW, r2aB, r2bW, r2bB, r3aW, r3aB, r3bW, r3bB;
    private final float[] logW, logB, vgW, vgB, delW, delB, stdW, stdB, rhoW, rhoB, fblkW, fblkB;

    private BowNeuralV4(DataInputStream in) throws IOException {
        if (in.readInt() != MAGIC) throw new IOException("Invalid BNN4 magic");
        numModes = in.readInt();
        numH = in.readInt();
        int calLen = in.readInt();
        if (numModes < 1 || numModes > 16 || numH < 1 || numH > 16 || calLen < 2 || calLen > 64) {
            throw new IOException("Invalid BNN4 header");
        }
        horizons = new int[numH];
        for (int i = 0; i < numH; i++) horizons[i] = in.readInt();
        maxH = horizons[numH - 1];
        calibration = readFloats(in, calLen);

        c1w = readFloats(in, 48 * HIST_CH * 3); c1b = readFloats(in, 48);
        c2w = readFloats(in, 64 * 48 * 3);      c2b = readFloats(in, 64);
        c3w = readFloats(in, 80 * 64 * 3);      c3b = readFloats(in, 80);
        c4w = readFloats(in, 96 * 80 * 3);      c4b = readFloats(in, 96);
        aqkvW = readFloats(in, 288 * 96);       aqkvB = readFloats(in, 288);
        aoutW = readFloats(in, 96 * 96);        aoutB = readFloats(in, 96);
        alnW = readFloats(in, 96);              alnB = readFloats(in, 96);
        hpW = readFloats(in, 144 * 288);        hpB = readFloats(in, 144);

        v31W = readFloats(in, 32 * 14 * 9);     v31B = readFloats(in, 32);
        v32W = readFloats(in, 48 * 32 * 9);     v32B = readFloats(in, 48);
        v33W = readFloats(in, 64 * 48 * 9);     v33B = readFloats(in, 64);
        v3pW = readFloats(in, 96 * 576);        v3pB = readFloats(in, 96);

        t21W = readFloats(in, 16 * TOP_CH * 9); t21B = readFloats(in, 16);
        t22W = readFloats(in, 24 * 16 * 9);     t22B = readFloats(in, 24);
        t23W = readFloats(in, 32 * 24 * 9);     t23B = readFloats(in, 32);
        t2pW = readFloats(in, 96 * 512);        t2pB = readFloats(in, 96);

        trW = readFloats(in, 256 * (144 + CTX_DIM + 96 + 96)); trB = readFloats(in, 256);
        r1aW = readFloats(in, 256 * 256);       r1aB = readFloats(in, 256);
        r1bW = readFloats(in, 256 * 256);       r1bB = readFloats(in, 256);
        r2aW = readFloats(in, 256 * 256);       r2aB = readFloats(in, 256);
        r2bW = readFloats(in, 256 * 256);       r2bB = readFloats(in, 256);
        r3aW = readFloats(in, 256 * 256);       r3aB = readFloats(in, 256);
        r3bW = readFloats(in, 256 * 256);       r3bB = readFloats(in, 256);

        logW = readFloats(in, numModes * 256);           logB = readFloats(in, numModes);
        vgW = readFloats(in, numModes * 2 * 256);        vgB = readFloats(in, numModes * 2);
        delW = readFloats(in, numModes * numH * 3 * 256); delB = readFloats(in, numModes * numH * 3);
        stdW = readFloats(in, numModes * numH * 3 * 256); stdB = readFloats(in, numModes * numH * 3);
        rhoW = readFloats(in, numModes * numH * 256);    rhoB = readFloats(in, numModes * numH);
        fblkW = readFloats(in, FUT_DIM * 256);           fblkB = readFloats(in, FUT_DIM);
    }

    public static BowNeuralV4 read(InputStream stream) throws IOException {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(stream))) {
            return new BowNeuralV4(in);
        }
    }

    public int modes() { return numModes; }
    public int maxHorizon() { return maxH; }

    public Prediction predict(Track track, byte[] fineSolid567, byte[] finePlaced567,
                              byte[] topInt8_2500, boolean hasTerrain, boolean bridgeHack) {
        double p0x = track.get(0, 0), p0y = track.get(0, 1), p0z = track.get(0, 2);
        double s0x = track.serverGet(0, 0), s0y = track.serverGet(0, 1), s0z = track.serverGet(0, 2);
        float yaw0 = track.yaw(0);
        int q = ((int)Math.floor((yaw0 + 45.0) / 90.0)) & 3;
        double qDeg = q * 90.0;
        int validPast = track.length();
        int maxAge = Math.min(validPast - 1, HIST_LEN - 1);
        boolean hasRel = track.hasRel(0);

        // 1) 48-tick x 30-ch temporal tensor
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
            float[] b = track.blk4(cAge);
            double brx = rotX(b[1], b[3], q), brz = rotZ(b[1], b[3], q);
            hist[26 * HIST_LEN + age] = clampF(b[0], 0f, 1f);
            hist[27 * HIST_LEN + age] = clampF(brx / 3.0, -1f, 1f);
            hist[28 * HIST_LEN + age] = clampF(b[2] / 3.0, -1f, 1f);
            hist[29 * HIST_LEN + age] = clampF(brz / 3.0, -1f, 1f);
        }

        // 2) Exact causal velocity base vbase (mirrored 1-to-1 with train_v4.py compute_vbase)
        float[] vbase = computeVBase(track, validPast, q);

        // 3) 34-dim style, periodicity, and block-place context
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
            for (int a = 0; a < nAc; a++) { vx3[a] -= mx; vz3[a] -= mz; energy += vx3[a] * vx3[a] + vz3[a] * vz3[a]; }
            if (energy > 1e-4) {
                for (int li = 0; li < AUTOCORR_LAGS.length; li++) {
                    int lag = AUTOCORR_LAGS[li];
                    if (lag + 8 < nAc) {
                        double num = 0, e1 = 0, e2 = 0;
                        int m = nAc - lag;
                        for (int a = 0; a < m; a++) {
                            num += vx3[a] * vx3[a + lag] + vz3[a] * vz3[a + lag];
                            e1 += vx3[a] * vx3[a] + vz3[a] * vz3[a];
                            e2 += vx3[a + lag] * vx3[a + lag] + vz3[a + lag] * vz3[a + lag];
                        }
                        double den = Math.sqrt(e1 * e2);
                        if (den > 1e-5) ctx[12 + li] = clampF(num / den, -1f, 1f);
                    }
                }
            }
        }
        ctx[20] = (float)(Math.log1p(Math.min(256, validPast)) / 5.5);
        ctx[21] = (hasTerrain && fineSolid567 != null) ? 1f : 0f;
        int nSt = Math.min(160, validPast - 1);
        if (nSt >= 4) {
            int jumps = 0, sneak = 0, blk = 0, hurt = 0;
            for (int a = 0; a < nSt; a++) if (track.get(a, 1) - track.get(a + 1, 1) > 0.25) jumps++;
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
                    double v1x = track.get(a, 0) - track.get(a + 4, 0), v1z = track.get(a, 2) - track.get(a + 4, 2);
                    double v2x = track.get(a + 4, 0) - track.get(a + 8, 0), v2z = track.get(a + 4, 2) - track.get(a + 8, 2);
                    if (Math.hypot(v1x, v1z) * Math.hypot(v2x, v2z) > 0.04 && (v1x * v2x + v1z * v2z) < 0.0) rev++;
                }
                ctx[23] = (float)rev / totRev;
            }
            ctx[24] = (float)sneak / (nSt + 1);
            ctx[25] = (float)blk / (nSt + 1);
            ctx[26] = (float)hurt / (nSt + 1);
        }
        ctx[27] = (float)(Math.hypot(s0x - p0x, s0z - p0z) * 3.0);
        ctx[28] = hasRel ? 1f : 0f;
        ctx[29] = hasRel ? track.rel(0)[3] : 0f;
        int nb = Math.min(40, validPast);
        int nbPos = 0;
        for (int a = 0; a < nb; a++) if (track.blk4(a)[0] > 0.05f) nbPos++;
        ctx[30] = (float)nbPos / Math.max(1, nb);
        ctx[31] = clampF(vbase[1] * 5.0, -2f, 2f);
        ctx[32] = clampF(vbase[0] * 3.0, -2f, 2f);
        ctx[33] = clampF(vbase[2] * 3.0, -2f, 2f);
        for (int i = 0; i < CTX_DIM; i++) ctx[i] = clampF(ctx[i], -4f, 4f);

        // 4) Temporal Conv1d + 4-head Self-Attention + LayerNorm
        float[] h1 = conv1dSilu(hist, HIST_CH, HIST_LEN, c1w, c1b, 48, 1);
        float[] h2 = conv1dSilu(h1, 48, 48, c2w, c2b, 64, 2);
        float[] h3 = conv1dSilu(h2, 64, 24, c3w, c3b, 80, 2);
        float[] h4 = conv1dSilu(h3, 80, 12, c4w, c4b, 96, 2); // (96, 6)

        // Transpose to (6, 96) tokens
        float[][] tok = new float[6][96];
        for (int t = 0; t < 6; t++) for (int c = 0; c < 96; c++) tok[t][c] = h4[c * 6 + t];
        float[][] qkv = new float[6][288];
        for (int t = 0; t < 6; t++) qkv[t] = linear(tok[t], aqkvW, aqkvB, 288);
        float[][] attCat = new float[6][96];
        float invScale = (float)(1.0 / Math.sqrt(24.0));
        for (int hd = 0; hd < 4; hd++) {
            int off = hd * 24;
            for (int t1 = 0; t1 < 6; t1++) {
                float[] scores = new float[6];
                float maxS = -Float.MAX_VALUE;
                for (int t2 = 0; t2 < 6; t2++) {
                    float s = 0f;
                    for (int i = 0; i < 24; i++) s += qkv[t1][off + i] * qkv[t2][96 + off + i];
                    scores[t2] = s * invScale;
                    if (scores[t2] > maxS) maxS = scores[t2];
                }
                float sumE = 0f;
                for (int t2 = 0; t2 < 6; t2++) { scores[t2] = (float)Math.exp(scores[t2] - maxS); sumE += scores[t2]; }
                for (int t2 = 0; t2 < 6; t2++) scores[t2] /= sumE;
                for (int i = 0; i < 24; i++) {
                    float vSum = 0f;
                    for (int t2 = 0; t2 < 6; t2++) vSum += scores[t2] * qkv[t2][192 + off + i];
                    attCat[t1][off + i] = vSum;
                }
            }
        }
        for (int t = 0; t < 6; t++) {
            float[] proj = linear(attCat[t], aoutW, aoutB, 96);
            float mean = 0f;
            for (int c = 0; c < 96; c++) { tok[t][c] += proj[c]; mean += tok[t][c]; }
            mean /= 96f;
            float var = 0f;
            for (int c = 0; c < 96; c++) { float d = tok[t][c] - mean; var += d * d; }
            float invStd = (float)(1.0 / Math.sqrt(var / 96.0 + 1e-5));
            for (int c = 0; c < 96; c++) tok[t][c] = (tok[t][c] - mean) * invStd * alnW[c] + alnB[c];
        }
        float[] hPool = new float[288];
        for (int c = 0; c < 96; c++) {
            float first = tok[0][c], sum = 0f, mx = -Float.MAX_VALUE;
            for (int t = 0; t < 6; t++) { float v = tok[t][c]; sum += v; if (v > mx) mx = v; }
            hPool[c] = first; hPool[96 + c] = sum / 6f; hPool[192 + c] = mx;
        }
        float[] hEmb = linearSilu(hPool, hpW, hpB, 144);

        // 5) Fine 2.5D Vertical-Channel Spatial CNN on (14, 9, 9) -> (32, 9, 9) -> (48, 5, 5) -> (64, 3, 3)
        float[] f3In = new float[2 * FINE_DIM];
        if (hasTerrain && fineSolid567 != null && fineSolid567.length == FINE_DIM) {
            for (int dy = 0; dy < FINE_Y; dy++) {
                int yOff = dy * 81;
                for (int rz = 0; rz < FINE_Z; rz++) {
                    for (int rx = 0; rx < FINE_X; rx++) {
                        int src = yOff + srcVoxIndex(rz, rx, 9, q);
                        int dst = yOff + rz * 9 + rx;
                        if (fineSolid567[src] != 0) f3In[dst] = 1f;
                        if (finePlaced567 != null && finePlaced567[src] != 0) f3In[FINE_DIM + dst] = 1f;
                    }
                }
            }
        }
        float[] v3a = conv2dSilu(f3In, 14, 9, 9, v31W, v31B, 32, 1);  // (32, 9, 9)
        float[] v3b = conv2dSilu(v3a, 32, 9, 9, v32W, v32B, 48, 2);   // (48, 5, 5)
        float[] v3c = conv2dSilu(v3b, 48, 5, 5, v33W, v33B, 64, 2);   // (64, 3, 3)
        float[] v3Emb = linearSilu(v3c, v3pW, v3pB, 96);

        // 6) Wide 25x25 x 4 Top-Down 2D CNN
        float[] t2In = new float[TOP_DIM];
        if (hasTerrain && topInt8_2500 != null && topInt8_2500.length == TOP_DIM) {
            int plane = TOP_S * TOP_S;
            for (int rz = 0; rz < TOP_S; rz++) {
                for (int rx = 0; rx < TOP_S; rx++) {
                    int src = srcVoxIndex(rz, rx, TOP_S, q);
                    int dst = rz * TOP_S + rx;
                    byte c0 = topInt8_2500[src];
                    t2In[dst] = c0 <= -125 ? -1.4f : (c0 * 0.01f);
                    t2In[plane + dst] = topInt8_2500[plane + src] * 0.01f;
                    t2In[2 * plane + dst] = topInt8_2500[2 * plane + src] * 0.01f;
                    t2In[3 * plane + dst] = topInt8_2500[3 * plane + src] * 0.01f;
                }
            }
        }
        float[] t2a = conv2dSilu(t2In, TOP_CH, 25, 25, t21W, t21B, 16, 2); // (16, 13, 13)
        float[] t2b = conv2dSilu(t2a, 16, 13, 13, t22W, t22B, 24, 2);      // (24, 7, 7)
        float[] t2c = conv2dSilu(t2b, 24, 7, 7, t23W, t23B, 32, 2);        // (32, 4, 4)
        float[] t2Emb = linearSilu(t2c, t2pW, t2pB, 96);

        // 7) 3-block 256-dim residual fusion trunk
        float[] fused = new float[144 + CTX_DIM + 96 + 96];
        System.arraycopy(hEmb, 0, fused, 0, 144);
        System.arraycopy(ctx, 0, fused, 144, CTX_DIM);
        System.arraycopy(v3Emb, 0, fused, 144 + CTX_DIM, 96);
        System.arraycopy(t2Emb, 0, fused, 144 + CTX_DIM + 96, 96);

        float[] x = linearSilu(fused, trW, trB, 256);
        float[] r1 = linear(linearSilu(x, r1aW, r1aB, 256), r1bW, r1bB, 256);
        for (int i = 0; i < 256; i++) x[i] = silu(x[i] + r1[i]);
        float[] r2 = linear(linearSilu(x, r2aW, r2aB, 256), r2bW, r2bB, 256);
        for (int i = 0; i < 256; i++) x[i] = silu(x[i] + r2[i]);
        float[] r3 = linear(linearSilu(x, r3aW, r3aB, 256), r3bW, r3bB, 256);
        for (int i = 0; i < 256; i++) x[i] = silu(x[i] + r3[i]);

        // 8) Velocity-residual multi-modal heads anchored on serverPos(0) + auxiliary future-block head
        float[] logits = linear(x, logW, logB, numModes);
        float[] vgateRaw = linear(x, vgW, vgB, numModes * 2);
        float[] deltaFlat = linear(x, delW, delB, numModes * numH * 3);
        float[] logstdFlat = linear(x, stdW, stdB, numModes * numH * 3);
        float[] rhoFlat = linear(x, rhoW, rhoB, numModes * numH);
        float[] fblkLogits = linear(x, fblkW, fblkB, FUT_DIM);
        float[] futBlkProbs = new float[FUT_DIM];
        for (int i = 0; i < FUT_DIM; i++) futBlkProbs[i] = (float)(1.0 / (1.0 + Math.exp(-fblkLogits[i])));

        double[] weights = new double[numModes];
        double maxL = Double.NEGATIVE_INFINITY;
        for (int k = 0; k < numModes; k++) if (logits[k] > maxL) maxL = logits[k];
        double sumW = 0;
        for (int k = 0; k < numModes; k++) { weights[k] = Math.exp(logits[k] - maxL); sumW += weights[k]; }
        for (int k = 0; k < numModes; k++) weights[k] /= sumW;

        double[][][] paths = new double[numModes][maxH + 1][3];
        double[][][] stds = new double[numModes][maxH + 1][3];
        double[][] rhos = new double[numModes][maxH + 1];
        double[][] meanPath = new double[maxH + 1][3];
        double[][] ancPos = new double[numH + 1][3];
        double[][] ancStd = new double[numH + 1][3];
        double[] ancRho = new double[numH + 1];

        for (int k = 0; k < numModes; k++) {
            double gxz = 1.65 / (1.0 + Math.exp(-vgateRaw[k * 2]));
            double gy = 1.65 / (1.0 + Math.exp(-vgateRaw[k * 2 + 1]));
            ancPos[0][0] = s0x; ancPos[0][1] = s0y; ancPos[0][2] = s0z;
            ancStd[0][0] = 0.05; ancStd[0][1] = 0.05; ancStd[0][2] = 0.05;
            ancRho[0] = 0.0;
            for (int hi = 0; hi < numH; hi++) {
                int hz = horizons[hi];
                int base = (k * numH + hi) * 3;
                double rx = gxz * vbase[0] * hz + deltaFlat[base];
                double ry = gy * vbase[1] * hz + deltaFlat[base + 1];
                double rz = gxz * vbase[2] * hz + deltaFlat[base + 2];
                ancPos[hi + 1][0] = s0x + invRotX(rx, rz, q);
                ancPos[hi + 1][1] = s0y + ry;
                ancPos[hi + 1][2] = s0z + invRotZ(rx, rz, q);
                double sx = Math.exp(Math.max(-2.1, Math.min(2.3, logstdFlat[base])));
                double sy = Math.exp(Math.max(-2.1, Math.min(2.3, logstdFlat[base + 1])));
                double sz = Math.exp(Math.max(-2.1, Math.min(2.3, logstdFlat[base + 2])));
                double rVal = 0.90 * Math.tanh(rhoFlat[k * numH + hi]);
                if (q == 1 || q == 3) {
                    ancStd[hi + 1][0] = sz; ancStd[hi + 1][2] = sx; ancRho[hi + 1] = -rVal;
                } else {
                    ancStd[hi + 1][0] = sx; ancStd[hi + 1][2] = sz; ancRho[hi + 1] = rVal;
                }
                ancStd[hi + 1][1] = sy;
            }
            paths[k][0][0] = s0x; paths[k][0][1] = s0y; paths[k][0][2] = s0z;
            stds[k][0][0] = 0.05; stds[k][0][1] = 0.05; stds[k][0][2] = 0.05;
            rhos[k][0] = 0.0;
            int hi = 0, lower = 0;
            for (int t = 1; t <= maxH; t++) {
                while (hi + 1 < numH && t > horizons[hi]) lower = horizons[hi++];
                double u = Math.min(1.0, (t - lower) / (double)(horizons[hi] - lower));
                for (int a = 0; a < 3; a++) {
                    double pv = ancPos[hi][a] * (1.0 - u) + ancPos[hi + 1][a] * u;
                    double sv = ancStd[hi][a] * (1.0 - u) + ancStd[hi + 1][a] * u;
                    paths[k][t][a] = pv; stds[k][t][a] = sv;
                    meanPath[t][a] += weights[k] * pv;
                }
                rhos[k][t] = ancRho[hi] * (1.0 - u) + ancRho[hi + 1] * u;
            }
        }
        meanPath[0][0] = s0x; meanPath[0][1] = s0y; meanPath[0][2] = s0z;
        Prediction pred = new Prediction(weights, paths, stds, rhos, meanPath, x, q, futBlkProbs);
        if (bridgeHack) {
            BowNeuralMotion.applyBridgeHack(track, pred);
        }
        return pred;
    }

    public double calibrate(double raw) {
        if (raw <= 0.0) return 0.0;
        double idx = raw * (calibration.length - 1);
        int lo = (int)idx;
        if (lo >= calibration.length - 1) return calibration[calibration.length - 1];
        return calibration[lo] + (idx - lo) * (calibration[lo + 1] - calibration[lo]);
    }

    public double[] modeHitProbs(Shot shot, Prediction pred, double leadShiftTicks) {
        if (shot == null || pred == null) return null;
        double tHit = shot.time;
        int maxAllowed = shot.gravity < 0.04 ? Math.max(maxH, BowBallistics.MAX_THROW_TICKS) : BowBallistics.MAX_TICKS;
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
        double varProj = (SPREAD * spd * tr) * (SPREAD * spd * tr);

        double tEval = Math.min(maxH, tHit + Math.max(0.0, leadShiftTicks));
        int lo = (int)tEval, hi = Math.min(maxH, lo + 1);
        double frac = tEval - lo;
        double[] out = new double[numModes];
        for (int k = 0; k < numModes; k++) {
            if (pred.weights[k] < 1e-4) continue;
            double[] pLo = pred.paths[k][lo], pHi = pred.paths[k][hi];
            double[] sLo = pred.stds[k][lo], sHi = pred.stds[k][hi];
            double mx = pLo[0] + frac * (pHi[0] - pLo[0]);
            double my = pLo[1] + frac * (pHi[1] - pLo[1]);
            double mz = pLo[2] + frac * (pHi[2] - pLo[2]);
            double stx = sLo[0] + frac * (sHi[0] - sLo[0]);
            double sty = sLo[1] + frac * (sHi[1] - sLo[1]);
            double stz = sLo[2] + frac * (sHi[2] - sLo[2]);
            double r0 = pred.rhos[k][lo] + frac * (pred.rhos[k][hi] - pred.rhos[k][lo]);
            double covXz = r0 * stx * stz;
            double sx = Math.sqrt(stx * stx + varProj), sy = Math.sqrt(sty * sty + varProj), sz = Math.sqrt(stz * stz + varProj);
            double rhoEff = Math.max(-0.92, Math.min(0.92, covXz / Math.max(1e-6, sx * sz)));
            out[k] = correlatedBoxProb(ax, ay, az, mx, my, mz, sx, sy, sz, rhoEff, ivx, ivy, ivz);
        }
        return out;
    }

    public double modeHitProbAt(Shot shot, Prediction pred, int k, double evalTick) {
        if (shot == null || pred == null || k < 0 || k >= numModes) return 0.0;
        double tHit = shot.time;
        if (tHit <= 0.0 || tHit > maxH || evalTick <= 0.0 || evalTick > maxH) return 0.0;
        double tr = BowBallistics.travel(tHit);
        double ax = shot.x + shot.vx * tr;
        double ay = shot.y + shot.vy * tr - BowBallistics.fall(tHit, shot.gravity);
        double az = shot.z + shot.vz * tr;
        double spd = Math.sqrt(shot.vx * shot.vx + shot.vy * shot.vy + shot.vz * shot.vz);
        double dragT = Math.pow(BowBallistics.DRAG, tHit);
        double ivx = shot.vx * dragT;
        double ivy = shot.vy * dragT - shot.gravity * (1.0 - dragT) / (1.0 - BowBallistics.DRAG);
        double ivz = shot.vz * dragT;
        double varProj = (SPREAD * spd * tr) * (SPREAD * spd * tr);

        int lo = (int)evalTick, hi = Math.min(maxH, lo + 1);
        double frac = evalTick - lo;
        double[] pLo = pred.paths[k][lo], pHi = pred.paths[k][hi];
        double[] sLo = pred.stds[k][lo], sHi = pred.stds[k][hi];
        double mx = pLo[0] + frac * (pHi[0] - pLo[0]);
        double my = pLo[1] + frac * (pHi[1] - pLo[1]);
        double mz = pLo[2] + frac * (pHi[2] - pLo[2]);
        double stx = sLo[0] + frac * (sHi[0] - sLo[0]);
        double sty = sLo[1] + frac * (sHi[1] - sLo[1]);
        double stz = sLo[2] + frac * (sHi[2] - sLo[2]);
        double r0 = pred.rhos[k][lo] + frac * (pred.rhos[k][hi] - pred.rhos[k][lo]);
        double covXz = r0 * stx * stz;
        double sx = Math.sqrt(stx * stx + varProj), sy = Math.sqrt(sty * sty + varProj), sz = Math.sqrt(stz * stz + varProj);
        double rhoEff = Math.max(-0.92, Math.min(0.92, covXz / Math.max(1e-6, sx * sz)));
        return correlatedBoxProb(ax, ay, az, mx, my, mz, sx, sy, sz, rhoEff, ivx, ivy, ivz);
    }

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

    public Shot refineShotEv(double shooterX, double eyeY, double shooterZ, double speed,
                             Shot initial, Prediction pred, double leadShiftTicks,
                             double[] effWeights, double[] modeValues, Predicate<Shot> isClear) {
        if (initial == null || pred == null) return initial;
        Shot best = initial;
        double bestScore = expectedValue(initial, pred, leadShiftTicks, effWeights, modeValues);
        float yaw = initial.yaw, pitch = initial.pitch;
        double tHit = initial.time, gravity = initial.gravity;
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
                if (sc > bestScore + 1e-4) { bestScore = sc; best = cand; yaw = ny; pitch = np; }
            }
        }
        return best;
    }

    private static float[] computeVBase(Track track, int validPast, int q) {
        float[] out = new float[3];
        int wv = Math.min(12, validPast - 1);
        if (wv >= 1) {
            double vxW = (track.serverGet(0, 0) - track.serverGet(wv, 0)) / wv;
            double vzW = (track.serverGet(0, 2) - track.serverGet(wv, 2)) / wv;
            out[0] = clampF(rotX(vxW, vzW, q), -0.38f, 0.38f);
            out[2] = clampF(rotZ(vxW, vzW, q), -0.38f, 0.38f);
        }
        if (validPast >= 14) {
            int w = Math.min(22, validPast - 1);
            int fwin = Math.min(6, w / 3);
            double minRec = Double.POSITIVE_INFINITY, minOld = Double.POSITIVE_INFINITY;
            for (int a = 0; a < fwin; a++) minRec = Math.min(minRec, track.serverGet(a, 1));
            for (int a = w - fwin + 1; a <= w; a++) minOld = Math.min(minOld, track.serverGet(a, 1));
            double rawVy = (minRec - minOld) / Math.max(1, w - fwin);
            if (rawVy >= 0.04) out[1] = (float)Math.min(0.28, rawVy);
        }
        return out;
    }

    private static double correlatedBoxProb(double ax, double ay, double az, double mx, double my, double mz,
                                            double sx, double sy, double sz, double rho,
                                            double ivx, double ivy, double ivz) {
        double slackX = 0.6 + 0.38 * Math.abs(ivx);
        double slackYLo = -0.3 - 0.35 * Math.abs(ivy), slackYHi = 2.1 + 0.35 * Math.abs(ivy);
        double slackZ = 0.6 + 0.38 * Math.abs(ivz);
        double py = prob1d(ay - my - slackYHi, ay - my - slackYLo, sy);
        if (py < 1e-6) return 0.0;
        double dx = ax - mx, dz = az - mz;
        if (Math.abs(rho) < 0.05) {
            return prob1d(dx - slackX, dx + slackX, sx) * py * prob1d(dz - slackZ, dz + slackZ, sz);
        }
        double safeSx = Math.max(0.08, sx);
        double a = Math.max(-4.0, (dx - slackX) / safeSx), b = Math.min(4.0, (dx + slackX) / safeSx);
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

    private static float[] conv1dSilu(float[] in, int inC, int inL, float[] w, float[] b, int outC, int stride) {
        int outL = (inL + 2 - 3) / stride + 1;
        float[] out = new float[outC * outL];
        for (int oc = 0; oc < outC; oc++) {
            float bias = b[oc]; int wOc = oc * inC * 3; int oOc = oc * outL;
            for (int ot = 0; ot < outL; ot++) {
                float sum = bias; int center = ot * stride;
                for (int ic = 0; ic < inC; ic++) {
                    int ib = ic * inL, wb = wOc + ic * 3;
                    if (center - 1 >= 0) sum += in[ib + center - 1] * w[wb];
                    sum += in[ib + center] * w[wb + 1];
                    if (center + 1 < inL) sum += in[ib + center + 1] * w[wb + 2];
                }
                out[oOc + ot] = silu(sum);
            }
        }
        return out;
    }

    private static float[] conv2dSilu(float[] in, int inC, int inH, int inW, float[] w, float[] b, int outC, int stride) {
        int outH = (inH + 2 - 3) / stride + 1, outW = (inW + 2 - 3) / stride + 1;
        float[] out = new float[outC * outH * outW];
        int inHW = inH * inW, outHW = outH * outW;
        for (int oc = 0; oc < outC; oc++) {
            float bias = b[oc]; int wOc = oc * inC * 9; int oOc = oc * outHW;
            for (int oh = 0; oh < outH; oh++) {
                int ch = oh * stride;
                for (int ow = 0; ow < outW; ow++) {
                    int cw = ow * stride;
                    float sum = bias;
                    for (int ic = 0; ic < inC; ic++) {
                        int ib = ic * inHW, wb = wOc + ic * 9;
                        for (int kh = -1; kh <= 1; kh++) {
                            int ih = ch + kh;
                            if (ih < 0 || ih >= inH) continue;
                            int rowIn = ib + ih * inW, rowW = wb + (kh + 1) * 3;
                            for (int kw = -1; kw <= 1; kw++) {
                                int iw = cw + kw;
                                if (iw >= 0 && iw < inW) sum += in[rowIn + iw] * w[rowW + kw + 1];
                            }
                        }
                    }
                    out[oOc + oh * outW + ow] = silu(sum);
                }
            }
        }
        return out;
    }

    private static float[] conv3dSilu(float[] in, int inC, int inD, int inH, int inW,
                                      float[] w, float[] b, int outC, int stride) {
        int outD = (inD + 2 - 3) / stride + 1, outH = (inH + 2 - 3) / stride + 1, outW = (inW + 2 - 3) / stride + 1;
        float[] out = new float[outC * outD * outH * outW];
        int inHW = inH * inW, inDHW = inD * inHW, outHW = outH * outW, outDHW = outD * outHW;
        for (int oc = 0; oc < outC; oc++) {
            float bias = b[oc]; int wOc = oc * inC * 27; int oOc = oc * outDHW;
            for (int od = 0; od < outD; od++) {
                int cd = od * stride;
                for (int oh = 0; oh < outH; oh++) {
                    int ch = oh * stride;
                    for (int ow = 0; ow < outW; ow++) {
                        int cw = ow * stride;
                        float sum = bias;
                        for (int ic = 0; ic < inC; ic++) {
                            int ib = ic * inDHW, wb = wOc + ic * 27;
                            for (int kd = -1; kd <= 1; kd++) {
                                int id = cd + kd;
                                if (id < 0 || id >= inD) continue;
                                int dIn = ib + id * inHW, dW = wb + (kd + 1) * 9;
                                for (int kh = -1; kh <= 1; kh++) {
                                    int ih = ch + kh;
                                    if (ih < 0 || ih >= inH) continue;
                                    int hIn = dIn + ih * inW, hW = dW + (kh + 1) * 3;
                                    for (int kw = -1; kw <= 1; kw++) {
                                        int iw = cw + kw;
                                        if (iw >= 0 && iw < inW) sum += in[hIn + iw] * w[hW + kw + 1];
                                    }
                                }
                            }
                        }
                        out[oOc + od * outHW + oh * outW + ow] = silu(sum);
                    }
                }
            }
        }
        return out;
    }

    private static float[] linear(float[] in, float[] w, float[] b, int outDim) {
        int inDim = in.length;
        float[] out = new float[outDim];
        for (int o = 0; o < outDim; o++) {
            float sum = b[o]; int row = o * inDim;
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

    private static void fillMeanVel(Track track, int validPast, int a, int b, int q,
                                    float[] ctx, int offset, boolean withY) {
        int fa = Math.min(a, validPast - 1), fb = Math.min(b, validPast - 1);
        if (fb <= fa) return;
        double dt = fb - fa;
        double mx = (track.get(fa, 0) - track.get(fb, 0)) / dt;
        double my = (track.get(fa, 1) - track.get(fb, 1)) / dt;
        double mz = (track.get(fa, 2) - track.get(fb, 2)) / dt;
        double rx = rotX(mx, mz, q), rz = rotZ(mx, mz, q);
        if (withY) { ctx[offset] = (float)(rx * 3.0); ctx[offset + 1] = (float)(my * 3.0); ctx[offset + 2] = (float)(rz * 3.0); }
        else { ctx[offset] = (float)(rx * 3.0); ctx[offset + 1] = (float)(rz * 3.0); }
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
    private static float silu(float x) { return (float)(x / (1.0 + Math.exp(-x))); }
    private static float clampF(double x, float lo, float hi) { return (float)Math.max(lo, Math.min(hi, x)); }

    private static float[] readFloats(DataInputStream in, int n) throws IOException {
        float[] arr = new float[n];
        for (int i = 0; i < n; i++) {
            float v = in.readFloat();
            if (!Float.isFinite(v)) throw new IOException("Non-finite weight in BNN4");
            arr[i] = v;
        }
        return arr;
    }
}
