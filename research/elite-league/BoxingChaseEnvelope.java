import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import lab.Json;

/** Paired model diagnostic for ordinary footwork after the first established follow-up. */
final class BoxingChaseEnvelope {
    static final double HORIZON_MS = 4500;
    private static final int START_HEADS = 6;
    private static final boolean BEFORE_LAUNCH = Boolean.getBoolean("lab.boxing.beforeFollowup");
    private static final boolean START_ON_CUE = Boolean.getBoolean("lab.boxing.chaseCueCut");
    private static final String CUE_WORD = System.getProperty("lab.boxing.chaseCueWord");
    private static final double OWN_ATTACK_SHIFT_MS = Double.parseDouble(System.getProperty("lab.boxing.chaseOwnAttackMs", "0"));
    private static final double DEFENDER_ATTACK_SHIFT_MS = Double.parseDouble(System.getProperty("lab.boxing.chaseDefenderAttackMs", "0"));
    static final List<Phase> PHASES = phases();

    private static List<Phase> phases() {
        var result = new ArrayList<Phase>();
        int[] offsets = Boolean.getBoolean("lab.boxing.chaseWideTiming")
                ? new int[]{0, -5, 5, -10, 10} : new int[]{0, -5, 5};
        for (int own : offsets) for (int defender : offsets) result.add(new Phase(own, defender));
        return List.copyOf(result);
    }

    record Phase(int own, int defender) {
        List<Integer> values() { return List.of(own, defender); }
    }

    static final class Movement extends DuelEnv.Player {
        final DuelEnv.Player base;
        int word;
        int remaining = BoxingLateReturns.HEADS;

        Movement(DuelEnv.Player original, int word) {
            super(original.style, original.mask, original.rtt, original.wake);
            base = original.copy();
            firstClick = base.firstClick;
            this.word = word;
        }

        @Override public PeriodicComboClock.Control input(PeriodicComboClock.Observation own) {
            var normal = base.input(own);
            if (remaining == 0) return normal;
            var applied = BoxingLateReturns.movement(normal, word % BoxingLateReturns.MOVES);
            word /= BoxingLateReturns.MOVES;
            remaining--;
            if (base instanceof Arena.Neural neural) {
                neural.net.applied(lab.DuelNetwork.pack(applied.key(), (int) Math.signum(applied.strafe()),
                        applied.click(), applied.jump()));
            }
            return applied;
        }

        @Override public void received(PeriodicComboClock.PacketObservation packet) { base.received(packet); }

        @Override public boolean injected(PeriodicComboClock.Observation own, double at) {
            super.injected(own, at);
            return base.injected(own, at);
        }
    }

    record Window(double launch, int launchTick, double separation, List<Double> impulse) {}

    record Evidence(BoxingLateReturns.Score score, Window first, Double readyAt,
                            Double readyOwnDistance, Double readyDefenderDistance,
                            Double second, Integer secondDrainOffset) {
        Map<String, Object> values(double landmark) {
            var out = new LinkedHashMap<String, Object>(score.values());
            if (first == null) return out;
            out.put("firstFollowupMs", first.launch - landmark);
            out.put("launchSeparation", first.separation);
            out.put("impulse", first.impulse);
            out.put("freshWindowMs", readyAt == null ? null : readyAt - first.launch);
            out.put("ownBoxDistanceOnWindow", readyOwnDistance);
            out.put("defenderBoxDistanceOnWindow", readyDefenderDistance);
            out.put("secondFollowupMs", second == null ? null : second - first.launch);
            out.put("secondDrainOffset", secondDrainOffset);
            return out;
        }
    }

    static Evidence trace(DuelEnv.Game game, double landmark, double end, Window preceding) {
        return trace(game, landmark, end, preceding, (hit, state) -> {});
    }

