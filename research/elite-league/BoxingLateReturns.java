import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lab.Json;

/** Diagnose the first return from exact established-combo forks. No deployable policy. */
final class BoxingLateReturns {
    private static final double HORIZON = 2200;
    private static final double ACTIVE_GAP = 1000;
    static final int HEADS = 3;
    private static final int SECOND_SEARCH_HEAD_LIMIT = 3;

    private record Step(String label, int key, float strafe) {}
    private static final List<Step> STEPS = List.of(
            new Step("keep", -1, 0),
            new Step("sprint", PeriodicComboFightModel.S, 0),
            new Step("walk", PeriodicComboFightModel.W, 0),
            new Step("release", PeriodicComboFightModel.R, 0),
            new Step("back", PeriodicComboFightModel.BACK, 0),
            new Step("sprint-left", PeriodicComboFightModel.S, .98f),
            new Step("sprint-right", PeriodicComboFightModel.S, -.98f),
            new Step("left", PeriodicComboFightModel.R, .98f),
            new Step("right", PeriodicComboFightModel.R, -.98f));
    static final int MOVES = STEPS.size();
    static final int WORDS = MOVES * MOVES * MOVES;

    static PeriodicComboClock.Control movement(PeriodicComboClock.Control control, int index) {
        var step = STEPS.get(index);
        return step.key < 0 ? control
                : new PeriodicComboClock.Control(step.key, step.strafe, control.click(), control.jump());
    }

    private static final class Footwork extends BoxingReentryRelease.Gate {
        int word, remaining = HEADS;

        Footwork(DuelEnv.Player prior, int word) {
            super(prior, observation -> true);
            this.word = word;
        }

        @Override public PeriodicComboClock.Control input(PeriodicComboClock.Observation own) {
            var control = super.input(own);
            if (remaining == 0) return control;
            remaining--;
            var next = BoxingLateReturns.movement(control, word % MOVES);
            word /= MOVES;
            return next;
        }
    }

    private record Phase(int own, int defender) {
        List<Integer> values() { return List.of(own, defender); }
    }

    record Score(int active, int returnOnly, double activeMs, double returnOnlyMs,
                         String activeEnd, String returnOnlyEnd, int margin, int freshMargin) {
        Map<String, Object> values() {
            return Map.of("active", active, "returnOnly", returnOnly, "activeMs", activeMs,
                    "returnOnlyMs", returnOnlyMs, "activeEnd", activeEnd, "returnOnlyEnd", returnOnlyEnd,
                    "margin", margin, "freshMargin", freshMargin);
        }
    }

    static Score score(DuelEnv.Game game, double landmark) {
        return score(game, landmark, ACTIVE_GAP);
    }

    static Score score(DuelEnv.Game game, double landmark, double activeGap) {
        double activeLast = landmark, terminalAt = game.clock.time();
        int active = 0, cycles = 0, margin = 0, freshMargin = 0;
        String terminal = "censored";
        for (var hit : game.hits.list) {
            boolean own = hit.sideA() == game.first;
            margin += own ? 1 : -1;
            if (!hit.upgrade()) freshMargin += own ? 1 : -1;
            if (!terminal.equals("censored")) continue;
            if (!own) {
                terminal = hit.upgrade() ? "return-upgrade" : "return-fresh";
                terminalAt = hit.at();
            } else if (!hit.upgrade()) {
                if (active == cycles && hit.at() - activeLast <= activeGap) {
                    active++;
                    activeLast = hit.at();
                }
                cycles++;
            }
        }
        double activeAt = Math.min(terminalAt, activeLast + activeGap);
        return new Score(active, cycles, activeAt - landmark, terminalAt - landmark,
                activeAt < terminalAt ? "inactive" : terminal, terminal, margin, freshMargin);
    }

    private static Score run(DuelEnv.Game game, double landmark, double end) {
        game.clock.runUntil(end);
        return score(game, landmark);
    }

    private static DuelEnv.Game shifted(BoxingDrill.Checkpoint start, Phase phase) {
        var game = start.suffix(null);
        if (phase.own != 0) game.clock.shiftFutureClientOpportunities(game.first, phase.own);
        if (phase.defender != 0) game.clock.shiftFutureClientOpportunities(!game.first, phase.defender);
        return game;
    }

    private static DuelEnv.Game fork(DuelEnv env, DuelEnv.Game prefix, int word) {
        var movement = new Footwork(prefix.our(), word);
        var game = env.new Game(prefix, prefix.first ? movement : prefix.a.copy(),
                prefix.first ? prefix.b.copy() : movement);
        game.trace();
        game.hits.list.addAll(prefix.hits.list);
        return game;
    }

