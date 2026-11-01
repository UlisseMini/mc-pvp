import lab.Json;
import java.nio.file.*;
import java.util.*;

/** Matched ordinary hold-W / visible-cue W-tap controls on the canonical plant.
 * W-tap releases W and Ctrl for50ms after its own observed target hurt cue.
 * The150ms variant preserves the historical combo-wtap20 default separately.
 */
public final class ComboBaselineCheck {
    static final class Baseline implements PeriodicComboClock.Policy {
        final int delay;double rawCue=Double.NEGATIVE_INFINITY,pending=Double.NEGATIVE_INFINITY,until=Double.NEGATIVE_INFINITY;
        Baseline(int delay){this.delay=delay;}
        public PeriodicComboClock.Control input(PeriodicComboClock.Observation o){
            if(delay<0)return new PeriodicComboClock.Control(2,0,true);
            if(o.lastTargetHurtTime()>rawCue){rawCue=o.lastTargetHurtTime();pending=o.time()+delay;}
            if(Double.isFinite(pending)&&o.time()>=pending){until=o.time()+50;pending=Double.NEGATIVE_INFINITY;}
            return new PeriodicComboClock.Control(o.time()<until?0:2,0,true);
        }
    }
    static Map<String,Object> trial(String name,int delay,double reach,double h,double v,int ticks,double seconds,double ping,double bPhase,double serverPhase,double seed,int openingBurst){
        return trial(name,delay,reach,h,v,ticks,seconds,ping,bPhase,serverPhase,seed,openingBurst,null);
    }
    static Map<String,Object> trial(String name,int delay,double reach,double h,double v,int ticks,double seconds,double ping,double bPhase,double serverPhase,double seed,int openingBurst,List<PeriodicComboClock.Control> prefix){
        var c=new PeriodicComboFightModel.Config();c.ownRtt=0;c.otherRtt=ping;c.defenderReach=reach;c.kbScale=h;c.verticalScale=v;c.hitTicks=ticks;c.forceContacts=false;
        var timing=new PeriodicComboClock.Timing();timing.bTickPhase=bPhase;timing.serverPhase=serverPhase;
        timing.uplinkA=timing.uplinkB=timing.downlinkA=timing.downlinkB=.4;
        PeriodicComboFightModel.defenderReach=reach;
        var state=new PeriodicComboFightModel.State(4093,0,4099,0);var baseline=new Baseline(delay);
        var plan=new ComboCycleSearch.Plan(2*ticks,ticks,Math.min(4,2*ticks-1),0,openingBurst,0);
        var opening=new ComboCycleSearch.Controller(plan,ticks);
        var clock=new PeriodicComboClock(state,c,timing,seed>0?o->{baseline.input(o);return opening.input(o);}:baseline,ComboOracleSearch.OPPONENT);
        if(seed>0){
            clock.runUntil(seed-.001);
            int[] at={0};PeriodicComboClock.Policy policy=prefix==null?baseline:o->{var fallback=baseline.input(o);return at[0]<prefix.size()?prefix.get(at[0]++):fallback;};
            clock=clock.fork(policy,ComboOracleSearch.OPPONENT);state=clock.state;
        }
        var score=new ComboCycleSearch.Score(c,timing);score.a=state.hits;score.b=state.incomingHits;score.bAccepted=state.acceptedB;score.priorH=state.resistant;
        int initialA=state.hits,initialB=state.incomingHits;clock.setAudit(score);
        try{clock.runUntil(seed+seconds*1000);}catch(IllegalStateException e){score.stop=e.getMessage();}
        var out=score.result(plan,state,seed+seconds*1000,clock.time());out.remove("plan");out.put("policy",name);
        out.put("fresh_hits",List.of(state.hits-initialA,state.incomingHits-initialB));out.put("reaction_ms",Math.max(0,delay));
        out.put("measurement_start_ms",seed);out.put("duration_seconds",seconds);out.put("opening_burst",openingBurst);
        out.put("start_mode",seed>0?"same_oracle_seed_with_shadow_baseline_cue_history":"fresh_start");
        if(prefix!=null){out.put("oracle_prefix_ticks",prefix.size());out.put("after_prefix","plain zero-delay W-tap");}
        return out;
    }
    public static void main(String[]args)throws Exception{
        if(args.length<6)throw new IllegalArgumentException("out reach h v hitTicks seconds [opponentRTT120] [bPhase10] [serverPhase25] [seedMs0] [openingBurst2]");
        Path out=Path.of(args[0]);if(Files.exists(out))throw new IllegalArgumentException("Refuse overwrite");
        double reach=Double.parseDouble(args[1]),h=Double.parseDouble(args[2]),v=Double.parseDouble(args[3]),seconds=Double.parseDouble(args[5]);int ticks=Integer.parseInt(args[4]);
        double ping=args.length>6?Double.parseDouble(args[6]):120,b=args.length>7?Double.parseDouble(args[7]):10,server=args.length>8?Double.parseDouble(args[8]):25,seed=args.length>9?Double.parseDouble(args[9]):0;int burst=args.length>10?Integer.parseInt(args[10]):2;
        List<Map<String,Object>> rows=new ArrayList<>();
        rows.add(trial("hold-w",-1,reach,h,v,ticks,seconds,ping,b,server,seed,burst));
        rows.add(trial("wtap-zero-delay",0,reach,h,v,ticks,seconds,ping,b,server,seed,burst));
        rows.add(trial("wtap-historical-150ms",150,reach,h,v,ticks,seconds,ping,b,server,seed,burst));
        Files.writeString(out,Json.encode(Map.of("scope","Matched20CPS baselines; own-view hurt cues only; target starts20CPS after2s opening handicap; explicit phase/schedule assumptions. Seeded mode replays identical acquisition and shadows cue history before changing policy.","combat",List.of(h,v,ticks),"reach",reach,"rtt",List.of(0,ping),"phases_ms",List.of(0,b,server),"results",rows))+"\n");
        System.out.println(Json.encode(Map.of("file",out.toString(),"results",rows)));
    }
}
