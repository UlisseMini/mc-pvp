import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import lab.Json;

/** Compare a single own-client edge-of-reach hold against the unchanged match policies. */
final class BoxingEdgeHitSelect {
    private static final double HOLD_MS = Integer.getInteger("lab.boxing.edgeHoldMs", 80);
    private static final double MIN_DISTANCE = 2.5;
    private static final double MAX_DISTANCE = 3.0;
    private static final double REARM_DISTANCE = 3.15;
    private static final double QUIET_MS = 500;
    private static final int HOLD_BUDGET = Integer.getInteger("lab.boxing.holdBudget", Integer.MAX_VALUE);
    private static final int OWN_PHASE_MS = Integer.getInteger("lab.boxing.regOwnPhase", 0);
    private static final int OTHER_PHASE_MS = Integer.getInteger("lab.boxing.regOtherPhase", 0);
    private static final boolean SHIFT_MAINTENANCE = Boolean.getBoolean("lab.boxing.shiftMaintenance");

    static class Selector extends DuelEnv.Player {
        final DuelEnv.Player movement;
        final boolean shadow;
        final List<Double> starts = new ArrayList<>();
        boolean armed = true;
        double pendingUntil = Double.NaN;

        Selector(DuelEnv.Player movement) {
            this(movement, false);
        }

        Selector(DuelEnv.Player movement, boolean shadow) {
            super(movement.style, movement.mask, movement.rtt, movement.wake);
            this.movement = movement;
            this.shadow = shadow;
            firstClick = movement.firstClick;
        }

        @Override public PeriodicComboClock.Control input(PeriodicComboClock.Observation own) {
            if (!armed && !Double.isFinite(pendingUntil) && own.nearest() > REARM_DISTANCE)
                armed = true;
            return movement.input(own);
        }

        @Override public boolean allowInjection(PeriodicComboClock.Observation own, String source,
                                                 BooleanSupplier contactReady) {
            double now = own.time();
            if (Double.isFinite(pendingUntil)) return now >= pendingUntil;
            if (!armed || (!shadow && starts.size() >= HOLD_BUDGET) || !own.own().onGround()
                    || now - own.lastOwnHurtTime() < QUIET_MS
                    || now - own.lastTargetHurtTime() < QUIET_MS) return true;
            double gap = own.nearest();
            if (gap < MIN_DISTANCE || gap > MAX_DISTANCE || !contactReady.getAsBoolean()) return true;
            armed = false;
            marked(own, source);
            if (!shadow && !wantsHold(own, source)) return true;
            starts.add(now);
            if (shadow) return true;
            pendingUntil = now + HOLD_MS;
            return false;
        }

        void marked(PeriodicComboClock.Observation own, String source) {}

        boolean wantsHold(PeriodicComboClock.Observation own, String source) { return true; }

        @Override public boolean injected(PeriodicComboClock.Observation own, double time) {
            super.injected(own, time);
            pendingUntil = Double.NaN;
            return movement.injected(own, time);
        }

        @Override Selector copy() {
            var clone = new Selector(movement.copy(), shadow);
            clone.armed = armed;
            clone.pendingUntil = pendingUntil;
            clone.starts.addAll(starts);
            return clone;
        }
    }

    private record Term(String feature, double weight, double center, double scale) {}

    private record LinearScore(double intercept, double threshold, List<Term> terms) {
        static LinearScore read(Path path) throws Exception {
            var lines = Files.readAllLines(path);
            var header = lines.get(0).split("\t");
            var terms = new ArrayList<Term>();
            for (var line : lines.subList(1, lines.size())) {
                var columns = line.split("\t");
                terms.add(new Term(columns[0], Double.parseDouble(columns[1]),
                        Double.parseDouble(columns[2]), Double.parseDouble(columns[3])));
            }
            return new LinearScore(Double.parseDouble(header[0]), Double.parseDouble(header[1]), List.copyOf(terms));
        }

