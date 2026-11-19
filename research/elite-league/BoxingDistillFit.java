import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import lab.DuelNetwork;
import lab.Json;

/** Refine four independently selected teacher-initialized policies with paired head-to-head boxing returns. */
final class BoxingDistillFit {
    static List<BoxingDrill.Checkpoint> starts(List<Path> paths,boolean shifted)throws Exception{
        var data=new ArrayList<BoxingDrill.Checkpoint>();
        for(var p:paths){
            var tape=DuelEnv.Tape.read(p,20000);
            for(int[] offset:shifted?new int[][]{{0,0},{13,31},{37,7},{23,43}}:new int[][]{{0,0}}){
                var t=BoxingDrill.shift(tape,offset[0],offset[1]);
                for(int ping:new int[]{0,80})for(boolean seat:new boolean[]{true,false})
                    data.addAll(BoxingDrill.acquire(new DuelEnv(t,"mmc",ping,false),seat,false));
            }
        }
        return data;
    }
    static List<BoxingFit.Ranked> score(List<Arena.Fighter> fighters,List<BoxingDrill.Checkpoint> starts,ExecutorService pool)throws Exception{
        var jobs=new ArrayList<Callable<BoxingFit.Ranked>>();for(var f:fighters)jobs.add(()->BoxingFit.score(f,starts));
        var all=new ArrayList<BoxingFit.Ranked>();for(var f:pool.invokeAll(jobs))all.add(f.get());
        all.sort(Comparator.comparingDouble(BoxingFit.Ranked::reward).reversed().thenComparing(r->r.fighter().name()));return all;
    }
    public static void main(String[] args)throws Exception{
        Path directory=Path.of(args[1]);Files.createDirectories(directory);var students=Matches.roster(Path.of(args[0]));
        var data=starts(Arrays.stream(args,2,args.length).map(Path::of).toList(),true);
        var pool=Executors.newFixedThreadPool(3);var rng=new Random(3705559);long tested=0;
        try(var log=Files.newBufferedWriter(directory.resolve("iterations.jsonl"),StandardOpenOption.CREATE_NEW)){
            var baselines=List.of(Evolve.WT,RobustField.load("old-specialist",Path.of("research/elite-league/boxing-frozen/specialist.net")));
            var initial=score(students,data,pool);var elites=new ArrayList<Arena.Fighter>();
            initial.stream().limit(4).forEach(r->elites.add(r.fighter()));
            for(var f:elites)Files.writeString(directory.resolve(f.name()+".net"),f.model().encode()+"\n");
            for(int gen=0;gen<96;gen++){
                var sampled=new ArrayList<>(data);Collections.shuffle(sampled,rng);
                var group=sampled.subList(0,Math.min(40,data.size()));
                var candidates=new ArrayList<>(elites);
                while(candidates.size()<32){
                    var parent=elites.get(rng.nextInt(elites.size())).model();
                    if(rng.nextDouble()<.14)parent=initial.get(rng.nextInt(Math.min(24,initial.size()))).fighter().model();
                    var changed=Evolve.mutate(parent,rng,new double[]{.03,.09,.18,.38,.75}[rng.nextInt(5)]);
                    candidates.add(new Arena.Fighter("g"+gen+"-"+candidates.size(),changed));
                }
                candidates.addAll(baselines);
                var ranked=score(candidates,group,pool);
                elites.clear();
                ranked.stream().filter(r->r.fighter().model()!=null && !r.fighter().name().equals("old-specialist")).limit(5).forEach(r->elites.add(r.fighter()));
                tested+=(long)candidates.size()*group.size();
                if(gen%4==0||gen==95)Files.writeString(directory.resolve(String.format("search-%03d.net",gen)),elites.get(0).model().encode()+"\n");
                log.write(Json.encode(Map.of("gen",gen,"cumulative_suffixes",tested,"wtap",group.stream().mapToInt(c->c.baseline.cycles()).sum(),
                    "scores",ranked.stream().map(r->List.of(r.fighter().name(),r.reward(),r.advantage(),r.wins(),r.ties(),r.losses())).toList())));
                log.newLine();
            }
        }finally{pool.shutdown();}
    }
}
