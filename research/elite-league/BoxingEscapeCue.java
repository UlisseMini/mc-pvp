import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lab.Json;

/** Grounded impact-jump observations and paired counterfactuals using ordinary controls. */
final class BoxingEscapeCue {
    private static final double WINDOW_MS = 1700;
    private static final int ENROLLMENT_SCORE = 85;

    private record Term(String field, double weight, double center, double scale) {}

    private record Linear(double intercept, double threshold, List<Term> terms) {
        static Linear read(Path path) throws Exception {
            var rows = Files.readAllLines(path);
            var headline = rows.get(0).split("\t");
            var terms = new ArrayList<Term>();
            for (var row : rows.subList(1, rows.size())) {
                var f = row.split("\t");
                terms.add(new Term(f[0], Double.parseDouble(f[1]), Double.parseDouble(f[2]), Double.parseDouble(f[3])));
            }
            return new Linear(Double.parseDouble(headline[0]), Double.parseDouble(headline[1]), List.copyOf(terms));
        }

        boolean chooses(Map<String, Double> data) {
            double estimate = intercept;
            for (var t : terms) {
                if (!data.containsKey(t.field)) throw new AssertionError("Unknown observed feature " + t.field);
                Double value = data.get(t.field);
                if (value != null) estimate += t.weight * (value - t.center) / t.scale;
            }
            return estimate >= threshold;
        }
    }

    private static final class Player extends DuelEnv.Player {
        final ArrayDeque<PeriodicComboClock.Observation> heads = new ArrayDeque<>();
        PeriodicComboClock.PacketObservation pending;
        double previousImpact = Double.NEGATIVE_INFINITY;
        double latestImpact = Double.NEGATIVE_INFINITY;
        final Linear rule;
        int decisionsRemaining;
        int groundedDecisions;
        Map<String, Double> firstDecision;
        double firstDecisionAt = Double.NaN;
        Boolean firstJumpChoice;
        Boolean forceNextImpact;

        Player(int rtt, int decisions, Linear rule) {
            super("wtap", 0, rtt, 0);
            this.decisionsRemaining = decisions;
            this.rule = rule;
        }

        Player(Player history, int decisions, Linear rule) {
            this(history.rtt, decisions, rule);
            heads.addAll(history.heads);
            latestImpact = history.latestImpact;
            previousImpact = history.previousImpact;
            pending = history.pending;
            handled = history.handled;
            releaseUntil = history.releaseUntil;
            firstClick = history.firstClick;
        }

        @Override public void received(PeriodicComboClock.PacketObservation packet) {
            if (packet.kind() == PeriodicComboClock.ReceivedKind.OWN_VELOCITY
                    && (packet.vx() != 0 || packet.vz() != 0)) {
                previousImpact = latestImpact;
                latestImpact = packet.handledAt();
                pending = packet;
            }
        }

        @Override public PeriodicComboClock.Control input(PeriodicComboClock.Observation own) {
            var normal = super.input(own);
            boolean jump = false;
            if (pending != null && decisionsRemaining > 0) {
                decisionsRemaining--;
                if (own.own().onGround()) {
                    groundedDecisions++;
                    var features = observe(own, normal);
                    jump = forceNextImpact != null ? forceNextImpact : rule == null || rule.chooses(features);
                    if (firstDecision == null) {
                        firstDecision = features;
                        firstDecisionAt = own.time();
                        firstJumpChoice = jump;
                    }
                }
                forceNextImpact = null;
            }
            pending = null;
            heads.addLast(own);
            if (heads.size() > 6) heads.removeFirst();
            if (!jump) return normal;
            jumps++;
            return new PeriodicComboClock.Control(normal.key(), normal.strafe(), normal.click(), true);
        }

