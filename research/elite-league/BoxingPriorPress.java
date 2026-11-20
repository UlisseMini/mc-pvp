import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lab.DuelNetwork;
import lab.Json;

/** Retime the physical prior press under the same cap, without changing combat physics. */
final class BoxingPriorPress {
    private static final int[][] PHASES = {{0,0},{0,-5},{0,5},{5,0},{5,5}};
    private static final int[] DELAYS = {0, 15, 30, 45, 60, 90, 120};

    private record Cut(String id, String clock, boolean first, BoxingLaterTrades.Use source,
                       int[] prefixPhase, double release, double prior) {}

    private record Contact(double consumed, long admitted) {}

    private static List<Cut> read(Path path) throws Exception {
        var cuts = new ArrayList<Cut>();
        for (String line : Files.readAllLines(path)) {
            String[] t = line.split("\t");
            if (t.length != 8) throw new IllegalArgumentException("Invalid launch case " + line);
            cuts.add(new Cut(t[0], t[1], t[2].equals("a"), BoxingLaterTrades.Use.valueOf(t[3]),
                    new int[]{Integer.parseInt(t[4]),Integer.parseInt(t[5])}, Double.parseDouble(t[6]),Double.parseDouble(t[7])));
        }
        return cuts;
    }

    private static Contact sourcePress(DuelEnv env, Arena.Model model, Cut cut) {
        var game = BoxingLaterTrades.start(env,model,cut.first,cut.source,cut.prefixPhase[0],cut.prefixPhase[1]);
        var contacts = new LinkedHashMap<Long,Contact>();
        game.clock.setClickAudit(e -> {
            if (e.fromA() != cut.first || !e.action().equals("CONSUME") || !e.contact()) return;
            var s = game.clock.state;
            var p = s.packets.get(s.packets.size() - 1);
            if (p.sequence != s.seq - 1 || p.kind != (cut.first ? 3 : 7)) throw new AssertionError("Unknown own contact");
            contacts.put(p.sequence,new Contact(e.time(),e.admittedNanos()));
        });
        game.clock.runUntil(cut.prior);
        var s = game.clock.state;
        var velocity = cut.first ? s.lastFreshB : s.lastFreshA;
        long attack = cut.first ? s.lastFreshPacketA : s.lastFreshPacketB;
        if (velocity == null || velocity.time() != cut.prior || !contacts.containsKey(attack))
            throw new AssertionError("Missing original accepted click " + cut.id);
        return contacts.get(attack);
    }

    private static final class Press extends DuelEnv.Player {
        final BoxingAcquisitionControl.Acquisition original;
        final boolean stockBefore, sprintRelease;
        final double firstAllowed, releaseAfter, oldTargetCue;
        final int delay;
        double firstDeferred = Double.NaN, firstNewPress = Double.NaN, switched = Double.NaN;
        boolean usedSprint;

        Press(BoxingAcquisitionControl.Acquisition original, boolean stockBefore, boolean sprintRelease,
              double firstAllowed, double releaseAfter, double oldTargetCue, int delay) {
            super(original.style, original.mask, original.rtt, original.wake);
            this.original = original;
            this.stockBefore = stockBefore;
            this.sprintRelease = sprintRelease;
            this.firstAllowed = firstAllowed;
            this.releaseAfter = releaseAfter;
            this.oldTargetCue = oldTargetCue;
            this.delay = delay;
            this.firstClick = original.firstClick;
        }

        @Override public PeriodicComboClock.Control input(PeriodicComboClock.Observation own) {
            if (Double.isFinite(switched)) return original.input(own,true);
            if (own.time() < releaseAfter || own.lastTargetHurtTime() <= oldTargetCue)
                return original.input(own,stockBefore);
            switched = own.time();
            var stock = original.input(own,true);
            if (!sprintRelease || stock.key() != 0) return stock;
            usedSprint = true;
            original.learned.net.applied(DuelNetwork.pack(2,(int)Math.signum(stock.strafe()),stock.click(),stock.jump()));
            return new PeriodicComboClock.Control(2,stock.strafe(),stock.click(),stock.jump());
        }

        @Override public boolean allowInjection(PeriodicComboClock.Observation own,String source) {
            if (own.time() < firstAllowed || Double.isFinite(firstNewPress)) return true;
            if (!Double.isFinite(firstDeferred)) firstDeferred = own.time();
            return own.time() >= firstDeferred + delay;
        }

        @Override public boolean injected(PeriodicComboClock.Observation own,double at) {
            if (at >= firstAllowed && !Double.isFinite(firstNewPress)) firstNewPress = at;
            return super.injected(own,at);
        }
    }

