package lab;

import java.util.ArrayList;
import java.util.List;

/**
 * Reconstructs a vanilla remote player's quantized interpolation goal from
 * visible positions alone. No packets, remote motion fields or game objects
 * enter this observer. Feed EVERY newly eligible delayed observation, in native
 * tick order, after enforcing the policy's reaction delay outside this class.
 *
 * Use absolute world coordinates: packet goals are on the world 1/32 grid.
 * Subtracting an arbitrary origin before calling update destroys that grid.
 * ownTick is our own monotonically increasing native tick ordinal, not the
 * remote entity's ticksExisted or a rounded wall clock. Duplicate calls with
 * the same ordinal are harmless; a missing tick invalidates that difference.
 *
 * For consecutive visible positions p and q, native interpolation implies
 * goal = p + n * (q - p), where n is 1, 2 or 3. The common n across XYZ must
 * produce a point on the 1/32 grid. If several n fit, continuation of the
 * previous inferred interpolation wins; otherwise n=3 wins. Ambiguity remains
 * explicit: quantization does not make this inverse problem always unique.
 *
 * Client observations are assumed to be sampled BEFORE world entity update.
 * A newly inferred n=3 goal first drives movement after the PREVIOUS sample.
 * Packets received but not yet used by a visible update cannot be inferred.
 * This reconstructs a delayed measurement, never the defender's current body.
 */
public final class VisibleTracker {
    public static final double GRID = 1.0 / 32.0;
    private static final double GRID_EPSILON = 1e-6;
    private static final double STILL_EPSILON = 1e-10;
    private static final long TICK_MS = 50;

    public enum Confidence {
        NONE, STATIONARY, AMBIGUOUS, CONTINUATION, UNIQUE
    }

    /** One mathematically admissible divisor and quantized goal. */
    public static final class Candidate {
        public final int divisor;
        public final double x, y, z, gridError;
        private Candidate(int divisor, double x, double y, double z, double error) {
            this.divisor = divisor;
            this.x = x; this.y = y; this.z = z; this.gridError = error;
        }
    }

    /** Immutable result. Position units are blocks; velocity is blocks/tick. */
    public static final class Estimate {
        public final long ownTick, sampleTimeMs;
        public final boolean valid, consecutive, goalChanged, ambiguous;
        public final Confidence confidence;
        public final double goalX, goalY, goalZ, gridError;
        /** Divisor used for the just-observed step; zero means stationary. */
        public final int divisor, remainingAfter;
        /**
         * Estimated first tick at whose world update this goal was used.
         * Long.MIN_VALUE means unknown (for example, initial stationary state).
         * This time is retained across continuation, not refreshed every tick.
         */
        public final long firstUseTick, firstUseTimeMs;
        /** True if n=3 was observed, rather than backdated from n=1 or n=2. */
        public final boolean timingEstablished;
        /** All exact-grid alternatives, with the chosen candidate first. */
        private final Candidate[] alternatives;
        /**
         * Secant velocity between distinct inferred goals, NOT instantaneous
         * motion. Do not overwrite current velocity with this across a hit.
         */
        public final boolean velocityValid;
        public final double vx, vy, vz;
        public final long velocityIntervalMs;

        private Estimate(long tick, long time, boolean valid, boolean consecutive,
                boolean changed, Confidence confidence, Candidate chosen,
                Candidate[] alternatives, long firstTick, long firstTime,
                boolean timingEstablished, boolean velocityValid,
                double vx, double vy, double vz, long velocityInterval) {
            ownTick = tick; sampleTimeMs = time;
            this.valid = valid; this.consecutive = consecutive;
            goalChanged = changed; this.confidence = confidence;
            this.alternatives = alternatives;
            ambiguous = alternatives.length > 1;
            goalX = chosen == null ? Double.NaN : chosen.x;
            goalY = chosen == null ? Double.NaN : chosen.y;
            goalZ = chosen == null ? Double.NaN : chosen.z;
            gridError = chosen == null ? Double.NaN : chosen.gridError;
            divisor = chosen == null ? 0 : chosen.divisor;
            remainingAfter = Math.max(0, divisor - 1);
            firstUseTick = firstTick; firstUseTimeMs = firstTime;
            this.timingEstablished = timingEstablished;
            this.velocityValid = velocityValid;
            this.vx = vx; this.vy = vy; this.vz = vz;
            velocityIntervalMs = velocityInterval;
        }

