import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiPredicate;
import lab.Json;

/** Replay later trades from the same queued state with selected movement disabled. */
final class BoxingLaterTrades {
    private static final int BIN_MS = 8_000;
    private static final int BINS = 3;
    private static final int TRADE_MS = 200;
    private static final int HORIZON_MS = 4_500;
    private static final int PREFIX_HEADS = 2;
    private static final boolean TEST_DIRECTIONS = Boolean.getBoolean("lab.boxing.laterDirections");

    enum Use {
        WTAP, FULL, EARLY, LATE, WALK_ONLY, KEEP_FORWARD_ONLY, BOTH, WITHOUT_WALK, WITHOUT_KEEP_FORWARD, WITHOUT_BOTH;

        boolean stock(int head) {
            return this == WTAP || (this == EARLY && head >= PREFIX_HEADS) || (this == LATE && head < PREFIX_HEADS);
        }

        boolean allow(PeriodicComboClock.Control stock, PeriodicComboClock.Control proposed) {
            boolean walk = stock.key() == 2 && proposed.key() == 1;
            boolean keepForward = stock.key() == 0 && proposed.key() == 2;
            return switch (this) {
                case WALK_ONLY -> walk;
                case KEEP_FORWARD_ONLY -> keepForward;
                case BOTH -> walk || keepForward;
                case WITHOUT_WALK -> !walk;
                case WITHOUT_KEEP_FORWARD -> !keepForward;
                case WITHOUT_BOTH -> !walk && !keepForward;
                default -> true;
            };
        }
    }

    static final class Trial extends DuelEnv.Player {
        final BoxingAcquisitionControl.Acquisition player;
        final Use use;
        final BiPredicate<PeriodicComboClock.Control, PeriodicComboClock.Control> allowed;
        final List<Object> heads = new ArrayList<>();
        final int[] keys = new int[4];
        int head;

        Trial(BoxingAcquisitionControl.Acquisition player, Use use) {
            super(player.style, player.mask, player.rtt, player.wake);
            this.player = player;
            this.use = use;
            this.allowed = use::allow;
            firstClick = player.firstClick;
        }

        @Override public PeriodicComboClock.Control input(PeriodicComboClock.Observation own) {
            boolean stock = use.stock(head);
            var applied = player.input(own, stock, allowed);
            keys[applied.key()]++;
            if (head++ < 6) {
                var body = own.own();
                heads.add(Map.of("at", own.time(), "key", applied.key(), "nearest", own.nearest(),
                        "sprint", own.sprint(), "ownGround", body.onGround(),
                        "ownV", List.of(body.vx(), body.vy(), body.vz()),
                        "targetY", own.targetY() - body.y(), "click", applied.click(), "jump", applied.jump()));
            }
            return applied;
        }
    }

    record Anchor(int index, DuelEnv.Hit hit, double tradeGap) {}
    record Result(Use use, List<DuelEnv.Hit> hits, List<List<Long>> presses, Map<String,Object> values) {}

    static DuelEnv.Game start(DuelEnv env, Arena.Model model, boolean first, Use source, int own, int other) {
        var player = new BoxingAcquisitionControl.Acquisition(new Arena.Neural(model, env.rtt),
                new DuelEnv.Player("wtap", 0, env.rtt, 0), 2, 1);
        var subject = new Trial(player, source);
        var game = env.new Game(first, subject, new DuelEnv.Player("wtap", 0, env.rtt, 0));
        game.trace();
        BoxingAcquisitionControl.shift(game, own, other);
        return game;
    }

    static List<Anchor> anchors(List<DuelEnv.Hit> hits) {
        var result = new ArrayList<Anchor>();
        int afterBin = BIN_MS;
        for (int i = 1; i < hits.size() && afterBin <= BIN_MS * BINS; i++) {
            var end = hits.get(i);
            if (end.at() < afterBin || end.at() >= BIN_MS * (BINS + 1)) continue;
            var begin = hits.get(i - 1);
            double spacing = end.at() - begin.at();
            if (end.upgrade() || begin.upgrade() || end.sideA() == begin.sideA() || spacing > TRADE_MS) continue;
            result.add(new Anchor(i, end, spacing));
            afterBin = ((int) end.at() / BIN_MS + 1) * BIN_MS;
        }
        return result;
    }