    private static Map<String,Object> play(DuelEnv env, DuelEnv.Game prefix, Cut cut, Contact origin,
                                           int[] phase, int delay, boolean sprint) {
        double start = origin.admitted / 1e6;
        double end = cut.release + 2_200;
        var policy = new Press(((BoxingLaterTrades.Trial)prefix.our()).player.copy(),cut.source == BoxingLaterTrades.Use.WTAP,
                sprint,start,cut.release,prefix.clock.observation(cut.first).lastTargetHurtTime(),delay);
        var game = env.new Game(prefix,cut.first ? policy : prefix.a.copy(),cut.first ? prefix.b.copy() : policy);
        game.trace();
        BoxingAcquisitionControl.shift(game,phase[0],phase[1]);
        var press = List.of(new ArrayList<Long>(),new ArrayList<Long>());
        var attacks = new LinkedHashMap<Long,Map<String,Object>>();
        game.clock.setClickAudit(e -> {
            if (e.action().equals("ADMIT")) {
                var times = press.get(e.fromA() == cut.first ? 0 : 1);
                if (!times.isEmpty() && e.admittedNanos() - times.get(times.size() - 1) < 50_000_000L)
                    throw new AssertionError("CPS interval violated");
                times.add(e.admittedNanos());
            }
            if (!e.action().equals("CONSUME") || !e.contact()) return;
            var s = game.clock.state;
            var packet = s.packets.get(s.packets.size() - 1);
            if (packet.sequence != s.seq - 1 || packet.kind != (e.fromA() ? 3 : 7))
                throw new AssertionError("Contact missing its packet");
            var b = e.fromA() ? s.a : s.b;
            attacks.put(packet.sequence,Map.of("consumed",e.time(),"admitted",e.admittedNanos(),"ready",packet.ready,
                    "observedGround",b.q.onGround(),"observedY",b.q.y(),"ray",PeriodicComboFightModel.ray(b,e.fromA() ? s.ab : s.ba)));
        });
        var hitRows = new ArrayList<Object>();
        game.clock.setAudit((t,stage,s) -> {
            int seen = game.hits.list.size();
            game.hits.event(t,stage,s);
            for (; seen < game.hits.list.size(); seen++) {
                var hit = game.hits.list.get(seen);
                long id = hit.upgrade() ? (hit.sideA() ? s.lastAcceptedPacketA : s.lastAcceptedPacketB)
                        : (hit.sideA() ? s.lastFreshPacketA : s.lastFreshPacketB);
                var h = new LinkedHashMap<String,Object>(Map.of("at",hit.at(),"ours",hit.sideA() == cut.first,
                        "upgrade",hit.upgrade(),"packet",id));
                if (attacks.containsKey(id)) h.put("contact",attacks.get(id));
                if (!hit.upgrade()) {
                    var impulse = hit.sideA() ? s.lastFreshB : s.lastFreshA;
                    h.put("lift",impulse.y());
                    h.put("horizontal",Math.hypot(impulse.x(),impulse.z()));
                    h.put("victimGround",impulse.victimBefore().onGround());
                }
                hitRows.add(h);
            }
        });
        game.clock.runUntil(end);
        return Map.of("hits",hitRows,"presses",List.of(press.get(0).size(),press.get(1).size()),
                "first",Double.isFinite(policy.firstNewPress) ? policy.firstNewPress : -1,
                "switchAt",Double.isFinite(policy.switched) ? policy.switched : -1,"sprintRelease",policy.usedSprint);
    }

    private static void study(DuelEnv env,Arena.Model model,Cut cut) {
        Contact chosen = sourcePress(env,model,cut);
        var prefix = BoxingLaterTrades.start(env,model,cut.first,cut.source,cut.prefixPhase[0],cut.prefixPhase[1]);
        prefix.clock.runUntil(Math.nextDown(chosen.admitted / 1e6));
        for (int[] phase : PHASES) {
            var outcomes = new LinkedHashMap<String,Object>();
            for (int delay : DELAYS) for (boolean sprint : new boolean[]{false,true})
                outcomes.put(delay + (sprint ? "/SPRINT" : "/WTAP"),play(env,prefix,cut,chosen,phase,delay,sprint));
            System.out.println(Json.encode(Map.of("id",cut.id,"clock",cut.clock,"seat",cut.first ? "a" : "b",
                    "phase",cut.prefixPhase,"timing",phase,"source",cut.source.toString(),"originalAdmission",chosen.admitted / 1e6,
                    "originalConsume",chosen.consumed,"release",cut.release,"arms",outcomes)));
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
            for (Cut cut : cuts) if (cut.clock.equals(env.clockId)) study(env,model,cut);
        }
    }
}
