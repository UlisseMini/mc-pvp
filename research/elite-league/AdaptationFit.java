import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import lab.DuelNetwork;
import lab.Json;
import lab.PairedExperts;

/** Robust training on named individual opponents, including mid-fight strategy changes. */
final class AdaptationFit {
    static List<Arena.Scenario> fixtures(List<DuelEnv> envs,List<Arena.Fighter> field,double[] miss,Random rng){
        var switchers=field.stream().filter(f->f.name().startsWith("shift-train-")).toList();
        var out=new ArrayList<Arena.Scenario>();
        for(int i=0;i<12;i++){
            Arena.Fighter opponent;
            if(i<6){
                double draw=rng.nextDouble()*Arrays.stream(miss).sum();int j=0;
                while(j+1<miss.length && (draw-=miss[j])>0)j++;
                opponent=field.get(j);
            }else if(i<9)opponent=switchers.get(rng.nextInt(switchers.size()));
            else opponent=field.get(rng.nextInt(field.size()));
            DuelEnv e=envs.get(rng.nextInt(envs.size()));
            for(boolean seat:new boolean[]{true,false})out.add(new Arena.Scenario(e,opponent,seat));
        }
        return out;
    }
    record Score(Arena.Fighter fighter,double mean,double tail,double worst){
        double merit(){return (mean+tail+worst)/3;}
    }
    static Score score(Arena.Fighter fighter,List<Arena.Scenario> games){
        double[] cells=new double[games.size()/2];
        for(int i=0;i<games.size();i++)cells[i/2]+=.5*games.get(i).game(fighter);
        Arrays.sort(cells);
        return new Score(fighter,Arrays.stream(cells).average().orElseThrow(),Arrays.stream(cells,0,5).average().orElseThrow(),cells[0]);
    }
    static Arena.Model mutatePair(Arena.Model p,List<Arena.Fighter> warm,Random rng){
        float[] w=p.weights().clone();
        if(rng.nextDouble()<.1){
            var bases=warm.get(rng.nextInt(warm.size())).model().weights();
            w[1]=bases[1];w[2]=bases[2];int at=PairedExperts.HEADER+PairedExperts.GATE;
            System.arraycopy(bases,at,w,at,w.length-at);
        }
        double std=new double[]{.06,.2,.6,1.3}[rng.nextInt(4)];
        for(int i=PairedExperts.HEADER;i<PairedExperts.HEADER+PairedExperts.GATE;i++)
            if(rng.nextDouble()<.45)w[i]+=rng.nextGaussian()*std;
        if(rng.nextBoolean())w[6]=(float)Math.max(1,Math.min(20,w[6]+rng.nextGaussian()*3));
        if(rng.nextBoolean())w[7]=(float)Math.max(0,Math.min(2,w[7]+rng.nextGaussian()*.18));
        return new Arena.Model(DuelNetwork.Family.PAIR,w);
    }
    public static void main(String[] args)throws Exception{
        Path out=Path.of(args[0]);Files.createDirectories(out);
        int seed=Integer.parseInt(args[1]);
        var field=Matches.roster(Path.of(args[2]));var warm=Matches.roster(Path.of(args[3]));
        var envs=Arena.envs(Arrays.stream(args,4,args.length).map(Path::of).toList(),"mmc",true);
        for(var e:envs)e.initial.timing.mouseInput=true;
        var pairs=warm.stream().filter(f->f.model().family()==DuelNetwork.Family.PAIR).toList();
        if(pairs.isEmpty() || warm.stream().noneMatch(f->f.model().family()==DuelNetwork.Family.RECURRENT))
            throw new IllegalArgumentException("Both adaptation tracks require a starting policy");
        var rng=new Random(seed);double[] miss=new double[field.size()];Arrays.fill(miss,1);
        var elites=new ArrayList<>(warm);var pool=Executors.newFixedThreadPool(3);long bouts=0;
        try(var log=Files.newBufferedWriter(out.resolve("training.jsonl"),StandardOpenOption.CREATE_NEW)){
            for(int gen=0;gen<64;gen++){
                if(gen%8==0){
                    var policy=elites.get(0);
                    for(int j=0;j<field.size();j++){
                        double points=0;
                        for(int k=0;k<2;k++){
                            var e=envs.get(rng.nextInt(envs.size()));
                            for(boolean seat:new boolean[]{true,false})points+=new Arena.Scenario(e,field.get(j),seat).game(policy);
                        }
                        miss[j]=.12+Math.pow(1-points/4,2);bouts+=4;
                    }
                }
                var games=fixtures(envs,field,miss,rng);var entrants=new ArrayList<Arena.Fighter>();
                for(var family:List.of(DuelNetwork.Family.PAIR,DuelNetwork.Family.RECURRENT)){
                    var parents=elites.stream().filter(f->f.model().family()==family).toList();
                    var proposed=new ArrayList<>(parents);
                    while(proposed.size()<32){
                        var parent=parents.get(rng.nextInt(parents.size())).model();
                        var model=family==DuelNetwork.Family.PAIR?mutatePair(parent,pairs,rng):
                                Evolve.mutate(parent,rng,new double[]{.08,.19,.36,.65}[rng.nextInt(4)]);
                        proposed.add(new Arena.Fighter("g"+gen+"-"+family+"-"+proposed.size(),model));
                    }
                    entrants.addAll(proposed);
                }
                entrants.add(Evolve.WT);entrants.add(Evolve.HOLD);var jobs=new ArrayList<Callable<Score>>();
                for(var f:entrants)jobs.add(()->score(f,games));
                var scores=new ArrayList<Score>();for(var result:pool.invokeAll(jobs))scores.add(result.get());
                bouts+=(long)games.size()*entrants.size();scores.sort(Comparator.comparingDouble(Score::merit).reversed()
                        .thenComparing(Comparator.comparingDouble(Score::mean).reversed()).thenComparing(v->v.fighter().name()));
                elites.clear();
                for(var family:List.of(DuelNetwork.Family.PAIR,DuelNetwork.Family.RECURRENT)){
                    var chosen=scores.stream().map(Score::fighter).filter(f->f.model()!=null&&f.model().family()==family).limit(5).toList();
                    elites.addAll(chosen);
                    if(gen%8==0 || gen==63)Files.writeString(out.resolve(String.format("%s-g%02d.net",family.name().toLowerCase(Locale.ROOT),gen)),chosen.get(0).model().encode()+"\n");
                }
                // The next weakness survey follows the best of either track.
                var best=scores.stream().map(Score::fighter).filter(f->f.model()!=null).findFirst().orElseThrow();
                elites.remove(best);elites.add(0,best);
                log.write(Json.encode(Map.of("generation",gen,"bouts",bouts,"field",field.size(),
                        "scores",scores.stream().map(v->List.of(v.fighter().name(),v.mean(),v.tail(),v.worst())).toList())));log.newLine();log.flush();
            }
        }finally{pool.shutdown();}
    }
}
