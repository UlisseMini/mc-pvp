package sword;

import java.nio.file.*;
import java.util.*;

/** Outcome probabilities under unobserved scheduler phases, conditional on both recorded opening poses. */
public final class ClockPrediction {
    public static void main(String[] args) throws Exception {
        System.out.println("id,fixed_win_score,phase_win_score,fixed_count,phase_count");
        for(String line: Files.readAllLines(Path.of(args[0]))){
            var c=line.split("\t"); Path dir=Path.of(c[0]);String id=c[1];
            int lag=Integer.parseInt(c[2])/100,seat=Integer.parseInt(c[3]);
            double angle=Double.parseDouble(c[4]);
            var left=KernelReplay.read(dir.resolve(id+"-A.tsv")).getFirst();
            var right=KernelReplay.read(dir.resolve(id+"-B.tsv")).getFirst();
            float yawA=Float.parseFloat(String.format(Locale.ROOT,"%.2f",Math.toDegrees(angle)-90));
            float yawB=Float.parseFloat(String.format(Locale.ROOT,"%.2f",Math.toDegrees(angle)+90));
            double fixed=0,phase=0;
            var scene=new Model.Scene(0,lag,lag);var start=new Model.Start(scene,List.of());
            for(int bits=0;bits<32;bits++)for(int variant=0;variant<5;variant++){
                var a=new FastDuel.Seat(lag,bits&1,1);var b=new FastDuel.Seat(lag,(bits>>1)&1,2);
                KernelReplay.start(a,left);KernelReplay.start(b,right);
                a.local.yaw=a.server.yaw=FastDuel.wrapYaw(yawA);b.local.yaw=b.server.yaw=FastDuel.wrapYaw(yawB);
                a.localFirst=(bits&4)!=0;b.localFirst=(bits&8)!=0;boolean aFirst=(bits&16)!=0;
                var ca=Controls.get(c[5]);var cb=Controls.get(c[6]);
                var game=variant==0?FastDuel.scheduled(a,b,ca,cb,start,null,aFirst):
                    PhaseClock.duel(a,b,ca,cb,start,null,new SplittableRandom(79765L+bits*11L+variant).nextLong(),aFirst);
                double leftPoints=game.b()==0?1:game.a()==0?0:.5;
                double points=seat==0?leftPoints:1-leftPoints;
                if(variant==0)fixed+=points;else phase+=points;
            }
            System.out.printf(Locale.ROOT,"%s,%.8f,%.8f,32,128%n",id,fixed/32,phase/128);
        }
    }
}
