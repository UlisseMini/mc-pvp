import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import lab.Json;

/** Offline screen of rephasing one out-of-range, cap-ready opening press. */
final class BoxingOpeningCadence {
    private static final int[] HOLDS = {4,8,12,16,20,24,32,40};
    private static final double END = 3100;
    private static final double LIMIT = 550;
    private static final double CLEAR = 3.2;
    private static final boolean TRACE = Boolean.getBoolean("lab.openingCadence.trace");

    private static final class EarlyPress extends DuelEnv.Player {
        final int index,hold;
        int offers;
        boolean reached;
        double trigger=-1, release=-1, range=-1;

        EarlyPress(int rtt,int index,int hold) {
            super("wtap",0,rtt,0);
            this.index=index;
            this.hold=hold;
        }

        @Override public boolean allowInjection(PeriodicComboClock.Observation o,String source,BooleanSupplier ready) {
            if (index==0 || release>=0 || reached) return true;
            if (trigger<0 && o.time()<=LIMIT && !Double.isFinite(o.lastOwnHurtTime()) &&
                    !Double.isFinite(o.lastTargetHurtTime())) {
                reached=ready.getAsBoolean();
                if (!reached && o.nearest()>CLEAR && ++offers==index) {
                    trigger=o.time();
                    range=o.nearest();
                }
            }
            return trigger<0 || o.time()>=trigger+hold;
        }

        @Override public boolean injected(PeriodicComboClock.Observation o,double completed) {
            if (trigger>=0 && release<0) release=completed;
            return super.injected(o,completed);
        }
    }

    private static int unanswered(List<DuelEnv.Hit> hits,boolean first,int start) {
        int n=0;
        double prior=-1;
        for (var hit:hits.subList(start,hits.size())) {
            if (hit.sideA()!=first) {
                if (n>0 && prior==hit.at()) n--;
                break;
            }
            if (hit.upgrade()) continue;
            if (n>0 && hit.at()-prior>1100) break;
            n++;
            prior=hit.at();
        }
        return n;
    }

    private static int quietTrade(List<DuelEnv.Hit> hits) {
        int first=-1;
        for (int i=0;i<hits.size();i++) {
            var hit=hits.get(i);
            if (hit.upgrade()) continue;
            if (first<0) first=i;
            else return hits.get(first).sideA()!=hit.sideA() && hit.at()-hits.get(first).at()<=275 ? i+1 : -1;
        }
        return -1;
    }

    private static void loadCompletionCosts(PeriodicComboClock.Timing timing, Path path) throws IOException {
        var recorded = List.of(new HashMap<Long,Long>(), new HashMap<Long,Long>());
        var fallback = new long[2];
        for (String line : Files.readAllLines(path)) {
            String[] fields = line.split(" ");
            int side = switch (fields[0]) {
                case "a" -> 0;
                case "b" -> 1;
                default -> throw new IllegalArgumentException("Invalid completion side: " + fields[0]);
            };
            long cost = Long.parseLong(fields[2]);
            if (cost < 0) throw new IllegalArgumentException("Negative completion cost");
            if (fields[1].equals("fallback")) fallback[side] = cost;
            else if (recorded.get(side).putIfAbsent(Long.parseLong(fields[1]), cost) != null)
                throw new IllegalArgumentException("Duplicate completion poll: " + line);
        }
        timing.injectionTiming = (fromA, pollNanos) -> {
            int side = fromA ? 0 : 1;
            return recorded.get(side).getOrDefault(pollNanos, fallback[side]);
        };
        timing.causalBackpressure = true;
    }

