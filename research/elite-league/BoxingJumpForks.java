import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lab.Json;

/** One normal jump from the exact mirror state where W-tap is about to give up a double. */
final class BoxingJumpForks {
    private static final double HORIZON = 1_700;
    private static final double ACTIVITY = 1000;
    private static final int[][] PHASES = {{0, 0}, {11, 27}, {32, 7}};

    static final class Jumper extends DuelEnv.Player {
        final boolean ahead;
        boolean pending, decided;
        double jumpAt = Double.NaN;

        Jumper(DuelEnv.Player baseline, boolean ahead) {
            super("wtap", 0, baseline.rtt, baseline.wake);
            this.ahead = ahead;
            pending = ahead;
            handled = baseline.handled;
            releaseUntil = baseline.releaseUntil;
            firstClick = baseline.firstClick;
        }

        @Override public void received(PeriodicComboClock.PacketObservation packet) {
            if (!ahead && !decided && packet.kind() == PeriodicComboClock.ReceivedKind.OWN_VELOCITY
                    && (packet.vx() != 0 || packet.vz() != 0)) pending = true;
        }

        @Override public PeriodicComboClock.Control input(PeriodicComboClock.Observation o) {
            var move = super.input(o);
            if (!pending) return move;
            pending = false;
            decided = true;
            if (!o.own().onGround()) return move;
            jumpAt = o.time();
            return new PeriodicComboClock.Control(move.key(), move.strafe(), move.click(), true);
        }
    }

    record Result(List<DuelEnv.Hit> hits, int concededBeforeReply, int acceptedFor, int acceptedAgainst,
                  Double jumpAt, boolean groundAtFork) {
        Map<String,Object> values(boolean first) {
            var result = new java.util.LinkedHashMap<String,Object>();
            result.put("events", hits.stream().map(h -> List.of(h.at(), h.sideA() == first, h.upgrade())).toList());
            result.put("concededBeforeReply", concededBeforeReply);
            result.put("accepted", List.of(acceptedFor, acceptedAgainst));
            result.put("jumpAt", jumpAt);
            result.put("groundAtFork", groundAtFork);
            return result;
        }
    }

    private static DuelEnv.Game mirror(DuelEnv env, boolean first) {
        var game = env.new Game(first, new DuelEnv.Player("wtap", 0, env.rtt, 0),
                new DuelEnv.Player("wtap", 0, env.rtt, 0));
        game.trace();
        return game;
    }

    private static List<Double> incomingDoubles(DuelEnv.Game game) {
        var targets = new ArrayList<Double>();
        var hits = game.hits.list;
        for (int i = 0; i + 1 < hits.size(); i++) {
            var hit = hits.get(i);
            if (hit.sideA() == game.first || hit.upgrade() || hit.at() < 1_500 || hit.at() > 9_000) continue;
            if (i > 0 && hits.get(i-1).sideA() != game.first && hit.at() - hits.get(i-1).at() < ACTIVITY) continue;
            boolean earlierIncoming = false;
            for (int j = i - 1; j >= 0 && hit.at() - hits.get(j).at() <= 125; j--) {
                if (hits.get(j).sideA() != game.first) earlierIncoming = true;
            }
            if (earlierIncoming) continue;
            for (int j = i + 1; j < hits.size(); j++) {
                var next = hits.get(j);
                if (next.sideA() == game.first || next.at() - hit.at() > ACTIVITY) break;
                if (next.upgrade()) continue;
                if (j+1 < hits.size() && hits.get(j+1).at() == next.at()
                        && hits.get(j+1).sideA() == game.first) break;
                targets.add(hit.at());
                break;
            }
        }
        return targets;
    }

    private static Result branch(DuelEnv env, DuelEnv.Game prefix, double end, boolean jump, boolean ahead) {
        var a = prefix.a.copy();
        var b = prefix.b.copy();
        boolean first = prefix.first;
        Jumper jumper = jump ? new Jumper(first ? a : b, ahead) : null;
        if (jumper != null) {
            if (first) a = jumper; else b = jumper;
        }
        var game = env.new Game(prefix, a, b);
        game.trace();
        var state = game.clock.state;
        boolean ground = (first ? state.a.q : state.b.q).onGround();
        game.clock.runUntil(end);
        int own = 0, other = 0, conceded = 0;
        boolean replied = false;
        for (var hit : game.hits.list) {
            if (hit.sideA() == first) {
                own++;
                // Accepted damage upgrades count for boxing score and end the incoming run.
                if (other > 0) replied = true;
            } else {
                other++;
                if (!replied) conceded++;
            }
        }
        Double jumped = jumper != null && Double.isFinite(jumper.jumpAt) ? jumper.jumpAt : null;
        return new Result(List.copyOf(game.hits.list), conceded, own, other, jumped, ground);
    }

