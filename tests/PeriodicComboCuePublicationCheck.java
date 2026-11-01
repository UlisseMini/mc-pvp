import java.util.*;

/** Opt-in publication mechanics only. No controller search/performance result. */
public final class PeriodicComboCuePublicationCheck {
    static int checks;
    static void check(boolean condition,String message){checks++;if(!condition)throw new AssertionError(message);}
    static void reject(Runnable r,String text){
        checks++;try{r.run();}catch(IllegalArgumentException|IllegalStateException e){
            if(e.getMessage().contains(text))return;throw new AssertionError("Wrong rejection: "+e,e);
        }throw new AssertionError("Missing rejection: "+text);
    }
    static final PeriodicComboClock.Policy IDLE=o->new PeriodicComboClock.Control(0,0,false);
    static PeriodicComboClock.Scheduled e(double t,long seq,PeriodicComboClock.Kind k,boolean a){return new PeriodicComboClock.Scheduled(Math.round(t*1e6),seq,k,a);}
    static PeriodicComboFightModel.Packet packet(int kind,double raw,double ready,long seq){
        var p=new PeriodicComboFightModel.Packet(kind,raw,6,4,.2,.3,false,seq);p.ready=ready;p.vz=-.1;return p;
    }
    static class Reader implements PeriodicComboClock.Policy {
        final List<PeriodicComboClock.PacketObservation> received=new ArrayList<>();
        final List<PeriodicComboClock.Observation> inputs=new ArrayList<>();int admissions;
        public PeriodicComboClock.Control input(PeriodicComboClock.Observation o){inputs.add(o);return new PeriodicComboClock.Control(0,0,false);}
        public void received(PeriodicComboClock.PacketObservation p){received.add(p);}
    }
    static class RecordingTiming implements PeriodicComboClock.CuePublicationTiming {
        final List<List<Long>> calls=new ArrayList<>();
        public long publicationNanos(int route,long raw,long ready,long drain){
            calls.add(List.of((long)route,raw,ready,drain));
            return drain+(raw==1_500_000?3_000_000:2_000_000);
        }
        public RecordingTiming deepCopy(){var c=new RecordingTiming();c.calls.addAll(calls);return c;}
    }
    static PeriodicComboClock clock(PeriodicComboFightModel.State s,PeriodicComboClock.CuePublicationTiming timing,
                                   PeriodicComboClock.Policy a,PeriodicComboClock.Policy b,List<PeriodicComboClock.Scheduled> tape){
        var cfg=new PeriodicComboFightModel.Config();cfg.ownRtt=cfg.otherRtt=0;
        var t=new PeriodicComboClock.Timing();t.inputMode=PeriodicComboClock.InputMode.NATIVE_QUEUED;t.explicitPolls=true;t.cuePublicationTiming=timing;
        return new PeriodicComboClock(s,cfg,t,a,b,tape);
    }
    static PeriodicComboFightModel.State batch(){
        var s=new PeriodicComboFightModel.State(6);s.ab.lastHurtTime=-11;s.a.lastHurtTime=-12;
        s.packets.add(packet(5,1,1,0));
        s.packets.add(packet(12,1.1,2.1,1));
        s.packets.add(packet(8,1.2,2.2,2));
        var p=packet(5,1.3,2.3,3);p.x=8;p.y=4.2;s.packets.add(p);
        p=packet(5,1.4,2.4,4);p.x=9;p.y=4.4;s.packets.add(p);
        s.packets.add(packet(14,1.5,2.5,5));
        s.packets.add(packet(12,8,8,6));
        return s;
    }
    static List<PeriodicComboClock.Scheduled> tape(){return List.of(
        e(3,0,PeriodicComboClock.Kind.CLIENT_DRAIN,true),
        e(4,1,PeriodicComboClock.Kind.TICK_HEAD,false),
        e(4.5,2,PeriodicComboClock.Kind.SERVER_END,true),
        e(6,3,PeriodicComboClock.Kind.TICK_HEAD,true),
        e(10,4,PeriodicComboClock.Kind.CLIENT_DRAIN,true),
        e(12,5,PeriodicComboClock.Kind.TICK_HEAD,true),
        e(13,6,PeriodicComboClock.Kind.SERVER_END,true));}

