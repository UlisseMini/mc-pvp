import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import lab.DuelNetwork;
import lab.HumanTiming;
import lab.Json;

/** Paired searches with the same initialization, clocks, seats and mutation draws.
 * The intervention changes only the opponent used in the final two fixture groups. */
final class HumanChallenge {
    static final Set<Integer> RESERVED=Set.of(10103,30307,50111,70201,151121,221077,351019,448043);
    static Arena.Fighter variant(Arena.Model source,int seed,boolean pressure){
        float[] w=source.weights().clone();
        w[HumanTiming.PARAMETERS]=seed;w[HumanTiming.PARAMETERS+1]=pressure?1:0;
        return new Arena.Fighter(pressure?"human-pressure":"human-paced",new Arena.Model(source.family(),w));
    }

    static List<Population.Task> fixtures(RobustField field,Arena.Model clone,List<Arena.Fighter> reference,
                                          boolean useHuman,int seed,int generation){
        Random fixture=new Random(0x6e122f12231L+seed*117731L+generation);
        var cases=new ArrayList<Population.Task>();
        var groups=new ArrayList<>(field.groups);
        groups.add(reference);
        for(int g=0;g<7;g++){
            int repetitions=g<5?2:3;
            for(int rep=0;rep<repetitions;rep++){
                var pool=g<5?groups.get(g):groups.get(fixture.nextInt(groups.size()));
                var opponent=pool.get(fixture.nextInt(pool.size()));
                int stream;
                do{stream=fixture.nextInt(1<<23)+1;}while(RESERVED.contains(stream));
                if(g>=5 && useHuman)opponent=variant(clone,stream,g==6);
                var domains=fixture.nextDouble()<.28?field.shifted:field.standard;
                var env=domains.get(fixture.nextInt(domains.size())).env();
                for(boolean seat:new boolean[]{true,false})
                    cases.add(new Population.Task(new Arena.Scenario(env,opponent,seat),g));
            }
        }
        return cases;
    }

    public static void main(String[] args)throws Exception{
        Path dest=Path.of(args[0]);var warm=RobustField.load("warm",Path.of(args[1]));
        var clone=RobustField.load("demonstrator",Path.of(args[2])).model();
        boolean useHuman=Integer.parseInt(args[3])!=0;
        int seed=Integer.parseInt(args[4]),steps=Integer.parseInt(args[5]),pop=Integer.parseInt(args[6]);
        Path here=Path.of("research/elite-league");
        var field=new RobustField(Arrays.stream(args,7,args.length).map(Path::of).toList(),here.resolve("frozen"),here.resolve("challenge-frozen"));
        var reference=RobustField.load("reference",here.resolve("robust-frozen/champion.net"));
        var ppo=RobustField.load("ppo",here.resolve("robust-frozen/ppo.net"));
        Files.createDirectories(dest);
        var elites=new ArrayList<Arena.Fighter>();elites.add(warm);
        var pool=Executors.newFixedThreadPool(3);
        long bouts=0;
        try(var log=Files.newBufferedWriter(dest.resolve("training.jsonl"),StandardOpenOption.CREATE_NEW)){
            for(int gen=0;gen<steps;gen++){
                var cases=fixtures(field,clone,List.of(reference,ppo),useHuman,seed,gen);
                var rng=new Random(seed*137709L+gen+0x7253d42eL);
                var group=new ArrayList<>(elites);
                while(group.size()<pop){
                    var parent=elites.get(rng.nextInt(elites.size())).model();
                    double choice=rng.nextDouble();
                    if(choice<.10)parent=warm.model();
                    else if(choice<.17 && elites.size()>1)parent=Population.recombine(parent,elites.get(rng.nextInt(elites.size())).model(),rng);
                    double sigma=new double[]{.04,.10,.22,.42,.85}[rng.nextInt(5)];
                    group.add(new Arena.Fighter(String.format("g%03d-%02d",gen,group.size()),Evolve.mutate(parent,rng,sigma)));
                }
                group.addAll(List.of(Evolve.WT,Evolve.HOLD,reference));
                var ranked=Population.rank(group,cases,7,pool);
                elites.clear();ranked.stream().filter(v->v.fighter().model()!=null && v.fighter()!=reference)
                    .limit(pop/6).forEach(v->elites.add(v.fighter()));
                var champion=elites.get(0);
                if(gen%8==0 || gen==steps-1)
                    Files.writeString(dest.resolve(String.format("g%03d.net",gen)),champion.model().encode()+"\n",StandardOpenOption.CREATE_NEW);
                bouts+=(long)cases.size()*group.size();
                log.write(Json.encode(Map.of("generation",gen,"bouts",bouts,"scores",ranked.stream()
                        .map(v->List.of(v.fighter().name(),v.average(),v.worst())).toList())));log.newLine();log.flush();
                if(gen%32==0)System.out.printf("%s human=%s g%d %,d bouts%n",dest.getFileName(),useHuman,gen,bouts);
            }
        }finally{pool.shutdown();}
    }
}
