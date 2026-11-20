import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lab.Json;

/** Change the grounded approach once, keeping the same local hurt-cue reply. */
final class BoxingApproachStudy {
    private static final int[] LATENCIES = {0, 20, 80};
    private static final int[][] PHASES = {{0, 0}, {0, 17}, {23, 0}, {34, 11}};
    private static final BoxingTradeSkills.Setting WTAP = new BoxingTradeSkills.Setting("wtap", 0, -1, false);

    private record Step(String name, int key, float strafe) {}

    record Approach(String name, double range, int heads, int key, float strafe) {}

    static final class Fighter extends BoxingTradeSkills.Fighter {
        private final Approach approach;
        private boolean started;
        private int remaining;
        int steps;

        Fighter(int delay, int ping, Approach approach) {
            super(new BoxingTradeSkills.Setting("hurt+" + delay, 0, delay, false), ping);
            this.approach = approach;
        }

        private Fighter(Fighter source) {
            super(source);
            approach = source.approach;
            started = source.started;
            remaining = source.remaining;
            steps = source.steps;
        }

        @Override Fighter copy() { return new Fighter(this); }

        @Override public PeriodicComboClock.Control input(PeriodicComboClock.Observation obs) {
            var normal = super.input(obs);
            if (normal.click() || Double.isFinite(hurtAt) || !obs.own().onGround()) {
                remaining = 0;
                return normal;
            }
            if (!started && obs.nearest() <= approach.range) {
                started = true;
                remaining = approach.heads;
            }
            if (remaining == 0) return normal;
            remaining--;
            steps++;
            return new PeriodicComboClock.Control(approach.key, approach.strafe, normal.click(), normal.jump());
        }
    }

    private static List<Approach> approaches() {
        var result = new ArrayList<Approach>();
        var steps = List.of(new Step("back", PeriodicComboFightModel.BACK, 0),
                new Step("left", PeriodicComboFightModel.S, .98f),
                new Step("right", PeriodicComboFightModel.S, -.98f));
        for (var step : steps) for (double range : new double[]{3.4, 4.1, 4.8}) {
            for (int heads : new int[]{2, 4}) {
                result.add(new Approach(step.name + "-" + range + "-" + heads, range, heads, step.key, step.strafe));
            }
        }
        return result;
    }

    public static void main(String[] args) throws Exception {
        if (args[0].equals("recoil")) {
            recoil(args);
            return;
        }
        int cps = Integer.parseInt(args[0]);
        double gap = Double.parseDouble(args[1]);
        var approaches = approaches();
        for (int i = 2; i < args.length; i++) {
            var tape = DuelEnv.Tape.read(Path.of(args[i]), 12_000);
            for (int[] phase : PHASES) {
                var shifted = BoxingDrill.shift(tape, phase[0], phase[1]);
                for (int rtt : LATENCIES) {
                    var env = BoxingTradeSkills.env(shifted, rtt, cps, gap);
                    for (boolean first : new boolean[]{true, false}) {
                        var wtap = BoxingTradeSkills.play(env, first, WTAP);
                        for (int delay : new int[]{100, 150}) {
                            var cue = new BoxingTradeSkills.Setting("hurt+" + delay, 0, delay, false);
                            var unchanged = BoxingTradeSkills.play(env, first, cue);
                            for (var approach : approaches) {
                                var fighter = new Fighter(delay, rtt, approach);
                                var score = BoxingTradeSkills.play(env, first, fighter);
                                System.out.println(Json.encode(Map.ofEntries(
                                        Map.entry("donor", tape.id()), Map.entry("phase", phase),
                                        Map.entry("cps", cps), Map.entry("rtt", rtt), Map.entry("gap", gap),
                                        Map.entry("seat", first ? "a" : "b"), Map.entry("delay", delay),
                                        Map.entry("arm", approach.name), Map.entry("steps", fighter.steps),
                                        Map.entry("wtap", wtap), Map.entry("cueOnly", unchanged), Map.entry("candidate", score))));
                            }
                        }
                    }
                }
            }
        }
    }

    private static void recoil(String[] args) throws Exception {
        int delay = Integer.parseInt(args[1]);
        var move = approaches().stream().filter(a -> a.name.equals(args[2])).findFirst().orElseThrow();
        var cue = new BoxingTradeSkills.Setting("hurt+" + delay, 0, delay, false);
        for (int i = 3; i < args.length; i++) {
            var tape = DuelEnv.Tape.read(Path.of(args[i]), 12_000);
            for (int[] phase : PHASES) {
                var shifted = BoxingDrill.shift(tape, phase[0], phase[1]);
                for (int rtt : LATENCIES) {
                    var env = BoxingTradeSkills.env(shifted, rtt, 10, 6);
                    for (boolean first : new boolean[]{true, false}) {
                        var control = BoxingOpeningRecoil.measure(env, first, 10, phase, 6,
                                () -> new BoxingTradeSkills.Fighter(cue, rtt));
                        var candidate = BoxingOpeningRecoil.measure(env, first, 10, phase, 6,
                                () -> new Fighter(delay, rtt, move));
                        System.out.println(Json.encode(Map.of("delay", delay, "arm", move.name,
                                "cueOnly", control, "candidate", candidate)));
                    }
                }
            }
        }
    }
}
