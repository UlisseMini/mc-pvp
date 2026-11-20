package lab;

/** Diagnostic for issuing the first selected approach click at a client input tick. */
public final class BoxingApproachPoll {
    public enum Mode { OBSERVE, INPUT_TICK }
    public enum Decision { ORDINARY, FIRST_DEFER, DEFER, SELECT }

    private static final long WINDOW_NS = 140_000_000L;
    private static final long QUIET_MS = 1100;
    private static final long REARM_NS = QUIET_MS * 1_000_000L;
    private static final int MAX_ARMS = 4;

    private final Mode mode;
    private long armedAt;
    private int arms;
    private boolean selected;
    private boolean deferred;

    public BoxingApproachPoll(Mode mode) {
        this.mode = mode;
    }

    public BoxingApproachPoll(BoxingApproachPoll source) {
        mode = source.mode;
        armedAt = source.armedAt;
        arms = source.arms;
        selected = source.selected;
        deferred = source.deferred;
    }

    public Mode mode() { return mode; }
    public int arms() { return arms; }
    public long armedAt() { return armedAt; }

    public boolean arm(long nowNs, long nowMs, long ownHurt, long targetHurt, boolean grounded,
                       double nearest, double heightDifference, double toward) {
        if (arms >= MAX_ARMS || (arms > 0 && nowNs - armedAt < REARM_NS) || !grounded ||
                nearest < 3.70 || nearest > 4.10 || Math.abs(heightDifference) > .05 || toward < .13 ||
                nowMs - Math.max(ownHurt, targetHurt) < QUIET_MS) return false;
        armedAt = nowNs;
        arms++;
        selected = false;
        deferred = false;
        return true;
    }

    public Decision poll(long nowNs, boolean inputTick, boolean targetSelected) {
        if (arms == 0 || selected || nowNs - armedAt >= WINDOW_NS || !targetSelected) return Decision.ORDINARY;
        if (mode == Mode.INPUT_TICK && !inputTick) {
            if (deferred) return Decision.DEFER;
            deferred = true;
            return Decision.FIRST_DEFER;
        }
        selected = true;
        return Decision.SELECT;
    }
}
