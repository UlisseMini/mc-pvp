package practice.core.bow;

/**
 * Principled Optimal-Stopping / Peak-Spike Auto-Release Policy for Bow Aim.
 * Supports both classic fixed threshold (THRESHOLD) and adaptive predictability-spike
 * peak capture (SMART) so low-baseline long-range fights still release on jumps,
 * bridge walks, and corner lock-ins without waiting forever for a high raw percentage.
 */
public final class BowReleasePolicy {
    public enum ReleaseMode { SMART, THRESHOLD }

    private static final double MIN_FLOOR = 0.22;
    private static final double SPIKE_MARGIN = 0.07;
    private static final double SPIKE_RATIO = 1.25;
    private static final int PATIENCE_START_TICKS = 30; // 1.5s at full draw

    private int holdTicks;
    private double ema, peak, best;
    private boolean spiking;
    private int plateau;

    public void reset() {
        holdTicks = 0;
        ema = peak = best = 0.0;
        spiking = false;
        plateau = 0;
    }

    public boolean isSpiking() { return spiking; }
    public double baseline() { return ema; }
    public int holdTicks() { return holdTicks; }

    public boolean shouldFire(double prob, int thresholdPct, ReleaseMode mode) {
        return shouldFire(prob, thresholdPct, mode, false);
    }

    /**
     * Evaluate whether to release the arrow on this full-draw tick.
     * @param prob calibrated expected hit probability in [0, 1]
     * @param thresholdPct user layup threshold in [1, 100]
     * @param mode SMART (optimal-stopping peak-spike) or THRESHOLD (fixed cutoff)
     * @param losingCover true if target is predicted to step behind terrain cover in ~4 ticks
     */
    public boolean shouldFire(double prob, int thresholdPct, ReleaseMode mode, boolean losingCover) {
        if (!Double.isFinite(prob) || prob <= 0.0) return false;
        double layup = thresholdPct / 100.0;
        if (mode == ReleaseMode.THRESHOLD) {
            return prob >= layup;
        }
        int w = holdTicks++;
        if (w == 0) {
            ema = prob;
        } else {
            ema = 0.88 * ema + 0.12 * prob;
        }
        if (prob > best) best = prob;

        // 1. Instant layup
        if (prob >= layup) {
            spiking = true;
            return true;
        }

        // 1b. Cover-urgency: target is visible now (decent shot) but about to duck behind blocks!
        if (losingCover && prob >= MIN_FLOOR) {
            spiking = true;
            return true;
        }

        // 2. Predictability spike & local peak capture
        double spikeBar = Math.max(MIN_FLOOR, Math.min(ema + SPIKE_MARGIN, ema * SPIKE_RATIO));
        if (prob >= spikeBar) {
            if (!spiking) {
                spiking = true;
                peak = prob;
                plateau = 1;
            } else if (prob > peak + 0.008) {
                peak = prob;
                plateau = 1;
            } else if (prob >= peak - 0.015) {
                plateau++;
                if (plateau >= 3) return true;
            } else {
                // Turning over right past the predictability peak -> fire now!
                return true;
            }
        } else if (spiking) {
            if (prob >= Math.max(MIN_FLOOR * 0.85, ema + 0.02)) {
                return true;
            }
            spiking = false;
            peak = prob;
            plateau = 0;
        }

        // 3. Opportunity-cost patience decay after holding full draw > 1.5s
        if (w >= PATIENCE_START_TICKS) {
            double relaxed = Math.max(0.18, best * (0.94 - 0.009 * (w - PATIENCE_START_TICKS)));
            if (prob >= relaxed && prob >= ema) return true;
        }
        return false;
    }
}
