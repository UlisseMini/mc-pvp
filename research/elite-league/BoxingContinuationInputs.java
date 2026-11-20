import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lab.DuelNetwork;
import lab.Json;

/** Diagnose one changed input in a matched W-tap or frozen-network continuation. */
final class BoxingContinuationInputs {
    private static final double MAX_ACTIVE_GAP = 1000;

    private record Decision(PeriodicComboClock.Observation view, PeriodicComboClock.Control wtap,
                            PeriodicComboClock.Control network) {}

    private static final class InputProbe extends DuelEnv.Player {
        final Arena.Neural network;
        final boolean baseNetwork;
        final int alternateHead;
        final List<Decision> decisions = new ArrayList<>();

        InputProbe(BoxingDrill.Checkpoint start, Arena.Neural primed, boolean baseNetwork, int alternateHead) {
            super("wtap", 0, start.env.rtt, 0);
            var wtap = start.state.our();
            handled = wtap.handled;
            releaseUntil = wtap.releaseUntil;
            firstClick = wtap.firstClick;
            network = primed.copy();
            this.baseNetwork = baseNetwork;
            this.alternateHead = alternateHead;
        }

        InputProbe(InputProbe prefix, boolean baseNetwork, int alternateHead) {
            super("wtap", 0, prefix.rtt, prefix.wake);
            handled = prefix.handled;
            releaseUntil = prefix.releaseUntil;
            firstClick = prefix.firstClick;
            network = prefix.network.copy();
            this.baseNetwork = baseNetwork;
            this.alternateHead = alternateHead;
            decisions.addAll(prefix.decisions);
        }

        @Override public PeriodicComboClock.Control input(PeriodicComboClock.Observation o) {
            var wtap = super.input(o);
            var learned = network.input(o);
            if (wtap.click() != learned.click() || wtap.jump() != learned.jump() || wtap.strafe() != learned.strafe())
                throw new IllegalArgumentException("This probe requires matching clicks, strafe and jump");
            boolean useNetwork = baseNetwork ^ (decisions.size() == alternateHead);
            var actual = useNetwork ? learned : wtap;
            network.net.applied(DuelNetwork.pack(actual.key(), 0, actual.click(), actual.jump()));
            decisions.add(new Decision(o, wtap, learned));
            return actual;
        }
    }

    private record Outcome(List<DuelEnv.Hit> hits, List<Double> fresh, int active, double activeMs, String activeEnd,
                           double exposure, String end, List<Decision> decisions) {
        Map<String,Object> values() {
            return Map.of("freshMs", fresh, "active", active, "activeMs", activeMs, "activeEnd", activeEnd,
                    "returnOnly", fresh.size(), "exposureMs", exposure, "end", end);
        }
    }

    private static Outcome evaluate(BoxingDrill.Checkpoint start, DuelEnv.Game game, List<Decision> decisions) {
        var hits = new ArrayList<DuelEnv.Hit>();
        var fresh = new ArrayList<Double>();
        int seen = 0, active = 0;
        while (game.step < start.env.serverEnds.length && start.env.serverEnds[game.step] < game.clock.time())
            game.step++;
        double lastActive = start.landmark;
        double returnTime = Double.NaN;
        do {
            while (seen < game.hits.list.size()) {
                var hit = game.hits.list.get(seen++);
                hits.add(hit);
                if (hit.sideA() != game.first) {
                    returnTime = hit.at();
                    break;
                }
                if (hit.upgrade()) continue;
                if (active == fresh.size() && hit.at() - lastActive <= MAX_ACTIVE_GAP) {
                    active++;
                    lastActive = hit.at();
                }
                fresh.add(hit.at() - start.landmark);
            }
        } while (!Double.isFinite(returnTime) && game.advance());
        boolean returned = Double.isFinite(returnTime);
        double end = returned ? returnTime : game.clock.time();
        double activeEnd = Math.min(end, lastActive + MAX_ACTIVE_GAP);
        String terminal = returned ? "return" : "censored";
        return new Outcome(hits, fresh, active, activeEnd - start.landmark,
                activeEnd < end ? "inactive" : terminal, end - start.landmark, terminal, decisions);
    }

    private record Fork(DuelEnv.Game game, InputProbe player) {}