    private static Map<String, Object> removeFirstReturn(BoxingDrill.Checkpoint start, Score baseline, double end) {
        if (baseline.returnOnlyEnd.equals("censored")) return Map.of("status", "no return in the horizon");
        var traced = start.suffix(null);
        double at = start.landmark + baseline.returnOnlyMs;
        if (traced.clock.time() >= at) return Map.of("status", "already accepted at the checkpoint");

        long[] packetId = {-1};
        traced.clock.setAudit((time, stage, state) -> {
            traced.hits.event(time, stage, state);
            if (time != at || !stage.equals("SERVER_DRAIN")) return;
            boolean fresh = baseline.returnOnlyEnd.equals("return-fresh");
            packetId[0] = traced.first ? (fresh ? state.lastFreshPacketB : state.lastAcceptedPacketB)
                    : (fresh ? state.lastFreshPacketA : state.lastAcceptedPacketA);
        });
        traced.clock.runUntil(at);
        if (packetId[0] < 0) throw new AssertionError("No packet for the first accepted return");
        var game = start.suffix(null);
        game.clock.runUntil(Math.nextDown(at));
        int attack = game.first ? 7 : 3;
        long id = packetId[0];
        var packets = game.clock.state.packets;
        int before = packets.size();
        packets.removeIf(p -> p.kind == attack && p.sequence == id && p.ready <= at);
        if (packets.size() != before - 1) throw new AssertionError("Return attack was absent from its drain");
        boolean queued = start.state.clock.state.packets.stream().anyMatch(p -> p.sequence == id);
        return Map.of("removedPacket", id, "queuedAtLandmark", queued,
                "score", run(game, start.landmark, end).values());
    }

    static List<String> names(int code) {
        var labels = new ArrayList<String>();
        for (int i = 0; i < HEADS; i++) {
            labels.add(STEPS.get(code % STEPS.size()).label);
            code /= STEPS.size();
        }
        return labels;
    }

    private record Word(int head, int code, Score score) {
        Map<String, Object> values(double time) {
            return Map.of("startHead", head, "startMs", time, "keys", names(code), "score", score.values());
        }
    }

    private static List<DuelEnv.Game> cuts(BoxingDrill.Checkpoint start, Phase phase, double end, int count) {
        var prefix = shifted(start, phase);
        var result = new ArrayList<DuelEnv.Game>();
        if (count == 0 || end < prefix.clock.time() || !prefix.clock.advanceToBeforeNextHead(prefix.first, end))
            return result;
        do {
            boolean first = prefix.first;
            var snapshot = start.env.new Game(prefix, prefix.a.copy(), prefix.b.copy());
            snapshot.hits.list.addAll(prefix.hits.list);
            result.add(snapshot);
        } while (result.size() < count && prefix.clock.runHeadInterval(prefix.first, end));
        return result;
    }

    private static Map<String, Object> trace(DuelEnv.Game game, double landmark, double end) {
        return trace(game, landmark, end, ACTIVE_GAP);
    }

    static Map<String, Object> trace(DuelEnv.Game game, double landmark, double end, double activeGap) {
        var clicks = new ArrayList<List<Object>>();
        var movement = new ArrayList<List<Object>>();
        var packets = new ArrayList<List<Object>>();
        var heads = new ArrayList<List<Object>>();
        String move = game.first ? "MOVEA" : "MOVEB";
        String head = game.first ? "POLICYA" : "POLICYB";
        int[] seen = {game.hits.list.size()};
        game.clock.setClickAudit(e -> {
            if (!e.action().equals("CONSUME")) return;
            var state = game.clock.state;
            var body = e.fromA() ? state.a : state.b;
            var view = e.fromA() ? state.ab : state.ba;
            clicks.add(List.of(e.time() - landmark, e.fromA() == game.first, e.eligible(), e.contact(),
                    PeriodicComboFightModel.nearest(body.q, view), body.q.y(), view.y));
        });
        game.clock.setAudit((time, stage, state) -> {
            game.hits.event(time, stage, state);
            for (; seen[0] < game.hits.list.size(); seen[0]++) {
                var h = game.hits.list.get(seen[0]);
                long packet = h.upgrade()
                        ? (h.sideA() ? state.lastAcceptedPacketA : state.lastAcceptedPacketB)
                        : (h.sideA() ? state.lastFreshPacketA : state.lastFreshPacketB);
                packets.add(List.of(time - landmark, h.sideA() == game.first, h.upgrade(), packet));
            }
            if (stage.equals(move)) {
                var body = game.first ? state.a : state.b;
                var view = game.first ? state.ab : state.ba;
                var input = game.clock.appliedControl(game.first);
                movement.add(List.of(time - landmark, input.key(), input.strafe(), body.sprint,
                        Math.hypot(body.q.vx(), body.q.vz()), PeriodicComboFightModel.nearest(body.q, view)));
            }
            if (stage.equals(head)) {
                var body = game.first ? state.a : state.b;
                var view = game.first ? state.ab : state.ba;
                heads.add(Arrays.asList(time - landmark, PeriodicComboFightModel.nearest(body.q, view),
                        relative(view.lastHurtTime, landmark), relative(body.lastContactTime, landmark),
                        view.y - body.q.y(), body.sprint, body.q.onGround()));
            }
        });
        game.clock.runUntil(end);
        var outcome = score(game, landmark, activeGap);
        return Map.of("clicks", clicks, "ownHeads", heads, "ownMovement", movement, "accepted", packets,
                "score", outcome.values());
    }

