import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiPredicate;
import lab.ComboContinuationGate;
import lab.DuelNetwork;
import lab.Json;

/** Test whether existing movement helps through acquisition while W-tap continues the combo. */
final class BoxingAcquisitionControl {
    private static final double ACTIVE_GAP = 1100;
    private static final int[] CUE_THRESHOLDS = {1, 2, 3};
    private static final boolean REGULATION_ONLY = Boolean.getBoolean("lab.boxing.regulationOnly");
    private static final boolean TRACE_REGULATION = Boolean.getBoolean("lab.boxing.traceRegulation");
    private static final boolean TEST_ENTRY_DEFICIT = Boolean.getBoolean("lab.boxing.testEntryDeficit");
    private static final boolean TRACE_ENTRY = Boolean.getBoolean("lab.boxing.traceEntry");
    private static final int[] ENTRY_DEFICITS = {1, 2, 4};

    static final class Acquisition extends DuelEnv.Player {
        private static final BiPredicate<PeriodicComboClock.Control, PeriodicComboClock.Control> ALL = (stock, proposed) -> true;
        final Arena.Neural learned;
        final DuelEnv.Player tap;
        final ComboContinuationGate continuation;
        final int entryDeficit;
        final double learnedWindow;
        double newestIncoming = Double.NEGATIVE_INFINITY, newestTarget = Double.NEGATIVE_INFINITY;
        double firstChange = Double.POSITIVE_INFINITY;
        int localDeficit;
        boolean armed;
        int tapHeads, learnedHeads;
        Map<String, Object> entry = Map.of();

        Acquisition(Arena.Neural learned, DuelEnv.Player tap, int threshold) {
            this(learned, tap, threshold, 0);
        }

        Acquisition(Arena.Neural learned, DuelEnv.Player tap, int threshold, int entryDeficit) {
            this(learned, tap, threshold, entryDeficit, Double.POSITIVE_INFINITY);
        }

        Acquisition(Arena.Neural learned, DuelEnv.Player tap, int threshold, int entryDeficit,
                    double learnedWindow) {
            super("wtap", 0, tap.rtt, tap.wake);
            this.learned = learned;
            this.tap = tap;
            continuation = new ComboContinuationGate(threshold);
            this.entryDeficit = entryDeficit;
            this.learnedWindow = learnedWindow;
            armed = entryDeficit == 0;
            firstClick = tap.firstClick;
        }

        private Acquisition(Acquisition original) {
            super(original.style, original.mask, original.rtt, original.wake);
            learned = original.learned.copy();
            tap = original.tap.copy();
            continuation = original.continuation.copy();
            entryDeficit = original.entryDeficit;
            learnedWindow = original.learnedWindow;
            firstClick = original.firstClick;
            newestIncoming = original.newestIncoming;
            newestTarget = original.newestTarget;
            firstChange = original.firstChange;
            localDeficit = original.localDeficit;
            armed = original.armed;
            tapHeads = original.tapHeads;
            learnedHeads = original.learnedHeads;
            entry = original.entry;
        }

        @Override Acquisition copy() { return new Acquisition(this); }

        boolean observe(PeriodicComboClock.Observation obs) {
            if (!armed) {
                if (obs.lastOwnHurtTime() > newestIncoming) {
                    newestIncoming = obs.lastOwnHurtTime();
                    localDeficit++;
                }
                if (obs.lastTargetHurtTime() > newestTarget) {
                    newestTarget = obs.lastTargetHurtTime();
                    localDeficit--;
                }
                armed = localDeficit >= entryDeficit;
            }
            return continuation.observe(obs.time(), obs.lastOwnHurtTime(), obs.lastTargetHurtTime());
        }

        @Override public PeriodicComboClock.Control input(PeriodicComboClock.Observation obs) {
            return input(obs, false);
        }

        PeriodicComboClock.Control input(PeriodicComboClock.Observation obs, boolean useStock) {
            return input(obs, useStock, ALL);
        }

