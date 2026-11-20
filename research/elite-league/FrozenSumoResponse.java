import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import lab.DuelNetwork;

/** Best-response learning against one frozen opponent in the current range-calibrated arena. */
final class FrozenSumoResponse {
    static final Path D=Path.of("research/elite-league/sumo-confidence"), F=D.resolve("specialist");
    static final List<DuelEnv> train=new ArrayList<>(), dev=new ArrayList<>();
    static final int[][] LINKS={{0,0},{80,80},{31,109},{109,31},{15,65},{65,15},{40,135},{135,40}};
    static Arena.Fighter target;
    static void load() throws IOException {
        target=InputStudy.fighter("v4-ppo-g0496",D.resolve("frozen/v4-ppo-g0496.net"));
        for(String line:Files.readAllLines(InputStudy.PREV.resolve("clocks.tsv"))){
            String[] v=line.split("\t");
            if(v[0].equals("train")||v[0].equals("dev"))(v[0].equals("train")?train:dev).add(InputStudy.clock(Path.of(v[1])));
        }
    }
    static List<Arena.Scenario> panel(List<DuelEnv> clocks){
        var rows=new ArrayList<Arena.Scenario>();
        for(var c:clocks)for(var link:LINKS)for(boolean first:new boolean[]{true,false})
            rows.add(PingLeague.scenario(c,target,first,link[0],link[1]));
        return rows;
    }
    static Arena.Scenario draw(Random r) {
        return PingLeague.scenario(train.get(r.nextInt(train.size())),target,r.nextBoolean(),r.nextInt(151),r.nextInt(151));
    }
    record Stats(double mean,double seatA,double seatB) {
        double merit(){return .75*mean+.25*Math.min(seatA,seatB);}
    }
    static Stats evaluate(Arena.Fighter actor,List<Arena.Scenario> games) throws Exception {
        var jobs=new ArrayList<Callable<Double>>();
        for(var s:games)jobs.add(()->s.game(actor));
        double a=0,b=0;int na=0,i=0;
        for(var f:PingLeague.pool.invokeAll(jobs)){
            double v=f.get();
            if(games.get(i++).first()){a+=v;na++;}else b+=v;
        }
        return new Stats((a+b)/games.size(),a/na,b/(games.size()-na));
    }
    static final class Best implements AutoCloseable {
        final Path folder;final String id;final List<Arena.Scenario> games;final Stats wt;
        final PrintWriter log;double high=-1;
        Best(String id,List<Arena.Scenario> games)throws Exception {
            this.id=id;this.games=games;folder=F.resolve("fits");Files.createDirectories(folder);
            wt=evaluate(PingLeague.WT,games);
            log=new PrintWriter(Files.newBufferedWriter(folder.resolve(id+".csv.tmp")));
            log.println("update,merit,target,seat_a,seat_b,wtap");
        }
        void consider(int step,Arena.Fighter actor)throws Exception {
            var s=evaluate(actor,games);
            log.printf(Locale.ROOT,"%d,%.9f,%.9f,%.9f,%.9f,%.9f%n",step,s.merit(),s.mean(),s.seatA(),s.seatB(),wt.mean());log.flush();
            if(s.merit()>high){high=s.merit();Files.writeString(folder.resolve(id+".net.tmp"),actor.model().encode()+"\n");}
        }
        void finish()throws IOException{
            if(log.checkError())throw new IOException("Incomplete training curve");
            for(String ext:List.of("net","csv"))Files.move(folder.resolve(id+"."+ext+".tmp"),folder.resolve(id+"."+ext),StandardCopyOption.ATOMIC_MOVE);
        }
        public void close(){log.close();}
    }
    static void ppo(String id,Arena.Fighter parent,int seed)throws Exception{
        var model=parent.model();
        float[] w=model.weights().clone();int dim=(w.length==DuelNetwork.size(DuelNetwork.Family.MLP)?22:34);
        double[] critic=new double[dim+1];var adam=new PPO.Adam(w.length);
        var batches=new Random(seed);var learn=new Random(seed+2917L);
        var actor=new Arena.Fighter(id,new Arena.Model(model.family(),w));
        try(var best=new Best(id,panel(dev))){
            for(int g=0;g<=1024;g++){
                if(g%64==0)best.consider(g,actor);
                if(g==1024)break;
                double temperature=g<512?.32:g<832?.22:.14,lr=g<640?.00032:g<896?.00014:.000045;
                var jobs=new ArrayList<Callable<PPO.Batch>>();
                for(int i=0;i<32;i++){
                    var s=draw(batches);long episode=batches.nextLong();
                    jobs.add(()->PPO.trajectory(s,w,critic,temperature,episode));
                }
                var data=new ArrayList<PPO.Step>();for(var r:PingLeague.pool.invokeAll(jobs))data.addAll(r.get().steps());
                PPO.update(data,w,critic,adam,learn,new PPO.Dist(temperature),lr);
            }
            best.finish();System.out.println(id+" development "+best.high);
        }
    }
    static Arena.Fighter mutate(Arena.Fighter parent,int serial,Random r){
        float[] w=parent.model().weights().clone();
        int end=w.length-(DuelNetwork.study(parent.model().family())?1:0);
        int hidden=DuelNetwork.width(parent.model().family());
        int outputStart=end-5*(hidden+1);int style=r.nextInt(3);
        double sigma=new double[]{.025,.075,.18,.40,.85}[r.nextInt(5)];
        int head=r.nextInt(5);
        for(int i=0;i<end;i++){
            boolean change=style==0?r.nextDouble()<.12:style==1?i>=outputStart:i>=outputStart+head*(hidden+1)&&i<outputStart+(head+1)*(hidden+1);
            if(change)w[i]+=(float)(sigma*r.nextGaussian());
        }
        return new Arena.Fighter("mut"+serial,new Arena.Model(parent.model().family(),w));
    }
    static void evolve(String id,Arena.Fighter start,int seed)throws Exception{
        var rng=new Random(seed+736433);var elites=new ArrayList<Arena.Fighter>(List.of(start));
        try(var best=new Best(id,panel(dev))){
            best.consider(0,start);
            for(int g=1;g<=128;g++){
                var games=new ArrayList<Arena.Scenario>();for(int i=0;i<24;i++)games.add(draw(rng));
                var group=new ArrayList<>(elites);
                while(group.size()<24)group.add(mutate(elites.get(rng.nextInt(elites.size())),g*24+group.size(),rng));
                var jobs=new ArrayList<Callable<Double>>();
                for(var f:group)jobs.add(()->{double sum=0;for(var s:games)sum+=s.game(f);return sum/games.size();});
                var scored=new ArrayList<Map.Entry<Arena.Fighter,Double>>();int i=0;
                for(var x:PingLeague.pool.invokeAll(jobs))scored.add(Map.entry(group.get(i++),x.get()));
                scored.sort(Map.Entry.<Arena.Fighter,Double>comparingByValue().reversed());
                elites.clear();for(var pair:scored.subList(0,4))elites.add(pair.getKey());
                if(g%8==0)best.consider(g,elites.get(0));
            }
            best.finish();System.out.println(id+" development "+best.high);
        }
    }
    public static void main(String[] args)throws Exception{
        try{
            load();
            if(args[0].equals("select")){
                var old=new ArrayList<DuelEnv>();for(var p:Files.readAllLines(F.resolve("selection-clocks.txt")))old.add(InputStudy.clock(Path.of(p)));
                var games=panel(old);var wt=evaluate(PingLeague.WT,games);
                try(var out=new PrintWriter(Files.newBufferedWriter(F.resolve("selection.csv.tmp")))){
                    out.println("id,merit,mean,seat_a,seat_b,wtap");
                    for(var file:Files.readAllLines(F.resolve("candidates.txt"))){
                        var f=InputStudy.fighter(file,F.resolve("fits/"+file+".net"));var s=evaluate(f,games);
                        out.printf(Locale.ROOT,"%s,%.9f,%.9f,%.9f,%.9f,%.9f%n",file,s.merit(),s.mean(),s.seatA(),s.seatB(),wt.mean());
                    }
                    if(out.checkError())throw new IOException("Failed to write selection table");
                }
                Files.move(F.resolve("selection.csv.tmp"),F.resolve("selection.csv"),StandardCopyOption.ATOMIC_MOVE);
            }else{
                var p=InputStudy.fighter("start",Path.of(args[2]));int seed=Integer.parseInt(args[3]);
                if(args[0].equals("ppo"))ppo(args[1],p,seed);else evolve(args[1],p,seed);
            }
        }finally{PingLeague.pool.shutdown();}
    }
}
