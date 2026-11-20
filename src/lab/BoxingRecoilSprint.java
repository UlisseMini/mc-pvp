package lab;

/** A one-intervention acquisition trial, triggered on the first qualifying stock release. */
public final class BoxingRecoilSprint {
    public enum Arm {
        OBSERVE, SPRINT, ORDERED_OBSERVE, ORDERED;

        boolean requiresOrder() { return this == ORDERED_OBSERVE || this == ORDERED; }
        boolean sprints() { return this == SPRINT || this == ORDERED; }
    }

    public enum Decision { STOCK, WRONG_ORDER, ENROLLED }

    final Arm arm;
    private long lastRelease;
    private boolean enrolled;

    public BoxingRecoilSprint(Arm arm) {
        this.arm = arm;
    }

    public Decision decide(long now, long ownHurt, long targetHurt, long releaseCue, boolean stockForward,
                           boolean grounded, boolean sprinting, double nearest, double along, boolean enabled,
                           double nowMs, double ownStatusMs, double targetStatusMs) {
        if (enrolled || stockForward || releaseCue <= lastRelease) return Decision.STOCK;
        lastRelease = releaseCue;
        boolean eligible = enabled && grounded && !sprinting && nearest <= 1.30 && along <= -.80
                && ownHurt > 0 && targetHurt == releaseCue && Math.abs(ownHurt - targetHurt) <= 16
                && now >= Math.max(ownHurt, targetHurt) && now - Math.min(ownHurt, targetHurt) <= 75;
        if (!eligible) return Decision.STOCK;
        if (arm.requiresOrder() && !(nowMs - ownStatusMs <= 150 && ownStatusMs < targetStatusMs && targetStatusMs <= nowMs))
            return Decision.WRONG_ORDER;
        enrolled = true;
        return Decision.ENROLLED;
    }
}
