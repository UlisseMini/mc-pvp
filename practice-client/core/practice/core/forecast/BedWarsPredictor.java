package practice.core.forecast;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.zip.GZIPInputStream;

/** Local inference for BedWars horizontal tiles and their joint height distributions. */
public final class BedWarsPredictor {
    public static final int FEATURE_COUNT = 17;
    public static final int SIDE = 16;
    public static final int CELLS = SIDE * SIDE;
    public static final int MIN_BLOCK = -128;
    public static final int TILE_WIDTH = 16;
    public static final int SUPPORT_WIDTH = 27;
    public static final int MIN_Y = -64, HEIGHT_BLOCKS = 256, HEIGHT_STEP = 4;
    public static final int LEVELS = HEIGHT_BLOCKS / HEIGHT_STEP;
    public static final int VOXELS = CELLS * LEVELS;

    private static final int LATEST_SUPPORT = 32;
    private static final String[] MODELS = {
        "stay", "velocity", "drift", "motion", "motion-no-motion", "motion-no-context",
        "heatmap-ce", "heatmap", "head-tune", "anil-prior", "anil", "history", "meta-history",
        "personal-hotspots", "personal-routes"
    };
    private static final double[] ANIL_SMOOTH = {.25, .5, .25};
    private static final double[] HIST_SMOOTH = gaussianKernel(.65, 2);
    private static final int[] ROUTE_FEATURES = {6, 8, 12, 14};

    private final int[] horizons;
    private final Heatmap exact, shared, anil;
    private final float[] steps, historyIntercept, historyCoefficients;
    private final Baseline[] baseline;

    private BedWarsPredictor(DataInputStream input) throws IOException {
        if (input.readInt() != 0x4257504d) throw new IOException("Unsupported BedWars forecast bundle");
        int version = input.readInt();
        if ((version != 1 && version != 2) || input.readInt() != SIDE) {
            throw new IOException("Unsupported BedWars forecast bundle");
        }
        int n = checkedSize(input, 1, 12);
        horizons = new int[n];
        for (int i = 0; i < n; i++) horizons[i] = checkedSize(input, 1, 300);
        exact = new Heatmap(input, version == 2);
        shared = new Heatmap(input, version == 2);
        anil = new Heatmap(input, version == 2);
        steps = readFloats(input, n);
        historyIntercept = readFloats(input, n);
        historyCoefficients = readFloats(input, 4);
        baseline = new Baseline[n];
        for (int i = 0; i < n; i++) baseline[i] = new Baseline(input);
        if (input.read() != -1) throw new IOException("Unexpected BedWars forecast data");
    }

    public static BedWarsPredictor load(Path bundle) throws IOException {
        try (DataInputStream in = new DataInputStream(
                new BufferedInputStream(new GZIPInputStream(Files.newInputStream(bundle))))) {
            return new BedWarsPredictor(in);
        }
    }

    public int[] horizons() { return horizons.clone(); }
    public String[] models() { return MODELS.clone(); }
    public boolean hasVolume() { return shared.fineHidden != null; }

    /** A 16 by 4 by 16 block display grid with a representative feet block inside each bin. */
    public static final class Volume {
        public final float[] mass = new float[VOXELS];
        public final float[] horizontal = new float[CELLS];
        private final float[] peak = new float[VOXELS];
        private final int[] feet = new int[VOXELS];

        public void add(Volume source, float weight) {
            for (int i = 0; i < CELLS; i++) horizontal[i] += source.horizontal[i] * weight;
            for (int i = 0; i < VOXELS; i++) {
                deposit(i, source.mass[i] * weight, source.peak[i] * weight, source.feet[i]);
            }
        }

        public int x(int voxel) {
            return MIN_BLOCK + (peak[voxel] > 0 ? feet[voxel] >>> 16 : (voxel / LEVELS / SIDE) * TILE_WIDTH + TILE_WIDTH / 2);
        }

        public int y(int voxel) {
            return MIN_Y + (peak[voxel] > 0 ? feet[voxel] & 255 : (voxel % LEVELS) * HEIGHT_STEP + HEIGHT_STEP / 2);
        }

