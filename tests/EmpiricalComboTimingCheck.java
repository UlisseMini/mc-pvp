import java.util.*;

/** Adapter-only checks, without controller search or recorded payloads. */
public final class EmpiricalComboTimingCheck {
    static int checks;
    static void check(boolean yes,String why){checks++;if(!yes)throw new AssertionError(why);}
    static void reject(Runnable task,String why){checks++;try{task.run();}catch(IllegalArgumentException|IllegalStateException expected){return;}throw new AssertionError(why);}
    static double[][] routes(double... values){double[][] out=new double[4][];for(int i=0;i<4;i++)out[i]=values.clone();return out;}
    static final class Capture implements PeriodicComboFightModel.PacketTiming {
        final List<double[]> calls=new ArrayList<>();
        public double arrival(int route,double send,double halfRtt){calls.add(new double[]{route,send,halfRtt});return send+halfRtt+3;}
        public Capture deepCopy(){Capture copy=new Capture();for(double[] row:calls)copy.calls.add(row.clone());return copy;}
    }
    static PeriodicComboClock.Scheduled event(long time,long sequence,PeriodicComboClock.Kind kind){return new PeriodicComboClock.Scheduled(time*1_000_000L,sequence,kind,true);}
    static PeriodicComboClock.Timing queued(){var t=new PeriodicComboClock.Timing();t.inputMode=PeriodicComboClock.InputMode.NATIVE_QUEUED;return t;}
    static PeriodicComboFightModel.Config config(){var c=new PeriodicComboFightModel.Config();c.ownRtt=c.otherRtt=0;c.defenderReach=3;return c;}
    static final PeriodicComboClock.Policy PRESS=o->new PeriodicComboClock.Control(0,0,true),IDLE=o->new PeriodicComboClock.Control(0,0,false);
    public static void main(String[] args){
        double[][] knots=routes(0,10),residuals=routes(20,0);
        var timing=new EmpiricalComboTiming(knots,residuals,100);
        knots[0][0]=99;residuals[0][0]=99;
        check(timing.arrival(0,1,5)==26,"Caller cannot mutate frozen timing arrays");
        var fork=timing.deepCopy();check(fork.arrival(0,80,5)==85,"Copied route can advance independently");
        check(timing.arrival(0,11,5)==26,"Later generated packet cannot overtake earlier route arrival");
        check(timing.fifoClamps()[0]==1,"FIFO clamping is diagnosed");
        check(timing.arrival(1,11,5)==16,"Independent routes do not share FIFO watermark");
        long[] counts=timing.fifoClamps();counts[0]=99;check(timing.fifoClamps()[0]==1,"Counter access is defensive");
        reject(()->timing.arrival(0,10,5),"Backwards send time rejected");
        reject(()->timing.arrival(0,101,5),"No silent timing extrapolation");
        reject(()->new EmpiricalComboTiming(routes(1),routes(0),100),"Missing initial coverage rejected");
        reject(()->new EmpiricalComboTiming(routes(0,0),routes(0,1),100),"Duplicate knots rejected");
        reject(()->new EmpiricalComboTiming(routes(0),routes(Double.NaN),100),"Nonfinite residual rejected");
        reject(()->new EmpiricalComboTiming(routes(0),routes(-2),100).arrival(0,0,1),"Negative total transport delay rejected");

        var s=new PeriodicComboFightModel.State(6);var c=config();c.ownRtt=100;c.otherRtt=20;
        var capture=new Capture();s.packetTiming=capture;
        var tape=List.of(event(0,0,PeriodicComboClock.Kind.TICK_HEAD),event(1,1,PeriodicComboClock.Kind.MOVE),event(5,2,PeriodicComboClock.Kind.FLUSH));
        var clock=new PeriodicComboClock(s,c,queued(),o->new PeriodicComboClock.Control(2,0,false),IDLE,tape);
        clock.runUntil(1);check(capture.calls.isEmpty(),"Unflushed C03/C0B are not sent packets");
        clock.runUntil(5);check(capture.calls.size()==2,"Generated sprint and movement packets use hook");
        for(double[] call:capture.calls)check(call[0]==0&&call[1]==5&&call[2]==50,"Hook sees actual flush time and declared route RTT only");
        for(var packet:s.packets)check(packet.due==58,"Arrival is network receipt, before separate server task drain");
        var copied=s.deepCopy();check(copied.packetTiming!=s.packetTiming,"Timing hook deep-copied with complete state");
        copied.nominalHalfRtt[0]=999;check(s.nominalHalfRtt[0]==50,"Nominal route latency array isolated");
        check(((Capture)copied.packetTiming).calls.size()==2,"Copied timing history preserved");
        copied.nominalHalfRtt[0]=50;copied.add(3,70,0,0,0,0,false);check(capture.calls.size()==2,"Copied timing mutation cannot leak back");
        s.transport=new PeriodicComboTransport();final var mixed=s;reject(()->mixed.add(3,70,0,0,0,0,false),"Exact payload transport cannot mix with empirical timing");

        // Native failure ordering: selection -> own S12 handler -> consume -> first KB move.
        // S12 changes velocity, not current boxes. The queued click must still contact.
        s=new PeriodicComboFightModel.State(2);c=config();final var incoming=new ArrayList<PeriodicComboClock.PacketObservation>();
        PeriodicComboClock.Policy observer=new PeriodicComboClock.Policy(){
            public PeriodicComboClock.Control input(PeriodicComboClock.Observation own){return new PeriodicComboClock.Control(0,0,true);}
            public void received(PeriodicComboClock.PacketObservation packet){incoming.add(packet);}
        };
        tape=List.of(event(0,0,PeriodicComboClock.Kind.TICK_HEAD),event(2,1,PeriodicComboClock.Kind.CLIENT_DRAIN),
                     event(3,2,PeriodicComboClock.Kind.CONSUME),event(4,3,PeriodicComboClock.Kind.MOVE));
        clock=new PeriodicComboClock(s,c,queued(),observer,IDLE,tape);s.add3(8,2,0,0,0,.5,.4,0,false);
        clock.runUntil(2);check(s.a.q.x()==0&&s.a.q.vx()==.5,"S12 applied before first knockback move without translating body");
        var sameSeed=clock.fork(observer,IDLE);
        clock.runUntil(3);check(s.a.localContacts==1&&clock.queuedClicks(true)==0,"Queued contact survives S12/goal-only drain");
        check(incoming.size()==1&&incoming.get(0).kind()==PeriodicComboClock.ReceivedKind.OWN_VELOCITY,"Own packet callback follows handling");
        check(sameSeed.state.a.localContacts==0&&sameSeed.queuedClicks(true)==1,"Fork keeps independent admitted FIFO and pre-consume state");
        sameSeed.runUntil(3);check(sameSeed.state.a.localContacts==1,"Complete fork reproduces the same pending contact");
        clock.runUntil(4);check(s.a.q.x()>0,"First velocity movement occurs after contact");

        s=new PeriodicComboFightModel.State(2);c=config();
        tape=List.of(event(0,0,PeriodicComboClock.Kind.TICK_HEAD),event(1,1,PeriodicComboClock.Kind.INTERP),event(2,2,PeriodicComboClock.Kind.CONSUME));
        var invalid=new PeriodicComboClock(s,c,queued(),PRESS,IDLE,tape);
        reject(()->invalid.runUntil(2),"Geometry-changing cached-selection crossing remains explicitly unsupported");
        s=new PeriodicComboFightModel.State(2);c=config();
        tape=List.of(event(0,0,PeriodicComboClock.Kind.TICK_HEAD),event(1,1,PeriodicComboClock.Kind.CONSUME),
                     event(50,2,PeriodicComboClock.Kind.TICK_HEAD),event(51,3,PeriodicComboClock.Kind.CONSUME));
        clock=new PeriodicComboClock(s,c,queued(),PRESS,IDLE,tape);clock.runUntil(51);
        check(s.a.localContacts==2,"One unconditional consume opportunity per input tick drains new-policy FIFO without recorded counts");
        System.out.println("EmpiricalComboTimingCheck passed: "+checks+" checks");
    }
}