    private static Double relative(double time, double landmark) {
        return Double.isFinite(time) ? time - landmark : null;
    }

    private static Map<String, Object> examine(BoxingDrill.Checkpoint start) {
        return examine(start, Integer.MAX_VALUE);
    }

    private static Map<String, Object> examine(BoxingDrill.Checkpoint start, int maximumHeads) {
        return examine(start, maximumHeads, Double.POSITIVE_INFINITY);
    }

    static Map<String, Object> examine(BoxingDrill.Checkpoint start, int maximumHeads, double latestStart) {
        double landmark = start.landmark, end = Math.min(start.env.limit, landmark + HORIZON);
        int target = start.k == 1 ? 3 : 4;
        var phases = new ArrayList<Phase>();
        for (int own : new int[]{0, 5, -5}) {
            for (int defender : new int[]{0, 10, -10}) phases.add(new Phase(own, defender));
        }
        var phase0 = phases.get(0);
        var baseline = run(shifted(start, phase0), landmark, end);
        var row = new LinkedHashMap<String, Object>();
        row.put("clock", start.env.clockId);
        row.put("rtt", start.env.rtt);
        row.put("seat", start.state.first ? "a" : "b");
        row.put("landmarkHits", start.k);
        row.put("targetHits", target);
        row.put("landmark", landmark);
        row.put("horizonMs", end - landmark);
        row.put("wtap", baseline.values());
        row.put("removeFirstReturnPacket", removeFirstReturn(start, baseline, end));
        var nominalCuts = cuts(start, phase0,
                Math.nextDown(Math.min(latestStart, landmark + baseline.returnOnlyMs)), maximumHeads);
        if (nominalCuts.isEmpty()) return row;

        var standings = Comparator.comparingInt((Word w) -> w.score.active)
                .thenComparingInt(w -> w.score.margin).thenComparingDouble(w -> w.score.returnOnlyMs);
        var best = new Word(0, 0, baseline);
        var improving = new ArrayList<Word>();
        var scoreImproving = new ArrayList<Word>();
        for (int head = 0; head < nominalCuts.size(); head++) {
            var cut = nominalCuts.get(head);
            if (!run(fork(start.env, cut, 0), landmark, end).equals(baseline))
                throw new AssertionError("No-op does not reproduce matched W-tap");
            for (int code = 1; code < WORDS; code++) {
                var result = run(fork(start.env, cut, code), landmark, end);
                var word = new Word(head, code, result);
                if (standings.compare(word, best) > 0) best = word;
                if (result.margin > baseline.margin) scoreImproving.add(word);
                if (madeProgress(target - start.k, result, baseline)) improving.add(word);
            }
        }
        var controls = new ArrayList<Map<String, Object>>();
        var activeAll = new ArrayList<Map<String, Object>>();
        int activeNoRegress = 0;
        var scoreAll = new ArrayList<Map<String, Object>>();
        var tested = new ArrayList<Word>(improving);
        for (var word : scoreImproving) {
            if (!madeProgress(target - start.k, word.score, baseline)) tested.add(word);
        }
        if (!tested.contains(best)) tested.add(best);
        var results = new LinkedHashMap<Word, List<Map<String, Object>>>();
        for (var word : tested) results.put(word, new ArrayList<>());
        for (var phase : phases) {
            var control = run(shifted(start, phase), landmark, end);
            controls.add(Map.of("phase", phase.values(), "wtap", control.values()));
            var starts = phase.equals(phase0) ? nominalCuts : cuts(start, phase, end, nominalCuts.size());
            for (var word : tested) {
                var cut = starts.get(word.head);
                var outcome = phase.equals(phase0) ? word.score : run(fork(start.env, cut, word.code), landmark, end);
                results.get(word).add(Map.of("phase", phase.values(), "outcome", outcome.values(),
                        "deltaActive", outcome.active - control.active, "deltaMargin", outcome.margin - control.margin,
                        "reachesFourth", outcome.active >= 4 - start.k,
                        "reachesTarget", outcome.active >= target - start.k));
            }
        }
        for (var word : improving) {
            var scores = results.get(word);
            int worst = scores.stream().mapToInt(r -> (int) r.get("deltaActive")).min().orElseThrow();
            var record = Map.of("word", word.values(nominalCuts.get(word.head).clock.time() - landmark),
                    "variants", scores);
            if (worst >= 0) activeNoRegress++;
            if (worst > 0 && scores.stream().allMatch(v -> (boolean) v.get("reachesTarget"))) activeAll.add(record);
        }
        for (var word : scoreImproving) {
            var scores = results.get(word);
            if (scores.stream().allMatch(v -> (int) v.get("deltaMargin") > 0)) {
                scoreAll.add(Map.of("word", word.values(nominalCuts.get(word.head).clock.time() - landmark),
                        "variants", scores));
            }
        }
        row.put("testedHeads", nominalCuts.size());
        row.put("testedWords", nominalCuts.size() * (WORDS - 1));
        row.put("nominalMarginGains", scoreImproving.size());
        row.put("nominalActiveGains", improving.size());
        row.put("wtapPhases", controls);
        row.put("bestWord", best.values(nominalCuts.get(best.head).clock.time() - landmark));
        row.put("bestWordPhases", results.get(best));
        row.put("robustActiveGains", activeAll);
        row.put("activeGainsWithoutRegression", activeNoRegress);
        row.put("robustMarginGains", scoreAll);
        var robust = improving.stream().filter(w -> results.get(w).stream().allMatch(v ->
                (int) v.get("deltaActive") > 0 && (boolean) v.get("reachesTarget"))).toList();
        var byMargin = Comparator.comparingInt((Word w) -> results.get(w).stream()
                .mapToInt(v -> (int) v.get("deltaMargin")).min().orElseThrow())
                .thenComparingInt(w -> results.get(w).stream().mapToInt(v -> (int) v.get("deltaMargin")).sum());
        var witness = start.k <= 2 ? robust.stream().max(byMargin).orElse(best)
                : robust.stream().findFirst().orElse(best);
        row.put("trace", Map.of("wtap", trace(shifted(start, phase0), landmark, end),
                "word", witness.values(nominalCuts.get(witness.head).clock.time() - landmark),
                "movement", trace(fork(start.env, nominalCuts.get(witness.head), witness.code), landmark, end)));
        return row;
    }