    static Evidence trace(DuelEnv.Game game, double landmark, double end, Window preceding,
                          BiConsumer<DuelEnv.Hit, PeriodicComboFightModel.State> accepted) {
        Window[] window = {preceding};
        var readings = new Readings();
        boolean[] returned = {false};
        for (var hit : game.hits.list) {
            if (hit.sideA() != game.first) returned[0] = true;
        }
        game.clock.setAudit((time, stage, state) -> {
            int seen = game.hits.list.size();
            game.hits.event(time, stage, state);
            if (!stage.equals("SERVER_DRAIN")) return;
            var first = window[0];
            if (first != null && !returned[0] && state.serverTicks == first.launchTick + game.clock.config.hitTicks) {
                readings.readyAt = time;
                readings.readyOwnDistance = game.first ? PeriodicComboFightModel.nearest(state.a.q, state.ab)
                        : PeriodicComboFightModel.nearest(state.b.q, state.ba);
                readings.readyDefenderDistance = game.first ? PeriodicComboFightModel.nearest(state.b.q, state.ba)
                        : PeriodicComboFightModel.nearest(state.a.q, state.ab);
            }
            for (; seen < game.hits.list.size() && !returned[0]; seen++) {
                var hit = game.hits.list.get(seen);
                accepted.accept(hit, state);
                if (hit.sideA() != game.first) {
                    returned[0] = true;
                } else if (!hit.upgrade() && window[0] == null) {
                    var impulse = game.first ? state.lastFreshB : state.lastFreshA;
                    if (impulse == null || impulse.time() != time) throw new AssertionError("Missing fresh impulse");
                    window[0] = new Window(time, state.serverTicks,
                            Math.hypot(state.serverBx - state.serverAx, state.serverBz - state.serverAz),
                            List.of(impulse.x(), impulse.y(), impulse.z()));
                } else if (!hit.upgrade() && readings.second == null) {
                    readings.second = time;
                    readings.secondDrainOffset = state.serverTicks - window[0].launchTick - game.clock.config.hitTicks;
                }
            }
        });
        game.clock.runUntil(end);
        double activeGap = 100.0 * game.clock.config.hitTicks + 100;
        return new Evidence(BoxingLateReturns.score(game, landmark, activeGap), window[0], readings.readyAt,
                readings.readyOwnDistance, readings.readyDefenderDistance, readings.second, readings.secondDrainOffset);
    }

    private static final class Readings {
        Double readyAt, readyOwnDistance, readyDefenderDistance, second;
        Integer secondDrainOffset;
    }

    static DuelEnv.Game start(BoxingDrill.Checkpoint checkpoint, Arena.Model model, Phase phase) {
        var game = checkpoint.suffix(model);
        if (phase.own != 0) game.clock.shiftFutureClientOpportunities(game.first, phase.own);
        if (phase.defender != 0) game.clock.shiftFutureClientOpportunities(!game.first, phase.defender);
        if (OWN_ATTACK_SHIFT_MS != 0 || DEFENDER_ATTACK_SHIFT_MS != 0) {
            BoxingJumpTimingAudit.shiftAttackTiming(game.clock.state,
                    game.first ? OWN_ATTACK_SHIFT_MS : DEFENDER_ATTACK_SHIFT_MS,
                    game.first ? DEFENDER_ATTACK_SHIFT_MS : OWN_ATTACK_SHIFT_MS);
        }
        return game;
    }

    static DuelEnv.Game branch(DuelEnv env, DuelEnv.Game prefix, DuelEnv.Player subject) {
        var game = env.new Game(prefix, prefix.first ? subject : prefix.a.copy(),
                prefix.first ? prefix.b.copy() : subject);
        game.trace();
        game.hits.list.addAll(prefix.hits.list);
        return game;
    }

    static boolean advanceToLocalCue(DuelEnv.Game game, double landmark, double deadline) {
        if (deadline < game.clock.time() || !game.clock.advanceToBeforeNextHead(game.first, deadline)) return false;
        do {
            var target = game.first ? game.clock.state.ab : game.clock.state.ba;
            if (target.lastHurtTime >= landmark) return true;
        } while (game.clock.runHeadInterval(game.first, deadline));
        return false;
    }

    static int movementWord(List<String> keys) {
        for (int word = 0; word < BoxingLateReturns.WORDS; word++) {
            if (BoxingLateReturns.names(word).equals(keys)) return word;
        }
        throw new IllegalArgumentException("Invalid motion " + keys);
    }

