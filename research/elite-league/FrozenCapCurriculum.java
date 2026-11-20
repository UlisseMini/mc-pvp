import java.util.*;
import java.util.concurrent.*;
import java.nio.file.*;

/** Two matched continuation schedules with identical legal starting weights and rollout draws. */
final class FrozenCapCurriculum {
    static int cap(boolean curriculum,int step){return !curriculum||step>=768?20:Math.min(20,7+2*(step/96));}
    public static void main(String[] args)throws Exception{
        try{
            FrozenSumoResponse.load();
            String id=args[0];boolean curriculum=args[1].equals("ramp");int seed=Integer.parseInt(args[2]);
            var fighter=InputStudy.fighter(id,Path.of(args[3]));var model=fighter.model();float[] w=model.weights();
            if(model.family()!=lab.DuelNetwork.Family.INPUT32)throw new IllegalArgumentException("Expected the released wide network");
            var adam=new PPO.Adam(w.length);double[] critic=new double[35];var rng=new Random(seed);var learn=new Random(seed+2917L);
            var validation=FrozenSumoResponse.panel(FrozenSumoResponse.dev);
            try(var best=new FrozenSumoResponse.Best(id,validation)){
                for(int g=0;g<=1536;g++){
                    if(g%64==0)best.consider(g,fighter);
                    if(g==1536)break;
                    int cps=cap(curriculum,g);long ns=(1_000_000_000L+cps-1)/cps;
                    var jobs=new ArrayList<Callable<PPO.Batch>>();
                    double temperature=g<512?.29:g<1152?.22:.14,lr=g<768?.00027:g<1280?.00014:.00006;
                    for(int k=0;k<32;k++){var s=FrozenSumoResponse.draw(rng);
                        s.env().initial.timing.clickIntervalNanos=ns;long ep=rng.nextLong();
                        jobs.add(()->PPO.trajectory(s,w,critic,temperature,ep));}
                    var steps=new ArrayList<PPO.Step>();
                    for(var r:PingLeague.pool.invokeAll(jobs))steps.addAll(r.get().steps());
                    PPO.update(steps,w,critic,adam,learn,new PPO.Dist(temperature),lr);
                }
                best.finish();System.out.println(id+" at 20 CPS, development "+best.high);
            }
        }finally{PingLeague.pool.shutdown();}
    }
}
