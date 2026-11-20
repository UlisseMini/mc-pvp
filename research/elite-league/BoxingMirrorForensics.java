import java.nio.file.Path;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import lab.Json;

/** Compare the generated W-tap mirror's exact opening events with a native recording. */
final class BoxingMirrorForensics {
    private record Delay(int route,int id,PeriodicComboFightModel.TimingSemantic semantic,
                         double when,double receipt,double ready) {}

    record StageCost(double rawOffset,double queue) {}

    private record StageSample(double time, StageCost cost) {}
    private record StageRoute(int route, String family) {}

    private record StageCosts(Map<String,StageCost> typical, Map<StageRoute,List<StageSample>> timelines) {
        StageCost at(int route,String family,double time) {
            if (timelines.isEmpty()) return typical.get(family);
            var samples=timelines.get(new StageRoute(route,family));
            if (samples==null) return typical.get(family);
            int lo=0,hi=samples.size();
            while (lo<hi) {
                int mid=(lo+hi)/2;
                if (samples.get(mid).time()<=time) lo=mid+1;
                else hi=mid;
            }
            return lo==0?typical.get(family):samples.get(lo-1).cost();
        }
    }

    private record PredictiveTiming(PeriodicComboFightModel.PacketTiming base,StageCosts cost,
                                    boolean raw,boolean ready,double[] lastReceipt)
            implements PeriodicComboFightModel.PacketTiming {
        @Override public double arrival(int route,double time,double leg) {
            return base.arrival(route,time,leg);
        }
        @Override public PeriodicComboFightModel.PacketTimingPlan planForPacket(int route,double time,double leg,
                PeriodicComboFightModel.TimingSemantic semantic,int id) {
            var p=base.planForPacket(route,time,leg,semantic,id);
            StageCost estimate=cost.at(route,family(route,id),time);
            double adjustment=estimate!=null && raw?estimate.rawOffset():0;
            double receipt=Math.max(lastReceipt[route],Math.max(time,p.rawReceiptMs()+adjustment));
            lastReceipt[route]=receipt;
            double enqueue=estimate!=null && ready?estimate.queue():0;
            return new PeriodicComboFightModel.PacketTimingPlan(receipt,receipt+enqueue,null);
        }
        private static String family(int route,int id) {
            if (route<2) {
                if (id==2) return "attack";
                if (id>=3 && id<=6) return "movement";
            } else {
                if (id==0x12) return "velocity";
                if (id==0x1a) return "hurt";
                if (id>=0x14 && id<=0x18) return "position";
            }
            return "";
        }
        @Override public PredictiveTiming deepCopy() {
            return new PredictiveTiming(base.deepCopy(),cost,raw,ready,lastReceipt.clone());
        }
    }

    private static final class ClickCosts implements PeriodicComboClock.InjectionTiming {
        final long[] costs;
        final int[] calls;
        ClickCosts(long[] costs,int[] calls){this.costs=costs;this.calls=calls;}
        @Override public long completionDelayNanos(boolean fromA,long pollNanos) {
            int side=fromA?0:1;
            int index=calls[side];
            if (index<costs.length-1) calls[side]++;
            return costs[index];
        }
        @Override public ClickCosts deepCopy() { return new ClickCosts(costs,calls.clone()); }
    }

    static void loadPacketModel(PeriodicComboFightModel.State state,Path path,String mode) throws Exception {
        var timings=new HashMap<String,StageCost>();
        var timelines=new HashMap<StageRoute,List<StageSample>>();
        for (String line:Files.readAllLines(path)) {
            String[] f=line.split(" ");
            if (f[0].equals("sample")) {
                var route=new StageRoute(Integer.parseInt(f[1]),f[2]);
                var sample=new StageSample(Double.parseDouble(f[3]),new StageCost(Double.parseDouble(f[4]),Double.parseDouble(f[5])));
                timelines.computeIfAbsent(route,ignored->new ArrayList<>()).add(sample);
            } else if (!f[0].equals("click")) {
                timings.put(f[0],new StageCost(Double.parseDouble(f[1]),Double.parseDouble(f[2])));
            }
        }
        boolean raw=mode.contains("raw"),ready=mode.contains("queue");
        if (raw || ready) {
            double[] last={-1,-1,-1,-1};
            for (var packet:state.packets) {
                int route=PeriodicComboTransport.route(packet.kind);
                last[route]=Math.max(last[route],packet.due);
                state.queueReadyWatermarks[route]=Math.max(state.queueReadyWatermarks[route],packet.ready);
            }
            timelines.replaceAll((route,samples)->samples.stream().sorted(java.util.Comparator.comparingDouble(StageSample::time)).toList());
            var costs=new StageCosts(Map.copyOf(timings),Map.copyOf(timelines));
            state.packetTiming=new PredictiveTiming(state.packetTiming,costs,raw,ready,last);
        }
    }

    static void loadStageModel(DuelEnv env,Path path,String mode) throws Exception {
        loadPacketModel(env.initial.state,path,mode);
        if (mode.contains("cap")) {
            long[] clickCosts=Files.readAllLines(path).stream().filter(line->line.startsWith("click "))
                    .mapToLong(line->Long.parseLong(line.substring("click ".length()))).toArray();
            env.initial.timing.injectionTiming=new ClickCosts(clickCosts,new int[2]);
            env.initial.timing.causalBackpressure=true;
        }
    }