    private static List<DuelEnv.Game> cuts(BoxingDrill.Checkpoint checkpoint, Arena.Model model,
                                          Phase phase, Evidence baseline, double end) {
        var game = start(checkpoint, model, phase);
        if (START_ON_CUE) {
            double last = baseline.first == null ? end : baseline.first.launch;
            double deadline = Math.nextDown(Math.min(last, checkpoint.landmark + baseline.score.returnOnlyMs()));
            if (!advanceToLocalCue(game, checkpoint.landmark, deadline)) return List.of();
            return List.of(branch(checkpoint.env, game, game.our().copy()));
        }
        if (baseline.first == null) return List.of();
        double accepted = baseline.first.launch;
        double deadline = Math.nextDown(Math.min(BEFORE_LAUNCH ? accepted : end,
                checkpoint.landmark + baseline.score.returnOnlyMs()));
        if (deadline < game.clock.time()) return List.of();
        if (!BEFORE_LAUNCH) {
            if (deadline < accepted) return List.of();
            game.clock.runUntil(accepted);
        }
        if (!game.clock.advanceToBeforeNextHead(game.first, deadline)) return List.of();
        var result = new ArrayList<DuelEnv.Game>();
        do {
            result.add(branch(checkpoint.env, game, game.our().copy()));
        } while (result.size() < START_HEADS && game.clock.runHeadInterval(game.first, deadline));
        return result;
    }

    private static boolean beats(Evidence result, Evidence wtap, Evidence stock) {
        return result.score.active() > Math.max(wtap.score.active(), stock.score.active())
                && result.score.margin() >= Math.max(wtap.score.margin(), stock.score.margin());
    }

    private static List<Map<String, Object>> ownViews(List<DuelEnv.Game> cuts, double landmark) {
        var views = new ArrayList<Map<String, Object>>();
        int sinceCue = -1;
        for (var game : cuts) {
            var state = game.clock.state;
            var body = game.first ? state.a : state.b;
            var target = game.first ? state.ab : state.ba;
            if (target.lastHurtTime >= landmark) sinceCue++;
            var view = new LinkedHashMap<String, Object>();
            view.put("inputMs", game.clock.time() - landmark);
            view.put("targetHurtCueMs", Double.isFinite(target.lastHurtTime) ? target.lastHurtTime - landmark : null);
            view.put("headsSinceCue", sinceCue < 0 ? null : sinceCue);
            view.put("ownBoxDistance", PeriodicComboFightModel.nearest(body.q, target));
            view.put("height", target.y - body.q.y());
            view.put("ownGrounded", body.q.onGround());
            views.add(view);
        }
        return views;
    }

    private record Trial(int head, int word, Evidence result) {}

    private static List<Map<String, Object>> localCue(BoxingDrill.Checkpoint checkpoint, Arena.Model model,
                                                       List<Evidence> base, double end) {
        int word = movementWord(List.of(CUE_WORD.split(",")));
        var outcomes = new ArrayList<Map<String, Object>>();
        for (int index = 0; index < PHASES.size(); index++) {
            var game = start(checkpoint, model, PHASES.get(index));
            var initial = base.get(index);
            double landmark = checkpoint.landmark;
            double deadline = Math.nextDown(Math.min(end, landmark + initial.score.returnOnlyMs()));
            if (advanceToLocalCue(game, landmark, deadline)) {
                var fork = branch(checkpoint.env, game, new Movement(game.our(), word));
                Window first = initial.first != null && initial.first.launch <= game.clock.time() ? initial.first : null;
                var revised = trace(fork, landmark, end, first);
                outcomes.add(Map.of("cue", ownViews(List.of(game), landmark).get(0),
                        "result", revised.values(landmark)));
            } else {
                outcomes.add(Map.of("result", initial.values(landmark)));
            }
        }
        return outcomes;
    }

