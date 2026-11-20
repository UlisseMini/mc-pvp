import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import lab.BoxingRecoilSprint;
import lab.Json;

/** Closed-loop reproduction of the native one-sprint hurt-order trial. */
final class BoxingRecoilOrderModel {
    private record Phase(int own, int other, int direction) {
        void apply(DuelEnv.Game game) {
            if (own == 0 && other == 0) return;
            var s = game.clock.state;
            s.packetTiming = new BoxingReleaseWindow.ShiftedReceipt(s.packetTiming,
                    game.first ? own : other, game.first ? other : own, direction);
        }
        List<Integer> fields() { return List.of(own, other, direction); }
    }

    private static final List<Phase> PHASES = List.of(new Phase(0, 0, 1),
            new Phase(-2, 0, 1), new Phase(2, 0, 1), new Phase(0, -2, -1), new Phase(0, 2, -1));

    private static final class Subject extends DuelEnv.Player {
        final BoxingRecoilSprint gate = new BoxingRecoilSprint(BoxingRecoilSprint.Arm.ORDERED);
        final boolean enabled;
        long order, ownOrder, targetOrder;
        double ownStatus = Double.NEGATIVE_INFINITY, targetStatus = Double.NEGATIVE_INFINITY;
        double changedAt = Double.NaN;
        Map<String, Object> changedFrom = Map.of();
        int rejected;

        Subject(int ping, boolean enabled) {
            super("wtap", 0, ping, 0);
            this.enabled = enabled;
        }

        Subject(Subject original, boolean enabled) {
            this(original.rtt, enabled);
            handled = original.handled;
            releaseUntil = original.releaseUntil;
            firstClick = original.firstClick;
            order = original.order;
            ownOrder = original.ownOrder;
            targetOrder = original.targetOrder;
            ownStatus = original.ownStatus;
            targetStatus = original.targetStatus;
        }

        @Override public void received(PeriodicComboClock.PacketObservation p) {
            if (p.kind() == PeriodicComboClock.ReceivedKind.OWN_HURT) {
                ownStatus = p.handledAt();
                ownOrder = ++order;
            } else if (p.kind() == PeriodicComboClock.ReceivedKind.TARGET_HURT) {
                targetStatus = p.handledAt();
                targetOrder = ++order;
            }
        }

        private static long wall(double t) { return Double.isFinite(t) ? (long) t + 1 : 0; }

        @Override public PeriodicComboClock.Control input(PeriodicComboClock.Observation view) {
            double previous = handled;
            var stock = super.input(view);
            if (!enabled || !Double.isNaN(changedAt) || previous >= handled || stock.key() != PeriodicComboFightModel.R)
                return stock;
            var body = view.own();
            double x = view.targetX() - body.x(), z = view.targetZ() - body.z();
            double along = (x * body.vx() + z * body.vz()) / Math.max(.001, Math.hypot(x, z));
            double heardOwn = ownStatus;
            // Several handlers in one modeled drain can share a clock instant.
            // Only this client's callback order resolves that tie.
            if (heardOwn == targetStatus && ownOrder > 0 && ownOrder < targetOrder)
                heardOwn = Math.nextDown(heardOwn);
            var d = gate.decide(wall(view.time()), wall(view.lastOwnHurtTime()), wall(view.lastTargetHurtTime()),
                    wall(handled), false, body.onGround(), view.sprint(), view.nearest(), along, true,
                    view.time(), heardOwn, targetStatus);
            if (d == BoxingRecoilSprint.Decision.WRONG_ORDER) rejected++;
            if (d != BoxingRecoilSprint.Decision.ENROLLED) return stock;
            changedAt = view.time();
            changedFrom = Map.of("nearest", view.nearest(), "along", along, "vy", body.vy(),
                    "height", view.targetY() - body.y(), "ownStatusAge", view.time() - ownStatus,
                    "targetStatusAge", view.time() - targetStatus);
            return new PeriodicComboClock.Control(PeriodicComboFightModel.S, stock.strafe(), stock.click(), stock.jump());
        }
    }

    private static final class Presses {
        final List<List<Long>> times = List.of(new ArrayList<>(), new ArrayList<>());
        Presses(DuelEnv.Game game) {
            game.clock.setClickAudit(e -> {
                if (!e.action().equals("ADMIT")) return;
                var lane = times.get(e.fromA() == game.first ? 0 : 1);
                if (!lane.isEmpty() && e.admittedNanos() - lane.get(lane.size() - 1) < 50_000_000L)
                    throw new IllegalStateException("The matched physical cap changed");
                lane.add(e.admittedNanos());
            });
        }
        List<List<Long>> before(double end) {
            return times.stream().map(a -> a.stream().takeWhile(t -> t / 1e6 <= end).toList()).toList();
        }
    }

    private record Played(Map<String,Object> fields, Presses presses, double end, List<DuelEnv.Hit> hits, boolean first) {}