        PeriodicComboClock.Control input(PeriodicComboClock.Observation obs, boolean useStock,
                                        BiPredicate<PeriodicComboClock.Control, PeriodicComboClock.Control> allow) {
            boolean continueWithWtap = observe(obs);
            var stock = tap.input(obs);
            var candidate = learned.input(obs);
            if (useStock || !armed || continueWithWtap || obs.time() > firstChange + learnedWindow || !allow.test(stock, candidate)) {
                tapHeads++;
                learned.net.applied(DuelNetwork.pack(stock.key(), (int) Math.signum(stock.strafe()), stock.click(), stock.jump()));
                return stock;
            }
            if (TRACE_ENTRY && !Double.isFinite(firstChange) && !stock.equals(candidate)) {
                firstChange = obs.time();
                var p = obs.own();
                double dx = obs.targetX() - p.x(), dz = obs.targetZ() - p.z();
                double horizontal = Math.hypot(dx, dz);
                entry = Map.of("at", firstChange, "nearest", obs.nearest(), "horizontal", horizontal,
                        "heightDifference", obs.targetY() - p.y(), "grounded", p.onGround(), "sprint", obs.sprint(),
                        "toward", horizontal == 0 ? 0 : (p.vx() * dx + p.vz() * dz) / horizontal,
                        "deficit", localDeficit, "stock", control(stock), "learned", control(candidate));
            }
            learnedHeads++;
            return candidate;
        }

        private static List<Object> control(PeriodicComboClock.Control input) {
            return List.of(input.key(), input.strafe(), input.click(), input.jump());
        }
    }

    static void shift(DuelEnv.Game game, int own, int other) {
        if (own != 0) game.clock.shiftFutureClientOpportunities(game.first, own);
        if (other != 0) game.clock.shiftFutureClientOpportunities(!game.first, other);
    }

    static Map<String, Object> regulation(DuelEnv env, boolean first, DuelEnv.Player player,
                                         int ownPhase, int otherPhase) {
        var game = env.new Game(first, player, new DuelEnv.Player("wtap", 0, env.rtt, 0));
        game.trace();
        shift(game, ownPhase, otherPhase);
        var presses = List.of(new ArrayList<Double>(), new ArrayList<Double>());
        game.clock.setClickAudit(event -> {
            if (!event.action().equals("ADMIT")) return;
            var times = presses.get(event.fromA() == game.first ? 0 : 1);
            double at = event.admittedNanos() / 1e6;
            if (!times.isEmpty() && at - times.get(times.size()-1) < 50 - 1e-6) {
                throw new AssertionError("CPS cap violated");
            }
            times.add(at);
        });
        var score = BoxingDoubleSearch.play(game, 100);
        double end = BoxingEdgeHitSelect.decidingHit(game);
        double begin = presses.stream().mapToDouble(list -> list.isEmpty() ? end : list.get(0)).max().orElseThrow();
        var cps = presses.stream().map(times -> 1000 * times.stream().filter(t -> t >= begin && t <= end).count() / (end-begin)).toList();
        var output = new LinkedHashMap<String, Object>(Map.of("scores", score.values(), "cps", cps, "finish", end,
                "tappedHeads", player instanceof Acquisition a ? a.tapHeads : 0,
                "learnedHeads", player instanceof Acquisition a ? a.learnedHeads : 0));
        if (TRACE_ENTRY && player instanceof Acquisition a) output.put("entry", a.entry);
        if (TRACE_REGULATION || TRACE_ENTRY) {
            var hits = new ArrayList<List<Object>>();
            int[] tally = new int[2];
            for (var hit : game.hits.list) {
                boolean ours = hit.sideA() == game.first;
                hits.add(List.of(hit.at(), ours, hit.upgrade()));
                if (++tally[ours ? 0 : 1] == 100) break;
            }
            if (tally[0] != score.own() || tally[1] != score.other()) throw new AssertionError("Trace and score disagree");
            output.put("hits", hits);
        }
        return output;
    }

    private static Map<String, Object> entryStudy(DuelEnv env, Arena.Model model, boolean first, int own, int other) {
        var tap = regulation(env, first, new DuelEnv.Player("wtap", 0, env.rtt, 0), own, other);
        var full = new Acquisition(new Arena.Neural(model, env.rtt), new DuelEnv.Player("wtap", 0, env.rtt, 0), 2, 1);
        var brief = new Acquisition(new Arena.Neural(model, env.rtt), new DuelEnv.Player("wtap", 0, env.rtt, 0), 2, 1, ACTIVE_GAP);
        return Map.of("clock", env.clockId, "rtt", env.rtt, "seat", first ? "a" : "b", "phase", List.of(own, other),
                "wtap", tap, "full", regulation(env, first, full, own, other),
                "brief", regulation(env, first, brief, own, other));
    }

    private static Map<String, Object> maintenance(BoxingDrill.Checkpoint checkpoint, Arena.Model model,
                                                    int threshold, int ownPhase, int otherPhase) {
        return maintenance(checkpoint, model, threshold, 0, ownPhase, otherPhase);
    }

