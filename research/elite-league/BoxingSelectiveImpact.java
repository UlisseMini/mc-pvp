import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import lab.Json;

/** Isolate the ordinary impact jump from the W-tap release input. */
final class BoxingSelectiveImpact {
    private static final int HORIZON = 180_000;

    private static final class SprintJump extends DuelEnv.Player {
        SprintJump(int rtt) {
            super("reset", 0, rtt, 0);
        }

        SprintJump(DuelEnv.Player history) {
            super("reset", 0, history.rtt, history.wake);
            handled = history.handled;
            releaseUntil = history.releaseUntil;
            firstClick = history.firstClick;
        }

        @Override public PeriodicComboClock.Control input(PeriodicComboClock.Observation own) {
            var stock = super.input(own);
            if (!stock.jump() || stock.key() == PeriodicComboFightModel.S) return stock;
            jumps--;
            return new PeriodicComboClock.Control(stock.key(), stock.strafe(), stock.click(), false);
        }
    }

    private static List<Object> continuation(BoxingDrill.Checkpoint start, boolean selective) {
        var prefix = start.state;
        var a = prefix.a.copy();
        var b = prefix.b.copy();
        if (selective) {
            if (prefix.first) a = new SprintJump(a);
            else b = new SprintJump(b);
        }
        var game = start.env.new Game(prefix, a, b);
        game.trace();
        game.hits.list.addAll(start.committed);
        int cycles = 0, active = 0, seen = 0;
        double lastActive = start.landmark;
        Double returned = null;
        do {
            while (seen < game.hits.list.size()) {
                var h = game.hits.list.get(seen++);
                if (h.sideA() != game.first) {
                    returned = h.at();
                    break;
                }
                if (h.upgrade()) continue;
                if (active == cycles && h.at() - lastActive <= 1000) {
                    lastActive = h.at();
                    active++;
                }
                cycles++;
            }
        } while (returned == null && game.advance());
        double end = returned == null ? game.clock.time() : returned;
        double activityEnd = Math.min(end, lastActive + 1000);
        String terminal = returned == null ? "censored" : "return";
        String activityTerminal = activityEnd < end ? "inactive" : terminal;
        if (!selective && (start.baseline.cycles() != active
                || start.baseline.ms() != activityEnd - start.landmark
                || !start.baseline.terminal().equals(activityTerminal)))
            throw new AssertionError("Unchanged maintenance fork diverged");
        return List.of(active, cycles, activityEnd - start.landmark, end - start.landmark, activityTerminal, terminal);
    }

    public static void main(String[] args) throws Exception {
        var originalReset = Arena.Fighter.named("reset");
        for (int rtt : new int[]{0, 20, 50, 60, 80}) for (String path : args) {
            var env = BoxingDoubleSearch.environment(Path.of(path), rtt, 20, HORIZON);
            var drill = BoxingDoubleSearch.environment(Path.of(path), rtt, 20, 20_000);
            for (boolean first : new boolean[]{true, false}) {
                var player = new SprintJump(rtt);
                var wtap = BoxingDoubleSearch.play(env, first, Evolve.WT, 100);
                var reset = BoxingDoubleSearch.play(env, first, originalReset, 100);
                var selective = BoxingDoubleSearch.play(env, first, player, 100);
                var entries = BoxingDrill.acquire(drill, first, true).stream().map(cp ->
                    Map.of("landmark", cp.k, "wtap", continuation(cp, false),
                            "selective", continuation(cp, true))).toList();
                System.out.println(Json.encode(Map.of("clock", env.clockId, "rtt", rtt, "seat", first ? "a" : "b",
                        "wtap", wtap.values(), "reset", reset.values(), "selective", selective.values(),
                        "jumps", player.jumps, "maintenance", entries)));
            }
        }
    }
}