        public int z(int voxel) {
            return MIN_BLOCK + (peak[voxel] > 0 ? (feet[voxel] >>> 8) & 255 : (voxel / LEVELS % SIDE) * TILE_WIDTH + TILE_WIDTH / 2);
        }

        private void deposit(int bin, double probability, double strength, int point) {
            mass[bin] += (float)probability;
            if (strength > peak[bin]) {
                peak[bin] = (float)strength;
                feet[bin] = point;
            }
        }

        private void normalizeBy(double total) {
            if (!(total > 0)) return;
            for (int i = 0; i < VOXELS; i++) {
                mass[i] /= total;
                peak[i] /= total;
            }
        }
    }

    private static int blockKey(int x, int y, int z) {
        return (x - MIN_BLOCK) << 16 | (z - MIN_BLOCK) << 8 | (y - MIN_Y);
    }

    /**
     * Returns normalized tile mass indexed as `xTile * 16 + zTile`. Support rows
     * contain issue XYZ, velocity XYZ, 17 features, outcome XYZ, and issue yaw in radians.
     */
    public float[] forecast(String model, int horizon, float[] features, float[] xyz, float[] velocity,
                            float yaw, float[][] completedSupport) {
        return forecast(model, horizon, features, xyz, velocity, yaw, completedSupport, null);
    }

    public Volume forecast3d(String model, int horizon, float[] features, float[] xyz, float[] velocity,
                             float yaw, float[][] completedSupport) {
        if (!hasVolume()) throw new IllegalStateException("3D forecasts need the updated model bundle");
        Volume volume = new Volume();
        forecast(model, horizon, features, xyz, velocity, yaw, completedSupport, volume);
        return volume;
    }

    private float[] forecast(String model, int horizon, float[] features, float[] xyz, float[] velocity,
                             float yaw, float[][] completedSupport, Volume volume) {
        int h = Arrays.binarySearch(horizons, horizon);
        if (h < 0) throw new IllegalArgumentException("Unsupported forecast horizon " + horizon);
        requireFinite(features, FEATURE_COUNT);
        requireFinite(xyz, 3);
        requireFinite(velocity, 3);
        if (!Float.isFinite(yaw)) throw new IllegalArgumentException("Invalid yaw");
        float[][] support = validSupport(completedSupport);
        double[] result;
        Heatmap map = null;
        switch (model) {
        case "heatmap-ce":
            map = exact;
            result = softmax(exact.logits(horizon, features, 0, xyz, 0, velocity, 0));
            break;
        case "heatmap":
            map = shared;
            result = softmax(shared.logits(horizon, features, 0, xyz, 0, velocity, 0));
            break;
        case "anil-prior":
            map = anil;
            result = softmax(anil.logits(horizon, features, 0, xyz, 0, velocity, 0));
            break;
        case "head-tune":
            map = shared;
            result = applyHead(shared, horizon, features, xyz, velocity, support, 38);
            break;
        case "anil":
            map = anil;
            result = applyHead(anil, horizon, features, xyz, velocity, support, steps[h]);
            break;
        case "history":
        case "meta-history":
            map = shared;
            result = softmax(shared.logits(horizon, features, 0, xyz, 0, velocity, 0));
            if (support.length >= 4) {
                double n = support.length;
                double score = historyIntercept[h] + historyCoefficients[0] * Math.log1p(n) / 4
                    + historyCoefficients[1] * features[9] + historyCoefficients[2] * features[12]
                    + historyCoefficients[3] * features[16];
                double mix = "history".equals(model) ? .7 * n / (n + 24)
                    : .94 / (1 + Math.exp(-score)) * n / (n + 4);
                blend(result, history(support), mix);
            }
            break;
        default:
            result = baseline[h].forecast(model, horizon, features, xyz, velocity, yaw, support, volume);
        }
        if (volume != null && map != null) map.fillVolume(volume, result, horizon, features, xyz);
        float[] out = new float[CELLS];
        for (int k = 0; k < CELLS; k++) out[k] = (float) result[k];
        if (volume != null) System.arraycopy(out, 0, volume.horizontal, 0, CELLS);
        return out;
    }

