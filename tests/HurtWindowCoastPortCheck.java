import java.util.*;
import java.nio.file.*;
import lab.HurtWindowCoastController;
import lab.Json;

/** Same-event port regression over the full qualified model trajectories. */
public final class HurtWindowCoastPortCheck {
    static int checks;
    static void check(boolean b,String why){checks++;if(!b)throw new AssertionError(why);}
    static final class Port implements PeriodicComboClock.Policy {
        double maxExcursion;
        final HurtWindowCoastPolicy frozen=new HurtWindowCoastPolicy(150,8);
        final HurtWindowCoastController live=new HurtWindowCoastController();
        public PeriodicComboClock.Control input(PeriodicComboClock.Observation o){
            maxExcursion=Math.max(maxExcursion,Math.max(Math.abs(o.own().x()-4096),Math.abs(o.own().z())));
            var a=frozen.input(o);var b=live.input(o.tick(),o.time(),o.nearest(),o.lastTargetHurtTime());
            check(a.key()==b.key&&a.strafe()==b.strafe&&a.click()==b.click,"native port movement equality");return a;
        }
        public boolean allowInjection(PeriodicComboClock.Observation o,String source){
            boolean a=frozen.allowInjection(o,source),b=live.allowInjection(o.time(),source.equals("native_tick"),o.lastTargetHurtTime(),150,8);
            check(a==b,"native port admission equality");return a;
        }
        public void consumed(PeriodicComboClock.Observation o,long id){frozen.consumed(o,id);}
    }
    public static void main(String[] args)throws Exception{
        var rows=new ArrayList<Object>();
        for(int cap:new int[]{50,51})for(double[] phase:ArrivalPhaseFollowup.PHASES){
            var cfg=new PeriodicComboFightModel.Config();cfg.ownRtt=150;cfg.otherRtt=0;cfg.kbScale=1.1;cfg.verticalScale=1;cfg.hitTicks=8;cfg.forceContacts=false;cfg.defenderReach=3;PeriodicComboFightModel.defenderReach=3;
            var timing=new PeriodicComboClock.Timing();timing.inputMode=PeriodicComboClock.InputMode.NATIVE_QUEUED;timing.clickIntervalNanos=cap*1_000_000L;timing.bTickPhase=phase[0];timing.serverPhase=phase[1];timing.uplinkA=timing.uplinkB=timing.downlinkA=timing.downlinkB=.4;
            var port=new Port();var wt=new ComboBaselineCheck.Baseline(0);PeriodicComboClock.Policy opp=o->new PeriodicComboClock.Control(2,0,o.time()>=4000);
            PeriodicComboClock.Policy opening=new PeriodicComboClock.Policy(){
                public PeriodicComboClock.Control input(PeriodicComboClock.Observation o){wt.input(o);return port.input(o);}
                public boolean allowInjection(PeriodicComboClock.Observation o,String s){return port.allowInjection(o,s);}
                public void consumed(PeriodicComboClock.Observation o,long id){port.consumed(o,id);}
            };
            var source=new PeriodicComboClock(new PeriodicComboFightModel.State(4093,0,4099,0),cfg,timing,opening,opp);source.runUntil(3999.999);
            var c=source.fork(port,opp);var w=source.fork(wt,opp);
            var cr=ArrivalPhaseSweep.run(c,null);var wr=ArrivalPhaseSweep.run(w,null);
            check((int)cr.get("fresh")==150&&Boolean.TRUE.equals(cr.get("zero60")),"qualified result preserved");
            rows.add(Map.of("cap_ms",cap,"phases",List.of(0,phase[0],phase[1]),"candidate",cr,"Wtap",wr,"max_own_axis_excursion",port.maxExcursion));
        }
        var out=Map.of("checks",checks,"rows",rows);
        Files.writeString(Path.of(args[0]),Json.encode(out)+"\n");System.out.println("Passed "+checks+" same-event port checks across14 matched phase/cap cells");
    }
}
