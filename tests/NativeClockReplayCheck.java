import lab.ComboModel;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Independent offline-clock contracts, not a claim of native trajectory accuracy. */
public final class NativeClockReplayCheck {
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    private static void near(double actual, double expected, String message) {
        require(Math.abs(actual - expected) < 1e-9, message + ": " + actual + " != " + expected);
    }
    private static ComboModel.State initial(double gap, boolean sprint) {
        return new ComboModel.State(0,
                new ComboModel.Body(0, 0, 0, -.0784, true, sprint),
                new ComboModel.Body(gap, 0, 0, -.0784, true, sprint),
                new ComboModel.View(gap, 0), new ComboModel.View(0, 0),
                new ComboModel.View(0, 0), new ComboModel.View(gap, 0));
    }
    private static ComboModel.State event(ComboModel.State s, long time,
            ComboModel.NativeEventKind kind, ComboModel.Action action, boolean click, ComboModel.Config c) {
        return ComboModel.replayNativeEvent(s, time, kind, action, click, c);
    }
    private static void sameBody(ComboModel.Body a, ComboModel.Body b) {
        double[] av = {a.x, a.y, a.z, a.vx, a.vy, a.vz, a.groundAcceleration, a.airAcceleration};
        double[] bv = {b.x, b.y, b.z, b.vx, b.vy, b.vz, b.groundAcceleration, b.airAcceleration};
        for (int i = 0; i < av.length; i++) near(av[i], bv[i], "fixed-clock body component " + i);
        require(a.ground == b.ground && a.sprint == b.sprint, "fixed-clock body flags");
    }
    private static void sameView(ComboModel.View a, ComboModel.View b) {
        double[] av = {a.x, a.y, a.z, a.goalX, a.goalY, a.goalZ, a.lookX, a.lookZ};
        double[] bv = {b.x, b.y, b.z, b.goalX, b.goalY, b.goalZ, b.lookX, b.lookZ};
        for (int i = 0; i < av.length; i++) near(av[i], bv[i], "fixed-clock view component " + i);
        require(a.interpolationTicks == b.interpolationTicks, "fixed-clock interpolation clock");
    }
    private static void fixedClockEquivalence() {
        for (int serverPhase : new int[]{5, 25}) for (int rtt : new int[]{0, 100}) {
            ComboModel.Config c = new ComboModel.Config();
            c.serverPhaseMs = serverPhase; c.opponentPhaseMs = 10;
            c.ownRttMs = rtt; c.opponentRttMs = 100 - rtt;
            c.defenderClicks = true;
            ComboModel.State fixed = initial(3, true), recorded = fixed.copy();
            for (int i = 0; i < 40; i++) {
                ComboModel.Action action = i % 7 == 0 ? ComboModel.Action.RELEASE : ComboModel.Action.SPRINT;
                fixed = ComboModel.advanceRecorded(fixed, action, i % 2 == 0, c);
                recorded = event(recorded, i * 50, ComboModel.NativeEventKind.A, action, i % 2 == 0, c);
                if (serverPhase < 10)
                    recorded = event(recorded, i * 50 + serverPhase, ComboModel.NativeEventKind.SERVER, null, false, c);
                recorded = event(recorded, i * 50 + 10, ComboModel.NativeEventKind.B,
                        ComboModel.Action.SPRINT, i % 2 == 0, c);
                if (serverPhase > 10)
                    recorded = event(recorded, i * 50 + serverPhase, ComboModel.NativeEventKind.SERVER, null, false, c);
                sameBody(fixed.a, recorded.a); sameBody(fixed.b, recorded.b);
                sameBody(fixed.serverMotionA, recorded.serverMotionA);
                sameBody(fixed.serverMotionB, recorded.serverMotionB);
                sameView(fixed.aSeesB, recorded.aSeesB); sameView(fixed.bSeesA, recorded.bSeesA);
                sameView(fixed.serverA, recorded.serverA); sameView(fixed.serverB, recorded.serverB);
                require(fixed.acceptedHits == recorded.acceptedHits && fixed.receivedHits == recorded.receivedHits
                        && fixed.acceptedFreshHits == recorded.acceptedFreshHits
                        && fixed.receivedFreshHits == recorded.receivedFreshHits
                        && fixed.acceptedDamageUpgrades == recorded.acceptedDamageUpgrades
                        && fixed.receivedDamageUpgrades == recorded.receivedDamageUpgrades,
                        "fixed-clock combat uses identical transitions");
                near(fixed.accumulatedReward, recorded.accumulatedReward, "fixed-clock reward");
                require(fixed.pendingPacketCount() == recorded.pendingPacketCount(), "fixed-clock queue size");
            }
        }
    }
    private static void isolatedClocksAndRecordedInputs() {
        ComboModel.Config c = new ComboModel.Config();
        c.defenderWtap = true; c.defenderMoves = false; c.defenderControlAtMs = Long.MAX_VALUE;
        ComboModel.State seed = initial(100, false);
        ComboModel.State s = event(seed, 500, ComboModel.NativeEventKind.B, ComboModel.Action.SPRINT, true, c);
        near(s.b.x, 100 - .1274, "recorded B sprint overrides inferred policy/start gates");
        near(s.a.x, 0, "B event never invents an A tick");
        require(s.nowMs == 500 && s.serverTicks == 0 && s.defenderPhysicalClicks == 1
                && s.defenderAirMisses == 1 && s.physicalClicks == 0, "one supplied event only");
        require(seed.nowMs == 0 && seed.pendingPacketCount() == 0 && seed.b.x == 100,
                "replay leaves initial snapshot independent");
        c.defenderClicks = true; c.defenderMoves = true; c.defenderControlAtMs = 0;
        s = event(s, 10_000, ComboModel.NativeEventKind.B, ComboModel.Action.RELEASE, false, c);
        require(s.defenderPhysicalClicks == 1 && !s.b.sprint && s.serverTicks == 0,
                "large clock gap creates neither catch-up ticks nor automatic clicks");
        s = event(s, 10_000, ComboModel.NativeEventKind.A, ComboModel.Action.RELEASE, false, c);
        require(s.physicalClicks == 0, "recorded false suppresses A clock-generated clicks");
    }
    private static void packetTimingAndTieOrder() {
        ComboModel.Config c = new ComboModel.Config();
        c.ownRttMs = 50; c.opponentRttMs = 100;
        ComboModel.State s = event(initial(3, true), 0, ComboModel.NativeEventKind.A,
                ComboModel.Action.RELEASE, true, c);
        s = event(s, 24, ComboModel.NativeEventKind.SERVER, null, false, c);
        require(s.acceptedHits == 0, "attack cannot arrive before configured uplink delay");
        s = event(s, 25, ComboModel.NativeEventKind.SERVER, null, false, c);
        require(s.acceptedFreshHits == 1, "due attack executes at first supplied server event");
        s = event(s, 74, ComboModel.NativeEventKind.B, ComboModel.Action.RELEASE, false, c);
        near(s.b.y, 0, "victim does not receive impulse before downlink delay");
        s = event(s, 75, ComboModel.NativeEventKind.B, ComboModel.Action.RELEASE, false, c);
        require(s.b.y > .3 && s.defenderPhysicalClicks == 0, "due impulse uses shared receive/travel path");
        c.ownRttMs = c.opponentRttMs = 0;
        ComboModel.State before = event(initial(3, false), 0, ComboModel.NativeEventKind.SERVER, null, false, c);
        before = event(before, 0, ComboModel.NativeEventKind.A, ComboModel.Action.RELEASE, true, c);
        require(before.acceptedHits == 0, "same-time earlier server event cannot see later attack");
        ComboModel.State after = event(initial(3, false), 0, ComboModel.NativeEventKind.A,
                ComboModel.Action.RELEASE, true, c);
        after = event(after, 0, ComboModel.NativeEventKind.SERVER, null, false, c);
        require(after.acceptedHits == 1, "caller order controls same-time server/client ambiguity");
    }
    private static void invalidEventsAndTsv() throws Exception {
        ComboModel.Config c = new ComboModel.Config();
        ComboModel.State seed = initial(10, false);
        int rejected = 0;
        try { event(seed, -1, ComboModel.NativeEventKind.A, ComboModel.Action.RELEASE, false, c); }
        catch (IllegalArgumentException expected) { rejected++; }
        try { event(seed, 0, ComboModel.NativeEventKind.B, ComboModel.Action.WALK, false, c); }
        catch (IllegalArgumentException expected) { rejected++; }
        try { event(seed, 0, ComboModel.NativeEventKind.SERVER, null, true, c); }
        catch (IllegalArgumentException expected) { rejected++; }
        require(rejected == 3, "invalid clocks/actions fail explicitly");
        String init = "INIT\t0\t0\t0\t0\t0\t0\t0\t10\t0\t0";
        for (String extra : new String[]{"", "\t9.5\t0\t0\t0.5\t0\t0"}) {
            String input = init + extra + "\n0\tSERVER\t-\t0\n1\tA\tSPRINT\t0\n2\tB\tRELEASE\t1\n";
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            NativeClockReplay.run(new BufferedReader(new StringReader(input)), new PrintStream(bytes));
            String[] rows = bytes.toString(StandardCharsets.UTF_8).strip().split("\n");
            String[] header = rows[0].split("\t");
            require(rows.length == 4 && Arrays.asList(header).contains("a_x")
                    && Arrays.asList(header).contains("b_z"), "TSV header and exactly one row per event");
            for (int i = 1; i < rows.length; i++) require(rows[i].split("\t").length == header.length,
                    "TSV data columns align with header");
            if (!extra.isEmpty()) {
                int viewColumn = Arrays.asList(header).indexOf("a_sees_b_x");
                near(Double.parseDouble(rows[1].split("\t")[viewColumn]), 9.5,
                        "optional initial rendered view is distinct from body");
            }
        }
    }
    public static void main(String[] args) throws Exception {
        fixedClockEquivalence();
        isolatedClocksAndRecordedInputs();
        packetTimingAndTieOrder();
        invalidEventsAndTsv();
        System.out.println("NativeClockReplayCheck: all contracts passed");
    }
}
