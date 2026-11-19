import java.io.*;
import java.util.*;
import lab.Json;

/** Predict the native fight from physical inputs and observed host-stage times.
 * No contact, health, velocity or subsequent position is supplied. */
public final class InputReplay {
    static final class Keyboard implements PeriodicComboClock.Policy {
        final List<PeriodicComboClock.Control> words=new ArrayList<>();
        int index;
        public PeriodicComboClock.Control input(PeriodicComboClock.Observation unused){return words.get(index++);}
    }
    public static void main(String[] args)throws Exception{
        var input=new BufferedReader(new InputStreamReader(System.in));
        var a=new Keyboard();var b=new Keyboard();
        String[] header=input.readLine().split(" ");
        double center=Double.parseDouble(header[0]);int rtt=Integer.parseInt(header[1]);
        var cfg=BoxingModel.config(header[2],rtt);cfg.sumo=true;cfg.sumoArena(center,12);
        var timing=new PeriodicComboClock.Timing();timing.inputMode=PeriodicComboClock.InputMode.NATIVE_QUEUED;
        timing.explicitPolls=true;timing.uplinkA=timing.uplinkB=timing.downlinkA=timing.downlinkB=.35;
        var tape=new ArrayList<PeriodicComboClock.Scheduled>();
        String line;long end=0;
        while((line=input.readLine())!=null){
            var v=line.split(" ");boolean first=v[1].equals("a");long ns=Long.parseLong(v[0]);
            var kind=PeriodicComboClock.Kind.valueOf(v[2]);
            if(kind==PeriodicComboClock.Kind.TICK_HEAD)(first?a:b).words.add(
                new PeriodicComboClock.Control(Integer.parseInt(v[3]),0,true,Boolean.parseBoolean(v[4])));
            tape.add(new PeriodicComboClock.Scheduled(ns,tape.size(),kind,first||v[1].equals("s")));end=Math.max(end,ns);
        }
        var st=new PeriodicComboFightModel.State(center-3,0,center+3,0,12);
        var clock=new PeriodicComboClock(st,cfg,timing,a,b,tape);
        var moves=new ArrayList<Object>();var hits=new DuelEnv.Hits();
        clock.setAudit((t,event,state)->{
            if(event.equals("MOVEA")||event.equals("MOVEB")){
                var q=event.equals("MOVEA")?state.a.q:state.b.q;
                moves.add(List.of(Math.round(t*1e6),event.equals("MOVEA")?"a":"b",q.x(),q.y(),q.z(),q.vx(),q.vy(),q.vz()));
            }
            hits.event(t,event,state);
        });
        int[] admits=new int[2];
        clock.setClickAudit(e->{if(e.action().equals("ADMIT"))admits[e.fromA()?0:1]++;});
        clock.runUntil(end/1e6);
        System.out.println(Json.encode(Map.of("moves",moves,"admissions",List.of(admits[0],admits[1]),
            "hits",hits.list.stream().map(h->List.of(h.at(),h.sideA(),h.upgrade())).toList())));
    }
}
