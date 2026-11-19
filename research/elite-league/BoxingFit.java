import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import lab.DuelNetwork;
import lab.Json;

/** Reward confirmed additional hit-to-hit cycles from the same W-tap prefix. */
final class BoxingFit{
    record Ranked(Arena.Fighter fighter,double reward,double advantage,int wins,int ties,int losses){}
    static Ranked score(Arena.Fighter f,List<BoxingDrill.Checkpoint> starts){
        double reward=0,sum=0;int wins=0,ties=0,losses=0;
        for(var s:starts){
            int wt=s.baseline.cycles(),our=f.model()==null?wt:s.run(f.model(),null).cycles();
            int diff=our-wt;
            reward+=Math.log1p(our)-Math.log1p(wt)+.15*Math.signum(diff);
            sum+=diff;if(diff>0)wins++;else if(diff<0)losses++;else ties++;
        }
        return new Ranked(f,reward/starts.size(),sum/starts.size(),wins,ties,losses);
    }
    public static void main(String[] args)throws Exception{
        var out=Path.of(args[0]);int steps=Integer.parseInt(args[1]),pop=Integer.parseInt(args[2]),draws=Integer.parseInt(args[3]);
        List<BoxingDrill.Checkpoint> starts=new ArrayList<>();int openings=0;
        for(var p:Arrays.stream(args,4,args.length).map(Path::of).toList()){
            var tape=DuelEnv.Tape.read(p,20000);
            for(int[] off:new int[][]{{0,0},{13,31},{37,7},{23,43}}){
                var shifted=BoxingDrill.shift(tape,off[0],off[1]);
                for(int rtt:new int[]{0,80}){
                    var e=new DuelEnv(shifted,"mmc",rtt,false);
                    for(boolean first:new boolean[]{true,false}){openings++;starts.addAll(BoxingDrill.acquire(e,first,false));}
                }
            }
        }
        Files.createDirectories(out);
        System.out.println("Enrolled "+starts.size()+" of "+openings+" openings.");
        var pool=Executors.newFixedThreadPool(2);long suffixes=0;
        try{
            for(var family:List.of(DuelNetwork.Family.LINEAR,DuelNetwork.Family.MLP,DuelNetwork.Family.RECURRENT)){
                var folder=out.resolve(family.name().toLowerCase(Locale.ROOT));Files.createDirectories(folder);
                var rng=new Random(93651+family.ordinal());
                var warm=Arena.Model.decode(Files.readString(Path.of("research/elite-league/frozen/"+family.name().toLowerCase(Locale.ROOT)+".net")).trim());
                var elites=new ArrayList<Arena.Fighter>();elites.add(new Arena.Fighter("sumo",warm));
                try(var log=Files.newBufferedWriter(folder.resolve("training.jsonl"),StandardOpenOption.CREATE_NEW)){
                    for(int gen=0;gen<steps;gen++){
                        var scenarios=new ArrayList<>(starts);Collections.shuffle(scenarios,rng);
                        List<BoxingDrill.Checkpoint> group=scenarios.subList(0,Math.min(draws,scenarios.size()));
                        var fighters=new ArrayList<>(elites);
                        while(fighters.size()<pop){
                            var parent=elites.get(rng.nextInt(elites.size())).model();
                            double prior=rng.nextDouble();if(prior<.08)parent=Evolve.initial(family,rng);else if(prior<.15)parent=warm;
                            fighters.add(new Arena.Fighter(String.format("g%03d-%02d",gen,fighters.size()),
                                Evolve.mutate(parent,rng,new double[]{.14,.38,.85}[rng.nextInt(3)])));
                        }
                        fighters.add(Evolve.WT);
                        var jobs=new ArrayList<Callable<Ranked>>();for(var f:fighters)jobs.add(()->score(f,group));
                        var rank=new ArrayList<Ranked>();for(var p:pool.invokeAll(jobs))rank.add(p.get());
                        rank.sort(Comparator.comparingDouble(Ranked::reward).reversed().thenComparing(r->r.fighter().name()));
                        elites.clear();rank.stream().filter(r->r.fighter.model()!=null).limit(6).forEach(r->elites.add(r.fighter));
                        var champ=elites.get(0);suffixes+=(long)group.size()*pop;
                        if(gen%8==0||gen==steps-1)Files.writeString(folder.resolve(String.format("g%03d.net",gen)),champ.model().encode()+"\n",StandardOpenOption.CREATE_NEW);
                        log.write(Json.encode(Map.of("generation",gen,"starts",group.size(),"wtap",group.stream().mapToInt(c->c.baseline.cycles()).sum(),
                            "cumulative_suffixes_all_families",suffixes,"scores",rank.stream().map(v->List.of(v.fighter.name(),v.reward,v.advantage,v.wins,v.ties,v.losses)).toList())));
                        log.newLine();log.flush();
                    }
                }
            }
        }finally{pool.shutdown();}
    }
}
