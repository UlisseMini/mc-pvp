package practice.core.bow;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

/** Movement estimates from consecutive client poses, using ticks as the unit of time. */
public final class BowMotion {
    public static final int[] HORIZONS = {3, 5, 8, 12, 16, 22, 30, 42};
    public static final int FEATURES = 8, CAPACITY = 256, CONTEXT = 20;
    private static final int MASK = CAPACITY - 1;
    private final double[][][] weights;

    public enum Mode { LINEAR, SHARED, ADAPTIVE }

    public BowMotion(double[][][] weights) { this.weights = weights; }

    public static BowMotion read(InputStream stream) throws IOException {
        try (DataInputStream in = new DataInputStream(stream)) {
            if (in.readInt() != 0x424f574d) throw new IOException("Invalid bow motion model");
            double[][][] weights = new double[HORIZONS.length][2][FEATURES];
            for (double[][] horizon : weights)
                for (double[] axes : horizon)
                    for (int k = 0; k < axes.length; k++) axes[k] = in.readDouble();
            return new BowMotion(weights);
        }
    }

    public static final class Track {
        private final double[][] pos = new double[3][CAPACITY];
        private final byte[] context = new byte[CAPACITY];
        private int tick = Integer.MIN_VALUE, length;

        public int tick() { return tick; }
        public int length() { return length; }

        public void add(int now, double x, double y, double z) {
            add(now, x, y, z, 0);
        }

        public void add(int now, double x, double y, double z, int cue) {
            if (now == tick) return;
            if (length == 0 || now != tick + 1 || distance(x, y, z) > 2.5) length = 0;
            tick = now;
            int index = now & MASK;
            pos[0][index] = x;
            pos[1][index] = y;
            pos[2][index] = z;
            context[index] = (byte)cue;
            length = Math.min(CAPACITY, length + 1);
        }

        private double distance(double x, double y, double z) {
            return Math.sqrt(sq(x - get(0, 0)) + sq(y - get(0, 1)) + sq(z - get(0, 2)));
        }

        public double get(int age, int axis) { return pos[axis][(tick - age) & MASK]; }
        public int cue(int age) { return context[(tick - age) & MASK] & 3; }

        /** Native observations can repeat between packets, so estimate velocity across multiple ticks. */
        private double velocity(int age, int a, int b, int axis) {
            int from = Math.min(length - 1, age + a), to = Math.min(length - 1, age + b);
            return from == to ? 0 : (get(from, axis) - get(to, axis)) / (to - from);
        }

        public double[] features(int age, int axis) {
            double a = velocity(age, 0, 2, axis), b = velocity(age, 2, 5, axis);
            double c = velocity(age, 5, 10, axis), d = velocity(age, 10, CONTEXT, axis);
            double vx = velocity(age, 0, 4, 0), vz = velocity(age, 0, 4, 2);
            double px = velocity(age, 4, 10, 0), pz = velocity(age, 4, 10, 2);
            double mismatch = Math.min(1, Math.hypot(vx - px, vz - pz) / .30);
            double speed = Math.min(1, Math.hypot(vx, vz) / .30);
            double mean = (a + b) * .5;
            if (axis == 1) {
                return new double[]{a, b, c, d, mean * Math.min(1, Math.abs(mean) / .2),
                        Math.abs(mean), mean * mismatch, Math.copySign(Math.min(.4, Math.abs(a - b)), a - b)};
            }
            return new double[]{a, b, c, d, mean * mismatch, mean * speed, c * mismatch, mean * speed * speed};
        }
    }

    /** Cache future displacements once per target and reuse them during the intercept search. */
    public double[][] path(Track track, Mode mode, int maximum) {
        double[][] path = new double[maximum + 1][3];
        double[][] anchors = new double[HORIZONS.length + 1][3];
        if (mode != Mode.LINEAR) {
            double[][] features = {track.features(0, 0), track.features(0, 1), track.features(0, 2)};
            for (int i = 0; i < HORIZONS.length; i++) {
                double[] point = anchors[i + 1];
                for (int axis = 0; axis < 3; axis++) point[axis] = dot(weights[i][axis == 1 ? 1 : 0], features[axis]);
                if (mode == Mode.ADAPTIVE) adapt(track, i, point);
                if (HORIZONS[i] >= maximum) break;
            }
        }
        int hi = 0, lower = 0;
        for (int t = 0; t <= maximum; t++) {
            while (hi + 1 < HORIZONS.length && t > HORIZONS[hi]) lower = HORIZONS[hi++];
            double u = Math.min(1, (t - lower) / (double)(HORIZONS[hi] - lower));
            for (int a = 0; a < 3; a++) {
                double delta = mode == Mode.LINEAR ? t * track.velocity(0, 0, 5, a)
                        : anchors[hi][a] * (1 - u) + anchors[hi + 1][a] * u;
                path[t][a] = track.get(0, a) + delta;
            }
        }
        return path;
    }

    private void adapt(Track track, int index, double[] displacement) {
        int h = HORIZONS[index];
        // Only finished examples enter the memory. Space their starts to reduce duplicate evidence
        // from adjacent ticks. Compare recent velocities in world axes and downweight distant starts.
        double mass = 0, squares = 0;
        double[] offset = new double[3];
        for (int age = h + 4; age + CONTEXT < track.length(); age += Math.max(4, h / 2)) {
            double distance = 0;
            for (int a = 0; a < 3; a++) {
                double fast = track.velocity(0, 0, 3, a) - track.velocity(age, 0, 3, a);
                double previous = track.velocity(0, 3, 9, a) - track.velocity(age, 3, 9, a);
                double position = track.get(0, a) - track.get(age, a);
                distance += sq(fast / .13) + sq(previous / .20) + sq(position / (a == 1 ? 2.0 : 6.0));
            }
            double w = Math.exp(-.5 * distance - age / 180.);
            if (w < .015) continue;
            mass += w;
            squares += w * w;
            for (int a = 0; a < 3; a++) {
                double expected = dot(weights[index][a == 1 ? 1 : 0], track.features(age, a));
                double error = track.get(age - h, a) - track.get(age, a) - expected;
                double limit = .14 * h + .2;
                offset[a] += w * Math.max(-limit, Math.min(limit, error));
            }
        }
        if (mass < .25) return;
        double effective = mass * mass / squares;
        double trust = .52 * mass / (mass + 1.5) * effective / (effective + 2);
        for (int a = 0; a < 3; a++) displacement[a] += trust * offset[a] / mass;
    }

    private static double dot(double[] a, double[] b) {
        double out = 0;
        for (int k = 0; k < a.length; k++) out += a[k] * b[k];
        return out;
    }

    private static double sq(double x) { return x * x; }
}
