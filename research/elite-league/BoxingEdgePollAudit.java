import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lab.Json;

/** Isolate a single held click on each stock opportunity marked by the causal rule. */
final class BoxingEdgePollAudit {
    private static final double HORIZON = 1400;
    private static final List<Integer> EXTRA_HOLDS = java.util.Arrays
            .stream(System.getProperty("lab.boxing.edgeAuditHolds", "").split(","))
            .filter(s -> !s.isEmpty()).map(Integer::parseInt).toList();

    static class ObservationHistory extends BoxingEdgeHitSelect.Selector {
        final ArrayDeque<PeriodicComboClock.Observation> heads = new ArrayDeque<>();
        final Map<Double, Map<String, Map<String, Object>>> marks = new LinkedHashMap<>();
        PeriodicComboClock.PacketObservation lastPosition, previousPosition;
        double lastPress = Double.NEGATIVE_INFINITY;

        ObservationHistory(DuelEnv.Player movement) { this(movement, true); }

        ObservationHistory(DuelEnv.Player movement, boolean shadow) { super(movement, shadow); }

        private void remember(PeriodicComboClock.Observation own) {
            heads.addLast(own);
            if (heads.size() > 5) heads.removeFirst();
        }

        void prime(List<BoxingDrill.Head> history, double press) {
            for (var head : history) remember(head.view());
            lastPress = press;
        }

        @Override public PeriodicComboClock.Control input(PeriodicComboClock.Observation own) {
            remember(own);
            return super.input(own);
        }

        @Override public void received(PeriodicComboClock.PacketObservation packet) {
            if (packet.kind() == PeriodicComboClock.ReceivedKind.TARGET_POSITION
                    && (packet.packetId() == 0x15 || packet.packetId() == 0x17 || packet.packetId() == 0x18)) {
                previousPosition = lastPosition;
                lastPosition = packet;
            }
        }

        @Override public boolean injected(PeriodicComboClock.Observation own, double at) {
            lastPress = at;
            return super.injected(own, at);
        }

        private PeriodicComboClock.Observation before(double time, double age) {
            for (var it = heads.descendingIterator(); it.hasNext(); ) {
                var old = it.next();
                if (time - old.time() >= age) return old;
            }
            return null;
        }

        @Override void marked(PeriodicComboClock.Observation own, String source) {
            if (shadow) marks.put(own.time(), observe(own, source));
        }

        Map<String, Map<String, Object>> observe(PeriodicComboClock.Observation own, String source) {
            double now = own.time();
            double dx = own.targetX() - own.own().x(), dz = own.targetZ() - own.own().z();
            double norm = Math.hypot(dx, dz);
            double ux = dx / norm, uz = dz / norm;
            var geometry = new LinkedHashMap<String, Object>();
            geometry.put("nearest", own.nearest());
            geometry.put("height", own.targetY() - own.own().y());
            geometry.put("ownToward", own.own().vx() * ux + own.own().vz() * uz);
            geometry.put("ownAcross", Math.abs(own.own().vx() * uz - own.own().vz() * ux));
            geometry.put("ownSprint", own.sprint() ? 1 : 0);
            geometry.put("ownHurtAge", age(now, own.lastOwnHurtTime(), 2000));
            geometry.put("targetHurtAge", age(now, own.lastTargetHurtTime(), 2000));
            geometry.put("contactAge", age(now, own.lastContactTime(), 2000));
            geometry.put("sincePress", age(now, lastPress, 200));
            geometry.put("nativePoll", source.equals("native_tick") ? 1 : 0);
            var latest = heads.peekLast();
            geometry.put("headAge", latest == null ? null : now - latest.time());
            for (double age : new double[]{1, 85}) {
                var old = before(now, age);
                double period = old == null ? 0 : now - old.time();
                String suffix = age == 1 ? "Short" : "Long";
                geometry.put("targetToward" + suffix, old == null ? null : 50 * (
                        (old.targetX() - own.targetX()) * ux + (old.targetZ() - own.targetZ()) * uz) / period);
                geometry.put("closing" + suffix, old == null ? null : 50 * (
                        Math.hypot(old.targetX() - old.own().x(), old.targetZ() - old.own().z()) - norm) / period);
            }
            var packets = new LinkedHashMap<String, Object>();
            if (lastPosition != null && (lastPosition.handledAt() > now || lastPosition.arrivedAt() > now))
                throw new AssertionError("Position packet was not yet visible at the marked poll");
            packets.put("receivedAge", lastPosition == null ? null : age(now, lastPosition.arrivedAt(), 250));
            packets.put("handledAge", lastPosition == null ? null : age(now, lastPosition.handledAt(), 250));
            packets.put("arrivalInterval", previousPosition == null ? null :
                    Math.min(250, lastPosition.arrivedAt() - previousPosition.arrivedAt()));
            packets.put("goalAhead", lastPosition == null ? null :
                    (lastPosition.goalX() - own.targetX()) * ux + (lastPosition.goalZ() - own.targetZ()) * uz);
            packets.put("goalMotion", previousPosition == null ? null :
                    (lastPosition.goalX() - previousPosition.goalX()) * ux
                            + (lastPosition.goalZ() - previousPosition.goalZ()) * uz);
            return Map.of("geometry", geometry, "packets", packets);
        }

