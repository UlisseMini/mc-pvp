package sword;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static sword.Fighter.*;

/** Optimize on a broad reference population, with a downside term for exploitable matchups. */
public final class Refine {
    record Opp(double[] w,Model.Start state){}
    record Ranked(double score,double[] w){}
    static double score(double[] w,List<Opp> panel){
        double[] scores=new double[panel.size()];
        for(int i=0;i<scores.length;i++){
            var opp=panel.get(i);scores[i]=(Starts.play(w,opp.w,opp.state,false)+Starts.play(w,opp.w,opp.state,true))/2;
        }
        Arrays.sort(scores);
        return .7*Arrays.stream(scores).average().orElseThrow()+.3*Arrays.stream(scores,0,Math.max(1,scores.length/3)).average().orElseThrow();
    }
    public static void main(String[] args)throws Exception{
        var dir=Path.of(args[0]);Files.createDirectory(dir);int gens=Integer.parseInt(args[1]);
        var rng=new Random(Long.parseLong(args[2]));
        List<double[]> reference=new ArrayList<>(),parents=new ArrayList<>();
        for(int i=3;i<args.length;i++){var w=read(Path.of(args[i]));reference.add(w);if(w.length==TreePolicy.COUNT)parents.add(w);}
        int fixed=reference.size();
        var pool=Executors.newFixedThreadPool(4);
        try(var log=Files.newBufferedWriter(dir.resolve("training.tsv"))){
            log.write("generation\tbest\tduels\n");
            for(int gen=0;gen<gens;gen++){
                var panel=new ArrayList<Opp>();
                for(int i=0;i<22;i++){
                    int size=i%4>0?fixed:reference.size();
                    var opponent=reference.get(rng.nextInt(size));
                    var scene=new Model.Scene(rng.nextLong(),rng.nextInt(2),rng.nextInt(2));
                    var start=i%5==4?Starts.sample(scene,reference.get(rng.nextInt(reference.size())),opponent,rng):new Model.Start(scene,List.of());
                    panel.add(new Opp(opponent,start));
                }
                var work=new ArrayList<Callable<Ranked>>();
                for(int i=0;i<56;i++){
                    double[] w=i<Math.min(12,parents.size())?parents.get(i):TreePolicy.child(parents.get(rng.nextInt(parents.size())),parents.get(rng.nextInt(parents.size())),rng);
                    work.add(()->new Ranked(score(w,panel),w));
                }
                var contenders=new ArrayList<Ranked>();for(var f:pool.invokeAll(work))contenders.add(f.get());
                contenders.sort(Comparator.comparingDouble(Ranked::score).reversed());
                parents=new ArrayList<>();for(var r:contenders){if(parents.stream().noneMatch(p->Arrays.equals(p,r.w)))parents.add(r.w);if(parents.size()==12)break;}
                if(gen%8==0||gen==gens-1){reference.add(parents.getFirst());write(dir.resolve("gen-%04d.net".formatted(gen)),parents.getFirst());}
                log.write("%d\t%.4f\t%d%n".formatted(gen,contenders.getFirst().score,56*22*2));log.flush();
            }
        }finally{pool.shutdown();}
    }
}
