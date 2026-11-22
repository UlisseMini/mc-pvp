package practice.tests;

import java.util.Arrays;
import java.util.Collections;
import practice.core.CombatCoachMath;
import static practice.core.CombatCoachMath.Block.*;

public final class CombatCoachChecks {
    private static void expect(Object expected, Object actual) {
        if (!expected.equals(actual)) throw new AssertionError("Expected " + expected + ", got " + actual);
    }

    private static void near(double expected, double actual) {
        if (Math.abs(expected - actual) > 1e-8) throw new AssertionError("Expected " + expected + ", got " + actual);
    }

    public static void main(String[] args) {
        expect(BLOCKED, CombatCoachMath.block(4.5, 20, 0, 0, -1, 0, Arrays.asList(8f, 12f)));
        expect(UNBLOCKED, CombatCoachMath.block(8, 20, 0, 0, -1, 0, Arrays.asList(8f, 12f)));
        expect(UNCERTAIN, CombatCoachMath.block(5, 20, 0, 0, -1, 0, Arrays.asList(5f, 9f)));
        // Both weapons fit each observation under different protection rolls.
        expect(BLOCKED, CombatCoachMath.block(2.16, 20, 0, 0, -1, 25, Arrays.asList(8f, 11f)));
        expect(UNBLOCKED, CombatCoachMath.block(3.52, 20, 0, 0, -1, 25, Arrays.asList(8f, 11f)));
        expect(UNCERTAIN, CombatCoachMath.block(6, 20, 0, 0, -1, 0, Collections.emptyList()));
        expect(UNCERTAIN, CombatCoachMath.block(2, 2, 0, 0, -1, 0, Arrays.asList(8f, 12f)));
        expect(UNCERTAIN, CombatCoachMath.block(0, 20, 5, 0, -1, 0, Arrays.asList(8f, 12f)));
        float loss = CombatCoachMath.healthLoss(15.75f, true, 20, .5f, 16, 0, 4);
        expect(BLOCKED, CombatCoachMath.block(loss, 20, .5f, 16, 0, 5, Collections.singletonList(15.75f)));
        expect(UNBLOCKED, CombatCoachMath.match(1.2, 20, 0, 20, -1, 0, Arrays.asList(5f, 7.5f, 11f),
            Collections.singleton(5f)).verdict);
        CombatCoachMath.DamageMatch critUpgrade = CombatCoachMath.match(.7, 20, 0, 20, -1, 0,
            Collections.singletonList(11f), Collections.singleton(5f));
        expect(BLOCKED, critUpgrade.verdict);
        expect(Collections.singleton(11f), critUpgrade.rawDamage);
        expect(BLOCKED, CombatCoachMath.match(1.2, 20, 0, 20, -1, 0, Collections.singletonList(11f),
            CombatCoachMath.FRESH).verdict);
        expect(UNBLOCKED, CombatCoachMath.block(.2, 20, 0, 20, -1, 0, Collections.singletonList(1f)));
        expect(BLOCKED, CombatCoachMath.block(.25, 20, 0, 20, -1, 0, Collections.singletonList(1.5f)));
        expect(Collections.singleton(5f), CombatCoachMath.weaponBonuses(2.6, 20, 20, -1, 0, 8, 0, false));
        expect(Collections.singleton(5f), CombatCoachMath.weaponBonuses(3.4, 20, 20, -1, 0, 8, 0, true));
        expect(Collections.emptySet(), CombatCoachMath.weaponBonuses(1, 1, 20, -1, 0, 8, 0, false));
        near(.2, CombatCoachMath.reduction(-.57, 0, .2, 0, -.9, 0));
        near(0, CombatCoachMath.reduction(-.77, .2, 0, .2, -.9, 0));
        near(-.2, CombatCoachMath.reduction(-.97, 0, -.2, 0, -.9, 0));
        near(.04, CombatCoachMath.reduction(.16, 0, .2, 0, -.9, 0));
        expect(0f, CombatCoachMath.jumpCueVolume(Double.NaN));
        expect(0f, CombatCoachMath.jumpCueVolume(-.20));
        expect(0f, CombatCoachMath.jumpCueVolume(0));
        expect(true, CombatCoachMath.jumpCueVolume(.18) > CombatCoachMath.jumpCueVolume(.05));
        expect(true, CombatCoachMath.jumpCueVolume(.20) > CombatCoachMath.jumpCueVolume(.18));
        BlockhitTimelineChecks.run();
        System.out.println("PASS melee ambiguity, armor/absorption, and directional jump reduction");
    }
}