        private Map<String, Double> observe(PeriodicComboClock.Observation o, PeriodicComboClock.Control normal) {
            double x = o.targetX() - o.own().x(), z = o.targetZ() - o.own().z(), gap = Math.hypot(x, z);
            double ux = gap == 0 ? 0 : x / gap, uz = gap == 0 ? 0 : z / gap;
            double t = o.time();
            var p = pending;
            if (p.handledAt() > t || p.arrivedAt() > t)
                throw new AssertionError("Own velocity packet had not arrived at decision");
            var f = new LinkedHashMap<String, Double>();
            f.put("rtt", (double) rtt);
            f.put("nearest", o.nearest());
            f.put("height", o.targetY() - o.own().y());
            f.put("along", o.own().vx() * ux + o.own().vz() * uz);
            f.put("vertical", o.own().vy());
            f.put("recoil", Math.hypot(p.vx(), p.vz()));
            f.put("recoilVertical", p.vy());
            f.put("sprint", o.sprint() ? 1.0 : 0.0);
            f.put("pressingSprint", normal.key() == PeriodicComboFightModel.S ? 1.0 : 0.0);
            f.put("sinceReceipt", t - p.arrivedAt());
            f.put("sinceHandled", t - p.handledAt());
            f.put("sinceContact", age(t, o.lastContactTime()));
            f.put("sinceOwnHurt", age(t, o.lastOwnHurtTime()));
            f.put("sinceTargetHurt", age(t, o.lastTargetHurtTime()));
            f.put("sincePreviousImpact", age(t, previousImpact));
            for (int cutoff : new int[]{35, 90}) {
                PeriodicComboClock.Observation previous = null;
                for (var it = heads.descendingIterator(); it.hasNext();) {
                    var candidate = it.next();
                    if (t - candidate.time() >= cutoff) {
                        previous = candidate;
                        break;
                    }
                }
                double dt = previous == null ? 1 : t - previous.time();
                f.put("closure" + cutoff, previous == null ? null : 50 * (
                        Math.hypot(previous.targetX() - previous.own().x(), previous.targetZ() - previous.own().z()) - gap) / dt);
                f.put("targetAlong" + cutoff, previous == null ? null :
                        50 * ((o.targetX() - previous.targetX()) * ux + (o.targetZ() - previous.targetZ()) * uz) / dt);
                f.put("targetLift" + cutoff, previous == null ? null : 50 * (o.targetY() - previous.targetY()) / dt);
            }
            return f;
        }
    }

    private static double age(double now, double seen) {
        return Double.isFinite(seen) ? Math.min(2200, now - seen) : 2200;
    }

    private static DuelEnv.Game mirror(DuelEnv env, boolean first) {
        return source(env, first, 0, null);
    }

    private static DuelEnv.Game source(DuelEnv env, boolean first, int decisions, Linear rule) {
        var game = env.new Game(first, new Player(env.rtt, decisions, rule), Evolve.WT.create(env.rtt, 0));
        game.trace();
        return game;
    }

    private static DuelEnv.Game fork(DuelEnv env, DuelEnv.Game prefix, Player ours) {
        var game = env.new Game(prefix, prefix.first ? ours : prefix.a.copy(), prefix.first ? prefix.b.copy() : ours);
        game.trace();
        return game;
    }

    private static Map<String, Object> shortScore(DuelEnv.Game game) {
        int own = 0, other = 0, freshOwn = 0, freshOther = 0, conceded = 0;
        boolean replied = false;
        for (var hit : game.hits.list) {
            if (hit.sideA() == game.first) {
                own++;
                if (other > 0) replied = true;
                if (!hit.upgrade()) freshOwn++;
            } else {
                other++;
                if (!hit.upgrade()) freshOther++;
                if (!replied) conceded++;
            }
        }
        return Map.of("accepted", List.of(own, other), "fresh", List.of(freshOwn, freshOther),
                "beforeReply", conceded, "margin", own - other);
    }

    private static void episodes(DuelEnv env, boolean first) {
        var stock = mirror(env, first);
        stock.finish();
        var hits = stock.hits.list;
        var prefix = mirror(env, first);
        int[] score = new int[2];
        int seen = 0;
        for (int i = 0; i < hits.size(); i++) {
            var hit = hits.get(i);
            boolean own = hit.sideA() == first;
            if (++score[own ? 0 : 1] >= ENROLLMENT_SCORE) break;
            if (own || hit.upgrade()) continue;
            double anchor = hit.at();
            double before = Math.nextDown(anchor), end = Math.min(env.limit, anchor + WINDOW_MS);
            prefix.clock.runUntil(before);
            var history = (Player) prefix.our();
            var challenger = new Player(history, 1, null);
            var alternative = fork(env, prefix, challenger);
            alternative.clock.runUntil(end);
            while (seen < i && hits.get(seen).at() <= before) seen++;
            int stop = i + 1;
            while (stop < hits.size() && hits.get(stop).at() <= end) stop++;
            var control = fork(env, prefix, new Player(history, 0, null));
            control.clock.runUntil(end);
            if (!control.hits.list.equals(hits.subList(seen, stop)))
                throw new AssertionError("Matched no-jump fork failed to replay");
            if (challenger.firstDecisionAt < anchor || alternative.hits.list.size() <= i - seen
                    || !alternative.hits.list.subList(0, i - seen + 1).equals(hits.subList(seen, i + 1)))
                throw new AssertionError("The actual impact must precede the grounded jump decision");
            var row = new LinkedHashMap<String, Object>();
            row.put("clock", env.clockId);
            row.put("seat", first ? "a" : "b");
            row.put("rtt", env.rtt);
            row.put("anchor", anchor);
            row.put("observed", challenger.firstDecision);
            row.put("waitMs", Double.isFinite(challenger.firstDecisionAt) ? challenger.firstDecisionAt - anchor : null);
            row.put("wtap", shortScore(control));
            row.put("jump", shortScore(alternative));
            System.out.println(Json.encode(row));
        }
    }

