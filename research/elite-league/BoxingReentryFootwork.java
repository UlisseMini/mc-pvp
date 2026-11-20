import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lab.ExactTravel;

/** Fork movement at a recoil landmark or a local cue. Only normal movement keys vary. */
final class BoxingReentryFootwork {
    private record Step(String label, int key, float strafe) {}

    private static final List<Step> STEPS = List.of(
            new Step("keep", -1, 0),
            new Step("sprint", PeriodicComboFightModel.S, 0),
            new Step("walk", PeriodicComboFightModel.W, 0),
            new Step("release", PeriodicComboFightModel.R, 0),
            new Step("back", PeriodicComboFightModel.BACK, 0),
            new Step("left", PeriodicComboFightModel.S, .98f),
            new Step("right", PeriodicComboFightModel.S, -.98f));
    private static final int HEADS = 3;
    private static final int WORDS = STEPS.size() * STEPS.size() * STEPS.size();
    private static final boolean FORCE_BRAKE = Boolean.getBoolean("lab.boxing.reentryForceBrake");
    private static final boolean INCLUDE_FULL = Boolean.getBoolean("lab.boxing.reentryIncludeFull");
    private static final int CHECK_WORD = Integer.getInteger("lab.boxing.reentryCheckWord", -1);
    private static final int TRACE_WORD = Integer.getInteger("lab.boxing.reentryFootworkTrace", -1);
    private static final boolean TRACE_BRAKE = Boolean.getBoolean("lab.boxing.reentryTraceBrake");
    private static final String LOCAL_CUE = System.getProperty("lab.boxing.reentryFootworkCue");

    static class Movement extends BoxingReentryRelease.Gate {
        int word, remaining = HEADS;

        Movement(BoxingReentryRelease.Gate source, int word) {
            super(source);
            this.word = word;
        }

        @Override public PeriodicComboClock.Control input(PeriodicComboClock.Observation own) {
            var normal = super.input(own);
            if (remaining == 0) return normal;
            remaining--;
            var step = STEPS.get(word % STEPS.size());
            word /= STEPS.size();
            return step.key < 0 ? normal : new PeriodicComboClock.Control(step.key, step.strafe, normal.click(), normal.jump());
        }
    }

    private static final class Approach extends Movement {
        final double cut;
        Double cueNearest, cueTime;

        Approach(DuelEnv.Player source, double cut, BoxingReentryRelease.Delay delay, int word) {
            super(new BoxingReentryRelease.Gate(source,
                    own -> delay.permits(cut, own.time(), own.lastOwnHurtTime(), own.own().y())), word);
            this.cut = cut;
        }

        @Override public boolean allowInjection(PeriodicComboClock.Observation own, String source) {
            if (cueTime == null && own.lastOwnHurtTime() >= cut) {
                cueTime = own.time() - cut;
                cueNearest = own.nearest();
            }
            return super.allowInjection(own, source);
        }
    }

    private static Map<String, Object> closeApproach(DuelEnv env, DuelEnv.Game prefix, double cut, double end,
                                                     BoxingReentryRelease.Delay delay) {
        int radix = STEPS.size();
        var words = new ArrayList<Integer>();
        for (int word = 0; word < radix * radix; word++) words.add(word);
        for (int step = 1; step < radix; step++) words.add(step * (1 + radix + radix * radix));
        if (CHECK_WORD >= 0) {
            if (CHECK_WORD == 0 || !words.contains(CHECK_WORD)) {
                throw new IllegalArgumentException("Unknown close-approach word " + CHECK_WORD);
            }
            words.retainAll(List.of(0, CHECK_WORD));
        }
        var arms = new ArrayList<List<Object>>();
        for (int word : words) {
            var subject = new Approach(prefix.our(), cut, delay, word);
            var branch = fork(env, prefix, subject, opponent(prefix).copy());
            branch.clock.runUntil(end);
            var reply = BoxingOpeningRecoil.score(branch.hits.list, prefix.first);
            int fresh = branch.hits.list.stream().filter(h -> !h.upgrade())
                    .mapToInt(h -> h.sideA() == prefix.first ? 1 : -1).sum();
            var own = BoxingReentryTiming.openingChain(branch.hits.list, prefix.first, 850);
            var defender = BoxingReentryTiming.openingChain(branch.hits.list, !prefix.first, 850);
            double activeGap = 100.0 * branch.clock.config.hitTicks + 100;
            var active = BoxingReentryTiming.openingChain(branch.hits.list, prefix.first, activeGap);
            arms.add(Arrays.asList(reply.score(), fresh, own, defender, active, reply.incoming(), reply.counter(),
                    subject.cueNearest, subject.cueTime));
        }
        var output = new LinkedHashMap<String, Object>(
                Map.of("words", words, "keys", STEPS.stream().map(Step::label).toList(), "outcomes", arms));
        if (TRACE_WORD >= 0) {
            if (!words.contains(TRACE_WORD)) throw new IllegalArgumentException("Unmeasured close-approach trace word");
            var checkpoint = begin(env, prefix, cut, delay);
            output.put("traces", List.of(trace(env, checkpoint, cut, end, 0),
                    trace(env, checkpoint, cut, end, TRACE_WORD)));
        }
        return output;
    }

