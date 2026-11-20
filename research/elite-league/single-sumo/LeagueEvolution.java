import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** The historical population mutation operator, with the same 32,768-duel budget as doubled PPO. */
final class LeagueEvolution {
    record Row(Arena.Fighter model,double mean){}
    public static void main(String[] args)throws Exception{
        LeagueWorker.load();Path out=Path.of(args[0]);Files.createDirectories(out);
        Arena.Fighter incumbent=LeagueWorker.fighter(args[1]);
        int seed=Integer.parseInt(args[2]);var elites=new ArrayList<Arena.Fighter>();
        // This operator is the original INPUT32 best-response search and preserves its observation mask.
        elites.add(LeagueWorker.fighter("sumo-seven-first"));
        var clocks=new ArrayList<DuelEnv>();
        for(String l:Files.readAllLines(Path.of("research/elite-league/ping-ppo/clocks.tsv"))){
            String[] s=l.split("\t");if(s[0].equals("train"))clocks.add(LeagueWorker.clock(s[1]));
        }
        var pool=Executors.newFixedThreadPool(2);
        try{
            for(int gen=0;gen<64;gen++){
                Path checkpoint=out.resolve("es-"+gen+".txt");
                if(Files.exists(checkpoint)){
                    elites.clear();for(String l:Files.readAllLines(checkpoint))elites.add(new Arena.Fighter("elite",Arena.Model.decode(l)));continue;
                }
                var rng=new Random(seed+gen*15731L);var group=new ArrayList<>(elites);
                while(group.size()<32)group.add(FrozenSumoResponse.mutate(elites.get(rng.nextInt(elites.size())),gen*32+group.size(),rng));
                var clock=new DuelEnv[16];var own=new int[16];var other=new int[16];var seat=new boolean[16];var as=new long[16];var bs=new long[16];
                for(int i=0;i<16;i++){
                    clock[i]=clocks.get(rng.nextInt(clocks.size()));own[i]=InputStudy.ping(rng);other[i]=InputStudy.ping(rng);
                    seat[i]=rng.nextBoolean();as[i]=rng.nextLong();bs[i]=rng.nextLong();
                }
                var tasks=new ArrayList<Callable<Row>>();
                for(var m:group)tasks.add(()->{
                    double total=0;
                    for(int i=0;i<16;i++)total+=LeagueWorker.play(new LeagueWorker.Trial(m,incumbent,clock[i],seat[i],own[i],other[i],as[i],bs[i]),false).outcome();
                    return new Row(m,total/16);
                });
                var scores=new ArrayList<Row>();for(var f:pool.invokeAll(tasks))scores.add(f.get());
                scores.sort(Comparator.comparingDouble(Row::mean).reversed());elites.clear();
                for(int i=0;i<4;i++)elites.add(scores.get(i).model());
                var tmp=out.resolve("es-"+gen+".tmp");Files.write(tmp,elites.stream().map(m->m.model().encode()).toList());
                Files.move(tmp,checkpoint,StandardCopyOption.ATOMIC_MOVE);
            }
            var tmp=out.resolve("final.tmp");Files.writeString(tmp,elites.get(0).model().encode()+"\n");Files.move(tmp,out.resolve("final.net"),StandardCopyOption.REPLACE_EXISTING);
        }finally{pool.shutdown();}
    }
}
