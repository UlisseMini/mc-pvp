package lab;

import static lab.JumpResetFeedback.Grade.*;

/** A vanilla velocity packet can launch the player by the same height as Jump. */
public final class JumpResetFeedbackCheck {
    private static final JumpResetFeedback.Movement FLOOR = new JumpResetFeedback.Movement(0, SumoArena.TOP_Y, 0, true, true);

    private static JumpResetFeedback.Movement rise(double dy) {
        return new JumpResetFeedback.Movement(-.57, SumoArena.TOP_Y + dy, 0, false, true);
    }

    private static void expect(JumpResetFeedback.Grade expected, JumpResetFeedback.Movement before,
                               JumpResetFeedback.Movement after, double deliveredY) {
        expect(expected, before, after, deliveredY, 0);
    }

    private static void expect(JumpResetFeedback.Grade expected, JumpResetFeedback.Movement before,
                               JumpResetFeedback.Movement after, double deliveredY, int earlyTicks) {
        JumpResetFeedback.Grade actual = JumpResetFeedback.classify(before, after, deliveredY, earlyTicks);
        if (actual != expected) throw new AssertionError("Expected " + expected + ", got " + actual);
    }

    public static void main(String[] args) {
        expect(ON_TIME, FLOOR, rise(.42f), .46075);
        expect(MISSED, FLOOR, rise(.46075), .46075);
        expect(UNKNOWN, FLOOR, rise(.42), .42);
        expect(UNKNOWN, FLOOR, rise(.48), .46);
        expect(UNKNOWN, FLOOR, FLOOR, .46);
        expect(UNKNOWN, null, rise(.42f), .46);
        expect(UNKNOWN, FLOOR, new JumpResetFeedback.Movement(0, SumoArena.TOP_Y, 0, false, false), .46);
        expect(AIRBORNE, rise(.42f), rise(.88), .46);
        expect(EARLY, rise(.42f), rise(.88), .46, 1);
        expect(MISSED, FLOOR, rise(.46), .46, 1);
        System.out.println("PASS vanilla jump-reset classification");
    }
}