    private static DuelEnv.Player opponent(DuelEnv.Game prefix) { return prefix.first ? prefix.b : prefix.a; }

    private static DuelEnv.Game fork(DuelEnv env, DuelEnv.Game prefix, DuelEnv.Player selector, DuelEnv.Player defender) {
        var game = env.new Game(prefix, prefix.first ? selector : defender, prefix.first ? defender : selector);
        game.trace();
        return game;
    }

    private static DuelEnv.Game begin(DuelEnv env, DuelEnv.Game prefix, double cut, BoxingReentryRelease.Delay delay) {
        var source = opponent(prefix);
        var defender = new BoxingOpeningRecoil.Defender(source.rtt);
        defender.firstClick = source.firstClick;
        defender.handled = source.handled;
        defender.releaseUntil = source.releaseUntil;
        var selector = new BoxingReentryRelease.Gate(prefix.our(),
                own -> delay.permits(cut, own.time(), own.lastOwnHurtTime(), own.own().y()));
        return fork(env, prefix, selector, defender);
    }

    private static List<DuelEnv.Hit> suffix(DuelEnv env, DuelEnv.Game prefix, double end, int word) {
        var selector = new Movement((BoxingReentryRelease.Gate) prefix.our(), word);
        var game = fork(env, prefix, selector, opponent(prefix).copy());
        game.clock.runUntil(end);
        var hits = new ArrayList<>(prefix.hits.list);
        hits.addAll(game.hits.list);
        return hits;
    }

    private static Map<String, Object> excluded(String status) { return Map.of("status", status); }

    private static void brake(PeriodicComboFightModel.Body body) {
        var q = body.q;
        body.q = new ExactTravel.State(q.x(), q.y(), q.z(), q.vx() * .6, q.vy(), q.vz() * .6,
                q.box(), q.onGround(), q.isCollidedHorizontally(), q.isCollidedVertically(), q.isCollided(),
                q.noClip(), q.isInWeb(), q.sneaking());
    }

    private static List<Integer> score(List<DuelEnv.Hit> hits, boolean first) {
        var reply = BoxingOpeningRecoil.score(hits, first);
        return List.of(reply.score(), reply.followups(), BoxingReentryTiming.openingChain(hits, !first));
    }