    private static void requireFinite(float[] values, int length) {
        if (values == null || values.length != length) throw new IllegalArgumentException("Unexpected input shape");
        for (float value : values) {
            if (!Float.isFinite(value)) throw new IllegalArgumentException("Nonfinite model input");
        }
    }

    private static float[][] validSupport(float[][] input) {
        if (input == null || input.length == 0) return new float[0][];
        int start = Math.max(0, input.length - LATEST_SUPPORT);
        float[][] valid = new float[input.length - start][];
        int count = 0;
        for (int i = start; i < input.length; i++) {
            float[] row = input[i];
            requireFinite(row, SUPPORT_WIDTH);
            if (tile(row[23], row[25]) >= 0) valid[count++] = row;
        }
        return Arrays.copyOf(valid, count);
    }

    private static double[] applyHead(Heatmap map, int h, float[] features, float[] xyz, float[] velocity,
                                      float[][] support, double step) {
        double[] logits = map.logits(h, features, 0, xyz, 0, velocity, 0);
        if (support.length >= 4) {
            double[] residual = new double[CELLS];
            for (float[] row : support) {
                double[] q = softmax(map.logits(h, row, 6, row, 0, row, 3));
                residual[tile(row[23], row[25])] += 1;
                for (int k = 0; k < CELLS; k++) residual[k] -= q[k];
            }
            double[] smooth = smooth(residual, ANIL_SMOOTH);
            double update = step / (support.length + 4.);
            for (int k = 0; k < CELLS; k++) logits[k] += update * smooth[k];
        }
        return softmax(logits);
    }

    private static double[] history(float[][] support) {
        double[] bins = new double[CELLS];
        for (float[] row : support) bins[tile(row[23], row[25])]++;
        bins = smooth(bins, HIST_SMOOTH);
        for (int k = 0; k < CELLS; k++) bins[k] += .00002;
        return normalize(bins);
    }

    private static double[] gaussianKernel(double width, int radius) {
        double[] values = new double[2 * radius + 1];
        for (int i = -radius; i <= radius; i++) values[i + radius] = Math.exp(-.5 * square(i / width));
        return normalize(values);
    }

    private static double[] smooth(double[] values, double[] kernel) {
        double[] scratch = new double[CELLS];
        double[] result = new double[CELLS];
        int radius = kernel.length / 2;
        for (int x = 0; x < SIDE; x++) {
            for (int z = 0; z < SIDE; z++) {
                for (int offset = -radius; offset <= radius; offset++) {
                    int nextX = x + offset;
                    if (nextX >= 0 && nextX < SIDE) scratch[x * SIDE + z] +=
                        kernel[offset + radius] * values[nextX * SIDE + z];
                }
            }
        }
        for (int x = 0; x < SIDE; x++) {
            for (int z = 0; z < SIDE; z++) {
                for (int offset = -radius; offset <= radius; offset++) {
                    int nextZ = z + offset;
                    if (nextZ >= 0 && nextZ < SIDE) result[x * SIDE + z] +=
                        kernel[offset + radius] * scratch[x * SIDE + nextZ];
                }
            }
        }
        return result;
    }

    private static int tile(double x, double z) {
        int ix = (int) Math.floor((x - MIN_BLOCK) / TILE_WIDTH);
        int iz = (int) Math.floor((z - MIN_BLOCK) / TILE_WIDTH);
        return ix >= 0 && ix < SIDE && iz >= 0 && iz < SIDE ? ix * SIDE + iz : -1;
    }

    private static double center(int tile) { return MIN_BLOCK + (tile + .5) * TILE_WIDTH; }
    private static double square(double number) { return number * number; }

    private static double[] softmax(double[] values) {
        double largest = Double.NEGATIVE_INFINITY;
        for (double v : values) largest = Math.max(largest, v);
        for (int k = 0; k < values.length; k++) values[k] = Math.exp(values[k] - largest);
        return normalize(values);
    }

