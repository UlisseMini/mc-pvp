import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import lab.ExactTravel;
import lab.Json;

/** Change only the first recoil step's horizontal velocity on a copied simulator state. */
final class BoxingOpeningRecoil {
    private static final int LENGTH = 12_000;
    private static final int[][] PHASES = {{0, 0}, {0, 17}, {23, 0}, {34, 11}};
    private static final BoxingTradeSkills.Setting CUE = new BoxingTradeSkills.Setting("hurt+150", 0, 150, false);

    record Impulse(double at, double vx, double vy, double vz) {}

    static final class Defender extends DuelEnv.Player {
        Impulse recoil;

        Defender(int ping) { super("wtap", 0, ping, 0); }

        @Override public void received(PeriodicComboClock.PacketObservation packet) {
            if (recoil == null && packet.kind() == PeriodicComboClock.ReceivedKind.OWN_VELOCITY
                    && (packet.vx() != 0 || packet.vz() != 0)) {
                recoil = new Impulse(packet.handledAt(), packet.vx(), packet.vy(), packet.vz());
            }
        }

        @Override Defender copy() {
            var copy = new Defender(rtt);
            copy.handled = handled;
            copy.releaseUntil = releaseUntil;
            copy.firstClick = firstClick;
            copy.recoil = recoil;
            return copy;
        }
    }

    private static DuelEnv.Game start(DuelEnv env, boolean first, Defender defender, DuelEnv.Player selector) {
        var game = env.new Game(first, selector, defender);
        game.trace();
        return game;
    }

    record Window(boolean timely, Double incoming, Double counter, int followups, int score) {
        Map<String, Object> values() {
            var output = new LinkedHashMap<String, Object>();
            output.put("incoming", incoming);
            output.put("counter", counter);
            output.put("timely", timely);
            output.put("followups", followups);
            output.put("score", score);
            return output;
        }
    }

    static Window score(List<DuelEnv.Hit> hits, boolean first) {
        Double counter = null, incoming = null;
        int ownAt = -1, margin = 0;
        for (int i = 0; i < hits.size(); i++) {
            var h = hits.get(i);
            margin += h.sideA() == first ? 1 : -1;
            if (h.upgrade()) continue;
            if (h.sideA() == first && counter == null) {
                counter = h.at();
                ownAt = i;
            }
            if (h.sideA() != first && incoming == null) incoming = h.at();
        }
        boolean timely = counter != null && incoming != null && counter > incoming && counter - incoming <= 275;
        int followups = 0;
        if (timely) {
            double last = counter;
            for (int i = ownAt + 1; i < hits.size();) {
                double time = hits.get(i).at();
                boolean returnHit = false;
                int fresh = 0;
                while (i < hits.size() && hits.get(i).at() == time) {
                    var h = hits.get(i++);
                    returnHit |= h.sideA() != first;
                    if (!h.upgrade()) fresh++;
                }
                if (time == counter || returnHit || time - last > 1000) break;
                if (fresh > 0) {
                    followups += fresh;
                    last = time;
                }
            }
        }
        return new Window(timely, incoming, counter, followups, margin);
    }

    static PeriodicComboFightModel.Body defender(DuelEnv.Game game) {
        return game.first ? game.clock.state.b : game.clock.state.a;
    }

    private static List<DuelEnv.Hit> through(DuelEnv.Game game, double time) {
        return game.hits.list.stream().filter(h -> h.at() <= time).toList();
    }

    private static DuelEnv.Game fork(DuelEnv env, DuelEnv.Game prefix) {
        var game = env.new Game(prefix, prefix.a.copy(), prefix.b.copy());
        game.trace();
        return game;
    }

