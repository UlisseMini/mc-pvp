import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lab.Json;

/** Evaluate the frozen own-view release gate on independent native calendars. */
final class BoxingRecoilTransfer {
    private static final int HORIZON = 2200;
    private static final int DRILL = 20_000;
    private static final int REGULATION = 180_000;
    private static final int SPRINT = PeriodicComboFightModel.S;
    private static final int RELEASE = PeriodicComboFightModel.R;
    private static final int SPRINT_WORD = BoxingChaseEnvelope.movementWord(List.of("sprint", "keep", "keep"));
    private static final List<Timing> ALL_TIMINGS = timings();
    private static final List<Timing> WHOLE_BOUT_TIMINGS = List.of(
            new Timing(0, 0, 1), new Timing(-2, 0, 1), new Timing(2, 0, 1),
            new Timing(0, -2, -1), new Timing(0, 2, -1));

    private record Timing(int own, int defender, int direction) {
        void apply(DuelEnv.Game game) {
            if (own == 0 && defender == 0) return;
            var state = game.clock.state;
            state.packetTiming = new BoxingReleaseWindow.ShiftedReceipt(state.packetTiming,
                    game.first ? own : defender, game.first ? defender : own, direction);
        }
        List<Integer> fields() { return List.of(own, defender, direction); }
    }

    private static List<Timing> timings() {
        var times = new ArrayList<Timing>();
        for (int own : new int[]{0, -2, 2}) for (int defender : new int[]{0, -2, 2}) {
            times.add(new Timing(own, defender, 1));
            if (own != 0 || defender != 0) times.add(new Timing(own, defender, -1));
        }
        return List.copyOf(times);
    }

    private record Cue(double gap, double along, double age, double cueDelta, double height,
                       boolean grounded, boolean sprint, double vertical) {
        static Cue at(PeriodicComboClock.Observation view) {
            var own = view.own();
            double x = view.targetX() - own.x();
            double z = view.targetZ() - own.z();
            double length = Math.hypot(x, z);
            double along = length == 0 ? 0 : (x * own.vx() + z * own.vz()) / length;
            return new Cue(view.nearest(), along, view.time() - view.lastTargetHurtTime(),
                    Math.abs(view.lastTargetHurtTime() - view.lastOwnHurtTime()), view.targetY() - own.y(),
                    own.onGround(), view.sprint(), own.vy());
        }

        boolean heavy() { return grounded && along <= -.80 && gap <= 1.60; }

        boolean admitted() { return heavy() && !sprint && gap <= 1.30 && cueDelta <= 16 && age <= 75; }

        Map<String, Object> fields() {
            var row = new LinkedHashMap<String, Object>(Map.of(
                    "gap", gap, "along", along, "age", age, "height", height,
                    "grounded", grounded, "sprint", sprint, "vertical", vertical));
            row.put("cueDelta", Double.isFinite(cueDelta) ? cueDelta : null);
            return row;
        }
    }

    private static final class Subject extends DuelEnv.Player {
        final DuelEnv.Player stock;
        final List<Double> changes = new ArrayList<>();

        Subject(DuelEnv.Player stock) {
            super(stock.style, stock.mask, stock.rtt, stock.wake);
            this.stock = stock.copy();
            firstClick = stock.firstClick;
        }

        @Override public PeriodicComboClock.Control input(PeriodicComboClock.Observation view) {
            double previous = stock.handled;
            var ordinary = stock.input(view);
            if (stock.handled <= previous || ordinary.key() != RELEASE || !Cue.at(view).admitted()) return ordinary;
            changes.add(view.time());
            return new PeriodicComboClock.Control(SPRINT, ordinary.strafe(), ordinary.click(), ordinary.jump());
        }
    }

    private static final class Presses {
        final List<List<Long>> bySeat = List.of(new ArrayList<>(), new ArrayList<>());
        Presses(DuelEnv.Game game) {
            game.clock.setClickAudit(event -> {
                if (!event.action().equals("ADMIT")) return;
                var side = bySeat.get(event.fromA() == game.first ? 0 : 1);
                if (!side.isEmpty() && event.admittedNanos() - side.get(side.size() - 1) < 50_000_000L)
                    throw new IllegalStateException("Physical click cap exceeded");
                side.add(event.admittedNanos());
            });
        }

        List<List<Long>> before(double end) {
            return bySeat.stream().map(side -> side.stream().takeWhile(t -> t / 1e6 <= end).toList()).toList();
        }
    }

