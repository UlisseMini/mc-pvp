import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lab.DuelNetwork;
import lab.Json;

/** Separate horizontal recoil from the ordinary inputs that produce it. */
final class BoxingSprintAttribution {
    private record Phase(int own, int other) {}
    private record Window(int skip, int heads) {
        List<Integer> label() { return List.of(skip, heads); }
    }

    private static final List<Phase> PHASES = phases();
    private static final List<Window> WINDOWS = windows();
    private static final boolean TAP_FACTORIAL = Boolean.getBoolean("lab.boxing.sprintTapFactorial");

    private static List<Phase> phases() {
        var result = new ArrayList<Phase>();
        for (int own : new int[]{0, -5, 5}) for (int other : new int[]{0, -5, 5}) {
            result.add(new Phase(own, other));
        }
        return List.copyOf(result);
    }

    private static List<Window> windows() {
        var result = new ArrayList<Window>();
        result.add(new Window(0, 12));
        for (int skip = 0; skip < 12; skip++) for (int length : new int[]{1, 2, 3}) {
            result.add(new Window(skip, length));
        }
        return List.copyOf(result);
    }

    private static final class Ctrl extends DuelEnv.Player {
        final Arena.Neural base;
        final Window window;
        final DuelEnv.Player tap;
        final int tapMask;
        int heads, changed;

        Ctrl(Arena.Neural base, Window window, DuelEnv.Player tap, int tapMask) {
            super(base.style, 0, base.rtt, base.wake);
            this.base = base;
            this.window = window;
            this.tap = tap;
            this.tapMask = tapMask;
            firstClick = base.firstClick;
        }

        @Override public PeriodicComboClock.Control input(PeriodicComboClock.Observation obs) {
            var natural = base.input(obs);
            int head = heads++;
            var standard = tap == null ? null : tap.input(obs);
            if (head < window.skip || head >= window.skip + window.heads) return natural;
            int key = natural.key();
            if (standard == null && key == PeriodicComboFightModel.W) {
                key = PeriodicComboFightModel.S;
            } else if (standard != null) {
                int factor = standard.key() == PeriodicComboFightModel.R ? 1 : 2;
                if ((tapMask & factor) != 0) key = standard.key();
            }
            if (key == natural.key()) return natural;
            changed++;
            base.net.applied(DuelNetwork.pack(key, (int) Math.signum(natural.strafe()), natural.click(), natural.jump()));
            return new PeriodicComboClock.Control(key, natural.strafe(), natural.click(), natural.jump());
        }

        @Override public boolean injected(PeriodicComboClock.Observation obs, double time) {
            return base.injected(obs, time);
        }

        @Override public void received(PeriodicComboClock.PacketObservation packet) { base.received(packet); }
    }

    private record Launch(double time, double x, double y, double z, long attack) {
        List<Object> values() { return List.of(time, x, y, z, attack); }
        boolean sameHorizontal(Launch other) { return x == other.x && z == other.z; }
    }

    private record Outcome(BoxingLateReturns.Score score, Launch launch, List<DuelEnv.Hit> prefix, int changed) {
        Map<String, Object> values() {
            var out = new LinkedHashMap<>(score.values());
            out.put("launch", launch == null ? null : launch.values());
            out.put("changed", changed);
            return out;
        }
    }

    private static Outcome run(BoxingDrill.Checkpoint cp, Arena.Model model, Phase phase,
                               Window ctrl, Launch horizontalSource) {
        return run(cp, model, phase, ctrl, horizontalSource, 0);
    }

