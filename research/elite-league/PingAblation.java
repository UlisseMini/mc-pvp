import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Replay each sealed regulation matchup with only the frozen actor's opponent-RTT input changed. */
final class PingAblation {
    public static void main(String[] args)throws Exception{
        var dir=Path.of(args[0]);var field=new HashMap<String,Arena.Fighter>();
        for(String row:Files.readAllLines(dir.resolve("field.tsv"))){
            var v=row.split("\t");field.put(v[0],v.length==1?Arena.Fighter.named(v[0]):
                    new Arena.Fighter(v[0],Arena.Model.decode(Files.readString(Path.of(v[1])).trim())));
        }
        var network=Arena.Model.decode(Files.readString(dir.resolve("two-ping.net")).trim());
        var clocks=new HashMap<String,DuelEnv>();
        try(var out=new PrintWriter(Files.newBufferedWriter(dir.resolve("ping-ablation.csv"),StandardOpenOption.CREATE_NEW))){
            out.println("foe,calendar,seat,own,other,truth,opponent_set_to_own,opponent_unknown");
            for(String row:Files.readAllLines(dir.resolve("transfer.csv"))){
                String[] v=row.split(",");if(!v[0].equals("two-ping"))continue;
                var foe=field.get(v[1]);var clock=clocks.get(v[2]);
                if(clock==null){
                    var tape=DuelEnv.Tape.read(dir.resolve(v[2]+".txt.gz"),DuelEnv.LIMIT);
                    clock=new DuelEnv(tape,"mmc-range",0,true,29,true);clock.initial.timing.mouseInput=true;clocks.put(v[2],clock);
                }
                int own=Integer.parseInt(v[4]),other=Integer.parseInt(v[5]);boolean first=v[3].equals("A");
                var sc=PingLeague.scenario(clock,foe,first,own,other);
                double[] scores=new double[3];int i=0;
                for(double observed:new double[]{other,own,Double.NaN}){
                    var env=sc.env();var rival=foe.create(other,own,0,true,13,env.initial.config.hitTicks);
                    var game=env.new Game(first,new Arena.Neural(network,own,observed),rival);
                    game.finish();scores[i++]=game.reward();
                }
                if(scores[0]!=Double.parseDouble(v[6]))throw new AssertionError("Trajectory changed on original inputs");
                out.printf(Locale.ROOT,"%s,%s,%s,%d,%d,%.1f,%.1f,%.1f%n",v[1],v[2],v[3],own,other,scores[0],scores[1],scores[2]);
            }
        }
    }
}