    private static double[] normalize(double[] values) {
        double sum = 0;
        for (double value : values) sum += value;
        if (!(sum > 0)) {
            Arrays.fill(values, 1. / values.length);
        } else {
            for (int i = 0; i < values.length; i++) values[i] /= sum;
        }
        return values;
    }

    private static void blend(double[] base, double[] other, double proportion) {
        for (int i = 0; i < base.length; i++) base[i] = base[i] * (1 - proportion) + other[i] * proportion;
    }

    private static int checkedSize(DataInputStream input, int min, int max) throws IOException {
        int n = input.readInt();
        if (n < min || n > max) throw new IOException("Invalid BedWars forecast size " + n);
        return n;
    }

    private static float[] readFloats(DataInputStream input, int n) throws IOException {
        float[] values = new float[n];
        for (int i = 0; i < n; i++) {
            float v = input.readFloat();
            if (!Float.isFinite(v)) throw new IOException("Nonfinite model parameter");
            values[i] = v;
        }
        return values;
    }

    private static final class Dense {
        final int width;
        final float[] weights, bias;

        Dense(DataInputStream input, int width, int outputs) throws IOException {
            this.width = width;
            weights = readFloats(input, width * outputs);
            bias = readFloats(input, outputs);
        }

        double[] apply(double[] in, boolean tanh) {
            double[] out = new double[bias.length];
            for (int row = 0; row < out.length; row++) {
                double value = bias[row];
                int offset = row * width;
                for (int i = 0; i < width; i++) value += in[i] * weights[offset + i];
                out[row] = tanh ? Math.tanh(value) : value;
            }
            return out;
        }
    }

    private static final class Heatmap {
        final Dense hidden, encoder, mixtures, field, fineHidden, fineOutput;

        Heatmap(DataInputStream input, boolean volume) throws IOException {
            hidden = new Dense(input, 20, 32);
            encoder = new Dense(input, 32, 24);
            mixtures = new Dense(input, 24, 3);
            field = new Dense(input, 24, 64);
            fineHidden = volume ? new Dense(input, 28, 32) : null;
            fineOutput = volume ? new Dense(input, 32, 39) : null;
        }

        double[] encode(int h, float[] features, int fo, float[] xyz, int xo) {
            double[] in = new double[20];
            for (int i = 0; i < FEATURE_COUNT; i++) in[i] = features[fo + i];
            in[17] = xyz[xo] / 64.;
            in[18] = xyz[xo + 2] / 64.;
            in[19] = Math.log1p(h) / 5;
            return encoder.apply(hidden.apply(in, true), true);
        }

        double[] logits(int h, float[] features, int fo, float[] xyz, int xo, float[] velocity, int vo) {
            double x = xyz[xo], z = xyz[xo + 2];
            double[] encoded = encode(h, features, fo, xyz, xo);
            double[] mix = softmax(mixtures.apply(encoded, false));
            double[] residual = field.apply(encoded, false);
            double advance = Math.min(h, 22) * .7;
            double[] cx = {x, x + velocity[vo] * advance, 0};
            double[] cz = {z, z + velocity[vo + 2] * advance, 0};
            double[] widths = {5 + .23 * Math.min(h, 100), 10 + .44 * Math.min(h, 115), 51};
            double[] logits = new double[CELLS];
            double[] prior = new double[CELLS];
            for (int j = 0; j < mix.length; j++) {
                double scale = -.5 / square(widths[j]);
                for (int ix = 0; ix < SIDE; ix++) {
                    double dx = square(center(ix) - cx[j]);
                    for (int iz = 0; iz < SIDE; iz++) {
                        prior[ix * SIDE + iz] = scale * (dx + square(center(iz) - cz[j]));
                    }
                }
                softmax(prior);
                for (int k = 0; k < CELLS; k++) logits[k] += mix[j] * prior[k];
            }
            for (int ix = 0; ix < SIDE; ix++) {
                for (int iz = 0; iz < SIDE; iz++) {
                    int k = ix * SIDE + iz;
                    logits[k] = Math.log(Math.max(logits[k], Double.MIN_NORMAL)) + residual[(ix / 2) * 8 + iz / 2];
                }
            }
            return logits;
        }