    private static Map<String, Object> trace(DuelEnv env, DuelEnv.Game checkpoint, double cut, double end, int word) {
        var selector = new Movement((BoxingReentryRelease.Gate) checkpoint.our(), word);
        var game = fork(env, checkpoint, selector, opponent(checkpoint).copy());
        var defender = (BoxingOpeningRecoil.Defender) opponent(game);
        boolean freshReceipt = defender.recoil == null;
        double[] move = {Double.NaN};
        String defenderMove = game.first ? "MOVEB" : "MOVEA";
        var exchange = new BoxingReentryRelease.Exchange(game, cut, (t, stage, s) -> {
            if (freshReceipt && stage.equals(defenderMove) && defender.recoil != null && Double.isNaN(move[0])) move[0] = t;
        });
        game.clock.setClickAudit(event -> exchange.click(game, cut, event));
        game.clock.runUntil(end);
        var output = new LinkedHashMap<String, Object>(
                Map.of("word", word, "events", exchange.events, "ownHeads", exchange.ownHeads));
        if (!exchange.drains.isEmpty()) {
            output.put("dispatch", exchange.dispatch);
            output.put("serverDrains", exchange.drains);
        }
        var boundary = exchange.boundaryValues();
        if (!boundary.isEmpty()) {
            output.put("boundary", boundary);
            output.put("flight", exchange.flight.values(game.first));
        }
        if (Double.isFinite(move[0]) && defender.recoil.at() < move[0]) {
            var check = fork(env, checkpoint, new Movement((BoxingReentryRelease.Gate) checkpoint.our(), word),
                    opponent(checkpoint).copy());
            check.clock.runUntil(Math.nextDown(move[0]));
            var q = BoxingOpeningRecoil.defender(check).q;
            var impulse = defender.recoil;
            output.put("recoil", List.of(impulse.at() - cut, move[0] - cut,
                    Math.hypot(impulse.vx(), impulse.vz()), Math.hypot(q.vx(), q.vz()), impulse.vy(), q.vy()));
            var reply = BoxingOpeningRecoil.score(game.hits.list, game.first);
            if (TRACE_BRAKE && reply.timely() && impulse.at() >= reply.counter()
                    && q.vx() == impulse.vx() && q.vz() == impulse.vz() && q.vy() == impulse.vy()) {
                brake(BoxingOpeningRecoil.defender(check));
                check.clock.runUntil(end);
                output.put("forcedBrakeScore", approachScore(check.hits.list, game.first));
            }
        }
        return output;
    }

    private static boolean advanceToLocalCue(DuelEnv.Game game, double cut, double end) {
        while (game.clock.advanceToBeforeNextHead(game.first, end)) {
            var body = game.first ? game.clock.state.a : game.clock.state.b;
            boolean heardHurt = body.lastHurtTime >= cut;
            boolean ready = switch (LOCAL_CUE) {
                case "hurt" -> heardHurt;
                case "contact" -> heardHurt && body.lastContactTime >= body.lastHurtTime;
                default -> throw new IllegalArgumentException("Unknown movement cue " + LOCAL_CUE);
            };
            if (ready) return true;
            if (!game.clock.runHeadInterval(game.first, end)) break;
        }
        return false;
    }

    private static List<Object> approachScore(List<DuelEnv.Hit> hits, boolean first) {
        var reply = BoxingOpeningRecoil.score(hits, first);
        int freshMargin = hits.stream().filter(hit -> !hit.upgrade()).mapToInt(hit -> hit.sideA() == first ? 1 : -1).sum();
        return List.of(reply.score(), reply.followups(), BoxingReentryTiming.openingChain(hits, !first),
                reply.timely(), freshMargin);
    }

    private static Map<String, Object> approach(DuelEnv env, DuelEnv.Game prefix, double cut,
                                                BoxingReentryRelease.Delay delay, double end) {
        var checkpoint = begin(env, prefix, cut, delay);
        var outcomes = new ArrayList<List<Object>>();
        var words = new ArrayList<Integer>();
        words.add(0);
        if (CHECK_WORD >= 0) {
            if (CHECK_WORD == 0 || CHECK_WORD >= WORDS) throw new IllegalArgumentException("Unknown word " + CHECK_WORD);
            words.add(CHECK_WORD);
        } else {
            for (int word = 1; word < WORDS; word++) words.add(word);
        }
        for (int word : words) outcomes.add(approachScore(suffix(env, checkpoint, end, word), prefix.first));
        var result = new LinkedHashMap<String, Object>();
        result.put("status", "approach");
        result.put("digitLabels", STEPS.stream().map(Step::label).toList());
        result.put("scoreColumns", List.of("margin", "replyFollowups", "opponentOpeningChain", "timely", "freshMargin"));
        result.put("outcomes", outcomes);
        if (CHECK_WORD >= 0) result.put("words", words);
        if (TRACE_WORD >= 0) {
            if (TRACE_WORD >= WORDS) throw new IllegalArgumentException("Unknown word " + TRACE_WORD);
            result.put("traces", List.of(trace(env, checkpoint, cut, end, 0), trace(env, checkpoint, cut, end, TRACE_WORD)));
        }
        return result;
    }