    private static Outcome run(BoxingDrill.Checkpoint cp, Arena.Model model, Phase phase,
                               Window ctrl, Launch horizontalSource, int tapMask) {
        var game = cp.suffix(model);
        Ctrl subject = ctrl == null ? null : new Ctrl((Arena.Neural) game.our(), ctrl,
                tapMask == 0 ? null : cp.state.our().copy(), tapMask);
        if (subject != null) {
            var original = game;
            game = cp.env.new Game(original, original.first ? subject : original.a.copy(),
                    original.first ? original.b.copy() : subject);
            game.trace();
            game.hits.list.addAll(original.hits.list);
        }
        if (phase.own != 0) game.clock.shiftFutureClientOpportunities(game.first, phase.own);
        if (phase.other != 0) game.clock.shiftFutureClientOpportunities(!game.first, phase.other);
        var branch = game;
        var launch = new Launch[1];
        var leading = new ArrayList<DuelEnv.Hit>(branch.hits.list);
        var sequence = new long[]{branch.clock.state.seq};
        var returned = new boolean[]{leading.stream().anyMatch(hit -> hit.sideA() != branch.first)};
        branch.clock.setAudit((time, stage, state) -> {
            int before = branch.hits.list.size();
            branch.hits.event(time, stage, state);
            for (int i = before; launch[0] == null && !returned[0] && i < branch.hits.list.size(); i++) {
                var hit = branch.hits.list.get(i);
                leading.add(hit);
                if (hit.sideA() != branch.first) {
                    returned[0] = true;
                    break;
                }
                if (hit.upgrade()) continue;
                int kind = branch.first ? 4 : 8;
                var emitted = state.packets.stream().filter(p -> p.kind == kind && p.sequence >= sequence[0]).toList();
                if (emitted.size() != 1) throw new AssertionError("Ambiguous first velocity");
                var packet = emitted.get(0);
                long attack = branch.first ? state.lastFreshPacketA : state.lastFreshPacketB;
                if (horizontalSource != null) {
                    if (time != horizontalSource.time) throw new AssertionError("Incomparable launch drains");
                    packet.vx = horizontalSource.x;
                    packet.vz = horizontalSource.z;
                }
                launch[0] = new Launch(time, packet.vx, packet.vy, packet.vz, attack);
            }
            sequence[0] = state.seq;
        });
        branch.clock.runUntil(cp.env.limit);
        return new Outcome(BoxingLateReturns.score(branch, cp.landmark, 100.0 * branch.clock.config.hitTicks + 100),
                launch[0], leading, subject == null ? 0 : subject.changed);
    }

    private static Map<String, Object> phase(BoxingDrill.Checkpoint cp, Arena.Model model, Phase phase) {
        var wtap = run(cp, null, phase, null, null);
        var movement = run(cp, model, phase, null, null);
        var output = new LinkedHashMap<String, Object>();
        output.put("phase", List.of(phase.own, phase.other));
        output.put("wtap", wtap.values());
        output.put("movement", movement.values());
        if (TAP_FACTORIAL) {
            var variants = new LinkedHashMap<String, Object>();
            for (int length : new int[]{12, Integer.MAX_VALUE}) for (int mask = 1; mask <= 3; mask++) {
                var candidate = run(cp, model, phase, new Window(0, length), null, mask);
                if (mask == 3 && length == Integer.MAX_VALUE
                        && (!candidate.score.equals(wtap.score) || !java.util.Objects.equals(wtap.launch, candidate.launch))) {
                    throw new AssertionError("Combined W-tap release and chase fail to reproduce W-tap");
                }
                String lengthName = length == 12 ? "first12" : "all";
                variants.put(lengthName + "-" + mask, candidate.values());
            }
            output.put("tapFactors", variants);
            return output;
        }
        if (wtap.launch != null && movement.launch != null && wtap.launch.time == movement.launch.time
                && !wtap.launch.sameHorizontal(movement.launch)) {
            var movementRecoil = run(cp, model, phase, null, wtap.launch);
            var wtapRecoil = run(cp, null, phase, null, movement.launch);
            assertSamePrefix(movement, movementRecoil);
            assertSamePrefix(wtap, wtapRecoil);
            output.put("movementWithWtapRecoil", movementRecoil.values());
            output.put("wtapWithMovementRecoil", wtapRecoil.values());
        }
        var inputs = new ArrayList<Map<String, Object>>();
        for (var window : WINDOWS) {
            var candidate = run(cp, model, phase, window, null);
            if (candidate.changed == 0 && (!candidate.score.equals(movement.score)
                    || !java.util.Objects.equals(candidate.launch, movement.launch))) {
                throw new AssertionError("Unchanged wrapper disagrees with original policy");
            }
            inputs.add(candidate.values());
        }
        output.put("ctrl", inputs);
        return output;
    }

    private static void assertSamePrefix(Outcome control, Outcome altered) {
        if (!control.prefix.equals(altered.prefix) || control.launch.attack != altered.launch.attack
                || control.launch.y != altered.launch.y) {
            throw new AssertionError("Horizontal swap changed the launch or its past");
        }
    }

    public static void main(String[] args) throws Exception {
        var model = Arena.Model.decode(Files.readString(Path.of(args[0])).strip());
        int rtt = Integer.parseInt(args[1]);
        for (int i = 4; i < args.length; i++) {
            var env = BoxingEdgeHitSelect.environment(Path.of(args[i]), rtt, 20_000,
                    Path.of(args[2]), Path.of(args[3]));
            for (boolean first : new boolean[]{true, false}) {
                for (var cp : BoxingDrill.acquire(env, first, true)) {
                    var timings = PHASES.stream().map(p -> phase(cp, model, p)).toList();
                    System.out.println(Json.encode(Map.of("clock", env.clockId, "seat", first ? "a" : "b",
                            "k", cp.k, "rtt", rtt, "landmark", cp.landmark, "ctrlWindows",
                            WINDOWS.stream().map(Window::label).toList(), "timings", timings)));
                }
            }
        }
    }
}