    private record Launch(double at, int tick, int hitIndex) {}
    private record Suffix(Map<String, Object> score, List<List<Long>> presses, List<DuelEnv.Hit> hits) {}

    private static Suffix suffix(DuelEnv env, DuelEnv.Game prefix, Launch launch, Timing timing, int word) {
        var game = BoxingChaseEnvelope.branch(env, prefix, new BoxingChaseEnvelope.Movement(prefix.our(), word));
        timing.apply(game);
        var presses = new Presses(game);
        int ready = launch.tick + game.clock.config.hitTicks;
        double[] freshAndReturn = {Double.NaN, Double.NaN};
        int[] freshTick = {-1};
        for (var hit : game.hits.list) {
            if (hit.sideA() != game.first && !Double.isFinite(freshAndReturn[1])) freshAndReturn[1] = hit.at();
        }
        game.clock.setAudit((time, stage, state) -> {
            int previous = game.hits.list.size();
            game.hits.event(time, stage, state);
            for (int i = previous; i < game.hits.list.size(); i++) {
                var hit = game.hits.list.get(i);
                if (hit.sideA() != game.first && !Double.isFinite(freshAndReturn[1])) freshAndReturn[1] = hit.at();
                if (hit.sideA() == game.first && !hit.upgrade() && !Double.isFinite(freshAndReturn[0])) {
                    freshAndReturn[0] = hit.at();
                    freshTick[0] = state.serverTicks;
                }
            }
        });
        double end = launch.at + HORIZON;
        game.clock.runUntil(end);
        boolean timely = freshTick[0] >= ready && freshTick[0] <= ready + 1;
        boolean protectedHit = timely && (!Double.isFinite(freshAndReturn[1]) || freshAndReturn[1] > freshAndReturn[0] + 500);
        var score = new LinkedHashMap<>(BoxingLateReturns.score(game, launch.at,
                100.0 * game.clock.config.hitTicks + 100).values());
        score.put("timely", timely);
        score.put("protected", protectedHit);
        score.put("nextFreshMs", elapsed(freshAndReturn[0], launch.at));
        score.put("returnMs", elapsed(freshAndReturn[1], launch.at));
        score.put("clicks", presses.bySeat.stream().map(List::size).toList());
        return new Suffix(score, presses.before(end), List.copyOf(game.hits.list));
    }

    private static Double elapsed(double event, double anchor) {
        return Double.isFinite(event) ? event - anchor : null;
    }

    private static double horizon(DuelEnv env) {
        return Math.min(env.limit, env.serverEnds[env.serverEnds.length - 1]);
    }

    private record Parity(Launch anchor, List<DuelEnv.Hit> nominal) {}

    private static List<Map<String, Object>> cuts(DuelEnv env, boolean first) {
        var game = env.new Game(first, new DuelEnv.Player("wtap", 0, env.rtt, 0),
                new DuelEnv.Player("wtap", 0, env.rtt, 0));
        game.trace();
        var launches = new ArrayList<Launch>();
        game.clock.setAudit((time, stage, state) -> {
            int before = game.hits.list.size();
            game.hits.event(time, stage, state);
            for (int i = before; i < game.hits.list.size(); i++) {
                var hit = game.hits.list.get(i);
                if (hit.sideA() == first && !hit.upgrade()) launches.add(new Launch(time, state.serverTicks, i));
            }
        });
        var output = new ArrayList<Map<String, Object>>();
        var stockPaths = new ArrayList<Parity>();
        double limit = horizon(env);
        if (!game.clock.advanceToBeforeNextHead(first, limit)) return output;
        do {
            var state = game.clock.state;
            if (Math.max(state.acceptedA, state.acceptedB) >= 85) break;
            var view = game.clock.observation(first);
            if (view.lastTargetHurtTime() <= game.our().handled || launches.isEmpty()) continue;
            var cue = Cue.at(view);
            if (!cue.heavy() || game.our().copy().input(view).key() != RELEASE) continue;
            var launch = launches.get(launches.size() - 1);
            if (limit < launch.at + HORIZON || launch.at > view.lastTargetHurtTime()) continue;
            var prefix = env.new Game(game, game.a.copy(), game.b.copy());
            prefix.trace();
            prefix.hits.list.addAll(game.hits.list.subList(launch.hitIndex + 1, game.hits.list.size()));
            var phases = new ArrayList<Map<String, Object>>();
            for (var timing : ALL_TIMINGS) {
                var baseline = suffix(env, prefix, launch, timing, 0);
                var sprint = suffix(env, prefix, launch, timing, SPRINT_WORD);
                if (timing.own == 0 && timing.defender == 0) stockPaths.add(new Parity(launch, baseline.hits));
                phases.add(Map.of("timing", timing.fields(), "wtap", baseline.score, "sprint", sprint.score,
                        "matched", baseline.presses.equals(sprint.presses)));
            }
            output.add(Map.of("head", view.time(), "anchor", launch.at, "admitted", cue.admitted(),
                    "observation", cue.fields(), "phases", phases));
        } while (game.clock.runHeadInterval(first, limit));
        game.clock.runUntil(limit);
        for (var stock : stockPaths) {
            var anchor = stock.anchor;
            var expected = game.hits.list.subList(anchor.hitIndex + 1, game.hits.list.size()).stream()
                    .takeWhile(hit -> hit.at() <= anchor.at + HORIZON).toList();
            if (!expected.equals(stock.nominal)) throw new IllegalStateException("Copied W-tap branch changed trace");
        }
        return output;
    }