    private static boolean madeProgress(int requiredCycles, Score result, Score baseline) {
        return result.active > baseline.active && result.active >= requiredCycles;
    }

    public static void main(String[] args) throws Exception {
        int rtt = Integer.parseInt(args[0]);
        var wires = Path.of(args[1]);
        var seeds = Path.of(args[2]);
        var second = System.getProperty("lab.boxing.secondCutMs");
        var seat = System.getProperty("lab.boxing.secondCutSeat");
        boolean secondEnvelope = Boolean.getBoolean("lab.boxing.sweepSecondHits");
        if (second != null && (args.length != 4 || !("a".equals(seat) || "b".equals(seat))))
            throw new IllegalArgumentException("An exact K2 audit requires one clock and seat");
        for (int i = 3; i < args.length; i++) {
            var env = BoxingDoubleSearch.environment(Path.of(args[i]), rtt, 20, 20_000);
            BoxingJumpTimingAudit.wireTiming(env, wires.resolve(env.clockId + ".txt"));
            BoxingJumpTimingAudit.seedTracker(env, seeds.resolve(env.clockId + ".txt"));
            for (boolean first : new boolean[]{true, false}) {
                if (secondEnvelope) {
                    for (var stamp : BoxingDrill.secondHits(env, first)) {
                        if (stamp.landmark + HORIZON > env.limit) continue;
                        System.out.println(Json.encode(examine(
                                BoxingDrill.exactSecondHit(env, first, stamp.landmark), SECOND_SEARCH_HEAD_LIMIT)));
                    }
                    continue;
                }
                if (second != null && first != seat.equals("a")) continue;
                for (var start : BoxingDrill.acquire(env, first, false)) {
                    var cut = second == null ? start : BoxingDrill.exactSecondHit(env, first, Double.parseDouble(second));
                    System.out.println(Json.encode(examine(cut)));
                }
            }
        }
    }
}