        void fillVolume(Volume output, double[] horizontal, int h, float[] features, float[] xyz) {
            double[] input = Arrays.copyOf(encode(h, features, 0, xyz, 0), 28);
            double[] weights = new double[3], values = new double[HEIGHT_BLOCKS];
            double[] heights = new double[HEIGHT_BLOCKS];
            for (int cell = 0; cell < CELLS; cell++) {
                double cx = center(cell / SIDE), cz = center(cell % SIDE);
                input[24] = cx / 112;
                input[25] = cz / 112;
                input[26] = (cx - xyz[0]) / 64;
                input[27] = (cz - xyz[2]) / 64;
                double[] fine = fineOutput.apply(fineHidden.apply(input, true), false);
                double gate = Math.abs(input[26]) < .15 && Math.abs(input[27]) < .15
                        ? .97 / (1 + Math.exp(-fine[32])) : .001;
                int x = (int)cx - TILE_WIDTH / 2, z = (int)cz - TILE_WIDTH / 2;
                double[] px = fineAxis(fine, 0, xyz[0] - x, gate);
                double[] pz = fineAxis(fine, TILE_WIDTH, xyz[2] - z, gate);
                int bestX = maxIndex(px), bestZ = maxIndex(pz);

                weights[0] = fine[37];
                weights[1] = fine[38];
                weights[2] = -4;
                softmax(weights);
                Arrays.fill(heights, 0);
                addHeight(heights, values, xyz[1] + 12 * Math.tanh(fine[33]),
                          .65 + 3 * softplus(fine[34]), weights[0]);
                addHeight(heights, values, xyz[1] + 35 * Math.tanh(fine[35]),
                          4 + 14 * softplus(fine[36]), weights[1]);
                addHeight(heights, values, 52, 49, weights[2]);
                for (int level = 0; level < LEVELS; level++) {
                    int first = level * HEIGHT_STEP, bestY = first;
                    double sum = 0;
                    for (int y = first; y < first + HEIGHT_STEP; y++) {
                        sum += heights[y];
                        if (heights[y] > heights[bestY]) bestY = y;
                    }
                    output.deposit(cell * LEVELS + level, horizontal[cell] * sum,
                            horizontal[cell] * heights[bestY] * px[bestX] * pz[bestZ],
                            blockKey(x + bestX, MIN_Y + bestY, z + bestZ));
                }
            }
        }

        private static double[] fineAxis(double[] fine, int start, double current, double gate) {
            double[] learnt = Arrays.copyOfRange(fine, start, start + TILE_WIDTH);
            double[] close = new double[TILE_WIDTH];
            for (int i = 0; i < close.length; i++) close[i] = -.5 * square((i + .5 - current) / 1.45);
            blend(softmax(learnt), softmax(close), gate);
            return learnt;
        }

        private static void addHeight(double[] out, double[] values, double mean, double sigma, double weight) {
            for (int i = 0; i < values.length; i++) values[i] = -.5 * square((MIN_Y + i + .5 - mean) / sigma);
            softmax(values);
            for (int i = 0; i < values.length; i++) out[i] += weight * values[i];
        }

        private static double softplus(double x) { return Math.max(x, 0) + Math.log1p(Math.exp(-Math.abs(x))); }

        private static int maxIndex(double[] values) {
            int peak = 0;
            for (int i = 1; i < values.length; i++) if (values[i] > values[peak]) peak = i;
            return peak;
        }
    }

