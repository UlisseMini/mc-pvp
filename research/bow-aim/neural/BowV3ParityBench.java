import practice.core.bow.BowBallistics;
import practice.core.bow.BowMotion;
import practice.core.bow.BowNeuralMotion;
import practice.core.bow.BowWaitModel;
import practice.core.bow.VoidKbValue;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.FileInputStream;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Exact Java 8 parity & latency bench for BowAim v3:
 * 1. VoidKbValue.simulate & VoidKbValue.scoreMode on real bridge/edge/wall/island scenes
 * 2. BowBallistics.interceptThrowable & BowNeuralMotion.modeHitProbAt (Volley Coverage)
 * 3. BowWaitModel.predictWaitEv on real BowNeuralMotion trunks
 */
public final class BowV3ParityBench {
    private static long pack(int x, int y, int z) {
        return (((long)x & 0x3FFFFFL) << 42) | (((long)y & 0xFFFFFL) << 22) | ((long)z & 0x3FFFFFL);
    }

    public static void main(String[] args) throws Exception {
        String neuralBin = args[0];
        String waitBin = args[1];
        String casesBin = args[2];

        BowNeuralMotion neural;
        try (FileInputStream in = new FileInputStream(neuralBin)) {
            neural = BowNeuralMotion.read(in);
        }
        BowWaitModel waitModel;
        try (FileInputStream in = new FileInputStream(waitBin)) {
            waitModel = BowWaitModel.read(in);
        }

        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(casesBin)))) {
            // Part 1: VoidKbValue parity cases
            int nSolid = in.readInt();
            Set<Long> solidSet = new HashSet<>(nSolid * 2);
            for (int i = 0; i < nSolid; i++) {
                solidSet.add(pack(in.readInt(), in.readInt(), in.readInt()));
            }
            VoidKbValue.SolidQuery query = (x, y, z) -> solidSet.contains(pack(x, y, z));

            int nVoidCases = in.readInt();
            long t0 = System.nanoTime();
            for (int i = 0; i < nVoidCases; i++) {
                double px = in.readDouble(), py = in.readDouble(), pz = in.readDouble();
                double tvx = in.readDouble(), tvy = in.readDouble(), tvz = in.readDouble();
                double sx = in.readDouble(), sz = in.readDouble();
                double ivx = in.readDouble(), ivz = in.readDouble();
                int isThrow = in.readInt();
                int punch = in.readInt();
                VoidKbValue.Outcome o = VoidKbValue.simulate(px, py, pz, tvx, tvy, tvz, ivx, ivz, punch, query);
                VoidKbValue.Score sc = VoidKbValue.scoreMode(px, py, pz, tvx, tvy, tvz, sx, sz, ivx, ivz,
                        isThrow != 0, punch, 20.0, 0.0, query);
                System.out.printf(Locale.ROOT, "VOID %d %d %.6f %.6f %.6f%n",
                        i, o.voidKill ? 1 : 0, o.dropBlocks, sc.voidProb, sc.value);
            }
            double voidUs = (System.nanoTime() - t0) / 1000.0 / Math.max(1, nVoidCases);
            System.out.printf(Locale.ROOT, "VOID_US %.3f%n", voidUs);

            // Part 2: Throwable ballistics + Volley + BowWaitModel parity on real tracks
            int nTrackCases = in.readInt();
            long waitNs = 0;
            for (int i = 0; i < nTrackCases; i++) {
                int len = in.readInt();
                BowMotion.Track tr = new BowMotion.Track();
                for (int t = 0; t < len; t++) {
                    double x = in.readDouble(), y = in.readDouble(), z = in.readDouble();
                    double sx = in.readDouble(), sy = in.readDouble(), sz = in.readDouble();
                    float yaw = in.readFloat(), pitch = in.readFloat();
                    int flags = in.readInt();
                    float[] rel = new float[10];
                    for (int r = 0; r < 10; r++) rel[r] = in.readFloat();
                    tr.add(t + 1, x, y, z, 0, yaw, pitch, flags, sx, sy, sz, rel);
                }
                byte[] fine = new byte[BowNeuralMotion.FINE_DIM];
                byte[] coarse = new byte[BowNeuralMotion.COARSE_DIM];
                in.readFully(fine);
                in.readFully(coarse);
                double shx = in.readDouble(), shey = in.readDouble(), shz = in.readDouble();
                double qShoot = in.readDouble(), pHit = in.readDouble(), pVoid = in.readDouble();
                int isThrow = in.readInt(), hasLos = in.readInt();

                BowNeuralMotion.Prediction pred = neural.predict(tr, fine, coarse, true, false);
                BowBallistics.Shot tShot = BowBallistics.interceptThrowable(shx, shey, shz, pred.paths[0], 0.90);
                double tTime = tShot == null ? -1.0 : tShot.time;
                double tYaw = tShot == null ? 0.0 : tShot.yaw;
                double tPitch = tShot == null ? 0.0 : tShot.pitch;
                double cov0 = tShot == null ? 0.0 : neural.modeHitProbAt(tShot, pred, 0, Math.max(1.0, tShot.time - 5.0));

                long wt0 = System.nanoTime();
                double vw = waitModel.predictWaitEv(pred, shx, shey, shz,
                        tr.get(0, 0), tr.get(0, 1), tr.get(0, 2),
                        qShoot, pHit, pVoid, isThrow != 0, hasLos != 0);
                waitNs += (System.nanoTime() - wt0);

                System.out.printf(Locale.ROOT, "TRACK %d %.6f %.6f %.6f %.6f %.6f%n",
                        i, tTime, tYaw, tPitch, cov0, vw);
            }
            System.out.printf(Locale.ROOT, "WAIT_US %.3f%n", waitNs / 1000.0 / Math.max(1, nTrackCases));
        }
    }
}
