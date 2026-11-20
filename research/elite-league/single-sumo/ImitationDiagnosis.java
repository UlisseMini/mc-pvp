import java.util.*;
import java.nio.file.*;
import lab.*;

/** Isolate action-sampling and each imitated button against the positive control teacher. */
final class ImitationDiagnosis {
    static final class Driver extends DuelEnv.Player {
        final Arena.Neural learner;final DuelEnv.Player tutor,wt;final String arm;
        long heads,movementDiff,sideDiff,jumpDiff,clickDiff;
        Driver(Arena.Neural l,DuelEnv.Player t,DuelEnv.Player w,int own,String mode){super("imitation-diagnostic",0,own,0);learner=l;tutor=t;wt=w;arm=mode;}
        public void received(PeriodicComboClock.PacketObservation p){learner.received(p);tutor.received(p);wt.received(p);}
        public boolean injected(PeriodicComboClock.Observation o,double t){learner.injected(o,t);tutor.injected(o,t);wt.injected(o,t);return true;}
        public PeriodicComboClock.Control input(PeriodicComboClock.Observation o){
            var a=learner.input(o);var t=tutor.input(o);var plain=wt.input(o);heads++;
            if(a.key()!=t.key())movementDiff++;if(a.strafe()!=t.strafe())sideDiff++;
            if(a.click()!=t.click())clickDiff++;if(a.jump()!=t.jump())jumpDiff++;
            var c=a;
            if(arm.equals("teacher"))c=t;
            else if(arm.equals("wtap"))c=plain;
            else if(arm.equals("greedy")){
                double[] y=learner.net.scores();int key=y[0]<-.65?3:y[0]>0?(y[1]>0?2:1):0;
                c=new PeriodicComboClock.Control(key,y[4]>.45?.98f:y[4]<-.45?-.98f:0,y[2]>0,o.own().onGround()&&y[3]>0);
            }else c=new PeriodicComboClock.Control(arm.equals("movement")?t.key():a.key(),
                arm.equals("strafe")?t.strafe():a.strafe(),arm.equals("click")?t.click():a.click(),arm.equals("jump")?t.jump():a.jump());
            int packed=DuelNetwork.pack(c.key(),(int)Math.signum(c.strafe()),c.click(),c.jump());
            learner.net.applied(packed);if(tutor instanceof Arena.Neural n)n.net.applied(packed);
            return c;
        }
    }
    public static void main(String[] args)throws Exception{
        LeagueWorker.load();
        try(var src=Files.newBufferedReader(Path.of(args[0]));var dest=Files.newBufferedWriter(Path.of(args[1]))){
            String line;while((line=src.readLine())!=null){
                var t=MemoryAssay.trial(line.split("\t"));var e=new DuelEnv(t.base(),t.seat()?t.own():t.other(),t.seat()?t.other():t.own());
                var result=new LinkedHashMap<String,Object>();
                for(String arm:List.of("sampled","greedy","teacher","movement","strafe","click","jump","wtap")){
                    var a=new Driver((Arena.Neural)LeagueWorker.player(t.actor(),t.own(),t.other(),e,t.aSeed()),
                         LeagueWorker.player(MemoryAssay.resolve(args[2]),t.own(),t.other(),e,t.aSeed()),
                         LeagueWorker.player(MemoryAssay.resolve("wtap"),t.own(),t.other(),e,t.aSeed()),t.own(),arm);
                    var b=LeagueWorker.player(t.foe(),t.other(),t.own(),e,t.bSeed());var g=e.new Game(t.seat(),a,b);g.finish();
                    result.put(arm,List.of(g.reward(),g.clock.time(),a.heads,a.movementDiff,a.sideDiff,a.clickDiff,a.jumpDiff));
                }
                dest.write(Json.encode(result));dest.newLine();
            }
        }
    }
}
