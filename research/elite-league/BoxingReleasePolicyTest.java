import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lab.BoxingReleaseHold;
import lab.Json;

/** Test an own-observation release exception in full bouts and matched combo forks. */
final class BoxingReleasePolicyTest {
    private static final double CLOSE_REACH = 1.6;
    private static final boolean ONLY_REGULATION = Boolean.getBoolean("lab.boxing.releaseRegulationOnly");

    private static final class Policy extends DuelEnv.Player {
        final DuelEnv.Player tap;
        final BoxingReleaseHold hold;
        int kept, opportunities;

        Policy(DuelEnv.Player tap, boolean closeGround) {
            super(tap.style, tap.mask, tap.rtt, tap.wake);
            this.tap = tap;
            hold = new BoxingReleaseHold(closeGround ? CLOSE_REACH : Double.POSITIVE_INFINITY);
            firstClick = tap.firstClick;
        }

        boolean release(PeriodicComboClock.Observation own, PeriodicComboClock.Control stock) {
            double cue = own.lastTargetHurtTime();
            var decision = hold.decide(own.time(), own.lastOwnHurtTime(), cue, cue,
                    stock.key() != 0, own.own().onGround(), own.nearest(), true);
            if (decision != BoxingReleaseHold.Decision.STOCK) opportunities++;
            return decision == BoxingReleaseHold.Decision.HOLD;
        }

        @Override public PeriodicComboClock.Control input(PeriodicComboClock.Observation own) {
            var stock = tap.input(own);
            if (!release(own, stock)) return stock;
            kept++;
            return new PeriodicComboClock.Control(2, stock.strafe(), stock.click(), stock.jump());
        }
    }

    private static Map<String,Object> regulation(DuelEnv env, boolean first, boolean closeGround, int own, int other) {
        var policy = new Policy(new DuelEnv.Player("wtap", 0, env.rtt, 0), closeGround);
        var result = new LinkedHashMap<>(BoxingAcquisitionControl.regulation(env, first, policy, own, other));
        result.put("opportunities", policy.opportunities);
        result.put("kept", policy.kept);
        return result;
    }

    private static Map<String,Object> maintenance(BoxingDrill.Checkpoint start, int own, int other, Boolean closeGround) {
        var game = start.suffix(null);
        Policy policy = null;
        if (closeGround != null) {
            policy = new Policy(game.our().copy(), closeGround);
            for (var head : start.history) policy.release(head.view(), head.applied());
            policy.opportunities = 0;
            var prev = game;
            game = start.env.new Game(prev, prev.first ? policy : prev.a.copy(), prev.first ? prev.b.copy() : policy);
            game.trace();
            game.hits.list.addAll(prev.hits.list);
        }
        BoxingAcquisitionControl.shift(game, own, other);
        game.clock.runUntil(start.env.limit);
        return Map.of("score", BoxingLateReturns.score(game, start.landmark, 1100).values(),
                "opportunities", policy == null ? 0 : policy.opportunities, "kept", policy == null ? 0 : policy.kept);
    }

    public static void main(String[] args) throws Exception {
        int rtt = Integer.parseInt(args[0]);
        var wire = Path.of(args[1]);
        var clipped = Path.of(args[2]);
        var seeds = Path.of(args[3]);
        for (int i = 4; i < args.length; i++) {
            var tape = Path.of(args[i]);
            var env = BoxingEdgeHitSelect.environment(tape, rtt, 180_000, wire, seeds);
            var drill = BoxingEdgeHitSelect.environment(tape, rtt, 20_000, clipped, seeds);
            for (boolean first : new boolean[]{true,false}) {
                var starts = ONLY_REGULATION ? List.<BoxingDrill.Checkpoint>of() : BoxingDrill.acquire(drill, first, true);
                for (int own : new int[]{0,-5,5}) for (int other : new int[]{0,-5,5}) {
                    var stock = BoxingAcquisitionControl.regulation(env, first, new DuelEnv.Player("wtap", 0, env.rtt, 0), own, other);
                    var drills = new ArrayList<Map<String,Object>>();
                    for (var cp : starts) {
                        drills.add(Map.of("k", cp.k, "landmark", cp.landmark, "wtap", maintenance(cp, own, other, null),
                                "all", maintenance(cp, own, other, false), "near", maintenance(cp, own, other, true)));
                    }
                    var bout = Map.of("wtap", stock, "all", regulation(env, first, false, own, other),
                            "near", regulation(env, first, true, own, other));
                    System.out.println(Json.encode(Map.of("clock", env.clockId, "seat", first ? "a" : "b", "rtt", rtt,
                            "phase", List.of(own, other), "arms", bout, "maintenance", drills)));
                }
            }
        }
    }
}
