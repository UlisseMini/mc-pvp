import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lab.Json;

/** Compare a brief coast or backstep after first hurt with the same counter clock. */
final class BoxingOpeningFootwork {
    private static final int[][] PHASES = {{0, 0}, {0, 17}, {23, 0}, {34, 11}};
    private static final BoxingTradeSkills.Setting WTAP = new BoxingTradeSkills.Setting("wtap", 0, -1, false);

    private static List<BoxingTradeSkills.Setting> arms(int delay) {
        var result = new ArrayList<BoxingTradeSkills.Setting>();
        for (int key : new int[]{PeriodicComboFightModel.R, PeriodicComboFightModel.BACK}) {
            String movement = key == PeriodicComboFightModel.R ? "coast" : "back";
            for (int steps = 1; steps <= (delay - 50) / 50; steps++) {
                result.add(new BoxingTradeSkills.Setting(movement + steps, 0, delay, false, 0, 0, false,
                        new BoxingTradeSkills.Footwork(key, steps)));
            }
        }
        return result;
    }

    public static void main(String[] args) throws Exception {
        int cps = Integer.parseInt(args[0]);
        for (int i = 1; i < args.length; i++) {
            var tape = DuelEnv.Tape.read(Path.of(args[i]), 12_000);
            for (int[] phase : PHASES) {
                var shifted = BoxingDrill.shift(tape, phase[0], phase[1]);
                for (int rtt : new int[]{0, 20, 80}) for (double gap : new double[]{5, 6, 7}) {
                    var env = BoxingTradeSkills.env(shifted, rtt, cps, gap);
                    for (boolean first : new boolean[]{true, false}) {
                        var mirror = BoxingTradeSkills.play(env, first, WTAP);
                        for (int delay : new int[]{100, 150, 200}) {
                            var clockOnly = new BoxingTradeSkills.Setting("cue-only", 0, delay, false);
                            var control = BoxingTradeSkills.play(env, first, clockOnly);
                            for (var candidate : arms(delay)) {
                                System.out.println(Json.encode(Map.ofEntries(
                                        Map.entry("donor", tape.id()), Map.entry("phase", phase),
                                        Map.entry("cps", cps), Map.entry("rtt", rtt), Map.entry("gap", gap),
                                        Map.entry("seat", first ? "a" : "b"), Map.entry("delay", delay),
                                        Map.entry("arm", candidate.name()), Map.entry("wtap", mirror),
                                        Map.entry("cueOnly", control),
                                        Map.entry("candidate", BoxingTradeSkills.play(env, first, candidate)))));
                            }
                        }
                    }
                }
            }
        }
    }
}
