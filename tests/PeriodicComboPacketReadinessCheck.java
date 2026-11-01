import java.util.*;

/** Queue-eligibility mechanics only; no controller/native performance result. */
public final class PeriodicComboPacketReadinessCheck {
    static int checks;
    static void check(boolean value,String why){checks++;if(!value)throw new AssertionError(why);}
    static void reject(Runnable task){checks++;try{task.run();}catch(IllegalStateException expected){return;}throw new AssertionError("Invalid readiness accepted");}
    static class Ready implements PeriodicComboFightModel.PacketReadiness {
        double delay;int calls;final List<double[]> seen=new ArrayList<>();
        Ready(double delay){this.delay=delay;}
        public double ready(int route,int packetId,double wire){calls++;seen.add(new double[]{route,packetId,wire});return wire+delay;}
        public Ready deepCopy(){Ready r=new Ready(delay);r.calls=calls;for(double[] x:seen)r.seen.add(x.clone());return r;}
    }
    static class Override implements PeriodicComboFightModel.PacketReadiness {
        final int route,id;final double wire,queue;
        Override(int route,int id,double wire,double queue){this.route=route;this.id=id;this.wire=wire;this.queue=queue;}
        public double ready(int route,int id,double wire){return route==this.route&&id==this.id&&wire==this.wire?queue:wire;}
        public Override deepCopy(){return this;}
    }
    static PeriodicComboFightModel.Packet packet(int kind,double wire,long seq){return new PeriodicComboFightModel.Packet(kind,wire,3,4,.402,.322375,false,seq);}
    static PeriodicComboClock.Scheduled e(double ms,long seq,PeriodicComboClock.Kind kind,boolean a){return new PeriodicComboClock.Scheduled(Math.round(ms*1e6),seq,kind,a);}
    static final PeriodicComboClock.Policy IDLE=o->new PeriodicComboClock.Control(0,0,false);
    static PeriodicComboFightModel.Config config(){var c=new PeriodicComboFightModel.Config();c.ownRtt=c.otherRtt=0;c.defenderReach=3;return c;}
    public static void main(String[] args)throws Exception {
        // First divergence: wire precedes empty drain, enqueue follows it.
        var s=new PeriodicComboFightModel.State(6);s.packetReadiness=new Override(2,0x17,864.300209,864.521001);
        var p=packet(5,864.300209,s.seq++);p.packetId=0x17;s.enqueue(p);
        check(p.due==864.300209&&p.ready==864.521001,"Wire is preserved separately from exact target-position queue readiness");
        var observations=new ArrayList<PeriodicComboClock.PacketObservation>();
        var observer=new PeriodicComboClock.Policy(){public PeriodicComboClock.Control input(PeriodicComboClock.Observation o){return new PeriodicComboClock.Control(0,0,false);}public void received(PeriodicComboClock.PacketObservation q){observations.add(q);}};
        var t=new PeriodicComboClock.Timing();t.inputMode=PeriodicComboClock.InputMode.NATIVE_QUEUED;
        var clock=new PeriodicComboClock(s,config(),t,observer,IDLE,List.of(e(864.308084,0,PeriodicComboClock.Kind.CLIENT_DRAIN,true),e(872.386126,1,PeriodicComboClock.Kind.CLIENT_DRAIN,true)));
        clock.runUntil(864.308084);check(s.ab.gx==6&&s.packets.size()==1,"Wire-before-start is insufficient when enqueue misses drain");
        clock.runUntil(872.386126);check(s.ab.gx==3&&s.packets.isEmpty(),"Next drain handles the now-ready target packet");
        check(observations.size()==1&&observations.get(0).arrivedAt()==864.300209&&observations.get(0).handledAt()==872.386126,"Own observation retains raw receipt and independent handling time");

        // S19 before S12 on the same route; only S12 misses the earlier drain.
        s=new PeriodicComboFightModel.State(6);s.packetReadiness=new Override(3,0x12,3537.888230,3538.586292);
        s.add(13,3537.888230,0,0,0,0,false);s.add3(4,3537.888230,0,0,0,.402,.322375,.141625,false);
        var before=s.b.q;PeriodicComboFightModel.receive(s,3537.938251,false);
        check(s.ba.lastHurtTime==3537.938251&&s.b.q.equals(before)&&s.packets.size()==1,"Earlier S19 can run while delayed later S12 remains queued");
        check(s.packets.get(0).packetId==0x12&&s.packets.get(0).due==3537.888230,"S12 wire arrival remains shared stage-mean value");
        PeriodicComboFightModel.receive(s,3548.126042,false);
        check(s.b.q.vx()==.402&&s.b.q.vy()==.322375&&s.b.q.vz()==.141625,"S12 overwrites velocity only at its eligible later drain");
        check(s.b.q.x()==before.x()&&s.b.q.y()==before.y(),"Eligibility change does not translate body or replace physics");

        // Route FIFO forbids later packets overtaking a delayed first task.
        s=new PeriodicComboFightModel.State(6);s.packetReadiness=new Override(2,0x1a,10,100);
        s.add(12,10,0,0,0,0,false);s.add(8,20,0,0,1,2,false);s.add(13,30,0,0,0,0,false);
        check(s.packets.get(0).ready==100&&s.packets.get(1).ready==100&&s.packets.get(2).ready==30,"Readiness FIFO applies within routes, not between independent clients");
        check(PeriodicComboFightModel.due(s,50,1).isEmpty(),"Later receipt cannot escape blocked route FIFO");
        var ready=PeriodicComboFightModel.due(s,100,1);
        check(ready.size()==2&&ready.get(0).kind==12&&ready.get(1).kind==8,"Clamped equal readiness preserves generated FIFO order");
        check(PeriodicComboFightModel.due(s,50,2).size()==1,"Other route is independently eligible");

        // Server drains use enqueue readiness as well, including sprint-before-move ordering.
        s=new PeriodicComboFightModel.State(6);s.packetReadiness=new Ready(5);s.add(2,10,0,0,0,0,true);
        PeriodicComboFightModel.serverDrain(s,config(),14);check(!s.serverSprint&&s.packets.size()==1,"Server input is not handled merely because wire arrived");
        PeriodicComboFightModel.serverDrain(s,config(),15);check(s.serverSprint&&s.packets.isEmpty(),"Server input enters on readiness boundary, inclusively");

        // Hook follows all three transport resolution paths exactly once.
        s=new PeriodicComboFightModel.State(6);var hook=new Ready(2);s.packetReadiness=hook;s.extraLatency[2]=3;s.add(5,10,3,4,0,0,false);
        check(hook.calls==1&&hook.seen.get(0)[2]==13&&s.packets.get(0).ready==15,"Legacy extra latency is resolved before readiness callback");
        s=new PeriodicComboFightModel.State(6);hook=new Ready(2);s.packetReadiness=hook;
        s.packetTiming=new PeriodicComboFightModel.PacketTiming(){public double arrival(int route,double send,double half){return send+half+4;}public PeriodicComboFightModel.PacketTiming deepCopy(){return this;}};
        s.add(5,10,3,4,0,0,false);check(hook.calls==1&&hook.seen.get(0)[2]==14&&s.packets.get(0).due==14&&s.packets.get(0).ready==16,"Empirical arrival is resolved before readiness callback");
        s=new PeriodicComboFightModel.State(6);hook=new Ready(2);s.packetReadiness=hook;s.transport=new PeriodicComboTransport();
        s.transport.add(new String[]{"NETWORK","5","0","15","3","4","0","0","-1","0","0","21"});s.transport.seal();s.add(5,10,3,4,0,0,false);
        check(hook.calls==1&&hook.seen.get(0)[2]==15&&s.packets.get(0).due==15&&s.packets.get(0).ready==17,"Recorded transport arrival is resolved before readiness, never silently bypassed");

        // Complete state forks preserve and isolate pending packets, hook history and watermarks.
        s=new PeriodicComboFightModel.State(6);s.packetReadiness=new Ready(30);s.add(8,10,0,0,1,2,false);var copy=s.deepCopy();
        check(copy.packets.get(0)!=s.packets.get(0)&&copy.packets.get(0).ready==40,"Pending packet is independently copied with readiness");
        check(copy.packetReadiness!=s.packetReadiness&&((Ready)copy.packetReadiness).calls==1,"Mutable readiness hook history is copied");
        ((Ready)copy.packetReadiness).delay=100;copy.add(8,20,0,0,1,2,false);
        check(((Ready)s.packetReadiness).calls==1&&s.queueReadyWatermarks[2]==40&&copy.queueReadyWatermarks[2]==120,"Generated fork packets cannot mutate original hook or route watermark");
        copy.packets.get(0).ready=111;check(s.packets.get(0).ready==40,"Pending readiness mutation is isolated");
        PeriodicComboFightModel.receive(s,40,true);check(copy.packets.size()==2,"Draining source does not drain fork queue");

        // Identity and null hooks must yield exactly the same canonical body/state history.
        var legacy=new PeriodicComboFightModel.State(6);var identity=new PeriodicComboFightModel.State(6);identity.packetReadiness=new Ready(0);
        var lc=new PeriodicComboClock(legacy,config(),new PeriodicComboClock.Timing(),IDLE,IDLE);
        var ic=new PeriodicComboClock(identity,config(),new PeriodicComboClock.Timing(),IDLE,IDLE);
        for(int i=0;i<10;i++){lc.runUntil(i*50);ic.runUntil(i*50);var comparable=identity.deepCopy();comparable.packetReadiness=null;PeriodicComboForkCheck.equal(legacy,comparable,"legacy-identity@"+i);checks++;}
        var defaultOrder=new PeriodicComboFightModel.State(6);
        defaultOrder.add(8,20,0,0,0,0,false);defaultOrder.add(8,10,0,0,0,0,false);
        var legacyDue=PeriodicComboFightModel.due(defaultOrder,10,1);
        check(legacyDue.size()==1&&legacyDue.get(0).due==10,"Null hook preserves historical raw-due sorting even for non-FIFO legacy arrivals");
        for(double value:new double[]{Double.NaN,Double.POSITIVE_INFINITY,9}){
            final double bad=value;var invalid=new PeriodicComboFightModel.State(6);invalid.packetReadiness=new Ready(0){public double ready(int route,int id,double wire){return bad;}};
            reject(()->invalid.add(8,10,0,0,0,0,false));check(invalid.packets.isEmpty(),"Invalid hook cannot append a malformed ready packet");
        }
        System.out.println("PeriodicComboPacketReadinessCheck passed: "+checks+" checks");
    }
}
