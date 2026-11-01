import java.util.*;

/** Conditional busy-client mechanics, never controller performance evidence. */
public final class PeriodicComboBackpressureCheck {
    static int checks;
    static void check(boolean yes,String why){checks++;if(!yes)throw new AssertionError(why);}
    static void rejects(Runnable task,String prefix){checks++;try{task.run();}catch(IllegalArgumentException|IllegalStateException expected){if(expected.getMessage().startsWith(prefix))return;throw expected;}throw new AssertionError("Missing rejection: "+prefix);}
    static PeriodicComboClock.Scheduled e(double ms,long sequence,PeriodicComboClock.Kind kind,boolean a){return new PeriodicComboClock.Scheduled(Math.round(ms*1_000_000),sequence,kind,a);}
    static final PeriodicComboClock.Policy PRESS=o->new PeriodicComboClock.Control(0,0,true),OFF=o->new PeriodicComboClock.Control(0,0,false);
    static PeriodicComboClock clock(boolean enabled,long cost,List<PeriodicComboClock.Scheduled> tape,PeriodicComboClock.Policy a){
        var c=new PeriodicComboFightModel.Config();c.ownRtt=c.otherRtt=0;c.defenderReach=3;
        var t=new PeriodicComboClock.Timing();t.inputMode=PeriodicComboClock.InputMode.NATIVE_QUEUED;t.explicitPolls=true;t.causalBackpressure=enabled;t.injectionTiming=(side,poll)->side?cost:0;
        return new PeriodicComboClock(new PeriodicComboFightModel.State(2),c,t,a,OFF,tape);
    }
    static List<PeriodicComboClock.Scheduled> allStages(){
        return List.of(e(0,0,PeriodicComboClock.Kind.TICK_HEAD,true),e(.05,1,PeriodicComboClock.Kind.CONSUME,true),e(.1,2,PeriodicComboClock.Kind.MOVE,true),e(.2,3,PeriodicComboClock.Kind.FLUSH,true),
            e(.3,4,PeriodicComboClock.Kind.INTERP,true),e(.4,5,PeriodicComboClock.Kind.FRAME_ENTRY,true),e(1,6,PeriodicComboClock.Kind.POLL_FRAME,true),
            e(2,100,PeriodicComboClock.Kind.TICK_HEAD,true),e(3,99,PeriodicComboClock.Kind.FRAME_ENTRY,true),e(4,98,PeriodicComboClock.Kind.CLIENT_DRAIN,true),
            e(5,97,PeriodicComboClock.Kind.CONSUME,true),e(6,96,PeriodicComboClock.Kind.MOVE,true),e(7,95,PeriodicComboClock.Kind.FLUSH,true),
            e(8,94,PeriodicComboClock.Kind.INTERP,true),e(9,93,PeriodicComboClock.Kind.POLL_FRAME,true),e(10,92,PeriodicComboClock.Kind.POLL_NATIVE,true),
            e(2.5,200,PeriodicComboClock.Kind.TICK_HEAD,false),e(3.5,201,PeriodicComboClock.Kind.MOVE,false),e(4.5,202,PeriodicComboClock.Kind.FLUSH,false),
            e(5.5,203,PeriodicComboClock.Kind.INTERP,false),e(6.5,204,PeriodicComboClock.Kind.CONSUME,false),
            e(2.7,300,PeriodicComboClock.Kind.SERVER_DRAIN,true),e(3.7,301,PeriodicComboClock.Kind.SERVER_ENTITY,true),e(4.7,302,PeriodicComboClock.Kind.SERVER_TRACK,true),e(5.7,303,PeriodicComboClock.Kind.SERVER_END,true),
            e(100,400,PeriodicComboClock.Kind.SERVER_END,true));
    }
    public static void main(String[] args){
        var tape=allStages();var fit=clock(false,100_000,tape,PRESS);var fitEnabled=clock(true,100_000,tape,PRESS);
        var left=new ArrayList<String>();var right=new ArrayList<String>();
        fit.setAudit((t,k,s)->left.add(t+":"+k+":"+s.a.q+":"+s.b.q+":"+s.a.localContacts));
        fitEnabled.setAudit((t,k,s)->right.add(t+":"+k+":"+s.a.q+":"+s.b.q+":"+s.a.localContacts));
        fit.runUntil(100);fitEnabled.runUntil(100);
        check(left.equals(right),"Original fitting costs produce identical event/state sequence");
        check(fitEnabled.backpressureStats(true).deferredStages()==0,"No invented deferrals when original costs fit");
        var strict=clock(false,10_000_000,tape,PRESS);rejects(()->strict.runUntil(20),"UNSUPPORTED_INJECTION_OVERLAP");

        final PeriodicComboClock[] holder=new PeriodicComboClock[1];final int[] mapped={0};
        PeriodicComboClock.Policy mappedPolicy=o->{
            var head=holder[0].headTiming(true);check(head!=null&&head.tick()==o.tick()&&head.actualTime()==o.time(),"Head mapping available before policy input");
            if(head.originalTime()==2){check(head.actualTime()==11&&head.sequence()==100,"Delayed head retains original wall-label identity");mapped[0]++;}
            return new PeriodicComboClock.Control(0,0,true);
        };
        var busy=clock(true,10_000_000,tape,mappedPolicy);holder[0]=busy;
        var audit=new ArrayList<String>();var clicks=new ArrayList<PeriodicComboClock.ClickEvent>();
        busy.setAudit((t,k,s)->audit.add(t+":"+k));busy.setClickAudit(clicks::add);busy.runUntil(6);
        check(busy.state.serverTicks==1&&audit.contains("3.5:MOVEB"),"Other client/server continue while A is busy");
        check(busy.headTiming(false).actualTime()==2.5,"Other actor head is not delayed");
        check(busy.backpressureStats(true).deferredStages()==5,"Every reached busy A stage deferred, including head/drain/consume/move");
        int sourceEvents=audit.size();var fork=busy.fork(PRESS,OFF);var forkClicks=new ArrayList<PeriodicComboClock.ClickEvent>();fork.setClickAudit(forkClicks::add);fork.runUntil(11);
        check(audit.size()==sourceEvents&&busy.state.a.localContacts==0,"Fork detaches audit and cannot mutate source state");
        check(fork.state.a.localContacts==1&&fork.queuedClicks(true)==0,"Pending injection/press/consume copied completely");
        check(busy.backpressureStats(true).deferredStages()==5&&fork.backpressureStats(true).deferredStages()==9,"Deferral counters and pending event queue isolated across forks");
        busy.runUntil(11);check(mapped[0]==1&&busy.state.a.localContacts==1,"Completion precedes deferred head and queued consumption");
        var stats=busy.backpressureStats(true);
        check(stats.deferredStages()==9&&stats.deferralAttempts()==9&&stats.maxDelayMillis()==9,"Unique stage counts and total reference delay reported");
        for(var kind:List.of(PeriodicComboClock.Kind.TICK_HEAD,PeriodicComboClock.Kind.FRAME_ENTRY,PeriodicComboClock.Kind.CLIENT_DRAIN,PeriodicComboClock.Kind.CONSUME,PeriodicComboClock.Kind.MOVE,PeriodicComboClock.Kind.FLUSH,PeriodicComboClock.Kind.INTERP,PeriodicComboClock.Kind.POLL_FRAME,PeriodicComboClock.Kind.POLL_NATIVE))check(stats.stageCounts().get(kind)==1,"All same-client stage kinds participate: "+kind);
        check(busy.backpressureStats(false).deferredStages()==0,"No cross-actor backpressure");
        var atCompletion=audit.stream().filter(s->s.startsWith("11.0:")).toList();
        check(atCompletion.equals(List.of("11.0:INJECTION_COMPLETEA","11.0:POLICYA","11.0:FRAMEA","11.0:CLIENT_DRAINA","11.0:INPUTA","11.0:MOVEA","11.0:AFTER_MOVE","11.0:FLUSHA","11.0:INTERPA","11.0:POLLA","11.0:POLLA")),"Original client chronological order retained despite reverse numeric sequences");
        check(clicks.stream().filter(c->c.action().equals("ADMIT")).count()==1,"Delayed polls cannot bypass physical cap");
        boolean immutable=false;try{stats.stageCounts().put(PeriodicComboClock.Kind.MOVE,99L);}catch(UnsupportedOperationException expected){immutable=true;}check(immutable,"Diagnostic map cannot mutate clock counters");

        var capTape=List.of(e(0,0,PeriodicComboClock.Kind.TICK_HEAD,true),e(.1,1,PeriodicComboClock.Kind.POLL_NATIVE,true),
            e(.2,2,PeriodicComboClock.Kind.CONSUME,true),e(.3,3,PeriodicComboClock.Kind.MOVE,true),e(.4,4,PeriodicComboClock.Kind.FLUSH,true),
            e(50,5,PeriodicComboClock.Kind.FRAME_ENTRY,true),e(50.1,6,PeriodicComboClock.Kind.POLL_FRAME,true),e(55,7,PeriodicComboClock.Kind.TICK_HEAD,true),
            e(55.099999,8,PeriodicComboClock.Kind.POLL_NATIVE,true),e(55.1,9,PeriodicComboClock.Kind.POLL_NATIVE,true),e(55.2,10,PeriodicComboClock.Kind.CONSUME,true),e(70,11,PeriodicComboClock.Kind.SERVER_END,true));
        var cap=clock(true,5_000_000,capTape,PRESS);var capEvents=new ArrayList<PeriodicComboClock.ClickEvent>();cap.setClickAudit(capEvents::add);cap.runUntil(70);
        var admits=capEvents.stream().filter(c->c.action().equals("ADMIT")).toList();
        check(admits.size()==2&&admits.get(0).time()==5.1&&admits.get(1).time()==60.1,"Cap remains measured from actual completed injection");
        check(admits.get(1).admittedNanos()==55_100_000L&&cap.state.a.localContacts==2,"No early epsilon/catch-up or lost queued click");
        check(capEvents.stream().filter(c->c.action().equals("CONSUME")).allMatch(c->c.time()>=c.admittedNanos()/1_000_000.0),"No queued consumption is advanced before its originating poll");

        var limitTape=List.of(e(0,0,PeriodicComboClock.Kind.TICK_HEAD,true),e(.1,1,PeriodicComboClock.Kind.MOVE,true),e(.2,2,PeriodicComboClock.Kind.FRAME_ENTRY,true),
            e(1,3,PeriodicComboClock.Kind.POLL_FRAME,true),e(11,4,PeriodicComboClock.Kind.TICK_HEAD,true),e(100,5,PeriodicComboClock.Kind.SERVER_END,true));
        var limit=clock(true,60_000_000,limitTape,PRESS);rejects(()->limit.runUntil(100),"UNSUPPORTED_BACKPRESSURE_DELAY");
        var below=new ArrayList<>(limitTape);below.set(4,e(11.000001,4,PeriodicComboClock.Kind.TICK_HEAD,true));
        var belowLimit=clock(true,60_000_000,below,PRESS);belowLimit.runUntil(100);
        check(belowLimit.backpressureStats(true).maxDelayMillis()==49.999999,"Bound uses total original opportunity delay with nanosecond boundary");
        check(belowLimit.headTiming(true).actualTime()>=belowLimit.headTiming(true).originalTime(),"Reference opportunity is never advanced");
        var periodic=new PeriodicComboClock.Timing();periodic.causalBackpressure=true;
        rejects(()->new PeriodicComboClock(new PeriodicComboFightModel.State(6),new PeriodicComboFightModel.Config(),periodic,OFF,OFF),"Causal backpressure requires");
        System.out.println("PeriodicComboBackpressureCheck passed: "+checks+" checks");
    }
}
