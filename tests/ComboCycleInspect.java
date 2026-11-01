import lab.Json;
import java.nio.file.*;
import java.util.*;

/** Detailed event trace for one frozen candidate, using the canonical kernel. */
public final class ComboCycleInspect {
    public static void main(String[] args)throws Exception{
        if(args.length<6)throw new IllegalArgumentException("output.json reach coastStart coastLength burst seconds [horizontal vertical hitTicks ownRTT opponentRTT]");
        Path output=Path.of(args[0]);if(Files.exists(output))throw new IllegalArgumentException("Refuse overwrite");
        var c=new PeriodicComboFightModel.Config();c.ownRtt=args.length>9?Double.parseDouble(args[9]):0;c.otherRtt=args.length>10?Double.parseDouble(args[10]):120;c.defenderReach=Double.parseDouble(args[1]);c.forceContacts=false;
        c.kbScale=args.length>6?Double.parseDouble(args[6]):1;c.verticalScale=args.length>7?Double.parseDouble(args[7]):1;c.hitTicks=args.length>8?Integer.parseInt(args[8]):10;
        var timing=new PeriodicComboClock.Timing();timing.uplinkA=timing.uplinkB=timing.downlinkA=timing.downlinkB=.4;
        var state=new PeriodicComboFightModel.State(4093,0,4099,0);PeriodicComboFightModel.defenderReach=c.defenderReach;
        var plan=new ComboCycleSearch.Plan(2*c.hitTicks,Integer.parseInt(args[2]),Integer.parseInt(args[3]),0,Integer.parseInt(args[4]),0);
        var controller=new ComboCycleSearch.Controller(plan,c.hitTicks);
        var clock=new PeriodicComboClock(state,c,timing,controller,o->new PeriodicComboClock.Control(2,0,o.time()>=2000));
        var score=new ComboCycleSearch.Score(c,timing);List<Map<String,Object>> events=new ArrayList<>();int[] previous={0,0};
        clock.setAudit((t,event,s)->{
            score.event(t,event,s);
            if(event.startsWith("INPUT")||s.hits!=previous[0]||s.acceptedB!=previous[1]){
                Map<String,Object> row=new LinkedHashMap<>();row.put("time",t);row.put("event",event);row.put("phase",Double.isNaN(controller.anchor)?-1:Math.round((t-controller.anchor)/50)%plan.period());
                row.put("hits",List.of(s.hits,s.incomingHits));row.put("accepted",List.of(s.acceptedA,s.acceptedB));
                row.put("a",List.of(s.a.q.x(),s.a.q.y(),s.a.q.vx(),s.a.q.vy()));row.put("b",List.of(s.b.q.x(),s.b.q.y(),s.b.q.vx(),s.b.q.vy()));
                row.put("range",List.of(PeriodicComboFightModel.own(s),PeriodicComboFightModel.their(s)));row.put("ray",List.of(PeriodicComboFightModel.ray(s.a,s.ab),PeriodicComboFightModel.ray(s.b,s.ba)));
                row.put("server",List.of(s.serverAx,s.serverAy,s.serverA.vx(),s.serverA.vy(),s.serverBx,s.serverBy,s.serverB.vx(),s.serverB.vy()));
                row.put("sprint",List.of(s.a.sprint,s.b.sprint,s.serverSprint,s.serverSprintB));row.put("H",List.of(s.ownResistant,s.resistant));row.put("last_velocity",List.of(s.lastVx,s.lastVy));events.add(row);
            }
            previous[0]=s.hits;previous[1]=s.acceptedB;
        });
        double requested=Double.parseDouble(args[5])*1000;
        try{clock.runUntil(requested);}catch(IllegalStateException e){score.stop=e.getMessage();}
        Map<String,Object> result=score.result(plan,state,requested,clock.time());Files.writeString(output,Json.encode(Map.of("summary",result,"events",events))+"\n");System.out.println(Json.encode(result));
    }
}