    private static List<Object> score(List<DuelEnv.Hit> hits, boolean first, DuelEnv.Hit last, double end) {
        int own = 0, other = 0, ownFresh = 0, otherFresh = 0, streak = 1;
        int firstTwo = 0, firstFive = 0;
        boolean side = last.sideA();
        double lastAt = last.at();
        for (var hit : hits) {
            if (hit.at() > end) break;
            boolean ours = hit.sideA() == first;
            if (ours) own++; else other++;
            if (side != hit.sideA() || hit.at() - lastAt > 1100) streak = 0;
            side = hit.sideA();
            if (hit.upgrade()) continue;
            if (ours) ownFresh++; else otherFresh++;
            if (++streak >= 2 && firstTwo == 0 && hit.at() - lastAt <= 850) firstTwo = ours ? 1 : -1;
            if (streak == 5 && firstFive == 0) firstFive = ours ? 1 : -1;
            lastAt = hit.at();
        }
        return List.of(own, other, ownFresh, otherFresh, firstTwo, firstFive);
    }

    private static Result run(DuelEnv env, DuelEnv.Game prefix, Anchor anchor, Use use) {
        var subject = new Trial(((Trial) prefix.our()).player.copy(), use);
        var game = env.new Game(prefix, prefix.first ? subject : prefix.a.copy(), prefix.first ? prefix.b.copy() : subject);
        game.trace();
        var presses = List.of(new ArrayList<Long>(), new ArrayList<Long>());
        var firstLaunch = new LinkedHashMap<String,Object>();
        var contacts = new int[2];
        game.clock.setClickAudit(event -> {
            int side = event.fromA() == game.first ? 0 : 1;
            if (event.action().equals("ADMIT")) {
                var times = presses.get(side);
                if (!times.isEmpty() && event.admittedNanos() - times.get(times.size() - 1) < 50_000_000L)
                    throw new AssertionError("CPS cap violated");
                times.add(event.admittedNanos());
            }
            if (event.action().equals("CONSUME") && event.contact()) contacts[side]++;
        });
        game.clock.setAudit((time, stage, state) -> {
            int seen = game.hits.list.size();
            game.hits.event(time, stage, state);
            for (; seen < game.hits.list.size(); seen++) {
                var hit = game.hits.list.get(seen);
                if (hit.upgrade()) continue;
                var impulse = hit.sideA() ? state.lastFreshB : state.lastFreshA;
                if (impulse == null || impulse.time() != time) throw new AssertionError("Fresh hit has no matching impulse");
                var defender = hit.sideA() ? state.b : state.a;
                var attacker = hit.sideA() ? state.a : state.b;
                var view = hit.sideA() ? state.ab : state.ba;
                firstLaunch.putIfAbsent(hit.sideA() == game.first ? "own" : "opponent",
                        Map.of("at", time, "serverGap", Math.hypot(state.serverAx - state.serverBx, state.serverAz - state.serverBz),
                                "motion", List.of(impulse.x(), impulse.y(), impulse.z()),
                                "attackerClientNearestAtDrain", PeriodicComboFightModel.nearest(attacker.q, view),
                                "defenderClientYAtDrain", defender.q.y()));
            }
        });
        double start = prefix.clock.time();
        game.clock.runUntil(start + HORIZON_MS);
        var committed = new ArrayList<>(prefix.hits.list.subList(anchor.index + 1, prefix.hits.list.size()));
        committed.addAll(game.hits.list);
        var scores = new LinkedHashMap<String,Object>();
        for (int length : new int[]{450, 1100, 2500, HORIZON_MS}) {
            scores.put(Integer.toString(length), score(committed, game.first, anchor.hit, start + length));
        }
        return new Result(use, committed, List.of(presses.get(0), presses.get(1)), Map.of(
                "scores", scores, "firstLaunch", firstLaunch, "heads", subject.heads, "keys", subject.keys,
                "clicks", List.of(presses.get(0).size(), presses.get(1).size()), "contacts", contacts,
                "hits", committed.stream().map(hit -> List.of(hit.at(), hit.sideA() == game.first, hit.upgrade())).toList()));
    }