    private static void attribute(DuelEnv env, boolean first, String arm, Linear rule) {
        var original = source(env, first, Integer.MAX_VALUE, rule);
        original.finish();
        var prefix = source(env, first, Integer.MAX_VALUE, rule);
        var hits = original.hits.list;
        int[] scores = new int[2];
        int seen = 0;
        for (int i = 0; i < hits.size(); i++) {
            var hit = hits.get(i);
            boolean own = hit.sideA() == first;
            if (++scores[own ? 0 : 1] >= ENROLLMENT_SCORE) break;
            if (own || hit.upgrade()) continue;
            double anchor = hit.at(), end = Math.min(env.limit, anchor + WINDOW_MS);
            double cut = Math.nextDown(anchor);
            prefix.clock.runUntil(cut);
            while (seen < i && hits.get(seen).at() <= cut) seen++;
            int stop = i + 1;
            while (stop < hits.size() && hits.get(stop).at() <= end) stop++;
            var history = (Player) prefix.our();
            var base = new Player(history, Integer.MAX_VALUE, rule);
            var natural = fork(env, prefix, base);
            natural.clock.runUntil(end);
            if (!natural.hits.list.equals(hits.subList(seen, stop)))
                throw new AssertionError("Replaying the same escape rule changed its trajectory");
            var noMore = fork(env, prefix, new Player(history, 0, null));
            noMore.clock.runUntil(end);
            var outcomes = new LinkedHashMap<String, Object>();
            for (boolean jump : new boolean[]{false, true}) {
                var subject = new Player(history, Integer.MAX_VALUE, rule);
                subject.forceNextImpact = jump;
                var game = fork(env, prefix, subject);
                game.clock.runUntil(end);
                if (game.hits.list.size() <= i - seen
                        || !game.hits.list.subList(0, i - seen + 1).equals(hits.subList(seen, i + 1)))
                    throw new AssertionError("The reaction changed the already committed impact");
                outcomes.put(jump ? "jumpNext" : "skipNext", shortScore(game));
            }
            var row = new LinkedHashMap<String, Object>();
            row.put("clock", env.clockId);
            row.put("seat", first ? "a" : "b");
            row.put("rtt", env.rtt);
            row.put("anchor", anchor);
            row.put("stateSource", arm);
            row.put("choice", base.firstJumpChoice);
            row.put("observed", base.firstDecision);
            row.put("wtapContinuation", shortScore(noMore));
            row.put("natural", shortScore(natural));
            row.putAll(outcomes);
            System.out.println(Json.encode(row));
        }
    }

    private static Map<String, Object> regulation(DuelEnv env, boolean first, int ownPhase, int defenderPhase,
                                                   int maximum, Linear rule) {
        var subject = new Player(env.rtt, maximum, rule);
        var game = env.new Game(first, subject, Evolve.WT.create(env.rtt, 0));
        if (ownPhase != 0) game.clock.shiftFutureClientOpportunities(first, ownPhase);
        if (defenderPhase != 0) game.clock.shiftFutureClientOpportunities(!first, defenderPhase);
        game.trace();
        var presses = List.of(new ArrayList<Double>(), new ArrayList<Double>());
        game.clock.setClickAudit(event -> {
            if (event.action().equals("ADMIT")) {
                presses.get(event.fromA() == first ? 0 : 1).add(event.admittedNanos() / 1e6);
            }
        });
        var score = BoxingDoubleSearch.play(game, 100);
        double finish = BoxingEdgeHitSelect.decidingHit(game);
        double active = presses.stream().mapToDouble(p -> p.isEmpty() ? finish : p.get(0)).max().orElseThrow();
        var cps = presses.stream().map(p ->
                1000.0 * p.stream().filter(t -> t >= active && t <= finish).count() / (finish - active)).toList();
        for (var side : presses) for (int i = 1; i < side.size(); i++) {
            if (side.get(i) - side.get(i - 1) < 50 - 1e-6) throw new AssertionError("Unequal physical click cap");
        }
        return Map.of("scores", score.values(), "cps", cps, "jumps", subject.jumps,
                "opportunities", subject.groundedDecisions, "finishMs", finish);
    }

