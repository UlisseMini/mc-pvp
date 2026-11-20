import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lab.Json;

/** Record comparable score and fresh-damage drain timing from both generated arms. */
final class BoxingJumpTimingAudit {
    private record PendingContact(double ready, Map<String, Object> click) {}

    private record AttackDispatch(PeriodicComboFightModel.PacketTiming wire, double extraA, double extraB,
                                  double start, double end)
            implements PeriodicComboFightModel.PacketTiming {
        @Override public double arrival(int route, double time, double leg) {
            return wire.arrival(route, time, leg);
        }

        @Override public PeriodicComboFightModel.PacketTimingPlan planForPacket(int route, double time, double leg,
                PeriodicComboFightModel.TimingSemantic semantic, int packetId) {
            boolean shifted = route < 2 && packetId == 2 && time >= start && time < end;
            double adjusted = shifted ? leg + (route == 0 ? extraA : extraB) : leg;
            return wire.planForPacket(route, time, adjusted, semantic, packetId);
        }

        @Override public AttackDispatch deepCopy() { return new AttackDispatch(wire.deepCopy(), extraA, extraB, start, end); }
    }

    private static final class Geometry {
        final List<List<Object>> drains = new ArrayList<>();
        final List<List<Object>> impulses = new ArrayList<>();
        final List<Map<String, Object>> clicks = new ArrayList<>();
        final List<PendingContact> pending = new ArrayList<>();
        final List<List<Integer>> releases = List.of(new ArrayList<>(), new ArrayList<>());
        final int[] releasedHeads = new int[2];

        void input(boolean subject, PeriodicComboClock.Control control) {
            int side = subject ? 0 : 1;
            if (control.key() == PeriodicComboFightModel.R) releasedHeads[side]++;
            else if (releasedHeads[side] > 0) {
                releases.get(side).add(releasedHeads[side]);
                releasedHeads[side] = 0;
            }
        }

        void consume(PeriodicComboClock.ClickEvent event, PeriodicComboFightModel.State s, boolean first) {
            boolean a = event.fromA();
            var body = a ? s.a : s.b;
            var other = a ? s.b : s.a;
            var view = a ? s.ab : s.ba;
            var actual = new PeriodicComboFightModel.View(other.q.x());
            actual.y = other.q.y();
            actual.z = other.q.z();
            double ray = PeriodicComboFightModel.ray(body, view);
            var click = new LinkedHashMap<String, Object>();
            click.put("side", a == first ? 0 : 1);
            click.put("ms", event.time());
            click.put("tick", event.tick());
            click.put("drain", null);
            click.put("nearest", PeriodicComboFightModel.nearest(body.q, view));
            click.put("actual_nearest", PeriodicComboFightModel.nearest(body.q, actual));
            click.put("eligible", event.eligible());
            click.put("contact", event.contact());
            click.put("ray", Double.isFinite(ray) ? ray : null);
            click.put("own", List.of(body.q.x(), body.q.y(), body.q.z()));
            click.put("view", List.of(view.x, view.y, view.z));
            click.put("actual", List.of(actual.x, actual.y, actual.z));
            if (event.contact()) {
                var packet = s.packets.get(s.packets.size() - 1);
                if (packet.kind != (a ? 3 : 7) || packet.sequence != s.seq - 1)
                    throw new AssertionError("Expected the modeled attack at the queue tail");
                pending.add(new PendingContact(packet.ready, click));
            }
            clicks.add(click);
        }

        void drain(double time, int tick) {
            drains.add(List.of(tick, time));
            pending.removeIf(p -> {
                if (p.ready > time) return false;
                p.click.put("drain", tick);
                return true;
            });
        }
    }

    static void wireTiming(DuelEnv env, Path delayFile) throws Exception {
        var times = List.of(new ArrayList<Double>(), new ArrayList<Double>(), new ArrayList<Double>(), new ArrayList<Double>());
        var residuals = List.of(new ArrayList<Double>(), new ArrayList<Double>(), new ArrayList<Double>(), new ArrayList<Double>());
        for (var line : Files.readAllLines(delayFile)) {
            var fields = line.split(" ");
            int route = Integer.parseInt(fields[0]);
            times.get(route).add(Double.parseDouble(fields[1]));
            residuals.get(route).add(Double.parseDouble(fields[2]));
        }
        var state = env.initial.state;
        state.extraLatency = new double[4];
        state.packetTiming = new EmpiricalComboTiming(
                times.stream().map(t -> t.stream().mapToDouble(x -> x).toArray()).toArray(double[][]::new),
                residuals.stream().map(t -> t.stream().mapToDouble(x -> x).toArray()).toArray(double[][]::new), env.limit);
        double attackDelay = Double.parseDouble(System.getProperty("lab.boxing.attackExtraMs", "0"));
        if (!Double.isFinite(attackDelay) || attackDelay < 0) throw new IllegalArgumentException("Invalid attack dispatch delay");
        shiftAttackTiming(state, attackDelay, attackDelay);
    }

    static void shiftAttackTiming(PeriodicComboFightModel.State state, double a, double b) {
        shiftAttackTiming(state, a, b, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY);
    }

    static void shiftAttackTiming(PeriodicComboFightModel.State state, double a, double b, double start, double end) {
        if (!Double.isFinite(a) || !Double.isFinite(b)) throw new IllegalArgumentException("Invalid attack timing shift");
        if (!(start < end)) throw new IllegalArgumentException("Attack timing interval ends before it starts");
        if (a != 0 || b != 0) {
            if (state.packetTiming == null) throw new IllegalArgumentException("Attack shift requires packet timing");
            state.packetTiming = new AttackDispatch(state.packetTiming, a, b, start, end);
        }
    }

