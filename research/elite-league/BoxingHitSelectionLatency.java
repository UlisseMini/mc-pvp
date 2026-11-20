import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.DoubleSupplier;
import lab.Json;
import lab.MouseLook;

/** Offline latency factorial for the opening hurt-cue response. */
final class BoxingHitSelectionLatency {
    private static final int END = 3100;
    private static final int WIRE_COVERAGE = 180_000;
    private static final int[] RTTS = {0, 20, 80};
    private static final int[][] PHASES = {{0, 0}, {-5, 0}, {5, 0}, {0, -5}, {0, 5}};
    private static final float LOOK_PITCH = Integer.getInteger("lab.hitSelectionLatency.lookPitch", 0);
    private static final int TRACE_DELAY = Integer.getInteger("lab.hitSelectionLatency.traceDelay", -1);

    private static final class Wait extends DuelEnv.Player {
        final int milliseconds;
        boolean open;

        Wait(int rtt, int milliseconds) {
            super("wtap", 0, rtt, 0);
            this.milliseconds = milliseconds;
        }

        @Override public boolean allowInjection(PeriodicComboClock.Observation own, String source) {
            open |= own.time() >= own.lastOwnHurtTime() + milliseconds
                    && Double.isFinite(own.lastOwnHurtTime());
            return open || own.time() >= 1100;
        }
    }

    private static final class LookAway extends DuelEnv.Player {
        final int milliseconds;
        DoubleSupplier time;
        double firstHurt = Double.POSITIVE_INFINITY;

        LookAway(int rtt, int milliseconds) {
            super("wtap", 0, rtt, 0);
            this.milliseconds = milliseconds;
        }

        @Override public void received(PeriodicComboClock.PacketObservation packet) {
            if (packet.kind() == PeriodicComboClock.ReceivedKind.OWN_HURT)
                firstHurt = Math.min(firstHurt, packet.handledAt());
        }

        @Override public MouseLook mouseLook(float yaw, float pitch, float targetYaw, float targetPitch, double elapsedMs) {
            double now = time.getAsDouble();
            if (now < Math.min(firstHurt + milliseconds, 1100)) targetPitch = LOOK_PITCH;
            return super.mouseLook(yaw, pitch, targetYaw, targetPitch, elapsedMs);
        }
    }

    private record Result(List<DuelEnv.Hit> hits, List<List<Long>> presses, double[] landings,
                          List<List<Object>> contacts, List<List<Object>> impulses) {
        int margin(boolean seat) {
            return hits.stream().mapToInt(h -> h.sideA() == seat ? 1 : -1).sum();
        }

        int counterChain(boolean seat) {
            if (hits.isEmpty() || hits.get(0).sideA() == seat || hits.get(0).upgrade()) return 0;
            int firstReply = 1;
            while (firstReply < hits.size() && hits.get(firstReply).upgrade()
                    && hits.get(firstReply).sideA() == seat) firstReply++;
            if (firstReply == hits.size()) return 0;
            var reply = hits.get(firstReply);
            double lag = reply.at() - hits.get(0).at();
            if (reply.sideA() != seat || lag <= 0 || lag > 275) return 0;
            return BoxingReentryTiming.openingChain(hits.subList(firstReply, hits.size()), seat, 1100);
        }

        Map<String, Object> values(boolean seat) {
            var values = new LinkedHashMap<String, Object>();
            values.put("hits", hits.stream().map(h -> List.of(h.at(), h.sideA() == seat, h.upgrade())).toList());
            values.put("presses", presses.stream().map(List::size).toList());
            values.put("margin", margin(seat));
            values.put("counterChain", counterChain(seat));
            values.put("openingChain", BoxingReentryTiming.openingChain(hits, seat, 1100));
            values.put("landings", landings);
            if (TRACE_DELAY >= 0) {
                values.put("polls", presses);
                values.put("contacts", contacts);
                values.put("impulses", impulses);
            }
            return values;
        }
    }

