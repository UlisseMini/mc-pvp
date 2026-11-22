import practice.core.bow.BowBallistics;
import practice.core.bow.BowMotion.Track;
import practice.core.bow.BowNeuralMotion.Prediction;
import practice.core.bow.BowNeuralV4;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.FileInputStream;
import java.util.Locale;

/** Exact Java 8 parity & latency bench for BowNeuralV4 (BNN4). */
public final class BowV4ParityBench {
    public static void main(String[] args) throws Exception {
        BowNeuralV4 model;
        try (FileInputStream in = new FileInputStream(args[0])) {
            model = BowNeuralV4.read(in);
        }
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(args[1])))) {
            int nCases = in.readInt();
            long totalNs = 0;
            for (int i = 0; i < nCases; i++) {
                int len = in.readInt();
                Track tr = new Track();
                for (int t = 0; t < len; t++) {
                    double x = in.readDouble(), y = in.readDouble(), z = in.readDouble();
                    double sx = in.readDouble(), sy = in.readDouble(), sz = in.readDouble();
                    float yaw = in.readFloat(), pitch = in.readFloat();
                    int flags = in.readInt();
                    float[] rel = new float[10];
                    for (int r = 0; r < 10; r++) rel[r] = in.readFloat();
                    float[] blk4 = new float[4];
                    for (int b = 0; b < 4; b++) blk4[b] = in.readFloat();
                    tr.add(t + 1, x, y, z, 0, yaw, pitch, flags, sx, sy, sz, rel, blk4);
                }
                byte[] fineS = new byte[BowNeuralV4.FINE_DIM];
                byte[] fineP = new byte[BowNeuralV4.FINE_DIM];
                byte[] top = new byte[BowNeuralV4.TOP_DIM];
                in.readFully(fineS);
                in.readFully(fineP);
                in.readFully(top);
                double shx = in.readDouble(), shey = in.readDouble(), shz = in.readDouble();

                long t0 = System.nanoTime();
                Prediction pred = model.predict(tr, fineS, fineP, top, true, false);
                if (i >= 4) totalNs += (System.nanoTime() - t0);

                BowBallistics.Shot s = BowBallistics.interceptThrowable(shx, shey, shz, pred.paths[0], 0.90);
                double p0 = s == null ? 0.0 : model.modeHitProbs(s, pred, 0.0)[0];
                double fbSum = 0.0;
                for (float v : pred.futBlk245) fbSum += v;

                StringBuilder sb = new StringBuilder();
                sb.append("CASE ").append(i);
                for (int k = 0; k < model.modes(); k++) sb.append(String.format(Locale.ROOT, " %.6f", pred.weights[k]));
                // Mode 0 at horizons 15, 42, 72
                for (int hz : new int[]{15, 42, 72}) {
                    sb.append(String.format(Locale.ROOT, " %.6f %.6f %.6f %.6f %.6f",
                            pred.paths[0][hz][0], pred.paths[0][hz][1], pred.paths[0][hz][2],
                            pred.stds[0][hz][0], pred.rhos[0][hz]));
                }
                sb.append(String.format(Locale.ROOT, " %.6f %.6f", p0, fbSum));
                System.out.println(sb.toString());
            }
            double avgMs = totalNs / 1e6 / Math.max(1, nCases - 4);
            System.out.printf(Locale.ROOT, "LATENCY_MS %.3f%n", avgMs);
        }
    }
}
