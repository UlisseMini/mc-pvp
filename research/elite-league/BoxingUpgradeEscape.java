import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lab.DuelNetwork;
import lab.Json;

/** Fork incoming fresh hits and isolate ordinary responses to the subsequent own hurt cue. */
final class BoxingUpgradeEscape {
    private static final double HORIZON = 1400;
    private static final int CPS = 20;

    private record Move(String name, int delay, int key, int heads, boolean jump) {}
    private static final List<Move> MOVES = moves();

    private static List<Move> moves() {
        var result = new ArrayList<Move>();
        for (int delay : new int[]{0, 100, 200, 250}) {
            result.add(new Move("coast-" + delay, delay, PeriodicComboFightModel.R, 2, false));
            result.add(new Move("back-" + delay, delay, PeriodicComboFightModel.BACK, 2, false));
            result.add(new Move("jump-" + delay, delay, -1, 1, true));
        }
        return List.copyOf(result);
    }

    private static final class Player extends DuelEnv.Player {
        final Arena.Neural learned;
        final boolean useNetwork;
        final Move move;
        final double landmark;
        double cue = Double.NaN, action = Double.NaN;
        int taken;

        Player(int rtt, Arena.Model model) {
            super("wtap", 0, rtt, 0);
            learned = model == null ? null : new Arena.Neural(model, rtt);
            useNetwork = learned != null;
            move = null;
            landmark = Double.POSITIVE_INFINITY;
        }

        Player(Player prefix, boolean useNetwork, Move move, double landmark) {
            super("wtap", 0, prefix.rtt, prefix.wake);
            learned = prefix.learned == null ? null : prefix.learned.copy();
            handled = prefix.handled;
            releaseUntil = prefix.releaseUntil;
            firstClick = prefix.firstClick;
            this.useNetwork = useNetwork;
            this.move = move;
            this.landmark = landmark;
        }

        @Override public PeriodicComboClock.Control input(PeriodicComboClock.Observation o) {
            var wtap = super.input(o);
            var stock = useNetwork ? learned.input(o) : wtap;
            var chosen = stock;
            if (move != null && taken < move.heads) {
                if (!Double.isFinite(cue) && o.lastOwnHurtTime() > landmark) cue = o.lastOwnHurtTime();
                if (o.time() >= cue + move.delay) {
                    if (!move.jump || o.own().onGround()) {
                        int key = move.key < 0 ? stock.key() : move.key;
                        chosen = new PeriodicComboClock.Control(key, stock.strafe(), stock.click(), move.jump);
                        if (!Double.isFinite(action)) action = o.time();
                    }
                    taken++;
                }
            }
            if (learned != null && useNetwork) {
                learned.net.applied(DuelNetwork.pack(chosen.key(), (int)Math.signum(chosen.strafe()),
                        chosen.click(), chosen.jump()));
            }
            return chosen;
        }
    }

    private record Score(int ours, int theirs, int oursFresh, int theirsFresh, boolean firstWindowUpgrade,
                         Double firstAction, boolean jumped, List<DuelEnv.Hit> hits) {
        Map<String,Object> values() {
            var result = new LinkedHashMap<String,Object>();
            result.put("accepted", List.of(ours, theirs));
            result.put("fresh", List.of(oursFresh, theirsFresh));
            result.put("firstWindowUpgrade", firstWindowUpgrade);
            result.put("firstActionMs", firstAction);
            result.put("jumped", jumped);
            return result;
        }
    }

    private static DuelEnv.Game start(DuelEnv env, boolean seat, Arena.Model model) {
        var game = env.new Game(seat, new Player(env.rtt, model), Evolve.WT.create(env.rtt, 0));
        game.trace();
        return game;
    }

