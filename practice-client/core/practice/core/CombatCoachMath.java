package practice.core;

/** Counterfactuals for isolated 1.8.9 melee hits and unobstructed first movement steps. */
public final class CombatCoachMath {
    public enum Block { BLOCKED, UNBLOCKED, UNCERTAIN }
    private static final double HEALTH_TOLERANCE = .015;

    private CombatCoachMath() {}

    /** An attacker is unnecessary if every compatible hit has the same block outcome. */
    public static Block block(double lost, float hp, float absorption, int armor, int resistance,
                              int protectionPoints, Iterable<Float> possibleDamage) {
        if (!Double.isFinite(lost) || lost <= HEALTH_TOLERANCE || hp <= 0) return Block.UNCERTAIN;
        int points = Math.max(0, Math.min(25, protectionPoints));
        boolean blocked = false, open = false;
        for (float raw : possibleDamage) {
            if (raw <= 0 || !Float.isFinite(raw)) continue;
            for (int roll = (points + 1) / 2; roll <= points; roll++) {
                blocked |= Math.abs(lost - healthLoss(raw, true, hp, absorption, armor, resistance, roll)) <= HEALTH_TOLERANCE;
                open |= Math.abs(lost - healthLoss(raw, false, hp, absorption, armor, resistance, roll)) <= HEALTH_TOLERANCE;
                if (blocked && open) return Block.UNCERTAIN;
            }
        }
        return blocked ? Block.BLOCKED : open ? Block.UNBLOCKED : Block.UNCERTAIN;
    }

    public static float healthLoss(float raw, boolean block, float hp, float absorption,
                                   int armor, int resistance, int roll) {
        if (block) raw = (raw + 1f) * .5f;
        raw = raw * (25 - armor) / 25f;
        if (resistance >= 0) raw = raw * (25 - 5 * (resistance + 1)) / 25f;
        if (roll > 0) raw = raw * (25 - Math.min(20, roll)) / 25f;
        return hp - Math.max(0f, hp - Math.max(0f, raw - absorption));
    }

    public static double projected(double x, double z, double pushX, double pushZ) {
        return (x * pushX + z * pushZ) / Math.hypot(pushX, pushZ);
    }

    public static double reduction(double x, double z, double jumpX, double jumpZ, double pushX, double pushZ) {
        double observed = projected(x, z, pushX, pushZ);
        double withoutJump = observed - projected(jumpX, jumpZ, pushX, pushZ);
        return Math.max(0, withoutJump) - Math.max(0, observed);
    }
}