    private static Played regulation(DuelEnv env, boolean first, Phase phase, boolean guarded) {
        var subject = new Subject(env.rtt, guarded);
        var game = env.new Game(first, subject, new DuelEnv.Player("wtap", 0, env.rtt, 0));
        game.trace();
        phase.apply(game);
        var presses = new Presses(game);
        var outcome = BoxingDoubleSearch.play(game, 100);
        double end = BoxingEdgeHitSelect.decidingHit(game);
        double start = Math.max(game.a.firstClick, game.b.firstClick);
        var fields = new LinkedHashMap<String,Object>();
        fields.put("score", outcome.values());
        fields.put("cps", presses.before(end).stream()
                .map(a -> 1000 * a.stream().filter(t -> t / 1e6 >= start).count() / (end - start)).toList());
        fields.put("changedAt", Double.isNaN(subject.changedAt) ? null : subject.changedAt);
        fields.put("changedFrom", subject.changedFrom);
        fields.put("rejected", subject.rejected);
        return new Played(fields, presses, end, List.copyOf(game.hits.list), first);
    }

    private static Played suffix(DuelEnv env, DuelEnv.Game prefix, Subject subject, double anchor,
                                 List<DuelEnv.Hit> after, boolean guarded) {
        var player = new Subject(subject, guarded);
        var game = env.new Game(prefix, prefix.first ? player : prefix.a.copy(), prefix.first ? prefix.b.copy() : player);
        game.trace();
        game.hits.list.addAll(after);
        var presses = new Presses(game);
        game.finish();
        var score = new LinkedHashMap<>(BoxingLateReturns.score(game, anchor,
                100.0 * game.clock.config.hitTicks + 100.0).values());
        score.put("changedAt", Double.isNaN(player.changedAt) ? null : player.changedAt);
        return new Played(score, presses, game.clock.time(), List.copyOf(game.hits.list), game.first);
    }

    private static Map<String, Object> afterActivation(Played bout, double at) {
        int ours = 0, other = 0, before = 0;
        var recent = new ArrayList<List<Object>>();
        for (var hit : bout.hits) {
            boolean own = hit.sideA() == bout.first;
            if (own) ours++; else other++;
            if (hit.at() < at) before += own ? 1 : -1;
            else if (hit.at() <= at + 2200) recent.add(List.of(hit.at() - at, own, hit.upgrade()));
            if (ours == 100 || other == 100) break;
        }
        int change = recent.stream().mapToInt(h -> (boolean) h.get(1) ? 1 : -1).sum();
        return Map.of("marginBefore", before, "next2200ms", recent, "marginNext2200ms", change,
                "marginAfter2200ms", ours - other - before - change);
    }

    private static Map<String, Object> pair(Played a, Played b) {
        double common = Math.min(a.end, b.end);
        var result = new LinkedHashMap<String, Object>();
        result.put("wtap", a.fields);
        result.put("guarded", b.fields);
        result.put("samePresses", a.presses.before(common).equals(b.presses.before(common)));
        if (b.fields.containsKey("score") && b.fields.get("changedAt") instanceof Double at && at < common) {
            if (!a.hits.stream().takeWhile(h -> h.at() < at).toList()
                    .equals(b.hits.stream().takeWhile(h -> h.at() < at).toList()))
                throw new IllegalStateException("Regulation hit history differs before guarded activation");
            var old = afterActivation(a, at);
            var guarded = afterActivation(b, at);
            result.put("atActivation", Map.of("wtap", old, "guarded", guarded));
        }
        return result;
    }

    private static List<Map<String, Object>> maintenance(DuelEnv env, boolean first, Phase phase) {
        var subject = new Subject(env.rtt, false);
        var game = env.new Game(first, subject, new DuelEnv.Player("wtap", 0, env.rtt, 2000));
        phase.apply(game);
        game.trace();
        int examined = 0, streak = 0;
        Set<Integer> enrolled = new HashSet<>();
        var rows = new ArrayList<Map<String,Object>>();
        while (game.advance()) {
            var hits = game.hits.list;
            double wake = Math.max(game.a.firstClick, game.b.firstClick);
            for (; examined < hits.size(); examined++) {
                var hit = hits.get(examined);
                if (!Double.isFinite(wake) || hit.at() < wake) continue;
                if (hit.sideA() != first) streak = 0;
                else if (!hit.upgrade()) {
                    streak++;
                    if ((streak == 3 || streak == 5 || streak == 10) && enrolled.add(streak)) {
                        var committed = List.copyOf(hits.subList(examined + 1, hits.size()));
                        var baseline = suffix(env, game, subject, hit.at(), committed, false);
                        var guarded = suffix(env, game, subject, hit.at(), committed, true);
                        rows.add(Map.of("k", streak, "anchor", hit.at(), "pair", pair(baseline, guarded)));
                    }
                }
            }
        }
        return rows;
    }

    public static void main(String[] args) throws Exception {
        int rtt = Integer.parseInt(args[0]);
        var tape = Path.of(args[4]);
        var reg = BoxingEdgeHitSelect.environment(tape, rtt, 180_000, Path.of(args[1]), Path.of(args[3]));
        var drill = BoxingEdgeHitSelect.environment(tape, rtt, 20_000, Path.of(args[2]), Path.of(args[3]));
        for (boolean first : new boolean[]{true,false}) for (var phase : PHASES) {
            System.out.println(Json.encode(Map.of("clock", reg.clockId, "rtt", rtt, "seat", first ? "a" : "b",
                    "phase", phase.fields(), "regulation", pair(regulation(reg, first, phase, false), regulation(reg, first, phase, true)),
                    "maintenance", maintenance(drill, first, phase))));
        }
    }
}