    private record Timing(PeriodicComboFightModel.PacketTiming base,List<Delay> edits)
            implements PeriodicComboFightModel.PacketTiming {
        static Timing diagnostics(PeriodicComboFightModel.PacketTiming base) {
            var edits=new ArrayList<Delay>();
            String input=System.getProperty("lab.mirror.deliveryEdits","");
            if (!input.isEmpty()) for (String description:input.split(";")) {
                String[] fields=description.split(",");
                edits.add(new Delay(Integer.parseInt(fields[0]),Integer.decode(fields[1]),
                        PeriodicComboFightModel.TimingSemantic.valueOf(fields[2]),Double.parseDouble(fields[3]),
                        Double.parseDouble(fields[4]),Double.parseDouble(fields[5])));
            }
            return new Timing(base,List.copyOf(edits));
        }
        @Override public double arrival(int route,double time,double leg) {
            return base.arrival(route,time,leg);
        }
        @Override public PeriodicComboFightModel.PacketTimingPlan planForPacket(int route,double time,double leg,
                PeriodicComboFightModel.TimingSemantic semantic,int packetId) {
            var p=base.planForPacket(route,time,leg,semantic,packetId);
            for (Delay d:edits) {
                if (d.route()==route && d.id()==packetId && d.semantic()==semantic && Math.abs(time-d.when())<.000001) {
                    if (d.receipt()<time) throw new IllegalArgumentException("Diagnostic receipt precedes send");
                    return new PeriodicComboFightModel.PacketTimingPlan(d.receipt(),d.ready(),p.cueDelayNanos(),p.service());
                }
            }
            return p;
        }
        @Override public Timing deepCopy() {
            return new Timing(base.deepCopy(),edits);
        }
    }

    private static PeriodicComboClock.InjectionTiming injectionDiagnostic() {
        String input=System.getProperty("lab.mirror.injectionEdit");
        if (input==null) return null;
        String[] fields=input.split(",");
        boolean side=fields[0].equals("a");
        long poll=Long.parseLong(fields[1]),cost=Long.parseLong(fields[2]);
        return (a,at)->a==side && at==poll?cost:0;
    }

    private static List<Object> body(PeriodicComboFightModel.Body b) {
        var q=b.q;
        return List.of(q.x(),q.y(),q.z(),q.vx(),q.vy(),q.vz(),q.onGround(),b.sprint);
    }

    private static List<Number> view(PeriodicComboFightModel.View v) {
        return List.of(v.x,v.y,v.z,v.gx,v.gy,v.gz,v.left);
    }

    private static Map<String,Object> trace(DuelEnv env,double duration) {
        var g=env.new Game(false,new BoxingLandingReentry.Scout(env.rtt),Evolve.WT.create(env.otherRtt,0));
        g.trace();
        if (Boolean.getBoolean("lab.mirror.scoreOnly")) {
            g.clock.runUntil(duration);
            return Map.of("clock",env.clockId,"rtt",env.rtt,
                    "hits",g.hits.list.stream().map(h->List.of(h.at(),h.sideA(),h.upgrade())).toList());
        }
        var states=new ArrayList<List<Object>>();
        var packets=new ArrayList<List<Object>>();
        var clicks=new ArrayList<List<Object>>();
        var moves=new ArrayList<List<Object>>();
        var seen=new HashSet<Long>();
        g.clock.setClickAudit(e->{
            var s=g.clock.state;
            var body=e.fromA()?s.a:s.b;
            var view=e.fromA()?s.ab:s.ba;
            double ray=PeriodicComboFightModel.ray(body,view);
            clicks.add(List.of(e.time(),e.fromA()?"a":"b",e.tick(),e.action(),e.admissionId(),e.admittedNanos(),
                    e.contact(),PeriodicComboFightModel.nearest(body.q,view),Double.isFinite(ray)?ray:-1));
        });
        g.clock.setAudit((at,event,s)->{
            g.hits.event(at,event,s);
            for (var p:s.packets) {
                if (seen.add(p.sequence))
                    packets.add(List.of(at,event,p.sequence,p.kind,p.packetId,p.due,p.ready,p.x,p.y,p.z,p.vx,p.vy,p.vz));
            }
            if (event.startsWith("MOVE")) {
                moves.add(List.of(at,event.equals("MOVEA")?"a":"b",body(event.equals("MOVEA")?s.a:s.b)));
            } else if (event.startsWith("CLIENT_DRAIN") || event.startsWith("INTERP") || event.startsWith("INPUT") ||
                    event.startsWith("POLICY") || event.equals("SERVER_DRAIN")) {
                states.add(List.of(at,event,body(s.a),body(s.b),view(s.ab),view(s.ba),
                        s.a.lastHurtTime,s.b.lastHurtTime,s.ab.lastHurtTime,s.ba.lastHurtTime,
                        g.clock.appliedControl(true)==null?-1:g.clock.appliedControl(true).key(),
                        g.clock.appliedControl(false)==null?-1:g.clock.appliedControl(false).key(),s.serverTicks));
            }
        });
        g.clock.runUntil(duration);
        return Map.of("clock",env.clockId,"rtt",env.rtt,"states",states,"moves",moves,"packets",packets,"clicks",clicks,
                "hits",g.hits.list.stream().map(h->List.of(h.at(),h.sideA(),h.upgrade())).toList());
    }

    public static void main(String[] args) throws Exception {
        int rtt=Integer.parseInt(args[0]);
        double duration=Double.parseDouble(args[1]);
        var env=BoxingEdgeHitSelect.environment(Path.of(args[4]),rtt,180_000,Path.of(args[2]),Path.of(args[3]));
        env.initial.state.packetTiming=Timing.diagnostics(env.initial.state.packetTiming);
        env.initial.timing.injectionTiming=injectionDiagnostic();
        if (args.length==7) loadStageModel(env,Path.of(args[5]),args[6]);
        System.out.println(Json.encode(trace(env,duration)));
    }
}
