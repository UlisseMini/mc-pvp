import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import lab.DuelNetwork;
import lab.Json;

/** Diagnose fights where neither side has taken a hit after the opening. */
final class OpeningContactStudy {
    static final List<String> WORDS = List.of("unchanged", "walk-4", "sprint-4", "sprint-8", "sprint-16", "wtap");

    static final List<String> CLICK_WORDS = List.of("unchanged", "wtap", "click-1", "click-3", "click-6", "click-12",
            "back-4-then-click-3", "back-4-then-click-6");
    static final int[] DURATIONS = {0,4,4,8,16,0,1,3,6,12,7,10};
    static boolean clickStudy;

    static final class Probe extends DuelEnv.Player {
        final Arena.Neural actor;
        final DuelEnv.Player wtap;
        int choice, remaining;
        double nearest, firstCue = -1;

        Probe(Arena.Model model, int rtt) {
            super("opening-probe", 0, rtt, 0);
            actor = new Arena.Neural(model, rtt);
            wtap = new DuelEnv.Player("wtap", 0, rtt, 0);
        }

        Probe(Probe other) {
            super(other.style, 0, other.rtt, 0);
            actor = other.actor.copy();
            wtap = other.wtap.copy();
            choice = other.choice;
            remaining = other.remaining;
            nearest = other.nearest;
            firstCue = other.firstCue;
            firstClick = other.firstClick;
        }

        @Override Probe copy() { return new Probe(this); }

        @Override public PeriodicComboClock.Control input(PeriodicComboClock.Observation o) {
            var a = actor.input(o);
            var w = wtap.input(o);
            nearest = o.nearest();
            if (firstCue < 0 && (Double.isFinite(o.lastOwnHurtTime()) || Double.isFinite(o.lastTargetHurtTime()))) {
                firstCue = o.time();
            }
            if (choice == 5) a = w;
            if (remaining > 0) {
                remaining--;
                if (choice <= 4) {
                    a = new PeriodicComboClock.Control(choice == 1 ? 1 : 2, a.strafe(), a.click(), a.jump());
                } else {
                    boolean retreat = choice >= 10 && DURATIONS[choice] - remaining <= 4;
                    a = new PeriodicComboClock.Control(retreat ? 3 : a.key(), a.strafe(), !retreat, a.jump());
                }
            }
            actor.net.applied(DuelNetwork.pack(a.key(), (int)Math.signum(a.strafe()), a.click(), a.jump()));
            return a;
        }
    }

    static List<Object> snapshot(DuelEnv.Game g) {
        var s = g.clock.state;
        var own = g.first ? s.serverA : s.serverB;
        var other = g.first ? s.serverB : s.serverA;
        var a = g.clock.appliedControl(g.first);
        var b = g.clock.appliedControl(!g.first);
        return List.of(g.clock.time(), Math.hypot(own.x() - other.x(), own.z() - other.z()),
                ((Probe)g.our()).nearest, a.key(), a.strafe(), a.click(), b.key(), b.strafe(), b.click());
    }

    static void play(DuelEnv env, Arena.Fighter arm, Arena.Fighter foe, boolean first) throws Exception {
        var player = new Probe(arm.model(), env.rtt);
        var g = env.new Game(first, player, foe.create(env.rtt, 0));
        var row = new LinkedHashMap<String, Object>();
        row.put("arm", arm.name());
        row.put("opponent", foe.name());
        row.put("clock", env.clockId);
        row.put("side", first ? "a" : "b");
        row.put("rtt", env.rtt);
        var snapshots = new java.util.ArrayList<List<Object>>();
        for (int landmark : new int[]{250, 500, 1000, 1500, 2000}) {
            while (g.clock.time() < landmark && g.advance()) {}
            snapshots.add(snapshot(g));
        }
        row.put("snapshots", snapshots);
        var branches = new LinkedHashMap<String, Object>();
        if (g.winner() < 0 && player.firstCue < 0) {
            String start = TradeWords.stateHash(g);
            row.put("fork_sha256", start);
            var words = clickStudy ? CLICK_WORDS : WORDS;
            for (int i = 0; i < words.size(); i++) {
                var branch = env.new Game(g, g.a.copy(), g.b.copy());
                if (i == 0 && !TradeWords.stateHash(branch).equals(start)) throw new AssertionError("Changed fork");
                var p = (Probe)branch.our();
                p.choice = clickStudy && i > 0 ? i + 4 : i;
                p.remaining = DURATIONS[p.choice];
                branch.trace();
                branch.finish();
                var hit = branch.hits.list.stream().findFirst();
                branches.put(words.get(i), List.of(branch.reward(), branch.clock.time(),
                        hit.map(DuelEnv.Hit::at).orElse(-1.), hit.map(h -> h.sideA() == first).orElse(false)));
                if (i == 0) row.put("unchanged_terminal_sha256", TradeWords.stateHash(branch));
            }
            if (!start.equals(TradeWords.stateHash(g))) throw new AssertionError("Mutated shared history");
            g.trace();
            g.finish();
            if (!row.get("unchanged_terminal_sha256").equals(TradeWords.stateHash(g)))
                throw new AssertionError("No-op continuation diverged");
        } else g.finish();
        row.put("branches", branches);
        row.put("checkpoint_score", g.reward());
        row.put("base_fresh", List.of(g.clock.state.hits, g.clock.state.incomingHits));
        var reference = new Arena.Scenario(env, foe, first).round(Arena.Fighter.named("wtap"));
        row.put("plain_wtap", reference.reward());
        System.out.println(Json.encode(row));
    }

    public static void main(String[] args) throws Exception {
        Arena.Fighter arm = Matches.roster(Path.of(args[0])).get(0);
        var foes = Matches.roster(Path.of(args[1]));
        clickStudy = args[2].equals("--clicks");
        for (var env : Arena.envs(Arrays.stream(args, clickStudy ? 3 : 2, args.length).map(Path::of).toList(), "mmc", true)) {
            env.initial.timing.mouseInput = true;
            for (var foe : foes) for (boolean first : new boolean[]{true,false}) play(env, arm, foe, first);
        }
    }
}