    private static Fork fork(BoxingDrill.Checkpoint start, Arena.Neural primed, boolean baseNetwork, int head, int phase) {
        var side = new InputProbe(start, primed, baseNetwork, head);
        var prefix = start.state;
        var game = start.env.new Game(prefix, prefix.first ? side : prefix.a.copy(), prefix.first ? prefix.b.copy() : side);
        shift(game, phase);
        game.trace();
        game.hits.list.addAll(start.committed);
        return new Fork(game, side);
    }

    private static void shift(DuelEnv.Game game, int phase) {
        if (phase != 0) game.clock.shiftFutureClientOpportunities(!game.first, phase);
    }

    private static Outcome probe(BoxingDrill.Checkpoint start, Arena.Neural primed, boolean baseNetwork, int head, int phase) {
        var fork = fork(start, primed, baseNetwork, head, phase);
        return evaluate(start, fork.game, fork.player.decisions);
    }

    private static final class ContactWire {
        final Map<Long,Map<String,Object>> ownContacts = new HashMap<>();
        final List<PeriodicComboFightModel.Packet> pending = new ArrayList<>();
        final List<List<Object>> sprintCommands = new ArrayList<>();
        final int attackKind, sprintKind;
        boolean serverSprint;

        ContactWire(DuelEnv.Game game) {
            attackKind = game.first ? 3 : 7;
            sprintKind = game.first ? 2 : 9;
            remember(game);
            game.clock.setClickAudit(e -> {
                if (!e.action().equals("CONSUME") || !e.contact() || e.fromA() != game.first) return;
                var state = game.clock.state;
                var packet = state.packets.stream().filter(p -> p.kind == attackKind)
                        .max(Comparator.comparingLong(p -> p.sequence)).orElseThrow();
                var body = game.first ? state.a : state.b;
                var view = game.first ? state.ab : state.ba;
                ownContacts.put(packet.sequence, Map.of("consumedMs", e.time(), "ray", PeriodicComboFightModel.ray(body, view),
                        "targetHeight", view.y - body.q.y(), "ownGround", body.q.onGround(),
                        "moveKey", game.clock.appliedControl(game.first).key()));
            });
        }

        void remember(DuelEnv.Game game) {
            var state = game.clock.state;
            serverSprint = game.first ? state.serverSprint : state.serverSprintB;
            pending.clear();
            for (var p : state.packets) if (p.kind == sprintKind || p.kind == attackKind) pending.add(p);
        }

        List<PeriodicComboFightModel.Packet> due(double time) {
            return pending.stream().filter(p -> p.ready <= time)
                    .sorted(Comparator.comparingDouble((PeriodicComboFightModel.Packet p) -> p.ready)
                            .thenComparingLong(p -> p.sequence)).toList();
        }

        void recordSprintCommands(double time) {
            for (var packet : due(time)) {
                if (packet.kind == sprintKind) sprintCommands.add(List.of(time, packet.flag));
            }
        }

        Map<String,Object> atDrain(double time) {
            var due = due(time);
            var attack = due.stream().filter(p -> p.kind == attackKind).toList();
            var result = new LinkedHashMap<String,Object>();
            result.put("serverSprintBeforeDrain", serverSprint);
            result.put("sprintCommandsThroughDrain", sprintCommands);
            result.put("readyInOrder", due.stream().map(p -> List.of(p.kind == attackKind ? "attack"
                    : p.flag ? "startSprint" : "stopSprint", p.sequence, p.ready)).toList());
            if (attack.size() == 1) result.put("linkedOwnContact", ownContacts.get(attack.get(0).sequence));
            return result;
        }
    }

    private static Map<String,Object> impulse(BoxingDrill.Checkpoint start, Arena.Neural primed, boolean baseNetwork,
                                              int head, int phase, int hitNumber, boolean change) {
        return measureImpulse(start, fork(start, primed, baseNetwork, head, phase), phase, hitNumber, change);
    }

