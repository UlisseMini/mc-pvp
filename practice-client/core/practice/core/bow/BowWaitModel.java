package practice.core.bow;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Compact Learned Continuation Value Head V_wait(s_t) for BowAim v3.
 * Predicts discounted causal future Hit-Equivalent EV if we hold our shot instead of firing now,
 * reusing BowNeuralNetV2's 224-dim trunk embedding + 10 shooter-relative tactical features.
 * Inference cost: < 0.04 ms in pure Java 8.
 */
public final class BowWaitModel {
    public static final int MAGIC = 0x42574154; // 'BWAT'
    public static final int TACTICAL_DIM = 10;

    private final int inDim, trunkDim, hidden1, hidden2;
    private final float fireAlpha;
    private final float[] w1, b1, w2, b2, w3, b3;

    private BowWaitModel(DataInputStream in) throws IOException {
        if (in.readInt() != MAGIC) throw new IOException("Invalid bow-wait model magic");
        inDim = in.readInt();
        hidden1 = in.readInt();
        hidden2 = in.readInt();
        fireAlpha = in.readFloat();
        trunkDim = inDim - TACTICAL_DIM;
        if (trunkDim < 64 || trunkDim > 512 || hidden1 < 8 || hidden1 > 256 || hidden2 < 4 || hidden2 > 256
                || !Float.isFinite(fireAlpha) || fireAlpha < 0.2f || fireAlpha > 3.0f) {
            throw new IOException("Invalid bow-wait header");
        }
        w1 = readFloats(in, hidden1 * inDim);
        b1 = readFloats(in, hidden1);
        w2 = readFloats(in, hidden2 * hidden1);
        b2 = readFloats(in, hidden2);
        w3 = readFloats(in, hidden2);
        b3 = readFloats(in, 1);
    }

    public static BowWaitModel read(InputStream stream) throws IOException {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(stream))) {
            return new BowWaitModel(in);
        }
    }

    public double fireAlpha() { return fireAlpha; }
    public int trunkDim() { return trunkDim; }

    /**
     * Predict V_wait(s_t) in Hit-Equivalents (e.g. 0.7 = normal hit, 4.5 = upcoming void kill window).
     */
    public double predictWaitEv(BowNeuralMotion.Prediction pred,
                                double shooterX, double shooterY, double shooterZ,
                                double targetX, double targetY, double targetZ,
                                double qShootNow, double pHitNow, double pVoidNow,
                                boolean isThrowable, boolean hasLosNow) {
        if (pred == null || pred.trunk224 == null || pred.trunk224.length != trunkDim) return Double.NaN;
        double dx = shooterX - targetX;
        double dy = shooterY - targetY;
        double dz = shooterZ - targetZ;
        double srx = rotX(dx, dz, pred.cardinalQ);
        double srz = rotZ(dx, dz, pred.cardinalQ);
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);

        float[] in = new float[inDim];
        System.arraycopy(pred.trunk224, 0, in, 0, trunkDim);
        in[trunkDim]     = clampF(srx / 32.0, -2f, 2f);
        in[trunkDim + 1] = clampF(dy / 32.0, -2f, 2f);
        in[trunkDim + 2] = clampF(srz / 32.0, -2f, 2f);
        in[trunkDim + 3] = clampF(dist / 32.0, 0f, 2.5f);
        in[trunkDim + 4] = (float)(Math.log1p(dist) / 4.0);
        in[trunkDim + 5] = clampF(qShootNow / 8.0, 0f, 2.0f);
        in[trunkDim + 6] = clampF(pHitNow, 0f, 1f);
        in[trunkDim + 7] = clampF(pVoidNow, 0f, 1f);
        in[trunkDim + 8] = isThrowable ? 1f : 0f;
        in[trunkDim + 9] = hasLosNow ? 1f : 0f;

        float[] h1 = new float[hidden1];
        for (int o = 0; o < hidden1; o++) {
            float sum = b1[o];
            int row = o * inDim;
            for (int i = 0; i < inDim; i++) sum += in[i] * w1[row + i];
            h1[o] = silu(sum);
        }
        float[] h2 = new float[hidden2];
        for (int o = 0; o < hidden2; o++) {
            float sum = b2[o];
            int row = o * hidden1;
            for (int i = 0; i < hidden1; i++) sum += h1[i] * w2[row + i];
            h2[o] = silu(sum);
        }
        double logit = b3[0];
        for (int i = 0; i < hidden2; i++) logit += h2[i] * w3[i];
        return softplus(logit);
    }

    private static double rotX(double dx, double dz, int q) {
        switch (q) { case 0: return dx; case 1: return dz; case 2: return -dx; default: return -dz; }
    }

    private static double rotZ(double dx, double dz, int q) {
        switch (q) { case 0: return dz; case 1: return -dx; case 2: return -dz; default: return dx; }
    }

    private static float silu(float x) { return (float)(x / (1.0 + Math.exp(-x))); }
    private static double softplus(double x) { return x > 20.0 ? x : Math.log1p(Math.exp(x)); }
    private static float clampF(double x, float lo, float hi) { return (float)Math.max(lo, Math.min(hi, x)); }

    private static float[] readFloats(DataInputStream in, int n) throws IOException {
        float[] arr = new float[n];
        for (int i = 0; i < n; i++) {
            float v = in.readFloat();
            if (!Float.isFinite(v)) throw new IOException("Non-finite weight in bow-wait model");
            arr[i] = v;
        }
        return arr;
    }
}
