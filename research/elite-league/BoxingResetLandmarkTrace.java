import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import lab.Json;

/** Compare unchanged W-tap and the frozen reset durations from one complete K3 state. */
final class BoxingResetLandmarkTrace {
    public static void main(String[] args) throws Exception {
        var tape = Path.of(args[0]);
        boolean first = args[1].equals("a");
        int rtt = Integer.parseInt(args[2]);
        var wire = Path.of(args[3]);
        var seeds = Path.of(args[4]);
        var env = BoxingEdgeHitSelect.environment(tape, rtt, 20_000, wire, seeds);
        var start = BoxingDrill.acquire(env, first, false).stream().findFirst().orElseThrow();
        var arms = new LinkedHashMap<String, Object>();
        arms.put("wtap", BoxingLateReturns.trace(start.suffix(null), start.landmark, env.limit, 1100));
        for (int i = 5; i < args.length; i++) {
            var path = Path.of(args[i]);
            var model = Arena.Model.decode(Files.readString(path).strip());
            arms.put(path.getFileName().toString(), BoxingLateReturns.trace(
                    start.suffix(model), start.landmark, env.limit, 1100));
        }
        System.out.println(Json.encode(Map.of("clock", env.clockId, "seat", args[1],
                "rtt", rtt, "landmark", start.landmark, "arms", arms)));
    }
}
