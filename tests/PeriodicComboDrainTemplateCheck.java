import java.util.*;

/** Opt-in conditional drain semantics; no combat/controller performance claims. */
public final class PeriodicComboDrainTemplateCheck {
    static int checks;
    static void check(boolean value,String why){checks++;if(!value)throw new AssertionError(why);}
    static void reject(Runnable f,String message){checks++;try{f.run();throw new AssertionError("Expected rejection: "+message);}catch(IllegalStateException|IllegalArgumentException e){if(!e.toString().contains(message))throw new AssertionError(e);}}
    static PeriodicComboFightModel.Packet packet(int kind,double ready,long sequence){var p=new PeriodicComboFightModel.Packet(kind,ready,8,4,.55,.2,false,sequence);p.ready=ready;p.vz=-.1;return p;}
    static PeriodicComboClock.Scheduled e(double t,long id,PeriodicComboClock.Kind kind){return new PeriodicComboClock.Scheduled(Math.round(t*1e6),id,kind,true);}
    static class Reader implements PeriodicComboClock.Policy {
        final List<PeriodicComboClock.Observation> inputs=new ArrayList<>();final List<PeriodicComboClock.PacketObservation> received=new ArrayList<>();
        public PeriodicComboClock.Control input(PeriodicComboClock.Observation o){inputs.add(o);return new PeriodicComboClock.Control(0,0,false);}
        public void received(PeriodicComboClock.PacketObservation o){received.add(o);}
    }
    static PeriodicComboClock clock(PeriodicComboFightModel.State s,Reader r,PeriodicComboClock.DrainServiceTiming plans){
        var t=new PeriodicComboClock.Timing();t.inputMode=PeriodicComboClock.InputMode.NATIVE_QUEUED;t.explicitPolls=true;t.causalBackpressure=true;t.packetCueDelays=true;t.serviceMode=PeriodicComboClock.ServiceMode.MIDPOINT;t.drainServiceTiming=plans;
        var schedule=List.of(e(0,0,PeriodicComboClock.Kind.TICK_HEAD),e(3,1,PeriodicComboClock.Kind.CLIENT_DRAIN),e(4,2,PeriodicComboClock.Kind.TICK_HEAD),e(4.2,3,PeriodicComboClock.Kind.CONSUME),e(4.3,4,PeriodicComboClock.Kind.MOVE),e(8,5,PeriodicComboClock.Kind.CLIENT_DRAIN),e(12,6,PeriodicComboClock.Kind.TICK_HEAD),e(20,7,PeriodicComboClock.Kind.SERVER_END));
        return new PeriodicComboClock(s,new PeriodicComboFightModel.Config(),t,r,new Reader(),schedule);
    }
    static void composition(){
        var state=new PeriodicComboFightModel.State(6);state.ab.lastHurtTime=-1;
        var h=packet(12,1,0);h.service=new PeriodicComboFightModel.ServiceEnvelope("old-drain-A",1,2,3);
        var v=packet(8,2,1);v.service=new PeriodicComboFightModel.ServiceEnvelope("old-drain-B",1,2,3);state.packets.addAll(List.of(h,v));
        var seen=new ArrayList<List<PeriodicComboClock.DrainPacket>>();
        PeriodicComboClock.DrainServiceTiming provider=(route,start,word)->{seen.add(word);check(route==2&&start==3_000_000,"Actual drain boundary only");return new PeriodicComboClock.DrainServicePlan("new-native-co-drain",List.of(new PeriodicComboClock.PacketService(1_000_000,1_500_000),new PeriodicComboClock.PacketService(2_000_000,2_500_000)),4_000_000);};
        var r=new Reader();var c=clock(state,r,provider);c.runUntil(4);
        check(state.ab.lastHurtTime==4&&state.a.q.vx()==0,"S19 cue applies before later S12");
        check(r.received.isEmpty()&&r.inputs.size()==1,"Callback and ordinary input wait separately");
        var forkReader=new Reader();var fork=c.fork(forkReader,new Reader());
        c.runUntil(4.5);check(r.received.size()==1&&r.received.get(0).handledAt()==4.5,"S19 callback has own offset");
        c.runUntil(5);check(state.a.q.vx()==.55&&r.received.size()==1,"S12 effect has its own offset");
        c.runUntil(5.5);check(r.received.size()==2&&r.received.get(1).vx()==.55,"S12 callback preserves payload");
        c.runUntil(7);check(r.inputs.size()==2&&r.inputs.get(1).time()==7,"Whole drain locks HEAD to completion");
        check(seen.get(0).stream().map(PeriodicComboClock.DrainPacket::kind).toList().equals(List.of(PeriodicComboClock.ReceivedKind.TARGET_HURT,PeriodicComboClock.ReceivedKind.OWN_VELOCITY)),"Complete generated FIFO supplied once");
        check(fork.state.a.q.vx()==0&&forkReader.received.isEmpty(),"Busy fork state isolated");fork.runUntil(7);
        check(forkReader.received.equals(r.received)&&fork.state.a.q.equals(state.a.q),"In-flight effects/callbacks copied exactly");
    }
    static void lockedArrivals(){
        var s=new PeriodicComboFightModel.State(6);s.packets.add(packet(12,1,0));var late=packet(8,5,1);s.packets.add(late);
        var sizes=new ArrayList<Integer>();var starts=new ArrayList<Long>();
        var r=new Reader();var c=clock(s,r,(route,start,word)->{sizes.add(word.size());starts.add(start);return new PeriodicComboClock.DrainServicePlan("one",List.of(new PeriodicComboClock.PacketService(0,0)),start==3_000_000?4_000_000:0);});
        c.runUntil(7);check(sizes.equals(List.of(1))&&s.packets.contains(late)&&s.a.q.vx()==0,"Arrival during monitor lock cannot expand selected word or disappear");
        c.runUntil(8);check(sizes.equals(List.of(1,1))&&starts.equals(List.of(3_000_000L,8_000_000L)),"Blocked arrival joins later actual drain");
        check(late.ready==7&&s.a.q.vx()==.55&&c.serviceStats(true).enqueueDeferrals()==1,"Ready delayed to unlock; velocity retained");
    }
    static void shapeAndUnknown(){
        reject(()->new PeriodicComboClock.DrainServicePlan("bad",List.of(new PeriodicComboClock.PacketService(2,3),new PeriodicComboClock.PacketService(1,4)),5),"FIFO");
        var s=new PeriodicComboFightModel.State(6);s.packets.add(packet(12,1,0));
        reject(()->clock(s.deepCopy(),new Reader(),(r,t,w)->{throw new IllegalStateException("UNKNOWN_DRAIN_SERVICE_WORD");}).runUntil(3),"UNKNOWN_DRAIN_SERVICE_WORD");
        reject(()->clock(s.deepCopy(),new Reader(),(r,t,w)->new PeriodicComboClock.DrainServicePlan("wrong",List.of(),0)).runUntil(3),"count");
        var duplicate=s.deepCopy();duplicate.packets.add(packet(12,2,1));
        final int[] count={0};var c=clock(duplicate,new Reader(),(r,t,w)->{count[0]=w.size();return new PeriodicComboClock.DrainServicePlan("two",List.of(new PeriodicComboClock.PacketService(1,1),new PeriodicComboClock.PacketService(2,2)),3);});c.runUntil(4);
        check(count[0]==2,"Repeated semantic packets preserve multiplicity");
    }
    public static void main(String[]args){composition();lockedArrivals();shapeAndUnknown();System.out.println("PeriodicComboDrainTemplateCheck passed: "+checks+" assertions");}
}