        private static Double age(double now, double seen, double max) {
            return Double.isFinite(seen) ? Math.min(max, now - seen) : null;
        }
    }

    private static Map<String, Object> replay(DuelEnv env, DuelEnv.Game anchor, DuelEnv.Game original,
                                               double marked, double end, int own, int other) {
        var shifted = BoxingUpgradeDeadline.branch(env, anchor, false);
        if (own != 0) shifted.clock.shiftFutureClientOpportunities(shifted.first, own);
        if (other != 0) shifted.clock.shiftFutureClientOpportunities(!shifted.first, other);
        var stock = BoxingUpgradeDeadline.branch(env, shifted, false);
        var ordinary = BoxingUpgradeHitSelection.play(stock, end);
        var wtap = BoxingUpgradeHitSelection.play(BoxingUpgradeDeadline.branch(env, shifted, true), end);
        var held = BoxingUpgradeHitSelection.play(BoxingUpgradeHitSelection.hold(env, shifted, marked + 80), end);
        if (own == 0 && other == 0 && (ordinary.firstAdmission != marked
                || !stock.hits.list.equals(BoxingUpgradeDeadline.suffix(original, marked, end))))
            throw new AssertionError("Exact pre-poll fork failed to replay");
        var row = new LinkedHashMap<String, Object>(Map.of("pollMs", marked, "phase", List.of(own, other),
                "stock", ordinary.values(marked), "wtap", wtap.values(marked),
                "held", held.values(marked), "deltaStock", held.margin() - ordinary.margin(),
                "deltaWtap", held.margin() - wtap.margin()));
        if (!EXTRA_HOLDS.isEmpty()) {
            var additional = new LinkedHashMap<String, Object>();
            for (int hold : EXTRA_HOLDS) {
                double releaseAt = hold == 0 ? Math.nextUp(marked) : marked + hold;
                var branch = BoxingUpgradeHitSelection.hold(env, shifted, releaseAt);
                var result = BoxingUpgradeHitSelection.play(branch, end);
                if (result.firstAdmission < releaseAt) throw new AssertionError("Press admitted before release");
                additional.put(Integer.toString(hold), result.values(marked));
            }
            row.put("extraHolds", additional);
        }
        return row;
    }

    private static List<Map<String, Object>> run(DuelEnv env, boolean first, Arena.Model model) {
        var shadow = new ObservationHistory(new Arena.Neural(model, env.rtt));
        var original = env.new Game(first, shadow, Evolve.WT.create(env.rtt, 0));
        original.trace();
        var score = BoxingDoubleSearch.play(original, 100);
        var reference = BoxingDoubleSearch.play(env, first, new Arena.Neural(model, env.rtt), 100);
        if (!score.equals(reference)) throw new AssertionError("Passive marks altered the stock score");

        double finish = BoxingEdgeHitSelect.decidingHit(original);
        var anchor = BoxingUpgradeDeadline.start(env, first, model);
        var output = new ArrayList<Map<String, Object>>();
        for (double marked : shadow.starts) {
            double end = marked + HORIZON;
            if (end >= finish) continue;
            anchor.clock.runUntil(Math.nextDown(marked));
            var row = new LinkedHashMap<>(replay(env, anchor, original, marked, end, 0, 0));
            row.put("observed", shadow.marks.get(marked));
            int opponentAttack = first ? 7 : 3;
            row.put("oracleOpponentC02Queued", anchor.clock.state.packets.stream()
                    .anyMatch(p -> p.kind == opponentAttack));
            var sweeps = new ArrayList<Map<String, Object>>();
            for (int own : new int[]{0, 5, -5}) for (int other : new int[]{0, 5, -5}) {
                if (own != 0 || other != 0) sweeps.add(replay(env, anchor, original, marked, end, own, other));
            }
            row.put("shifted", sweeps);
            output.add(row);
        }
        return output;
    }

    public static void main(String[] args) throws Exception {
        var model = Arena.Model.decode(Files.readString(Path.of(args[0])).strip());
        var wire = Path.of(args[1]);
        var seeds = Path.of(args[2]);
        int rtt = Integer.getInteger("lab.boxing.rtt", 20);
        for (int i = 3; i < args.length; i++) {
            var env = BoxingEdgeHitSelect.environment(Path.of(args[i]), rtt, 180000, wire, seeds);
            for (boolean first : new boolean[]{true, false}) {
                System.out.println(Json.encode(Map.of("clock", env.clockId, "seat", first ? "a" : "b",
                        "polls", run(env, first, model))));
            }
        }
    }
}
