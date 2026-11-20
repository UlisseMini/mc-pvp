import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import lab.Json;

/** A single opening hold driven only by the client's current crosshair and cooldown. */
final class BoxingLocalOpening {
    private static final double OPENING_END = 950;
    private static final double END = 3100;
    private static final int HOLD_MS = 4;

    private static final class FirstPoll extends DuelEnv.Player {
        final int hold;
        double trigger = -1, press = -1, nearest = -1;
        String poll = "";

        FirstPoll(int rtt,int hold) {
            super("wtap",0,rtt,0);
            this.hold = hold;
        }

        @Override public boolean allowInjection(PeriodicComboClock.Observation o,String source,BooleanSupplier ready) {
            if (press >= 0) return true;
            if (trigger < 0 && o.time() <= OPENING_END && !Double.isFinite(o.lastOwnHurtTime()) &&
                    !Double.isFinite(o.lastTargetHurtTime()) && ready.getAsBoolean()) {
                trigger = o.time();
                nearest = o.nearest();
                poll = source;
            }
            return trigger < 0 || o.time() >= trigger + hold;
        }

        @Override public boolean injected(PeriodicComboClock.Observation o,double completed) {
            if (trigger >= 0 && press < 0) press = completed;
            return super.injected(o,completed);
        }
    }

    private static List<Double> tradeThenChain(List<DuelEnv.Hit> hits,boolean first) {
        int prior = -1;
        double quietStart = Double.NEGATIVE_INFINITY;
        for (int i=0; i<hits.size(); i++) {
            var h = hits.get(i);
            if (h.upgrade()) continue;
            if (h.at() > OPENING_END) return List.of();
            if (prior >= 0 && hits.get(prior).sideA() != h.sideA() && h.at() - quietStart <= 275) {
                var chain = new ArrayList<Double>();
                double last = -1;
                for (var next:hits.subList(i+1,hits.size())) {
                    if (next.sideA() != first) {
                        if (!chain.isEmpty() && next.at() == last) chain.remove(chain.size()-1);
                        break;
                    }
                    if (next.upgrade()) continue;
                    if (last >= 0 && next.at()-last > 1100) break;
                    chain.add(next.at());
                    last = next.at();
                }
                return chain;
            }
            if (prior < 0 || h.at() - hits.get(prior).at() >= 1100) quietStart = h.at();
            else quietStart = Double.NaN;
            prior = i;
        }
        return List.of();
    }

    private static Map<String,Object> play(DuelEnv env,boolean first,int phase,int hold) {
        var player = new FirstPoll(env.rtt,hold);
        var g = env.new Game(first,player,Evolve.WT.create(env.otherRtt,0));
        if (phase != 0) g.clock.shiftFutureClientOpportunities(!first,phase);
        var adm = List.of(new ArrayList<Long>(),new ArrayList<Long>());
        g.trace();
        g.clock.setClickAudit(e->{
            if (!e.action().equals("ADMIT")) return;
            var list=adm.get(e.fromA()==first?0:1);
            if (!list.isEmpty() && e.admittedNanos()-list.get(list.size()-1)<50_000_000)
                throw new IllegalStateException("Exceeded the physical 20 CPS cap");
            list.add(e.admittedNanos());
        });
        g.clock.runUntil(Math.nextDown(END));
        var s = g.clock.state;
        return Map.of("score",first?List.of(s.acceptedA,s.acceptedB):List.of(s.acceptedB,s.acceptedA),
                "fresh",first?List.of(s.hits,s.incomingHits):List.of(s.incomingHits,s.hits),
                "trigger",player.trigger,"poll",player.poll,"range",player.nearest,"press",player.press,
                "clicks",adm,"chain",tradeThenChain(g.hits.list,first),
                "hits",g.hits.list.stream().map(h->List.of(h.at(),h.sideA()==first,h.upgrade())).toList());
    }

    public static void main(String[] args) throws Exception {
        int rtt = Integer.parseInt(args[0]);
        var root = Path.of(args[1]);
        var env = BoxingEdgeHitSelect.environment(Path.of(args[2]),rtt,180_000,root.resolve("wire"),root.resolve("seeds"));
        BoxingMirrorForensics.loadPacketModel(env.initial.state,Path.of(args[3]),"raw-queue");
        for (int i=4; i<args.length; i++) for (String side:List.of("a","b")) {
            int phase = Integer.parseInt(args[i]);
            var arms=new LinkedHashMap<String,Object>();
            arms.put("wtap",play(env,side.equals("a"),phase,0));
            arms.put("hold",play(env,side.equals("a"),phase,HOLD_MS));
            System.out.println(Json.encode(Map.of("clock",env.clockId,"seat",side,"rtt",rtt,"phase",phase,"arms",arms)));
        }
    }
}
