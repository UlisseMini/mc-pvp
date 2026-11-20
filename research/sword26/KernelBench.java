package sword;

import java.nio.file.Path;
import java.util.List;

/** Warm-JVM wall time for two players, their policies and a server for each tick. */
public final class KernelBench {
    record Batch(long ticks,int games,double checksum,double seconds){}
    static Batch run(double[] left,double[] right,int games,long seed) throws Exception {
        var rng=new java.util.SplittableRandom(seed);
        long ticks=0;double checksum=0,start=System.nanoTime();
        for(int i=0;i<games;i++){
            var scene=new Model.Scene(rng.nextLong(),i&1,i&1);
            var a=left==null?Controls.get("wtap"):new Fighter(left);
            var b=right==null?Controls.get("wtap"):new Fighter(right);
            var score=FastDuel.duel(a,b,new Model.Start(scene,List.of()),null);
            ticks+=score.ticks();checksum+=(i+1)*(score.a()-score.b());
        }
        return new Batch(ticks,games,checksum,(System.nanoTime()-start)/1e9);
    }
    public static void main(String[] args)throws Exception {
        var bot=Fighter.read(Path.of(args[0]));var rival=Fighter.read(Path.of(args[1]));
        run(bot,rival,1500,817434);run(null,rival,1500,817434);
        System.out.println("repeat,learned_duel_ticks,wtap_duel_ticks,learned_seconds,wtap_seconds,learned_ticks_per_second,wtap_ticks_per_second,learned_duels_per_second,wtap_duels_per_second,learned_checksum,wtap_checksum");
        for(int i=0;i<5;i++){
            int games=Integer.parseInt(args[2]);var a=run(bot,rival,games,184241+i);var b=run(null,rival,games,184241+i);
            System.out.printf(java.util.Locale.ROOT,"%d,%d,%d,%.6f,%.6f,%.1f,%.1f,%.1f,%.1f,%.3f,%.3f%n",
                i,a.ticks,b.ticks,a.seconds,b.seconds,a.ticks/a.seconds,b.ticks/b.seconds,games/a.seconds,games/b.seconds,a.checksum,b.checksum);
        }
    }
}