        boolean choose(Map<String, Object> features) {
            double score = intercept;
            for (var term : terms) {
                if (!features.containsKey(term.feature)) throw new IllegalArgumentException("Unknown causal feature " + term.feature);
                if (features.get(term.feature) instanceof Number value)
                    score += term.weight * (value.doubleValue() - term.center) / term.scale;
            }
            return score >= threshold;
        }
    }

    private static final class ValueSelector extends BoxingEdgePollAudit.ObservationHistory {
        final LinearScore model;

        ValueSelector(DuelEnv.Player movement, LinearScore model) {
            super(movement, false);
            this.model = model;
        }

        @Override boolean wantsHold(PeriodicComboClock.Observation own, String source) {
            return model.choose(observe(own, source).get("geometry"));
        }

        @Override ValueSelector copy() {
            var clone = new ValueSelector(movement.copy(), model);
            clone.armed = armed;
            clone.pendingUntil = pendingUntil;
            clone.starts.addAll(starts);
            clone.heads.addAll(heads);
            clone.lastPress = lastPress;
            clone.lastPosition = lastPosition;
            clone.previousPosition = previousPosition;
            return clone;
        }
    }

    private static Selector selector(DuelEnv.Player player, LinearScore value) {
        return value == null ? new Selector(player) : new ValueSelector(player, value);
    }

    static DuelEnv environment(Path tape, int rtt, int limit, Path wire, Path seeds) throws Exception {
        String profile = System.getProperty("lab.boxing.profile", "mmc");
        var env = BoxingDoubleSearch.environment(tape, profile, rtt, 20, limit);
        BoxingJumpTimingAudit.wireTiming(env, wire.resolve(env.clockId + ".txt"));
        BoxingJumpTimingAudit.seedTracker(env, seeds.resolve(env.clockId + ".txt"));
        return env;
    }

    static double decidingHit(DuelEnv.Game game) {
        int a = 0, b = 0;
        for (var hit : game.hits.list) {
            if (hit.sideA()) a++;
            else b++;
            if (a == 100 || b == 100) return hit.at();
        }
        return game.clock.time();
    }

    private static Map<String, Object> regulation(DuelEnv env, boolean first, Arena.Model model,
                                                   boolean select, LinearScore value) {
        DuelEnv.Player movement = model == null ? Evolve.WT.create(env.rtt, 0) : new Arena.Neural(model, env.rtt);
        var player = select ? selector(movement, value) : movement;
        var game = env.new Game(first, player, Evolve.WT.create(env.rtt, 0));
        if (OWN_PHASE_MS != 0) game.clock.shiftFutureClientOpportunities(first, OWN_PHASE_MS);
        if (OTHER_PHASE_MS != 0) game.clock.shiftFutureClientOpportunities(!first, OTHER_PHASE_MS);
        game.trace();
        var presses = List.of(new ArrayList<Double>(), new ArrayList<Double>());
        game.clock.setClickAudit(e -> {
            if (e.action().equals("ADMIT")) presses.get(e.fromA() == first ? 0 : 1).add(e.admittedNanos() / 1e6);
        });
        var score = BoxingDoubleSearch.play(game, 100);
        double finish = decidingHit(game);
        for (var side : presses) {
            for (int i = 1; i < side.size(); i++) {
                if (side.get(i) - side.get(i - 1) < 50 - 1e-6)
                    throw new AssertionError("Physical click cap violated");
            }
        }
        double active = presses.stream().filter(p -> !p.isEmpty()).mapToDouble(p -> p.get(0)).max().orElse(finish);
        var cps = presses.stream().map(p ->
                1000.0 * p.stream().filter(t -> t >= active && t <= finish).count() / (finish - active)).toList();
        long holds = select ? ((Selector) player).starts.stream().filter(t -> t <= finish).count() : 0;
        return Map.of("scores", score.values(), "cps", cps, "holds", holds,
                "terminal", score.own() == 100 || score.other() == 100 ? "100_hits" : "censored",
                "finishMs", finish);
    }