    private static Map<String,Object> measureImpulse(BoxingDrill.Checkpoint start, Fork fork, int phase,
                                                     int hitNumber, boolean change) {
        var game = fork.game;
        var launch = new ArrayList<Double>();
        var geometry = new LinkedHashMap<String,Object>();
        var orderedAttacks = new LinkedHashMap<String,Object>();
        var wire = new ContactWire(game);
        var state = game.clock.state;
        int[] ranking = {game.first ? state.hits : state.incomingHits};
        int target = ranking[0] + hitNumber;
        long[] previousSequence = {state.seq};
        game.clock.setAudit((time, stage, plant) -> {
            game.hits.event(time, stage, plant);
            int fresh = game.first ? plant.hits : plant.incomingHits;
            if (ranking[0] < target && stage.equals("SERVER_DRAIN")) wire.recordSprintCommands(time);
            if (ranking[0] < target && fresh == target) {
                int velocityKind = game.first ? 4 : 8;
                var newPackets = plant.packets.stream().filter(p -> p.kind == velocityKind
                        && p.sequence >= previousSequence[0]).toList();
                if (newPackets.size() != 1) throw new AssertionError("Unlinked outgoing impulse");
                var packet = newPackets.get(0);
                double horizontal = Math.hypot(packet.vx, packet.vz);
                if (horizontal == 0) throw new AssertionError("Missing horizontal recoil");
                double alternative = horizontal > .65 ? .527375 : .854375;
                launch.addAll(List.of(time - start.landmark, horizontal, packet.vy, change ? alternative : horizontal));
                var own = game.first ? plant.a : plant.b;
                var targetView = game.first ? plant.ab : plant.ba;
                geometry.put("serverGap", Math.hypot(plant.serverAx - plant.serverBx, plant.serverAz - plant.serverBz));
                geometry.put("attackerGround", own.q.onGround());
                geometry.put("attackerHorizontalSpeed", Math.hypot(own.q.vx(), own.q.vz()));
                geometry.put("attackerLocalTargetHeight", targetView.y - own.q.y());
                geometry.put("attackerLocalNearest", PeriodicComboFightModel.nearest(own.q, targetView));
                orderedAttacks.putAll(wire.atDrain(time));
                if (change) {
                    packet.vx *= alternative / horizontal;
                    packet.vz *= alternative / horizontal;
                }
            }
            ranking[0] = fresh;
            previousSequence[0] = plant.seq;
            if (fresh < target) wire.remember(game);
        });
        var outcome = evaluate(start, game, fork.player.decisions);
        return Map.of("requestedDefenderPhase", phase, "launchMsOriginalHorizontalVerticalAppliedHorizontal", launch,
                "geometryAtServerAcceptance", geometry, "wireAtAcceptance", orderedAttacks, "outcome", outcome.values());
    }

    /** Shift only host opportunities after the cut and delay newly sent uplink packets. */
    private record FutureTiming(int defenderMs, double serverMs, double uplinkMs) {
        void apply(DuelEnv.Game game) {
            if (defenderMs != 0 || serverMs != 0) {
                var replacement = game.clock.futureHostOpportunities().stream().map(e -> {
                    double delay = e.kind().name().startsWith("SERVER_") ? serverMs
                            : e.fromA() != game.first ? defenderMs : 0;
                    return new PeriodicComboClock.Scheduled(e.nanos() + Math.round(delay * 1_000_000),
                            e.sequence(), e.kind(), e.fromA());
                }).toList();
                double coverage = replacement.stream().mapToLong(PeriodicComboClock.Scheduled::nanos)
                        .max().orElseThrow() / 1_000_000.0;
                game.clock.replaceFutureHostOpportunities(replacement, Math.max(game.clock.time(), coverage));
            }
            game.clock.state.extraLatency[game.first ? 0 : 1] += uplinkMs;
        }
    }

    private static Fork timingFork(DuelEnv env, Fork prefix, boolean network, int alternateHead, FutureTiming timing) {
        var game = prefix.game;
        var hand = new InputProbe(prefix.player, network, alternateHead);
        var next = env.new Game(game, game.first ? hand : game.a.copy(), game.first ? game.b.copy() : hand);
        next.trace();
        next.hits.list.addAll(game.hits.list);
        timing.apply(next);
        return new Fork(next, hand);
    }

