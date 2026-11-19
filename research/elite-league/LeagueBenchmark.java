import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import lab.Json;

/** Time regulation rollouts with the same opposing roster in every arm. Self-reports wall time including throttling. */
final class LeagueBenchmark {
    record Stats(double points,double simulatedMs,long heads){}
    static Stats measure(Arena.Fighter f,List<Arena.Fighter> opponents,List<DuelEnv> envs,int n,int thread,int totalThreads){
        double points=0,ms=0;long heads=0;
        for(int i=thread;i<n;i+=totalThreads){
            var e=envs.get((i/2)%envs.size());var o=opponents.get((i/(2*envs.size())+i/2)%opponents.size());
            var g=new Arena.Scenario(e,o,i%2==0).round(f);
            points+=g.reward();ms+=g.clock.time();
            // HeadTiming.tick() is zero-indexed and the first stamped head has index zero.
            var a=g.clock.headTiming(true);var b=g.clock.headTiming(false);
            if(a!=null)heads+=a.tick()+1;if(b!=null)heads+=b.tick()+1;
        }
        return new Stats(points,ms,heads);
    }
    public static void main(String[] a)throws Exception{
        int n=Integer.parseInt(a[0]);var arms=Matches.roster(Path.of(a[1]));var opponents=Matches.roster(Path.of(a[2]));
        var envs=Arena.envs(Arrays.stream(a,3,a.length).map(Path::of).toList(),"mmc",true);
        for(int threads:new int[]{1,3}){
            var pool=Executors.newFixedThreadPool(threads);
            try{
                for(var arm:arms){
                    measure(arm,opponents,envs,256,0,1);
                    var jobs=new ArrayList<Callable<Stats>>();
                    long start=System.nanoTime();
                    for(int t=0;t<threads;t++){final int part=t;jobs.add(()->measure(arm,opponents,envs,n,part,threads));}
                    double points=0,ms=0;long heads=0;
                    for(var pending:pool.invokeAll(jobs)){var v=pending.get();points+=v.points;ms+=v.simulatedMs;heads+=v.heads;}
                    double elapsed=(System.nanoTime()-start)/1e9;
                    System.out.println(Json.encode(Map.of("arm",arm.name(),"threads",threads,"rounds",n,"points",points,
                         "wall_s",elapsed,"rounds_s",n/elapsed,"sim_s_wall_s",ms/(elapsed*1000),"actual_input_heads",heads,"heads_s",heads/elapsed)));
                }
            }finally{pool.shutdown();}
        }
    }
}