        public Candidate[] candidates() { return alternatives.clone(); }

        /** Qualitative confidence, not a calibrated probability. */
        public boolean highConfidence() {
            return confidence == Confidence.UNIQUE || confidence == Confidence.CONTINUATION;
        }

        /**
         * Nominal time of the defender position represented by this goal.
         * Subtract defender->server and server->us network legs. Server and
         * client scheduling still introduce phase uncertainty: use a history
         * anchor or phase ensemble, not a present-time body translation.
         * Returns NaN if goal age was not observable. RTT inputs are policy
         * declarations; this method never consults packet timings.
         */
        public double sourceTimeMs(double ownRttMs, double opponentRttMs) {
            if (!Double.isFinite(ownRttMs) || !Double.isFinite(opponentRttMs)
                    || ownRttMs < 0 || opponentRttMs < 0)
                throw new IllegalArgumentException("RTTs must be finite and nonnegative");
            return firstUseTimeMs == Long.MIN_VALUE ? Double.NaN
                    : firstUseTimeMs - (ownRttMs + opponentRttMs) * .5;
        }

        /**
         * Suggested phase-search radius, not a guaranteed error bound.
         * Jitter, stalls, batching and low-rate position updates can exceed it.
         */
        public long sourceTimeSearchRadiusMs() { return timingEstablished ? 50 : 150; }
    }

    private boolean haveSample;
    private long previousTick, previousTime;
    private double previousX, previousY, previousZ;
    private Estimate last;
    private Estimate previousGoal;

    public void reset() {
        haveSample = false;
        last = previousGoal = null;
    }

    public Estimate latest() { return last; }

