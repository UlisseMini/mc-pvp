package sword;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static sword.Fighter.*;

/** Population self-play. Fitness uses only terminal health and the duel outcome. */
public final class Train {
    static final int POP=36, ELITE=6;
    static double fitness(Model.Score s){
        double gap=(s.a()-s.b())/20;
        double win=s.b()==0?1:s.a()==0?-1:0;
        return win+.5*gap;
    }
    static double[] noise(Random rng,double[] parent,double scale){
        double[] w=parent.clone();for(int i=0;i<w.length;i++)w[i]+=rng.nextGaussian()*scale;
        return w;
    }
    static double play(double[] a,double[] b,Model.Scene scene,boolean swap){
        var p=new Fighter(a);var q=new Fighter(b);
        return swap?-fitness(Model.duel(q,p,scene)):fitness(Model.duel(p,q,scene));
    }
    public static void main(String[] args)throws Exception{
        Path dir=Path.of(args[0]);Files.createDirectory(dir);
        int generations=Integer.parseInt(args[1]);var rng=new Random(Long.parseLong(args[2]));
        List<double[]> pop=new ArrayList<>(),archive=new ArrayList<>();
        for(int k=0;k<POP;k++)pop.add(noise(rng,new double[COUNT],.35));
        archive.add(pop.get(0));var pool=Executors.newFixedThreadPool(Integer.parseInt(args[3]));
        try(var log=Files.newBufferedWriter(dir.resolve("fitness.tsv"))){
            log.write("generation\tbest\tmedian\tduels\n");
            for(int g=0;g<generations;g++){
                var field=new ArrayList<double[]>();var scenes=new ArrayList<Model.Scene>();
                for(int i=0;i<8;i++){
                    var source=i<4?archive:pop;
                    int n=source.size();
                    if(i>=4 && g>0)n=ELITE;
                    int index=i==0?n-1:rng.nextInt(n);
                    field.add(source.get(index));
                    scenes.add(new Model.Scene(rng.nextLong(),rng.nextInt(2),rng.nextInt(2)));
                }
                List<Callable<Double>> tasks=new ArrayList<>();
                for(var candidate:pop)tasks.add(()->{
                    double score=0;for(int i=0;i<field.size();i++){
                        score+=play(candidate,field.get(i),scenes.get(i),false);
                        score+=play(candidate,field.get(i),scenes.get(i),true);
                    }return score/(field.size()*2);
                });
                var results=pool.invokeAll(tasks);double[] scores=new double[POP];
                Integer[] rank=new Integer[POP];for(int i=0;i<POP;i++){rank[i]=i;scores[i]=results.get(i).get();}
                Arrays.sort(rank,Comparator.comparingDouble((Integer i)->scores[i]).reversed());
                var next=new ArrayList<double[]>();for(int i=0;i<ELITE;i++)next.add(pop.get(rank[i]));
                if(g%4==0||g==generations-1){
                    archive.add(next.get(0));write(dir.resolve("gen-%04d.net".formatted(g)),next.get(0));
                }
                log.write("%d\t%.5f\t%.5f\t%d%n".formatted(g,scores[rank[0]],scores[rank[POP/2]],POP*field.size()*2));log.flush();
                while(next.size()<POP){
                    double scale=Math.exp(rng.nextDouble()*Math.log(12))*.018;
                    next.add(noise(rng,next.get(rng.nextInt(ELITE)),scale));
                }
                pop=next;
            }
        }finally{pool.shutdown();}
    }
}
