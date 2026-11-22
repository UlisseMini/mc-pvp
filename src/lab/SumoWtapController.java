package lab;

/** A single W release scheduled from the fighter's own movement and hit cues. */
public final class SumoWtapController {
    private static final double TICK_MS = 50, REACH_TOLERANCE = .12;
    private enum Reset { READY, RELEASE, RESUME }

    private Reset reset = Reset.READY;
    private double seenHurt = Double.NEGATIVE_INFINITY, rearmed = Double.NEGATIVE_INFINITY;
    private boolean resetOwed;

    public SumoWtapController() {}

    public SumoWtapController(SumoWtapController other) {
        reset = other.reset;
        seenHurt = other.seenHurt;
        rearmed = other.rearmed;
        resetOwed = other.resetOwed;
    }

    public enum Decision {
        HOLD, PROTECT_BRAKE, KEEP_REACH, PROTECT_EDGE, WINDOW_CLOSED, TAP, RESUME;
        public boolean forward() { return this != TAP; }
    }

    public Decision decide(double time, double targetHurt, double lastContact, double rtt, int hurtTicks,
                           double clickEvery, double lastPress, boolean queued, boolean sprint, boolean ground,
                           double x, double z, double vx, double vz, double tx, double tz,
                           double targetVx, double targetVz, double rayDistance, double reach) {
        if (targetHurt > seenHurt) {
            seenHurt = targetHurt;
            if (lastContact > rearmed) resetOwed = true;
        }
        if (reset == Reset.RELEASE) return Decision.TAP;
        if (reset == Reset.RESUME) return Decision.RESUME;
        if (!resetOwed || !sprint) return Decision.HOLD;

        // Translate the observed hurt timestamp into a local send window.
        // Leave roughly one movement tick of uncertainty in its arrival phase.
        double fresh = seenHurt + hurtTicks * TICK_MS - rtt;
        double pressIn = Math.max(0, lastPress + clickEvery - time);
        boolean clickThisTick = queued || pressIn == 0;
        if (pressIn == 0) pressIn = clickEvery;
        double earliestRearmed = time + 2 * TICK_MS;
        if (rayDistance <= reach && time + pressIn < earliestRearmed && time + pressIn >= fresh - TICK_MS)
            return Decision.WINDOW_CLOSED;

        double dx = tx - x, dz = tz - z, distance = Math.hypot(dx, dz);
        if (distance < .001) return Decision.KEEP_REACH;
        double axisX = dx / distance, axisZ = dz / distance;
        double toward = vx * axisX + vz * axisZ;
        double targetAway = targetVx * axisX + targetVz * axisZ;
        double drag = ground ? .546 : .91;
        double input = ground ? .1274 : .02548;
        double afterClick = toward;
        if (clickThisTick && rayDistance <= reach) afterClick *= .6;
        double nextHoldRay = rayDistance + targetAway - (afterClick + input);
        boolean nextClickLikely = pressIn <= 1.5 * TICK_MS;
        if (nextClickLikely && nextHoldRay <= reach + REACH_TOLERANCE && -.4 * ((afterClick + input) * drag) >= .055)
            return Decision.PROTECT_BRAKE;

        double localX = x - 4096 * Math.rint(x / 4096);
        if (!supports(localX + vx * (1 + drag), z + vz * (1 + drag)) && toward < 0)
            return Decision.PROTECT_EDGE;

        // The two schedules differ by the acceleration on the released move.
        // Protect a marginal pursuit contact as well as a defensive brake.
        double nextTapRay = nextHoldRay + input;
        if (nextClickLikely && nextHoldRay <= reach && nextTapRay >= reach - REACH_TOLERANCE && toward >= 0)
            return Decision.KEEP_REACH;
        reset = Reset.RELEASE;
        return Decision.TAP;
    }

    public void moved(double time, boolean sprint) {
        if (reset == Reset.RELEASE) reset = Reset.RESUME;
        else if (reset == Reset.RESUME && sprint) {
            reset = Reset.READY;
            resetOwed = false;
            rearmed = time;
        }
    }

    private static boolean supports(double x, double z) {
        for (int bx = (int)Math.floor(x - .3); bx < x + .3; bx++) {
            for (int bz = (int)Math.floor(z - .3); bz < z + .3; bz++) {
                if (SumoFootprint.cell(bx, bz, SumoFootprint.DEFAULT)) return true;
            }
        }
        return false;
    }
}
