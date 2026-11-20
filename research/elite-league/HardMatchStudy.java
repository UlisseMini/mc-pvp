import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import lab.DuelNetwork;
import lab.SumoInputs;

/** Paired continuation of the development-best checkpoint with equal compute and different opponent mixes. */
final class HardMatchStudy {
    static final Path D=InputStudy.DIR.resolve("refine");
    static final int[] SEEDS={4711,5821,6911};
    static void fit(Arena.Fighter parent,boolean focus,int seed)throws Exception{
        for(String l:Files.readAllLines(InputStudy.PREV.resolve("clocks.tsv"))){
            var v=l.split("\t");if(v[0].equals("test"))continue;
            (v[0].equals("train")?PingLeague.train:PingLeague.dev).add(InputStudy.clock(Path.of(v[1])));
        }
        var panel=InputStudy.panel(PingLeague.dev,false);
        var totals=new LinkedHashMap<String,Double>();for(var s:panel)totals.merge(s.opponent().name(),s.game(parent),Double::sum);
        var weak=new ArrayList<>(PingLeague.field);weak.removeIf(f->f.name().equals("wtap")||(f.model()!=null&&f.model().encode().equals(parent.model().encode())));
        weak.sort(Comparator.comparingDouble(f->totals.get(f.name())));weak.subList(3,weak.size()).clear();
        String protocol=String.join("\n",weak.stream().map(Arena.Fighter::name).toList())+"\n";Path file=D.resolve("hardest.txt");
        if(Files.exists(file)&&!Files.readString(file).equals(protocol))throw new IllegalStateException("Changed hard opponents");
        Files.writeString(file,protocol);
        String id=(focus?"focused":"uniform")+"-"+seed;float[] w=parent.model().weights().clone();
        var actor=new Arena.Fighter(id,new Arena.Model(parent.model().family(),w));
        var critic=new double[SumoInputs.COUNT+1];var opt=new PPO.Adam(w.length);var draws=new Random(seed);var learning=new Random(seed+8201L);
        double best=-1,wt=PingLeague.evaluate(PingLeague.WT,panel,null);
        try(var out=new PrintWriter(Files.newBufferedWriter(D.resolve(id+".tmp")))){
            out.println("update,dev,wtap");
            for(int g=0;g<=384;g++){
                if(g%32==0){double merit=PingLeague.evaluate(actor,panel,null);out.printf(Locale.ROOT,"%d,%.9f,%.9f%n",g,merit,wt);out.flush();
                    if(merit>best){best=merit;Files.writeString(D.resolve(id+".net.tmp"),actor.model().encode()+"\n");}}
                if(g==384)break;
                var tasks=new ArrayList<Callable<PPO.Batch>>();
                for(int i=0;i<32;i++){
                    int a=InputStudy.ping(draws),b=InputStudy.ping(draws);
                    var foe=PingLeague.field.get(draws.nextInt(PingLeague.field.size()));var alternative=weak.get(draws.nextInt(3));
                    boolean replace=draws.nextBoolean();var clock=PingLeague.train.get(draws.nextInt(PingLeague.train.size()));
                    var s=PingLeague.scenario(clock,focus&&replace?alternative:foe,draws.nextBoolean(),a,b);
                    long episode=draws.nextLong();tasks.add(()->PPO.trajectory(s,w,critic,.22,episode));
                }
                var steps=new ArrayList<PPO.Step>();for(var f:PingLeague.pool.invokeAll(tasks))steps.addAll(f.get().steps());
                PPO.update(steps,w,critic,opt,learning,new PPO.Dist(.22),g<256?.00012:.00003);
            }
        }
        Files.move(D.resolve(id+".net.tmp"),D.resolve(id+".net"),StandardCopyOption.ATOMIC_MOVE);
        Files.move(D.resolve(id+".tmp"),D.resolve(id+".csv"),StandardCopyOption.ATOMIC_MOVE);
        System.out.println("Completed "+id+" best "+best);
    }
    public static void main(String[] args)throws Exception{
        InputStudy.field();String id=Files.readString(D.resolve("parent.txt")).trim();var parent=InputStudy.fighter(id,InputStudy.DIR.resolve("fits/"+id+".net"));
        try{
            if(!args[0].equals("test")){fit(parent,args[0].equals("focused"),Integer.parseInt(args[1]));return;}
            var clocks=new ArrayList<DuelEnv>();for(String l:Files.readAllLines(D.resolve("clocks.txt")))clocks.add(InputStudy.clock(Path.of(l)));
            var panel=InputStudy.panel(clocks,true);var entrants=new ArrayList<>(List.of(PingLeague.WT,PingLeague.field.get(1),PingLeague.field.get(9),parent));
            for(String arm:List.of("uniform","focused"))for(int seed:SEEDS){String n=arm+"-"+seed;entrants.add(InputStudy.fighter(n,D.resolve(n+".net")));}
            try(var out=new PrintWriter(Files.newBufferedWriter(D.resolve("transfer.tmp")))){
                out.println("arm,foe,calendar,seat,own_rtt,opponent_rtt,reward");for(var e:entrants)PingLeague.evaluate(e,panel,out);
            }
            Files.move(D.resolve("transfer.tmp"),D.resolve("transfer.csv"),StandardCopyOption.ATOMIC_MOVE);
        }finally{PingLeague.pool.shutdown();}
    }
}
