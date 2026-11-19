import java.nio.file.*;
import java.util.*;
import lab.DuelNetwork;
import lab.Json;

final class BoxingRegulation {
    /** Mirror the Forge specialist's local cue-release rule; the defender attacks from the first input. */
    static final class Specialist extends DuelEnv.Player{
        final Arena.Neural network;
        final DuelEnv.Player wt;
        boolean released;int streak;double incoming=Double.NEGATIVE_INFINITY,outgoing=Double.NEGATIVE_INFINITY;
        Specialist(Arena.Model model,int ping){super("specialist",0,ping,0);network=new Arena.Neural(model,ping);wt=new DuelEnv.Player("wtap",0,ping,0);}
        public PeriodicComboClock.Control input(PeriodicComboClock.Observation o){
            var proposed=network.input(o);var prefix=wt.input(o);
            if(!released && o.time()>=2000){
                if(o.lastOwnHurtTime()>incoming){incoming=o.lastOwnHurtTime();streak=0;}
                if(o.lastTargetHurtTime()>outgoing){
                    outgoing=o.lastTargetHurtTime();if(outgoing>=2000 && outgoing>incoming)streak++;
                }
                if(streak>=3)released=true;
            }
            var c=released?proposed:prefix;
            network.net.applied(DuelNetwork.pack(c.key(),(int)Math.signum(c.strafe()),c.click(),c.jump()));
            return c;
        }
    }
    static Map<String,Object> play(DuelEnv e,boolean first,Arena.Fighter f,Arena.Fighter opponent){
        DuelEnv.Player our=f.name().equals("specialist")?new Specialist(f.model(),e.rtt):f.create(e.rtt,0);
        var g=e.new Game(first,our,opponent.create(e.rtt,0));g.trace();int processed=0,own=0,other=0;
        double finish=e.limit;boolean complete=false;
        while(!complete&&g.advance()){
            while(processed<g.hits.list.size()){
                var h=g.hits.list.get(processed++);if(h.sideA()==first)own++;else other++;
                if(own==100||other==100){complete=true;finish=h.at();break;}
            }
        }
        var hs=new ArrayList<Object>();for(var h:g.hits.list.subList(0,processed))hs.add(List.of(h.at(),h.sideA()!=first,h.upgrade()));
        return Map.ofEntries(Map.entry("arm",f.name()),Map.entry("opponent",opponent.name()),Map.entry("clock",e.clockId),
            Map.entry("rtt",e.rtt),Map.entry("side",first?"a":"b"),Map.entry("score",!complete?.5:own==100?1.:0.),
            Map.entry("end",finish),Map.entry("active",Math.max(g.a.firstClick,g.b.firstClick)),Map.entry("hits",hs),
            Map.entry("margin",own-other),Map.entry("mode","boxing-100"));
    }
    public static void main(String[] a)throws Exception{
        var arms=Matches.roster(Path.of(a[0]));
        for(Path p:Arrays.stream(a,1,a.length).map(Path::of).toList()){
            var t=DuelEnv.Tape.read(p,180000);
            for(int ping:new int[]{0,80}){
                var e=new DuelEnv(t,"mmc",ping,ping,false,29,true,10,6,0,180000);
                for(var f:arms)for(var opp:List.of(Evolve.WT,Evolve.HOLD))for(boolean seat:new boolean[]{true,false})
                    System.out.println(Json.encode(play(e,seat,f,opp)));
            }
        }
    }
}
