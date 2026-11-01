import lab.CycleController;
import lab.Json;
import java.nio.file.*;
import java.util.*;

/** One causal change: use visible hurt acknowledgment instead of a fixed click
 * phase. The production key controller is shared with native Forge bots. */
public final class ComboCueSearch {
    static final class Policy implements PeriodicComboClock.Policy {
        final CycleController controller=new CycleController();
        final CycleController.Settings settings;final double rtt;
        long contacts;double cue=Double.NEGATIVE_INFINITY,observed=Double.NEGATIVE_INFINITY;
        Policy(String word,int ticks,int sync,double lead,double rtt){
            Properties p=new Properties();p.setProperty("cycle_word",word);p.setProperty("cycle_hit_ticks",""+ticks);
            p.setProperty("cycle_burst","2");p.setProperty("cycle_sync_cue",sync>0?"1":"0");p.setProperty("cycle_sync_movement",sync>1?"1":"0");
            p.setProperty("cycle_cue_lead_ms",""+lead);settings=CycleController.Settings.from(p);this.rtt=rtt;
        }
        public PeriodicComboClock.Control input(PeriodicComboClock.Observation o){
            if(o.localContacts()>contacts){contacts=o.localContacts();controller.contact(o.tick()-Math.round((o.time()-o.lastContactTime())/50));}
            // The native bot observes hurtTime once at input time, not the exact
            // packet-delivery timestamp. Preserve that sampling uncertainty.
            if(o.lastTargetHurtTime()>cue){cue=o.lastTargetHurtTime();observed=o.time();}
            var d=controller.decide(o.tick(),o.nearest(),settings,o.time(),observed,rtt);
            return new PeriodicComboClock.Control(d.forward?(d.sprint?2:1):0,0,d.click);
        }
    }
    static Map<String,Object> trial(int sync,double lead,int coastStart,int coastLength,double reach,double h,double v,int ticks,double seconds,double bPhase,double serverPhase){
        var p=new ComboCycleSearch.Plan(2*ticks,coastStart,coastLength,0,2,0);
        var c=new PeriodicComboFightModel.Config();c.ownRtt=0;c.otherRtt=120;c.defenderReach=reach;c.kbScale=h;c.verticalScale=v;c.hitTicks=ticks;c.forceContacts=false;
        var timing=new PeriodicComboClock.Timing();timing.bTickPhase=bPhase;timing.serverPhase=serverPhase;
        timing.uplinkA=timing.uplinkB=timing.downlinkA=timing.downlinkB=.4;
        var state=new PeriodicComboFightModel.State(4093,0,4099,0);PeriodicComboFightModel.defenderReach=reach;
        var clock=new PeriodicComboClock(state,c,timing,new Policy(p.word(),ticks,sync,lead,0),o->new PeriodicComboClock.Control(2,0,o.time()>=2000));
        var score=new ComboCycleSearch.Score(c,timing);clock.setAudit(score);clock.runUntil(seconds*1000);
        var out=score.result(p,state,seconds*1000,clock.time());out.put("cue_sync",sync);out.put("lead_ms",lead);
        out.put("b_phase",bPhase);out.put("server_phase",serverPhase);return out;
    }
    public static void main(String[]args)throws Exception{
        Path out=Path.of(args[0]);if(Files.exists(out))throw new IllegalArgumentException("Refuse overwrite");
        double reach=Double.parseDouble(args[1]),seconds=args.length>2?Double.parseDouble(args[2]):30;
        List<Map<String,Object>> rows=new ArrayList<>();
        for(int start:new int[]{9,10})for(double b:new double[]{10,35})for(double server:new double[]{5,25,45})for(int sync:new int[]{0,1,2})for(double lead:new double[]{50,100})
            rows.add(trial(sync,lead,start,4,reach,1,1,10,seconds,b,server));
        rows.sort(Comparator.comparingDouble(ComboCycleSearch::rank).reversed());
        Files.writeString(out,Json.encode(Map.of("scope","Own-view cue synchronization ablation on the shared production controller; hypothetical phase grid, vanilla KB, RTT0/120, 2s target no-click opening handicap. Finite evidence, not proof.","defender_reach",reach,"results",rows))+"\n");
        for(var row:rows.subList(0,Math.min(5,rows.size())))System.out.println(Json.encode(row));
    }
}