    private static Map<String,Object> localView(Decision decision, double landmark) {
        var view = decision.view;
        return Map.of("fromLandmarkMs", view.time() - landmark, "targetHeight", view.targetY() - view.own().y(),
                "nearest", view.nearest(), "ground", view.own().onGround(), "sprinting", view.sprint(),
                "targetHurtAgeMs", view.time() - view.lastTargetHurtTime(),
                "horizontalSpeed", Math.hypot(view.own().vx(), view.own().vz()));
    }

    private static void decisionTiming(BoxingDrill.Checkpoint start, Arena.Neural primed, boolean network,
                                       int head, int laterHead) {
        var prefix = fork(start, primed, network, -1, 0);
        var game = prefix.game;
        if (!game.clock.advanceToBeforeNextHead(game.first, start.env.limit)) throw new IllegalStateException("No next head");
        for (int i = 0; i < head; i++) {
            if (!game.clock.runHeadInterval(game.first, start.env.limit)) throw new IllegalStateException("No requested head");
        }
        if (prefix.player.decisions.size() != head || game.hits.list.stream().anyMatch(h -> h.sideA() != game.first))
            throw new IllegalStateException("Decision must precede the first return");
        var original = probe(start, primed, network, -1, 0);
        var switched = probe(start, primed, network, head, 0);
        var decision = original.decisions.get(head);
        if (decision.wtap.key() == decision.network.key()) throw new IllegalStateException("Decision keys are equal");
        if (!prefix.player.decisions.equals(original.decisions.subList(0, head)))
            throw new AssertionError("Prefix did not reproduce the input history");
        var trials = new ArrayList<Object>();
        var histories = new HashMap<List<Decision>,Integer>();
        for (int defender : new int[]{0, 5, 10, 15, 20}) {
            for (double server : new double[]{0, 2.5, 5}) for (double uplink : new double[]{0, 1, 4}) {
                var timing = new FutureTiming(defender, server, uplink);
                var a = timingFork(start.env, prefix, network, -1, timing);
                var b = timingFork(start.env, prefix, network, head, timing);
                var stock = measureImpulse(start, a, 0, 1, false);
                var swap = measureImpulse(start, b, 0, 1, false);
                if (!decision.equals(a.player.decisions.get(head)) || !decision.equals(b.player.decisions.get(head)))
                    throw new AssertionError("Hidden timing leaked into the current observation");
                if (defender == 0 && server == 0 && uplink == 0
                        && (!original.values().equals(stock.get("outcome")) || !switched.values().equals(swap.get("outcome"))))
                    throw new AssertionError("Decision fork failed to reproduce the nominal continuations");
                var trial = new LinkedHashMap<String,Object>();
                trial.putAll(Map.of("defenderMs", defender, "serverMs", server, "uplinkMs", uplink, "stock", stock, "swap", swap));
                var control = network ? timingFork(start.env, prefix, false, -1, timing) : a;
                trial.put("wtapFromCut", network ? measureImpulse(start, control, 0, 1, false) : stock);
                if (!decision.equals(control.player.decisions.get(head)))
                    throw new AssertionError("W-tap did not share the decision prefix");
                if (laterHead > head) {
                    var later = timingFork(start.env, prefix, network, laterHead, timing);
                    var laterResult = measureImpulse(start, later, 0, 1, false);
                    var history = List.copyOf(later.player.decisions.subList(0, laterHead + 1));
                    if (!history.equals(a.player.decisions.subList(0, laterHead + 1)))
                        throw new AssertionError("Waiting changed the observation prefix");
                    int group = histories.computeIfAbsent(history, ignored -> histories.size());
                    trial.put("later", laterResult);
                    trial.put("laterHistoryGroup", group);
                    trial.put("laterBeforeInput", localView(history.get(laterHead), start.landmark));
                }
                trials.add(trial);
            }
        }
        System.out.println(Json.encode(Map.of("clock", start.env.clockId, "seat", game.first ? "a" : "b",
                "base", network ? "frozen" : "wtap", "head", head, "laterHead", laterHead,
                "beforeInput", localView(decision, start.landmark),
                "wtapKey", decision.wtap.key(), "frozenKey", decision.network.key(), "trials", trials)));
    }

