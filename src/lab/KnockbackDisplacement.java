package lab;

/** Plans turns and clicks. The ordinary movement update publishes each priming turn. */
public final class KnockbackDisplacement {
    public enum Mode { EDGE, PULL }
    public enum Phase { TRACK, PRIME, STRIKE, REPOSITION, APPROACH, WAIT, NORMAL }

    public static final class Decision {
        public final Phase phase;
        public final float yaw;
        public final boolean click, jump;
        public final int lateral;

        Decision(Phase phase, float yaw, boolean click, boolean jump, int lateral) {
            this.phase = phase;
            this.yaw = yaw;
            this.click = click;
            this.jump = jump;
            this.lateral = lateral;
        }

        public boolean headOnly() { return phase != Phase.NORMAL; }
        public boolean forceForward() {
            return phase == Phase.APPROACH || phase == Phase.WAIT || phase == Phase.REPOSITION;
        }
    }

    /** The sumo disk's floor tiles, expanded by the player's half width. */
    public static final class Arena {
        private final double center;
        private final int diameter;

        public Arena(double center, int diameter) {
            this.center = center;
            this.diameter = diameter;
        }

        public boolean supports(double x, double z) {
            double localX = x - center;
            double radius = diameter / 2.0;
            for (int bx = (int)Math.floor(localX - .3); bx < localX + .3; bx++) {
                for (int bz = (int)Math.floor(z - .3); bz < z + .3; bz++) {
                    double dx = bx + .5, dz = bz + .5;
                    if (dx * dx + dz * dz <= radius * radius) return true;
                }
            }
            return false;
        }

        float edgeYaw(double x, double z, float fallback) {
            if (!supports(x, z)) return fallback;
            float outward = Math.hypot(x - center, z) < .05 ? fallback : yaw(x - center, z);
            float bestYaw = outward;
            double best = Double.POSITIVE_INFINITY;
            // Include the radial heading, then search both sides for a closer
            // exit around the actual block corners.
            for (int i = 0; i < 72; i++) {
                int offset = (i + 1) / 2 * (i % 2 == 0 ? -5 : 5);
                float angle = outward + offset;
                double dx = xComponent(angle), dz = zComponent(angle);
                double low = 0, high = .125;
                while (high < best && supports(x + dx * high, z + dz * high)) {
                    low = high;
                    high += .125;
                }
                if (supports(x + dx * high, z + dz * high)) continue;
                for (int step = 0; step < 6; step++) {
                    double mid = (low + high) / 2;
                    if (supports(x + dx * mid, z + dz * mid)) low = mid;
                    else high = mid;
                }
                if (high < best - .015625) {
                    best = high;
                    bestYaw = angle;
                }
            }
            return bestYaw;
        }
    }

    private final Mode mode;
    private final Arena arena;
    private final boolean mmc, jumpOpening;
    private long startTick = Long.MIN_VALUE, shotTick = Long.MIN_VALUE, impactTick = Long.MIN_VALUE;
    private double shotCue;
    private float approach, primed = Float.NaN;
    private boolean finished;

    public KnockbackDisplacement(Mode mode, Arena arena, boolean mmc, boolean jumpOpening) {
        this.mode = mode;
        this.arena = arena;
        this.mmc = mmc;
        this.jumpOpening = jumpOpening;
    }

