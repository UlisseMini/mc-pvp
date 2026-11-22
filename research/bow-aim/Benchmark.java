import practice.core.bow.BowBallistics;
import practice.core.bow.BowBallistics.Shot;
import practice.core.bow.BowMotion;
import practice.core.bow.BowMotion.Mode;
import practice.core.bow.BowMotion.Track;

import java.io.*;
import java.nio.file.*;
import java.util.Locale;
import java.util.Random;
import java.util.zip.GZIPInputStream;

public final class Benchmark {
    private static final int[] H = BowMotion.HORIZONS;
    private static final int F = BowMotion.FEATURES;
    private static final double[] RANGES = {12, 24, 38, 54};

    private interface Visitor { void accept(int game, int player, int[] ticks, float[][] positions); }

    private static void read(Path tracks, Visitor visitor) throws IOException {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(
                new GZIPInputStream(Files.newInputStream(tracks))))) {
            if (in.readInt() != 0x424f5731) throw new IOException("Wrong trajectory file");
            int games = in.readInt();
            for (int i = 0; i < games; i++) {
                int game = in.readInt(), players = in.readInt();
                for (int p = 0; p < players; p++) {
                    int n = in.readInt();
                    int[] ticks = new int[n];
                    float[][] positions = new float[n][3];
                    for (int k = 0; k < n; k++) {
                        ticks[k] = in.readInt();
                        for (int a = 0; a < 3; a++) positions[k][a] = in.readFloat();
                    }
                    visitor.accept(game, p, ticks, positions);
                }
            }
        }
    }

    private static int[] remaining(int[] ticks, float[][] pos) {
        int[] remain = new int[ticks.length];
        for (int i = ticks.length - 2; i >= 0; i--) if (ticks[i + 1] == ticks[i] + 1 &&
                Math.hypot(Math.hypot(pos[i + 1][0] - pos[i][0], pos[i + 1][2] - pos[i][2]),
                        pos[i + 1][1] - pos[i][1]) <= 2.5) remain[i] = 1 + remain[i + 1];
        return remain;
    }

    private static class Fit {
        final double[][] gram = new double[F][F + 1];
        int n;

        void add(double[] x, double y) {
            for (int j = 0; j < F; j++) {
                for (int k = 0; k < F; k++) gram[j][k] += x[j] * x[k];
                gram[j][F] += x[j] * y;
            }
            n++;
        }

        double[] solve() {
            for (int j = 0; j < F; j++) gram[j][j] += .00012 * n + .01;
            for (int j = 0; j < F; j++) {
                double pivot = gram[j][j];
                for (int k = j; k <= F; k++) gram[j][k] /= pivot;
                for (int row = 0; row < F; row++) if (row != j) {
                    double scale = gram[row][j];
                    for (int k = j; k <= F; k++) gram[row][k] -= scale * gram[j][k];
                }
            }
            double[] coefficients = new double[F];
            for (int j = 0; j < F; j++) coefficients[j] = gram[j][F];
            return coefficients;
        }
    }

    private static void train(Path data, Path output, int last) throws IOException {
        Fit[][] fits = new Fit[H.length][2];
        for (int i = 0; i < H.length; i++) { fits[i][0] = new Fit(); fits[i][1] = new Fit(); }
        read(data, (game, player, ticks, pos) -> {
            if (game > last) return;
            int[] valid = remaining(ticks, pos);
            Track track = new Track();
            for (int i = 0; i < ticks.length; i++) {
                float[] now = pos[i];
                track.add(ticks[i], now[0], now[1], now[2]);
                if (track.length() < 8 || (ticks[i] + player) % 3 != 0 || valid[i] < H[0]) continue;
                for (int a = 0; a < 3; a++) {
                    double[] x = track.features(0, a);
                    for (int j = 0; j < H.length && valid[i] >= H[j]; j++) {
                        fits[j][a == 1 ? 1 : 0].add(x, pos[i + H[j]][a] - now[a]);
                    }
                }
            }
        });
        try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(output))) {
            out.writeInt(0x424f574d);
            for (Fit[] horizon : fits) for (Fit f : horizon) for (double v : f.solve()) out.writeDouble(v);
        }
        for (int j = 0; j < H.length; j++) System.err.println("Fit " + H[j] + " ticks, " + fits[j][1].n + " examples");
    }

    private static boolean intersects(double[] from, double[] to, float[] feet) {
        double near = 0, far = 1;
        for (int a = 0; a < 3; a++) {
            double low = feet[a] - (a == 1 ? .3 : .6), high = feet[a] + (a == 1 ? 2.1 : .6);
            double delta = to[a] - from[a];
            if (Math.abs(delta) < 1e-9) {
                if (from[a] < low || from[a] > high) return false;
            } else {
                double p = (low - from[a]) / delta, q = (high - from[a]) / delta;
                near = Math.max(near, Math.min(p, q));
                far = Math.min(far, Math.max(p, q));
                if (near > far) return false;
            }
        }
        return true;
    }

    private static boolean hits(Shot shot, float[][] future, int issue, Random noise) {
        if (shot == null) return false;
        double spread = .007499999832361937;
        double speed = Math.sqrt(shot.vx * shot.vx + shot.vy * shot.vy + shot.vz * shot.vz);
        double vx = shot.vx + dispersion(noise) * spread * speed;
        double vy = shot.vy + dispersion(noise) * spread * speed;
        double vz = shot.vz + dispersion(noise) * spread * speed;
        double[] from = {shot.x, shot.y, shot.z};
        double[] to = new double[3];
        for (int t = 1; t <= BowBallistics.MAX_TICKS; t++) {
            to[0] = from[0] + vx; to[1] = from[1] + vy; to[2] = from[2] + vz;
            if (intersects(from, to, future[issue + t])) return true;
            System.arraycopy(to, 0, from, 0, 3);
            vx *= BowBallistics.DRAG;
            vy = vy * BowBallistics.DRAG - BowBallistics.GRAVITY;
            vz *= BowBallistics.DRAG;
        }
        return false;
    }

    private static double dispersion(Random noise) {
        return noise.nextGaussian() * (noise.nextBoolean() ? -1 : 1);
    }

    private static void nativeCases(Path data, Path model, Path output) throws IOException {
        BowMotion network = BowMotion.read(Files.newInputStream(model));
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(output)))) {
            out.writeInt(0x424f574e);
            read(data, (game, player, ticks, pos) -> {
                if (game < 9 || game > 12) return;
                Track track = new Track();
                int[] valid = remaining(ticks, pos);
                for (int i = 0; i < ticks.length; i++) {
                    float[] now = pos[i];
                    track.add(ticks[i], now[0], now[1], now[2]);
                    if (track.length() < 8 || valid[i] < BowBallistics.MAX_TICKS || (ticks[i] + player * 31) % 479 != 0) continue;
                    long seed = (long)ticks[i] * 9859 + player * 107L + game;
                    Random rng = new Random(seed);
                    double angle = rng.nextDouble() * 2 * Math.PI;
                    double range = RANGES[rng.nextInt(RANGES.length)];
                    double sx = now[0] + range * Math.sin(angle), sz = now[2] + range * Math.cos(angle);
                    try {
                        out.writeInt(game);
                        out.writeInt((int)range);
                        out.writeLong(seed ^ 43737);
                        out.writeDouble(8 + sx - now[0]);
                        out.writeDouble(8 + sz - now[2]);
                        for (int t = 0; t <= BowBallistics.MAX_TICKS; t++)
                            for (int a = 0; a < 3; a++) out.writeFloat(pos[i+t][a] - now[a]);
                        for (Mode mode : Mode.values()) {
                            Shot shot = BowBallistics.intercept(sx, now[1] + 1.62, sz, BowBallistics.speed(20),
                                    network.path(track, mode, BowBallistics.MAX_TICKS), .90);
                            out.writeFloat(shot == null ? Float.NaN : shot.yaw);
                            out.writeFloat(shot == null ? Float.NaN : shot.pitch);
                        }
                    } catch (IOException error) {
                        throw new UncheckedIOException(error);
                    }
                }
            });
            out.writeInt(0);
        }
    }

    private static void score(Path data, Path model, int first, int last) throws IOException {
        BowMotion network = BowMotion.read(Files.newInputStream(model));
        Mode[] modes = Mode.values();
        int[][] n = new int[13][RANGES.length];
        int[][][] hit = new int[13][RANGES.length][modes.length];
        double[][][][] err = new double[13][H.length][modes.length][2];
        int[][] examples = new int[13][H.length];
        read(data, (game, player, ticks, pos) -> {
            if (game < first || game > last) return;
            int[] valid = remaining(ticks, pos);
            Track track = new Track();
            for (int i = 0; i < ticks.length; i++) {
                float[] now = pos[i];
                track.add(ticks[i], now[0], now[1], now[2]);
                if (track.length() < 8 || valid[i] < BowBallistics.MAX_TICKS || (ticks[i] + player) % 9 != 0) continue;
                double[][][] paths = new double[modes.length][][];
                for (Mode mode : modes) paths[mode.ordinal()] = network.path(track, mode, BowBallistics.MAX_TICKS);
                for (int h = 0; h < H.length; h++) {
                    examples[game][h]++;
                    for (Mode mode : modes) {
                        double[] p = paths[mode.ordinal()][H[h]];
                        float[] actual = pos[i + H[h]];
                        err[game][h][mode.ordinal()][0] += Math.hypot(p[0] - actual[0], p[2] - actual[2]);
                        err[game][h][mode.ordinal()][1] += Math.abs(p[1] - actual[1]);
                    }
                }
                long seed = (long) ticks[i] * 9859 + player * 107L + game;
                Random settings = new Random(seed);
                double angle = settings.nextDouble() * Math.PI * 2;
                for (int range = 0; range < RANGES.length; range++) {
                    n[game][range]++;
                    double sx = now[0] + RANGES[range] * Math.sin(angle);
                    double sz = now[2] + RANGES[range] * Math.cos(angle);
                    double eye = now[1] + 1.62;
                    for (Mode mode : modes) {
                        Shot shot = BowBallistics.intercept(sx, eye, sz, BowBallistics.speed(20),
                                paths[mode.ordinal()], .90);
                        if (hits(shot, pos, i, new Random(seed ^ 43737))) hit[game][range][mode.ordinal()]++;
                    }
                }
            }
        });
        System.out.println("game,range,n,linear,shared,adaptive");
        for (int g = first; g <= last; g++) for (int range = 0; range < RANGES.length; range++) {
            System.out.printf(Locale.ROOT, "%d,%.0f,%d,%d,%d,%d%n", g, RANGES[range], n[g][range],
                    hit[g][range][0], hit[g][range][1], hit[g][range][2]);
        }
        System.out.println("game,ticks,n,linear_xz,shared_xz,adaptive_xz,linear_y,shared_y,adaptive_y");
        for (int g = first; g <= last; g++) for (int h = 0; h < H.length; h++) {
            int count = examples[g][h];
            System.out.printf(Locale.ROOT, "%d,%d,%d", g, H[h], count);
            for (int a = 0; a < 2; a++) for (Mode mode : modes)
                System.out.printf(Locale.ROOT, ",%.5f", err[g][h][mode.ordinal()][a] / count);
            System.out.println();
        }
    }

    public static void main(String[] args) throws IOException {
        Path tracks = Paths.get(args[1]), model = Paths.get(args[2]);
        if (args[0].equals("train")) train(tracks, model, Integer.parseInt(args[3]));
        else if (args[0].equals("score")) score(tracks, model, Integer.parseInt(args[3]), Integer.parseInt(args[4]));
        else if (args[0].equals("native")) nativeCases(tracks, model, Paths.get(args[3]));
        else throw new IllegalArgumentException("Choose train, score or native");
    }
}
