package sword;

import java.nio.file.*;
import java.util.*;

/** Outcome probabilities under unobserved scheduler phases, conditional on both recorded opening poses. */
public final class ClockPrediction {
    public static void main(String[] args) throws Exception {
        String bankPath=System.getProperty("sword.cadenceBank");
        Map<Integer,List<PollClock.Timing>> bank=new HashMap<>();
        if(bankPath!=null){
            for(String row:Files.readAllLines(Path.of(bankPath)).stream().skip(1).toList()){
                String[] fields=row.split("\t");
                var clocks=new PollClock.Cadence[3];
                for(int i=0;i<3;i++){
                    var stamps=Arrays.stream(fields[i+2].split(",")).map(Long::valueOf).toList();
                    clocks[i]=PollClock.Cadence.from(0,stamps);
                }
                bank.computeIfAbsent(Integer.parseInt(fields[1]),key->new ArrayList<>())
                    .add(new PollClock.Timing(clocks[0],clocks[1],clocks[2]));
            }
        }
        boolean cadence=bankPath!=null||Boolean.getBoolean("sword.pollForecast");
        System.out.println("id,fixed_win_score,phase_win_score,fixed_count,phase_count"+(cadence?",poll_win_score,poll_count":""));
        for(String line: Files.readAllLines(Path.of(args[0]))){
            var c=line.split("\t"); Path dir=Path.of(c[0]);String id=c[1];
            int lag=Integer.parseInt(c[2])/100,seat=Integer.parseInt(c[3]);
            double angle=Double.parseDouble(c[4]);
            var leftTape=KernelReplay.read(dir.resolve(id+"-A.tsv"));
            var rightTape=KernelReplay.read(dir.resolve(id+"-B.tsv"));
            var left=leftTape.getFirst();var right=rightTape.getFirst();
            var timing=cadence&&bankPath==null?PollClock.read(dir.resolve(id+"-server.tsv"),leftTape,rightTape):null;
            var templates=bank.get(Integer.parseInt(c[2]));
            if(bankPath!=null&&(templates==null||templates.isEmpty()))throw new IllegalArgumentException("No clocks for "+c[2]+" ms");
            float yawA=Float.parseFloat(String.format(Locale.ROOT,"%.2f",Math.toDegrees(angle)-90));
            float yawB=Float.parseFloat(String.format(Locale.ROOT,"%.2f",Math.toDegrees(angle)+90));
            double fixed=0,phase=0,poll=0;
            var scene=new Model.Scene(0,lag,lag);var start=new Model.Start(scene,List.of());
            for(int bits=0;bits<32;bits++)for(int variant=0;variant<(cadence?9:5);variant++){
                var a=new FastDuel.Seat(lag,bits&1,1);var b=new FastDuel.Seat(lag,(bits>>1)&1,2);
                KernelReplay.start(a,left);KernelReplay.start(b,right);
                a.local.yaw=a.server.yaw=FastDuel.wrapYaw(yawA);b.local.yaw=b.server.yaw=FastDuel.wrapYaw(yawB);
                a.localFirst=(bits&4)!=0;b.localFirst=(bits&8)!=0;boolean aFirst=(bits&16)!=0;
                var ca=Controls.get(c[5]);var cb=Controls.get(c[6]);
                if(variant>=5){
                    long seed=new SplittableRandom(79765L+bits*11L+variant-4).nextLong();
                    // Keep the native clocks for both clients and the server together.
                    var trial=templates==null?timing:templates.get((bits*4+variant-5)%templates.size());
                    double points=PollClock.duel(trial,a,b,ca,cb,seed,aFirst);
                    poll+=seat==0?points:1-points;
                    continue;
                }
                var game=variant==0?FastDuel.scheduled(a,b,ca,cb,start,null,aFirst):
                    PhaseClock.duel(a,b,ca,cb,start,null,new SplittableRandom(79765L+bits*11L+variant).nextLong(),aFirst);
                double leftPoints=game.b()==0?1:game.a()==0?0:.5;
                double points=seat==0?leftPoints:1-leftPoints;
                if(variant==0)fixed+=points;else phase+=points;
            }
            System.out.printf(Locale.ROOT,"%s,%.8f,%.8f,32,128",id,fixed/32,phase/128);
            if(cadence)System.out.printf(Locale.ROOT,",%.8f,128",poll/128);
            System.out.println();
        }
    }
}
