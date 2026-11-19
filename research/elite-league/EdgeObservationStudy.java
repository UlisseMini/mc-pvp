import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import lab.Json;

/** Observe the default controller from its first outward movement at each radius to 500 ms later. */
final class EdgeObservationStudy {
    static final class Observer extends DuelEnv.Player {
        final DuelEnv.Player controller;
        final double[] radii = {3.5, 5.0};
        final double[] start = {Double.NaN, Double.NaN};
        final boolean[] done = new boolean[2];
        final List<List<List<Object>>> samples = List.of(new ArrayList<>(), new ArrayList<>());
        double cx, cz;
        int heads;
        Observer(Arena.Fighter fighter, int rtt) {
            super("edge-observer", 0, rtt, 0);
            controller = fighter.create(rtt, 0);
        }
        @Override public PeriodicComboClock.Control input(PeriodicComboClock.Observation o) {
            var own = o.own();
            var a = controller.input(o);
            if (++heads == 1) {
                cx = (own.x() + o.targetX()) / 2;
                cz = (own.z() + o.targetZ()) / 2;
            }
            double x = own.x() - cx, z = own.z() - cz, radius = Math.hypot(x, z);
            double outward = radius > 0 ? (x * own.vx() + z * own.vz()) / radius : 0;
            boolean hurt = Double.isFinite(o.lastOwnHurtTime()) || Double.isFinite(o.lastTargetHurtTime());
            for (int i = 0; i < radii.length; i++) {
                if (done[i]) continue;
                if (Double.isNaN(start[i])) {
                    if (!hurt || radius < radii[i] || outward < .02) continue;
                    start[i] = o.time();
                }
                samples.get(i).add(List.of(o.time(), heads, x, own.y(), z, own.vx(), own.vy(), own.vz(),
                        o.targetX() - cx, o.targetZ() - cz, own.onGround(), a.key(),
                        (int)Math.signum(a.strafe()), a.click(), a.jump()));
                done[i] = o.time() >= start[i] + 500;
            }
            return a;
        }
    }

    public static void main(String[] args) throws Exception {
        var arms = Matches.roster(Path.of(args[0]));
        var foes = Matches.roster(Path.of(args[1]));
        var clocks = Arrays.stream(args, 2, args.length).map(Path::of).toList();
        for (var env : Arena.envs(clocks, "mmc", true)) {
            env.initial.timing.mouseInput = true;
            for (var arm : arms) for (var foe : foes) for (boolean first : new boolean[]{true,false}) {
                var observer = new Observer(arm, env.rtt);
                var g = env.new Game(first, observer, foe.create(env.rtt, 0));
                g.finish();
                var row = new LinkedHashMap<String,Object>();
                row.put("arm", arm.name()); row.put("opponent", foe.name());
                row.put("side", first ? "a" : "b"); row.put("rtt", env.rtt); row.put("clock", env.clockId);
                row.put("score", g.reward()); row.put("end", g.clock.time());
                row.put("fresh", first ? List.of(g.clock.state.hits,g.clock.state.incomingHits)
                        : List.of(g.clock.state.incomingHits,g.clock.state.hits));
                row.put("center", List.of(observer.cx, observer.cz));
                row.put("observations", observer.samples);
                System.out.println(Json.encode(row));
            }
        }
    }
}
