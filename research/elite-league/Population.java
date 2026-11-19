import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import lab.DuelNetwork;
import lab.Json;

/** Alternate best-response searches against a growing empirical game. Compute priority from measured
 * wins against each opponent; keep a uniform component so an old weakness cannot disappear from training. */
final class Population {
    record Value(Arena.Fighter fighter,double average,double worst,double score){}
    record Task(Arena.Scenario scenario,int group){}
    static Value play(Arena.Fighter f,List<Task> cases,int groupCount){
        double[] pts=new double[groupCount];int[] count=new int[groupCount];
        for(var c:cases){pts[c.group]+=c.scenario.game(f);count[c.group]++;}
        double avg=0,worst=1;
        for(int i=0;i<pts.length;i++){
            if(count[i]==0)throw new AssertionError("Empty opponent group");
            double mean=pts[i]/count[i];avg+=mean;worst=Math.min(worst,mean);
        }
        avg/=groupCount;
        return new Value(f,avg,worst,.6*avg+.4*worst);
    }
    static List<Value> rank(List<Arena.Fighter> fighters,List<Task> cases,int n,ExecutorService pool)throws Exception{
        var jobs=new ArrayList<Callable<Value>>();for(var f:fighters)jobs.add(()->play(f,cases,n));
        var values=new ArrayList<Value>();for(var future:pool.invokeAll(jobs))values.add(future.get());
        values.sort(Comparator.comparingDouble(Value::score).reversed()
            .thenComparing(Comparator.comparingDouble(Value::average).reversed()).thenComparing(v->v.fighter.name()));
        return values;
    }
    static List<Task> cases(RobustField field,List<List<Arena.Fighter>> groups,Map<String,Double> difficulty,int perGroup,Random rng){
        var cases=new ArrayList<Task>();int index=0;
        for(var group:groups){
            double[] cumulative=new double[group.size()];double total=0;
            for(int i=0;i<group.size();i++){total+=.3+difficulty.getOrDefault(group.get(i).name(),.5);cumulative[i]=total;}
            for(int i=0;i<perGroup;i++){
                double v=rng.nextDouble()*total;int o=0;
                while(o+1<group.size()&&v>cumulative[o])o++;
                var domains=rng.nextDouble()<.28?field.shifted:field.standard;
                DuelEnv env=domains.get(rng.nextInt(domains.size())).env();
                for(boolean seat:new boolean[]{true,false})cases.add(new Task(new Arena.Scenario(env,group.get(o),seat),index));
            }
            index++;
        }
        return cases;
    }
    static Map<String,Double> priority(Arena.Fighter champion,List<List<Arena.Fighter>> groups,RobustField field,Random rng){
        var map=new HashMap<String,Double>();
        for(var group:groups)for(var other:group){
            double points=0;
            for(int i=0;i<4;i++){
                var env=field.standard.get(rng.nextInt(field.standard.size())).env();
                for(boolean seat:new boolean[]{true,false})points+=new Arena.Scenario(env,other,seat).game(champion);
            }
            map.put(other.name(),1-points/8);
        }
        return map;
    }
    static Arena.Model recombine(Arena.Model a,Arena.Model b,Random rng){
        float[] w=a.weights().clone();double mix=rng.nextDouble()*.45;
        for(int i=0;i<w.length;i++)w[i]=(float)(w[i]*(1-mix)+b.weights()[i]*mix);
        return new Arena.Model(a.family(),w);
    }
    public static void main(String[] args)throws Exception{
        Path out=Path.of(args[0]),frozen=Path.of(args[1]),response=Path.of(args[2]);
        int season=Integer.parseInt(args[3]),generations=Integer.parseInt(args[4]),pop=Integer.parseInt(args[5]);
        int seed=Integer.parseInt(args[6]),pairs=Integer.parseInt(args[7]);
        var field=new RobustField(Arrays.stream(args,9,args.length).map(Path::of).toList(),frozen,response);
        var rng=new Random(seed);var pool=Executors.newFixedThreadPool(2);
        var history=new ArrayList<Arena.Fighter>();var sources=Matches.roster(Path.of(args[8]));
        for(var s:sources)if(s.model()!=null)history.add(s);
        Files.createDirectories(out);long bouts=0;
        try{
            for(int epoch=0;epoch<season;epoch++){
                var family=new DuelNetwork.Family[]{DuelNetwork.Family.RECURRENT,DuelNetwork.Family.HYBRID,
                    DuelNetwork.Family.MLP,DuelNetwork.Family.LINEAR}[epoch%4];
                var warm=RobustField.load("v1-"+family.name().toLowerCase(Locale.ROOT),frozen.resolve(family.name().toLowerCase(Locale.ROOT)+".net"));
                var groups=new ArrayList<List<Arena.Fighter>>(field.groups);
                if(!history.isEmpty())groups.add(List.copyOf(history));
                var elites=new ArrayList<Arena.Fighter>();elites.add(warm);
                for(var old:history)if(old.model().family()==family)elites.add(old);
                if(family==DuelNetwork.Family.MLP)elites.add(field.groups.get(3).get(0));
                if(family==DuelNetwork.Family.RECURRENT)elites.add(field.groups.get(3).get(1));
                var folder=out.resolve(String.format("epoch%02d-%s",epoch,family.name().toLowerCase(Locale.ROOT)));
                Files.createDirectories(folder);var contenders=new ArrayList<Arena.Fighter>();
                Map<String,Double> difficulty=Map.of();
                try(var log=Files.newBufferedWriter(folder.resolve("training.jsonl"),StandardOpenOption.CREATE_NEW)){
                    for(int gen=0;gen<generations;gen++){
                        if(gen%16==0){
                            difficulty=priority(elites.get(0),groups,field,rng);
                            bouts+=8L*groups.stream().mapToInt(List::size).sum();
                        }
                        var fixtures=cases(field,groups,difficulty,pairs,rng);var fighters=new ArrayList<>(elites);
                        while(fighters.size()<pop){
                            var parent=elites.get(rng.nextInt(elites.size())).model();
                            double choice=rng.nextDouble();
                            if(choice<.06)parent=Evolve.initial(family,rng);
                            else if(choice<.18&&elites.size()>1)parent=recombine(parent,elites.get(rng.nextInt(elites.size())).model(),rng);
                            var child=Evolve.mutate(parent,rng,new double[]{.10,.22,.4,.8,1.2}[rng.nextInt(5)]);
                            fighters.add(new Arena.Fighter(String.format("e%02d-g%03d-%02d",epoch,gen,fighters.size()),child));
                        }
                        fighters.addAll(List.of(Evolve.WT,Evolve.HOLD));
                        var rank=rank(fighters,fixtures,groups.size(),pool);bouts+=(long)fixtures.size()*fighters.size();
                        elites.clear();rank.stream().filter(r->r.fighter.model()!=null).limit(pop/6).forEach(r->elites.add(r.fighter));
                        var champ=elites.get(0);
                        if(gen%8==0||gen==generations-1){
                            String name=String.format("e%02d-g%03d",epoch,gen);champ=new Arena.Fighter(name,champ.model());contenders.add(champ);
                            Files.writeString(folder.resolve(name+".net"),champ.model().encode()+"\n",StandardOpenOption.CREATE_NEW);
                        }
                        log.write(Json.encode(Map.of("generation",gen,"cumulative_bouts",bouts,"matches_per_arm",fixtures.size(),
                            "scores",rank.stream().map(v->List.of(v.fighter.name(),v.average,v.worst)).toList())));log.newLine();log.flush();
                    }
                }
                var checks=cases(field,groups,Map.of(),32,rng);contenders.addAll(List.of(Evolve.WT,Evolve.HOLD));
                var finalRank=rank(contenders,checks,groups.size(),pool);
                var champ=finalRank.stream().filter(v->v.fighter.model()!=null).findFirst().orElseThrow().fighter;
                bouts+=(long)checks.size()*contenders.size();history.add(champ);
                Files.writeString(folder.resolve("stage-selection.json"),Json.encode(Map.of("winner",champ.name(),"bouts",bouts,
                    "scores",finalRank.stream().map(v->List.of(v.fighter.name(),v.average,v.worst)).toList())),StandardOpenOption.CREATE_NEW);
                System.out.printf("population epoch %d %s selected %s, %,d cumulative bouts%n",epoch,family,champ.name(),bouts);
            }
        }finally{pool.shutdown();}
    }
}
