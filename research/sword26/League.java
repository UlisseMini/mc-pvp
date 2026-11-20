package sword;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static sword.Fighter.*;

/** Evolve independent populations against their own peers, archived opponents, and past opponents
 * which defeated the incumbent. Include externally supplied, independently learned checkpoints. */
public final class League {
    static final int POP=40,ELITE=8;
    public static void main(String[] args)throws Exception{
        Path out=Path.of(args[0]);Files.createDirectory(out);
        int gens=Integer.parseInt(args[1]);Random rng=new Random(Long.parseLong(args[2]));
        var pool=Executors.newFixedThreadPool(Integer.parseInt(args[3]));
        var archive=new ArrayList<double[]>();for(int i=4;i<args.length;i++)archive.add(read(Path.of(args[i])));
        var pop=new ArrayList<double[]>();for(int i=0;i<POP;i++)pop.add(TreePolicy.random(rng));
        var hard=new ArrayList<double[]>();archive.add(pop.get(0));
        try(var log=Files.newBufferedWriter(out.resolve("training.tsv"))){
            log.write("generation\tbest\tmedian\tduels\n");
            for(int g=0;g<gens;g++){
                var field=new ArrayList<double[]>();var starts=new ArrayList<Model.Start>();
                for(int i=0;i<12;i++){
                    var p=i<3?pop:i<6&&!hard.isEmpty()?hard:archive;
                    int n=p==pop&&g>0?ELITE:p.size();
                    field.add(p.get(i==0?0:rng.nextInt(n)));
                    var scene=new Model.Scene(rng.nextLong(),rng.nextInt(2),rng.nextInt(2));
                    starts.add(i%3==2?Starts.sample(scene,archive.get(rng.nextInt(archive.size())),field.getLast(),rng):new Model.Start(scene,List.of()));
                }
                var tasks=new ArrayList<Callable<double[]>>();
                for(var w:pop)tasks.add(()->{
                    double[] result=new double[field.size()];
                    for(int i=0;i<result.length;i++){
                        result[i]=.5*(Starts.play(w,field.get(i),starts.get(i),false)+Starts.play(w,field.get(i),starts.get(i),true));
                    }return result;
                });
                var jobs=pool.invokeAll(tasks);double[] scores=new double[POP];Integer[] rank=new Integer[POP];
                for(int i=0;i<POP;i++){rank[i]=i;var v=jobs.get(i).get();scores[i]=Arrays.stream(v).average().orElseThrow();}
                Arrays.sort(rank,Comparator.comparingDouble((Integer i)->scores[i]).reversed());
                var next=new ArrayList<double[]>();for(int i=0;i<ELITE;i++)next.add(pop.get(rank[i]));
                var champ=next.get(0);if(g%8==0||g==gens-1){
                    archive.add(champ);write(out.resolve("gen-%04d.net".formatted(g)),champ);
                    var games=jobs.get(rank[0]).get();for(int i=0;i<games.length;i++)if(games[i]<0)hard.add(field.get(i));
                    while(hard.size()>40)hard.removeFirst();
                }
                while(next.size()<POP)next.add(TreePolicy.child(next.get(rng.nextInt(ELITE)),next.get(rng.nextInt(ELITE)),rng));
                pop=next;
                log.write("%d\t%.5f\t%.5f\t%d%n".formatted(g,scores[rank[0]],scores[rank[POP/2]],POP*field.size()*2));log.flush();
            }
        }finally{pool.shutdown();}
    }
}
