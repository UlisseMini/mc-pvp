import java.util.*;
import java.nio.file.*;
import lab.*;

/** Controlled W-tap substitutions from the same opening with actor and opponent RNGs held fixed. */
final class OpeningDiagnosis {
    static final class Driver extends DuelEnv.Player {
        final DuelEnv.Player actor,baseline; final String mode;
        int heads,go,back,side,jump,click,press;
        double minDistance=999,firstPress=-1;
        Driver(DuelEnv.Player actor,int rtt,String mode){super("diagnostic",0,rtt,0);this.actor=actor;this.mode=mode;baseline=new DuelEnv.Player("wtap",0,rtt,0);}
        public void received(PeriodicComboClock.PacketObservation e){actor.received(e);baseline.received(e);}
        public boolean injected(PeriodicComboClock.Observation o,double at){press++;if(firstPress<0)firstPress=at;baseline.injected(o,at);return actor.injected(o,at);}
        public PeriodicComboClock.Control input(PeriodicComboClock.Observation o){
            var a=actor.input(o);var b=baseline.input(o);
            boolean swapAll=mode.equals("wtap") || mode.equals("opening") && heads<20;
            var c=new PeriodicComboClock.Control(swapAll||mode.equals("forward")?b.key():a.key(),
                swapAll||mode.equals("strafe")?b.strafe():a.strafe(),swapAll||mode.equals("click")?b.click():a.click(),
                swapAll||mode.equals("jump")?b.jump():a.jump());
            if(actor instanceof Arena.Neural n)n.net.applied(DuelNetwork.pack(c.key(),(int)Math.signum(c.strafe()),c.click(),c.jump()));
            if(heads<20){go+=c.key()==1||c.key()==2?1:0;back+=c.key()==3?1:0;side+=c.strafe()!=0?1:0;jump+=c.jump()?1:0;click+=c.click()?1:0;}
            heads++;
            minDistance=Math.min(minDistance,Math.hypot(o.own().x()-o.targetX(),o.own().z()-o.targetZ()));
            return c;
        }
    }
    public static void main(String[] args)throws Exception{
        LeagueWorker.load();var actor=LeagueWorker.fighter(args[0]);var foe=LeagueWorker.fighter(args[1]);
        for(String row:Files.readAllLines(Path.of(args[2]))){
            String[] s=row.split("\t");boolean seat=s[1].equals("a");int own=Integer.parseInt(s[2]),other=Integer.parseInt(s[3]);
            var env=new DuelEnv(LeagueWorker.clock(s[0]),seat?own:other,seat?other:own);
            for(String mode:List.of("actor","wtap","opening","forward","strafe","jump","click")){
                var a=new Driver(LeagueWorker.player(actor,own,other,env,Long.parseLong(s[4])),own,mode);
                var b=LeagueWorker.player(foe,other,own,env,Long.parseLong(s[5]));var g=env.new Game(seat,a,b);
                g.trace();g.finish();int fresh=0,given=0;
                for(var h:g.hits.list)if(!h.upgrade()){fresh++;if(h.sideA()==seat)given++;}
                System.out.println(Json.encode(Map.of("cell",row,"arm",mode,"score",g.reward(),"end",g.clock.time(),"fresh",fresh,
                    "given",given,"inputs20",List.of(Math.min(20,a.heads),a.go,a.back,a.side,a.jump,a.click),
                    "press",a.press,"first_press",a.firstPress,"min_distance",a.minDistance)));
            }
        }
    }
}
