import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lab.Json;

/** Same-state timing intervention on one already emitted late critical contact. */
final class BoxingUpgradeDeadline {
    private static final double HORIZON = 1400;
    private static final double MAX_ADVANCE = 10;

    static class Primed extends DuelEnv.Player {
        final DuelEnv.Player actual, wtap;

        Primed(DuelEnv.Player actual, DuelEnv.Player wtap) {
            super("wtap", 0, actual.rtt, 0);
            this.actual = actual;
            this.wtap = wtap;
            firstClick = actual.firstClick;
        }

        @Override public PeriodicComboClock.Control input(PeriodicComboClock.Observation own) {
            wtap.input(own);
            return actual.input(own);
        }

        @Override public boolean injected(PeriodicComboClock.Observation own, double time) {
            super.injected(own, time);
            wtap.injected(own, time);
            return actual.injected(own, time);
        }

        @Override Primed copy() { return new Primed(actual.copy(), wtap.copy()); }
    }

    record Contact(long id, double admitted, double consume, double ready) {}
    record Candidate(Contact shot, double deadline, double freshAt, double normalAt, Contact normalShot) {}
    record Normal(Contact shot, double accepted, int tick) {}

    static DuelEnv.Game start(DuelEnv env, boolean first, Arena.Model model) {
        var normal = new DuelEnv.Player("wtap", 0, env.rtt, 0);
        var actual = model == null ? normal : new Arena.Neural(model, env.rtt);
        var selector = new Primed(actual, normal.copy());
        var game = env.new Game(first, selector, new DuelEnv.Player("wtap", 0, env.otherRtt, 0));
        game.trace();
        return game;
    }

    static final class Ledger {
        int normalTick = Integer.MIN_VALUE;
        double normalAt = Double.NaN;
        Contact normalShot;
        double previousDrain = Double.NaN;
        final List<Candidate> late = new ArrayList<>();
        final List<Normal> normals = new ArrayList<>();
        final Map<Integer, Double> drains = new HashMap<>();

        Ledger(DuelEnv.Game game) {
            var contacts = new HashMap<Long, Contact>();
            int kind = game.first ? 3 : 7;
            game.clock.setClickAudit(click -> {
                if (!click.action().equals("CONSUME") || !click.contact() || click.fromA() != game.first) return;
                var packet = game.clock.state.packets.stream().filter(p -> p.kind == kind)
                        .max(Comparator.comparingLong(p -> p.sequence)).orElseThrow();
                contacts.put(packet.sequence, new Contact(packet.sequence, click.admittedNanos() / 1e6,
                        click.time(), packet.ready));
            });
            game.clock.setAudit((time, event, state) -> {
                int seen = game.hits.list.size();
                game.hits.event(time, event, state);
                if (!event.equals("SERVER_DRAIN")) return;
                drains.put(state.serverTicks, time);
                for (int i = seen; i < game.hits.list.size(); i++) {
                    var hit = game.hits.list.get(i);
                    if (hit.sideA() != game.first) continue;
                    if (hit.upgrade()) {
                        normalTick = Integer.MIN_VALUE;
                    } else {
                        int damage = game.first ? state.lastDamageB : state.lastDamageA;
                        long id = game.first ? state.lastFreshPacketA : state.lastFreshPacketB;
                        boolean sameDrainUpgrade = game.hits.list.subList(i + 1, game.hits.list.size()).stream()
                                .anyMatch(h -> h.sideA() == game.first && h.upgrade());
                        boolean normal = damage == 8 || sameDrainUpgrade;
                        if (!normal && damage == 12 && normalTick != Integer.MIN_VALUE
                                && state.serverTicks - normalTick == game.clock.config.hitTicks) {
                            var shot = contacts.get(id);
                            if (shot == null || normalShot == null || !Double.isFinite(previousDrain))
                                throw new AssertionError("Missing late attack provenance");
                            late.add(new Candidate(shot, previousDrain, time, normalAt, normalShot));
                        }
                        normalTick = normal ? state.serverTicks : Integer.MIN_VALUE;
                        normalAt = time;
                        normalShot = normal ? contacts.get(id) : null;
                        if (normal) {
                            if (normalShot == null) throw new AssertionError("Missing normal attack provenance");
                            normals.add(new Normal(normalShot, time, state.serverTicks));
                        }
                    }
                }
                previousDrain = time;
            });
        }
    }

    private static List<Integer> margin(DuelEnv.Game game) {
        int accepted = 0, fresh = 0;
        for (var hit : game.hits.list) {
            int amount = hit.sideA() == game.first ? 1 : -1;
            accepted += amount;
            if (!hit.upgrade()) fresh += amount;
        }
        return List.of(accepted, fresh);
    }

    static DuelEnv.Game branch(DuelEnv env, DuelEnv.Game prefix, boolean wtap) {
        var subject = (Primed) prefix.our();
        var selected = wtap ? subject.wtap.copy() : subject.copy();
        var game = env.new Game(prefix, prefix.first ? selected : prefix.a.copy(),
                prefix.first ? prefix.b.copy() : selected);
        game.trace();
        return game;
    }

