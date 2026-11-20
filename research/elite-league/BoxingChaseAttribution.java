import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lab.Json;

/** Isolate footwork, lost pursuit and click delay at a shared established-combo state. */
final class BoxingChaseAttribution {
    private static final List<List<String>> WORDS = List.of(
            List.of("back", "keep", "keep"),
            List.of("back", "walk", "keep"),
            List.of("back", "walk", "back"));
    private static final int[] HOLDS_MS = {0, 30, 60, 100, 150, 200, 250};
    private static final boolean CUE_SCREEN = Boolean.getBoolean("lab.boxing.chaseObservationScreen");
    private static final List<String> LATERAL_WORD = List.of("walk", "right", "sprint-left");

    private static final class ClickHold extends DuelEnv.Player {
        final DuelEnv.Player movement;
        final double release;

        ClickHold(DuelEnv.Player movement, double release) {
            super(movement.style, movement.mask, movement.rtt, movement.wake);
            this.movement = movement.copy();
            this.release = release;
            firstClick = movement.firstClick;
        }

        @Override public PeriodicComboClock.Control input(PeriodicComboClock.Observation own) {
            return movement.input(own);
        }

        @Override public void received(PeriodicComboClock.PacketObservation packet) { movement.received(packet); }

        @Override public boolean allowInjection(PeriodicComboClock.Observation own, String source) {
            return own.time() >= release;
        }

        @Override public boolean injected(PeriodicComboClock.Observation own, double time) {
            return movement.injected(own, time);
        }
    }

    private record Press(double admission, double consume, double ready, double nearest, double ray, double height,
                         double vx, double vz, boolean sprint) {
        Map<String, Object> values(double anchor) {
            return Map.of("admissionMs", admission - anchor, "consumeMs", consume - anchor,
                    "readyMs", ready - anchor, "nearest", nearest, "aimRay", ray, "targetYMinusOwn", height,
                    "ownVx", vx, "ownVz", vz, "sprintAfterClick", sprint);
        }
    }

    private record Result(BoxingChaseEnvelope.Evidence evidence, Press launch, Press reply,
                          List<DuelEnv.Hit> accepted, List<Integer> extraPresses,
                          List<DefenderPress> defenderPresses) {
        Map<String, Object> values(double anchor) {
            var values = new LinkedHashMap<>(evidence.values(anchor));
            values.put("firstFreshPress", launch == null ? null : launch.values(anchor));
            values.put("returnPress", reply == null ? null : reply.values(anchor));
            values.put("returnPressMinusLaunchMs", reply == null || evidence.first() == null
                    ? null : reply.consume - evidence.first().launch());
            values.put("physicalPressesAfterFork", extraPresses);
            values.put("defenderPresses", defenderPresses.stream().map(p -> p.values(anchor)).toList());
            return values;
        }
    }

    private record DefenderPress(double time, boolean eligible, boolean contact, double nearest) {
        List<Object> values(double anchor) { return List.of(time - anchor, eligible, contact, nearest); }
    }

