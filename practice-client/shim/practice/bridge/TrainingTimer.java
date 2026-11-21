package practice.bridge;

import io.netty.channel.Channel;

/** A local training server may scale ticks on its own connection. Rendering still runs every frame. */
public final class TrainingTimer {
    private static volatile Channel owner;
    private static volatile float rate=1;
    private static volatile boolean sampled;
    private TrainingTimer() {}
    static void accept(Channel channel,double value) {
        if(!ConnectionScope.canSend(channel)||!Double.isFinite(value)||value<.1||value>1)return;
        if(owner==channel && rate==(float)value)return;
        sampled=false;rate=(float)value;owner=channel;
    }
    static boolean active(){return owner!=null && ConnectionScope.canSend(owner);}
    static float acknowledged(){return active() && sampled ? rate : 0;}
    public static double clockAdjustment(double vanilla){return active()?1.0:vanilla;}
    public static float scale(float vanilla) {
        if(!active())return vanilla;
        sampled=true;
        return vanilla*rate;
    }
    static void disconnected(Channel channel){if(owner==channel){owner=null;rate=1;sampled=false;}}
}
