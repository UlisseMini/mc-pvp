import lab.Json;
import java.nio.file.*;
import java.util.*;

/** Reproduce an existing oracle witness; analyze geometry without changing the plant. */
public final class ComboFeasibilityTrace {
    public static void main(String[] args)throws Exception{
        List<String> lines=Files.readAllLines(Path.of(args[0]));String[] header=lines.get(0).split("\\t");double seed=Double.parseDouble(header[0]),reach=Double.parseDouble(header[1]);
        List<PeriodicComboClock.Control> word=new ArrayList<>();for(String line:lines.subList(1,lines.size())){String[] p=line.split("\\t");word.add(new PeriodicComboClock.Control(Integer.parseInt(p[0]),0,p[1].equals("1")));}
        var c=new PeriodicComboFightModel.Config();c.ownRtt=0;c.otherRtt=120;c.defenderReach=reach;c.forceContacts=false;
        var t=new PeriodicComboClock.Timing();t.uplinkA=t.uplinkB=t.downlinkA=t.downlinkB=.4;
        var initial=new ComboCycleSearch.Controller(new ComboCycleSearch.Plan(20,10,4,0,2,0),10);
        var clock=new PeriodicComboClock(new PeriodicComboFightModel.State(4093,0,4099,0),c,t,initial,ComboOracleSearch.OPPONENT);
        clock.runUntil(seed-.001);
        clock=clock.fork(o->{int i=(int)Math.floor((o.time()-seed)/50+1e-8);return word.get(i);},ComboOracleSearch.OPPONENT);
        List<Map<String,Object>> hits=new ArrayList<>(),inputs=new ArrayList<>();int[] prior={clock.state.hits,clock.state.sprintHits};
        clock.setAudit((time,event,s)->{
            if(event.equals("INPUTA")){Map<String,Object> row=geometry(s,time,reach);row.put("hurt",s.resistant);row.put("ray",PeriodicComboFightModel.ray(s.a,s.ab));row.put("native_sprint",s.a.sprint);inputs.add(row);}
            if(s.hits>prior[0]){Map<String,Object> row=geometry(s,time,reach);row.put("fresh",s.hits);row.put("strength",s.sprintHits>prior[1]?"S":"N");row.put("launch_v",List.of(s.lastVx,s.lastVy));row.put("b_server_v",List.of(s.serverB.vx(),s.serverB.vy()));row.put("a_v",s.a.q.vx());hits.add(row);prior[0]=s.hits;prior[1]=s.sprintHits;}
        });
        clock.runUntil(seed+word.size()*50-.001);List<Map<String,Object>> next=new ArrayList<>();
        for(int key=0;key<3;key++)for(boolean click:new boolean[]{false,true}){final int k=key;final boolean hit=click;var fork=clock.fork(o->new PeriodicComboClock.Control(k,0,hit),ComboOracleSearch.OPPONENT);var guard=new ComboOracleSearch.Guard(clock.state.acceptedB,reach);double[] fail={Double.NaN};String[] failEvent={""};fork.setAudit((tt,ee,ss)->{guard.event(tt,ee,ss);if(!guard.valid&&Double.isNaN(fail[0])){fail[0]=tt;failEvent[0]=ee;}});fork.runUntil(clock.time()+50);Map<String,Object> row=geometry(fork.state,fork.time(),reach);row.put("first_failure_ms",fail[0]);row.put("failure_event",failEvent[0]);row.put("key",key);row.put("click",click);row.put("valid",guard.valid);row.put("reason",guard.failure);row.put("h",fork.state.resistant);row.put("new_fresh",fork.state.hits-clock.state.hits);next.add(row);}
        System.out.println(Json.encode(Map.of("scope","Exact replay of an existing finite oracle schedule, followed by its six one-input extensions; not global reachability or an impossibility proof.","hits",hits,"inputs",inputs,"extensions",next)));
    }
    static Map<String,Object> geometry(PeriodicComboFightModel.State s,double time,double reach){
        double da=PeriodicComboFightModel.vertical(s.a.q.y(),s.ab.y),db=PeriodicComboFightModel.vertical(s.b.q.y(),s.ba.y);
        double ra=Math.sqrt(Math.max(0,9-da*da)),rb=Math.sqrt(Math.max(0,reach*reach-db*db));
        double lo=s.ab.x-PeriodicComboFightModel.HALF-ra,hi=s.b.q.x()+(s.a.q.x()-s.ba.x)-PeriodicComboFightModel.HALF-rb;
        Map<String,Object> row=new LinkedHashMap<>();row.put("t",time);row.put("height",s.b.q.y()-4);row.put("render_height",s.ab.y-4);row.put("b_vy",s.b.q.vy());row.put("true_gap",s.b.q.x()-s.a.q.x());row.put("own",PeriodicComboFightModel.own(s));row.put("defender",PeriodicComboFightModel.their(s));row.put("own_vertical",da);row.put("defender_vertical",db);row.put("target_lag",s.b.q.x()-s.ab.x);row.put("own_lag",s.a.q.x()-s.ba.x);row.put("instant_interval_width",hi-lo);row.put("own_lower_headroom",s.a.q.x()-lo);row.put("defender_upper_headroom",hi-s.a.q.x());return row;
    }
}