    private static Result play(DuelEnv.Game game, double landmark, double end) {
        var presses = new HashMap<Long, Press>();
        var defenderPresses = new ArrayList<DefenderPress>();
        var physical = new int[2];
        game.clock.setClickAudit(e -> {
            if (e.action().equals("ADMIT")) physical[e.fromA() == game.first ? 0 : 1]++;
            if (!e.action().equals("CONSUME")) return;
            var state = game.clock.state;
            var self = e.fromA() ? state.a : state.b;
            var other = e.fromA() ? state.ab : state.ba;
            if (e.fromA() != game.first && e.time() <= landmark + 2000) {
                defenderPresses.add(new DefenderPress(e.time(), e.eligible(), e.contact(),
                        PeriodicComboFightModel.nearest(self.q, other)));
            }
            if (!e.contact()) return;
            int kind = e.fromA() ? 3 : 7;
            var packet = state.packets.stream().filter(p -> p.kind == kind)
                    .max(java.util.Comparator.comparingLong(p -> p.sequence)).orElseThrow();
            var q = self.q;
            presses.put(packet.sequence, new Press(e.admittedNanos() / 1e6, e.time(), packet.ready,
                    PeriodicComboFightModel.nearest(q, other), PeriodicComboFightModel.ray(self, other),
                    other.y - q.y(), q.vx(), q.vz(), self.sprint));
        });
        var linked = new Press[2];
        var launchSeen = new boolean[1];
        var evidence = BoxingChaseEnvelope.trace(game, landmark, end, null, (hit, state) -> {
            boolean own = hit.sideA() == game.first;
            if (own && (launchSeen[0] || hit.upgrade())) return;
            long id = hit.upgrade() ? (hit.sideA() ? state.lastAcceptedPacketA : state.lastAcceptedPacketB)
                    : (hit.sideA() ? state.lastFreshPacketA : state.lastFreshPacketB);
            linked[own ? 0 : 1] = presses.get(id);
            if (own) launchSeen[0] = true;
        });
        return new Result(evidence, linked[0], linked[1], List.copyOf(game.hits.list),
                List.of(physical[0], physical[1]), defenderPresses);
    }

    private record ClientSample(double time, double x, double z,
                                double targetX, double targetY, double targetZ) {}

    private static Map<String, Object> observedAtCue(DuelEnv.Game game, List<ClientSample> past) {
        var state = game.clock.state;
        var body = game.first ? state.a : state.b;
        var replica = game.first ? state.ab : state.ba;
        var q = body.q;
        double now = game.clock.time();
        double x = replica.x - q.x(), z = replica.z - q.z(), gap = Math.hypot(x, z);
        double ux = gap == 0 ? 0 : x / gap, uz = gap == 0 ? 0 : z / gap;
        var fields = new LinkedHashMap<String, Object>();
        fields.put("nearest", PeriodicComboFightModel.nearest(q, replica));
        fields.put("height", replica.y - q.y());
        fields.put("ownAlong", q.vx() * ux + q.vz() * uz);
        fields.put("ownAcross", Math.abs(q.vx() * uz - q.vz() * ux));
        fields.put("ownVertical", q.vy());
        fields.put("ground", q.onGround() ? 1 : 0);
        fields.put("sprint", body.sprint ? 1 : 0);
        fields.put("rtt", game.our().rtt);
        fields.put("sinceCue", now - replica.lastHurtTime);
        fields.put("sinceContact", boundedAge(now, body.lastContactTime));
        fields.put("sinceOwnHurt", boundedAge(now, body.lastHurtTime));
        for (int threshold : new int[]{35, 90}) {
            ClientSample old = null;
            for (int j = past.size() - 1; j >= 0; j--) {
                var sample = past.get(j);
                if (now - sample.time >= threshold) {
                    old = sample;
                    break;
                }
            }
            fields.put("closure" + threshold, old == null ? null :
                    50 * (Math.hypot(old.targetX - old.x, old.targetZ - old.z) - gap) / (now - old.time));
            fields.put("targetAlong" + threshold, old == null ? null :
                    50 * ((replica.x - old.targetX) * ux + (replica.z - old.targetZ) * uz) / (now - old.time));
            fields.put("targetVertical" + threshold, old == null ? null :
                    50 * (replica.y - old.targetY) / (now - old.time));
        }
        return fields;
    }

    private static double boundedAge(double time, double seen) {
        return Double.isFinite(seen) ? Math.min(2000, time - seen) : 2000;
    }

    private static void collectOwnHistory(BoxingDrill.Checkpoint checkpoint, DuelEnv.Game game,
                                          List<ClientSample> past) {
        var history = checkpoint.history;
        for (var head : history.subList(Math.max(0, history.size() - 6), history.size())) {
            var o = head.view();
            past.add(new ClientSample(o.time(), o.own().x(), o.own().z(),
                    o.targetX(), o.targetY(), o.targetZ()));
        }
        String head = game.first ? "POLICYA" : "POLICYB";
        game.clock.setAudit((time, stage, state) -> {
            game.hits.event(time, stage, state);
            if (!stage.equals(head)) return;
            var body = game.first ? state.a : state.b;
            var target = game.first ? state.ab : state.ba;
            past.add(new ClientSample(time, body.q.x(), body.q.z(), target.x, target.y, target.z));
        });
    }

