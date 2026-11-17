package practice.core;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/** Counterfactuals for isolated 1.8.9 melee hits and unobstructed first movement steps. */
public final class CombatCoachMath {
    public enum Block { BLOCKED, UNBLOCKED, UNCERTAIN }
    private static final double HEALTH_TOLERANCE = .015;
    public static final double MEANINGFUL_JUMP_REDUCTION = .03;
    public static final Set<Float> FRESH = Collections.singleton(0f);

    public static final class DamageMatch {
        public final Block verdict;
        public final Set<Float> rawDamage;

        private DamageMatch(Block verdict, Set<Float> rawDamage) {
            this.verdict = verdict;
            this.rawDamage = rawDamage;
        }
    }

    private CombatCoachMath() {}

    /** An attacker is unnecessary if every compatible hit has the same block outcome. */
    public static Block block(double lost, float hp, float absorption, int armor, int resistance,
                              int protectionPoints, Iterable<Float> possibleDamage) {
        return match(lost, hp, absorption, armor, resistance, protectionPoints, possibleDamage, FRESH).verdict;
    }

    public static DamageMatch match(double lost, float hp, float absorption, int armor, int resistance,
                                    int protectionPoints, Iterable<Float> possibleDamage, Set<Float> previousRaw) {
        Set<Float> matching = new LinkedHashSet<>();
        if (!Double.isFinite(lost) || lost <= HEALTH_TOLERANCE || hp <= 0)
            return new DamageMatch(Block.UNCERTAIN, matching);
        int points = Math.max(0, Math.min(25, protectionPoints));
        boolean blocked = false, open = false;
        for (float raw : possibleDamage) {
            if (raw <= 0 || !Float.isFinite(raw)) continue;
            for (float prior : previousRaw) {
                float delta = raw - prior;
                if (delta <= 0) continue;
                for (int roll = (points + 1) / 2; roll <= points; roll++) {
                    boolean b = Math.abs(lost - healthLoss(delta, true, hp, absorption, armor, resistance, roll)) <= HEALTH_TOLERANCE;
                    boolean u = Math.abs(lost - healthLoss(delta, false, hp, absorption, armor, resistance, roll)) <= HEALTH_TOLERANCE;
                    blocked |= b && delta > 1;
                    open |= u || b && delta <= 1;
                    if (b || u) matching.add(raw);
                }
            }
        }
        Block verdict = blocked == open ? Block.UNCERTAIN : blocked ? Block.BLOCKED : Block.UNBLOCKED;
        return new DamageMatch(verdict, matching);
    }

    public static float healthLoss(float raw, boolean block, float hp, float absorption,
                                   int armor, int resistance, int roll) {
        if (block) raw = (raw + 1f) * .5f;
        raw = raw * (25 - armor) / 25f;
        if (resistance >= 0) raw = raw * (25 - 5 * (resistance + 1)) / 25f;
        if (roll > 0) raw = raw * (25 - Math.min(20, roll)) / 25f;
        return hp - Math.max(0f, hp - Math.max(0f, raw - absorption));
    }

    /** Clean, open hits give candidate bonuses, one for each possible Protection roll. */
    public static Set<Float> weaponBonuses(double lost, float hp, int armor, int resistance,
                                         int protection, float base, float visibleBonus, boolean critical) {
        Set<Float> known = new LinkedHashSet<>(), fitted = new LinkedHashSet<>();
        if (!Double.isFinite(lost) || lost <= HEALTH_TOLERANCE || lost >= hp - HEALTH_TOLERANCE) return known;
        if (critical) base *= 1.5f;
        int points = Math.max(0, Math.min(25, protection));
        for (int level = 0; level <= 5; level++) {
            float bonus = visibleBonus + level * 1.25f;
            for (int roll = (points + 1) / 2; roll <= points; roll++) {
                if (Math.abs(lost - healthLoss(base + bonus, false, hp, 0, armor, resistance, roll)) <= HEALTH_TOLERANCE)
                    known.add(bonus);
            }
        }
        if (!known.isEmpty()) return known;
        for (int roll = (points + 1) / 2; roll <= points; roll++) {
            float scale = (25 - armor) / 25f * (resistance < 0 ? 1 : (25 - 5 * (resistance + 1)) / 25f)
                * (25 - Math.min(20, roll)) / 25f;
            if (scale <= 0) continue;
            float bonus = (float)(lost / scale) - base;
            if (bonus >= visibleBonus - .02f && bonus <= visibleBonus + 16f) fitted.add(Math.max(visibleBonus, bonus));
        }
        return fitted;
    }

    public static double projected(double x, double z, double pushX, double pushZ) {
        return (x * pushX + z * pushZ) / Math.hypot(pushX, pushZ);
    }

    public static double reduction(double x, double z, double jumpX, double jumpZ, double pushX, double pushZ) {
        double observed = projected(x, z, pushX, pushZ);
        double withoutJump = observed - projected(jumpX, jumpZ, pushX, pushZ);
        return Math.max(0, withoutJump) - Math.max(0, observed);
    }

    public static float jumpCueVolume(double reduction) {
        if (!Double.isFinite(reduction) || reduction <= MEANINGFUL_JUMP_REDUCTION) return 0;
        return (float) (.15 + .60 * Math.min(1, reduction / .20));
    }
}
