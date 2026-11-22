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
    private static final String[] MODELS_V3 = {
        "stay", "velocity", "drift", "motion", "motion-no-motion", "motion-no-context",
        "heatmap-ce", "heatmap", "head-tune", "anil-prior", "anil", "history", "meta-history",
        "personal-hotspots", "personal-routes", "personal-gate"
    };
    private static final double[] ANIL_SMOOTH = {.25, .5, .25};
    private static final double[] HIST_SMOOTH = gaussianKernel(.65, 2);
    private static final int[] ROUTE_FEATURES = {6, 8, 12, 14};

    private final int[] horizons;
    private final Heatmap exact, shared, anil;
    private final float[] steps, historyIntercept, historyCoefficients;
    private final Baseline[] baseline;
    private final PersonalGate personalGate;

    private BedWarsPredictor(DataInputStream input) throws IOException {
        if (input.readInt() != 0x4257504d) throw new IOException("Unsupported BedWars forecast bundle");
        int version = input.readInt();
        if ((version < 1 || version > 3) || input.readInt() != SIDE) {
            throw new IOException("Unsupported BedWars forecast bundle");
        }
        int n = checkedSize(input, 1, 12);
        horizons = new int[n];
        for (int i = 0; i < n; i++) horizons[i] = checkedSize(input, 1, 300);
        boolean hasFine = version >= 2;
        exact = new Heatmap(input, hasFine);
        shared = new Heatmap(input, hasFine);
        anil = new Heatmap(input, hasFine);
        steps = readFloats(input, n);
        historyIntercept = readFloats(input, n);
        historyCoefficients = readFloats(input, 4);
        baseline = new Baseline[n];
        for (int i = 0; i < n; i++) baseline[i] = new Baseline(input);
        personalGate = version >= 3 ? new PersonalGate(input, n) : null;
        if (input.read() != -1) throw new IOException("Unexpected BedWars forecast data");
    }

    public static BedWarsPredictor load(Path bundle) throws IOException {
        try (DataInputStream in = new DataInputStream(
                new BufferedInputStream(new GZIPInputStream(Files.newInputStream(bundle))))) {
            return new BedWarsPredictor(in);
        }
    }

    public int[] horizons() { return horizons.clone(); }
    public String[] models() { return (personalGate != null ? MODELS_V3 : MODELS).clone(); }
    public boolean hasVolume() { return shared.fineHidden != null; }
    public boolean hasPersonalGate() { return personalGate != null; }

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

        /** Probabilistic union across players: P(at least one player here) = 1 - prod(1 - p_i). */
        public void unionWith(Volume source) {
            for (int i = 0; i < CELLS; i++) {
                float a = Math.max(0f, Math.min(1f, horizontal[i]));
                float b = Math.max(0f, Math.min(1f, source.horizontal[i]));
                horizontal[i] = 1f - (1f - a) * (1f - b);
            }
            for (int i = 0; i < VOXELS; i++) {
                float a = Math.max(0f, Math.min(1f, mass[i]));
                float b = Math.max(0f, Math.min(1f, source.mass[i]));
                mass[i] = 1f - (1f - a) * (1f - b);
                if (source.peak[i] > peak[i]) {
                    peak[i] = source.peak[i];
                    feet[i] = source.feet[i];
                }
            }
        }

        /** Reweight voxel probabilities onto loaded walkable terrain while keeping airWeight for mid-air/void. */
        public void applyTerrain(float[] voxelWeight, int[] walkableFeet, float airWeight) {
            applyTerrain(voxelWeight, walkableFeet, null, airWeight);
        }

        /** Reweight voxel probabilities onto loaded walkable terrain and BFS-reachable tiles. */
        public void applyTerrain(float[] voxelWeight, int[] walkableFeet, float[] tileReachWeight, float airWeight) {
            if (voxelWeight == null && tileReachWeight == null) return;
            if (voxelWeight != null && voxelWeight.length != VOXELS) return;
            if (tileReachWeight != null && tileReachWeight.length != CELLS) return;
            double total = 0;
            for (int cell = 0; cell < CELLS; cell++) {
                float reach = tileReachWeight != null ? tileReachWeight[cell] : 1f;
                int base = cell * LEVELS;
                for (int level = 0; level < LEVELS; level++) {
                    int i = base + level;
                    float terrainFactor = voxelWeight == null ? 1f : (voxelWeight[i] > 0f ? voxelWeight[i] : airWeight);
                    float factor = terrainFactor * reach;
                    mass[i] *= factor;
                    peak[i] *= factor;
                    total += mass[i];
                    if (walkableFeet != null && walkableFeet[i] != 0) {
                        feet[i] = walkableFeet[i];
                        if (mass[i] > 0f && peak[i] <= 0f) peak[i] = mass[i];
                    }
                }
            }
            if (total > 1e-12) {
                float inv = (float)(1.0 / total);
                for (int i = 0; i < VOXELS; i++) {
                    mass[i] *= inv;
                    peak[i] *= inv;
                }
            }
            Arrays.fill(horizontal, 0f);
            for (int cell = 0; cell < CELLS; cell++) {
                float sum = 0f;
                int base = cell * LEVELS;
                for (int level = 0; level < LEVELS; level++) sum += mass[base + level];
                horizontal[cell] = sum;
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

    public static int packFeet(int x, int y, int z) {
        return blockKey(x, y, z);
    }

    /** Reweight a 2D tile distribution by per-tile reachability weights and renormalize. */
    public static void applyTileReach(float[] horizontal, float[] tileReachWeight) {
        if (horizontal == null || tileReachWeight == null || horizontal.length != CELLS || tileReachWeight.length != CELLS) return;
        double total = 0;
        for (int i = 0; i < CELLS; i++) {
            horizontal[i] *= tileReachWeight[i];
            total += horizontal[i];
        }
        if (total > 1e-12) {
            float inv = (float)(1.0 / total);
            for (int i = 0; i < CELLS; i++) horizontal[i] *= inv;
        }
    }

    /**
     * Fast weighted BFS over 4x4-block terrain patches from a player's current block.
     * Walking along loaded islands/bridges costs 4/step; bridging over loaded void costs 12/step;
     * unloaded distant chunks cost 6/step so unseen islands are never falsely zeroed.
     */
    public static float[] reachabilityMask(int[] patchSurfaceY, int patches,
                                           float playerX, float playerY, float playerZ,
                                           int horizonSec, float airWeight) {
        float[] tileWeight = new float[CELLS];
        Arrays.fill(tileWeight, 1f);
        if (patchSurfaceY == null || patches <= 0 || patchSurfaceY.length != patches * patches) return tileWeight;
        int patchBlocks = (SIDE * TILE_WIDTH) / patches;
        int sx = Math.max(0, Math.min(patches - 1, (int)Math.floor((playerX - MIN_BLOCK) / patchBlocks)));
        int sz = Math.max(0, Math.min(patches - 1, (int)Math.floor((playerZ - MIN_BLOCK) / patchBlocks)));
        int totalNodes = patches * patches;
        int[] dist = new int[totalNodes];
        Arrays.fill(dist, Integer.MAX_VALUE);
        int maxCost = Math.round(5.8f * horizonSec + 14.0f);
        int cutoff = maxCost * 3 + 48;
        // Dial's algorithm (bucket queue) over integer block-equivalent costs: O(V + E), <0.15ms.
        int buckets = cutoff + 24;
        int[] head = new int[buckets];
        Arrays.fill(head, -1);
        int[] next = new int[totalNodes];
        int[] prev = new int[totalNodes];
        Arrays.fill(next, -1);
        Arrays.fill(prev, -1);
        int start = sx * patches + sz;
        dist[start] = 0;
        head[0] = start;
        final int[] DX = {1, -1, 0, 0};
        final int[] DZ = {0, 0, 1, -1};
        for (int cost = 0; cost <= cutoff; cost++) {
            int u = head[cost];
            while (u != -1) {
                head[cost] = next[u];
                if (head[cost] != -1) prev[head[cost]] = -1;
                next[u] = prev[u] = -1;
                if (dist[u] == cost) {
                    int ux = u / patches, uz = u % patches;
                    int uy = patchSurfaceY[u];
                    for (int dir = 0; dir < 4; dir++) {
                        int vx = ux + DX[dir], vz = uz + DZ[dir];
                        if (vx < 0 || vx >= patches || vz < 0 || vz >= patches) continue;
                        int v = vx * patches + vz;
                        int vy = patchSurfaceY[v];
                        int step;
                        if (vy > 0) {
                            int dy = uy > 0 ? Math.abs(vy - uy) : 0;
                            step = patchBlocks + (dy > 4 ? dy - 4 : 0);
                        } else if (vy == 0) {
                            step = patchBlocks * 3;
                        } else {
                            step = patchBlocks + patchBlocks / 2;
                        }
                        int nd = cost + step;
                        if (nd < dist[v] && nd <= cutoff) {
                            int old = dist[v];
                            if (old <= cutoff) {
                                int p = prev[v], n = next[v];
                                if (p != -1) next[p] = n; else if (head[old] == v) head[old] = n;
                                if (n != -1) prev[n] = p;
                            }
                            dist[v] = nd;
                            int h = head[nd];
                            next[v] = h;
                            prev[v] = -1;
                            if (h != -1) prev[h] = v;
                            head[nd] = v;
                        }
                    }
                }
                u = head[cost];
            }
        }
        int sub = patches / SIDE;
        float decayScale = 0.35f * maxCost + 14.0f;
        for (int tx = 0; tx < SIDE; tx++) {
            for (int tz = 0; tz < SIDE; tz++) {
                int best = Integer.MAX_VALUE;
                for (int ox = 0; ox < sub; ox++) {
                    int row = (tx * sub + ox) * patches + tz * sub;
                    for (int oz = 0; oz < sub; oz++) {
                        int d = dist[row + oz];
                        if (d < best) best = d;
                    }
                }
                if (best <= maxCost) {
                    tileWeight[tx * SIDE + tz] = 1f;
                } else if (best == Integer.MAX_VALUE) {
                    tileWeight[tx * SIDE + tz] = airWeight;
                } else {
                    float w = (float)Math.exp(-(best - maxCost) / decayScale);
                    tileWeight[tx * SIDE + tz] = Math.max(airWeight, w);
                }
            }
        }
        return tileWeight;
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
        return forecast(model, horizon, features, xyz, velocity, yaw, completedSupport, null, null);
    }

    public float[] forecast(String model, int horizon, float[] features, float[] xyz, float[] velocity,
                            float yaw, float[][] completedSupport, BedWarsForecastHistory.PersonalContext personal) {
        return forecast(model, horizon, features, xyz, velocity, yaw, completedSupport, personal, null);
    }

    public Volume forecast3d(String model, int horizon, float[] features, float[] xyz, float[] velocity,
                             float yaw, float[][] completedSupport) {
        return forecast3d(model, horizon, features, xyz, velocity, yaw, completedSupport, null);
    }

    public Volume forecast3d(String model, int horizon, float[] features, float[] xyz, float[] velocity,
                             float yaw, float[][] completedSupport, BedWarsForecastHistory.PersonalContext personal) {
        if (!hasVolume()) throw new IllegalStateException("3D forecasts need the updated model bundle");
        Volume volume = new Volume();
        forecast(model, horizon, features, xyz, velocity, yaw, completedSupport, personal, volume);
        return volume;
    }

    private float[] forecast(String model, int horizon, float[] features, float[] xyz, float[] velocity,
                             float yaw, float[][] completedSupport, BedWarsForecastHistory.PersonalContext personal,
                             Volume volume) {
        int h = Arrays.binarySearch(horizons, horizon);
        if (h < 0) throw new IllegalArgumentException("Unsupported forecast horizon " + horizon);
        requireFinite(features, FEATURE_COUNT);
        requireFinite(xyz, 3);
        requireFinite(velocity, 3);
        if (!Float.isFinite(yaw)) throw new IllegalArgumentException("Invalid yaw");
        if ("personal-gate".equals(model)) {
            if (personalGate == null) throw new IllegalStateException("personal-gate requires a v3 model bundle");
            return forecastPersonalGate(h, horizon, features, xyz, velocity, yaw, personal, volume);
        }
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
                double baseY = xyz[1] - features[10] * 10.0;
                addHeight(heights, values, baseY, 49, weights[2]);
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
                grid.add(xyz[0] + c * dx - s * dz, Math.floor(xyz[1] + targets[k + 1]) + .5, xyz[2] + s * dx + c * dz,
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
            double cy = Math.floor(xyz[1]) + .5;
            if ("stay".equals(model)) {
                stay.add(grid, xyz[0], cy, xyz[2], 1);
            } else if ("velocity".equals(model)) {
                velocity.add(grid, xyz[0] + move[0] * h, cy, xyz[2] + move[2] * h, 1);
            } else if ("drift".equals(model)) {
                double dx = driftIntercept[0], dy = driftIntercept[1], dz = driftIntercept[2];
                for (int i = 0; i < FEATURE_COUNT; i++) {
                    dx += driftCoefficients[i] * (double) features[i];
                    dy += driftCoefficients[FEATURE_COUNT + i] * (double) features[i];
                    dz += driftCoefficients[2 * FEATURE_COUNT + i] * (double) features[i];
                }
                double c = Math.cos(yaw), s = Math.sin(yaw);
                drift.add(grid, xyz[0] + c * dx - s * dz, Math.floor(xyz[1] + dy) + .5, xyz[2] + s * dx + c * dz, .88);
                stay.add(grid, xyz[0], cy, xyz[2], .12);
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
                stay.add(grid, xyz[0], cy, xyz[2], .23 * (1 - part));
                if (personal) {
                    double[] weights = "personal-routes".equals(model)
                        ? routeWeights(xyz, move, features, support) : null;
                    for (int i = 0; i < support.length; i++) {
                        double weight = weights == null ? 1. / support.length : weights[i];
                        grid.add(support[i][23], Math.floor(support[i][24]) + .5, support[i][25], width, heightWidth, width, part * weight);
                    }
                }
            }
            return grid.finish();
        }
    }

    private float[] forecastPersonalGate(int hIndex, int horizon, float[] features, float[] xyz, float[] velocity,
                                         float yaw, BedWarsForecastHistory.PersonalContext personal, Volume volume) {
        double[] gateFeatures = new double[33];
        for (int i = 0; i < FEATURE_COUNT; i++) gateFeatures[i] = features[i];

        boolean[] available = new boolean[7];
        available[0] = true; // heatmap
        available[1] = true; // motion
        available[2] = true; // stay tightly

        // Windowed personal trail clouds: recent (24s), older (>=18s, stride 3, cap 88), home (cap 48)
        double[][] recentPts = null, oldPts = null, homePts = null, routePts = null;
        double[] recentW = null, oldW = null, homeW = null, routeW = null;

        float[][] trail = personal == null ? new float[0][] : personal.trail;
        int nowSec = personal == null ? 0 : personal.second;
        if (trail.length > 0) {
            int n = trail.length;
            // 1. Recent trail: last 24 entries with age < 24
            double[][] rTmp = new double[24][3];
            double[] rWTmp = new double[24];
            int rCount = 0;
            double rSum = 0, meanX = 0, meanZ = 0;
            double speedSum = 0;
            int speedCount = 0, slowCount = 0, slowRun = 0;
            boolean slowStillGoing = true;
            int curTileX = (int)Math.floor((xyz[0] + 128.0) / 16.0);
            int curTileZ = (int)Math.floor((xyz[2] + 128.0) / 16.0);
            int sameRecent = 0;
            float[] home = trail[0];
            boolean usableHome = home[0] <= 10f && Math.hypot(home[1], home[3]) >= 36.0;
            int homeFreq = 0;
            for (int k = 0; k < 24 && n - 1 - k >= 0; k++) {
                int idx = n - 1 - k;
                float[] pt = trail[idx];
                double age = nowSec - pt[0];
                if (age < 0 || age >= 24) break;
                double w = Math.exp(-age / 10.0);
                rTmp[rCount][0] = pt[1]; rTmp[rCount][1] = pt[2]; rTmp[rCount][2] = pt[3];
                rWTmp[rCount] = w;
                rCount++;
                rSum += w;
                meanX += pt[1] * w;
                meanZ += pt[3] * w;
                int tx = (int)Math.floor((pt[1] + 128.0) / 16.0);
                int tz = (int)Math.floor((pt[3] + 128.0) / 16.0);
                if (tx == curTileX && tz == curTileZ) sameRecent++;
                if (usableHome && Math.hypot(pt[1] - home[1], pt[3] - home[3]) <= 15.0) homeFreq++;
                if (idx > 0 && pt[0] - trail[idx - 1][0] == 1f) {
                    double move = Math.hypot(pt[1] - trail[idx - 1][1], pt[3] - trail[idx - 1][3]);
                    if (move <= 10.0) {
                        speedSum += move;
                        speedCount++;
                        boolean isSlow = move < 0.45;
                        if (isSlow) {
                            slowCount++;
                            if (slowStillGoing) slowRun++;
                        } else {
                            slowStillGoing = false;
                        }
                    } else {
                        slowStillGoing = false;
                    }
                } else {
                    slowStillGoing = false;
                }
            }
            if (rCount > 0 && rSum > 0) {
                recentPts = Arrays.copyOf(rTmp, rCount);
                recentW = Arrays.copyOf(rWTmp, rCount);
                for (int i = 0; i < rCount; i++) recentW[i] /= rSum;
                available[3] = true;
                meanX /= rSum;
                meanZ /= rSum;
            } else {
                meanX = xyz[0];
                meanZ = xyz[2];
            }

            // 2. Older locations: stamps % 3 == 0 and age >= 18, newest 88
            double[][] oTmp = new double[88][3];
            double[] oWTmp = new double[88];
            int oCount = 0;
            double oSum = 0, oSameW = 0;
            for (int idx = n - 1; idx >= 0 && oCount < 88; idx--) {
                float[] pt = trail[idx];
                int t = Math.round(pt[0]);
                double age = nowSec - t;
                if (age < 18 || (t % 3) != 0) continue;
                double w = Math.exp(-age / 185.0);
                oTmp[oCount][0] = pt[1]; oTmp[oCount][1] = pt[2]; oTmp[oCount][2] = pt[3];
                oWTmp[oCount] = w;
                oCount++;
                oSum += w;
                int tx = (int)Math.floor((pt[1] + 128.0) / 16.0);
                int tz = (int)Math.floor((pt[3] + 128.0) / 16.0);
                if (tx == curTileX && tz == curTileZ) oSameW += w;
            }
            if (oCount > 0 && oSum > 0) {
                oldPts = Arrays.copyOf(oTmp, oCount);
                oldW = Arrays.copyOf(oWTmp, oCount);
                for (int i = 0; i < oCount; i++) oldW[i] /= oSum;
                available[4] = true;
            }

            // 3. Home visits: within 15 horizontal, 9 vertical of home, stamps % 2 == 0, newest 48
            double[][] hTmp = new double[48][3];
            double[] hWTmp = new double[48];
            int hCount = 0;
            if (usableHome) {
                for (int idx = n - 1; idx >= 0 && hCount < 48; idx--) {
                    float[] pt = trail[idx];
                    int t = Math.round(pt[0]);
                    if ((t & 1) != 0) continue;
                    if (Math.hypot(pt[1] - home[1], pt[3] - home[3]) <= 15.0 && Math.abs(pt[2] - home[2]) <= 9.0) {
                        hTmp[hCount][0] = pt[1]; hTmp[hCount][1] = pt[2]; hTmp[hCount][2] = pt[3];
                        hWTmp[hCount] = 1.0;
                        hCount++;
                    }
                }
            }
            if (hCount > 0) {
                homePts = Arrays.copyOf(hTmp, hCount);
                homeW = Arrays.copyOf(hWTmp, hCount);
                for (int i = 0; i < hCount; i++) homeW[i] /= hCount;
                available[6] = true;
            }

            double denomSpeed = Math.max(1, speedCount);
            double denomRecent = Math.max(1, rCount);
            double shareRecent = sameRecent / denomRecent;
            double shareOld = oSameW / Math.max(1e-6, oSum);
            double distHome = Math.hypot(xyz[0] - home[1], xyz[2] - home[3]);
            gateFeatures[17] = (speedSum / denomSpeed) / 4.0;
            gateFeatures[18] = slowCount / denomSpeed;
            gateFeatures[19] = slowRun / 24.0;
            gateFeatures[20] = Math.hypot(xyz[0] - meanX, xyz[2] - meanZ) / 30.0;
            gateFeatures[21] = rCount / 24.0;
            gateFeatures[22] = Math.log1p(oSum) / 5.0;
            gateFeatures[23] = shareRecent;
            gateFeatures[24] = shareOld;
            gateFeatures[25] = shareRecent - shareOld;
            gateFeatures[26] = usableHome ? 1.0 : 0.0;
            gateFeatures[27] = usableHome ? distHome / 80.0 : 0.0;
            gateFeatures[28] = usableHome ? (homeFreq / denomRecent) : 0.0;
            gateFeatures[29] = Math.log1p(hCount) / 4.0;
        }

        // 4. Route matches from matured routes
        float[][] routes = personal == null ? new float[0][] : personal.routes;
        if (routes.length > 0) {
            double[] qg = {
                xyz[0] / 13.0, xyz[1] / 6.0, xyz[2] / 13.0,
                features[0] / 2.2, features[2] / 2.2,
                features[3] / 3.4, features[4] / 3.4, features[5] / 3.4,
                features[8] / 2.0
            };
            Neighbors best = new Neighbors(Math.min(20, routes.length));
            for (int i = 0; i < routes.length; i++) {
                float[] r = routes[i];
                double age = nowSec - r[27] - horizon;
                if (age < 0) continue;
                double ds = square(qg[0] - r[0] / 13.0) + square(qg[1] - r[1] / 6.0) + square(qg[2] - r[2] / 13.0)
                        + square(qg[3] - r[6] / 2.2) + square(qg[4] - r[8] / 2.2)
                        + square(qg[5] - r[9] / 3.4) + square(qg[6] - r[10] / 3.4) + square(qg[7] - r[11] / 3.4)
                        + square(qg[8] - r[14] / 2.0);
                best.offer(i, ds + age / 170.0);
            }
            if (best.used > 0) {
                double minDist = Double.POSITIVE_INFINITY, sumDist = 0;
                for (int i = 0; i < best.used; i++) {
                    minDist = Math.min(minDist, best.distances[i]);
                    sumDist += best.distances[i];
                }
                routePts = new double[best.used][3];
                routeW = new double[best.used];
                double wSum = 0;
                for (int i = 0; i < best.used; i++) {
                    float[] r = routes[best.indices[i]];
                    routePts[i][0] = r[23]; routePts[i][1] = r[24]; routePts[i][2] = r[25];
                    routeW[i] = Math.exp(-0.55 * (best.distances[i] - minDist));
                    wSum += routeW[i];
                }
                for (int i = 0; i < best.used; i++) routeW[i] /= wSum;
                available[5] = true;
                gateFeatures[30] = Math.log1p(best.used) / 3.1;
                gateFeatures[31] = Math.sqrt(Math.min(49.0, minDist)) / 6.0;
                gateFeatures[32] = Math.sqrt(Math.min(49.0, sumDist / best.used)) / 6.0;
            } else {
                gateFeatures[30] = 0; gateFeatures[31] = 7.0 / 6.0; gateFeatures[32] = 7.0 / 6.0;
            }
        } else {
            gateFeatures[30] = 0; gateFeatures[31] = 7.0 / 6.0; gateFeatures[32] = 7.0 / 6.0;
        }

        double[] gw = personalGate.weights(gateFeatures, hIndex, available);

        // Combine experts into horizontal + optional 3D Volume
        double[] horiz = new double[CELLS];
        // Expert 0: Heatmap
        if (gw[0] > 1e-6) {
            double[] hm = softmax(shared.logits(horizon, features, 0, xyz, 0, velocity, 0));
            for (int k = 0; k < CELLS; k++) horiz[k] += gw[0] * hm[k];
            if (volume != null) {
                Volume hmVol = new Volume();
                shared.fillVolume(hmVol, hm, horizon, features, xyz);
                volume.add(hmVol, (float)gw[0]);
            }
        }
        // Expert 1: Motion mixture
        if (gw[1] > 1e-6) {
            Volume mVol = volume != null ? new Volume() : null;
            double[] mot = baseline[hIndex].forecast("motion", horizon, features, xyz, velocity, yaw, new float[0][], mVol);
            for (int k = 0; k < CELLS; k++) horiz[k] += gw[1] * mot[k];
            if (volume != null) volume.add(mVol, (float)gw[1]);
        }
        // Experts 2..6: Gaussian clouds centered on discrete feet blocks (floor(pt) + 0.5)
        double gaussTotalW = gw[2] + gw[3] + gw[4] + gw[5] + gw[6];
        if (gaussTotalW > 1e-6) {
            Volume gVol = volume != null ? new Volume() : null;
            GaussianGrid grid = new GaussianGrid(gVol);
            if (gw[2] > 1e-6) {
                double sx = Math.floor(xyz[0]) + 0.5, sy = Math.floor(xyz[1]) + 0.5, sz = Math.floor(xyz[2]) + 0.5;
                grid.add(sx, sy, sz, 0.5, 0.46, 0.5, gw[2] * 0.68 / gaussTotalW);
                grid.add(sx, sy, sz, 1.9, 0.87, 1.9, gw[2] * 0.24 / gaussTotalW);
                grid.add(sx, sy, sz, 6.5, 2.1, 6.5, gw[2] * 0.08 / gaussTotalW);
            }
            if (gw[3] > 1e-6 && recentPts != null) {
                double xz = 1.1 + 0.016 * horizon, yw = 0.55 + 0.006 * horizon;
                for (int i = 0; i < recentPts.length; i++)
                    grid.add(Math.floor(recentPts[i][0]) + 0.5, Math.floor(recentPts[i][1]) + 0.5,
                             Math.floor(recentPts[i][2]) + 0.5, xz, yw, xz, gw[3] * recentW[i] / gaussTotalW);
            }
            if (gw[4] > 1e-6 && oldPts != null) {
                double xz = 1.3 + 0.020 * horizon, yw = 0.67 + 0.006 * horizon;
                for (int i = 0; i < oldPts.length; i++)
                    grid.add(Math.floor(oldPts[i][0]) + 0.5, Math.floor(oldPts[i][1]) + 0.5,
                             Math.floor(oldPts[i][2]) + 0.5, xz, yw, xz, gw[4] * oldW[i] / gaussTotalW);
            }
            if (gw[5] > 1e-6 && routePts != null) {
                double xz = 2.2 + 0.027 * horizon, yw = 0.8 + 0.007 * horizon;
                for (int i = 0; i < routePts.length; i++)
                    grid.add(Math.floor(routePts[i][0]) + 0.5, Math.floor(routePts[i][1]) + 0.5,
                             Math.floor(routePts[i][2]) + 0.5, xz, yw, xz, gw[5] * routeW[i] / gaussTotalW);
            }
            if (gw[6] > 1e-6 && homePts != null) {
                double xz = 1.05 + 0.009 * horizon, yw = 0.55 + 0.004 * horizon;
                for (int i = 0; i < homePts.length; i++)
                    grid.add(Math.floor(homePts[i][0]) + 0.5, Math.floor(homePts[i][1]) + 0.5,
                             Math.floor(homePts[i][2]) + 0.5, xz, yw, xz, gw[6] * homeW[i] / gaussTotalW);
            }
            double[] gp = grid.finish();
            for (int k = 0; k < CELLS; k++) horiz[k] += gaussTotalW * gp[k];
            if (volume != null) volume.add(gVol, (float)gaussTotalW);
        }
        normalize(horiz);
        float[] out = new float[CELLS];
        for (int k = 0; k < CELLS; k++) out[k] = (float)horiz[k];
        if (volume != null) System.arraycopy(out, 0, volume.horizontal, 0, CELLS);
        return out;
    }

    private static final class PersonalGate {
        final int horizons;
        final float[] offset, center, scale;
        final Dense hidden, output;

        PersonalGate(DataInputStream input, int horizons) throws IOException {
            this.horizons = horizons;
            offset = readFloats(input, horizons * 7);
            center = readFloats(input, 33);
            scale = readFloats(input, 33);
            hidden = new Dense(input, 33 + horizons, 24);
            output = new Dense(input, 24, 7);
        }

        double[] weights(double[] features, int hIndex, boolean[] available) {
            double[] in = new double[33 + horizons];
            for (int i = 0; i < 33; i++) {
                double v = (features[i] - center[i]) / Math.max(1e-6, scale[i]);
                in[i] = Math.max(-5.0, Math.min(5.0, v));
            }
            in[33 + hIndex] = 1.0;
            double[] logits = output.apply(hidden.apply(in, true), false);
            for (int k = 0; k < 7; k++) {
                logits[k] = available[k] ? logits[k] + offset[hIndex * 7 + k] : -1e8;
            }
            return softmax(logits);
        }
    }
}
