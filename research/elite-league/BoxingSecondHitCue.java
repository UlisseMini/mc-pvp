import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lab.Json;

/** Matched diagnostic for substituting a delivered hurt cue for known K2 timing. */
final class BoxingSecondHitCue {
    private static final double HORIZON = 2200;
    private static final double MAX_CONTACT_AGE = 185;
    private static final double MAX_CUE_GAP = 1000;

    private enum Arm {
        ANCHOR, LOCAL, GEOMETRY
    }

    private static final class Cues {
        double lastTarget = Double.NEGATIVE_INFINITY;
        double lastOwn = Double.NEGATIVE_INFINITY;
        int chain;

        boolean update(PeriodicComboClock.Observation o) {
            boolean target = o.lastTargetHurtTime() > lastTarget;
            boolean own = o.lastOwnHurtTime() > lastOwn;
            if (own) chain = 0;
            if (target) {
                if (o.lastTargetHurtTime() - lastTarget > MAX_CUE_GAP) chain = 0;
                double age = o.lastTargetHurtTime() - o.lastContactTime();
                // The latest observed contact can follow receipt between heads.
                if (!own && Double.isFinite(age) && age >= -75 && age <= MAX_CONTACT_AGE) chain++;
                else chain = 0;
                lastTarget = o.lastTargetHurtTime();
            }
            lastOwn = o.lastOwnHurtTime();
            return target;
        }
    }

    private static final class CueMovement extends DuelEnv.Player {
        final Arm arm;
        final double anchor;
        final Cues cues = new Cues();
        PeriodicComboClock.Observation trigger;
        int remaining;

        CueMovement(DuelEnv.Player prior, List<BoxingDrill.Head> history, Arm arm, double anchor) {
            super(prior.style, prior.mask, prior.rtt, prior.wake);
            handled = prior.handled;
            releaseUntil = prior.releaseUntil;
            firstClick = prior.firstClick;
            this.arm = arm;
            this.anchor = anchor;
            for (var head : history) cues.update(head.view());
        }

        private boolean ready(PeriodicComboClock.Observation o) {
            boolean target = cues.update(o);
            if (!target) return false;
            if (arm == Arm.ANCHOR) return o.lastTargetHurtTime() >= anchor;
            if (cues.chain != 2) return false;
            if (arm == Arm.LOCAL) return true;
            double height = o.targetY() - o.own().y();
            double nearest = o.nearest();
            return o.own().onGround() && o.sprint() && nearest >= 2.45 && nearest <= 2.95
                    && height >= .55 && height <= 1.05;
        }

        @Override public PeriodicComboClock.Control input(PeriodicComboClock.Observation o) {
            var normal = super.input(o);
            if (trigger == null && ready(o)) {
                trigger = o;
                remaining = 3;
            }
            if (remaining == 0) return normal;
            return switch (remaining--) {
                case 3 -> new PeriodicComboClock.Control(PeriodicComboFightModel.BACK, 0, normal.click(), normal.jump());
                case 2 -> new PeriodicComboClock.Control(PeriodicComboFightModel.S, .98f, normal.click(), normal.jump());
                default -> new PeriodicComboClock.Control(PeriodicComboFightModel.W, 0, normal.click(), normal.jump());
            };
        }

        Map<String, Object> triggerValues(double start) {
            if (trigger == null) return Map.of();
            var values = new LinkedHashMap<String, Object>();
            values.put("at", trigger.time() - start);
            values.put("targetHurt", trigger.lastTargetHurtTime() - start);
            values.put("height", trigger.targetY() - trigger.own().y());
            values.put("nearest", trigger.nearest());
            values.put("ground", trigger.own().onGround());
            values.put("sprint", trigger.sprint());
            values.put("replicaHurtAge", trigger.time() - trigger.lastTargetHurtTime());
            values.put("ownHurtAge", age(trigger.lastOwnHurtTime()));
            values.put("latestContactAge", age(trigger.lastContactTime()));
            values.put("cueChain", cues.chain);
            return values;
        }