    private static Score branch(DuelEnv env, DuelEnv.Game prefix, double anchor, boolean network, Move move) {
        var player = new Player((Player)prefix.our(), network, move, anchor);
        var game = env.new Game(prefix, prefix.first ? player : prefix.a.copy(), prefix.first ? prefix.b.copy() : player);
        game.trace();
        boolean[] jumped = {false};
        if (move != null && move.jump) {
            String ownMove = game.first ? "MOVEA" : "MOVEB";
            game.clock.setAudit((time, stage, state) -> {
                game.hits.event(time, stage, state);
                if (stage.equals(ownMove) && (game.first ? state.a : state.b).jumpTicks == 10)
                    jumped[0] = true;
            });
        }
        game.clock.runUntil(anchor + HORIZON);
        int ours = 0, theirs = 0, oursFresh = 0, theirsFresh = 0;
        boolean upgrade = false;
        for (var hit : game.hits.list) {
            if (hit.sideA() == game.first) {
                ours++;
                if (!hit.upgrade()) oursFresh++;
            } else {
                theirs++;
                if (!hit.upgrade()) theirsFresh++;
                else if (theirsFresh == 0) upgrade = true;
            }
        }
        return new Score(ours, theirs, oursFresh, theirsFresh, upgrade,
                Double.isFinite(player.action) ? player.action - anchor : null, jumped[0], game.hits.list);
    }

    private static void audit(DuelEnv env, boolean seat, Arena.Model model) {
        var source = start(env, seat, model);
        source.finish();
        var reference = env.new Game(seat, model == null ? Evolve.WT.create(env.rtt, 0)
                : new Arena.Neural(model, env.rtt), Evolve.WT.create(env.rtt, 0));
        reference.trace();
        reference.finish();
        if (!source.hits.list.equals(reference.hits.list)) throw new AssertionError("Stock observation wrapper changed play");
        var prefix = start(env, seat, model);
        double previous = Double.NEGATIVE_INFINITY;
        double active = Math.max(source.a.firstClick, source.b.firstClick);
        for (var h : source.hits.list) {
            double anchor = h.at();
            if (h.sideA() == seat || h.upgrade() || anchor < active || anchor + HORIZON > env.limit) continue;
            if (anchor == previous) throw new IllegalStateException("Multiple incoming fresh hits in one drain");
            previous = anchor;
            prefix.clock.runUntil(anchor);
            // A same-drain upgrade is already committed, so it cannot be denied in this fork.
            boolean committedUpgrade = prefix.hits.list.stream().anyMatch(x -> x.at() == anchor
                    && x.sideA() != seat && x.upgrade());
            if (committedUpgrade) continue;
            var stock = branch(env, prefix, anchor, model != null, null);
            var expected = source.hits.list.stream().filter(x -> x.at() > anchor && x.at() <= anchor + HORIZON).toList();
            if (!stock.hits.equals(expected)) throw new AssertionError("Unchanged branch departed from regulation");
            var wtap = model == null ? stock : branch(env, prefix, anchor, false, null);
            var outcomes = new LinkedHashMap<String,Object>();
            for (var move : MOVES)
                outcomes.put(move.name, branch(env, prefix, anchor, model != null, move).values());
            var state = prefix.clock.state;
            var incoming = seat ? state.lastFreshA : state.lastFreshB;
            if (incoming == null || incoming.time() != anchor) throw new IllegalStateException("Missing incoming impulse");
            System.out.println(Json.encode(Map.of("clock", env.clockId, "rtt", env.rtt, "seat", seat ? "a" : "b",
                    "base", model == null ? "wtap" : "frozen", "anchorMs", anchor, "startDamage",
                    seat ? state.lastDamageA : state.lastDamageB, "verticalImpulse", incoming.y(),
                    "wtap", wtap.values(), "stock", stock.values(), "moves", outcomes)));
        }
    }

    public static void main(String[] args) throws Exception {
        var model = Arena.Model.decode(Files.readString(Path.of(args[0])).strip());
        int rtt = Integer.parseInt(args[1]);
        for (int i = 2; i < args.length; i++) {
            var env = BoxingDoubleSearch.environment(Path.of(args[i]), rtt, CPS, 20_000);
            for (boolean seat : new boolean[]{true, false}) {
                audit(env, seat, null);
                audit(env, seat, model);
            }
        }
    }
}