    public Estimate update(long ownTick, long sampleTimeMs, double x, double y, double z) {
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z))
            throw new IllegalArgumentException("Visible coordinates must be finite");
        if (haveSample && ownTick == previousTick) return last;
        if (haveSample && ownTick < previousTick)
            throw new IllegalArgumentException("Observations must be in native tick order");
        if (haveSample && sampleTimeMs < previousTime)
            throw new IllegalArgumentException("Observation times must not decrease");

        boolean consecutive = haveSample && ownTick == previousTick + 1;
        if (!consecutive) {
            previousGoal = null;
            last = invalid(ownTick, sampleTimeMs, false);
            remember(ownTick, sampleTimeMs, x, y, z);
            return last;
        }

        double dx = x - previousX, dy = y - previousY, dz = z - previousZ;
        if (Math.max(Math.max(Math.abs(dx), Math.abs(dy)), Math.abs(dz)) < STILL_EPSILON) {
            Candidate point = candidate(0, x, y, z);
            if (point == null) {
                last = invalid(ownTick, sampleTimeMs, true);
                previousGoal = null;
            } else {
                boolean same = sameGoal(previousGoal, point);
                long firstTick = same ? previousGoal.firstUseTick : Long.MIN_VALUE;
                long firstTime = same ? previousGoal.firstUseTimeMs : Long.MIN_VALUE;
                last = new Estimate(ownTick, sampleTimeMs, true, true, false,
                        Confidence.STATIONARY, point, new Candidate[] { point },
                        firstTick, firstTime, same && previousGoal.timingEstablished,
                        false, 0, 0, 0, 0);
                previousGoal = last;
            }
            remember(ownTick, sampleTimeMs, x, y, z);
            return last;
        }

        List<Candidate> candidates = new ArrayList<>(3);
        // Prefer n=3 on an unresolved exact-grid tie, not the shortest motion.
        for (int n = 3; n >= 1; n--) {
            Candidate value = candidate(n, previousX + n * dx,
                    previousY + n * dy, previousZ + n * dz);
            if (value != null) candidates.add(value);
        }
        if (candidates.isEmpty()) {
            last = invalid(ownTick, sampleTimeMs, true);
            previousGoal = null;
            remember(ownTick, sampleTimeMs, x, y, z);
            return last;
        }

        Candidate chosen = candidates.get(0);
        boolean continuation = false;
        for (Candidate candidate : candidates) {
            if (previousGoal != null && candidate.divisor == previousGoal.remainingAfter
                    && sameGoal(previousGoal, candidate)) {
                chosen = candidate;
                continuation = true;
                break;
            }
        }
        candidates.remove(chosen);
        candidates.add(0, chosen);
        boolean changed = !sameGoal(previousGoal, chosen);
        // Identical goal does not provide evidence of a new packet/source time.
        boolean retainTime = !changed && previousGoal.firstUseTimeMs != Long.MIN_VALUE;
        long firstTick = retainTime ? previousGoal.firstUseTick : ownTick - (4 - chosen.divisor);
        long firstTime = retainTime ? previousGoal.firstUseTimeMs
                : previousTime - (3 - chosen.divisor) * TICK_MS;
        boolean timing = retainTime ? previousGoal.timingEstablished : chosen.divisor == 3;

        boolean velocityValid = false;
        double vx = 0, vy = 0, vz = 0;
        long velocityInterval = 0;
        if (!changed && previousGoal != null) {
            velocityValid = previousGoal.velocityValid;
            vx = previousGoal.vx; vy = previousGoal.vy; vz = previousGoal.vz;
            velocityInterval = previousGoal.velocityIntervalMs;
        } else if (previousGoal != null && timing && previousGoal.timingEstablished) {
            velocityInterval = firstTime - previousGoal.firstUseTimeMs;
            velocityValid = velocityInterval >= 25 && velocityInterval <= 500;
            if (velocityValid) {
                double scale = TICK_MS / (double) velocityInterval;
                vx = (chosen.x - previousGoal.goalX) * scale;
                vy = (chosen.y - previousGoal.goalY) * scale;
                vz = (chosen.z - previousGoal.goalZ) * scale;
            }
        }
        Confidence confidence = candidates.size() == 1 ? Confidence.UNIQUE
                : continuation ? Confidence.CONTINUATION : Confidence.AMBIGUOUS;
        last = new Estimate(ownTick, sampleTimeMs, true, true, changed, confidence,
                chosen, candidates.toArray(new Candidate[0]), firstTick, firstTime,
                timing, velocityValid, vx, vy, vz, velocityInterval);
        previousGoal = last;
        remember(ownTick, sampleTimeMs, x, y, z);
        return last;
    }

    private void remember(long tick, long time, double x, double y, double z) {
        haveSample = true;
        previousTick = tick; previousTime = time;
        previousX = x; previousY = y; previousZ = z;
    }

    private static Candidate candidate(int n, double x, double y, double z) {
        double qx = Math.rint(x * 32) / 32, qy = Math.rint(y * 32) / 32,
                qz = Math.rint(z * 32) / 32;
        double error = Math.max(Math.max(Math.abs(x - qx), Math.abs(y - qy)), Math.abs(z - qz));
        return error <= GRID_EPSILON ? new Candidate(n, qx, qy, qz, error) : null;
    }

    private static boolean sameGoal(Estimate estimate, Candidate candidate) {
        return estimate != null && estimate.valid && estimate.goalX == candidate.x
                && estimate.goalY == candidate.y && estimate.goalZ == candidate.z;
    }

    private static Estimate invalid(long tick, long time, boolean consecutive) {
        return new Estimate(tick, time, false, consecutive, false, Confidence.NONE,
                null, new Candidate[0], Long.MIN_VALUE, Long.MIN_VALUE,
                false, false, 0, 0, 0, 0);
    }
}
