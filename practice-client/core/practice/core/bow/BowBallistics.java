package practice.core.bow;

/** Minecraft 1.8.9 bow launch and dry-arrow flight. Positions and speeds use world blocks and ticks. */
public final class BowBallistics {
    public static final int MAX_TICKS = 42;
    private static final int PREVIEW_TICKS = 120;
    public static final double DRAG = .99f, GRAVITY = .05f;
    private static final double OFFSET = .16f;

    private BowBallistics() {}

    public static double speed(int chargeTicks) {
        float draw = chargeTicks / 20f;
        float power = (draw * draw + draw * 2) / 3;
        return power < .1f ? 0 : 3 * Math.min(1, power);
    }

    public static final class Shot {
        public final float yaw, pitch;
        public final double time, x, y, z, vx, vy, vz;

        private Shot(double shooterX, double eyeY, double shooterZ, double speed,
                     double yaw, double pitch, double time) {
            this.yaw = (float)Math.toDegrees(yaw);
            this.pitch = (float)Math.toDegrees(pitch);
            this.time = time;
            x = shooterX - OFFSET * Math.cos(yaw);
            y = eyeY - .1f;
            z = shooterZ - OFFSET * Math.sin(yaw);
            vx = -Math.sin(yaw) * Math.cos(pitch) * speed;
            vy = -Math.sin(pitch) * speed;
            vz = Math.cos(yaw) * Math.cos(pitch) * speed;
        }

        public double xAt(double t) { return x + vx * travel(t); }
        public double yAt(double t) { return y + vy * travel(t) - fall(t); }
        public double zAt(double t) { return z + vz * travel(t); }
    }

    public static Shot fromAim(double x, double eyeY, double z, double speed, float yaw, float pitch) {
        return new Shot(x, eyeY, z, speed, Math.toRadians(yaw), Math.toRadians(pitch), PREVIEW_TICKS);
    }

    public static Shot withTime(Shot base, double time) {
        return new Shot(base.x + OFFSET * Math.cos(Math.toRadians(base.yaw)),
                base.y + .1f, base.z + OFFSET * Math.sin(Math.toRadians(base.yaw)),
                Math.sqrt(base.vx * base.vx + base.vy * base.vy + base.vz * base.vz),
                Math.toRadians(base.yaw), Math.toRadians(base.pitch), time);
    }

    private static final double[] TRAVEL = new double[PREVIEW_TICKS + 1];
    static {
        double v = 1;
        for (int t = 1; t < TRAVEL.length; t++, v *= DRAG) TRAVEL[t] = TRAVEL[t - 1] + v;
    }

    public static Shot intercept(double x, double eyeY, double z, double speed, double[][] feet, double height) {
        if (speed <= 0) return null;
        double originY = eyeY - .1f;
        double initial = Double.POSITIVE_INFINITY;
        for (int t = 1; t < feet.length; t++) {
            double required = need(x, originY, z, feet, height, t);
            if (required <= speed && initial > speed) {
                double lo = Math.max(.01, t - 1), hi = t;
                for (int i = 0; i < 17; i++) {
                    double mid = (lo + hi) * .5;
                    if (need(x, originY, z, feet, height, mid) > speed) lo = mid;
                    else hi = mid;
                }
                double dx = sample(feet, hi, 0) - x, dz = sample(feet, hi, 2) - z;
                double forward = Math.sqrt(Math.max(0, dx * dx + dz * dz - OFFSET * OFFSET));
                double yaw = Math.atan2(-dx, dz) - Math.atan2(OFFSET, forward);
                double dy = sample(feet, hi, 1) + height - originY + fall(hi);
                double pitch = -Math.atan2(dy, forward);
                return new Shot(x, eyeY, z, speed, yaw, pitch, hi);
            }
            initial = required;
        }
        return null;
    }

    private static double need(double x, double y, double z, double[][] target, double height, double t) {
        double dx = sample(target, t, 0) - x;
        double dz = sample(target, t, 2) - z;
        double dy = sample(target, t, 1) + height - y + fall(t);
        return Math.sqrt(Math.max(0, dx * dx + dz * dz - OFFSET * OFFSET) + dy * dy) / travel(t);
    }

    private static double sample(double[][] data, double t, int axis) {
        int low = (int)t;
        if (t == low || low == data.length - 1) return data[low][axis];
        return data[low][axis] + (t - low) * (data[low + 1][axis] - data[low][axis]);
    }

    public static double travel(double t) {
        double clamped = Math.max(0, Math.min(PREVIEW_TICKS, t));
        int i = (int)clamped;
        if (i >= PREVIEW_TICKS) return TRAVEL[PREVIEW_TICKS];
        return clamped == i ? TRAVEL[i] : TRAVEL[i] + (clamped - i) * (TRAVEL[i + 1] - TRAVEL[i]);
    }

    public static double fall(double t) { return GRAVITY * (t - travel(t)) / (1 - DRAG); }
}
