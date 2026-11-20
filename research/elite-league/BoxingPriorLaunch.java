import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lab.CombatProfile;
import lab.Json;

/** Isolate the preceding launch from later input choices and packet timing. */
final class BoxingPriorLaunch {
    private static final int[][] PHASES = {{0,0},{0,-5},{0,5},{5,0},{5,5}};
    private static final boolean TEST_LIFT = Boolean.getBoolean("lab.boxing.priorLaunchLift");

    private enum Launch {
        UNCHANGED(-1,-1), NORMAL_H(0,-1), SPRINT_H(1,-1), NORMAL_Y(-1,0), SPRINT_Y(-1,1), NORMAL(0,0), SPRINT(1,1),
        ZERO_Y(-1,2), EXTRA_Y(-1,3);

        final int horizontal, vertical;
        Launch(int horizontal, int vertical) { this.horizontal = horizontal; this.vertical = vertical; }
    }

    private record Cut(String id, String clock, boolean first, BoxingLaterTrades.Use source,
                       int[] prefixPhase, double release, double prior) {}

    private record Prepared(DuelEnv.Game prefix, Map<String,Object> launch, List<Object> preReleaseHits) {}

    private static List<Cut> read(Path path) throws Exception {
        var cuts = new ArrayList<Cut>();
        for (String line : Files.readAllLines(path)) {
            String[] t = line.split("\t");
            if (t.length != 8) throw new IllegalArgumentException("Invalid prior launch case " + line);
            cuts.add(new Cut(t[0], t[1], t[2].equals("a"), BoxingLaterTrades.Use.valueOf(t[3]),
                    new int[]{Integer.parseInt(t[4]), Integer.parseInt(t[5])}, Double.parseDouble(t[6]), Double.parseDouble(t[7])));
        }
        return cuts;
    }

    private static Prepared prepare(DuelEnv env, Arena.Model model, Cut cut, Launch override) {
        var prefix = BoxingLaterTrades.start(env, model, cut.first, cut.source, cut.prefixPhase[0], cut.prefixPhase[1]);
        var launch = new LinkedHashMap<String,Object>();
        prefix.clock.runUntil(Math.nextDown(cut.prior));
        long previous = prefix.clock.state.seq;
        prefix.clock.setAudit((time, stage, state) -> {
            int seen = prefix.hits.list.size();
            prefix.hits.event(time, stage, state);
            if (time != cut.prior || !stage.equals("SERVER_DRAIN")) return;
            var hits = prefix.hits.list.subList(seen, prefix.hits.list.size());
            if (hits.stream().noneMatch(h -> h.sideA() == cut.first && !h.upgrade())) return;
            int kind = cut.first ? 4 : 8;
            var candidates = state.packets.stream().filter(p -> p.sequence >= previous && p.kind == kind).toList();
            if (candidates.size() != 1 || !launch.isEmpty()) throw new AssertionError("Ambiguous prior impulse " + cut.id);
            var p = candidates.get(0);
            double magnitude = Math.hypot(p.vx, p.vz);
            double separation = Math.hypot(state.serverAx - state.serverBx, state.serverAz - state.serverBz);
            var original = List.of(p.vx, p.vy, p.vz);
            if (override.horizontal >= 0 && magnitude > 0) {
                double desired = CombatProfile.rangeHorizontal(separation, override.horizontal);
                // Preserve the emitted direction. Quantization reflects a normal S12.
                if (Math.abs(desired - magnitude) > .00025) {
                    p.vx = PeriodicComboFightModel.vel(p.vx * desired / magnitude);
                    p.vz = PeriodicComboFightModel.vel(p.vz * desired / magnitude);
                }
            }
            if (override.vertical == 2) p.vy = 0;
            else if (override.vertical == 3) p.vy = PeriodicComboFightModel.vel(CombatProfile.RECORDED_Y + .1);
            else if (override.vertical >= 0 && p.vy > 0) {
                p.vy = PeriodicComboFightModel.vel(CombatProfile.RECORDED_Y + override.vertical * prefix.clock.config.sprintVertical);
            }
            launch.putAll(Map.of("at",time,"attack",cut.first ? state.lastFreshPacketA : state.lastFreshPacketB,
                    "velocity",p.sequence,"ready",p.ready,"separation",separation,"before",original,
                    "after",List.of(p.vx,p.vy,p.vz)));
            if (TEST_LIFT) {
                var movement = cut.first ? state.mmcB : state.mmcA;
                var impulse = cut.first ? state.lastFreshB : state.lastFreshA;
                double height = (cut.first ? state.serverBy : state.serverAy) - movement.floor();
                launch.put("target", Map.of("height",height,"capped",movement.capped(),
                        "grounded",impulse.victimBefore().onGround(),"landing",movement.landing(height,
                                cut.first ? prefix.clock.config.otherRtt : prefix.clock.config.ownRtt)));
            }
        });
        prefix.clock.runUntil(cut.prior);
        if (launch.isEmpty()) throw new AssertionError("Missing registered preceding fresh hit " + cut.id);
        prefix.clock.runUntil(Math.nextDown(cut.release));
        if (!prefix.clock.advanceToBeforeNextHead(cut.first,cut.release) || prefix.clock.time() != cut.release)
            throw new AssertionError("Unable to reproduce release head " + cut.id);
        List<Object> leading = prefix.hits.list.stream().filter(h -> h.at() > cut.prior)
                .map(h -> (Object) List.of(h.at(),h.sideA() == cut.first,h.upgrade())).toList();
        return new Prepared(prefix,launch,leading);
    }

