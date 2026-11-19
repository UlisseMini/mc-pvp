import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import lab.Json;

/** Time complete policy-versus-policy rounds, including queue service and regulation ring-outs. */
final class Benchmark {
    record Fixture(DuelEnv env, boolean first, String opponent) {}
    record Work(int rounds, double seconds, int wins) {}

    static Work batch(List<Fixture> fixtures, int worker, int workers, int rounds, int mask) {
        double seconds=0;
        int count=0, wins=0;
        for(int i=worker;i<rounds;i+=workers){
            Fixture f=fixtures.get(i%fixtures.size());
            var game=f.env.start(f.first,f.opponent,mask);
            game.finish();
            seconds+=game.clock.time()/1000.0;
            if(game.reward()==1)wins++;
            count++;
        }
        return new Work(count,seconds,wins);
    }
    public static void main(String[] args)throws Exception {
        int n=Integer.parseInt(args[1]),workers=Integer.parseInt(args[2]),mask=Integer.parseInt(args[3]);
        if(n<1 || workers<1)throw new IllegalArgumentException("Positive rounds and worker count required");
        List<Fixture> fixtures=new ArrayList<>();
        try(var paths=Files.list(Path.of(args[0]))){
            for(var file:paths.sorted().toList()){
                var tape=DuelEnv.Tape.read(file,DuelEnv.LIMIT);
                for(int rtt:new int[]{0,80}){
                    var env=new DuelEnv(tape,"mmc",rtt,true);
                    for(boolean first:new boolean[]{true,false})for(String opponent:List.of("wtap","hold","trade"))
                        fixtures.add(new Fixture(env,first,opponent));
                }
            }
        }
        batch(fixtures,0,1,fixtures.size(),mask);
        var pool=Executors.newFixedThreadPool(workers);
        try {
            List<Callable<Work>> tasks=new ArrayList<>();
            for(int w=0;w<workers;w++){final int i=w;tasks.add(()->batch(fixtures,i,workers,n,mask));}
            long start=System.nanoTime();
            double seconds=0;int wins=0,count=0;
            for(var f:pool.invokeAll(tasks)){Work r=f.get();seconds+=r.seconds();wins+=r.wins();count+=r.rounds();}
            double wall=(System.nanoTime()-start)/1e9;
            System.out.println(Json.encode(Map.of("rounds",count,"workers",workers,"wins",wins,"simulated_seconds",seconds,
                    "wall_seconds",wall,"rounds_per_second",count/wall,"equivalent_20hz_duel_ticks_per_second",20*seconds/wall,
                    "realtime_multiple",seconds/wall,"policy_code",mask)));
        } finally {pool.shutdown();}
    }
}
