import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lab.Json;

/** Test the frozen W-release rules and W-tap on the same pilot calendars. */
final class BoxingResetClock {
    private static final int[][] PHASES = {{0, 0}, {-5, 0}, {5, 0}, {0, -5}, {0, 5}};
    private static final int REGULATION = 45_000;
    private static final int DRILL = 20_000;

    private static Map<String, Object> continuation(BoxingDrill.Checkpoint start, Arena.Model model,
                                                    int own, int other) {
        var game = start.suffix(model);
        BoxingAcquisitionControl.shift(game, own, other);
        game.clock.runUntil(DRILL);
        return BoxingLateReturns.score(game, start.landmark, 1100).values();
    }

    public static void main(String[] args) throws Exception {
        var regulation = Arena.Model.decode(Files.readString(Path.of(args[0])).strip());
        var constant = Arena.Model.decode(Files.readString(Path.of(args[1])).strip());
        int rtt = Integer.parseInt(args[2]);
        var wire = Path.of(args[3]);
        var drillWire = Path.of(args[4]);
        var seeds = Path.of(args[5]);
        for (int i = 6; i < args.length; i++) {
            var tape = Path.of(args[i]);
            var env = BoxingEdgeHitSelect.environment(tape, rtt, REGULATION, wire, seeds);
            var drill = BoxingEdgeHitSelect.environment(tape, rtt, DRILL, drillWire, seeds);
            for (boolean first : new boolean[]{true, false}) {
                var starts = BoxingDrill.acquire(drill, first, true);
                for (int[] phase : PHASES) {
                    int own = phase[0], other = phase[1];
                    var stock = new DuelEnv.Player("wtap", 0, rtt, 0);
                    var arms = Map.of(
                            "wtap", BoxingAcquisitionControl.regulation(env, first, stock, own, other),
                            "distance", BoxingAcquisitionControl.regulation(env, first, new Arena.Neural(regulation, rtt), own, other),
                            "constant", BoxingAcquisitionControl.regulation(env, first, new Arena.Neural(constant, rtt), own, other));
                    var drills = new ArrayList<Map<String, Object>>();
                    for (var start : starts) {
                        drills.add(Map.of("k", start.k, "landmark", start.landmark,
                                "wtap", continuation(start, null, own, other),
                                "distance", continuation(start, regulation, own, other),
                                "constant", continuation(start, constant, own, other)));
                    }
                    System.out.println(Json.encode(Map.of("clock", env.clockId, "rtt", rtt,
                            "seat", first ? "a" : "b", "phase", List.of(own, other),
                            "arms", arms, "maintenance", drills)));
                }
            }
        }
    }
}
