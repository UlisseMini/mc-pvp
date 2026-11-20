import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import lab.DuelNetwork;
import lab.SumoInputs;

/** Factorial sumo training. A trailing nontrainable mask is serialized with each actor. */
final class InputStudy {
    enum Arm {
        base8(0),blind8(SumoInputs.BLIND),noself8(SumoInputs.NO_SELF),nofoe8(SumoInputs.NO_FOE),nohurt8(SumoInputs.NO_HURT),
        wide32(0),motion8(SumoInputs.MOTION),edge8(SumoInputs.EDGE),timing8(SumoInputs.TIMING),full8(112),full32(112);
        final int mask;Arm(int mask){this.mask=mask;}
        DuelNetwork.Family family(){return name().endsWith("32")?DuelNetwork.Family.INPUT32:DuelNetwork.Family.INPUT8;}
    }
    static final int[] SEEDS={7181,29137,66047};
    static final int UPDATES=320;
    static final Path DIR=Path.of("research/elite-league/sumo-inputs");
    static final Path PREV=DIR.resolveSibling("ping-ppo");
    static float[] warm(Arm arm,float[] original,int seed){
        int h=DuelNetwork.width(arm.family()),stride=SumoInputs.COUNT+1;
        float[] w=new float[DuelNetwork.size(arm.family())];w[w.length-1]=arm.mask;
        for(int i=0;i<8;i++)System.arraycopy(original,23*i,w,stride*i,23);
        // Nonzero incoming weights break symmetry; zero outgoing weights preserve the original function.
        var r=new Random(seed+90831L);
        for(int i=8;i<h;i++)for(int j=0;j<stride;j++)w[i*stride+j]=(float)(r.nextGaussian()*.16);
        for(int k=0;k<5;k++)System.arraycopy(original,184+9*k,w,h*stride+k*(h+1),9);
        return w;
    }
    static DuelEnv clock(Path path)throws IOException{
        var e=new DuelEnv(DuelEnv.Tape.read(path,DuelEnv.LIMIT),"mmc-range",0,true,29,true);e.initial.timing.mouseInput=true;return e;
    }
    static void field()throws IOException{
        for(String l:Files.readAllLines(PREV.resolve("field.tsv"))){
            String[] v=l.split("\t");PingLeague.field.add(v.length==1?Arena.Fighter.named(v[0]):fighter(v[0],Path.of(v[1])));
        }
        for(String n:List.of("own-ping","two-ping"))PingLeague.field.add(fighter("sumo-"+n,PREV.resolve(n+".net")));
    }
    static Arena.Fighter fighter(String name,Path path)throws IOException{return new Arena.Fighter(name,Arena.Model.decode(Files.readString(path).trim()));}
    static int ping(Random r){int p;do{p=r.nextInt(151);}while(p==31||p==109);return p;}
    static List<Arena.Scenario> panel(List<DuelEnv> clocks,boolean fresh){
        var out=new ArrayList<Arena.Scenario>();var r=new Random(537045);
        for(var foe:PingLeague.field)for(var c:clocks)for(int k=0;k<(fresh?4:2);k++){
            int own=fresh?new int[]{0,80,31,109}[k]:ping(r),other=fresh?new int[]{0,80,109,31}[k]:ping(r);
            for(boolean seat:new boolean[]{true,false})out.add(PingLeague.scenario(c,foe,seat,own,other));
        }
        return out;
    }
    static void fit(Arm arm,int seed)throws Exception{
        for(String l:Files.readAllLines(PREV.resolve("clocks.tsv"))){
            String[] v=l.split("\t");if(v[0].equals("test"))continue;
            (v[0].equals("train")?PingLeague.train:PingLeague.dev).add(clock(Path.of(v[1])));
        }
        Path fit=DIR.resolve("fits");Files.createDirectories(fit);String id=arm+"-"+seed;
        float[] w=warm(arm,PingLeague.field.get(1).model().weights(),seed);
        var critic=new double[SumoInputs.COUNT+1];var opt=new PPO.Adam(w.length);var eval=panel(PingLeague.dev,false);
        var draws=new Random(seed);var optDraws=new Random(seed+10582L);double best=-1;
        var actor=new Arena.Fighter(id,new Arena.Model(arm.family(),w));
        double wt=PingLeague.evaluate(PingLeague.WT,eval,null);
        try(var log=new PrintWriter(Files.newBufferedWriter(fit.resolve(id+".csv.tmp")))){
            log.println("update,dev,wtap");
            for(int g=0;g<=UPDATES;g++){
                if(g%32==0){
                    double score=PingLeague.evaluate(actor,eval,null);
                    log.printf(Locale.ROOT,"%d,%.9f,%.9f%n",g,score,wt);log.flush();
                    if(score>best){best=score;Files.writeString(fit.resolve(id+".net.tmp"),actor.model().encode()+"\n");}
                }
                if(g==UPDATES)break;
                var tasks=new ArrayList<Callable<PPO.Batch>>();
                for(int i=0;i<32;i++){
                    int own=ping(draws),other=ping(draws);
                    var foe=PingLeague.field.get(draws.nextInt(PingLeague.field.size()));
                    var c=PingLeague.train.get(draws.nextInt(PingLeague.train.size()));
                    var s=PingLeague.scenario(c,foe,draws.nextBoolean(),own,other);long episode=draws.nextLong();
                    tasks.add(()->PPO.trajectory(s,w,critic,.28,episode));
                }
                var data=new ArrayList<PPO.Step>();for(var f:PingLeague.pool.invokeAll(tasks))data.addAll(f.get().steps());
                PPO.update(data,w,critic,opt,optDraws,new PPO.Dist(.28),g<224?.00025:.0000625);
                if(w[w.length-1]!=arm.mask)throw new AssertionError("Optimizer changed feature mask");
            }
        }
        for(String ext:List.of("net","csv"))Files.move(fit.resolve(id+"."+ext+".tmp"),fit.resolve(id+"."+ext),StandardCopyOption.ATOMIC_MOVE);
        System.out.printf(Locale.ROOT,"finished %s %.4f%n",id,best);
    }
    static void test()throws Exception{
        for(String l:Files.readAllLines(DIR.resolve("test-clocks.txt")))PingLeague.test.add(clock(Path.of(l)));
        var panel=panel(PingLeague.test,true);var competitors=new ArrayList<Arena.Fighter>();
        for(String n:List.of("wtap","v4-ppo-g0496","sumo-own-ping","sumo-two-ping"))
            competitors.add(PingLeague.field.stream().filter(f->f.name().equals(n)).findFirst().orElseThrow());
        competitors.add(fighter("incumbent-blind",DIR.resolve("incumbent-blind.net")));
        for(var arm:Arm.values())for(int seed:SEEDS){String id=arm+"-"+seed;competitors.add(fighter(id,DIR.resolve("fits/"+id+".net")));}
        for(int seed:SEEDS)for(int bit:new int[]{SumoInputs.MOTION,SumoInputs.EDGE,SumoInputs.TIMING}){
            String group=bit==SumoInputs.MOTION?"motion":bit==SumoInputs.EDGE?"edge":"timing";
            String name="fixed-no"+group+"-"+seed;
            competitors.add(fighter(name,DIR.resolve("masks/"+name+".net")));
        }
        try(var log=new PrintWriter(Files.newBufferedWriter(DIR.resolve("transfer.tmp")))){
            log.println("arm,foe,calendar,seat,own_rtt,opponent_rtt,reward");
            for(var c:competitors){PingLeague.evaluate(c,panel,log);log.flush();}
        }
        Files.move(DIR.resolve("transfer.tmp"),DIR.resolve("transfer.csv"),StandardCopyOption.ATOMIC_MOVE);
    }
    public static void main(String[] args)throws Exception{
        try{field();if(args[0].equals("test"))test();else fit(Arm.valueOf(args[0]),Integer.parseInt(args[1]));}
        finally{PingLeague.pool.shutdown();}
    }
}
