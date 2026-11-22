package practice.tests;

import practice.core.SumoStats;
import practice.core.SumoStats.Cue;
import practice.core.SumoStats.Exchange;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class SumoStatsChecks implements SumoStats.Listener {
    private final List<Cue> heard = new ArrayList<>();
    private SumoStats stats = new SumoStats(this);
    private int closed;

    @Override public void cue(Exchange exchange, Cue cue) { heard.add(cue); }
    @Override public void closed(Exchange exchange) { closed++; }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private void moves(int from, int through) {
        for (int i = from; i < through; i++) stats.move(1000 + i * 50L + 40, .3, 0, i == 0, true, i == 0 ? .546 : .91);
    }

    private void reset() { heard.clear(); closed = 0; stats = new SumoStats(this); }

    private void cases() {
        stats.impact(1000, .94, 0, 1.8, -31, false);
        stats.inputSlot(1035, true, true, 0, .94);
        stats.click(1035, true, false, true, 0, .94, 1.4, true);
        stats.click(1035, true, false, false, 0, .56, 1.4, false);
        check(heard.isEmpty(), "Unpaired velocity cannot produce feedback");
        stats.confirm();
        check(heard.equals(Arrays.asList(Cue.BRAKE)), "Hurt confirms an already applied brake");
        moves(0, 1);
        stats.click(1076, true, false, true, 0, .4, 1.8, true);
        moves(1, 6);
        check(heard.equals(Arrays.asList(Cue.BRAKE, Cue.BRAKE)), "Count each brake; same-tick sprint consumption is not a mistake");
        check(closed == 1 && stats.brakes == 2 && stats.earlyBrakes == 1 && stats.earlyOpenings == 1, "One completed grouped window");
        check(stats.slides().contains("1.80 blocks"), "Slide is projected over six moves");
        stats.move(1350, 0, 0, true, true, .546);
        check(stats.edgeChecked == 1 && stats.edgeSaved == 1, "Landing after known near-edge hit is observed");
        reset();
        stats.impact(1000, .94, 0, Double.NaN, Integer.MIN_VALUE, true);
        stats.inputSlot(1035, true, true, 0, .94);
        moves(0, 1);
        stats.inputSlot(1076, true, true, 0, .8);
        moves(1, 2);
        stats.click(1127, false, false, true, 0, .7, 3.38, false);
        moves(2, 6);
        check(stats.last.firstIssue() == Cue.UNCLICKED && stats.last.unclickedTicks == 2 && heard.isEmpty(),
                "Grade unclicked opening after the window and don't mislabel the later out-of-range click");
        check(stats.last.explanation().contains("+35 ms. First click +127 ms, 3.38 blocks"), "Specific report uses both clock readings");
        for (Cue cue : Arrays.asList(Cue.SPRINT, Cue.COOLDOWN, Cue.AIM)) {
            reset();
            stats.impact(1000, .94, 0, Double.NaN, -32, true);
            stats.click(1018, cue != Cue.AIM, cue == Cue.AIM, cue != Cue.SPRINT, cue == Cue.COOLDOWN ? 8 : 0,
                    .94, 1.89, false);
            stats.move(1019, .92, 0, false, true, .91);
            moves(1, 6);
            check(heard.equals(Arrays.asList(cue)) && stats.losses(cue) == 1, "Distinct missed brake causes: " + cue);
            check(stats.beforeImpactJumps == 1, "Actual jump preceded an airborne impact");
        }
        reset();
        stats.move(1000, 0, 0, true, true, .546);
        stats.move(1050, 0, 0, true, false, .546);
        stats.move(1100, 0, 0, true, true, .546);
        stats.move(1150, 0, 0, true, false, .546);
        stats.move(1200, 0, 0, true, false, .546);
        stats.move(1250, 0, 0, true, true, .546);
        check(stats.oneMoveReleases == 1 && stats.longerReleases == 1, "W release lengths come from movement updates");
        stats.impact(1300, .94, 0, 1, -1, false);
        stats.move(1310, 2, 0, false, true, .91);
        stats.finish(1320, false);
        check(stats.launches == 0 && closed == 0, "Unpaired velocity cannot enter totals");
    }

    private double savings(int clickMask, double drag, double forward, boolean obstructed) {
        reset();
        stats.impact(1000, .95, 0, Double.NaN, Integer.MIN_VALUE, true);
        double speed = .95, noBrakeSpeed = .95, noBrakeDistance = 0, noBrakePeak = 0;
        for (int i = 0; i < SumoStats.MOVES; i++) {
            long time = 1000 + 50L * i;
            if ((clickMask & (1 << i)) != 0) {
                stats.click(time + 3, true, false, true, 0, speed, 1.9, true);
                speed *= .6;
            }
            speed -= forward;
            noBrakeSpeed -= forward;
            stats.move(time + 4, speed, 0, drag < .91, true, obstructed && i == 2 ? Double.NaN : drag);
            noBrakeDistance += noBrakeSpeed;
            noBrakePeak = Math.max(noBrakePeak, noBrakeDistance);
            speed *= drag;
            noBrakeSpeed *= drag;
        }
        if (obstructed) {
            check(Double.isNaN(stats.last.kbSaved()) && stats.savingSamples == 0, "An obstructed trajectory has no savings estimate");
            return Double.NaN;
        }
        check(Math.abs(stats.last.kbSaved() - (noBrakePeak - stats.last.peakSlide())) < 1e-10,
                "Savings matches an independent no-brake trajectory with the same movement");
        check(stats.savingSamples == 1 && stats.totalKbSaved == stats.last.kbSaved(), "Only complete measured windows enter the match total");
        return stats.totalKbSaved;
    }

    private void savingCases() {
        check(savings(0, .91, .025, false) == 0, "No measured brakes saves zero");
        double first = savings(1, .91, .025, false);
        double second = savings(2, .91, .025, false);
        double both = savings(3, .91, .025, false);
        check(first > second && both > first && both < first + second, "Early and follow-up brakes get their distance credit without double counting");
        savings(1, .546, .24, false);
        check(stats.last.peakSlide() > stats.last.distance, "Peak slide preserves the backward maximum when you walk forward again");
        savings(1, .91, .025, true);
        stats.impact(2000, .95, 0, Double.NaN, Integer.MIN_VALUE, true);
        stats.click(2010, true, false, true, 0, .95, 1.9, true);
        stats.move(2011, .57, 0, false, true, .91);
        stats.finish(2030, false);
        check(Double.isNaN(stats.last.kbSaved()) && stats.savingSamples == 0, "A short terminal window has no six-move estimate");
    }

    private void costlyBrakeCases() {
        reset();
        stats.move(975, 0, 0, false, true, .91);
        stats.move(990, 0, 0, false, false, .91);
        stats.impact(1000, .9, 0, Double.NaN, Integer.MIN_VALUE, true);
        stats.click(1002, true, false, false, 0, .9, 2, false);
        stats.move(1005, .9, 0, false, true, .91);
        stats.click(1052, true, false, true, 0, .819, 2, true);
        double speed = .819 * .6;
        double saved = .36;
        double delta = .36 * .91 * .6;
        for (int step = 1; step < SumoStats.MOVES; step++) {
            stats.move(1005 + step * 50, speed, 0, false, true, .91);
            saved += delta;
            speed *= .91;
            delta *= .91;
        }
        check(Math.abs(stats.last.missedBrakeBlocks - saved) < 1e-10, "A later real cut also shrinks the velocity difference");
        check(stats.costlyBrakeExchanges == 1 && stats.costlyBrakeAfterRelease == 1, "Costly brake after W up is attributed once");

        stats.impact(1400, .12, 0, Double.NaN, Integer.MIN_VALUE, true);
        stats.click(1402, true, false, false, 0, .12, 2, false);
        for (int step = 0; step < SumoStats.MOVES; step++)
            stats.move(1410 + step * 50, .12 * Math.pow(.91, step), 0, false, true, .91);
        check(stats.last.missedBrakeBlocks > 0 && stats.costlyBrakeExchanges == 1, "A small lost brake only qualifies for the normal cue");

        stats.impact(1800, .9, 0, Double.NaN, Integer.MIN_VALUE, true);
        stats.click(1802, true, false, false, 0, .9, 2, false);
        stats.move(1805, 0, 0, true, true, Double.NaN);
        stats.finish(1810, false);
        check(stats.last.missedBrakeBlocks == 0 && stats.costlyBrakeExchanges == 1, "A blocked trajectory does not qualify for the costly cue");
    }

    private void idealBrakeCase() {
        reset();
        stats.impact(1000, .9, 0, Double.NaN, Integer.MIN_VALUE, true);
        double actual = .9, ideal = .9, coast = .9, replaySaved = 0;
        for (int step = 0; step < SumoStats.MOVES; step++) {
            long time = 1000 + 50L * step;
            boolean target = step < 3, sprint = step == 1;
            int timer = step == 2 ? 4 : 0;
            stats.inputSlot(time, target, sprint, timer, actual);
            if (step < 2) {
                stats.click(time + 1, true, false, sprint, 0, actual, 2, sprint);
                ideal *= .6;
                if (sprint) actual *= .6;
            }
            stats.move(time + 2, actual, 0, false, true, .91);
            replaySaved += coast - ideal;
            actual *= .91;
            ideal *= .91;
            coast *= .91;
        }
        check(Math.abs(stats.last.idealKbSaved() - replaySaved) < 1e-10,
                "Ideal replays open slots including sprint off and excludes cooldown and duplicate cuts");
        check(stats.last.idealKbSaved() > stats.last.kbSaved(), "A missing early brake leaves a measurable gap to ideal");
    }

    private void lossTimelineCase() {
        reset();
        double[] slides = {1.4, .6, 2.0, 1.1, .4};
        for (int i = 0; i < slides.length; i++) {
            long time = 1000 + i * 600L;
            stats.impact(time, .9, 0, Double.NaN, Integer.MIN_VALUE, true);
            stats.move(time + 1, slides[i], 0, false, true, .91);
            stats.closeWindow();
        }
        List<Exchange> loss = stats.lossMoments(3500, true);
        check(loss.size() == 4 && loss.get(0).impact == 1000 && loss.get(1).impact == 2200 && loss.get(3) == stats.last,
                "Largest slides appear in time order with space for the recent terminal hit");
        check(stats.lossMoments(5000, true).get(1).impact == 1600, "An old final hit does not displace a larger slide");
    }

    private void brakeTimelineCase() {
        reset();
        stats.move(960, 0, 0, true, true, .546);
        stats.move(990, 0, 0, true, false, .546);
        stats.impact(1000, .95, 0, Double.NaN, Integer.MIN_VALUE, true);
        stats.click(1004, true, false, false, 0, .95, 2, false);
        stats.click(1005, true, false, false, 0, .95, 2, false);
        moves(0, 1);
        stats.inputSlot(1055, true, false, 0, .50);
        moves(1, 2);
        stats.click(1107, false, true, true, 0, .45, 2.5, false);
        moves(2, 3);
        stats.click(1158, true, false, false, 3, .40, 2.7, false);
        moves(3, 4);
        stats.click(1209, false, false, true, 0, .35, 3.51, false);
        moves(4, 5);
        stats.click(1259, true, false, true, 0, .30, 2, true);
        stats.click(1260, true, false, false, 0, .18, 2, false);
        moves(5, 6);
        List<String> events = stats.last.brakeTimeline();
        check(events.size() == 6, "Record distinct causes across moves without repeating the same failed click or a spent sprint");
        check(events.get(0).startsWith("+4 ms  Clicked the opponent") && events.get(0).contains("W-up move") &&
                events.get(0).contains("With sprint on") && events.get(0).contains("blocks less slide"),
                "A sprint-off click explicitly credits just its own possible brake");
        check(events.get(1).startsWith("+55 ms  Crosshair on the opponent, no click") &&
                events.get(1).contains("One fix alone gives no brake") && events.get(1).contains("With a click here and sprint on"),
                "Record an unclicked opening even when sprint was off too");
        check(events.get(2).startsWith("+107 ms") && events.get(2).contains("With aim corrected") && events.get(2).contains("blocks less slide"),
                "Give a nearby aim miss its own estimate");
        check(events.get(3).startsWith("+158 ms") && events.get(3).contains("miss timer (3 ticks left)") &&
                events.get(3).contains("With the timer clear and sprint on"), "Explain both blockers on a timer-blocked click");
        check(events.get(4).startsWith("+209 ms") && events.get(4).contains("3.51 blocks to hitbox") &&
                events.get(4).contains("Aim alone would still be out of reach here"), "Record a separate out-of-range click");
        check(events.get(5).startsWith("+259 ms") && events.get(5).contains("and braked"), "Keep a successful brake in timeline order");

        reset();
        stats.impact(1000, .9, 0, Double.NaN, Integer.MIN_VALUE, true);
        stats.inputSlot(1002, true, true, 0, .9);
        double coast = .9, withClick = .54, difference = 0;
        for (int move = 0; move < SumoStats.MOVES; move++) {
            stats.move(1010 + 50 * move, coast, 0, false, true, .91);
            difference += coast - withClick;
            coast *= .91;
            withClick *= .91;
        }
        check(stats.last.brakeTimeline().get(0).contains("With a click here, ~" + SumoStats.blocks(difference) + " less slide"),
                "No-click estimate agrees with an independent trajectory containing just that one click");
    }

    public static void main(String[] args) {
        SumoStatsChecks checks = new SumoStatsChecks();
        checks.cases();
        checks.savingCases();
        checks.costlyBrakeCases();
        checks.idealBrakeCase();
        checks.lossTimelineCase();
        checks.brakeTimelineCase();
        System.out.println("SumoStatsChecks passed");
    }
}
