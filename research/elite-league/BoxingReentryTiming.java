import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lab.Json;

/** Fork grounded approaches and change only the first opportunity to admit local clicks. */
final class BoxingReentryTiming {
    private static final int DURATION = 12_000;
    private static final int WINDOW = 2_500;
    private static final int[] PHASES = {0, 5, 15};
    private static final int[] WITNESS_PHASES = {0, 5, 10, 15, 20, 25};

    private record Delay(String label, int milliseconds, boolean fromHurt) {}
    private record Witness(int cps, int rtt, boolean first, double time, String arm) {}

    private static List<Delay> delays() {
        var arms = new ArrayList<Delay>();
        arms.add(new Delay("wtap", 0, false));
        for (int ms : new int[]{50, 100, 150, 200, 250, 300, 350}) {
            arms.add(new Delay("wait-" + ms, ms, false));
        }
        for (int ms : new int[]{0, 50, 100, 150}) {
            arms.add(new Delay("hurt-" + ms, ms, true));
        }
        return arms;
    }

    private static final class Delayed extends DuelEnv.Player {
        private final double start;
        private final Delay delay;
        private boolean opened;

        Delayed(DuelEnv.Player stock, double start, Delay delay) {
            super(stock.style, stock.mask, stock.rtt, stock.wake);
            handled = stock.handled;
            releaseUntil = stock.releaseUntil;
            firstClick = stock.firstClick;
            this.start = start;
            this.delay = delay;
        }

        @Override public boolean allowInjection(PeriodicComboClock.Observation own, String source) {
            if (!delay.fromHurt) return own.time() >= start + delay.milliseconds;
            opened |= own.time() >= start + 600 || own.lastOwnHurtTime() >= start
                    && own.time() >= own.lastOwnHurtTime() + delay.milliseconds;
            return opened;
        }
    }

    private record Outcome(List<DuelEnv.Hit> hits, Map<String, Object> score) {}

    static int openingChain(List<DuelEnv.Hit> hits, boolean side) {
        return openingChain(hits, side, 1000);
    }

