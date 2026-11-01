import java.util.*;

/** Independent opt-in service mechanics. No controller/search/performance claim. */
public final class PeriodicComboServiceBackpressureCheck {
    static int checks;
    static void check(boolean value,String why){checks++;if(!value)throw new AssertionError(why);}
    static void reject(Runnable task,String contains){
        checks++;try{task.run();}catch(IllegalArgumentException|IllegalStateException expected){
            if(expected.getMessage()!=null&&expected.getMessage().contains(contains))return;
            throw new AssertionError("Wrong rejection: "+expected,expected);
        }throw new AssertionError("Missing rejection: "+contains);
    }
    static class Reader implements PeriodicComboClock.Policy {
        final List<PeriodicComboClock.Observation> inputs=new ArrayList<>();
        final List<PeriodicComboClock.PacketObservation> received=new ArrayList<>();
        final boolean gate;
        Reader(){this(false);}Reader(boolean gate){this.gate=gate;}
        public PeriodicComboClock.Control input(PeriodicComboClock.Observation o){inputs.add(o);return new PeriodicComboClock.Control(0,0,gate);}
        public void received(PeriodicComboClock.PacketObservation p){received.add(p);}
    }
    static PeriodicComboClock.Scheduled e(double ms,long sequence,PeriodicComboClock.Kind kind,boolean a){
        return new PeriodicComboClock.Scheduled(Math.round(ms*1e6),sequence,kind,a);
    }
    static PeriodicComboFightModel.Packet packet(int kind,double raw,double ready,long sequence){
        var p=new PeriodicComboFightModel.Packet(kind,raw,8,4,.2,.3,false,sequence);p.ready=ready;p.vz=-.1;return p;
    }
    static PeriodicComboFightModel.ServiceEnvelope envelope(){
        // Offsets from actual drain: cue2ms, handler return2.5ms, unlock4..6ms.
        return new PeriodicComboFightModel.ServiceEnvelope("independent-batch",2_500_000,4_000_000,6_000_000);
    }
    static PeriodicComboFightModel.State initial(boolean service){
        var s=new PeriodicComboFightModel.State(6);s.ab.lastHurtTime=-11;
        var cue=packet(12,1,1,0);cue.cueDelayNanos=2_000_000L;if(service)cue.service=envelope();s.packets.add(cue);
        return s;
    }
    static List<PeriodicComboClock.Scheduled> opportunities(){
        return List.of(e(0,0,PeriodicComboClock.Kind.TICK_HEAD,true),
            e(3,1,PeriodicComboClock.Kind.CLIENT_DRAIN,true),
            e(4,2,PeriodicComboClock.Kind.TICK_HEAD,true),
            e(4,3,PeriodicComboClock.Kind.TICK_HEAD,false),
            e(4.1,4,PeriodicComboClock.Kind.INTERP,true),
            e(4.2,5,PeriodicComboClock.Kind.MOVE,true),
            e(4.3,6,PeriodicComboClock.Kind.FLUSH,true),
            e(4.4,7,PeriodicComboClock.Kind.FRAME_ENTRY,true),
            e(4.5,8,PeriodicComboClock.Kind.POLL_FRAME,true),
            e(4.55,9,PeriodicComboClock.Kind.POLL_NATIVE,true),
            e(4.6,10,PeriodicComboClock.Kind.CONSUME,true),
            e(4.7,11,PeriodicComboClock.Kind.SERVER_END,true),
            e(5.5,12,PeriodicComboClock.Kind.CLIENT_DRAIN,true),
            e(12,13,PeriodicComboClock.Kind.TICK_HEAD,true),
            e(12.1,14,PeriodicComboClock.Kind.CLIENT_DRAIN,true),
            e(20,15,PeriodicComboClock.Kind.SERVER_END,true));
    }
    static PeriodicComboClock clock(PeriodicComboFightModel.State s,PeriodicComboClock.ServiceMode mode,
            Reader a,Reader b,List<PeriodicComboClock.Scheduled> tape){
        var config=new PeriodicComboFightModel.Config();config.ownRtt=config.otherRtt=0;
        var timing=new PeriodicComboClock.Timing();timing.inputMode=PeriodicComboClock.InputMode.NATIVE_QUEUED;
        timing.explicitPolls=true;timing.causalBackpressure=true;timing.packetCueDelays=true;timing.serviceMode=mode;
        return new PeriodicComboClock(s,config,timing,a,b,tape);
    }
    static void modes()throws Exception{
        var modes=List.of(PeriodicComboClock.ServiceMode.LOWER,PeriodicComboClock.ServiceMode.MIDPOINT,PeriodicComboClock.ServiceMode.UPPER);
        for(int i=0;i<modes.size();i++){
            double unlock=7+i;var a=new Reader();var b=new Reader();var c=clock(initial(true),modes.get(i),a,b,opportunities());
            var audit=new ArrayList<String>();c.setAudit((t,k,s)->audit.add(t+":"+k));
            c.runUntil(4.9);
            check(c.serviceBusyUntil(true)==Math.round(unlock*1e6),"Envelope is drain-relative: "+modes.get(i));
            check(c.serviceBusyUntil(false)<=4_900_000,"A handler never makes B busy");
            check(a.inputs.size()==1&&b.inputs.size()==1&&b.inputs.get(0).time()==4,"B HEAD progresses while A HEAD waits");
            check(c.state.serverTicks==1,"Server progresses during client handler");
            check(a.received.isEmpty()&&c.state.ab.lastHurtTime==-11,"No early cue");
            c.runUntil(5);
            check(c.state.ab.lastHurtTime==5&&a.received.size()==1,"Cue can publish inside enclosing service");
            check(a.received.get(0).arrivedAt()==1&&a.received.get(0).handledAt()==5,"Raw receipt and publication remain distinct");
            check(a.inputs.size()==1,"Publication does not itself release ordinary stages");
            c.runUntil(unlock);
            check(a.inputs.size()==2&&a.inputs.get(1).time()==unlock,"Own HEAD waits for selected unlock");
            check(a.inputs.get(1).lastTargetHurtTime()==5,"Resumed HEAD observes published cue");
            var head=c.headTiming(true);
            check(head.originalTime()==4&&head.actualTime()==unlock&&head.sequence()==2,"Deferred HEAD retains donor identity");
            check(c.backpressureStats(true).deferredStages()==9,"All nine ordinary A opportunities delayed once");
            check(c.backpressureStats(false).deferredStages()==0,"No cross-actor deferral");
            for(var kind:List.of(PeriodicComboClock.Kind.TICK_HEAD,PeriodicComboClock.Kind.INTERP,PeriodicComboClock.Kind.MOVE,
                    PeriodicComboClock.Kind.FLUSH,PeriodicComboClock.Kind.FRAME_ENTRY,PeriodicComboClock.Kind.POLL_FRAME,
                    PeriodicComboClock.Kind.POLL_NATIVE,PeriodicComboClock.Kind.CONSUME,PeriodicComboClock.Kind.CLIENT_DRAIN))
                check(c.backpressureStats(true).stageCounts().get(kind)==1,"Deferred stage kind "+kind);
            check(audit.indexOf(unlock+":POLICYA")<audit.indexOf(unlock+":CLIENT_DRAINA"),"Earlier original HEAD cannot be overtaken by drain");
            c.runUntil(20);check(a.inputs.size()==3,"No HEAD dropped or duplicated");
        }
    }
    static void enqueues(){
        var s=initial(true);var first=packet(5,5,5,1);first.x=9;var second=packet(5,6,6,2);second.x=11;
        var atUnlock=packet(5,7,7,3);atUnlock.x=13;var after=packet(5,7.000001,7.000001,4);after.x=15;
        s.packets.addAll(List.of(first,second,atUnlock,after));var a=new Reader();var c=clock(s,PeriodicComboClock.ServiceMode.LOWER,a,new Reader(),opportunities());
        c.runUntil(5);
        check(a.received.size()==1&&s.ab.gx==6,"Future ready tasks excluded from first selected batch");
        c.runUntil(7);
        check(a.received.size()==4,"Next actual drain includes two blocked enqueues and exact-unlock task");
        check(a.received.subList(1,4).stream().map(PeriodicComboClock.PacketObservation::goalX).toList().equals(List.of(9.,11.,13.)),"FIFO payload snapshots preserved after clamping");
        check(a.received.subList(1,4).stream().allMatch(p->p.handledAt()==7),"Blocked tasks handled at actual next drain");
        check(a.received.get(1).arrivedAt()==5&&a.received.get(2).arrivedAt()==6,"No raw receipt retiming");
        check(first.ready==7&&second.ready==7&&atUnlock.ready==7,"Lock holds successful enqueue until unlock, no epsilon");
        check(s.packets.stream().filter(p->PeriodicComboTransport.route(p.kind)==2).toList().equals(List.of(after))&&after.ready==7.000001,"After-unlock enqueue cannot join earlier drain");
        check(c.serviceStats(true).enqueueDeferrals()==2,"Only strictly inside-lock enqueue attempts counted");
        c.runUntil(12.1);check(a.received.size()==5&&a.received.get(4).goalX()==15,"Excluded task preserved for following drain");
    }
    static void batchAndLimits(){
        var s=initial(true);var own=packet(14,2,2,1);own.cueDelayNanos=3_000_000L;own.service=envelope();s.packets.add(own);
        var a=new Reader();var c=clock(s,PeriodicComboClock.ServiceMode.LOWER,a,new Reader(),opportunities());c.runUntil(6.5);
        check(c.serviceBusyUntil(true)==7_000_000,"Same-batch service envelope not summed per packet");
        check(s.ab.lastHurtTime==5&&s.a.lastHurtTime==6,"Different cue publications retain FIFO and separate times");
        check(a.inputs.size()==1,"Run limit inside service leaves own HEAD pending");
        c.runUntil(7);check(a.inputs.size()==2,"Resuming limit consumes pending HEAD exactly once");
        var bad=initial(true);bad.packets.get(0).service=new PeriodicComboFightModel.ServiceEnvelope("too-short",0,1_000_000,1_000_000);
        reject(()->clock(bad,PeriodicComboClock.ServiceMode.LOWER,new Reader(),new Reader(),opportunities()).runUntil(4),"UNSUPPORTED");
        reject(()->clock(initial(false),PeriodicComboClock.ServiceMode.LOWER,new Reader(),new Reader(),opportunities()).runUntil(4),"UNSUPPORTED");
        reject(()->new PeriodicComboFightModel.ServiceEnvelope("invalid",2,1,3),"Invalid");
        reject(()->new PeriodicComboFightModel.ServiceEnvelope("invalid",0,3,2),"Invalid");
    }
    static void forkAndRepresentation()throws Exception{
        var a=new Reader();var b=new Reader();var base=clock(initial(true),PeriodicComboClock.ServiceMode.LOWER,a,b,opportunities());base.runUntil(4.9);
        var fa=new Reader();var fb=new Reader();var fork=base.fork(fa,fb);PeriodicComboForkCheck.equal(base,fork,"busy service fork");
        var frozen=base.fork(new Reader(),new Reader());frozen.enableSharedCalendar();var left=new ArrayList<String>();var right=new ArrayList<String>();
        fork.setAudit((t,k,s)->left.add(t+":"+k+":"+s.a.q+":"+s.b.q));frozen.setAudit((t,k,s)->right.add(t+":"+k+":"+s.a.q+":"+s.b.q));
        while(frozen.runHeadInterval(true,20)){}
        fork.runUntil(20);PeriodicComboForkCheck.equal(fork.state,frozen.state,"heap/shared physical state");
        check(left.equals(right),"Heap/shared/head-boundary event chronology identical");
        check(fork.serviceStats(true).equals(frozen.serviceStats(true)),"Service diagnostics identical across representations");
        check(base.time()==4.9&&a.received.isEmpty()&&base.state.ab.lastHurtTime==-11,"Sibling cannot publish into source");
        base.runUntil(20);PeriodicComboForkCheck.equal(base.state,fork.state,"busy clone continuation");
        check(a.received.equals(fa.received)&&a.inputs.subList(1,a.inputs.size()).equals(fa.inputs),"In-flight publications and HEAD observations copied once");
        check(base.serviceStats(true).equals(fork.serviceStats(true)),"Busy histories copied and isolated");
    }
    static void preservedPresses(){
        // Two cap-respecting presses exist BEFORE service. Its resumed HEAD closes
        // admission, but both native queued presses must still be processed.
        class Gate extends Reader {
            public PeriodicComboClock.Control input(PeriodicComboClock.Observation o){super.input(o);return new PeriodicComboClock.Control(0,0,o.time()<54);}
        }
        var state=new PeriodicComboFightModel.State(2);var cue=packet(12,53,53,0);cue.cueDelayNanos=2_000_000L;cue.service=envelope();state.packets.add(cue);
        var tape=List.of(e(0,0,PeriodicComboClock.Kind.TICK_HEAD,true),e(.5,1,PeriodicComboClock.Kind.POLL_NATIVE,true),
            e(50,2,PeriodicComboClock.Kind.TICK_HEAD,true),e(50.499999,3,PeriodicComboClock.Kind.POLL_FRAME,true),e(50.5,4,PeriodicComboClock.Kind.POLL_FRAME,true),
            e(54,5,PeriodicComboClock.Kind.CLIENT_DRAIN,true),e(54.5,6,PeriodicComboClock.Kind.TICK_HEAD,true),e(54.6,7,PeriodicComboClock.Kind.POLL_FRAME,true),
            e(55,8,PeriodicComboClock.Kind.CONSUME,true),e(60,9,PeriodicComboClock.Kind.SERVER_END,true));
        var c=clock(state,PeriodicComboClock.ServiceMode.LOWER,new Gate(),new Reader(),tape);var events=new ArrayList<PeriodicComboClock.ClickEvent>();c.setClickAudit(events::add);c.runUntil(54.1);
        check(c.queuedClicks(true)==2,"Both cap-respecting earlier presses remain pending during service");
        var sibling=c.fork(new Gate(),new Reader());check(sibling.queuedClicks(true)==2,"Busy fork copies all already-admitted presses");
        c.runUntil(60);var admitted=events.stream().filter(e->e.action().equals("ADMIT")).toList();var consumed=events.stream().filter(e->e.action().equals("CONSUME")).toList();
        check(admitted.stream().map(PeriodicComboClock.ClickEvent::time).toList().equals(List.of(.5,50.5)),"Service cannot bypass50ms admission cap or invent catch-up presses");
        check(consumed.size()==2&&consumed.stream().allMatch(e->e.time()==58),"Multiple admitted presses processed at one delayed native consume");
        check(c.queuedClicks(true)==0&&state.a.localContacts==2,"Closing the gate never skips existing ordinary contacts");
        check(sibling.queuedClicks(true)==2&&sibling.state.a.localContacts==0,"Sibling pending queue is isolated");
        sibling.runUntil(60);check(sibling.queuedClicks(true)==0&&sibling.state.a.localContacts==2,"Busy sibling continues without losing presses");
    }
    static void defaults(){
        var tape=List.of(e(0,0,PeriodicComboClock.Kind.TICK_HEAD,true),e(3,1,PeriodicComboClock.Kind.CLIENT_DRAIN,true),e(6,2,PeriodicComboClock.Kind.TICK_HEAD,true),e(20,3,PeriodicComboClock.Kind.SERVER_END,true));
        var a=new Reader();var b=new Reader();var x=clock(initial(false),PeriodicComboClock.ServiceMode.NONE,a,new Reader(),tape);
        var y=clock(initial(true),PeriodicComboClock.ServiceMode.NONE,b,new Reader(),tape);x.runUntil(20);y.runUntil(20);
        check(a.received.equals(b.received)&&a.inputs.equals(b.inputs),"NONE ignores attached service envelope exactly");
        check(y.serviceStats(true).deferredStages()==0,"NONE creates no service deferral");
        reject(()->clock(initial(true),PeriodicComboClock.ServiceMode.NONE,new Reader(),new Reader(),opportunities()).runUntil(4),"UNSUPPORTED_CUE_SERVICE_OVERLAP");
        var plain=new PeriodicComboFightModel.State(6);plain.packets.add(packet(5,1,1,0));var zero=plain.deepCopy();zero.packets.get(0).service=new PeriodicComboFightModel.ServiceEnvelope("zero",0,0,0);
        var r0=new Reader();var r1=new Reader();var c0=clock(plain,PeriodicComboClock.ServiceMode.NONE,r0,new Reader(),tape);var c1=clock(zero,PeriodicComboClock.ServiceMode.LOWER,r1,new Reader(),tape);c0.runUntil(20);c1.runUntil(20);
        check(r0.received.equals(r1.received)&&r0.inputs.equals(r1.inputs),"Zero service preserves observations exactly");
    }
    public static void main(String[]args)throws Exception{
        modes();enqueues();batchAndLimits();forkAndRepresentation();preservedPresses();defaults();
        System.out.println("PeriodicComboServiceBackpressureCheck passed: "+checks+" assertions; semantic comparisons="+PeriodicComboForkCheck.comparisons);
    }
}
