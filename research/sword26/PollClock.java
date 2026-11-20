package sword;

import java.nio.file.*;
import java.util.*;

/** Offline timing diagnostic. Repeat the first second of both native input clocks and the server
 * clock. The simulated policies see only their simulated game. No later keys, hits or scores
 * determine the clock. Unknown packet transit and entity update order are still sampled. */
final class PollClock {
    record Cadence(int offset, int[] millis, int duration) {
        static Cadence from(long origin, List<Long> stamps) {
            if(stamps.size()<21)throw new IllegalArgumentException("Need 21 opening clock samples");
            long first=stamps.getFirst();
            int[] times=new int[20];
            for(int i=0;i<times.length;i++)times[i]=Math.toIntExact(stamps.get(i)-first);
            int duration=Math.toIntExact(stamps.get(20)-first);
            if(duration<=0)throw new IllegalArgumentException("Clock did not advance");
            for(int i=1;i<times.length;i++)if(times[i]<times[i-1])throw new IllegalArgumentException("Clock reversed");
            return new Cadence(Math.toIntExact(first-origin),times,duration);
        }
        int time(int i) {return offset+(i/millis.length)*duration+millis[i%millis.length];}
    }
    record Timing(Cadence a, Cadence b, Cadence server) {}
    static Timing read(Path path, List<KernelReplay.Sample> a, List<KernelReplay.Sample> b)throws Exception {
        long first=Math.min(a.getFirst().stamp(),b.getFirst().stamp());
        List<Long> server;
        try(var stream=Files.lines(path)){
            server=stream.skip(1).map(s->Long.valueOf(s.split("\t")[15])).filter(s->s>=first).limit(21).toList();
        }
        return new Timing(Cadence.from(first,a.stream().limit(21).map(KernelReplay.Sample::stamp).toList()),
                          Cadence.from(first,b.stream().limit(21).map(KernelReplay.Sample::stamp).toList()),
                          Cadence.from(first,server));
    }
    static double duel(Timing timing, FastDuel.Seat a, FastDuel.Seat b, Model.Controller ca, Model.Controller cb,
                       long seed, boolean aFirst) {
        var rng=new SplittableRandom(seed);
        var left=new PhaseClock.Lane(a,ca,0);var right=new PhaseClock.Lane(b,cb,0);
        int i=0,j=0,server=0;
        while(server<Model.LIMIT && a.hp>0 && b.hp>0){
            int pollA=timing.a.time(i),pollB=timing.b.time(j),tick=timing.server.time(server);
            int up=Math.min(left.nextUpload(),right.nextUpload());
            int now=Math.min(Math.min(pollA,pollB),Math.min(up,tick));
            if(now==up){
                boolean fromA=left.nextUpload()==now&&(right.nextUpload()!=now||aFirst);
                var lane=fromA?left:right;
                lane.seat.handle(server,fromA?b:a,lane.incoming.removeFirst().value());
            }else if(now==tick){
                a.broadcast(server,b);b.broadcast(server,a);a.serverTick(b);b.serverTick(a);
            }else if(now==pollA&&(now!=pollB||aFirst))left.poll(i++,now,rng.nextInt(1,5),null);
            else right.poll(j++,now,rng.nextInt(1,5),null);
            left.collect(server,now,rng.nextInt(1,5));right.collect(server,now,rng.nextInt(1,5));
            if(now==tick && now!=up)server++;
        }
        return b.hp==0?1:a.hp==0?0:.5;
    }
}