    private record Played(Map<String, Object> score, Presses presses, double end) {}

    private static Played regulation(DuelEnv env, boolean first, Timing timing, boolean change) {
        var stock = new DuelEnv.Player("wtap", 0, env.rtt, 0);
        var patient = new Subject(stock);
        var game = env.new Game(first, change ? patient : stock, new DuelEnv.Player("wtap", 0, env.rtt, 0));
        timing.apply(game);
        game.trace();
        var presses = new Presses(game);
        var result = BoxingDoubleSearch.play(game, 100);
        double end = Math.min(horizon(env), BoxingEdgeHitSelect.decidingHit(game));
        double start = Math.max(game.a.firstClick, game.b.firstClick);
        var cps = presses.before(end).stream().map(side ->
                1000 * side.stream().filter(t -> t / 1e6 >= start).count() / (end - start)).toList();
        return new Played(Map.of("score", result.values(), "cps", cps, "end", end,
                "changedAt", patient.changes.stream().filter(t -> t <= end).toList()), presses, end);
    }

    private static Played maintenance(BoxingDrill.Checkpoint cp, Timing timing, boolean change) {
        var start = cp.suffix(null);
        var subject = new Subject(start.our());
        var game = change ? BoxingChaseEnvelope.branch(cp.env, start, subject) : start;
        timing.apply(game);
        var presses = new Presses(game);
        double end = horizon(cp.env);
        game.clock.runUntil(end);
        var score = new LinkedHashMap<>(BoxingLateReturns.score(game, cp.landmark,
                100.0 * game.clock.config.hitTicks + 100).values());
        score.put("changedAt", subject.changes);
        return new Played(score, presses, end);
    }

    private static Map<String, Object> pair(Played baseline, Played changed) {
        double end = Math.min(baseline.end, changed.end);
        return Map.of("wtap", baseline.score, "gate", changed.score,
                "matched", baseline.presses.before(end).equals(changed.presses.before(end)));
    }

    public static void main(String[] args) throws Exception {
        int rtt = Integer.parseInt(args[0]);
        var tape = Path.of(args[4]);
        var reg = BoxingEdgeHitSelect.environment(tape, rtt, REGULATION, Path.of(args[1]), Path.of(args[3]));
        var drill = BoxingEdgeHitSelect.environment(tape, rtt, DRILL, Path.of(args[2]), Path.of(args[3]));
        for (boolean first : new boolean[]{true, false}) {
            var starts = BoxingDrill.acquire(drill, first, true);
            var bouts = new ArrayList<Map<String, Object>>();
            for (var timing : WHOLE_BOUT_TIMINGS) {
                var drills = starts.stream().map(cp -> Map.of("k", cp.k, "anchor", cp.landmark,
                        "pair", pair(maintenance(cp, timing, false), maintenance(cp, timing, true)))).toList();
                bouts.add(Map.of("timing", timing.fields(), "maintenance", drills,
                        "regulation", pair(regulation(reg, first, timing, false), regulation(reg, first, timing, true))));
            }
            System.out.println(Json.encode(Map.of("clock", reg.clockId, "rtt", rtt, "seat", first ? "a" : "b",
                    "cuts", cuts(reg, first), "bouts", bouts)));
        }
    }
}
