package practice.bridge;

import io.netty.channel.Channel;
import java.util.Map;

/** The clock belongs to the currently connected loopback room. Rendering stays real-time. */
public final class TrainingTimer {
    private static final class Plan {
        final Channel owner;
        final float current,target;
        final long nextWall;
        final boolean configurable;
        Plan(Channel owner,double current,double target,long nextWall,boolean configurable){
            this.owner=owner;this.current=(float)current;this.target=(float)target;
            this.nextWall=nextWall;this.configurable=configurable;
        }
        float rate(){return nextWall>0 && System.currentTimeMillis()>=nextWall?target:current;}
    }
    private static volatile Plan plan;
    private static volatile float sampled;
    private TrainingTimer() {}
    private static boolean valid(double rate){return rate>=.1 && rate<=1;}
    static void accept(Channel channel,Map<String,Object> data){
        if(!ConnectionScope.canSend(channel))return;
        double rate=((Number)data.get("training_speed")).doubleValue();
        if(!valid(rate))return;
        boolean live=data.get("training_wall") instanceof Number && data.get("training_game_ns") instanceof Number
                && data.get("training_next_wall") instanceof Number && data.get("training_next_speed") instanceof Number;
        long nextWall=0;
        double target=rate;
        if(live){
            long wall=((Number)data.get("training_wall")).longValue(),ns=((Number)data.get("training_game_ns")).longValue();
            nextWall=((Number)data.get("training_next_wall")).longValue();
            target=((Number)data.get("training_next_speed")).doubleValue();
            if(!valid(target)||wall<=0||ns<0||(nextWall!=0 && nextWall<=wall))return;
            if(BotRuntime.enabled())lab.TrainingClock.synchronize(wall,ns,rate,nextWall,target);
        }
        if(plan==null || plan.owner!=channel)sampled=0;
        plan=new Plan(channel,rate,target,nextWall,live);
    }
    private static Plan current(){Plan p=plan;return p!=null&&ConnectionScope.canSend(p.owner)?p:null;}
    static boolean active(){return current()!=null;}
    static boolean configurable(){Plan p=current();return p!=null && p.configurable;}
    static float target(){Plan p=current();return p==null?1:p.target;}
    static float acknowledged(){Plan p=current();return p!=null && sampled==p.rate()?sampled:0;}
    public static double clockAdjustment(double vanilla){return active()?1.0:vanilla;}
    public static float scale(float vanilla){
        Plan p=current();
        if(p==null)return vanilla;
        float rate=p.rate();sampled=rate;return vanilla*rate;
    }
    static void disconnected(Channel channel){Plan p=plan;if(p!=null&&p.owner==channel){plan=null;sampled=0;}}
}
