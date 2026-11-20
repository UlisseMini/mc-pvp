package lab;

/** Select stock W-tap during an active unanswered run using only received hurt cues. */
public final class ComboContinuationGate {
    private static final double ACTIVE_GAP_MS = 1100;
    private final int threshold;
    private double lastOwnHurt = Double.NEGATIVE_INFINITY;
    private double lastTargetHurt = Double.NEGATIVE_INFINITY;
    private int unanswered;

    public ComboContinuationGate(int threshold) {
        if (threshold < 1) throw new IllegalArgumentException("Expected a positive combo cue threshold");
        this.threshold = threshold;
    }

    public ComboContinuationGate copy() {
        ComboContinuationGate copy = new ComboContinuationGate(threshold);
        copy.lastOwnHurt = lastOwnHurt;
        copy.lastTargetHurt = lastTargetHurt;
        copy.unanswered = unanswered;
        return copy;
    }

    public boolean observe(double now, double ownHurt, double targetHurt) {
        if (ownHurt > lastOwnHurt) {
            lastOwnHurt = ownHurt;
            unanswered = 0;
        }
        if (targetHurt > lastTargetHurt) {
            if (targetHurt - lastTargetHurt > ACTIVE_GAP_MS) unanswered = 0;
            lastTargetHurt = targetHurt;
            if (lastTargetHurt > lastOwnHurt) unanswered++;
        }
        return unanswered >= threshold && now - lastTargetHurt <= ACTIVE_GAP_MS;
    }
}