    private static void study(DuelEnv env, Arena.Model model, boolean first, Use source, int own, int other) {
        var observed = start(env, model, first, source, own, other);
        var regulation = BoxingDoubleSearch.play(observed, 100);
        if (Math.max(regulation.own(), regulation.other()) != 100) throw new AssertionError("No regulation finish");
        var prefix = start(env, model, first, source, own, other);
        var rows = new ArrayList<Object>();
        for (var anchor : anchors(observed.hits.list)) {
            prefix.clock.runUntil(anchor.hit.at());
            if (!prefix.clock.advanceToBeforeNextHead(first, anchor.hit.at() + 100))
                throw new AssertionError("No next decision head");
            if (!prefix.hits.list.equals(observed.hits.list.subList(0, prefix.hits.list.size())))
                throw new AssertionError("Source prefix diverged");
            double until = prefix.clock.time() + HORIZON_MS;
            if (until >= observed.clock.time()) continue;
            var expected = observed.hits.list.subList(anchor.index + 1, observed.hits.list.size()).stream()
                    .filter(hit -> hit.at() <= until).toList();
            var results = new LinkedHashMap<String,Object>();
            var base = run(env, prefix, anchor, Use.WTAP);
            for (Use arm : List.of(Use.WTAP, Use.FULL, Use.EARLY, Use.LATE)) {
                var branch = arm == Use.WTAP ? base : run(env, prefix, anchor, arm);
                if (arm == source && !branch.hits.equals(expected)) throw new AssertionError("Source suffix diverged");
                if (!branch.presses.equals(base.presses)) throw new AssertionError("Physical press schedules differ");
                results.put(arm.toString(), branch.values);
            }
            rows.add(Map.of("anchor", anchor.hit.at(), "postTradeHead", prefix.clock.time(),
                    "tradeGapMs", anchor.tradeGap, "lastOurs", anchor.hit.sideA() == first, "arms", results));
        }
        System.out.println(Json.encode(Map.of("clock", env.clockId, "seat", first ? "a" : "b", "phase", List.of(own, other),
                "source", source.toString(), "regulation", regulation.values(), "cuts", rows)));
    }

    private static void directionStudy(DuelEnv env, Arena.Model model, boolean first, int own, int other) {
        var arms = new LinkedHashMap<String,Object>();
        for (Use arm : List.of(Use.WTAP, Use.FULL, Use.WALK_ONLY, Use.KEEP_FORWARD_ONLY, Use.BOTH,
                Use.WITHOUT_WALK, Use.WITHOUT_KEEP_FORWARD, Use.WITHOUT_BOTH)) {
            var player = new BoxingAcquisitionControl.Acquisition(new Arena.Neural(model, env.rtt),
                    new DuelEnv.Player("wtap", 0, env.rtt, 0), 2, 1);
            var subject = new Trial(player, arm);
            var outcome = new LinkedHashMap<>(BoxingAcquisitionControl.regulation(env, first, subject, own, other));
            outcome.put("keys", subject.keys);
            outcome.put("stockHeads", player.tapHeads);
            outcome.put("learnedMovementHeads", player.learnedHeads);
            arms.put(arm.toString(), outcome);
        }
        System.out.println(Json.encode(Map.of("clock", env.clockId, "seat", first ? "a" : "b", "phase", List.of(own, other), "arms", arms)));
    }

    public static void main(String[] args) throws Exception {
        var model = Arena.Model.decode(Files.readString(Path.of(args[0])).strip());
        int rtt = Integer.parseInt(args[1]);
        var wire = Path.of(args[2]);
        var seeds = Path.of(args[3]);
        for (int i = 4; i < args.length; i++) {
            var env = BoxingEdgeHitSelect.environment(Path.of(args[i]), rtt, 180_000, wire, seeds);
            for (boolean first : new boolean[]{true, false}) {
                for (int own : new int[]{0, -5, 5}) for (int other : new int[]{0, -5, 5}) {
                    if (TEST_DIRECTIONS) directionStudy(env, model, first, own, other);
                    else for (Use source : List.of(Use.WTAP, Use.FULL)) study(env, model, first, source, own, other);
                }
            }
        }
    }
}