    private static Map<String, Object> search(BoxingDrill.Checkpoint checkpoint, Arena.Model model,
                                               List<Evidence> wtap, List<Evidence> frozen, double end) {
        double landmark = checkpoint.landmark;
        var base = model == null ? wtap : frozen;
        var cuts = cuts(checkpoint, model, PHASES.get(0), base.get(0), end);
        var preceding = BEFORE_LAUNCH || START_ON_CUE ? null : base.get(0).first;
        var candidates = new ArrayList<Trial>();
        int earlier = 0, moreThanBase = 0;
        for (int head = 0; head < cuts.size(); head++) {
            var cut = cuts.get(head);
            for (int word = 0; word < BoxingLateReturns.WORDS; word++) {
                var game = branch(checkpoint.env, cut, new Movement(cut.our(), word));
                var result = trace(game, landmark, end, preceding);
                if (word == 0 && !result.equals(base.get(0)))
                    throw new AssertionError("No-op movement fork changed baseline: " + result + " vs " + base.get(0));
                if (word == 0) continue;
                var originalSecond = base.get(0).secondDrainOffset;
                if (result.secondDrainOffset != null
                        && (originalSecond == null || result.secondDrainOffset < originalSecond)) earlier++;
                if (result.score.active() > base.get(0).score.active()) moreThanBase++;
                if (beats(result, wtap.get(0), frozen.get(0))) candidates.add(new Trial(head, word, result));
            }
        }
        var active = new ArrayList<>(candidates);
        var views = new ArrayList<List<Map<String, Object>>>();
        views.add(ownViews(cuts, landmark));
        var results = new LinkedHashMap<Trial, List<Map<String, Object>>>();
        for (var candidate : candidates)
            results.put(candidate, new ArrayList<>(List.of(candidate.result.values(landmark))));
        for (int i = 1; i < PHASES.size() && !active.isEmpty(); i++) {
            var baseline = base.get(i);
            var phaseCuts = cuts(checkpoint, model, PHASES.get(i), baseline, end);
            views.add(ownViews(phaseCuts, landmark));
            var iterator = active.iterator();
            while (iterator.hasNext()) {
                var trial = iterator.next();
                if (trial.head >= phaseCuts.size()) {
                    iterator.remove();
                    continue;
                }
                var cut = phaseCuts.get(trial.head);
                var game = branch(checkpoint.env, cut, new Movement(cut.our(), trial.word));
                var result = trace(game, landmark, end, BEFORE_LAUNCH || START_ON_CUE ? null : baseline.first);
                if (beats(result, wtap.get(i), frozen.get(i))) results.get(trial).add(result.values(landmark));
                else iterator.remove();
            }
        }
        var winners = active.stream().map(t -> Map.of(
                "head", t.head, "nominalStartMs", cuts.get(t.head).clock.time() - landmark,
                "keys", BoxingLateReturns.names(t.word), "phases", results.get(t))).toList();
        return Map.of("tested", cuts.size() * (BoxingLateReturns.WORDS - 1), "earlierNextHitNominal", earlier,
                "cycleGainOverOwnBaseNominal", moreThanBase, "cycleAndScoreGainAgainstBothNominal", candidates.size(),
                "robustWords", winners, "ownViews", views);
    }

    private static void examine(BoxingDrill.Checkpoint checkpoint, Arena.Model model) {
        var env = checkpoint.env;
        double landmark = checkpoint.landmark;
        double end = Math.min(env.limit, landmark + HORIZON_MS);
        var wtap = PHASES.stream().map(p -> trace(start(checkpoint, null, p), landmark, end, null)).toList();
        var frozen = PHASES.stream().map(p -> trace(start(checkpoint, model, p), landmark, end, null)).toList();
        var analysis = CUE_WORD != null
                ? Map.of("cueWord", CUE_WORD, "fromWtap", localCue(checkpoint, null, wtap, end),
                        "fromFrozen", localCue(checkpoint, model, frozen, end))
                : Map.of("fromWtap", search(checkpoint, null, wtap, frozen, end),
                        "fromFrozen", search(checkpoint, model, wtap, frozen, end));
        System.out.println(Json.encode(Map.of("clock", env.clockId, "profile", env.profile,
                "seat", checkpoint.state.first ? "a" : "b", "rtt", env.rtt, "landmark", landmark, "end", end,
                "phases", PHASES.stream().map(Phase::values).toList(),
                "wtap", wtap.stream().map(e -> e.values(landmark)).toList(),
                "frozen", frozen.stream().map(e -> e.values(landmark)).toList(), "search", analysis)));
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
                for (var checkpoint : starts) examine(checkpoint, model);
            }
        }
    }
}