    public static void main(String[] args)throws Exception {
        var timing=new RecordingTiming();var a=new Reader();var b=new Reader();var s=batch();
        var c=clock(s,timing,a,b,tape());var audit=new ArrayList<String>();c.setAudit((t,k,q)->audit.add(t+":"+k));
        c.runUntil(3.5);
        check(s.ab.lastHurtTime==-11&&s.a.lastHurtTime==-12,"Old target/own cue retained after selected drain");
        check(a.received.size()==1&&a.received.get(0).kind()==PeriodicComboClock.ReceivedKind.TARGET_POSITION,"Earlier callback visible; later FIFO callbacks withheld");
        check(s.a.q.vx()==.2&&s.a.q.vy()==.3&&s.a.q.vz()==-.1,"S12 physical effect remains at drain despite delayed callback");
        check(s.ab.gx==9&&s.ab.gy==4.4,"Later position effects are not removed when callback waits");
        check(timing.calls.equals(List.of(List.of(2L,1_100_000L,2_100_000L,3_000_000L),List.of(2L,1_500_000L,2_500_000L,3_000_000L))),"Only selected generated S19s query timing with route/raw/ready/actual drain");
        var fa=new Reader();var fb=new Reader();var fork=c.fork(fa,fb);
        check(fork.timing.cuePublicationTiming!=timing,"Mutable timing history is fork-copied");
        PeriodicComboForkCheck.equal(c,fork,"pending publication fork");
        c.runUntil(5);
        check(s.ab.lastHurtTime==5&&s.a.lastHurtTime==-12,"Target publication occurs at5 while own cue remains old until6");
        check(b.inputs.size()==1,"Other client's HEAD proceeds while A publication pending");
        check(a.received.size()==5,"Equal-time FIFO callbacks all publish before later own cue");
        check(a.received.get(1).arrivedAt()==1.1&&a.received.get(1).handledAt()==5,"Cue preserves raw receipt and actual publication time");
        check(a.received.get(2).kind()==PeriodicComboClock.ReceivedKind.OWN_VELOCITY&&a.received.get(2).vx()==.2,"Deferred velocity callback retains payload");
        check(a.received.get(3).goalX()==8&&a.received.get(4).goalX()==9,"Deferred position callbacks retain per-packet snapshots, not final view");
        check(fork.state.ab.lastHurtTime==-11&&fa.received.isEmpty(),"Original publication does not affect pending fork");
        c.runUntil(6);
        check(a.inputs.size()==1&&a.inputs.get(0).lastTargetHurtTime()==5&&a.inputs.get(0).lastOwnHurtTime()==6,"Same-time publication precedes actual input");
        check(audit.indexOf("6.0:CUE_PUBLICATIONA")<audit.indexOf("6.0:POLICYA"),"Explicit audit records publication before same-time HEAD");
        c.runUntil(13);
        check(timing.calls.size()==3&&((RecordingTiming)fork.timing.cuePublicationTiming).calls.size()==2,"Future timing calls cannot mutate sibling history");
        fork.runUntil(13);
        PeriodicComboForkCheck.equal(c.state,fork.state,"continued publication state");
        check(a.received.subList(1,a.received.size()).equals(fa.received),"Fork retains every in-flight FIFO callback exactly once");
        check(a.inputs.equals(fa.inputs)&&b.inputs.equals(fb.inputs),"Fork observes identical later inputs and cues");

        // Same extension routes both target and own S19s for B as well.
        s=new PeriodicComboFightModel.State(6);s.ba.lastHurtTime=-21;s.b.lastHurtTime=-22;
        s.packets.add(packet(13,1,1,0));s.packets.add(packet(15,1.5,1.5,1));
        timing=new RecordingTiming();a=new Reader();b=new Reader();
        c=clock(s,timing,a,b,List.of(e(3,0,PeriodicComboClock.Kind.CLIENT_DRAIN,false),e(6,1,PeriodicComboClock.Kind.TICK_HEAD,false)));
        c.runUntil(4);check(s.ba.lastHurtTime==-21&&s.b.lastHurtTime==-22,"B old cues are not exposed early");
        c.runUntil(6);check(s.ba.lastHurtTime==5&&s.b.lastHurtTime==6&&timing.calls.get(0).get(0)==3L,"B receives route3 own/target publications");

        // No policy or packet effect may run through an unfinished native handler.
        for(var k:List.of(PeriodicComboClock.Kind.FRAME_ENTRY,PeriodicComboClock.Kind.CLIENT_DRAIN,
                PeriodicComboClock.Kind.TICK_HEAD,PeriodicComboClock.Kind.CONSUME,PeriodicComboClock.Kind.MOVE,
                PeriodicComboClock.Kind.INTERP,PeriodicComboClock.Kind.FLUSH,PeriodicComboClock.Kind.POLL_FRAME,PeriodicComboClock.Kind.POLL_NATIVE)){
            var initial=new PeriodicComboFightModel.State(6);initial.packets.add(packet(12,1,1,0));var observer=new Reader();
            var bad=clock(initial,(route,raw,ready,drain)->10_000_000,observer,IDLE,
                List.of(e(3,0,PeriodicComboClock.Kind.CLIENT_DRAIN,true),e(4,1,k,true),e(11,2,PeriodicComboClock.Kind.SERVER_END,true)));
            bad.timing.causalBackpressure=true;
            reject(()->bad.runUntil(11),"UNSUPPORTED_CUE_SERVICE_OVERLAP");
            check(observer.inputs.isEmpty()&&observer.received.isEmpty()&&initial.ab.lastHurtTime==Double.NEGATIVE_INFINITY,"Overlap rejects before observation/cue mutation: "+k);
        }
        for(long invalid:new long[]{2_999_999,12_000_000}){
            var initial=new PeriodicComboFightModel.State(6);initial.packets.add(packet(12,1,1,0));
            var bad=clock(initial,(route,raw,ready,drain)->invalid,IDLE,IDLE,
                List.of(e(3,0,PeriodicComboClock.Kind.CLIENT_DRAIN,true),e(11,1,PeriodicComboClock.Kind.SERVER_END,true)));
            reject(()->bad.runUntil(3),"UNSUPPORTED_CUE_TIME");
        }
        s=new PeriodicComboFightModel.State(6);s.packets.add(packet(12,1,1,0));s.packets.add(packet(14,2,2,1));
        var reversed=clock(s,(route,raw,ready,drain)->raw==1_000_000?8_000_000:7_000_000,IDLE,IDLE,
            List.of(e(3,0,PeriodicComboClock.Kind.CLIENT_DRAIN,true),e(10,1,PeriodicComboClock.Kind.SERVER_END,true)));
        reject(()->reversed.runUntil(3),"UNSUPPORTED_CUE_ORDER");

        // Default and explicitly immediate extension agree bit-for-bit on state,
        // callbacks and input observations, without introducing publication events.
        var legacyA=new Reader();var immediateA=new Reader();
        var legacy=clock(batch(),null,legacyA,IDLE,tape());
        var immediate=clock(batch(),(route,raw,ready,drain)->drain,immediateA,IDLE,tape());
        var legacyAudit=new ArrayList<String>();var immediateAudit=new ArrayList<String>();
        legacy.setAudit((t,k,q)->legacyAudit.add(t+":"+k));immediate.setAudit((t,k,q)->immediateAudit.add(t+":"+k));
        legacy.runUntil(13);immediate.runUntil(13);
        PeriodicComboForkCheck.equal(legacy.state,immediate.state,"zero-delay legacy parity");
        check(legacyA.received.equals(immediateA.received)&&legacyA.inputs.equals(immediateA.inputs),"Immediate mode preserves exact legacy observations");
        check(legacyAudit.equals(immediateAudit),"Immediate mode adds no delayed-publication audit events");

        // A readiness-only gate must see publication time, not receipt/drain time.
        class Gate extends Reader {
            public PeriodicComboClock.Control input(PeriodicComboClock.Observation o){super.input(o);return new PeriodicComboClock.Control(0,0,true);}
            public boolean allowInjection(PeriodicComboClock.Observation o,String source){return o.time()-o.lastTargetHurtTime()>=4;}
            public boolean injected(PeriodicComboClock.Observation o,double t){admissions++;return true;}
        }
        var gateTape=List.of(e(3,0,PeriodicComboClock.Kind.CLIENT_DRAIN,true),e(6,1,PeriodicComboClock.Kind.TICK_HEAD,true),
                e(7,2,PeriodicComboClock.Kind.POLL_FRAME,true),e(9,3,PeriodicComboClock.Kind.POLL_FRAME,true));
        var g0=new Gate();var g1=new Gate();var s0=new PeriodicComboFightModel.State(6);s0.packets.add(packet(12,1,2,0));
        var c0=clock(s0,null,g0,IDLE,gateTape);var c1=clock(s0.deepCopy(),(route,raw,ready,drain)->5_000_000,g1,IDLE,gateTape);
        c0.runUntil(7);c1.runUntil(7);check(g0.admissions==1&&g1.admissions==0,"Publication delay changes elapsed-cue admission without altering policy formula");
        c1.runUntil(9);check(g1.admissions==1,"Deferred gate opens at correct publication-relative threshold");
        var noTape=new PeriodicComboClock.Timing();noTape.cuePublicationTiming=(route,raw,ready,drain)->drain;
        reject(()->new PeriodicComboClock(new PeriodicComboFightModel.State(6),new PeriodicComboFightModel.Config(),noTape,IDLE,IDLE),"requires an event tape");
        System.out.println("PeriodicComboCuePublicationCheck passed: "+checks+" assertions; semantic comparisons="+PeriodicComboForkCheck.comparisons);
    }
}
