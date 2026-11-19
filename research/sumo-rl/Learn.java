import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import lab.Json;

/** Cross-entropy episodic policy search. Every arm plays identical clock/seat/opponent fixtures. */
public final class Learn {
    record Fixture(DuelEnv env, boolean first, String other) {
        double score(int mask){var round=env.start(first,other,mask);round.finish();return round.reward();}
    }
    static final class Scored {final int mask;final double score;Scored(int mask,double score){this.mask=mask;this.score=score;}}
    static Scored evaluate(int mask,List<Fixture> fixtures){
        double total=0;for(var f:fixtures)total+=f.score(mask);return new Scored(mask,total/fixtures.size());
    }
    public static void main(String[] args)throws Exception{
        // Output is the immutable fit. Numeric hyperparameters come from the registered protocol via run.py.
        var files=Files.list(Path.of(args[0])).sorted().toList();
        var fixtures=new ArrayList<Fixture>();
        for(var file:files){
            var tape=DuelEnv.Tape.read(file,DuelEnv.LIMIT);
            for(int rtt:new int[]{0,80}){
                var env=new DuelEnv(tape,"mmc",rtt,true);
                for(boolean first:new boolean[]{true,false})for(String opponent:List.of("wtap","hold"))fixtures.add(new Fixture(env,first,opponent));
            }
        }
        var rng=new Random(Long.parseLong(args[1]));
        int generations=Integer.parseInt(args[2]),population=Integer.parseInt(args[3]),elite=Integer.parseInt(args[4]);
        var workers=Executors.newFixedThreadPool(4);
        var cache=new HashMap<Integer,Scored>();var progress=new ArrayList<Object>();
        var probabilities=new double[16];Arrays.fill(probabilities,.30);
        var order=Comparator.comparingDouble((Scored s)->-s.score).thenComparingInt(s->Integer.bitCount(s.mask)).thenComparingInt(s->s.mask);
        var best=evaluate(0,fixtures);cache.put(0,best);double baseline=best.score;
        try {
            for(int gen=0;gen<generations;gen++){
                var candidates=new LinkedHashSet<Integer>(); candidates.add(best.mask);candidates.add(0);
                while(candidates.size()<population){
                    int mask=0;for(int b=0;b<16;b++)if(rng.nextDouble()<probabilities[b])mask |= 1<<b;
                    candidates.add(mask);
                }
                List<Callable<Scored>> tasks=new ArrayList<>();
                for(int m:candidates)if(!cache.containsKey(m))tasks.add(()->evaluate(m,fixtures));
                for(var future:workers.invokeAll(tasks)){var s=future.get();cache.put(s.mask,s);}
                var ranked=candidates.stream().map(cache::get).sorted(order).toList();best=ranked.get(0);
                for(int b=0;b<16;b++){
                    double n=0;for(var s:ranked.subList(0,elite))if((s.mask&(1<<b))!=0)n++;
                    probabilities[b]=Math.max(.06,Math.min(.94,.30*probabilities[b]+.70*n/elite));
                }
                progress.add(Map.of("generation",gen,"mask",best.mask,"score",best.score,"wtap",baseline));
                System.err.printf("generation %d: %d %.4f vs %.4f%n",gen,best.mask,best.score,baseline);
            }
        } finally {workers.shutdown();}
        System.out.println(Json.encode(Map.of("mask",best.mask,"score",best.score,"wtap",baseline,"generations",progress,
            "unique_policies",cache.size(),"episodes",cache.size()*fixtures.size(),"fixtures",fixtures.size())));
    }
}