    static int openingChain(List<DuelEnv.Hit> hits, boolean side, double maximumGap) {
        int count = 0;
        double last = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < hits.size();) {
            double time = hits.get(i).at();
            int fresh = 0;
            boolean returned = false;
            while (i < hits.size() && hits.get(i).at() == time) {
                var hit = hits.get(i++);
                if (hit.sideA() == side && !hit.upgrade()) fresh++;
                else if (hit.sideA() != side) returned = true;
            }
            if (count == 0 && fresh == 0) continue;
            if (returned || count > 0 && time - last > maximumGap) break;
            if (fresh > 0) {
                count += fresh;
                last = time;
            }
        }
        return count;
    }

    private static Outcome fork(DuelEnv env, DuelEnv.Game prefix, double start, Delay delay,
                                int ownPhase, int otherPhase, boolean detailed) {
        var subject = new Delayed(prefix.our(), start, delay);
        boolean first = prefix.first;
        var game = env.new Game(prefix, first ? subject : prefix.a.copy(), first ? prefix.b.copy() : subject);
        if (ownPhase != 0) game.clock.shiftFutureClientOpportunities(first, ownPhase);
        if (otherPhase != 0) game.clock.shiftFutureClientOpportunities(!first, otherPhase);
        game.trace();
        int[] presses = new int[2];
        int[] held = {0};
        var trace = new ArrayList<Object>();
        game.clock.setClickAudit(event -> {
            if (event.action().equals("ADMIT")) presses[event.fromA() == first ? 0 : 1]++;
            if (event.fromA() == first && event.action().equals("DEFER")) held[0]++;
            if (detailed && event.action().equals("CONSUME")) {
                var state = game.clock.state;
                var body = event.fromA() ? state.a : state.b;
                var view = event.fromA() ? state.ab : state.ba;
                double ray = PeriodicComboFightModel.ray(body, view);
                trace.add(Arrays.asList(event.time() - start, "click", event.fromA() == first,
                        event.eligible(), event.contact(), Double.isFinite(ray) ? ray : null,
                        body.q.onGround(), List.of(body.q.x(), body.q.y(), body.q.z()),
                        List.of(view.x, view.y, view.z)));
            }
        });
        var flight = new BoxingTradeSkills.Landings(game.clock.state);
        game.clock.setAudit((t, stage, state) -> {
            int before = game.hits.list.size();
            game.hits.event(t, stage, state);
            flight.event(t, stage, state);
            if (!detailed) return;
            for (int i = before; i < game.hits.list.size(); i++) {
                var hit = game.hits.list.get(i);
                trace.add(List.of(t - start, "accepted", hit.sideA() == first, hit.upgrade()));
            }
            if (stage.equals("SERVER_ENTITY")) {
                int ourImmune = first ? state.ownResistant : state.resistant;
                int otherImmune = first ? state.resistant : state.ownResistant;
                if (ourImmune == state.freshThreshold) trace.add(List.of(t - start, "fresh-ready", false));
                if (otherImmune == state.freshThreshold) trace.add(List.of(t - start, "fresh-ready", true));
            }
        });
        game.clock.runUntil(start + WINDOW);
        var score = BoxingOpeningRecoil.score(game.hits.list, first);
        int ownAccepted = 0, enemyAccepted = 0, ownFresh = 0, enemyFresh = 0;
        for (var hit : game.hits.list) {
            if (hit.sideA() == first) {
                ownAccepted++;
                if (!hit.upgrade()) ownFresh++;
            } else {
                enemyAccepted++;
                if (!hit.upgrade()) enemyFresh++;
            }
        }
        var values = new LinkedHashMap<>(score.values());
        values.put("counts", List.of(ownAccepted, enemyAccepted, ownFresh, enemyFresh));
        values.put("firstChains", List.of(openingChain(game.hits.list, first), openingChain(game.hits.list, !first)));
        values.put("presses", presses);
        values.put("deferrals", held[0]);
        values.put("flight", flight.values(first));
        if (detailed) values.put("trace", trace);
        return new Outcome(game.hits.list, values);
    }

    private static DuelEnv.Game mirror(DuelEnv env, boolean first) {
        var game = env.new Game(first, new DuelEnv.Player("wtap", 0, env.rtt, 0),
                new DuelEnv.Player("wtap", 0, env.otherRtt, 0));
        game.trace();
        return game;
    }

    private static void measure(DuelEnv env, boolean first, int cps, Witness witness) {
        var mirror = mirror(env, first);
        mirror.clock.runUntil(env.limit);
        var prefix = mirror(env, first);
        double previous = Double.NEGATIVE_INFINITY;
        int previousHits = -1;
        while (prefix.clock.advanceToBeforeNextHead(first, env.limit - WINDOW)) {
            double t = prefix.clock.time();
            var state = prefix.clock.state;
            int hits = prefix.hits.list.size();
            var own = first ? state.a : state.b;
            var remote = first ? state.b : state.a;
            var view = first ? state.ab : state.ba;
            var otherView = first ? state.ba : state.ab;
            double nearest = PeriodicComboFightModel.nearest(own.q, view);
            double dx = view.x - own.q.x(), dz = view.z - own.q.z();
            double toward = (own.q.vx() * dx + own.q.vz() * dz) / Math.hypot(dx, dz);
            boolean approach = hits != previousHits && t - previous >= 750 && own.q.onGround() && remote.q.onGround()
                    && state.ownResistant <= state.freshThreshold && state.resistant <= state.freshThreshold
                    && nearest > 3.05 && nearest <= 3.85 && toward > 0;
            if (approach) {
                previous = t;
                previousHits = hits;
            }
            if (approach && (witness == null || t == witness.time)) {
                var expected = mirror.hits.list.subList(hits, mirror.hits.list.size()).stream()
                        .takeWhile(hit -> hit.at() <= t + WINDOW).toList();
                var choices = delays().stream().filter(d -> witness == null || d.label.equals("wtap")
                        || d.label.equals(witness.arm)).toList();
                int[] ownPhases = witness == null ? new int[]{0} : new int[]{0, 5, 15};
                int[] otherPhases = witness == null ? PHASES : WITNESS_PHASES;
                for (int ownPhase : ownPhases) for (int phase : otherPhases) {
                    var scores = new LinkedHashMap<String, Object>();
                    for (Delay delay : choices) {
                        var result = fork(env, prefix, t, delay, ownPhase, phase, witness != null);
                        if (ownPhase == 0 && phase == 0 && delay.label.equals("wtap") && !result.hits.equals(expected)) {
                            throw new AssertionError("Unchanged fork diverged from W-tap at " + env.clockId + " " + t);
                        }
                        scores.put(delay.label, result.score);
                    }
                    System.out.println(Json.encode(Map.ofEntries(
                            Map.entry("clock", env.clockId), Map.entry("rtt", env.rtt), Map.entry("cps", cps),
                            Map.entry("seat", first ? "a" : "b"), Map.entry("atMs", t), Map.entry("earlierHits", hits),
                            Map.entry("phaseMs", phase), Map.entry("ownPhaseMs", ownPhase),
                            Map.entry("nearest", nearest), Map.entry("toward", toward),
                            Map.entry("defenderNearest", PeriodicComboFightModel.nearest(remote.q, otherView)),
                            Map.entry("queued", List.of(prefix.clock.queuedClicks(first), prefix.clock.queuedClicks(!first))),
                            Map.entry("arms", scores))));
                }
                if (witness != null) return;
            }
            if (!prefix.clock.runHeadInterval(first, env.limit - WINDOW)) break;
        }
        if (witness != null) throw new IllegalArgumentException("No matching W-tap cut at " + witness.time);
    }

    public static void main(String[] args) throws Exception {
        if (args[0].equals("--witness")) {
            var witness = new Witness(Integer.parseInt(args[2]), Integer.parseInt(args[3]), args[4].equals("a"),
                    Double.parseDouble(args[5]), args[6]);
            var tape = DuelEnv.Tape.read(Path.of(args[1]), DURATION);
            measure(BoxingTradeSkills.env(tape, witness.rtt, witness.cps, 6), witness.first, witness.cps, witness);
            return;
        }
        for (String path : args) {
            var tape = DuelEnv.Tape.read(Path.of(path), DURATION);
            for (int cps : new int[]{10, 20}) for (int ping : new int[]{0, 20, 80}) {
                var env = BoxingTradeSkills.env(tape, ping, cps, 6);
                for (boolean first : new boolean[]{true, false}) measure(env, first, cps, null);
            }
        }
    }
}
