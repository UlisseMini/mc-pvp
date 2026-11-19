import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import lab.DuelNetwork;
import lab.Json;

/** Population policy search with common random numbers and a fixed-weight historical opponent pool. */
final class Evolve {
    record Result(Arena.Fighter fighter,double mean,double worst){
        double merit(){return .7*mean+.3*worst;}
    }
    static final Comparator<Result> ORDER=Comparator.comparingDouble(Result::merit).reversed()
        .thenComparing(Comparator.comparingDouble(Result::mean).reversed()).thenComparing(r->r.fighter.name());
    static final Arena.Fighter WT=Arena.Fighter.named("wtap"),HOLD=Arena.Fighter.named("hold");
    static Result play(Arena.Fighter f,List<Arena.Scenario> games){
        Map<String,double[]> buckets=new LinkedHashMap<>();
        for(var s:games){
            double reward=s.game(f);var cell=buckets.computeIfAbsent(s.opponent().name(),key->new double[2]);
            cell[0]+=reward;cell[1]++;
        }
        var values=buckets.values().stream().mapToDouble(v->v[0]/v[1]).summaryStatistics();
        return new Result(f,values.getAverage(),values.getMin());
    }
    static List<Result> rank(List<Arena.Fighter> pop,List<Arena.Scenario> games,ExecutorService pool)throws Exception{
        var jobs=new ArrayList<Callable<Result>>();
        for(var f:pop)jobs.add(()->play(f,games));
        var results=new ArrayList<Result>();for(var pending:pool.invokeAll(jobs))results.add(pending.get());
        results.sort(ORDER);return results;
    }
    static Arena.Model initial(DuelNetwork.Family family,Random random){
        float[] w=new float[DuelNetwork.size(family)];
        int block=(family==DuelNetwork.Family.LINEAR?DuelNetwork.INPUTS:DuelNetwork.HIDDEN)+1;
        int output=w.length-DuelNetwork.OUTPUTS*block;
        for(int i=0;i<output;i++)w[i]=(float)(random.nextGaussian()*.48);
        // Most first-generation children start near a moving, attacking fighter.
        double[] bias={.8,.8,.9,-.55,0};
        for(int out=0;out<bias.length;out++)w[output+out*block]=(float)(family==DuelNetwork.Family.HYBRID?.1*bias[out]:bias[out]);
        return new Arena.Model(family,w);
    }
    static Arena.Model mutate(Arena.Model parent,Random rng,double sigma){
        float[] w=parent.weights().clone();
        double fraction=rng.nextDouble()<.5?.12:.65;
        for(int i=0;i<w.length;i++)if(rng.nextDouble()<fraction)
            w[i]=(float)Math.max(-6,Math.min(6,w[i]+sigma*rng.nextGaussian()));
        return new Arena.Model(parent.family(),w);
    }
    public static void main(String[] args)throws Exception{
        int threads=Integer.parseInt(args[0]),seed=Integer.parseInt(args[1]),generations=Integer.parseInt(args[2]),
            pop=Integer.parseInt(args[3]),pairs=Integer.parseInt(args[4]);
        var family=DuelNetwork.Family.valueOf(args[5]);Path out=Path.of(args[6]);
        if(threads<1||threads>4||pairs<2||pop<8)throw new IllegalArgumentException("Invalid budget");
        List<Path> clocks=new ArrayList<>();for(int i=7;i<args.length;i++)clocks.add(Path.of(args[i]));
        var envs=Arena.envs(clocks,"mmc",true);
        var fixed=Arrays.stream(Arena.SCRIPTED).map(Arena.Fighter::named).toList();
        var rng=new Random(seed);var pool=Executors.newFixedThreadPool(threads);
        var elites=new ArrayList<Arena.Fighter>();var archive=new ArrayList<Arena.Fighter>();
        elites.add(new Arena.Fighter("init",initial(family,rng)));
        long bouts=0;
        Files.createDirectories(out);
        try(var log=Files.newBufferedWriter(out.resolve("training.jsonl"),StandardOpenOption.CREATE_NEW)){
            for(int gen=0;gen<generations;gen++){
                var games=new ArrayList<Arena.Scenario>();
                var opponents=new ArrayList<>(fixed);
                if(!archive.isEmpty())opponents.add(new Arena.Fighter("past-policy",archive.get(rng.nextInt(archive.size())).model()));
                if(!archive.isEmpty())opponents.add(new Arena.Fighter("recent-policy",archive.get(archive.size()-1).model()));
                for(var other:opponents){
                    for(int j=0;j<pairs;j++){
                        var e=envs.get(rng.nextInt(envs.size()/2)*2+(j+gen)%2);
                        games.add(new Arena.Scenario(e,other,true));games.add(new Arena.Scenario(e,other,false));
                    }
                }
                var population=new ArrayList<>(elites);
                while(population.size()<pop){
                    var parent=elites.get(rng.nextInt(elites.size())).model();
                    if(gen==0 || rng.nextDouble()<.04)parent=initial(family,rng);
                    double sigma=new double[]{.12,.31,.62,1.1}[rng.nextInt(4)];
                    population.add(new Arena.Fighter(String.format(Locale.ROOT,"g%03d-%03d",gen,population.size()),mutate(parent,rng,sigma)));
                }
                population.add(WT);population.add(HOLD);
                var scores=rank(population,games,pool);bouts+=(long)population.size()*games.size();
                var winners=scores.stream().filter(s->s.fighter.model()!=null).limit(Math.max(3,pop/6)).toList();
                elites.clear();for(var v:winners)elites.add(v.fighter);
                var best=winners.get(0);
                if(gen%4==0 || gen==generations-1){
                    archive.add(best.fighter);
                    Files.writeString(out.resolve(String.format("g%03d.net",gen)),best.fighter.model().encode()+"\n",StandardOpenOption.CREATE_NEW);
                }
                var record=Map.of("generation",gen,"winner",best.fighter.name(),"scores",scores.stream().map(v->List.of(v.fighter.name(),v.mean,v.worst)).toList(),
                            "games_per_fighter",games.size(),"cumulative_bouts",bouts);
                log.write(Json.encode(record));log.newLine();log.flush();
                System.out.printf("%s %d g%d %.3f worst %.3f %,d bouts%n",family,seed,gen,best.mean,best.worst,bouts);
            }
        }finally{pool.shutdown();}
    }
}