        private Double age(double event) {
            return Double.isFinite(event) ? trigger.time() - event : null;
        }
    }

    private static Map<String, Object> run(BoxingDrill.Checkpoint start, int own, int defender, double end) {
        var control = start.suffix(null);
        if (own != 0) control.clock.shiftFutureClientOpportunities(control.first, own);
        if (defender != 0) control.clock.shiftFutureClientOpportunities(!control.first, defender);
        var treatments = new ArrayList<DuelEnv.Game>();
        var movers = new ArrayList<CueMovement>();
        for (var arm : Arm.values()) {
            var movement = new CueMovement(control.our(), start.history, arm,
                    arm == Arm.ANCHOR ? start.landmark : Double.NaN);
            var g = start.env.new Game(control, control.first ? movement : control.a.copy(),
                    control.first ? control.b.copy() : movement);
            g.trace();
            g.hits.list.addAll(control.hits.list);
            treatments.add(g);
            movers.add(movement);
        }
        control.clock.runUntil(end);
        var baseline = BoxingLateReturns.score(control, start.landmark);
        var result = new LinkedHashMap<String, Object>();
        result.put("phase", List.of(own, defender));
        result.put("wtap", baseline.values());
        for (int i = 0; i < treatments.size(); i++) {
            var match = treatments.get(i);
            match.clock.runUntil(end);
            var score = BoxingLateReturns.score(match, start.landmark);
            var mover = movers.get(i);
            if (mover.trigger == null && !score.equals(baseline)) throw new AssertionError("Unarmed policy changed W-tap");
            double trigger = mover.trigger == null ? Double.POSITIVE_INFINITY : mover.trigger.time() - start.landmark;
            boolean beforeReturn = mover.trigger != null
                    && (baseline.returnOnlyEnd().equals("censored") || trigger < baseline.returnOnlyMs());
            result.put(mover.arm.name().toLowerCase(java.util.Locale.ROOT),
                    Map.of("score", score.values(), "trigger", mover.triggerValues(start.landmark),
                            "triggerBeforeWtapReturn", beforeReturn,
                            "deltaActive", score.active() - baseline.active(),
                            "deltaMargin", score.margin() - baseline.margin()));
        }
        return result;
    }

    public static void main(String[] args) throws Exception {
        boolean exact = Boolean.getBoolean("lab.boxing.cueExactAnchor");
        var wires = Path.of(args[0]);
        var seeds = Path.of(args[1]);
        for (int i = 2; i < args.length; i++) {
            var env = BoxingDoubleSearch.environment(Path.of(args[i]), 80, 20, 20_000);
            BoxingJumpTimingAudit.wireTiming(env, wires.resolve(env.clockId + ".txt"));
            BoxingJumpTimingAudit.seedTracker(env, seeds.resolve(env.clockId + ".txt"));
            for (boolean first : new boolean[]{true, false}) {
                for (var stamp : BoxingDrill.secondHits(env, first)) {
                    var start = exact ? BoxingDrill.exactSecondHit(env, first, stamp.landmark) : stamp;
                    double end = start.landmark + HORIZON;
                    if (end > env.limit) continue;
                    var scenarios = new ArrayList<Map<String, Object>>();
                    for (int own : new int[]{0, 5, -5}) {
                        for (int defender : new int[]{0, 10, -10}) scenarios.add(run(start, own, defender, end));
                    }
                    // The default arm must be unchanged by the action enumeration.
                    var ordinary = start.suffix(null);
                    ordinary.clock.runUntil(end);
                    if (!BoxingLateReturns.score(ordinary, start.landmark).values().equals(scenarios.get(0).get("wtap")))
                        throw new AssertionError("Nominal W-tap diverged");
                    System.out.println(Json.encode(Map.of("clock", env.clockId, "seat", first ? "a" : "b",
                            "k2", start.landmark, "stateAt", start.state.clock.time(), "scenarios", scenarios)));
                }
            }
        }
    }
}
