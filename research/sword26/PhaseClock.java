package sword;

import java.util.ArrayDeque;
import java.util.SplittableRandom;
import static sword.Fighter.*;
import static sword.FastDuel.*;

/** Optional training clock. A, B and the server each tick once per 50 ms slot at different phases.
 * The phases and transport costs depend only on the scene seed. Every stream preserves send order.
 * This is a distribution of plausible timings, distinct from a recorded ordinary TCP trajectory. */
final class PhaseClock {
    private static final String MODE=System.getProperty("sword.phaseClock","off");
    static boolean enabled(long seed) { return MODE.equals("on") || (MODE.equals("mix") && (seed&1)==0); }
    record UpEvent(int due,Up value){}
    record DownEvent(int due,Down value){}
    static final class Lane {
        final Seat seat;
        final Model.Controller controller;
        final ArrayDeque<UpEvent> incoming=new ArrayDeque<>();
        final ArrayDeque<DownEvent> outgoing=new ArrayDeque<>();
        int phase, pollTime;
        Keys keys=Keys.STILL;
        Lane(Seat seat,Model.Controller controller,int phase){this.seat=seat;this.controller=controller;this.phase=phase;}
        void poll(int t,int now,int transit,Keys forced){
            while(!outgoing.isEmpty()&&outgoing.getFirst().due<=now)seat.receive(outgoing.removeFirst().value);
            var v=seat.view();
            if(forced==null) keys=controller.act(v);
            else {keys=forced;controller.prime(v,forced);}
            seat.drive(t,v,keys);
            // A packet remains in its sender's fixed-size slot beyond this bounded propagation time.
            int due=now+transit+(seat.lag*50);
            if(!incoming.isEmpty())due=Math.max(due,incoming.getLast().due);
            incoming.addLast(new UpEvent(due,seat.up[slot(t+seat.lag)]));
            pollTime=Integer.MAX_VALUE;
        }
        void collect(int t,int now,int transit){
            int i=slot(t+seat.lag+1);var message=seat.down[i];
            if(!(message.motion||message.position||message.damage||message.enemyDamage||message.sprint!=0))return;
            int due=now+transit+(seat.lag*50);
            if(!outgoing.isEmpty())due=Math.max(due,outgoing.getLast().due);
            outgoing.addLast(new DownEvent(due,message));seat.down[i]=new Down();
        }
        int nextUpload(){return incoming.isEmpty()?Integer.MAX_VALUE:incoming.getFirst().due;}
    }
    static Model.Score duel(Seat a,Seat b,Model.Controller ca,Model.Controller cb,Model.Start start,
                            Model.Observer observer,long seed,boolean aFirst){
        var rng=new SplittableRandom(seed);
        var left=new Lane(a,ca,rng.nextInt(50));var right=new Lane(b,cb,rng.nextInt(50));
        int serverPhase=rng.nextInt(50),t=0;
        for(;t<Model.LIMIT&&a.hp>0&&b.hp>0;t++){
            int origin=50*t;
            left.phase=Math.floorMod(left.phase+rng.nextInt(-4,5),50);
            right.phase=Math.floorMod(right.phase+rng.nextInt(-4,5),50);
            left.pollTime=origin+left.phase;right.pollTime=origin+right.phase;
            int server=origin+serverPhase;
            int aUp=rng.nextInt(1,5),bUp=rng.nextInt(1,5),aDown=rng.nextInt(1,5),bDown=rng.nextInt(1,5);
            boolean prefix=t<start.prefix().size();var frame=prefix?start.prefix().get(t):null;
            double before=a.hp-b.hp;
            int oldA=a.stats.hits,oldB=b.stats.hits;boolean firstHitA=aFirst;
            while(true){
                int upload=Math.min(left.nextUpload(),right.nextUpload());
                int next=Math.min(Math.min(left.pollTime,right.pollTime),Math.min(server,upload));
                if(next>=origin+50)break;
                if(upload==next){
                    boolean fromA=left.nextUpload()==next&&(right.nextUpload()!=next||aFirst);
                    var who=fromA?left:right;var other=fromA?b:a;
                    who.seat.handle(t,other,who.incoming.removeFirst().value);
                    if(a.stats.hits+b.stats.hits==oldA+oldB+1)firstHitA=a.stats.hits>oldA;
                }else if(server==next){
                    a.broadcast(t,b);b.broadcast(t,a);a.serverTick(b);b.serverTick(a);
                    server=Integer.MAX_VALUE;
                }else if(left.pollTime==next)left.poll(t,next,aUp,prefix?frame.a():null);
                else right.poll(t,next,bUp,prefix?frame.b():null);
                left.collect(t,next,aDown);right.collect(t,next,bDown);
            }
            if(observer!=null)observer.step(t,a.stats,b.stats,left.keys,right.keys,firstHitA);
            if(!prefix){
                double reward=(a.hp-b.hp-before)/20+(b.hp==0?1:a.hp==0?-1:0);
                ca.reward(reward);cb.reward(-reward);
            }
        }
        return new Model.Score(a.hp,b.hp,t,a.stats.hits,b.stats.hits,a.stats.clicks,b.stats.clicks,
            a.stats.jumps,b.stats.jumps,a.stats.airHits,b.stats.airHits);
    }
}
