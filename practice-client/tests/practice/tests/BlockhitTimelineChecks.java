package practice.tests;

import practice.core.BlockhitGuard;
import practice.core.BlockhitTimeline;
import practice.core.BlockhitTimeline.Hit;

final class BlockhitTimelineChecks {
    private static final long MS = 1_000_000L;

    static void run() {
        BlockhitTimeline<String> inputs = new BlockhitTimeline<>();
        inputs.loss(20, "before red", .6, false, true);
        inputs.push(20, .3, .2);
        inputs.hurt(21, "hurt after red", false);
        inputs.push(21, .3, .2);
        inputs.critical(21);
        inputs.arrow(21);
        inputs.loss(23, "before yellow", .4, true, true);
        assert inputs.poll(23) == null;
        Hit<String> hit = inputs.poll(24);
        assert hit.atHurt.equals("hurt after red") && hit.atLoss.equals("before red");
        assert hit.health == .6 && hit.absorption == .4 && hit.critical && hit.clearPush && hit.arrow;
        assert inputs.poll(40) == null;

        inputs.clear();
        inputs.hurt(30, "first", false);
        inputs.push(30, .5, 0);
        inputs.hurt(31, "second", false);
        inputs.push(31, .7, 0);
        inputs.loss(31, "red one", .9, false, false);
        inputs.loss(32, "red two", 1.3, false, false);
        Hit<String> first = inputs.poll(36), second = inputs.poll(36);
        assert first.atHurt.equals("first") && first.health == .9 && first.pushX == .5;
        assert second.atHurt.equals("second") && second.health == 1.3 && second.pushX == .7;
        assert inputs.poll(40) == null;

        inputs.clear();
        inputs.hurt(50, "rod", true);
        inputs.loss(57, "upgrade", .9, true, false);
        Hit<String> upgrade = inputs.poll(61);
        assert upgrade.atHurt.equals("rod") && upgrade.absorption == .9;
        inputs.loss(62, "unattributed absorption", .4, true, true);
        assert inputs.poll(66).atHurt == null;

        inputs.clear();
        inputs.hurt(70, "first", false);
        inputs.chime(70);
        inputs.loss(70, "first yellow", .9, true, false);
        inputs.hurt(71, "second", false);
        inputs.loss(72, "second red", 1.6, false, false);
        assert inputs.poll(76).serverChime;
        Hit<String> open = inputs.poll(76);
        assert !open.serverChime && !open.arrow && open.atHurt.equals("second") && open.absorption == 0;

        BlockhitGuard guard = new BlockhitGuard();
        Object connection = new Object();
        guard.transition(connection, 99 * MS, true);
        guard.transition(connection, 150 * MS, false);
        assert guard.covers(connection, 150 * MS - 1, 0);
        assert !guard.covers(connection, 151 * MS, 0);
        guard.transition(connection, 200 * MS, true);
        guard.transition(connection, 230 * MS, true);
        assert guard.covers(connection, 350 * MS, 100);
        assert !guard.covers(new Object(), 350 * MS, 100);
        guard.clear();
        assert !guard.covers(connection, 350 * MS, 100);
        System.out.println("PASS blockhit packet reordering, split damage, overlapping hits, and outbound guard timing");
    }
}
