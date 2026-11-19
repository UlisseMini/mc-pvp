import java.io.*;
import java.util.*;
import lab.Json;

/** Predict the native fight from physical inputs and observed host-stage times.
 * No contact, health, velocity or subsequent position is supplied. */
public final class InputReplay {
    static final class Keyboard implements PeriodicComboClock.Policy {
        final List<PeriodicComboClock.Control> words=new ArrayList<>();
        final ArrayDeque<lab.MouseLook> counts=new ArrayDeque<>();
        boolean recordedMouse;
        final List<Object> velocities=new ArrayList<>();
        boolean diagnose;
        int index;
        public void received(PeriodicComboClock.PacketObservation p){
            if(diagnose && p.kind()==PeriodicComboClock.ReceivedKind.OWN_VELOCITY)
                velocities.add(List.of(Math.round(p.handledAt()*1e6),p.vx(),p.vy(),p.vz()));
        }
        public PeriodicComboClock.Control input(PeriodicComboClock.Observation unused){return words.get(index++);}
        public lab.MouseLook mouseLook(float y,float p,float targetY,float targetP,double elapsed){
            return recordedMouse?counts.removeFirst():PeriodicComboClock.Policy.super.mouseLook(y,p,targetY,targetP,elapsed);
        }
    }
    /** Training-derived stage-to-wire overhead and queue readiness. Route FIFO order is preserved. */
    static final class TransportShape implements PeriodicComboFightModel.PacketTiming {
        final double[] values,last=new double[4];
        TransportShape(double[] v){if(v.length!=9)throw new IllegalArgumentException("Expected five transit and four enqueue estimates");
            for(double x:v)if(x<0||!Double.isFinite(x))throw new IllegalArgumentException("Invalid overhead");values=v;}
        int cell(int route,PeriodicComboFightModel.TimingSemantic s){
            if(route<2)return 0;
            return switch(s){case OWN_HURT->2;case TARGET_HURT->3;case OWN_VELOCITY->4;default->1;};
        }
        public double arrival(int route,double sent,double half){return arrival(route,sent,half,PeriodicComboFightModel.TimingSemantic.OTHER);}
        public double arrival(int route,double sent,double half,PeriodicComboFightModel.TimingSemantic s){
            return last[route]=Math.max(last[route],sent+half+values[cell(route,s)]);
        }
        public PeriodicComboFightModel.PacketTimingPlan plan(int route,double sent,double half,PeriodicComboFightModel.TimingSemantic s){
            double raw=arrival(route,sent,half,s);Double ready=route<2?null:Double.valueOf(raw+values[cell(route,s)+4]);
            return new PeriodicComboFightModel.PacketTimingPlan(raw,ready,null);
        }
        public TransportShape deepCopy(){var t=new TransportShape(values);System.arraycopy(last,0,t.last,0,4);return t;}
    }
    /** Forecast two packet-family overheads using only generated packets. Preserve wire FIFO. */
    static final class FamilyTiming implements PeriodicComboFightModel.PacketTiming {
        final double velocity,flags;
        final double[] last=new double[4];
        FamilyTiming(double velocity,double flags){
            if(!Double.isFinite(velocity)||!Double.isFinite(flags)||velocity<0||flags<0)
                throw new IllegalArgumentException("Invalid packet overhead");
            this.velocity=velocity;this.flags=flags;
        }
        public double arrival(int route,double sent,double half){return sent+half+.35;}
        public PeriodicComboFightModel.PacketTimingPlan planForPacket(int route,double sent,double half,
                PeriodicComboFightModel.TimingSemantic kind,int packetId){
            double extra=.35;
            if(route>=2 && packetId==0x1c)extra=flags;
            if(route>=2 && kind==PeriodicComboFightModel.TimingSemantic.OWN_VELOCITY)extra=velocity;
            last[route]=Math.max(last[route],sent+half+extra);
            return new PeriodicComboFightModel.PacketTimingPlan(last[route],null,null);
        }
        public FamilyTiming deepCopy(){
            var copy=new FamilyTiming(velocity,flags);
            System.arraycopy(last,0,copy.last,0,last.length);
            return copy;
        }
    }
    /** FIFO times for a single generated packet family, independently on each client. */
    static final class DrainTimes {
        final List<List<Long>> drains;
        final int[] next=new int[2],assigned=new int[2],impossible=new int[2],absent=new int[2];
        DrainTimes(List<String> lines,String family){
            drains=List.of(new ArrayList<>(),new ArrayList<>());
            for(String line:lines)if(line.startsWith(family+" ")){
                String[] v=line.split(" ");
                drains.get(v[1].equals("a")?0:1).add(Long.parseLong(v[2]));
            }
        }
        private DrainTimes(DrainTimes other){
            drains=other.drains;
            for(int i=0;i<2;i++){
                next[i]=other.next[i];assigned[i]=other.assigned[i];
                impossible[i]=other.impossible[i];absent[i]=other.absent[i];
            }
        }
        PeriodicComboFightModel.PacketTimingPlan apply(int side,double sent,double raw){
            if(next[side]>=drains.get(side).size())absent[side]++;
            else{
                double drain=drains.get(side).get(next[side]++)/1e6;
                if(drain>=sent){
                    assigned[side]++;
                    return new PeriodicComboFightModel.PacketTimingPlan(Math.min(raw,drain),drain,null);
                }
                impossible[side]++;
            }
            return new PeriodicComboFightModel.PacketTimingPlan(raw,null,null);
        }
        Map<String,Object> coverage(){
            return Map.of("assigned",assigned,"native_drain_before_generated_send",impossible,"no_native_drain",absent,
                          "unused_native_drains",new int[]{drains.get(0).size()-next[0],drains.get(1).size()-next[1]});
        }
    }
    /** Only packet-family ordinal and native drain times cross this offline diagnostic boundary. */
    static final class ObservedDrains implements PeriodicComboFightModel.PacketTiming {
        final DrainTimes velocity,flags;
        ObservedDrains(DrainTimes velocity,DrainTimes flags){this.velocity=velocity;this.flags=flags;}
        public double arrival(int route,double sent,double half){return sent+half+.35;}
        public PeriodicComboFightModel.PacketTimingPlan planForPacket(int route,double sent,double half,
                PeriodicComboFightModel.TimingSemantic kind,int packetId){
            double raw=arrival(route,sent,half);
            if(route>=2 && flags!=null && packetId==0x1c)return flags.apply(route-2,sent,raw);
            if(route>=2 && velocity!=null && kind==PeriodicComboFightModel.TimingSemantic.OWN_VELOCITY)
                return velocity.apply(route-2,sent,raw);
            return new PeriodicComboFightModel.PacketTimingPlan(raw,null,null);
        }
        public ObservedDrains deepCopy(){
            return new ObservedDrains(velocity==null?null:new DrainTimes(velocity), flags==null?null:new DrainTimes(flags));
        }
    }
    public static void main(String[] args)throws Exception{
        var lines=new BufferedReader(new InputStreamReader(System.in)).lines().toList();
        if(args.length==0){run(lines,.35,.35,null);return;}
        for(String pair:args){
            if(Set.of("observed-velocity-drains","observed-sprint-drains","observed-velocity-and-sprint-drains").contains(pair)){
                var velocity=pair.contains("velocity")?new DrainTimes(lines,"@velocity"):null;
                var flags=pair.contains("sprint")?new DrainTimes(lines,"@flags"):null;
                run(lines,0,0,new ObservedDrains(velocity,flags));continue;
            }
            if(pair.startsWith("shape=")){
                double[] v=Arrays.stream(pair.substring(6).split(",")).mapToDouble(Double::parseDouble).toArray();
                run(lines,0,0,new TransportShape(v));continue;
            }
            if(pair.startsWith("family=")){
                String[] times=pair.substring(7).split(",",-1);
                if(times.length!=2)throw new IllegalArgumentException("Expected velocity and metadata overheads");
                run(lines,0,0,new FamilyTiming(Double.parseDouble(times[0]),Double.parseDouble(times[1])));continue;
            }
            String[] legs=pair.split(",",-1);
            if(legs.length!=2)throw new IllegalArgumentException("Expected uplink,downlink milliseconds");
            run(lines,Double.parseDouble(legs[0]),Double.parseDouble(legs[1]),null);
        }
    }
    static void run(List<String> lines,double up,double down,PeriodicComboFightModel.PacketTiming shape)throws Exception{
        if(!Double.isFinite(up)||!Double.isFinite(down)||up<0||down<0)throw new IllegalArgumentException("Invalid overhead");
        var input=lines.iterator();
        var a=new Keyboard();var b=new Keyboard();
        String[] header=input.next().split(" ");
        double center=Double.parseDouble(header[0]);int rtt=Integer.parseInt(header[1]);
        var cfg=BoxingModel.config(header[2],rtt);cfg.sumo=true;
        cfg.otherRtt=header.length>11?Integer.parseInt(header[11]):rtt;
        cfg.sumoDiameter=header.length>12?Integer.parseInt(header[12]):10;
        double gap=header.length>13?Double.parseDouble(header[13]):6;
        cfg.sumoArena(center,12);cfg.solidEdge();
        cfg.trackerAFirst=header.length<=10 || header[10].equals("1");
        var timing=new PeriodicComboClock.Timing();timing.inputMode=PeriodicComboClock.InputMode.NATIVE_QUEUED;
        String aim=header.length>14?header[14]:"ideal";
        if(!Set.of("ideal","recorded","generated").contains(aim))throw new IllegalArgumentException("Unknown aim input");
        timing.mouseInput=!aim.equals("ideal");a.recordedMouse=b.recordedMouse=aim.equals("recorded");
        a.diagnose=b.diagnose=header.length>15 && header[15].equals("ledger");
        timing.explicitPolls=true;timing.uplinkA=timing.uplinkB=up;timing.downlinkA=timing.downlinkB=down;
        var tape=new ArrayList<PeriodicComboClock.Scheduled>();
        String line;long end=0;
        while(input.hasNext()){
            line=input.next();if(line.startsWith("@velocity ")||line.startsWith("@flags "))continue;
            var v=line.split(" ");boolean first=v[1].equals("a");long ns=Long.parseLong(v[0]);
            var kind=PeriodicComboClock.Kind.valueOf(v[2]);
            if(a.recordedMouse && kind==PeriodicComboClock.Kind.FRAME_ENTRY)
                (first?a:b).counts.add(lab.MouseLook.fromCounts(Integer.parseInt(v[3]),Integer.parseInt(v[4]),
                        Float.parseFloat(v[5]),Boolean.parseBoolean(v[6])));
            if(kind==PeriodicComboClock.Kind.TICK_HEAD)(first?a:b).words.add(
                new PeriodicComboClock.Control(Integer.parseInt(v[3]),Float.parseFloat(v[5]),true,Boolean.parseBoolean(v[4])));
            tape.add(new PeriodicComboClock.Scheduled(ns,tape.size(),kind,first||v[1].equals("s")));end=Math.max(end,ns);
        }
        double offset=lab.SumoFootprint.centerOffset(cfg.sumoDiameter);
        var st=new PeriodicComboFightModel.State(center+offset-gap/2,offset,center+offset+gap/2,offset,12);
        st.serverTicks=Integer.parseInt(header[3]);st.teleportAgeA=Integer.parseInt(header[4]);st.teleportAgeB=Integer.parseInt(header[5]);
        st.trackedYawA=Integer.parseInt(header[6]);st.trackedYawB=Integer.parseInt(header[7]);
        st.trackedPitchA=Integer.parseInt(header[8]);st.trackedPitchB=Integer.parseInt(header[9]);
        if(shape!=null)st.packetTiming=shape;
        var clock=new PeriodicComboClock(st,cfg,timing,a,b,tape);
        var moves=new ArrayList<Object>();var hits=new DuelEnv.Hits();
        var sprintHistory=new ArrayList<Object>();boolean[] sprint={st.a.sprint,st.b.sprint};
        clock.setAudit((t,event,state)->{
            if(a.diagnose){
                for(int side=0;side<2;side++){
                    var body=side==0?state.a:state.b;
                    if(sprint[side]!=body.sprint){
                        sprint[side]=body.sprint;
                        sprintHistory.add(List.of(Math.round(t*1e6),side==0?"a":"b",event,body.sprint));
                    }
                }
            }
            if(event.equals("MOVEA")||event.equals("MOVEB")){
                var q=event.equals("MOVEA")?state.a.q:state.b.q;
                moves.add(List.of(Math.round(t*1e6),event.equals("MOVEA")?"a":"b",q.x(),q.y(),q.z(),q.vx(),q.vy(),q.vz()));
            }
            hits.event(t,event,state);
        });
        int[] admits=new int[2];var contacts=new ArrayList<Object>();
        clock.setClickAudit(e->{
            if(e.action().equals("ADMIT"))admits[e.fromA()?0:1]++;
            if(a.diagnose && e.action().equals("CONSUME") && e.contact())contacts.add(List.of(Math.round(e.time()*1e6),e.fromA()?"a":"b"));
        });
        clock.runUntil(end/1e6);
        if(!a.counts.isEmpty()||!b.counts.isEmpty())throw new IllegalStateException("Unconsumed raw mouse controls");
        var result=new LinkedHashMap<String,Object>();result.put("moves",moves);result.put("admissions",List.of(admits[0],admits[1]));
        result.put("hits",hits.list.stream().map(h->List.of(h.at(),h.sideA(),h.upgrade())).toList());
        if(a.diagnose){result.put("velocity",Map.of("a",a.velocities,"b",b.velocities));result.put("contacts",contacts);}
        if(a.diagnose)result.put("sprint_history",sprintHistory);
        if(clock.state.packetTiming instanceof ObservedDrains observed){
            if(observed.velocity!=null)result.put("observed_drain_coverage",observed.velocity.coverage());
            if(observed.flags!=null)result.put("observed_flag_coverage",observed.flags.coverage());
        }
        System.out.println(Json.encode(result));
    }
}