    private static Map<String, Object> maintain(BoxingDrill.Checkpoint checkpoint, Arena.Model model,
                                                 boolean select, LinearScore value) {
        var game = checkpoint.suffix(model);
        Selector player = null;
        if (select) {
            player = selector(game.our(), value);
            if (player instanceof ValueSelector forecast) forecast.prime(checkpoint.history, checkpoint.lastPhysicalPress);
            game = checkpoint.env.new Game(game, game.first ? player : game.a.copy(),
                    game.first ? game.b.copy() : player);
            game.trace();
            game.hits.list.addAll(checkpoint.committed);
        }
        boolean first = game.first;
        if (SHIFT_MAINTENANCE) {
            if (OWN_PHASE_MS != 0) game.clock.shiftFutureClientOpportunities(first, OWN_PHASE_MS);
            if (OTHER_PHASE_MS != 0) game.clock.shiftFutureClientOpportunities(!first, OTHER_PHASE_MS);
        }
        while (game.hits.list.stream().noneMatch(h -> h.sideA() != first) && game.advance()) {
            // A return ends both the return-only follow-up and any remaining exposure.
        }
        double activeGap = 100.0 * game.clock.config.hitTicks + 100;
        var readout = BoxingLateReturns.score(game, checkpoint.landmark, activeGap);
        long holds = player == null ? 0 : player.starts.stream()
                .filter(t -> t <= checkpoint.landmark + readout.returnOnlyMs()).count();
        var holdTimes = player == null ? List.<Double>of() : player.starts.stream()
                .filter(t -> t <= checkpoint.landmark + readout.returnOnlyMs())
                .map(t -> t - checkpoint.landmark).toList();
        var hits = game.hits.list.stream().filter(h -> h.at() <= checkpoint.landmark + readout.returnOnlyMs())
                .map(h -> List.of(h.at() - checkpoint.landmark, h.sideA() == first, h.upgrade())).toList();
        return Map.of("cycles", readout.active(), "activeMs", readout.activeMs(), "activeEnd", readout.activeEnd(),
                "returnOnlyCycles", readout.returnOnly(), "returnOnlyMs", readout.returnOnlyMs(),
                "returnOnlyEnd", readout.returnOnlyEnd(), "holdsBeforeReturn", holds,
                "holdTimes", holdTimes, "followupHits", hits);
    }

    public static void main(String[] args) throws Exception {
        var model = Arena.Model.decode(Files.readString(Path.of(args[0])).strip());
        var scorePath = System.getProperty("lab.boxing.edgeValueFile");
        var value = scorePath == null ? null : LinearScore.read(Path.of(scorePath));
        int rtt = Integer.parseInt(args[1]);
        var wire = Path.of(args[2]);
        var clipped = Path.of(args[3]);
        var seeds = Path.of(args[4]);
        for (int i = 5; i < args.length; i++) {
            var file = Path.of(args[i]);
            var reg = environment(file, rtt, 180000, wire, seeds);
            var drill = environment(file, rtt, 20000, clipped, seeds);
            for (boolean first : new boolean[]{true, false}) {
                var maintenance = new ArrayList<Map<String, Object>>();
                if (SHIFT_MAINTENANCE || (OWN_PHASE_MS == 0 && OTHER_PHASE_MS == 0)) {
                    for (var cp : BoxingDrill.acquire(drill, first, true)) {
                        maintenance.add(Map.of("k", cp.k, "time", cp.landmark,
                                "wtap", maintain(cp, null, false, value), "frozen", maintain(cp, model, false, value),
                                "wtapHold", maintain(cp, null, true, value), "frozenHold", maintain(cp, model, true, value)));
                    }
                }
                System.out.println(Json.encode(Map.of("clock", reg.clockId, "rtt", rtt, "seat", first ? "a" : "b",
                        "budget", HOLD_BUDGET, "phase", List.of(OWN_PHASE_MS, OTHER_PHASE_MS),
                        "wtap", regulation(reg, first, null, false, value), "frozen", regulation(reg, first, model, false, value),
                        "wtapHold", regulation(reg, first, null, true, value), "frozenHold", regulation(reg, first, model, true, value),
                        "maintenance", maintenance)));
            }
        }
    }
}
