import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import lab.Json;

/** Learn over existing human-control movement presets and state-dependent jump timing. */
public final class League {
    static final int STYLES=17, DEFAULT_TRADE=10;
    record Value(int code,double mean,double worst) {}
    static final Comparator<Value> ORDER=Comparator.comparingDouble((Value v)->-v.mean)
        .thenComparingDouble(v->-v.worst).thenComparingInt(v->Integer.bitCount(v.code&65535)).thenComparingInt(Value::code);
    static Value play(int code,List<Learn.Fixture> group){
        double[] sums=new double[3];int[] n=new int[3];double total=0;
        for(var f:group){
            int i=switch(f.other()){case "wtap"->0;case "hold"->1;case "trade"->2;default->throw new AssertionError();};
            double r=f.score(code);sums[i]+=r;n[i]++;total+=r;
        }
        double worst=1;for(int i=0;i<3;i++)if(n[i]>0)worst=Math.min(worst,sums[i]/n[i]);
        return new Value(code,total/group.size(),worst);
    }
    static List<Value> rank(Set<Integer> codes,List<Learn.Fixture> group,ExecutorService pool)throws Exception{
        var jobs=new ArrayList<Callable<Value>>();
        for(int code:codes)jobs.add(()->play(code,group));
        var scores=new ArrayList<Value>();for(var f:pool.invokeAll(jobs))scores.add(f.get());
        scores.sort(ORDER);return scores;
    }
    static int sample(double[] p,Random r){
        double x=r.nextDouble();for(int i=0;i<p.length-1;i++)if((x-=p[i])<=0)return i;
        return p.length-1;
    }
    public static void main(String[] args)throws Exception{
        long seed=Long.parseLong(args[0]);int generations=Integer.parseInt(args[1]),pop=Integer.parseInt(args[2]),
            elite=Integer.parseInt(args[3]),size=Integer.parseInt(args[4]),finalists=Integer.parseInt(args[5]);
        var rng=new Random(seed);var fixtures=new ArrayList<Learn.Fixture>();
        for(int i=6;i<args.length;i++){
            var tape=DuelEnv.Tape.read(Path.of(args[i]),DuelEnv.LIMIT);
            for(int rtt:new int[]{0,80}){
                var env=new DuelEnv(tape,"mmc",rtt,true,29,false);
                for(String opp:List.of("wtap","hold","trade"))for(boolean first:new boolean[]{true,false})
                    fixtures.add(new Learn.Fixture(env,first,opp));
            }
        }
        double[] styles=new double[STYLES],bits=new double[16];Arrays.fill(styles,1./STYLES);Arrays.fill(bits,.20);
        var seeds=List.of(0,65536,DEFAULT_TRADE<<16,8288,32816);var previous=new ArrayList<>(seeds);
        var winners=new LinkedHashSet<>(seeds);var history=new ArrayList<Object>();
        var pool=Executors.newFixedThreadPool(4);int episodes=0;
        try {
            for(int gen=0;gen<generations;gen++){
                var group=new ArrayList<>(fixtures);Collections.shuffle(group,rng);
                var options=new LinkedHashSet<>(seeds);options.addAll(previous);
                while(options.size()<pop){
                    int code=sample(styles,rng)<<16;for(int b=0;b<16;b++)if(rng.nextDouble()<bits[b])code|=1<<b;
                    options.add(code);
                }
                var scores=rank(options,group.subList(0,size),pool);episodes+=options.size()*size;
                var top=scores.subList(0,elite);int[] count=new int[STYLES],ones=new int[16];
                for(var v:top){
                    count[v.code>>>16]++;for(int b=0;b<16;b++)if((v.code&(1<<b))!=0)ones[b]++;
                }
                for(int i=0;i<STYLES;i++)styles[i]=.25*styles[i]+.75*(count[i]+.15)/(elite+.15*STYLES);
                for(int b=0;b<16;b++)bits[b]=Math.max(.04,Math.min(.96,.25*bits[b]+.75*ones[b]/elite));
                previous=new ArrayList<>(top.stream().limit(3).map(Value::code).toList());
                winners.addAll(previous);
                history.add(Map.of("generation",gen,"winner",previous.get(0),"score",top.get(0).mean,"wtap",scores.stream().filter(v->v.code==0).findFirst().orElseThrow().mean));
            }
            // Re-rank all distinct generation champions and controls on the complete grid.
            // The finalist quota applies to non-controls; use earliest champions, preserving the baselines.
            var subset=new LinkedHashSet<>(seeds);
            for(int i=history.size()-1;i>=0;i--)subset.add((int)((Map<?,?>)history.get(i)).get("winner"));
            for(int code:winners){if(subset.size()>=finalists+seeds.size())break;subset.add(code);}
            var result=rank(subset,fixtures,pool);episodes+=subset.size()*fixtures.size();
            var best=result.get(0);int style=best.code>>>16;var chosen=new DuelEnv.Player("wtap",0,0,0);chosen.select(best.code);
            System.out.println(Json.encode(Map.of("code",best.code,"mask",chosen.mask,"style",chosen.style,"wait",chosen.waitMs,
                "window",chosen.tradeWindow,"episodes",episodes,"fixtures",fixtures.size(),"rounds",history,
                "finalists",result.stream().map(v->List.of(v.code,v.mean,v.worst)).toList())));
        }finally{pool.shutdown();}
    }
}