    private static Map<String,Object> run(DuelEnv env,boolean seat,int ownPhase,int otherPhase,int index,int hold) {
        var subject=new EarlyPress(env.rtt,index,hold);
        var game=env.new Game(seat,subject,Evolve.WT.create(env.otherRtt,0));
        if (ownPhase!=0) game.clock.shiftFutureClientOpportunities(seat,ownPhase);
        if (otherPhase!=0) game.clock.shiftFutureClientOpportunities(!seat,otherPhase);
        game.trace();
        var presses=List.of(new ArrayList<Long>(),new ArrayList<Long>());
        var contacts=new ArrayList<List<Object>>();
        game.clock.setClickAudit(e->{
            boolean own=e.fromA()==seat;
            if (e.action().equals("ADMIT")) {
                var list=presses.get(own?0:1);
                if (!list.isEmpty() && e.admittedNanos()-list.get(list.size()-1)<50_000_000L)
                    throw new IllegalStateException("The native cap changed");
                list.add(e.admittedNanos());
            }
            if (TRACE && e.time()<1300 && e.action().equals("CONSUME")) {
                var s=game.clock.state;
                var body=e.fromA()?s.a:s.b;
                var view=e.fromA()?s.ab:s.ba;
                double ray=PeriodicComboFightModel.ray(body,view);
                var packet=e.contact()?s.packets.get(s.packets.size()-1):null;
                if (packet!=null && packet.kind!=(e.fromA()?3:7)) throw new AssertionError("Expected attack packet");
                contacts.add(List.of(e.time(),own,e.eligible(),e.contact(),Double.isFinite(ray)?ray:-1,
                        packet==null?-1:packet.ready,e.admittedNanos()));
            }
        });
        game.clock.runUntil(Math.nextDown(END));
        var s=game.clock.state;
        int trade=quietTrade(game.hits.list);
        var m=new LinkedHashMap<String,Object>();
        m.put("score",seat?List.of(s.acceptedA,s.acceptedB):List.of(s.acceptedB,s.acceptedA));
        m.put("fresh",seat?List.of(s.hits,s.incomingHits):List.of(s.incomingHits,s.hits));
        m.put("presses",presses.stream().map(List::size).toList());
        m.put("startChain",unanswered(game.hits.list,seat,0));
        m.put("tradeChain",trade<0?0:unanswered(game.hits.list,seat,trade));
        m.put("resent",List.of(subject.trigger,subject.release,subject.range));
        m.put("first",game.hits.list.stream().filter(x->!x.upgrade()).limit(4).map(h->List.of(h.at(),h.sideA()==seat)).toList());
        if (TRACE) {
            m.put("contacts",contacts);
            m.put("clicks",presses);
            m.put("hits",game.hits.list.stream().map(h->List.of(h.at(),h.sideA()==seat,h.upgrade())).toList());
        }
        return m;
    }

    public static void main(String[] args) throws Exception {
        int rtt=Integer.parseInt(args[0]);
        var root=Path.of(args[1]);
        var env=BoxingEdgeHitSelect.environment(Path.of(args[2]),rtt,180_000,root.resolve("wire"),root.resolve("seeds"));
        BoxingMirrorForensics.loadPacketModel(env.initial.state,Path.of(args[3]),"raw-queue");
        String completionCosts=System.getProperty("lab.openingCadence.completionCosts");
        if (completionCosts!=null) loadCompletionCosts(env.initial.timing,Path.of(completionCosts));
        int chosenIndex=Integer.getInteger("lab.openingCadence.index",0);
        int chosenHold=Integer.getInteger("lab.openingCadence.hold",0);
        for (int i=4;i<args.length;i++) {
            var spec=args[i].split(",");
            boolean seat=spec[0].equals("a");
            int own=Integer.parseInt(spec[1]), other=Integer.parseInt(spec[2]);
            var wtap=run(env,seat,own,other,0,0);
            var arms=new LinkedHashMap<String,Map<String,Object>>();
            for (int index=1;index<=6;index++) for (int hold:HOLDS) {
                if (chosenIndex!=0 && (chosenIndex!=index || chosenHold!=hold)) continue;
                var r=run(env,seat,own,other,index,hold);
                arms.put(index+"/"+hold,r);
                if (((List<?>)r.get("resent")).get(0).equals(-1.0)) {
                    if (!r.equals(wtap)) throw new IllegalStateException("A hold without a trigger changed the trajectory");
                    break;
                }
            }
            System.out.println(Json.encode(Map.of("clock",env.clockId,"seat",spec[0],"phase",List.of(own,other),
                    "rtt",rtt,"wtap",wtap,"arms",arms)));
        }
    }
}