    private static Map<String,Object> describe(BoxingReleaseDecision.Evidence evidence, Prepared state) {
        var record = new LinkedHashMap<String,Object>(Map.of("hits",evidence.result().get("hits"),"contacts",evidence.contacts(),
                "decision",evidence.decision(),"preReleaseHits",state.preReleaseHits,"launch",state.launch));
        if (evidence.result().containsKey("attempts")) record.put("attempts",evidence.result().get("attempts"));
        return record;
    }

    private static void evaluate(DuelEnv env, Arena.Model model, Cut cut) {
        var output = new ArrayList<Map<String,Object>>();
        for (int[] phase : PHASES) output.add(new LinkedHashMap<>());
        List<List<List<Long>>> presses = new ArrayList<>();
        for (Launch launch : Launch.values()) {
            if (!TEST_LIFT && launch.vertical >= 2) continue;
            var prepared = prepare(env,model,cut,launch);
            var prefix = prepared.prefix;
            var tap = ((BoxingLaterTrades.Trial) prefix.our()).player.tap;
            for (int i = 0; i < PHASES.length; i++) {
                var wtap = BoxingReleaseDecision.run(env,prefix,tap,BoxingReleaseDecision.Choice.WTAP,PHASES[i]);
                var sprint = BoxingReleaseDecision.run(env,prefix,tap,BoxingReleaseDecision.Choice.SPRINT,PHASES[i]);
                if (launch == Launch.UNCHANGED) presses.add(wtap.presses());
                if (!wtap.presses().equals(sprint.presses()) || !presses.get(i).equals(wtap.presses()))
                    throw new AssertionError("Mismatched physical click times");
                output.get(i).put(launch.toString(),Map.of("WTAP",describe(wtap,prepared),"SPRINT",describe(sprint,prepared)));
            }
        }
        for (int i = 0; i < PHASES.length; i++) {
            System.out.println(Json.encode(Map.of("id",cut.id,"clock",cut.clock,"seat",cut.first ? "a" : "b","source",cut.source.toString(),
                    "phase",cut.prefixPhase,"at",cut.release,"prior",cut.prior,"timing",PHASES[i],"arms",output.get(i))));
        }
    }

    public static void main(String[] args) throws Exception {
        var model = Arena.Model.decode(Files.readString(Path.of(args[0])).strip());
        int rtt = Integer.parseInt(args[1]);
        var cuts = read(Path.of(args[2]));
        var wire = Path.of(args[3]);
        var seeds = Path.of(args[4]);
        for (int i = 5; i < args.length; i++) {
            var env = BoxingEdgeHitSelect.environment(Path.of(args[i]),rtt,180_000,wire,seeds);
            for (Cut cut : cuts) if (cut.clock.equals(env.clockId)) evaluate(env,model,cut);
        }
    }
}
