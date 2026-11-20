import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lab.Json;

/** Test frozen re-entry controls after generating neighboring trade histories. */
final class BoxingReentryAcquisition {
    private static final double MAX_CUT_SHIFT = 225;
    private static final double POINT_HORIZON = 2200;

    private static final class Approach extends BoxingLandingReentry.Scout {
        final double range;
        final int move, heads;
        final List<List<Number>> altered = new ArrayList<>();

        Approach(int rtt, double range, int move, int heads) {
            super(rtt);
            this.range = range;
            this.move = move;
            this.heads = heads;
        }

        @Override public PeriodicComboClock.Control input(PeriodicComboClock.Observation own) {
            var stock = super.input(own);
            if (altered.size() == heads || Double.isFinite(own.lastOwnHurtTime()) || Double.isFinite(own.lastTargetHurtTime()))
                return stock;
            if (altered.isEmpty() && own.nearest() > range) return stock;
            var changed = BoxingLateReturns.movement(stock, move);
            altered.add(List.of(own.time(), own.nearest(), stock.key(), changed.key(), stock.strafe(), changed.strafe()));
            return changed;
        }
    }

    private static List<Object> launch(PeriodicComboFightModel.FreshImpulse f) {
        if (f == null) return List.of();
        return List.of(f.time(), f.serverSeparation(), Math.hypot(f.x(), f.z()), f.y(), f.attackerSprint());
    }

    private static int margin(List<DuelEnv.Hit> hits, boolean own, double end) {
        int total = 0;
        for (var hit : hits) if (hit.at() < end) total += hit.sideA() == own ? 1 : -1;
        return total;
    }

    private static List<List<Number>> futures(DuelEnv env, DuelEnv.Game game, Approach scout,
                                              PeriodicComboClock.Observation cut, double end) {
        var result = new ArrayList<List<Number>>();
        var word = BoxingLandingReentry.Adjustment.fromProperty();
        for (var timing : BoxingReentryProtection.timingGrid()) {
            var wtap = BoxingLandingReentry.fork(env, game, scout, cut, BoxingLandingReentry.Start.WTAP, timing, false, null);
            var candidate = BoxingLandingReentry.fork(env, game, scout, cut,
                    BoxingLandingReentry.Start.LANDING_PLUS_ONE, timing, false, word);
            if (!wtap.presses().equals(candidate.presses())) throw new IllegalStateException("Changed physical click schedules");
            if (candidate.activation().isEmpty() && !candidate.hits().equals(wtap.hits()))
                throw new IllegalStateException("A branch changed without activation");
            var a = BoxingReentryProtection.score(wtap, game.first, cut.time());
            var b = BoxingReentryProtection.score(candidate, game.first, cut.time());
            var activation = candidate.activation();
            double age = activation.isEmpty() ? -1 : ((Number) activation.get("time")).doubleValue() - cut.time();
            result.add(List.of(a.margin(), a.chain(), a.firstOwn(), a.firstReturn(), b.margin(), b.chain(),
                    b.firstOwn(), b.firstReturn(), margin(wtap.hits(), game.first, end), margin(candidate.hits(), game.first, end), age));
        }
        return result;
    }

    private static Map<String, Object> test(DuelEnv env, boolean own, List<Integer> phase, int episode, double at,
                                             Path fitted, double range, int move, int heads) throws Exception {
        var scout = new Approach(env.rtt, range, move, heads);
        var game = env.new Game(own, scout, Evolve.WT.create(env.otherRtt, 0));
        if (phase.get(0) != 0) game.clock.shiftFutureClientOpportunities(own, phase.get(0));
        if (phase.get(1) != 0) game.clock.shiftFutureClientOpportunities(!own, phase.get(1));
        game.trace();
        Map<String,Object> data = new LinkedHashMap<>();
        data.put("phase", phase);
        data.put("approach", List.of(range, move, heads));
        data.put("altered", scout.altered);
        double limit = at + MAX_CUT_SHIFT;
        if (!game.clock.advanceToBeforeNextHead(own, limit)) return data;
        do {
            var cut = game.clock.observation(own);
            scout.observe(cut);
            if (scout.episode != episode) continue;
            data.put("at", cut.time());
            data.put("near", Math.abs(cut.time() - at) <= MAX_CUT_SHIFT);
            if (Math.abs(cut.time() - at) > MAX_CUT_SHIFT) return data;
            var state = game.clock.state;
            var other = (own ? state.b : state.a).q;
            data.put("legal", BoxingNaturalReentry.legal(scout, cut));
            data.put("plant", List.of(Math.hypot(state.serverAx - state.serverBx, state.serverAz - state.serverBz),
                    cut.own().y(), other.y(), cut.own().vy(), other.vy(), other.onGround()));
            data.put("launches", own ? List.of(launch(state.lastFreshA), launch(state.lastFreshB)) :
                    List.of(launch(state.lastFreshB), launch(state.lastFreshA)));
            data.put("prefix", List.of(state.acceptedA, state.acceptedB));
            data.put("older", futures(env, game, scout, cut, at + POINT_HORIZON));
            BoxingMirrorForensics.loadPacketModel(state, fitted, "raw-queue");
            data.put("calibrated", futures(env, game, scout, cut, at + POINT_HORIZON));
            return data;
        } while (game.clock.runHeadInterval(own, limit));
        data.put("episodes", scout.episode);
        return data;
    }

    public static void main(String[] args) throws Exception {
        int rtt = Integer.parseInt(args[0]), episode = Integer.parseInt(args[4]);
        boolean own = args[3].equals("a");
        double at = Double.parseDouble(args[5]);
        Path fit = Path.of(args[6]);
        var env = BoxingEdgeHitSelect.environment(Path.of(args[1]), rtt, 180_000,
                Path.of(args[2]).resolve("wire"), Path.of(args[2]).resolve("seeds"));
        for (int i = 7; i < args.length; i++) {
            var cell = args[i].split(",");
            var phase = List.of(Integer.parseInt(cell[0]), Integer.parseInt(cell[1]));
            var data = test(env, own, phase, episode, at, fit, Double.parseDouble(cell[2]),
                    Integer.parseInt(cell[3]), Integer.parseInt(cell[4]));
            System.out.println(Json.encode(data));
        }
    }
}
