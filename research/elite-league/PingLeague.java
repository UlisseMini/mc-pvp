import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import lab.DuelNetwork;

/** PPO comparison on independent per-player link delays and identical regulation openings. */
final class PingLeague {
    static final int BATCH=32, CHECK=32, MIN=256;
    static final Arena.Fighter WT=Arena.Fighter.named("wtap");
    static record Bout(Arena.Scenario scenario, long seed){}
    static final List<Arena.Fighter> field=new ArrayList<>();
    static final List<DuelEnv> train=new ArrayList<>(),dev=new ArrayList<>(),test=new ArrayList<>();
    static final String[] arms={"two-ping","own-ping"};
    static final ExecutorService pool=Executors.newFixedThreadPool(3);
    static class Actor {
        final DuelNetwork.Family family;
        final float[] w;
        final double[] critic;
        final PPO.Adam adam;
        final Random update=new Random(978123);
        double record=-1,anchor=-1,lr=.00025;
        int anchorGen,bestGen;
        boolean reduced,done;
        Actor(Arena.Model old,boolean both){
            family=both?DuelNetwork.Family.PING_MLP:DuelNetwork.Family.MLP;
            int inputs=both?24:22;w=new float[DuelNetwork.size(family)];
            for(int i=0;i<PPO.H;i++)System.arraycopy(old.weights(),i*23,w,i*(inputs+1),23);
            System.arraycopy(old.weights(),PPO.H*23,w,PPO.H*(inputs+1),PPO.OUT*(PPO.H+1));
            critic=new double[inputs+1];adam=new PPO.Adam(w.length);
        }
        Arena.Fighter fighter(String name){return new Arena.Fighter(name,new Arena.Model(family,w));}
        void learn(List<Bout> batch)throws Exception{
            var jobs=new ArrayList<Callable<PPO.Batch>>();
            for(var b:batch)jobs.add(()->PPO.trajectory(b.scenario,w,critic,.28,b.seed));
            var steps=new ArrayList<PPO.Step>();
            for(var f:pool.invokeAll(jobs))steps.addAll(f.get().steps());
            PPO.update(steps,w,critic,adam,update,new PPO.Dist(.28),lr);
        }
        void assess(int gen,double score,Path best)throws IOException{
            if(score>record){record=score;bestGen=gen;Files.writeString(best,DuelNetwork.encode(family,w)+"\n");}
            if(score>=anchor+.005){anchor=score;anchorGen=gen;}
            if(gen<MIN || gen-anchorGen<6*CHECK)return;
            if(!reduced){lr*=.25;reduced=true;anchorGen=gen;}else done=true;
        }
    }
    static int ping(Random rng){int p;do{p=rng.nextInt(151);}while(p==23||p==97);return p;}
    static Arena.Scenario scenario(DuelEnv template,Arena.Fighter foe,boolean first,int own,int other){
        return new Arena.Scenario(new DuelEnv(template,first?own:other,first?other:own),foe,first);
    }
    static List<Bout> sample(Random random){
        var bouts=new ArrayList<Bout>();
        for(int k=0;k<BATCH;k++){
            int own=ping(random),other=ping(random);
            Arena.Fighter foe=field.get(random.nextInt(field.size()));
            DuelEnv clock=train.get(random.nextInt(train.size()));
            bouts.add(new Bout(scenario(clock,foe,random.nextBoolean(),own,other),random.nextLong()));
        }
        return bouts;
    }
    static List<Arena.Scenario> panel(List<DuelEnv> clocks,boolean sealed){
        var rows=new ArrayList<Arena.Scenario>();Random r=new Random(sealed?654911:540281);
        for(var foe:field)for(var clock:clocks)for(int k=0;k<(sealed?4:2);k++){
            int own=sealed?new int[]{23,97,0,80}[k]:ping(r);
            int other=sealed?new int[]{97,23,0,80}[k]:ping(r);
            for(boolean first:new boolean[]{true,false})rows.add(scenario(clock,foe,first,own,other));
        }
        return rows;
    }
    static double evaluate(Arena.Fighter fighter,List<Arena.Scenario> panel,PrintWriter detail)throws Exception{
        var jobs=new ArrayList<Callable<Double>>();for(var s:panel)jobs.add(()->s.game(fighter));
        var sums=new LinkedHashMap<String,Double>();int i=0;
        for(var f:pool.invokeAll(jobs)){
            var s=panel.get(i++);double y=f.get();sums.merge(s.opponent().name(),y,Double::sum);
            if(detail!=null)detail.printf(Locale.ROOT,"%s,%s,%s,%s,%d,%d,%.1f%n",fighter.name(),s.opponent().name(),s.env().clockId,s.first()?"A":"B",s.first()?s.env().rtt:s.env().otherRtt,s.first()?s.env().otherRtt:s.env().rtt,y);
        }
        int n=panel.size()/sums.size();double mean=sums.values().stream().mapToDouble(v->v).average().orElseThrow()/n;
        return (mean+sums.get("wtap")/n+sums.values().stream().mapToDouble(v->v).min().orElseThrow()/n)/3;
    }
    public static void main(String[] args)throws Exception{
        Path dst=Path.of(args[0]);var lines=Files.readAllLines(dst.resolve("field.tsv"));
        for(var l:lines){var v=l.split("\t");field.add(v.length==1?Arena.Fighter.named(v[0]):new Arena.Fighter(v[0],Arena.Model.decode(Files.readString(Path.of(v[1])).trim())));}
        boolean sealed=args[1].equals("test");Arena.Model old=field.get(1).model();
        try{
            for(var l:Files.readAllLines(dst.resolve("clocks.tsv"))){
                var v=l.split("\t");if(sealed!=v[0].equals("test"))continue;
                var clock=new DuelEnv(DuelEnv.Tape.read(Path.of(v[1]),DuelEnv.LIMIT),"mmc-range",0,true,29,true);
                clock.initial.timing.mouseInput=true;
                (sealed?test:v[0].equals("train")?train:dev).add(clock);
            }
            if(sealed){
                var scenarios=panel(test,true);
                try(var out=new PrintWriter(Files.newBufferedWriter(dst.resolve("transfer.csv"),StandardOpenOption.CREATE_NEW))){
                    out.println("arm,foe,calendar,seat,own_rtt,opponent_rtt,reward");
                    var entrants=new ArrayList<>(List.of(WT,field.get(1)));
                    for(String arm:arms)entrants.add(new Arena.Fighter(arm,Arena.Model.decode(Files.readString(dst.resolve(arm+".net")).trim())));
                    for(var f:entrants)evaluate(f,scenarios,out);
                }return;
            }
            var panel=panel(dev,false);var rng=new Random(179353);Actor[] entrants={new Actor(old,true),new Actor(old,false)};
            long start=System.nanoTime();
            try(var log=new PrintWriter(Files.newBufferedWriter(dst.resolve("learning.csv"),StandardOpenOption.CREATE_NEW))){
                log.println("update,arm,validation,champion,wtap,best_update,learning_rate,converged,seconds");
                double baseline=evaluate(WT,panel,null),champ=evaluate(field.get(1),panel,null);
                for(int g=0;;g++){
                    if(g%CHECK==0){
                        for(int i=0;i<2;i++){var a=entrants[i];if(a.done)continue;
                            double score=evaluate(a.fighter(arms[i]),panel,null);
                            if(g==0 && Math.abs(score-champ)>1e-12)throw new AssertionError("Warm start parity");
                            a.assess(g,score,dst.resolve(arms[i]+".net"));
                            log.printf(Locale.ROOT,"%d,%s,%.6f,%.6f,%.6f,%d,%.8f,%b,%.2f%n",g,arms[i],score,champ,baseline,a.bestGen,a.lr,a.done,(System.nanoTime()-start)/1e9);
                        }log.flush();
                        if(entrants[0].done&&entrants[1].done)break;
                    }
                    var batch=sample(rng);
                    for(var a:entrants)if(!a.done)a.learn(batch);
                    if((g+1)%8==0)System.out.printf(Locale.ROOT,"update %d %.2fs%n",g+1,(System.nanoTime()-start)/1e9);
                }
            }
        }finally{pool.shutdown();}
    }
}