    private static double cdf(double x) {
        // Hart's rational approximation; the tail form avoids subtracting from 1 below zero.
        double a = Math.abs(x);
        double tail;
        if (a > 37) {
            tail = 0;
        } else if (a < 7.07106781186547) {
            double p = ((((((.0352624965998911 * a + .700383064443688) * a + 6.37396220353165)
                * a + 33.912866078383) * a + 112.079291497871) * a + 221.213596169931)
                * a + 220.206867912376);
            double q = (((((((.0883883476483184 * a + 1.75566716318264) * a + 16.064177579207)
                * a + 86.7807322029461) * a + 296.564248779674) * a + 637.333633378831)
                * a + 793.826512519948) * a + 440.413735824752);
            tail = Math.exp(-a * a / 2) * p / q;
        } else {
            double fraction = a + 1 / (a + 2 / (a + 3 / (a + 4 / (a + .65))));
            tail = Math.exp(-a * a / 2) / (fraction * 2.506628274631);
        }
        return x <= 0 ? tail : 1 - tail;
    }

    private static double intervalMass(double low, double high) {
        return low >= 0 ? cdf(-low) - cdf(-high) : cdf(high) - cdf(low);
    }

    private static void axisMass(double[] output, double center, double width, int origin, int step) {
        double low = (origin - center) / width;
        for (int i = 0; i < output.length; i++) {
            double high = (origin + (i + 1) * step - center) / width;
            output[i] = intervalMass(low, high);
            low = high;
        }
    }

    private static final class GaussianGrid {
        final double[] probability = new double[CELLS], x = new double[SIDE], z = new double[SIDE];
        final Volume volume;
        final double[] y = new double[LEVELS];
        final int[] bx = new int[SIDE], bz = new int[SIDE], by = new int[LEVELS];
        final double[] px = new double[SIDE], pz = new double[SIDE], py = new double[LEVELS];

        GaussianGrid(Volume volume) { this.volume = volume; }

        void add(double centerX, double centerY, double centerZ,
                 double widthX, double widthY, double widthZ, double weight) {
            axisMass(x, centerX, widthX, MIN_BLOCK, TILE_WIDTH);
            axisMass(z, centerZ, widthZ, MIN_BLOCK, TILE_WIDTH);
            if (volume != null) {
                axisMass(y, centerY, widthY, MIN_Y, HEIGHT_STEP);
                peaks(bx, px, centerX, widthX, MIN_BLOCK, TILE_WIDTH);
                peaks(by, py, centerY, widthY, MIN_Y, HEIGHT_STEP);
                peaks(bz, pz, centerZ, widthZ, MIN_BLOCK, TILE_WIDTH);
            }
            for (int i = 0; i < SIDE; i++) {
                double higher = x[i] * weight;
                for (int j = 0; j < SIDE; j++) {
                    int tile = i * SIDE + j;
                    double mass = higher * z[j];
                    probability[tile] += mass;
                    if (volume != null && mass > 0) {
                        double block = px[i] * pz[j] * weight;
                        for (int k = 0; k < LEVELS; k++) {
                            if (y[k] > 0) volume.deposit(tile * LEVELS + k, mass * y[k],
                                    block * py[k], blockKey(bx[i], by[k], bz[j]));
                        }
                    }
                }
            }
        }

        private static void peaks(int[] blocks, double[] masses, double pos, double width, int origin, int step) {
            int mean = (int)Math.floor(pos);
            for (int i = 0; i < blocks.length; i++) {
                int start = origin + i * step;
                blocks[i] = Math.max(start, Math.min(start + step - 1, mean));
                double low = (blocks[i] - pos) / width;
                masses[i] = intervalMass(low, low + 1 / width);
            }
        }

        double[] finish() {
            if (volume != null) {
                double total = 0;
                for (double value : probability) total += value;
                volume.normalizeBy(total);
            }
            return normalize(probability);
        }
    }

    private static final class Residuals {
        final float[] widths, weights;

        Residuals(DataInputStream data) throws IOException {
            widths = readFloats(data, 6);
            weights = readFloats(data, 2);
            for (float width : widths) {
                if (!(width > 0)) throw new IOException("Invalid forecast width");
            }
        }