    private static List<Double> episodeStarts(DuelEnv.Game mirror) {
        double gap = 100.0 * mirror.clock.config.hitTicks + 100;
        var cuts = new ArrayList<Double>();
        int[] points = new int[2];
        double lastIncoming = Double.NEGATIVE_INFINITY;
        for (var hit : mirror.hits.list) {
            boolean own = hit.sideA() == mirror.first;
            if (++points[own ? 0 : 1] >= 85) break;
            if (own) {
                lastIncoming = Double.NEGATIVE_INFINITY;
            } else if (!hit.upgrade()) {
                if (hit.at() - lastIncoming > gap) cuts.add(hit.at());
                lastIncoming = hit.at();
            }
        }
        return cuts.stream().distinct().toList();
    }

    private static void recorded(String[] args) throws Exception {
        int rtt = Integer.parseInt(args[1]);
        for (int i = 4; i < args.length; i++) {
            var env = BoxingEdgeHitSelect.environment(Path.of(args[i]), rtt, 180_000, Path.of(args[2]), Path.of(args[3]));
            for (boolean first : new boolean[]{true, false}) {
                var original = mirror(env, first);
                original.finish();
                var prefix = mirror(env, first);
                var hits = original.hits.list;
                int seen = 0;
                for (double at : episodeStarts(original)) {
                    double cut = Math.nextDown(at), end = Math.min(env.limit, at + HORIZON);
                    prefix.clock.runUntil(cut);
                    while (seen < hits.size() && hits.get(seen).at() <= cut) seen++;
                    int to = seen;
                    while (to < hits.size() && hits.get(to).at() <= end) to++;
                    var baseline = branch(env, prefix, end, false, false);
                    if (!baseline.hits.equals(hits.subList(seen, to))) throw new AssertionError("W-tap prefix mismatch");
                    var escape = branch(env, prefix, end, true, false);
                    System.out.println(Json.encode(Map.of("clock", env.clockId, "rtt", rtt, "side", first ? "a" : "b",
                            "anchor", at, "wtap", baseline.values(first), "jump", escape.values(first))));
                }
            }
        }
    }

    private static Map<String, Object> maintenance(BoxingDrill.Checkpoint checkpoint, boolean jump) {
        var game = checkpoint.suffix(null);
        if (jump) {
            var subject = new Jumper(game.our(), false);
            game = BoxingChaseEnvelope.branch(checkpoint.env, game, subject);
        }
        boolean first = game.first;
        while (game.hits.list.stream().noneMatch(h -> h.sideA() != first) && game.advance()) {
            // The first accepted return terminates both maintenance endpoints.
        }
        double activeGap = 100.0 * game.clock.config.hitTicks + 100;
        return BoxingLateReturns.score(game, checkpoint.landmark, activeGap).values();
    }

    private static void recordedMaintenance(String[] args) throws Exception {
        int rtt = Integer.parseInt(args[1]);
        for (int i = 4; i < args.length; i++) {
            var env = BoxingEdgeHitSelect.environment(Path.of(args[i]), rtt, 20_000, Path.of(args[2]), Path.of(args[3]));
            for (boolean first : new boolean[]{true, false}) for (var cp : BoxingDrill.acquire(env, first, true)) {
                System.out.println(Json.encode(Map.of("clock", env.clockId, "rtt", rtt, "side", first ? "a" : "b",
                        "landmark", cp.landmark, "k", cp.k, "wtap", maintenance(cp, false),
                        "jump", maintenance(cp, true))));
            }
        }
    }

    public static void main(String[] args) throws Exception {
        if (args[0].equals("--wire-maintenance")) {
            recordedMaintenance(args);
            return;
        }
        if (args[0].equals("--wire")) {
            recorded(args);
            return;
        }
        int cps = Integer.parseInt(args[0]);
        for (int i = 1; i < args.length; i++) {
            var tape = DuelEnv.Tape.read(Path.of(args[i]), 12_000);
            for (int[] shift : PHASES) for (int ping : new int[]{0, 80}) {
                var env = BoxingTradeSkills.env(BoxingDrill.shift(tape, shift[0], shift[1]), ping, cps, 6);
                for (boolean first : new boolean[]{true, false}) {
                    var original = mirror(env, first);
                    original.finish();
                    for (double anchor : incomingDoubles(original)) {
                        for (int lead : new int[]{0, 75, 125}) {
                            double forkAt = Math.nextDown(anchor - lead), end = anchor + HORIZON;
                            var prefix = mirror(env, first);
                            prefix.clock.runUntil(forkAt);
                            var baseline = branch(env, prefix, end, false, false);
                            var unchanged = original.hits.list.stream().filter(h -> h.at() > forkAt && h.at() <= end).toList();
                            if (!unchanged.equals(baseline.hits)) throw new AssertionError("W-tap replay diverged");
                            if (baseline.concededBeforeReply < 2) throw new AssertionError("W-tap exposure changed");
                            var candidate = branch(env, prefix, end, true, lead > 0);
                            System.out.println(Json.encode(Map.of(
                                    "donor", tape.id(), "phase", shift, "rtt", ping, "side", first ? "a" : "b",
                                    "cps", cps, "anchor", anchor, "lead", lead, "wtap", baseline.values(first),
                                    "candidate", candidate.values(first))));
                        }
                    }
                }
            }
        }
    }
}