    static Map<String, Object> measure(DuelEnv env, boolean first, int cps, int[] shift, double gap,
                                       Supplier<? extends DuelEnv.Player> selector) {
        var normal = start(env, first, new Defender(env.rtt), new DuelEnv.Player("wtap", 0, env.rtt, 0));
        normal.finish();
        var observer = new Defender(env.rtt);
        var selected = start(env, first, observer, selector.get());
        String move = first ? "MOVEB" : "MOVEA";
        var firstMove = new double[]{Double.NaN};
        selected.clock.setAudit((time, stage, state) -> {
            selected.hits.event(time, stage, state);
            if (stage.equals(move) && observer.recoil != null && !Double.isFinite(firstMove[0])) firstMove[0] = time;
        });
        selected.finish();
        var baseline = score(selected.hits.list, first);
        var row = new LinkedHashMap<String, Object>();
        row.put("donor", env.clockId);
        row.put("shift", shift);
        row.put("rtt", env.rtt);
        row.put("cps", cps);
        row.put("gap", gap);
        row.put("seat", first ? "a" : "b");
        row.put("plainWtap", score(normal.hits.list, first).values());
        row.put("wtap", baseline.values()); // Unmodified defender against the cue opening.
        row.put("intervention", "no timely counter");
        if (baseline.timely && observer.recoil != null && Double.isFinite(firstMove[0])) {
            var impulse = observer.recoil;
            double cut = Math.nextDown(firstMove[0]);
            if (impulse.at > baseline.counter && impulse.at <= cut) {
                var prefix = start(env, first, new Defender(env.rtt), selector.get());
                prefix.clock.runUntil(cut);
                var control = fork(env, prefix);
                var compare = fork(env, prefix);
                var body = defender(compare);
                var q = body.q;
                double scale = q.vx() == impulse.vx && q.vz() == impulse.vz ? 1
                        : q.vx() == impulse.vx * .6 && q.vz() == impulse.vz * .6 ? .6 : Double.NaN;
                if (Double.isFinite(scale) && q.vy() == impulse.vy) {
                    double targetScale = scale == 1 ? .6 : 1;
                    body.q = new ExactTravel.State(q.x(), q.y(), q.z(), impulse.vx * targetScale, q.vy(),
                            impulse.vz * targetScale, q.box(), q.onGround(), q.isCollidedHorizontally(),
                            q.isCollidedVertically(), q.isCollided(), q.noClip(), q.isInWeb(), q.sneaking());
                    control.clock.runUntil(LENGTH);
                    compare.clock.runUntil(LENGTH);
                    var exact = new ArrayList<>(prefix.hits.list);
                    exact.addAll(control.hits.list);
                    if (!selected.hits.list.equals(exact) || !prefix.hits.list.equals(through(selected, cut))) {
                        throw new AssertionError("Unchanged W-tap fork diverged: " + row);
                    }
                    var hits = new ArrayList<>(prefix.hits.list);
                    hits.addAll(compare.hits.list);
                    row.put("forkAt", cut);
                    row.put("velocityReceived", impulse.at);
                    row.put("velocityScale", scale);
                    row.put("counterfactual", score(hits, first).values());
                    row.put("intervention", scale == .6 ? "restore full recoil" : "apply recoil brake");
                } else row.put("intervention", "unidentified pre-move velocity");
            } else row.put("intervention", "overlapping timing");
        }
        return row;
    }

    public static void main(String[] args) throws Exception {
        int cps = Integer.parseInt(args[0]);
        for (int i = 1; i < args.length; i++) {
            var tape = DuelEnv.Tape.read(Path.of(args[i]), LENGTH);
            for (int[] phase : PHASES) {
                var shifted = BoxingDrill.shift(tape, phase[0], phase[1]);
                for (int rtt : new int[]{0, 20, 80}) for (double gap : new double[]{5, 6, 7}) {
                    var env = BoxingTradeSkills.env(shifted, rtt, cps, gap);
                    for (boolean first : new boolean[]{true, false}) {
                        var row = measure(env, first, cps, phase, gap, () -> new BoxingTradeSkills.Fighter(CUE, rtt));
                        System.out.println(Json.encode(row));
                    }
                }
            }
        }
    }
}
