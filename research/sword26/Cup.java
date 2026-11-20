package sword;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Select for paired duel performance against an independently supplied learned field. */
public final class Cup {
    record Entry(Path name,double[] w){}
    static List<Entry> load(String file)throws Exception{
        var entries=new ArrayList<Entry>();
        for(String line:Files.readAllLines(Path.of(file))){
            var p=Path.of(line);entries.add(new Entry(p,Fighter.read(p)));
        }return entries;
    }
    public static void main(String[] args)throws Exception{
        var actors=load(args[0]);var field=load(args[1]);var rng=new Random(Long.parseLong(args[2]));
        int count=args.length>3?Integer.parseInt(args[3]):8;
        if(count<1)throw new IllegalArgumentException("At least one opening is required");
        boolean equalLatency=Boolean.getBoolean("sword.equalLatency");
        if(equalLatency&&(count<2||count%2!=0))throw new IllegalArgumentException("Provide equal numbers of openings at each latency");
        var scenes=new ArrayList<Model.Scene>();
        for(int i=0;i<count;i++)scenes.add(new Model.Scene(rng.nextLong(),i%2,equalLatency?i%2:i/2%2));
        var pool=Executors.newFixedThreadPool(4);
        try{
            var tasks=new ArrayList<Callable<String>>();
            for(var a:actors)tasks.add(()->{
                double score=0,min=2;int games=0;
                for(var b:field){
                    double sum=0;double[] strata=new double[2];
                    for(var scene:scenes){
                        double payoff=Train.play(a.w,b.w,scene,false)+Train.play(a.w,b.w,scene,true);
                        sum+=payoff;strata[scene.delayA()]+=payoff;
                    }
                    double lower=equalLatency?Math.min(strata[0],strata[1])/count:sum/(scenes.size()*2);
                    min=Math.min(min,lower);score+=sum;games+=scenes.size()*2;
                }
                return String.format(Locale.ROOT,"%.6f\t%.6f\t%d\t%s",score/games,min,games,a.name);
            });
            for(var result:pool.invokeAll(tasks))System.out.println(result.get());
        }finally{pool.shutdown();}
    }
}
