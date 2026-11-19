import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import lab.Json;

/** Compare learning under measured wire jitter to the identical tournament with fixed transport. */
final class RecordedTransportLeague {
    static final class Wire implements PeriodicComboFightModel.PacketTiming {
        final double[][] quantiles;
        final long seed;
        final double[] last = new double[4];
        Wire(double[][] quantiles, long seed) { this.quantiles=quantiles; this.seed=seed; }
        public double arrival(int route, double sent, double half) {
            // The seed, time and lane are exogenous. Both agents in every counterfactual see the same link.
            long block=(long)Math.floor(sent/50);
            long bits=seed+0x9e3779b97f4a7c15L*block+0x632be59bd9b4e019L*route;
            bits=(bits^(bits>>>30))*0xbf58476d1ce4e5b9L;
            bits=(bits^(bits>>>27))*0x94d049bb133111ebL;
            double u=((bits^(bits>>>31))>>>11)*0x1.0p-53;
            double[] q=quantiles[route];
            double v=u<=.1?q[0]:u<=.5?lerp(q[0],q[1],(u-.1)/.4):u<=.9?lerp(q[1],q[2],(u-.5)/.4):
                    lerp(q[2],q[3],Math.min(1,(u-.9)/.09));
            return last[route]=Math.max(last[route],sent+half+v);
        }
        static double lerp(double a,double b,double f){return a+(b-a)*f;}
        public Wire deepCopy(){var w=new Wire(quantiles,seed);System.arraycopy(last,0,w.last,0,4);return w;}
    }
    record Donor(int rtt,double[][] quantiles){}
    static List<Donor> read(Path path)throws Exception {
        var donors=new ArrayList<Donor>();
        for(String line:Files.readAllLines(path)){
            String[] w=line.split("\\s+");if(w.length!=17)throw new IllegalArgumentException("Expected four observed percentiles per route");
            int rtt=Integer.parseInt(w[0]);var table=new double[4][4];
            for(int lane=0;lane<4;lane++)for(int i=0;i<4;i++){
                double ms=Double.parseDouble(w[1+4*lane+i])-rtt/2.;
                if(!Double.isFinite(ms)||ms<0||(i>0&&ms<table[lane][i-1]))throw new IllegalArgumentException("Invalid residual");
                table[lane][i]=ms;
            }
            donors.add(new Donor(rtt,table));
        }
        return donors;
    }
    static void jitter(DuelEnv env,List<Donor> donors,Random rng){
        var group=donors.stream().filter(d->d.rtt==env.rtt).toList();
        var donor=group.get(rng.nextInt(group.size()));
        env.initial.state.extraLatency=new double[4];
        env.initial.state.packetTiming=new Wire(donor.quantiles,rng.nextLong());
    }
    record Rating(Arena.Fighter fighter,double mean,double bottom,double weakest) {
        double merit(){return .4*mean+.4*bottom+.2*weakest;}
    }
    static Rating rate(Arena.Fighter f,List<Arena.Scenario> games){
        Map<String,double[]> opponents=new TreeMap<>(),cells=new TreeMap<>();double total=0;
        for(var s:games){
            double value=s.game(f);total+=value;String n=s.opponent().name();
            for(var c:List.of(opponents.computeIfAbsent(n,k->new double[2]),cells.computeIfAbsent(n+s.first()+s.env().rtt,k->new double[2]))){c[0]+=value;c[1]++;}
        }
        var sorted=cells.values().stream().mapToDouble(c->c[0]/c[1]).sorted().toArray();
        double bottom=Arrays.stream(sorted,0,Math.max(1,sorted.length/4)).average().orElseThrow();
        return new Rating(f,total/games.size(),bottom,opponents.values().stream().mapToDouble(c->c[0]/c[1]).min().orElseThrow());
    }
    static List<Rating> rank(ExecutorService pool,List<Arena.Fighter> arms,List<Arena.Scenario> cases)throws Exception{
        var jobs=new ArrayList<Callable<Rating>>();for(var f:arms)jobs.add(()->rate(f,cases));
        var ranked=new ArrayList<Rating>();for(var job:pool.invokeAll(jobs))ranked.add(job.get());
        ranked.sort(Comparator.comparingDouble(Rating::merit).reversed().thenComparing(Comparator.comparingDouble(Rating::mean).reversed()).thenComparing(r->r.fighter.name()));
        return ranked;
    }
    static Arena.Model mutateSprint(Arena.Model parent,Random rng,double sigma){
        if(parent.family()!=lab.DuelNetwork.Family.MLP)throw new IllegalArgumentException("Expected the frozen PPO MLP");
        int block=lab.DuelNetwork.HIDDEN+1;
        int start=parent.weights().length-(lab.DuelNetwork.OUTPUTS-1)*block;
        float[] weights=parent.weights().clone();
        int required=rng.nextInt(block);double fraction=rng.nextBoolean()?.18:.7;
        for(int i=0;i<block;i++)if(i==required || rng.nextDouble()<fraction)
            weights[start+i]=(float)Math.max(-6,Math.min(6,weights[start+i]+rng.nextGaussian()*sigma));
        return new Arena.Model(parent.family(),weights);
    }
    static void train(Path folder,List<DuelEnv> basic,List<DuelEnv> varied,List<Arena.Fighter> opponents,Arena.Fighter source,boolean sprintOnly)throws Exception{
        var draw=new Random(783163);var pool=Executors.newFixedThreadPool(3);
        var rng=List.of(new Random(587301),new Random(587301));
        var tracks=List.of(new ArrayList<>(List.of(source)),new ArrayList<>(List.of(source)));
        try(var out=Files.newBufferedWriter(folder.resolve("training.jsonl"),StandardOpenOption.CREATE_NEW)){
            for(int gen=0;gen<80;gen++){
                var panels=List.of(new ArrayList<Arena.Scenario>(),new ArrayList<Arena.Scenario>());
                for(var foe:opponents)for(int ping=0;ping<2;ping++)for(int j=0;j<2;j++){
                    int index=2*draw.nextInt(basic.size()/2)+ping;
                    for(boolean seat:List.of(true,false)){
                        panels.get(0).add(new Arena.Scenario(basic.get(index),foe,seat));
                        panels.get(1).add(new Arena.Scenario(j==0?basic.get(index):varied.get(index),foe,seat));
                    }
                }
                for(int track=0;track<2;track++){
                    var elite=tracks.get(track);var entrants=new ArrayList<>(elite);
                    if(entrants.stream().noneMatch(f->Arrays.equals(f.model().weights(),source.model().weights())))entrants.add(source);
                    while(entrants.size()<20){
                        var random=rng.get(track);var parent=elite.get(random.nextInt(elite.size()));
                        double scale=new double[]{.04,.12,.3,.65}[entrants.size()%4];
                        var child=sprintOnly?mutateSprint(parent.model(),random,scale):Evolve.mutate(parent.model(),random,scale);
                        entrants.add(new Arena.Fighter("child-"+gen+"-"+entrants.size(),child));
                    }
                    var ranks=rank(pool,entrants,panels.get(track));var wt=rate(Evolve.WT,panels.get(track));
                    elite.clear();for(var r:ranks){
                        if(elite.stream().noneMatch(f->Arrays.equals(f.model().weights(),r.fighter.model().weights())))elite.add(r.fighter);
                        if(elite.size()==4)break;
                    }
                    var best=ranks.get(0);String tag=track==1?"jitter":"fixed";
                    if(gen%8==7)Files.writeString(folder.resolve(tag+"-"+gen+".net"),best.fighter.model().encode()+"\n",StandardOpenOption.CREATE_NEW);
                    out.write(Json.encode(Map.of("generation",gen,"track",tag,"mean",best.mean,"worst",best.weakest,
                        "bottom_quartile",best.bottom,"wtap_mean",wt.mean,"wtap_worst",wt.weakest,"bouts",21*panels.get(track).size())));out.newLine();out.flush();
                }
            }
        }finally{pool.shutdown();}
    }
    static void score(List<Arena.Fighter> arms,List<Arena.Fighter> foes,List<DuelEnv> basic,List<DuelEnv> varied)throws Exception{
        var pool=Executors.newFixedThreadPool(3);
        try{for(var arm:arms){
            var jobs=new ArrayList<Callable<String>>();
            for(int i=0;i<basic.size();i++)for(var mode:List.of("fixed","jitter")){
                var env=(mode.equals("fixed")?basic:varied).get(i);
                for(var rival:foes)for(boolean seat:List.of(true,false))jobs.add(()->{
                    var game=new Arena.Scenario(env,rival,seat).round(arm);
                    return Json.encode(Map.of("arm",arm.name(),"opponent",rival.name(),"clock",env.clockId,"rtt",env.rtt,
                        "side",seat?"a":"b","transport",mode,"score",game.reward(),"end_ms",game.clock.time()));
                });
            }
            for(var job:pool.invokeAll(jobs))System.out.println(job.get());
        }}finally{pool.shutdown();}
    }
    public static void main(String[] args)throws Exception{
        var donors=read(Path.of(args[1]));var foes=Matches.roster(Path.of(args[2]));var arms=Matches.roster(Path.of(args[3]));
        var tapes=Arrays.stream(args,5,args.length).map(Path::of).toList();
        String profile=args[0].endsWith("-range")?"mmc-range":"mmc-recorded";
        String operation=args[0].replaceFirst("-range$","");
        var fixed=Arena.envs(tapes,profile,true);var varied=Arena.envs(tapes,profile,true);
        var seed=new Random(operation.equals("fit")?179613:627715);
        for(var env:fixed)env.initial.timing.mouseInput=true;
        for(var env:varied){env.initial.timing.mouseInput=true;jitter(env,donors,seed);}
        if(operation.equals("fit")||operation.equals("fit-sprint"))train(Path.of(args[4]),fixed,varied,foes,arms.get(0),operation.equals("fit-sprint"));
        else if(operation.equals("score"))score(arms,foes,fixed,varied);
        else throw new IllegalArgumentException("Use fit or score");
    }
}