    static void seedTracker(DuelEnv env, Path file) throws Exception {
        String[] fields = Files.readString(file).strip().split(" ");
        var s = env.initial.state;
        s.serverTicks = Integer.parseInt(fields[0]);
        s.teleportAgeA = Integer.parseInt(fields[1]);
        s.teleportAgeB = Integer.parseInt(fields[2]);
        env.initial.config.trackerAFirst = Boolean.parseBoolean(fields[3]);
    }

    private static Map<String, Object> play(DuelEnv env, boolean first, boolean jump, boolean geometry,
                                            int ownPhase, int defenderPhase) {
        var subject = new DuelEnv.Player(jump ? "reset" : "wtap", 0, env.rtt, 0);
        var opponent = new DuelEnv.Player("wtap", 0, env.otherRtt, 0);
        var game = env.new Game(first, subject, opponent);
        if (ownPhase != 0) game.clock.shiftFutureClientOpportunities(first, ownPhase);
        if (defenderPhase != 0) game.clock.shiftFutureClientOpportunities(!first, defenderPhase);
        game.trace();
        int[] counts = new int[2], fresh = new int[2];
        var hits = new ArrayList<List<Object>>();
        var presses = List.of(new ArrayList<Double>(), new ArrayList<Double>());
        var trace = geometry ? new Geometry() : null;
        game.clock.setClickAudit(event -> {
            if (trace != null && event.action().equals("CONSUME")) trace.consume(event, game.clock.state, first);
            if (!event.action().equals("ADMIT") || counts[0] == 100 || counts[1] == 100) return;
            int side = event.fromA() == first ? 0 : 1;
            presses.get(side).add(event.time());
        });
        game.clock.setAudit((time, event, state) -> {
            if (trace != null && event.equals("SERVER_DRAIN")) trace.drain(time, state.serverTicks);
            if (trace != null && (event.equals("POLICYA") || event.equals("POLICYB"))) {
                boolean a = event.equals("POLICYA");
                trace.input(a == first, game.clock.appliedControl(a));
            }
            int seen = game.hits.list.size();
            game.hits.event(time, event, state);
            while (seen < game.hits.list.size() && counts[0] < 100 && counts[1] < 100) {
                var hit = game.hits.list.get(seen++);
                int side = hit.sideA() == first ? 0 : 1;
                hits.add(List.of(hit.at(), state.serverTicks, side, hit.upgrade()));
                counts[side]++;
                if (!hit.upgrade()) {
                    fresh[side]++;
                    if (trace != null) {
                        var impulse = hit.sideA() ? state.lastFreshB : state.lastFreshA;
                        trace.impulses.add(List.of(state.serverTicks, side, impulse.x(), impulse.y(), impulse.z(),
                                impulse.victimBefore().y(), impulse.victimBefore().onGround()));
                    }
                }
            }
        });
        while (counts[0] < 100 && counts[1] < 100 && game.advance()) {
            // Each server end follows the drain scored by the audit callback.
        }
        double finish = hits.isEmpty() ? game.clock.time() : (double) hits.get(hits.size() - 1).get(0);
        double rateStart = presses.stream().mapToDouble(p -> p.isEmpty() ? finish : p.get(0)).max().orElseThrow();
        double factor = finish > rateStart ? 1000 / (finish - rateStart) : 0;
        var result = new LinkedHashMap<String, Object>(Map.of("scores", counts, "fresh", fresh, "jumps", subject.jumps,
                "cps", presses.stream().map(p -> p.stream().filter(t -> t >= rateStart).count() * factor).toList(),
                "hits", hits));
        if (trace != null) {
            result.put("drains", trace.drains);
            result.put("clicks", trace.clicks);
            result.put("impulses", trace.impulses);
            result.put("releases", trace.releases);
        }
        return result;
    }

    public static void main(String[] args) throws Exception {
        boolean geometry = args[0].equals("--geometry");
        int wireArg = geometry ? 1 : 0;
        boolean wire = args[wireArg].equals("--wire");
        int firstTape = wire ? wireArg + 2 : wireArg;
        String trackerSeeds = System.getProperty("lab.boxing.trackerSeeds");
        int rtt = Integer.getInteger("lab.boxing.rtt", 20);
        String profile = System.getProperty("lab.boxing.profile", "mmc");
        int own = Integer.getInteger("lab.boxing.regOwnPhase", 0);
        int defender = Integer.getInteger("lab.boxing.regOtherPhase", 0);
        for (int i = firstTape; i < args.length; i++) {
            var env = BoxingDoubleSearch.environment(Path.of(args[i]), profile, rtt, 20, 180_000);
            if (wire) wireTiming(env, Path.of(args[wireArg + 1], env.clockId + ".txt"));
            if (trackerSeeds != null) seedTracker(env, Path.of(trackerSeeds, env.clockId + ".txt"));
            for (boolean first : new boolean[]{true, false}) {
                System.out.println(Json.encode(Map.of("clock", env.clockId, "seat", first ? "a" : "b",
                        "explicitPolls", env.initial.timing.explicitPolls, "threshold", env.initial.config.hitTicks,
                        "wtap", play(env, first, false, geometry, own, defender),
                        "jump", play(env, first, true, geometry, own, defender))));
            }
        }
    }
}
