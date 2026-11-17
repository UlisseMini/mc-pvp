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
        near(.2, CombatCoachMath.reduction(-.57, 0, .2, 0, -.9, 0));
        near(0, CombatCoachMath.reduction(-.77, .2, 0, .2, -.9, 0));
        near(-.2, CombatCoachMath.reduction(-.97, 0, -.2, 0, -.9, 0));
        near(.04, CombatCoachMath.reduction(.16, 0, .2, 0, -.9, 0));
        System.out.println("PASS melee ambiguity, armor/absorption, and directional jump reduction");
    }
}