    private static Map<String, Object> continuation(BoxingDrill.Checkpoint checkpoint, DuelEnv.Game prefix,
                                                     int decisions, Linear rule) {
        var subject = new Player((Player) prefix.our(), decisions, rule);
        var game = fork(checkpoint.env, prefix, subject);
        game.hits.list.addAll(checkpoint.committed);
        boolean first = game.first;
        while (game.hits.list.stream().noneMatch(h -> h.sideA() != first) && game.advance()) {
            // The first accepted return ends both maintenance measures.
        }
        double activeGap = 100.0 * game.clock.config.hitTicks + 100;
        double stop = game.hits.list.stream().filter(h -> h.sideA() != first).findFirst()
                .map(DuelEnv.Hit::at).orElse(game.clock.time());
        return Map.of("score", BoxingLateReturns.score(game, checkpoint.landmark, activeGap).values(),
                "opportunityBeforeReturn", subject.firstDecisionAt < stop,
                "hits", game.hits.list.stream().filter(h -> h.at() <= stop)
                        .map(h -> List.of(h.at(), h.sideA() == first, h.upgrade())).toList());
    }

    private static void maintenance(DuelEnv env, boolean first, Linear risk, Linear value) {
        var prefix = env.new Game(first, new Player(env.rtt, 0, null),
                new DuelEnv.Player("wtap", 0, env.rtt, 2000));
        prefix.trace();
        for (var checkpoint : BoxingDrill.acquire(env, first, true)) {
            prefix.clock.runUntil(checkpoint.state.clock.time());
            prefix.step = checkpoint.state.step;
            var original = prefix.hits.list;
            int index = -1;
            for (int i = 0; i < original.size(); i++) {
                var hit = original.get(i);
                if (hit.at() == checkpoint.landmark && hit.sideA() == first && !hit.upgrade()) index = i;
            }
            if (index < 0 || !original.subList(index + 1, original.size()).equals(checkpoint.committed))
                throw new AssertionError("Cue history replay changed the shared acquisition");
            System.out.println(Json.encode(Map.of("clock", env.clockId, "rtt", env.rtt, "seat", first ? "a" : "b",
                    "at", checkpoint.landmark, "k", checkpoint.k,
                    "wtap", continuation(checkpoint, prefix, 0, null),
                    "alwaysJump", continuation(checkpoint, prefix, Integer.MAX_VALUE, null),
                    "risk", continuation(checkpoint, prefix, Integer.MAX_VALUE, risk),
                    "value", continuation(checkpoint, prefix, Integer.MAX_VALUE, value))));
        }
    }

    public static void main(String[] args) throws Exception {
        boolean collect = args[0].equals("episodes");
        boolean drill = args[0].equals("maintenance");
        boolean attribution = args[0].equals("attribution");
        int rtt = Integer.parseInt(args[1]);
        var wire = Path.of(args[2]);
        var seeds = Path.of(args[3]);
        Linear risk = collect ? null : Linear.read(Path.of(args[4]));
        Linear value = collect ? null : Linear.read(Path.of(args[5]));
        for (int i = collect ? 4 : 6; i < args.length; i++) {
            var env = BoxingEdgeHitSelect.environment(Path.of(args[i]), rtt, drill ? 20_000 : 180_000, wire, seeds);
            for (boolean first : new boolean[]{true, false}) {
                if (collect) episodes(env, first);
                else if (drill) maintenance(env, first, risk, value);
                else if (attribution) {
                    attribute(env, first, "alwaysJump", null);
                    attribute(env, first, "value", value);
                }
                else {
                    int own = Integer.getInteger("lab.boxing.regOwnPhase", 0);
                    int defender = Integer.getInteger("lab.boxing.regOtherPhase", 0);
                    System.out.println(Json.encode(Map.of("clock", env.clockId, "rtt", env.rtt, "seat", first ? "a" : "b",
                            "phase", List.of(own, defender),
                            "wtap", regulation(env, first, own, defender, 0, null),
                            "alwaysJump", regulation(env, first, own, defender, Integer.MAX_VALUE, null),
                            "risk", regulation(env, first, own, defender, Integer.MAX_VALUE, risk),
                            "value", regulation(env, first, own, defender, Integer.MAX_VALUE, value))));
                }
            }
        }
    }
}