    static Map<String, Object> measure(DuelEnv env, DuelEnv.Game prefix, double cut, BoxingReentryRelease.Delay delay) {
        double end = cut + 2500;
        if ("close-factorial".equals(LOCAL_CUE)) return closeApproach(env, prefix, cut, end, delay);
        if ("approach".equals(LOCAL_CUE)) return approach(env, prefix, cut, delay, end);
        var factual = begin(env, prefix, cut, delay);
        boolean first = prefix.first;
        var defender = (BoxingOpeningRecoil.Defender) opponent(factual);
        var moveAt = new double[]{Double.NaN};
        String move = first ? "MOVEB" : "MOVEA";
        factual.clock.setAudit((time, event, state) -> {
            factual.hits.event(time, event, state);
            if (event.equals(move) && defender.recoil != null && Double.isNaN(moveAt[0])) moveAt[0] = time;
        });
        factual.clock.runUntil(end);
        var reply = BoxingOpeningRecoil.score(factual.hits.list, first);
        var impulse = defender.recoil;
        if (!reply.timely()) return excluded("no timely reply");
        if (impulse == null || !Double.isFinite(moveAt[0]) || impulse.at() < reply.counter() || impulse.at() >= moveAt[0])
            return excluded("recoil ordering unresolved");
        var checkpoint = begin(env, prefix, cut, delay);
        checkpoint.clock.runUntil(Math.nextDown(moveAt[0]));
        var body = BoxingOpeningRecoil.defender(checkpoint);
        var q = body.q;
        boolean full = q.vx() == impulse.vx() && q.vz() == impulse.vz() && q.vy() == impulse.vy();
        boolean braked = q.vx() == .6 * impulse.vx() && q.vz() == .6 * impulse.vz() && q.vy() == impulse.vy();
        if (!full && !braked) return excluded("first recoil step unresolved");
        if (full && !FORCE_BRAKE && !INCLUDE_FULL) return excluded("first recoil step full");
        boolean forced = full && FORCE_BRAKE;
        if (LOCAL_CUE == null) {
            if (forced) brake(body);
            checkpoint.clock.runUntil(moveAt[0]);
        } else {
            if (FORCE_BRAKE) throw new IllegalArgumentException("Use local-cue forks with factual recoil");
            checkpoint = begin(env, prefix, cut, delay);
            if (!advanceToLocalCue(checkpoint, cut, end)) return excluded("local cue not observed");
        }
        var baseline = suffix(env, checkpoint, end, 0);
        if (!forced && !baseline.equals(factual.hits.list)) throw new AssertionError("W-tap continuation diverged");
        var outcomes = new ArrayList<List<Integer>>();
        outcomes.add(score(baseline, first));
        if (CHECK_WORD >= 0) {
            if (CHECK_WORD == 0 || CHECK_WORD >= WORDS) throw new IllegalArgumentException("Unknown word " + CHECK_WORD);
            outcomes.add(score(suffix(env, checkpoint, end, CHECK_WORD), first));
        } else {
            for (int word = 1; word < WORDS; word++) outcomes.add(score(suffix(env, checkpoint, end, word), first));
        }
        var output = new LinkedHashMap<String, Object>();
        output.put("status", forced ? "forced brake" : full ? "full recoil" : "observed brake");
        output.put("moveMs", moveAt[0] - cut);
        if (LOCAL_CUE != null) {
            output.put("cue", LOCAL_CUE);
            output.put("checkpointMs", checkpoint.clock.time() - cut);
        }
        output.put("replyMargin", reply.score());
        output.put("scoreColumns", List.of("margin", "replyFollowups", "opponentOpeningChain"));
        output.put("digitLabels", STEPS.stream().map(Step::label).toList());
        output.put("outcomes", outcomes);
        if (CHECK_WORD >= 0) output.put("words", List.of(0, CHECK_WORD));
        if (TRACE_WORD >= 0) {
            if (TRACE_WORD >= WORDS) throw new IllegalArgumentException("Unknown word " + TRACE_WORD);
            output.put("traces", List.of(trace(env, checkpoint, cut, end, 0),
                    trace(env, checkpoint, cut, end, TRACE_WORD)));
        }
        return output;
    }
}