        void add(GaussianGrid grid, double x, double y, double z, double weight) {
            for (int i = 0; i < weights.length; i++) {
                grid.add(x, y, z, widths[3 * i], widths[3 * i + 1], widths[3 * i + 2], weight * weights[i]);
            }
        }
    }

    private static final class Analogues {
        final int[] columns;
        final float[] center, scale, points, targets;

        Analogues(DataInputStream data) throws IOException {
            int width = checkedSize(data, 1, FEATURE_COUNT);
            columns = new int[width];
            for (int i = 0; i < width; i++) columns[i] = checkedSize(data, 0, FEATURE_COUNT - 1);
            center = readFloats(data, width);
            scale = readFloats(data, width);
            for (float v : scale) {
                if (!(v > 0)) throw new IOException("Invalid feature scale");
            }
            int n = checkedSize(data, 1, 200_000);
            points = readFloats(data, n * width);
            targets = readFloats(data, n * 3);
        }

        void add(GaussianGrid grid, float[] features, float[] xyz, double yaw,
                 double width, double heightWidth, double weight) {
            double[] query = new double[columns.length];
            for (int i = 0; i < columns.length; i++) {
                query[i] = ((double) features[columns[i]] - center[i]) / scale[i];
            }
            Neighbors nearest = new Neighbors(Math.min(48, targets.length / 3));
            for (int j = 0; j < targets.length / 3; j++) {
                double distance = 0;
                int offset = j * columns.length;
                for (int i = 0; i < query.length; i++) distance += square(query[i] - points[offset + i]);
                nearest.offer(j, distance);
            }
            double[] weights = nearest.relativeWeights();
            double c = Math.cos(yaw), s = Math.sin(yaw);
            for (int i = 0; i < weights.length; i++) {
                int k = nearest.indices[i] * 3;
                double dx = targets[k], dz = targets[k + 2];
                grid.add(xyz[0] + c * dx - s * dz, xyz[1] + targets[k + 1], xyz[2] + s * dx + c * dz,
                         width, heightWidth, width, weights[i] * weight);
            }
        }
    }

    /** Bounded max heap, with the most distant selected point at the root. */
    private static final class Neighbors {
        final int[] indices;
        final double[] distances;
        int used;

        Neighbors(int capacity) {
            indices = new int[capacity];
            distances = new double[capacity];
        }

        void offer(int index, double distance) {
            if (used < indices.length) {
                int child = used++;
                while (child > 0) {
                    int parent = (child - 1) / 2;
                    if (distance <= distances[parent]) break;
                    indices[child] = indices[parent];
                    distances[child] = distances[parent];
                    child = parent;
                }
                indices[child] = index;
                distances[child] = distance;
            } else if (distance < distances[0]) {
                int parent = 0;
                while (2 * parent + 1 < used) {
                    int child = parent * 2 + 1;
                    if (child + 1 < used && distances[child + 1] > distances[child]) child++;
                    if (distances[child] <= distance) break;
                    indices[parent] = indices[child];
                    distances[parent] = distances[child];
                    parent = child;
                }
                indices[parent] = index;
                distances[parent] = distance;
            }
        }

        double[] relativeWeights() {
            double[] weights = new double[used];
            double scale = -.5 / Math.max(distances[0], .25);
            for (int i = 0; i < used; i++) weights[i] = Math.exp(scale * distances[i]);
            return normalize(weights);
        }
    }

    private static double[] routeFeatures(float[] xyz, int x, float[] velocity, int v, float[] feature, int f) {
        double[] route = {xyz[x] / 11., xyz[x + 2] / 11., xyz[x + 1] / 6., velocity[v] / 2.,
            velocity[v + 2] / 2., 0, 0, 0, 0};
        for (int i = 0; i < ROUTE_FEATURES.length; i++) route[5 + i] = feature[f + ROUTE_FEATURES[i]];
        return route;
    }