    static List<DuelEnv.Hit> suffix(DuelEnv.Game source, double from, double end) {
        return source.hits.list.stream().filter(h -> h.at() >= from && h.at() <= end).toList();
    }

    private static Map<String, Object> probe(DuelEnv env, boolean first, Arena.Model policy,
                                              DuelEnv.Game source, Candidate candidate) {
        var shot = candidate.shot;
        double cutoff = candidate.deadline, end = cutoff + HORIZON;
        var values = new LinkedHashMap<String, Object>(Map.of(
                "consumeFromCut", shot.consume - cutoff, "readyFromCut", shot.ready - cutoff,
                "cut", cutoff, "originalHitMs", candidate.freshAt));
        if (shot.consume >= cutoff) {
            values.put("status", "already-too-late-at-consume");
            return values;
        }
        double earlier = Math.nextDown(cutoff);
        if (shot.ready - earlier > MAX_ADVANCE || earlier < shot.consume) {
            values.put("status", "advance-outside-bound");
            return values;
        }
        var prefix = start(env, first, policy);
        prefix.clock.runUntil(earlier);
        var changed = branch(env, prefix, false);
        var packets = changed.clock.state.packets;
        var packet = packets.stream().filter(p -> p.sequence == shot.id).findFirst().orElseThrow();
        if (packet.ready != shot.ready) throw new AssertionError("Packet readiness diverged on replay");
        int route = PeriodicComboTransport.route(packet.kind);
        boolean blocked = packets.stream().anyMatch(p -> p.sequence < packet.sequence && p.ready > earlier
                && PeriodicComboTransport.route(p.kind) == route);
        if (blocked) {
            values.put("status", "earlier-queued-packet");
            return values;
        }

        var stock = branch(env, prefix, false);
        var wtap = branch(env, prefix, true);
        stock.clock.runUntil(end);
        wtap.clock.runUntil(end);
        if (!stock.hits.list.equals(suffix(source, cutoff, end)))
            throw new AssertionError("Unchanged fork failed to replay the model match");
        packet.ready = earlier;
        packet.due = Math.min(packet.due, earlier);
        changed.clock.runUntil(cutoff);
        var now = changed.clock.state;
        boolean accepted = (changed.first ? now.lastAcceptedPacketA : now.lastAcceptedPacketB) == shot.id;
        boolean fresh = (changed.first ? now.lastFreshPacketA : now.lastFreshPacketB) == shot.id;
        changed.clock.runUntil(end);
        values.put("status", accepted ? (fresh ? "earlier-fresh" : "earlier-upgrade") : "earlier-unscored");
        values.put("wtap", margin(wtap));
        values.put("stock", margin(stock));
        values.put("advanced", margin(changed));
        return values;
    }

    private static Map<String, Object> assess(DuelEnv env, boolean first, Arena.Model model) {
        var reference = start(env, first, model);
        var ledger = new Ledger(reference);
        while (reference.clock.state.acceptedA < 100 && reference.clock.state.acceptedB < 100 && reference.advance()) {
            // Stop after the deciding drain.
        }
        double complete = reference.hits.list.get(reference.hits.list.size() - 1).at();
        var rows = new ArrayList<Map<String, Object>>();
        for (var candidate : ledger.late) {
            if (candidate.deadline + HORIZON < complete) rows.add(probe(env, first, model, reference, candidate));
        }
        int[] score = {0, 0};
        for (var hit : reference.hits.list) {
            score[hit.sideA() == first ? 0 : 1]++;
            if (score[0] == 100 || score[1] == 100) break;
        }
        DuelEnv.Player uninstrumented = model == null ? new DuelEnv.Player("wtap", 0, env.rtt, 0)
                : new Arena.Neural(model, env.rtt);
        var expected = BoxingDoubleSearch.play(env, first, uninstrumented, 100);
        if (score[0] != expected.own() || score[1] != expected.other())
            throw new AssertionError("Read-only instrumentation changed the regulation score");
        return Map.of("score", score, "cases", rows);
    }

    public static void main(String[] args) throws Exception {
        var model = Arena.Model.decode(Files.readString(Path.of(args[0])).strip());
        var wires = Path.of(args[1]);
        var seeds = Path.of(args[2]);
        for (int i = 3; i < args.length; i++) {
            var env = BoxingDoubleSearch.environment(Path.of(args[i]), 20, 20, 180_000);
            BoxingJumpTimingAudit.wireTiming(env, wires.resolve(env.clockId + ".txt"));
            BoxingJumpTimingAudit.seedTracker(env, seeds.resolve(env.clockId + ".txt"));
            for (boolean first : new boolean[]{true, false}) {
                var wtap = assess(env, first, null);
                var frozen = assess(env, first, model);
                System.out.println(Json.encode(Map.of("clock", env.clockId, "seat", first ? "a" : "b",
                        "wtap", wtap, "frozen", frozen)));
            }
        }
    }
}
