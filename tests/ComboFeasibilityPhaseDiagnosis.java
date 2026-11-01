import lab.Json;
import java.nio.file.*;
import java.util.*;

/** Saved-prefix/W-tap replay only: inspect seed queues and causal horizons, no search. */
public final class ComboFeasibilityPhaseDiagnosis {
    static Map<String,Object> snapshot(PeriodicComboFightModel.State s){
        Map<String,Object> r=new LinkedHashMap<>();r.put("own_range",PeriodicComboFightModel.own(s));r.put("defender_range",PeriodicComboFightModel.their(s));r.put("a",List.of(s.a.q.x(),s.a.q.y(),s.a.q.z(),s.a.q.vx(),s.a.q.vy(),s.a.q.vz()));r.put("b",List.of(s.b.q.x(),s.b.q.y(),s.b.q.z(),s.b.q.vx(),s.b.q.vy(),s.b.q.vz()));r.put("a_view",List.of(s.ab.x,s.ab.y,s.ab.z,s.ab.gx,s.ab.gy,s.ab.gz,s.ab.left));r.put("b_view",List.of(s.ba.x,s.ba.y,s.ba.z,s.ba.gx,s.ba.gy,s.ba.gz,s.ba.left));r.put("hurt",List.of(s.resistant,s.ownResistant));r.put("sprint",List.of(s.a.sprint,s.serverSprint));r.put("counts",List.of(s.hits,s.acceptedB));r.put("last_launch",List.of(s.lastVx,s.lastVy));
        List<Object> packets=new ArrayList<>();for(var p:s.packets)packets.add(Map.of("kind",p.kind,"due",p.due,"xyz",List.of(p.x,p.y,p.z),"velocity",List.of(p.vx,p.vy,p.vz),"flag",p.flag,"sequence",p.sequence));r.put("packets",packets);return r;
    }
    static Map<String,Object> run(double seed,double bp,double sp,List<PeriodicComboClock.Control> prefix){
        var c=new PeriodicComboFightModel.Config();c.ownRtt=0;c.otherRtt=120;c.defenderReach=3;c.forceContacts=false;var t=new PeriodicComboClock.Timing();t.bTickPhase=bp;t.serverPhase=sp;t.uplinkA=t.uplinkB=t.downlinkA=t.downlinkB=.4;PeriodicComboFightModel.defenderReach=3;
        var baseline=new ComboBaselineCheck.Baseline(0);var opening=new ComboCycleSearch.Controller(new ComboCycleSearch.Plan(20,10,4,0,2,0),10);
        var clock=new PeriodicComboClock(new PeriodicComboFightModel.State(4093,0,4099,0),c,t,o->{baseline.input(o);return opening.input(o);},ComboOracleSearch.OPPONENT);
        List<Object> acquisition=new ArrayList<>();int[] acquired={0,0};clock.setAudit((time,event,s)->{if(s.hits!=acquired[0]||s.acceptedB!=acquired[1]){var row=snapshot(s);row.put("t",time);acquisition.add(row);acquired[0]=s.hits;acquired[1]=s.acceptedB;}});clock.runUntil(seed-.001);
        var initial=snapshot(clock.state);int[] index={0};List<Object> controls=new ArrayList<>();clock=clock.fork(o->{var fallback=baseline.input(o);var chosen=prefix!=null&&index[0]<prefix.size()?prefix.get(index[0]):fallback;index[0]++;controls.add(Map.of("t",o.time(),"key",chosen.key(),"strafe",chosen.strafe(),"click",chosen.click(),"local_cue",o.lastTargetHurtTime()));return chosen;},ComboOracleSearch.OPPONENT);
        List<Object> events=new ArrayList<>(),forecasts=new ArrayList<>();int[] counts={clock.state.hits,clock.state.acceptedB};long[] contacts={clock.state.b.localContacts};boolean[] denial={false};
        clock.setAudit((time,event,s)->{boolean fresh=s.hits>counts[0],returned=s.acceptedB>counts[1],contact=s.b.localContacts>contacts[0],firstDenial=!denial[0]&&PeriodicComboFightModel.their(s)<=3;if(fresh||returned||contact||firstDenial){var row=snapshot(s);row.put("t",time);row.put("event",event);row.put("fresh",fresh);row.put("accepted_return",returned);row.put("b_contact",contact);row.put("first_denial",firstDenial);events.add(row);}counts[0]=s.hits;counts[1]=s.acceptedB;contacts[0]=s.b.localContacts;denial[0]|=firstDenial;});
        int initialReturns=clock.state.acceptedB;
        for(int step=0;step<40&&clock.state.acceptedB==initialReturns;step++){
            var f=ComboFeasibilitySearch.committed(clock,3);var row=new LinkedHashMap<String,Object>();row.put("t",clock.time());row.put("cutoff",f.cutoff());row.put("min_margin",f.margin());row.put("first_failure",f.firstFailure());row.put("separated",f.separated());forecasts.add(row);clock.runUntil(seed+(step+1)*50-.001);
        }
        return Map.of("policy",prefix==null?"plain_wtap0":"saved_prefix_then_wtap0","seed",initial,"acquisition",acquisition,"events",events,"forecasts",forecasts,"controls",controls);
    }
    public static void main(String[] args)throws Exception{List<String> lines=Files.readAllLines(Path.of(args[0]));String[] h=lines.get(0).split("\\t");double seed=Double.parseDouble(h[0]),bp=Double.parseDouble(h[1]),sp=Double.parseDouble(h[2]);List<PeriodicComboClock.Control> prefix=new ArrayList<>();for(String line:lines.subList(1,lines.size())){String[] p=line.split("\\t");prefix.add(new PeriodicComboClock.Control(Integer.parseInt(p[0]),Integer.parseInt(p[1])*.98f,p[2].equals("1")));}System.out.println(Json.encode(Map.of("seed_ms",seed,"phases",List.of(0,bp,sp),"results",List.of(run(seed,bp,sp,prefix),run(seed,bp,sp,null)))));}
}