    private static Result run(DuelEnv env, boolean seat, int own, int other, int[] phase, int delay) {
        DuelEnv.Player subject;
        if (delay < 0) subject = Evolve.WT.create(own, 0);
        else if (LOOK_PITCH == 0) subject = new Wait(own, delay);
        else subject = new LookAway(own, delay);
        var game = env.new Game(seat, subject, Evolve.WT.create(other, 0));
        if (subject instanceof LookAway look) look.time = game.clock::time;
        if (phase[0] != 0) game.clock.shiftFutureClientOpportunities(seat, phase[0]);
        if (phase[1] != 0) game.clock.shiftFutureClientOpportunities(!seat, phase[1]);
        game.trace();
        var flight = new BoxingTradeSkills.Landings(game.clock.state);
        var impulses = new ArrayList<List<Object>>();
        game.clock.setAudit((time, stage, state) -> {
            game.hits.event(time, stage, state);
            flight.event(time, stage, state);
            if (TRACE_DELAY >= 0 && stage.equals("SERVER_DRAIN")) {
                for (boolean fromA : new boolean[]{true, false}) {
                    var kick = fromA ? state.lastFreshB : state.lastFreshA;
                    if (kick != null && kick.time() == time)
                        impulses.add(List.of(time, seat == fromA, kick.x(), kick.y(), kick.z()));
                }
            }
        });
        var presses = List.of(new ArrayList<Long>(), new ArrayList<Long>());
        var contacts = new ArrayList<List<Object>>();
        game.clock.setClickAudit(e -> {
            if (e.action().equals("ADMIT")) presses.get(e.fromA() == seat ? 0 : 1).add(e.admittedNanos());
            if (TRACE_DELAY >= 0 && e.action().equals("CONSUME")) {
                var s = game.clock.state;
                var body = e.fromA() ? s.a : s.b;
                var view = e.fromA() ? s.ab : s.ba;
                double ray = PeriodicComboFightModel.ray(body, view);
                contacts.add(List.of(e.time(), e.fromA() == seat, e.eligible(), e.contact(), e.admittedNanos(),
                        PeriodicComboFightModel.nearest(body.q, view), Double.isFinite(ray) ? ray : -1,
                        body.pitch, body.q.onGround(), body.q.vx(), body.q.vy(),
                        e.contact() ? s.packets.get(s.packets.size()-1).ready : -1));
            }
        });
        game.clock.runUntil(Math.nextDown((double)END));
        int idx = seat ? 0 : 1;
        return new Result(game.hits.list, List.copyOf(presses), new double[]{
                Double.isFinite(flight.landed[idx]) ? flight.landed[idx] : -1,
                Double.isFinite(flight.landed[1-idx]) ? flight.landed[1-idx] : -1}, contacts, impulses);
    }

    public static void main(String[] args) throws Exception {
        var root = Path.of(args[0]);
        var tape = DuelEnv.Tape.read(Path.of(args[1]), END);
        var stageFits = Path.of(args[2]);
        for (int a : RTTS) for (int b : RTTS) {
            var env = new DuelEnv(tape, "mmc-range", a, b, false, 29, true, 10, 6, 0, WIRE_COVERAGE);
            env.initial.timing.mouseInput = true;
            BoxingJumpTimingAudit.wireTiming(env, root.resolve("wire").resolve(tape.id() + ".txt"));
            BoxingJumpTimingAudit.seedTracker(env, root.resolve("seeds").resolve(tape.id() + ".txt"));
            BoxingMirrorForensics.loadPacketModel(env.initial.state, stageFits, "raw-queue");
            for (boolean seat : new boolean[]{true, false}) for (var phase : PHASES) {
                int own = seat ? a : b, other = seat ? b : a;
                var baseline = run(env, seat, own, other, phase, -1);
                var waits = new ArrayList<>();
                for (int delay = 0; delay <= 200; delay += 5) {
                    if (TRACE_DELAY >= 0 && delay != TRACE_DELAY) continue;
                    var result = run(env, seat, own, other, phase, delay);
                    if (LOOK_PITCH != 0 && !baseline.presses().equals(result.presses()))
                        throw new AssertionError("The mouse move changed a physical press schedule");
                    waits.add(result.values(seat));
                }
                System.out.println(Json.encode(Map.of("clock", tape.id(), "seat", seat ? "a" : "b",
                        "rtt", List.of(own, other), "phase", phase, "wtap", baseline.values(seat), "waits", waits)));
            }
        }
    }
}
