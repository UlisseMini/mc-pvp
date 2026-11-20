package lab;

/** Skip the first stock release after one local cue while acquiring a boxing combo. */
public final class BoxingReleaseHold {
    public enum Decision { STOCK, RELEASE, HOLD }

    private final double reach;
    private final ComboContinuationGate combo = new ComboContinuationGate(2);
    private double previousRelease = Double.NEGATIVE_INFINITY;

    public BoxingReleaseHold(double reach) {
        if (Double.isNaN(reach) || reach <= 0) throw new IllegalArgumentException("Expected a positive release reach");
        this.reach = reach;
    }

    public Decision decide(double now, double ownHurt, double targetHurt, double releaseCue,
                           boolean forward, boolean grounded, double nearest, boolean enabled) {
        boolean continuing = combo.observe(now, ownHurt, targetHurt);
        if (forward || releaseCue <= previousRelease) return Decision.STOCK;
        previousRelease = releaseCue;
        if (!enabled || continuing) return Decision.STOCK;
        return (reach == Double.POSITIVE_INFINITY || (grounded && nearest < reach)) ? Decision.HOLD : Decision.RELEASE;
    }
}
