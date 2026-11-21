package practice.bridge;

import lab.Client;
import lab.ReplicationTrace;
import lab.ScienceTrace;

/** Native boundary adapter. Human processes never initialize the controller classes. */
public final class BotHooks {
    public static void tick(Object mc){if(BotRuntime.enabled())Client.tick(mc);}
    public static boolean suppressSeedPacket(Object packet){return BotRuntime.enabled()&&ConnectionScope.practice()&&lab.SeededStartClient.suppressPacket(packet);}
    private static final int FRAME_MS=Integer.getInteger("hmc.lwjgl.update_sleep",10);
    public static void frame(Object mc){
        if(!BotRuntime.enabled())return;
        if(lab.TrainingClock.ROOM){
            long extra=Math.round(FRAME_MS/lab.TrainingClock.rate())-FRAME_MS;
            if(extra>0)try{Thread.sleep(extra);}catch(InterruptedException ex){Thread.currentThread().interrupt();}
        }
        Client.frame(mc);
    }
    public static void attackLoop(){if(BotRuntime.enabled())ReplicationTrace.attackLoop();}
    public static double reach(){return BotRuntime.enabled()?Client.attackReach():3.0;}
    public static float ray(float original){return BotRuntime.enabled()?Client.selectionRayLength(original):original;}
    public static void click(Object mc,boolean after){if(BotRuntime.enabled()){if(after)ScienceTrace.clickAfter(mc);else{Client.inputWordConsumed(mc);ScienceTrace.clickBefore(mc);}}}
    public static void attack(Object self,Object target,boolean after){if(BotRuntime.enabled()){if(after)Client.attackAfter(self,target);else Client.attackBefore(self,target);}}
    public static void clearUse(Object self){if(BotRuntime.enabled())Client.blockUseClearing(self);}
    public static void apply(Object packet,boolean after){if(!BotRuntime.enabled())return;
        if(!after)Client.beforeSeedPacket();
        ReplicationTrace.apply(packet,after);
        if(after){String name=packet.getClass().getSimpleName();
            if(name.equals("S19PacketEntityStatus"))Client.hurtStatus(packet);
            else if(name.equals("S12PacketEntityVelocity"))Client.velocityPacket(packet);}
    }
    public static void receive(Object packet){if(BotRuntime.enabled())ReplicationTrace.receive(packet);}
    public static void movement(Object self,float strafe,float forward){if(BotRuntime.enabled())ScienceTrace.movementBefore(self,strafe,forward);}
    public static void moved(Object self){if(BotRuntime.enabled()){ScienceTrace.movementAfter(self);Client.inputWordMoved(self);}}
    public static void interpolation(Object self,boolean after){if(BotRuntime.enabled()){if(after)ScienceTrace.interpolationAfter(self);else ScienceTrace.interpolationBefore(self);}}
    public static void remote(Object self){if(BotRuntime.enabled())ScienceTrace.remoteUpdate(self);}
}
