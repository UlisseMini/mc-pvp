package practice.bridge;

import java.util.Arrays;
import java.util.List;
import practice.core.CombatCoachMath;
import practice.core.CombatCoachMath.Block;
import practice.bridge.MeleeObservations.Candidate;

public final class CombatDirectionCheck {
    public static void main(String[] args) {
        Candidate left = new Candidate(1, 2, 0);
        Candidate right = new Candidate(2, -2, 0);
        Candidate leftAgain = new Candidate(2, 2, .5);
        left.damage.add(5f);
        right.damage.add(9f);
        leftAgain.damage.add(9f);
        List<Candidate> both = Arrays.asList(left, right);
        expect(Block.BLOCKED, verdict(3, both, -1));
        expect(Block.UNBLOCKED, verdict(9, both, -1));
        expect(Block.UNCERTAIN, verdict(5, both, -1));
        expect(1, MeleeObservations.fromDirection(both, .9, 0));
        expect(2, MeleeObservations.fromDirection(both, -.9, 0));
        expect(Block.UNBLOCKED, verdict(5, both, 1));
        expect(Block.BLOCKED, verdict(5, both, 2));
        expect(-1, MeleeObservations.fromDirection(Arrays.asList(left, leftAgain), .9, 0));
        expect(-1, MeleeObservations.fromDirection(Arrays.asList(left, right, leftAgain), .9, 0));
        expect(-1, MeleeObservations.fromDirection(both, .01, 0));
        System.out.println("PASS two melee candidates, compatible verdicts, and directional ambiguity");
    }

    private static Block verdict(float loss, List<Candidate> candidates, int attacker) {
        return CombatCoachMath.block(loss, 20, 0, 0, -1, 0, MeleeObservations.damage(candidates, attacker));
    }

    private static void expect(Object expected, Object actual) {
        if (!expected.equals(actual)) throw new AssertionError("Expected " + expected + ", got " + actual);
    }
}