    public Decision decide(long tick, double now, double lastTargetHurt, double rtt, int hitTicks,
                           double x, double z, double tx, double tz, double reach, float reportedYaw,
                           boolean grounded, boolean incomingVelocity) {
        float bearing = yaw(tx - x, tz - z);
        double distance = Math.hypot(tx - x, tz - z);
        boolean couldReach = distance <= reach + .35;
        // Cue age includes downlink plus the next click's uplink. Leave one
        // movement tick of slack for the turn before a fresh-hit opportunity.
        boolean nearFresh = now - lastTargetHurt + rtt >= (hitTicks - 1) * 50;
        if (mode == Mode.PULL)
            return pull(tick, lastTargetHurt, rtt, x, z, tx, tz, bearing,
                couldReach, nearFresh, reportedYaw, grounded, incomingVelocity);
        if (couldReach && nearFresh && !Float.isNaN(primed) && angleBetween(reportedYaw, primed) < 1) {
            primed = Float.NaN;
            return action(Phase.STRIKE, Float.NaN, true);
        }
        primed = Float.NaN;
        if (!couldReach || !nearFresh)
            return action(Phase.TRACK, Float.NaN, true);
        float edge = arena.edgeYaw(tx, tz, bearing);
        double delta = AimController.wrap(edge - bearing);
        // The 1.8.9 ray uses the head yaw from the previous living update.
        // Limit the priming turn to a conservative angular slice of the
        // target's expanded hitbox. The native selection still gates the click.
        double cone = Math.max(0, Math.toDegrees(Math.atan2(.34, distance)) - 2);
        float steer = (float)(bearing + Math.copySign(Math.min(cone, Math.abs(delta)), delta));
        double baseAlignment = Math.cos(Math.toRadians(delta));
        double steerAlignment = Math.cos(Math.toRadians(edge - steer));
        if (outwardProjection(baseAlignment, steerAlignment, mmc) <= 0) {
            int lateral = delta >= 0 ? -1 : 1;
            return new Decision(Phase.REPOSITION, Float.NaN, false, false, lateral);
        }
        if (angleBetween(steer, bearing) < 2 || angleBetween(steer, reportedYaw) < 2)
            return action(Phase.STRIKE, Float.NaN, true);
        primed = steer;
        return action(Phase.PRIME, steer, false);
    }

    private Decision pull(long tick, double lastTargetHurt, double rtt, double x, double z,
                          double tx, double tz, float bearing, boolean couldReach,
                          boolean nearFresh, float reportedYaw, boolean grounded, boolean incomingVelocity) {
        if (startTick == Long.MIN_VALUE) {
            startTick = tick;
            approach = bearing;
        }
        if (finished || tick - startTick >= 36
                || shotTick != Long.MIN_VALUE && lastTargetHurt > shotCue) {
            finished = true;
            return action(Phase.NORMAL, Float.NaN, true);
        }
        if (shotTick != Long.MIN_VALUE && tick - shotTick <= Math.ceil(rtt / 50) + 2)
            return action(Phase.WAIT, approach, false);
        double ahead = (tx - x) * xComponent(approach) + (tz - z) * zComponent(approach);
        // Keep the previous published look facing down the approach lane.
        // Once past the target, snap back just for the attack.
        if (ahead <= -.22 && couldReach && nearFresh && angleBetween(reportedYaw, approach) < 8)
            return action(Phase.STRIKE, Float.NaN, true);
        if (!arena.supports(x + .5 * xComponent(approach), z + .5 * zComponent(approach))) {
            finished = true;
            return action(Phase.NORMAL, Float.NaN, true);
        }
        if (incomingVelocity && ahead > 0 && impactTick == Long.MIN_VALUE) impactTick = tick;
        boolean jump = jumpOpening && grounded && tick == impactTick;
        // Return the opening hit as the recoil arrives. Initiating the trade
        // prematurely pushes the target away and closes the pass opportunity.
        boolean trade = ahead > 0 && impactTick != Long.MIN_VALUE && tick - impactTick <= 2
                && !Double.isFinite(lastTargetHurt);
        return new Decision(Phase.APPROACH, approach, trade, jump, 0);
    }

    public void localContact(long tick, double targetHurt) {
        if (mode == Mode.PULL) {
            shotTick = tick;
            shotCue = targetHurt;
        }
    }

    private static Decision action(Phase phase, float yaw, boolean click) {
        return new Decision(phase, yaw, click, false, 0);
    }

    private static double outwardProjection(double baseAlignment, double steerAlignment, boolean mmc) {
        return mmc ? .5274 * baseAlignment + .327 * steerAlignment
                : (double).4f * baseAlignment + .5 * steerAlignment;
    }

    private static double angleBetween(float a, float b) { return Math.abs(AimController.wrap(a - b)); }
    private static float yaw(double dx, double dz) { return (float)Math.toDegrees(Math.atan2(-dx, dz)); }
    private static double xComponent(float yaw) { return -Math.sin(Math.toRadians(yaw)); }
    private static double zComponent(float yaw) { return Math.cos(Math.toRadians(yaw)); }
}
