import java.util.ArrayList;
import java.util.List;

/** Component checks only: generated timing bundles, queue FIFO and cue scheduling. */
public final class PeriodicComboPacketTimingPlanCheck {
    static int checks;
    static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
    static void bits(double a,double b,String why){check(Double.doubleToLongBits(a)==Double.doubleToLongBits(b),why);}
    static void reject(Runnable r,String text){checks++;try{r.run();}catch(IllegalArgumentException|IllegalStateException e){if(e.getMessage().contains(text))return;throw new AssertionError(e);}throw new AssertionError("Missing rejection "+text);}
    static final PeriodicComboFightModel.TimingSemantic TARGET=PeriodicComboFightModel.TimingSemantic.TARGET_HURT,
        OWN=PeriodicComboFightModel.TimingSemantic.OWN_HURT,VELOCITY=PeriodicComboFightModel.TimingSemantic.OWN_VELOCITY;
    static class Legacy implements PeriodicComboFightModel.PacketTiming {
        int calls;
        public double arrival(int route,double send,double half){calls++;return send+half+1;}
        public Legacy deepCopy(){var x=new Legacy();x.calls=calls;return x;}
    }
    static final class Bundle extends Legacy {
        int plans,tupleSelections;long service=2_000_000;
        @Override public PeriodicComboFightModel.PacketTimingPlan plan(int route,double send,double half,PeriodicComboFightModel.TimingSemantic semantic){
            plans++;if(tupleSelections==0)tupleSelections++;
            double raw=send+half+(semantic==TARGET?1:semantic==OWN?1.1:1.2);
            return new PeriodicComboFightModel.PacketTimingPlan(raw,raw+2,semantic==TARGET?service:null);
        }
        @Override public Bundle deepCopy(){var b=new Bundle();b.calls=calls;b.plans=plans;b.tupleSelections=tupleSelections;b.service=service;return b;}
    }
    static final class Readiness implements PeriodicComboFightModel.PacketReadiness {
        int calls;
        public double ready(int route,int protocol,double raw){calls++;return raw+10;}
        public Readiness deepCopy(){var r=new Readiness();r.calls=calls;return r;}
    }
    static final class Mixed extends Legacy {
        public PeriodicComboFightModel.PacketTimingPlan plan(int route,double send,double half,PeriodicComboFightModel.TimingSemantic semantic){
            return new PeriodicComboFightModel.PacketTimingPlan(send+half,send==1?10.0:null,null);
        }
        public Mixed deepCopy(){return new Mixed();}
    }
    static final class Reader implements PeriodicComboClock.Policy {
        final List<PeriodicComboClock.PacketObservation> packets=new ArrayList<>();
        final List<PeriodicComboClock.Observation> inputs=new ArrayList<>();
        public PeriodicComboClock.Control input(PeriodicComboClock.Observation o){inputs.add(o);return new PeriodicComboClock.Control(0,0,false);}
        public void received(PeriodicComboClock.PacketObservation p){packets.add(p);}
    }
    static PeriodicComboClock.Scheduled e(double ms,long seq,PeriodicComboClock.Kind kind,boolean a){return new PeriodicComboClock.Scheduled(Math.round(ms*1e6),seq,kind,a);}
    static PeriodicComboClock clock(double aDrain,double aHead,boolean enable,Reader a,Reader b,PeriodicComboClock.CuePublicationTiming legacyCue){
        var s=new PeriodicComboFightModel.State(6);s.ab.lastHurtTime=-11;
        s.packetTiming=new Bundle();s.packetReadiness=new Readiness();
        var cfg=new PeriodicComboFightModel.Config();cfg.ownRtt=cfg.otherRtt=0;
        var t=new PeriodicComboClock.Timing();t.inputMode=PeriodicComboClock.InputMode.NATIVE_QUEUED;t.explicitPolls=true;
        t.packetCueDelays=enable;t.cuePublicationTiming=legacyCue;
        var c=new PeriodicComboClock(s,cfg,t,a,b,List.of(e(2,0,PeriodicComboClock.Kind.CLIENT_DRAIN,true),
            e(4,1,PeriodicComboClock.Kind.CLIENT_DRAIN,false),e(aDrain,2,PeriodicComboClock.Kind.CLIENT_DRAIN,true),
            e(aHead,3,PeriodicComboClock.Kind.TICK_HEAD,true),e(12,4,PeriodicComboClock.Kind.SERVER_END,true)));
        s.add(12,0,2,0,0,0,false);s.add(15,0,2,0,0,0,false);s.add3(4,0,0,0,0,.4,.3,.2,false);
        return c;
    }
    static void bundleAndFork()throws Exception{
        var a=new Reader();var b=new Reader();var c=clock(5,7,true,a,b,null);
        var sampler=(Bundle)c.state.packetTiming;var readiness=(Readiness)c.state.packetReadiness;
        check(sampler.plans==3&&sampler.tupleSelections==1&&sampler.calls==0,"Plan is the only timing query; triplet shares one tuple");
        check(readiness.calls==0,"Explicit ready bypasses old readiness hook");
        c.runUntil(2.5);check(c.state.ab.lastHurtTime==-11,"Receipt alone cannot publish cue");
        var fa=new Reader();var fb=new Reader();var fork=c.fork(fa,fb);
        check(QuietFourWordAcquisitionCheck.digest(c).equals(QuietFourWordAcquisitionCheck.digest(fork)),"Full pre-drain bundle fork digest exact");
        sampler.service=100_000_000; // Cannot change already stored per-packet service.
        check(((Bundle)fork.state.packetTiming).service==2_000_000,"Sampler fork independent");
        c.runUntil(5.5);fork.runUntil(5.5);
        check(c.state.ab.lastHurtTime==-11&&a.packets.isEmpty(),"Selected cue remains old until actual publication");
        check(c.state.b.lastHurtTime==4&&c.state.b.q.vx()==.4,"Other route immediate S19/S12 effects retained");
        check(b.packets.size()==2,"Other route callbacks available independently");
        var pending=c.fork(new Reader(),new Reader());
        check(QuietFourWordAcquisitionCheck.digest(c).equals(QuietFourWordAcquisitionCheck.digest(pending)),"Full pending-publication fork digest exact");
        c.runUntil(7);fork.runUntil(7);pending.runUntil(7);
        check(c.state.ab.lastHurtTime==7&&fork.state.ab.lastHurtTime==7&&pending.state.ab.lastHurtTime==7,"Immutable delay anchors to selected drain5, publication7");
        check(a.inputs.get(0).lastTargetHurtTime()==7,"Equal-time publication precedes own HEAD");
        check(a.packets.get(0).arrivedAt()==1&&a.packets.get(0).handledAt()==7,"Callback retains raw receipt and publication anchors");
        check(sampler.plans==3&&((Bundle)fork.state.packetTiming).plans==3,"No sampler call on drain/publication");
        check(readiness.calls==0,"No late readiness resampling");
        var later=clock(6,8,true,new Reader(),new Reader(),null);later.runUntil(8);
        check(later.state.ab.lastHurtTime==8,"Same stored delay moves with actual receiving drain");
    }
    static void fallbackAndFifo(){
        var legacy=new Legacy();var p=legacy.plan(2,.25,75,TARGET);
        bits(p.rawReceiptMs(),76.25,"Default plan calls old arrival exactly");
        check(p.readyMs()==null&&p.cueDelayNanos()==null&&legacy.calls==1,"Legacy optional fields remain absent");
        var s=new PeriodicComboFightModel.State(6);s.packetTiming=legacy;s.packetReadiness=new Readiness();
        s.add(12,1,0,0,0,0,false);check(s.packets.get(0).due==2&&s.packets.get(0).ready==12,"Absent ready retains legacy lookup");
        check(s.plannedReadinessRoutes==0&&s.packets.get(0).cueDelayNanos==null,"Legacy-only state does not activate bundled FIFO mode");
        s=new PeriodicComboFightModel.State(6);
        s.packetTiming=new Mixed();
        s.add(12,1,0,0,0,0,false);s.add(12,2,0,0,0,0,false);
        check(s.packets.get(1).ready==10,"Legacy fallback cannot overtake earlier bundled enqueue without old hook");
        var copy=s.deepCopy();copy.add(12,3,0,0,0,0,false);
        check(copy.packets.get(2).ready==10&&s.packets.size()==2,"Mixed FIFO mode/watermark forked");
        final int[] calls={0};var c=clock(5,7,true,new Reader(),new Reader(),(route,raw,ready,drain)->{calls[0]++;return drain;});
        c.runUntil(7);check(calls[0]==1&&c.state.ab.lastHurtTime==7,"Stored cue overrides external hook; null cue uses hook");
    }
    static void rejections(){
        reject(()->new PeriodicComboFightModel.PacketTimingPlan(Double.NaN,null,null),"Invalid");
        reject(()->new PeriodicComboFightModel.PacketTimingPlan(2,1.0,null),"Invalid");
        reject(()->new PeriodicComboFightModel.PacketTimingPlan(1,null,-1L),"Invalid");
        reject(()->clock(5,7,false,new Reader(),new Reader(),null).runUntil(5),"PACKET_CUE_MODE");
        reject(()->clock(5,6,true,new Reader(),new Reader(),null).runUntil(6),"CUE_SERVICE_OVERLAP");
        var t=new PeriodicComboClock.Timing();t.packetCueDelays=true;
        reject(()->new PeriodicComboClock(new PeriodicComboFightModel.State(6),new PeriodicComboFightModel.Config(),t,new Reader(),new Reader()),"event tape");
        var s=new PeriodicComboFightModel.State(6);s.packetTiming=new Legacy(){
            public PeriodicComboFightModel.PacketTimingPlan plan(int r,double send,double h,PeriodicComboFightModel.TimingSemantic k){return new PeriodicComboFightModel.PacketTimingPlan(send,null,1L);}
        };
        reject(()->s.add(4,0,0,0,0,0,false),"only on generated S19");
        var overflow=clock(5,7,true,new Reader(),new Reader(),null);overflow.state.packets.get(0).cueDelayNanos=Long.MAX_VALUE;
        reject(()->overflow.runUntil(5),"overflows");
        var coverage=clock(5,7,true,new Reader(),new Reader(),null);coverage.state.packets.get(0).cueDelayNanos=20_000_000L;
        reject(()->coverage.runUntil(5),"coverage");
    }
    public static void main(String[] args)throws Exception{bundleAndFork();fallbackAndFifo();rejections();
        System.out.println("PeriodicComboPacketTimingPlanCheck passed: "+checks+" checks");}
}
