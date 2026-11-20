package sword;

import java.nio.file.*;
import java.util.*;

/** Offline comparison using recorded client and server poll times. The simulated policies see
 * their own simulated game. Unknown packet transit and entity update order are still sampled. */
final class PollClock {
    record Cadence(int offset, int[] millis) {
        static Cadence from(long origin, List<Long> stamps) {
            if(stamps.size()<21)throw new IllegalArgumentException("Need 21 opening clock samples");
            long first=stamps.getFirst();
            int[] times=new int[stamps.size()];
            for(int i=0;i<times.length;i++)times[i]=Math.toIntExact(stamps.get(i)-first);
            for(int i=1;i<times.length;i++)if(times[i]<times[i-1])throw new IllegalArgumentException("Clock reversed");
            if(times[times.length-1]==times[times.length-21])throw new IllegalArgumentException("Clock did not advance");
            return new Cadence(Math.toIntExact(first-origin),times);
        }
        int time(int i) {
            if(i<millis.length)return offset+millis[i];
            // A simulated fight may outlast the recording. Repeat its last 20 intervals.
            int start=millis.length-21,elapsed=i-start;
            return offset+millis[start+elapsed%20]+(elapsed/20)*(millis[start+20]-millis[start]);
        }
    }
    record Timing(Cadence a, Cadence b, Cadence server) {}
    static Timing read(Path path, List<KernelReplay.Sample> a, List<KernelReplay.Sample> b)throws Exception {
        long first=Math.min(a.getFirst().stamp(),b.getFirst().stamp());
        long count=Boolean.getBoolean("sword.fullPollReplay")?Long.MAX_VALUE:21;
        List<Long> server;
        try(var stream=Files.lines(path)){
            server=stream.skip(1).map(s->Long.valueOf(s.split("\t")[15])).filter(s->s>=first).limit(count).toList();
        }
        return new Timing(Cadence.from(first,a.stream().limit(count).map(KernelReplay.Sample::stamp).toList()),
                          Cadence.from(first,b.stream().limit(count).map(KernelReplay.Sample::stamp).toList()),
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
            int processing=PhaseClock.processTime(up,tick);
            int now=Math.min(Math.min(pollA,pollB),Math.min(processing,tick));
            if(now==processing){
                boolean fromA=left.nextUpload()==up&&(right.nextUpload()!=up||aFirst);
                var lane=fromA?left:right;
                lane.seat.handle(server,fromA?b:a,lane.incoming.removeFirst().value());
            }else if(now==tick){
                a.broadcast(server,b);b.broadcast(server,a);a.serverTick(b);b.serverTick(a);
            }else if(now==pollA&&(now!=pollB||aFirst))left.poll(i++,now,rng.nextInt(1,5),null);
            else right.poll(j++,now,rng.nextInt(1,5),null);
            left.collect(server,now,rng.nextInt(1,5));right.collect(server,now,rng.nextInt(1,5));
            if(now==tick && now!=processing)server++;
        }
        return b.hp==0?1:a.hp==0?0:.5;
    }
}