    private static double[] routeWeights(float[] xyz, float[] velocity, float[] features, float[][] support) {
        double[][] track = new double[support.length][];
        double[] query = routeFeatures(xyz, 0, velocity, 0, features, 0);
        double[] mean = new double[query.length];
        for (int i = 0; i < support.length; i++) {
            float[] row = support[i];
            track[i] = routeFeatures(row, 0, row, 3, row, 6);
            for (int j = 0; j < query.length; j++) mean[j] += track[i][j] / track.length;
        }
        double[] scale = new double[query.length];
        for (double[] route : track) {
            for (int j = 0; j < query.length; j++) scale[j] += square(route[j] - mean[j]) / track.length;
        }
        for (int j = 0; j < query.length; j++) scale[j] = Math.max(Math.sqrt(scale[j]), .75);
        double[] weights = new double[track.length];
        double farthest = .25;
        for (int i = 0; i < track.length; i++) {
            for (int j = 0; j < query.length; j++) weights[i] += square((query[j] - track[i][j]) / scale[j]);
            farthest = Math.max(farthest, weights[i]);
        }
        for (int i = 0; i < weights.length; i++) weights[i] = Math.exp(-.5 * weights[i] / farthest);
        return normalize(weights);
    }

    private static final class Baseline {
        final Residuals stay, velocity, drift;
        final float[] driftCoefficients, driftIntercept;
        final Analogues main, noMotion, noContext;

        Baseline(DataInputStream source) throws IOException {
            stay = new Residuals(source);
            velocity = new Residuals(source);
            drift = new Residuals(source);
            driftCoefficients = readFloats(source, 3 * FEATURE_COUNT);
            driftIntercept = readFloats(source, 3);
            main = new Analogues(source);
            noMotion = new Analogues(source);
            noContext = new Analogues(source);
        }

        double[] forecast(String model, int h, float[] features, float[] xyz, float[] move, double yaw,
                          float[][] support, Volume volume) {
            GaussianGrid grid = new GaussianGrid(volume);
            if ("stay".equals(model)) {
                stay.add(grid, xyz[0], xyz[1], xyz[2], 1);
            } else if ("velocity".equals(model)) {
                velocity.add(grid, xyz[0] + move[0] * h, xyz[1], xyz[2] + move[2] * h, 1);
            } else if ("drift".equals(model)) {
                double dx = driftIntercept[0], dy = driftIntercept[1], dz = driftIntercept[2];
                for (int i = 0; i < FEATURE_COUNT; i++) {
                    dx += driftCoefficients[i] * (double) features[i];
                    dy += driftCoefficients[FEATURE_COUNT + i] * (double) features[i];
                    dz += driftCoefficients[2 * FEATURE_COUNT + i] * (double) features[i];
                }
                double c = Math.cos(yaw), s = Math.sin(yaw);
                drift.add(grid, xyz[0] + c * dx - s * dz, xyz[1] + dy, xyz[2] + s * dx + c * dz, .88);
                stay.add(grid, xyz[0], xyz[1], xyz[2], .12);
            } else {
                Analogues analogues;
                boolean personal = false;
                switch (model) {
                case "motion": analogues = main; break;
                case "motion-no-motion": analogues = noMotion; break;
                case "motion-no-context": analogues = noContext; break;
                case "personal-hotspots":
                case "personal-routes":
                    analogues = main;
                    personal = support.length > 0;
                    break;
                default:
                    throw new IllegalArgumentException("Unknown forecast model " + model);
                }
                double width = 2.2 + Math.sqrt(h) * .55;
                double heightWidth = 1 + Math.sqrt(h) * .15;
                double part = personal ? support.length / (support.length + 45.) : 0;
                analogues.add(grid, features, xyz, yaw, width, heightWidth, .77 * (1 - part));
                stay.add(grid, xyz[0], xyz[1], xyz[2], .23 * (1 - part));
                if (personal) {
                    double[] weights = "personal-routes".equals(model)
                        ? routeWeights(xyz, move, features, support) : null;
                    for (int i = 0; i < support.length; i++) {
                        double weight = weights == null ? 1. / support.length : weights[i];
                        grid.add(support[i][23], support[i][24], support[i][25], width, heightWidth, width, part * weight);
                    }
                }
            }
            return grid.finish();
        }
    }
}