    private static Map<String, Object> phase(BoxingDrill.Checkpoint checkpoint, Arena.Model model,
                                              BoxingChaseEnvelope.Phase timing, double end) {
        double landmark = checkpoint.landmark;
        var tap = play(BoxingChaseEnvelope.start(checkpoint, null, timing), landmark, end);
        var frozen = play(BoxingChaseEnvelope.start(checkpoint, model, timing), landmark, end);
        var out = new LinkedHashMap<String, Object>();
        out.put("phase", timing.values());
        out.put("wtap", tap.values(landmark));
        out.put("frozen", frozen.values(landmark));
        var cue = BoxingChaseEnvelope.start(checkpoint, model, timing);
        double deadline = Math.nextDown(Math.min(end, landmark + frozen.evidence.score().returnOnlyMs()));
        var past = new ArrayList<ClientSample>();
        if (CUE_SCREEN) collectOwnHistory(checkpoint, cue, past);
        if (BoxingChaseEnvelope.advanceToLocalCue(cue, landmark, deadline)) {
            var footwork = new ArrayList<Map<String, Object>>();
            var words = CUE_SCREEN ? List.of(WORDS.get(2), LATERAL_WORD) : WORDS;
            for (var word : words) {
                var player = new BoxingChaseEnvelope.Movement(cue.our(), BoxingChaseEnvelope.movementWord(word));
                var branch = BoxingChaseEnvelope.branch(checkpoint.env, cue, player);
                footwork.add(Map.of("keys", word, "result", play(branch, landmark, end).values(landmark)));
            }
            out.put("cueMs", cue.clock.time() - landmark);
            if (CUE_SCREEN) out.put("observed", observedAtCue(cue, past));
            out.put("footwork", footwork);
        }
        if (!CUE_SCREEN && frozen.launch != null && frozen.launch.admission > checkpoint.state.clock.time()) {
            var cut = BoxingChaseEnvelope.start(checkpoint, model, timing);
            cut.clock.runUntil(Math.nextDown(frozen.launch.admission));
            var holds = new ArrayList<Map<String, Object>>();
            for (int ms : HOLDS_MS) {
                var player = new ClickHold(cut.our(), frozen.launch.admission + ms);
                var branch = BoxingChaseEnvelope.branch(checkpoint.env, cut, player);
                var scored = play(branch, landmark, end);
                if (ms == 0 && (!scored.evidence.equals(frozen.evidence) || !scored.accepted.equals(frozen.accepted)))
                    throw new AssertionError("Unchanged pre-press fork changed the accepted sequence");
                holds.add(Map.of("ms", ms, "result", scored.values(landmark)));
            }
            out.put("holds", holds);
        }
        return out;
    }

    public static void main(String[] args) throws Exception {
        var model = Arena.Model.decode(Files.readString(Path.of(args[0])).strip());
        int rtt = Integer.parseInt(args[1]);
        boolean regulation = Boolean.getBoolean("lab.boxing.regulationChase");
        for (int i = 4; i < args.length; i++) {
            var env = BoxingEdgeHitSelect.environment(Path.of(args[i]), rtt, regulation ? 180_000 : 20_000,
                    Path.of(args[2]), Path.of(args[3]));
            for (boolean first : new boolean[]{true, false}) {
                var starts = regulation ? BoxingDrill.regulationThirdHits(env, first) : BoxingDrill.acquire(env, first, false);
                for (var cp : starts) {
                    double end = Math.min(env.limit, cp.landmark + BoxingChaseEnvelope.HORIZON_MS);
                    var variants = BoxingChaseEnvelope.PHASES.stream().map(p -> phase(cp, model, p, end)).toList();
                    System.out.println(Json.encode(Map.of("clock", env.clockId, "seat", first ? "a" : "b",
                            "rtt", rtt, "k3Ms", cp.landmark, "variants", variants)));
                }
            }
        }
    }
}