    private static void envelope(BoxingDrill.Checkpoint start, Arena.Model model) {
        var primed = (Arena.Neural) start.suffix(model).our();
        var row = new LinkedHashMap<String,Object>();
        row.put("clock", start.env.clockId);
        row.put("seat", start.state.first ? "a" : "b");
        row.put("rtt", start.env.rtt);
        row.put("k", start.k);
        for (boolean frozen : new boolean[]{false, true}) {
            row.put(frozen ? "frozen" : "wtap", Map.of(
                    "natural", impulse(start, primed, frozen, -1, 0, 1, false),
                    "flipped", impulse(start, primed, frozen, -1, 0, 1, true)));
        }
        System.out.println(Json.encode(row));
    }

    /** Offline recognition check. The first matching head is identifiable from the own-client prefix. */
    private static int cueHead(Outcome base, double landmark) {
        for (int i = 0; i < base.decisions.size(); i++) {
            var decision = base.decisions.get(i);
            var view = decision.view;
            if (view.time() > landmark + base.exposure) break;
            double age = view.time() - view.lastTargetHurtTime();
            double nearest = view.nearest();
            if (decision.wtap.key() == PeriodicComboFightModel.S
                    && decision.network.key() == PeriodicComboFightModel.W
                    && view.own().onGround() && view.sprint()
                    && age >= 275 && age <= 380
                    && view.targetY() - view.own().y() >= .70
                    && nearest >= 2.50 && nearest <= 2.98) return i;
        }
        return -1;
    }

    private static void cueCheck(BoxingDrill.Checkpoint start, Arena.Model model, int phase) {
        var primed = (Arena.Neural) start.suffix(model).our();
        var row = new LinkedHashMap<String,Object>();
        row.put("clock", start.env.clockId);
        row.put("seat", start.state.first ? "a" : "b");
        row.put("rtt", start.env.rtt);
        row.put("landmark", start.k);
        row.put("requestedDefenderPhase", phase);
        for (boolean frozen : new boolean[]{false, true}) {
            var stock = probe(start, primed, frozen, -1, phase);
            int head = cueHead(stock, start.landmark);
            var swap = head < 0 ? stock : probe(start, primed, frozen, head, phase);
            if (head >= 0 && !swap.decisions.subList(0, head + 1).equals(stock.decisions.subList(0, head + 1)))
                throw new AssertionError("Recognition must use exactly the same input history");
            row.put(frozen ? "frozen" : "wtap", Map.of("head", head, "stock", stock.values(), "matchedCue", swap.values()));
        }
        System.out.println(Json.encode(row));
    }

    private static void study(BoxingDrill.Checkpoint start, Arena.Model frozen, int phase) {
        var reference = start.suffix(frozen);
        var primed = ((Arena.Neural) reference.our()).copy();
        var plain = start.suffix(null);
        shift(reference, phase);
        shift(plain, phase);
        var wtap = probe(start, primed, false, -1, phase);
        var neural = probe(start, primed, true, -1, phase);
        if (!wtap.hits.equals(evaluate(start, plain, List.of()).hits)
                || !neural.hits.equals(evaluate(start, reference, List.of()).hits))
            throw new AssertionError("Probe changed one of the baseline hit timelines");
        var sameState = new LinkedHashMap<String,Object>();
        sameState.put("clock", start.env.clockId);
        sameState.put("rtt", start.env.rtt);
        sameState.put("seat", start.state.first ? "a" : "b");
        sameState.put("k", start.k);
        sameState.put("anchorMs", start.landmark);
        sameState.put("defenderPhase", phase);
        sameState.put("wtap", wtap.values());
        sameState.put("frozen", neural.values());
        var trials = new ArrayList<Object>();
        for (boolean baseNetwork : new boolean[]{false, true}) {
            var base = baseNetwork ? neural : wtap;
            for (int i = 0; i < base.decisions.size(); i++) {
                var original = base.decisions.get(i);
                if (original.view.time() > start.landmark + base.exposure || original.wtap.key() == original.network.key()) continue;
                var switched = probe(start, primed, baseNetwork, i, phase);
                if (!switched.decisions.subList(0, i + 1).equals(base.decisions.subList(0, i + 1)))
                    throw new AssertionError("Intervention did not share the control prefix");
                trials.add(Map.of("base", baseNetwork ? "frozen" : "wtap", "head", i, "ms", original.view.time() - start.landmark,
                        "wtapKey", original.wtap.key(), "frozenKey", original.network.key(), "outcome", switched.values()));
            }
        }
        sameState.put("trials", trials);
        System.out.println(Json.encode(sameState));
    }

