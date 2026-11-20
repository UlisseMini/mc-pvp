import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lab.Json;

/** Offline bound on return denial using one or two ordinary inputs before the next hit. */
final class BoxingReleaseDefense {
    private static final boolean WIDE = Boolean.getBoolean("lab.boxing.releaseLongDefense");
    private record Cut(String id, String clock, boolean first, BoxingLaterTrades.Use source, int[] prefixPhase,
                       double time, int[] phase, int early, int late, int earliest) {}

    private enum Step {
        COAST(0, 0), WALK(1, 0), SPRINT(2, 0), BACK(3, 0), LEFT(-1, .98f), RIGHT(-1, -.98f);

        final int key;
        final float side;
        Step(int key, float side) { this.key = key; this.side = side; }

        PeriodicComboClock.Control apply(PeriodicComboClock.Control stock) {
            return new PeriodicComboClock.Control(key < 0 ? stock.key() : key, side, stock.click(), stock.jump());
        }
    }

    private record Pulse(String name, List<Integer> heads, Step step, Step last) {
        Step at(int head) {
            if (!heads.contains(head)) return null;
            return head == heads.get(heads.size() - 1) ? last : step;
        }
    }

    private static List<Cut> read(Path path) throws Exception {
        var cuts = new ArrayList<Cut>();
        for (String line : Files.readAllLines(path)) {
            String[] t = line.split("\t");
            if (t.length != (WIDE ? 12 : 11)) throw new IllegalArgumentException("Invalid defense cut: " + line);
            cuts.add(new Cut(t[0], t[1], t[2].equals("a"), BoxingLaterTrades.Use.valueOf(t[3]),
                    new int[]{Integer.parseInt(t[4]),Integer.parseInt(t[5])}, Double.parseDouble(t[6]),
                    new int[]{Integer.parseInt(t[7]),Integer.parseInt(t[8])}, Integer.parseInt(t[9]), Integer.parseInt(t[10]),
                    WIDE ? Integer.parseInt(t[11]) : -1));
        }
        return cuts;
    }

    private static List<Pulse> pulses(Cut cut) {
        var result = new ArrayList<Pulse>();
        for (Step step : Step.values()) {
            result.add(new Pulse("late-" + step, List.of(cut.late), step, step));
            if (cut.early > 0) {
                result.add(new Pulse("early-" + step, List.of(cut.early), step, step));
                result.add(new Pulse("both-" + step, List.of(cut.early, cut.late), step, step));
            }
        }
        if (WIDE) for (int length : new int[]{3,4,5}) {
            int start = cut.late - length + 1;
            if (start < cut.earliest) continue;
            var heads = new ArrayList<Integer>();
            for (int index = start; index <= cut.late; index++) heads.add(index);
            for (Step step : List.of(Step.COAST, Step.BACK, Step.LEFT, Step.RIGHT))
                result.add(new Pulse(length + "-" + step, heads, step, step));
            for (Step step : List.of(Step.COAST, Step.BACK))
                result.add(new Pulse(length + "-" + step + "+SPRINT", heads, step, Step.SPRINT));
        }
        return result;
    }

    private static Map<String,Object> describe(BoxingReleaseDecision.Evidence result, List<Object> movement) {
        return Map.of("hits", result.result().get("hits"), "contacts", result.contacts(), "movement", movement);
    }

    private static void evaluate(DuelEnv env, Arena.Model model, Cut cut) {
        var prefix = BoxingLaterTrades.start(env, model, cut.first, cut.source, cut.prefixPhase[0], cut.prefixPhase[1]);
        prefix.clock.runUntil(Math.nextDown(cut.time));
        if (!prefix.clock.advanceToBeforeNextHead(cut.first, cut.time) || prefix.clock.time() != cut.time)
            throw new AssertionError("The registered release decision did not replay: " + cut.id);
        var tap = ((BoxingLaterTrades.Trial) prefix.our()).player.tap;
        if (tap.copy().input(prefix.clock.observation(cut.first)).key() != 0)
            throw new AssertionError("Original W-tap would not release at the registered cut");
        var baseline = BoxingReleaseDecision.run(env, prefix, tap, BoxingReleaseDecision.Choice.WTAP, cut.phase);
        var sprint = BoxingReleaseDecision.run(env, prefix, tap, BoxingReleaseDecision.Choice.SPRINT, cut.phase);
        if (!baseline.presses().equals(sprint.presses())) throw new AssertionError("Reference click times diverged");
        var arms = new LinkedHashMap<String,Object>();
        arms.put("WTAP", describe(baseline,List.of()));
        arms.put("SPRINT", describe(sprint,List.of()));
        for (Pulse pulse : pulses(cut)) {
            var actions = new ArrayList<Object>();
            var trial = BoxingReleaseDecision.adjusted(env, prefix, tap, cut.phase, (index, own, stock) -> {
                Step step = pulse.at(index);
                if (step == null) return stock;
                var action = step.apply(stock);
                actions.add(Map.of("head", index, "at", own.time(), "from", stock.key(), "to", action.key(),
                        "strafe", action.strafe(), "nearest", own.nearest(), "grounded", own.own().onGround()));
                return action;
            });
            if (actions.size() != pulse.heads.size() || !baseline.presses().equals(trial.presses()))
                throw new AssertionError("Input count or physical press timing changed");
            arms.put(pulse.name, describe(trial,actions));
        }
        System.out.println(Json.encode(Map.of("id", cut.id, "clock", cut.clock, "at", cut.time,
                "rtt", env.rtt, "seat", cut.first ? "a" : "b", "source", cut.source.toString(),
                "phase", cut.prefixPhase, "timing", cut.phase, "arms", arms)));
    }

    public static void main(String[] args) throws Exception {
        var model = Arena.Model.decode(Files.readString(Path.of(args[0])).strip());
        int rtt = Integer.parseInt(args[1]);
        var cases = read(Path.of(args[2]));
        var wire = Path.of(args[3]);
        var seeds = Path.of(args[4]);
        for (int i = 5; i < args.length; i++) {
            var env = BoxingEdgeHitSelect.environment(Path.of(args[i]),rtt,180_000,wire,seeds);
            for (Cut cut : cases) if (cut.clock.equals(env.clockId)) evaluate(env,model,cut);
        }
    }
}