    private static Map<String, Object> maintenance(BoxingDrill.Checkpoint checkpoint, Arena.Model model,
                                                    int threshold, int deficit, int ownPhase, int otherPhase) {
        var branch = checkpoint.suffix(model);
        Acquisition player = null;
        if (threshold > 0) {
            player = new Acquisition((Arena.Neural) branch.our(), checkpoint.state.our().copy(), threshold, deficit);
            for (var head : checkpoint.history) player.observe(head.view());
            var original = branch;
            branch = checkpoint.env.new Game(original, original.first ? player : original.a.copy(),
                    original.first ? original.b.copy() : player);
            branch.trace();
            branch.hits.list.addAll(original.hits.list);
        }
        shift(branch, ownPhase, otherPhase);
        branch.clock.runUntil(checkpoint.env.limit);
        return Map.of("score", BoxingLateReturns.score(branch, checkpoint.landmark, ACTIVE_GAP).values(),
                "tappedHeads", player == null ? 0 : player.tapHeads, "learnedHeads", player == null ? 0 : player.learnedHeads);
    }

    public static void main(String[] args) throws Exception {
        var model = Arena.Model.decode(Files.readString(Path.of(args[0])).strip());
        int rtt = Integer.parseInt(args[1]);
        var wire = Path.of(args[2]);
        var clipped = Path.of(args[3]);
        var seeds = Path.of(args[4]);
        for (int i = 5; i < args.length; i++) {
            var tape = Path.of(args[i]);
            var regulation = BoxingEdgeHitSelect.environment(tape, rtt, 180_000, wire, seeds);
            var drill = BoxingEdgeHitSelect.environment(tape, rtt, 20_000, clipped, seeds);
            for (boolean first : new boolean[]{true,false}) {
                List<BoxingDrill.Checkpoint> checkpoints = REGULATION_ONLY || TRACE_ENTRY
                        ? List.of() : BoxingDrill.acquire(drill, first, true);
                for (int own : new int[]{0,-5,5}) for (int other : new int[]{0,-5,5}) {
                    if (TRACE_ENTRY) {
                        System.out.println(Json.encode(entryStudy(regulation, model, first, own, other)));
                        continue;
                    }
                    var tap = regulation(regulation, first, new DuelEnv.Player("wtap", 0, rtt, 0), own, other);
                    var frozen = regulation(regulation, first, new Arena.Neural(model, rtt), own, other);
                    var mixed = new ArrayList<Map<String,Object>>();
                    for (int threshold : CUE_THRESHOLDS) {
                        var policy = new Acquisition(new Arena.Neural(model, rtt),
                                new DuelEnv.Player("wtap", 0, rtt, 0), threshold);
                        mixed.add(regulation(regulation, first, policy, own, other));
                    }
                    var combo = new ArrayList<Map<String,Object>>();
                    var deficitArms = new ArrayList<Map<String,Object>>();
                    if (TEST_ENTRY_DEFICIT) for (int deficit : ENTRY_DEFICITS) {
                        var policy = new Acquisition(new Arena.Neural(model, rtt),
                                new DuelEnv.Player("wtap", 0, rtt, 0), 2, deficit);
                        deficitArms.add(regulation(regulation, first, policy, own, other));
                    }
                    for (var cp : checkpoints) {
                        var continuation = new ArrayList<Map<String,Object>>();
                        for (int threshold : CUE_THRESHOLDS) continuation.add(maintenance(cp,model,threshold,own,other));
                        var row = new LinkedHashMap<String, Object>(Map.of("k", cp.k, "landmark", cp.landmark,
                                "wtap", maintenance(cp,null,0,own,other),
                                "movement", maintenance(cp,model,0,own,other), "mixed", continuation));
                        if (TEST_ENTRY_DEFICIT) {
                            var tested = new ArrayList<Map<String,Object>>();
                            for (int deficit : ENTRY_DEFICITS) tested.add(maintenance(cp,model,2,deficit,own,other));
                            row.put("deficitArms", tested);
                        }
                        combo.add(row);
                    }
                    var row = new LinkedHashMap<String, Object>(Map.of("clock",regulation.clockId,"rtt",rtt,"seat",first?"a":"b",
                            "phase",List.of(own,other),"wtap",tap,"movement",frozen,
                            "thresholds",CUE_THRESHOLDS,"mixed",mixed,"maintenance",combo));
                    if (TEST_ENTRY_DEFICIT) {
                        row.put("deficits", ENTRY_DEFICITS);
                        row.put("deficitArms", deficitArms);
                    }
                    System.out.println(Json.encode(row));
                }
            }
        }
    }
}
