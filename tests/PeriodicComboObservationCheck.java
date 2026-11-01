import java.util.*;

/** Read-only own-packet boundary: routing, causality, payload isolation and parity. */
public final class PeriodicComboObservationCheck {
    static int checks;
    static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
    static final class Reader implements PeriodicComboClock.Policy {
        final List<PeriodicComboClock.PacketObservation> seen=new ArrayList<>();
        public PeriodicComboClock.Control input(PeriodicComboClock.Observation o){return new PeriodicComboClock.Control(0,0,false);}
        public void received(PeriodicComboClock.PacketObservation p){
            check(p.arrivedAt()<=p.handledAt(),"No future packet exposed");seen.add(p);
        }
    }
    static void packets(PeriodicComboFightModel.State s){
        for(boolean a:new boolean[]{true,false}){
            double offset=a?10:20;
            var p=new PeriodicComboFightModel.Packet(a?5:6,1.25,offset,4,0,0,true,s.seq++);
            p.z=offset+1;p.ground=true;p.packetId=0x18;s.enqueue(p);
            var look=new PeriodicComboFightModel.Packet(a?5:6,1.25,offset,4,0,0,false,s.seq++);
            look.z=offset+1;look.ground=false;look.packetId=0x16;s.enqueue(look);
            s.add3(a?8:4,1.25,0,0,0,offset/100,offset/200,offset/300,false);
            s.add(a?10:11,1.25,0,0,0,0,true);
            s.add(a?12:13,1.25,2,0,0,0,false);
            s.add(a?14:15,1.25,2,0,0,0,false);
        }
    }
    public static void main(String[] args)throws Exception{
        var state=new PeriodicComboFightModel.State(4093,0,4099,0);packets(state);
        var cfg=new PeriodicComboFightModel.Config();cfg.ownRtt=cfg.otherRtt=0;
        var timing=new PeriodicComboClock.Timing();timing.inputMode=PeriodicComboClock.InputMode.NATIVE_QUEUED;
        var a=new Reader();var b=new Reader();var c=new PeriodicComboClock(state,cfg,timing,a,b);
        var silent=c.fork(o->new PeriodicComboClock.Control(0,0,false),o->new PeriodicComboClock.Control(0,0,false));
        c.runUntil(1);check(a.seen.isEmpty()&&b.seen.isEmpty(),"Before arrival, neither policy knows packets");
        c.runUntil(2);check(a.seen.isEmpty()&&b.seen.isEmpty(),"Arrival does not bypass own main-thread handling");
        c.runUntil(3.5);silent.runUntil(3.5);
        PeriodicComboForkCheck.equal(c,silent,"observation_noninterference");
        for(boolean fromA:new boolean[]{true,false}){
            var seen=fromA?a.seen:b.seen;double offset=fromA?10:20,handled=fromA?2.5:3.5;
            check(seen.size()==6,"Only six own-addressed packets");
            var kinds=EnumSet.noneOf(PeriodicComboClock.ReceivedKind.class);
            for(var p:seen){kinds.add(p.kind());check(p.arrivedAt()==1.25&&p.handledAt()==handled,"Local receipt and application distinct");
                boolean target=p.kind()==PeriodicComboClock.ReceivedKind.TARGET_POSITION||p.kind()==PeriodicComboClock.ReceivedKind.TARGET_LOOK;
                boolean velocity=p.kind()==PeriodicComboClock.ReceivedKind.OWN_VELOCITY;
                check(target?(p.goalX()==offset&&p.goalY()==4&&p.goalZ()==offset+1):Double.isNaN(p.goalX())&&Double.isNaN(p.goalY())&&Double.isNaN(p.goalZ()),"Own received goal only, no remote actual state");
                check(velocity?(p.vx()==offset/100&&p.vy()==offset/200&&p.vz()==offset/300):Double.isNaN(p.vx())&&Double.isNaN(p.vy())&&Double.isNaN(p.vz()),"Only own velocity payload");
                if(p.kind()==PeriodicComboClock.ReceivedKind.TARGET_POSITION)check(p.groundFlag()&&p.packetId()==0x18,"Received ground flag, not actual remote ground");
                if(p.kind()==PeriodicComboClock.ReceivedKind.TARGET_LOOK)check(!p.groundFlag()&&p.packetId()==0x16,"Look distinguishable from position update");
                check(p.sprintFlag()==(p.kind()==PeriodicComboClock.ReceivedKind.OWN_SPRINT),"Sprint payload only on own metadata");
            }
            check(kinds.size()==6,"All payload routes covered");
        }
        System.out.println("PeriodicComboObservationCheck passed: "+checks+" checks; full clock noninterference comparisons="+PeriodicComboForkCheck.comparisons);
    }
}