    public static void main(String[] args) throws Exception {
        var frozen = Arena.Model.decode(Files.readString(Path.of(args[0])).strip());
        int rtt = Integer.parseInt(args[1]);
        if (args[2].equals("--wire-study")) {
            var wires = Path.of(args[3]);
            var seeds = Path.of(args[4]);
            for (int i = 5; i < args.length; i++) {
                var env = BoxingEdgeHitSelect.environment(Path.of(args[i]), rtt, 20_000, wires, seeds);
                for (boolean first : new boolean[]{true, false}) {
                    for (var start : BoxingDrill.acquire(env, first, true)) {
                        for (int phase : new int[]{0, -5, 5}) study(start, frozen, phase);
                    }
                }
            }
            return;
        }
        int[] phases = args[2].equals("--phases") ? new int[]{0, -10, 10, 20} : new int[]{0};
        boolean envelope = args[2].equals("--envelope");
        boolean cue = args[2].equals("--cue");
        if (List.of("--trace", "--impulse", "--decision").contains(args[2])) {
            var env = BoxingDoubleSearch.environment(Path.of(args[3]), rtt, 20, 20_000);
            boolean seatA = args[4].equals("a");
            int k = Integer.parseInt(args[5]);
            var start = BoxingDrill.acquire(env, seatA, true).stream().filter(cp -> cp.k == k).findFirst().orElseThrow();
            var primed = (Arena.Neural) start.suffix(frozen).our();
            if (args[2].equals("--decision")) {
                int later = args.length > 8 ? Integer.parseInt(args[8]) : -1;
                decisionTiming(start, primed, args[6].equals("frozen"), Integer.parseInt(args[7]), later);
                return;
            }
            if (args[2].equals("--impulse")) {
                int hitNumber = Integer.parseInt(args[6]);
                var cases = new LinkedHashMap<String,Object>();
                for (int i = 7; i < args.length; i += 2) {
                    boolean baseNetwork = args[i].equals("frozen");
                    int head = Integer.parseInt(args[i + 1]);
                    var paired = new ArrayList<Object>();
                    for (int phase : new int[]{0, -10, 10, 20}) {
                        paired.add(Map.of("nativeImpulse", impulse(start, primed, baseNetwork, head, phase, hitNumber, false),
                                "flippedImpulse", impulse(start, primed, baseNetwork, head, phase, hitNumber, true)));
                    }
                    cases.put(args[i] + "/" + head, paired);
                }
                System.out.println(Json.encode(Map.of("clock", env.clockId, "seat", args[4], "k", k,
                        "hitAfterLandmark", hitNumber, "cases", cases)));
                return;
            }
            var ledgers = new LinkedHashMap<String,Object>();
            ledgers.put("wtap", new BoxingSuffixTrace.Ledger(start, null).run());
            ledgers.put("frozen", new BoxingSuffixTrace.Ledger(start, frozen).run());
            for (int i = 6; i < args.length; i += 2) {
                boolean baseNetwork = args[i].equals("frozen");
                int head = Integer.parseInt(args[i + 1]);
                var fork = fork(start, primed, baseNetwork, head, 0);
                ledgers.put(args[i] + "-" + head, new BoxingSuffixTrace.Ledger(fork.game, start.landmark).run());
            }
            System.out.println(Json.encode(Map.of("clock", env.clockId, "anchorMs", start.landmark, "ledgers", ledgers)));
            return;
        }
        for (int i = phases.length > 1 || envelope || cue ? 3 : 2; i < args.length; i++) {
            var env = BoxingDoubleSearch.environment(Path.of(args[i]), rtt, 20, 20_000);
            for (boolean first : new boolean[]{true, false}) {
                for (var start : BoxingDrill.acquire(env, first, !envelope)) {
                    if (envelope) envelope(start, frozen);
                    else if (cue) for (int phase : new int[]{0, 10, 20}) cueCheck(start, frozen, phase);
                    else for (int phase : phases) study(start, frozen, phase);
                }
            }
        }
    }
}
