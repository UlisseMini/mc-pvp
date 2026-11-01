import lab.Json;
import java.nio.file.*;
import java.util.*;

/** Retrospective same-seed causal comparison of a saved prefix and plain W-tap. No search. */
public final class ComboFeasibilityBaselineTrace {
    static Map<String,Object> run(String name,int delay,List<PeriodicComboClock.Control> prefix,double seed,double seconds){
        var c=new PeriodicComboFightModel.Config();c.ownRtt=0;c.otherRtt=120;c.defenderReach=3;c.forceContacts=false;var t=new PeriodicComboClock.Timing();t.uplinkA=t.uplinkB=t.downlinkA=t.downlinkB=.4;
        var baseline=new ComboBaselineCheck.Baseline(delay);var opening=new ComboCycleSearch.Controller(new ComboCycleSearch.Plan(20,10,4,0,2,0),10);
        var clock=new PeriodicComboClock(new PeriodicComboFightModel.State(4093,0,4099,0),c,t,o->{baseline.input(o);return opening.input(o);},ComboOracleSearch.OPPONENT);clock.runUntil(seed-.001);
        int[] index={0};clock=clock.fork(o->{var fallback=baseline.input(o);return prefix!=null&&index[0]<prefix.size()?prefix.get(index[0]++):fallback;},ComboOracleSearch.OPPONENT);
        List<Map<String,Object>> hits=new ArrayList<>(),inputs=new ArrayList<>();int[] last={clock.state.hits,clock.state.acceptedB,clock.state.sprintHits};double[] firstReturn={Double.NaN};
        clock.setAudit((time,event,s)->{
            if(event.equals("INPUTA"))inputs.add(Map.of("t",time,"range",PeriodicComboFightModel.own(s),"target_height",s.ab.y-4,"own_v",Math.hypot(s.a.q.vx(),s.a.q.vz()),"local_sprint",s.a.sprint,"server_sprint",s.serverSprint,"target_h",s.resistant,"local_contacts",s.a.localContacts));
            if(s.hits>last[0]){Map<String,Object> r=new LinkedHashMap<>();r.put("t",time);r.put("strength",s.sprintHits>last[2]?"S":"N");r.put("launch",List.of(s.lastVx,s.lastVy));r.put("target_true_height",s.b.q.y()-4);r.put("target_render_height",s.ab.y-4);r.put("own_range",PeriodicComboFightModel.own(s));r.put("defender_range",PeriodicComboFightModel.their(s));r.put("own_speed",Math.hypot(s.a.q.vx(),s.a.q.vz()));r.put("fresh_order",s.lastFreshOrderA);r.put("return_order",s.lastAcceptedOrderB);hits.add(r);}
            if(s.acceptedB>last[1]&&Double.isNaN(firstReturn[0]))firstReturn[0]=time;
            last[0]=s.hits;last[1]=s.acceptedB;last[2]=s.sprintHits;
        });
        String stop="duration";try{clock.runUntil(seed+seconds*1000);}catch(IllegalStateException e){stop=e.getMessage();}
        Map<String,Object> out=new LinkedHashMap<>();out.put("policy",name);out.put("first_return_ms",firstReturn[0]);out.put("stop",stop);out.put("hits",hits);out.put("inputs",inputs);return out;
    }
    public static void main(String[] args)throws Exception{List<String> lines=Files.readAllLines(Path.of(args[0]));String[] h=lines.get(0).split("\\t");double seed=Double.parseDouble(h[0]);List<PeriodicComboClock.Control> prefix=new ArrayList<>();for(String line:lines.subList(1,lines.size())){String[] p=line.split("\\t");prefix.add(new PeriodicComboClock.Control(Integer.parseInt(p[0]),Integer.parseInt(p[1])*.98f,p[2].equals("1")));}
        System.out.println(Json.encode(Map.of("scope","Retrospective12s matched same-seed comparison, fullreach3, vanilla,0/120RTT,phases0/10/25,openingburst2; prefix followed by plain zero-delay W-tap. Not a new controller search or general superiority claim.","seed_ms",seed,"results",List.of(run("saved-oracle-prefix_then_wtap",0,prefix,seed,12),run("plain-wtap-zero-delay",0,null,seed,12),run("hold-w",-1,null,seed,12)))));
    }
}
