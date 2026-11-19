import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import lab.DuelNetwork;
import lab.Json;

/** Targeted adversarial training. Opening tempo variations are training-only and matched between arms. */
final class BestResponse {
    static final class Opening extends DuelEnv.Player{
        final DuelEnv.Player controller;
        final int delay,side;
        Opening(DuelEnv.Player p,int delay,int side){super("opening",0,p.rtt,0);controller=p;this.delay=delay;this.side=side;}
        @Override public PeriodicComboClock.Control input(PeriodicComboClock.Observation o){
            if(o.time()<delay){
                // Prime a recurrent opponent with the actual opening it executed.
                var c=new PeriodicComboClock.Control(side==0?0:1,.98f*side,false,false);
                if(controller instanceof Arena.Neural n){
                    n.input(o);n.net.applied(DuelNetwork.pack(c.key(),side,false,false));
                }
                return c;
            }
            return controller.input(o);
        }
        @Override public void received(PeriodicComboClock.PacketObservation p){controller.received(p);}
        @Override public boolean injected(PeriodicComboClock.Observation o,double t){return controller.injected(o,t);}
    }
    record Case(DuelEnv e,Arena.Fighter opponent,boolean first,int delay,int side){
        double play(Arena.Fighter f){
            var other=opponent.create(e.rtt,0);
            if(delay>0)other=new Opening(other,delay,side);
            var g=e.new Game(first,f.create(e.rtt,0),other);g.finish();return g.reward();
        }
    }
    static double reward(Arena.Fighter f,List<Case> group){
        double total=0;for(var c:group)total+=c.play(f);return total/group.size();
    }
    public static void main(String[] args)throws Exception{
        Path out=Path.of(args[0]);DuelNetwork.Family family=DuelNetwork.Family.valueOf(args[1]);
        int seed=Integer.parseInt(args[2]),steps=Integer.parseInt(args[3]),pop=Integer.parseInt(args[4]);
        int pairs=Integer.parseInt(args[5]),threads=Integer.parseInt(args[6]);
        if(threads<1||threads>4)throw new IllegalArgumentException("Invalid parallelism");
        var target=(args[7].equals("wtap")||args[7].equals("hold"))?Arena.Fighter.named(args[7]):new Arena.Fighter("target",Arena.Model.decode(Files.readString(Path.of(args[7])).trim()));
        var warm=Arena.Model.decode(Files.readString(Path.of(args[8])).trim());
        if(warm.family()!=family)throw new IllegalArgumentException("Warm start has wrong architecture");
        var envs=Arena.envs(Arrays.stream(args,9,args.length).map(Path::of).toList(),"mmc",true);
        var rng=new Random(seed);var pool=Executors.newFixedThreadPool(threads);
        var elites=new ArrayList<Arena.Fighter>();elites.add(new Arena.Fighter("warm",warm));
        Files.createDirectories(out);long bouts=0;
        try(var history=Files.newBufferedWriter(out.resolve("training.jsonl"),StandardOpenOption.CREATE_NEW)){
            for(int gen=0;gen<steps;gen++){
                var cases=new ArrayList<Case>();
                for(int i=0;i<pairs;i++){
                    var e=envs.get(2*rng.nextInt(envs.size()/2)+(i+gen)%2);
                    int delay=i%4<2?0:new int[]{100,150,250,350}[rng.nextInt(4)];
                    int side=i%4==2?0:rng.nextBoolean()?1:-1;
                    for(boolean first:new boolean[]{true,false})cases.add(new Case(e,target,first,delay,side));
                }
                var group=new ArrayList<>(elites);
                while(group.size()<pop){
                    var parent=elites.get(rng.nextInt(elites.size())).model();
                    double origin=rng.nextDouble();
                    if(origin<.06)parent=Evolve.initial(family,rng);
                    else if(origin<.14)parent=warm;
                    double sigma=new double[]{.13,.32,.65,1.25}[rng.nextInt(4)];
                    group.add(new Arena.Fighter(String.format("g%04d-%03d",gen,group.size()),Evolve.mutate(parent,rng,sigma)));
                }
                group.add(Evolve.WT);group.add(Evolve.HOLD);
                var tasks=new ArrayList<Callable<Evolve.Result>>();
                for(var f:group)tasks.add(()->new Evolve.Result(f,reward(f,cases),0));
                var result=new ArrayList<Evolve.Result>();for(var p:pool.invokeAll(tasks))result.add(p.get());
                result.sort(Evolve.ORDER);
                elites.clear();
                result.stream().filter(r->r.fighter().model()!=null).limit(pop/7).forEach(r->elites.add(r.fighter()));
                var best=elites.get(0);bouts+=(long)group.size()*cases.size();
                if(gen%8==0||gen==steps-1)Files.writeString(out.resolve(String.format("g%03d.net",gen)),best.model().encode()+"\n",StandardOpenOption.CREATE_NEW);
                history.write(Json.encode(Map.of("generation",gen,"cumulative_bouts",bouts,"winner",best.name(),
                    "scores",result.stream().map(r->List.of(r.fighter().name(),r.mean())).toList())));history.newLine();history.flush();
                if(gen%8==0)System.out.printf("%s %d %s %d / %d %,d bouts%n",family,seed,target.name(),gen,steps,bouts);
            }
        }finally{pool.shutdown();}
    }
}
