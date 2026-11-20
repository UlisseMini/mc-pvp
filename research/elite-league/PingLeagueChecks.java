import java.nio.file.*;
import java.util.*;
import lab.DuelNetwork;

/** Compare randomized link forks to independent constructions and verify the two new actor inputs. */
final class PingLeagueChecks {
    static void check(boolean ok){if(!ok)throw new AssertionError();}
    public static void main(String[] args)throws Exception{
        var dest=Path.of(args[0]);var old=Arena.Model.decode(Files.readString(Path.of(Files.readAllLines(dest.resolve("field.tsv")).get(1).split("\t")[1])).trim());
        var wide=new PingLeague.Actor(old,true);var orig=new DuelNetwork(old.family(),old.weights());
        var twin=new DuelNetwork(wide.family,wide.w);var random=new Random(1291);
        for(int i=0;i<512;i++){
            double x=random.nextDouble()*5, age=random.nextDouble()*400,own=random.nextInt(151);
            double other=i%3==0?Double.NaN:random.nextInt(151);
            int a=orig.step(x,12,0,4,12,1,.1,.2,.3,true,true,age,0,own);
            int b=twin.step(x,12,0,4,12,1,.1,.2,.3,true,true,age,0,own,other);
            check(a==b && Arrays.equals(orig.scores(),twin.scores()));
            var f=twin.features();check(f[15]==own/80-1 && f[22]==(Double.isFinite(other)?other/80-1:0) && f[23]==(Double.isFinite(other)?1:-1));
            orig.applied(a);twin.applied(b);
        }
        // Numerical derivatives cover the opponent RTT column, availability and the shifted output head.
        double[] input=twin.features(),h=new double[PPO.H],scores=new double[PPO.OUT];
        double[] d={.12,-.3,.41,.22,-.09},gradient=new double[wide.w.length];
        PPO.forward(wide.w,input,h,scores);PPO.backward(wide.w,input,h,d,gradient,1.);
        for(int i=0;i<wide.w.length;i++){
            float saved=wide.w[i],hi=saved+.001f,lo=saved-.001f;
            wide.w[i]=hi;PPO.forward(wide.w,input,h,scores);double plus=0;
            for(int k=0;k<d.length;k++)plus+=scores[k]*d[k];
            wide.w[i]=lo;PPO.forward(wide.w,input,h,scores);double minus=0;
            for(int k=0;k<d.length;k++)minus+=scores[k]*d[k];
            wide.w[i]=saved;check(Math.abs(gradient[i]-(plus-minus)/(hi-lo))<.0001);
        }
        int bouts=0,ticks=0;
        for(String row:Files.readAllLines(dest.resolve("clocks.tsv")).subList(0,2)){
            var path=Path.of(row.split("\t")[1]);var tape=DuelEnv.Tape.read(path,DuelEnv.LIMIT);
            var template=new DuelEnv(tape,"mmc-range",0,true,29,true);template.initial.timing.mouseInput=true;
            for(var pair:new int[][]{{0,0},{23,97},{97,23},{150,1},{1,150}})
                for(boolean first:new boolean[]{true,false}){
                    var raw=new DuelEnv(tape,"mmc-range",pair[0],pair[1],true,29,true,13,8,0);
                    raw.initial.timing.mouseInput=true;var fast=new DuelEnv(template,pair[0],pair[1]);
                    var foe=new Arena.Fighter("incumbent",old);
                    var left=new Arena.Scenario(raw,foe,first).round(PingLeague.WT);
                    var right=new Arena.Scenario(fast,foe,first).round(PingLeague.WT);
                    check(left.winner()==right.winner() && left.clock.time()==right.clock.time());
                    var x=left.clock.state;var y=right.clock.state;
                    check(x.serverA.equals(y.serverA) && x.serverB.equals(y.serverB) && x.hits==y.hits && x.incomingHits==y.incomingHits && x.acceptedA==y.acceptedA && x.acceptedB==y.acceptedB && x.seq==y.seq);
                    ticks+=left.step;bouts++;
                }
        }
        System.out.println("245 gradient coordinates, 512 exact warm-start scores and features; "+bouts+" direct/cached full bouts, "+ticks+" server tick ends.");
    }
}
