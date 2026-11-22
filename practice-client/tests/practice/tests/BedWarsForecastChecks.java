package practice.tests;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.zip.GZIPInputStream;
import practice.core.forecast.BedWarsPredictor;
import practice.core.forecast.BedWarsPredictor.Volume;

/** Compares native probabilities with Python reference forecasts on held-out observations. */
public final class BedWarsForecastChecks {
    public static void main(String[] args) throws IOException {
        if (args.length != 2) throw new IllegalArgumentException("Usage: model.bin.gz parity.bin[.gz]");
        BedWarsPredictor model = BedWarsPredictor.load(Paths.get(args[0]));
        String[] names = model.models();
        double globalWorst = 0, globalL1 = 0;
        int checked = 0;
        boolean volumetric;
        try (java.io.InputStream raw = Files.newInputStream(Paths.get(args[1]));
             DataInputStream in = new DataInputStream(new BufferedInputStream(
                     args[1].endsWith(".gz") ? new GZIPInputStream(raw) : raw))) {
            int magic = in.readInt();
            volumetric = magic == 0x42573344;
            if (!volumetric && magic != 0x42575046) throw new IOException("Invalid Python forecast fixture");
            int fixtures = in.readInt();
            for (int i = 0; i < fixtures; i++) {
                int horizon = in.readInt();
                float[] features = read(in, BedWarsPredictor.FEATURE_COUNT);
                float[] xyz = read(in, 3), velocity = read(in, 3);
                float yaw = in.readFloat();
                float[][] support = new float[in.readInt()][];
                for (int k = 0; k < support.length; k++) support[k] = read(in, BedWarsPredictor.SUPPORT_WIDTH);
                int tests = in.readInt();
                for (int j = 0; j < tests; j++) {
                    String method = names[in.readInt()];
                    float[] reference = read(in, volumetric ? BedWarsPredictor.VOXELS : BedWarsPredictor.CELLS);
                    Volume volume = volumetric ? model.forecast3d(method, horizon, features, xyz, velocity, yaw, support) : null;
                    float[] actual = volume == null ? model.forecast(method, horizon, features, xyz, velocity, yaw, support)
                            : volume.mass;
                    double total = 0, expectedTotal = 0, largest = 0, l1 = 0;
                    for (int k = 0; k < actual.length; k++) {
                        if (actual[k] < 0 || !Float.isFinite(actual[k])) throw new AssertionError(method + " invalid mass");
                        double difference = Math.abs((double) actual[k] - reference[k]);
                        largest = Math.max(largest, difference);
                        l1 += difference;
                        total += actual[k];
                        expectedTotal += reference[k];
                        if (volume != null && actual[k] > 1e-8) checkFeet(volume, k);
                    }
                    if (Math.abs(total - (volumetric ? expectedTotal : 1)) > 2e-6 || largest > 4e-6 || l1 > 2e-5) {
                        throw new AssertionError(method + " h=" + horizon + " support=" + support.length
                            + " fixture=" + i + " total=" + total + " max=" + largest + " L1=" + l1);
                    }
                    globalWorst = Math.max(globalWorst, largest);
                    globalL1 = Math.max(globalL1, l1);
                    checked++;
                }
            }
            if (in.read() >= 0) throw new IOException("Unexpected trailing parity fixture data");
        }
        System.out.printf("Python/Java parity on %,d %s forecasts, max bin error %.3g, max L1 %.3g%n",
                          checked, volumetric ? "3D" : "projected", globalWorst, globalL1);
    }

    private static void checkFeet(Volume volume, int bin) {
        int cell = bin / BedWarsPredictor.LEVELS;
        int x = (volume.x(bin) - BedWarsPredictor.MIN_BLOCK) / BedWarsPredictor.TILE_WIDTH;
        int z = (volume.z(bin) - BedWarsPredictor.MIN_BLOCK) / BedWarsPredictor.TILE_WIDTH;
        int y = (volume.y(bin) - BedWarsPredictor.MIN_Y) / BedWarsPredictor.HEIGHT_STEP;
        if (x != cell / BedWarsPredictor.SIDE || z != cell % BedWarsPredictor.SIDE || y != bin % BedWarsPredictor.LEVELS)
            throw new AssertionError("Feet outside forecast bin " + bin);
    }

    private static float[] read(DataInputStream in, int n) throws IOException {
        float[] values = new float[n];
        for (int i = 0; i < n; i++) values[i] = in.readFloat();
        return values;
    }
}
