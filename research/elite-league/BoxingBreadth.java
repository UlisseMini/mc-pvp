import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import lab.Json;

/** Compare existing boxers after one W-tap opening against defenders with different timing. */
final class BoxingBreadth {
    static final class Defender extends DuelEnv.Player{
        final DuelEnv.Player inner;final double starts;
        Defender(Arena.Fighter f,int rtt){super("defender",0,rtt,0);inner=f.create(rtt,0);starts=2000;}
        Defender(Defender p){super(p.style,0,p.rtt,0);inner=p.inner.copy();starts=p.starts;firstClick=p.firstClick;}
        @Override Defender copy(){return new Defender(this);}
        @Override public PeriodicComboClock.Control input(PeriodicComboClock.Observation o){
            var c=inner.input(o);
            return new PeriodicComboClock.Control(c.key(),c.strafe(),c.click()&&o.time()>=starts,c.jump());
        }
        @Override public void received(PeriodicComboClock.PacketObservation p){inner.received(p);}
        @Override public boolean injected(PeriodicComboClock.Observation o,double t){super.injected(o,t);return inner.injected(o,t);}
        @Override public void consumed(PeriodicComboClock.Observation o,long id){inner.consumed(o,id);}
        @Override public float aimYaw(float y){return inner.aimYaw(y);}
        @Override public boolean contactOnly(){return inner.contactOnly();}
        @Override public boolean allowInjection(PeriodicComboClock.Observation o,String source,BooleanSupplier ready){
            return inner.allowInjection(o,source,ready);
        }
    }
    public static void main(String[] a)throws Exception{
        var arms=Matches.roster(Path.of(a[0]));var foes=Matches.roster(Path.of(a[1]));
        var envs=Arena.envs(Arrays.stream(a,2,a.length).map(Path::of).toList(),"mmc",false);
        var pool=Executors.newFixedThreadPool(3);
        try{
            for(var env:envs){
                env.initial.timing.mouseInput=true;var jobs=new ArrayList<Callable<String>>();
                for(var f:arms)for(var opp:foes)for(boolean seat:new boolean[]{true,false})
                    jobs.add(()->Json.encode(Matches.boxing(env,seat,f,opp)));
                for(var res:pool.invokeAll(jobs))System.out.println(res.get());
            }
        }finally{pool.shutdown();}
    }
}
