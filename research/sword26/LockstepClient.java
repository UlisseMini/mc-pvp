package sword;

import java.lang.invoke.*;
import net.minecraft.client.Minecraft;
import net.minecraft.util.thread.BlockableEventLoop;

/** The fast render loop still calls Minecraft.tick(), including native picking,
 * button processing, player travel and the complete ClientLevel tick. */
public final class LockstepClient {
    private static final MethodHandle tasks;
    static{
        try{tasks=MethodHandles.privateLookupIn(BlockableEventLoop.class,MethodHandles.lookup())
            .findVirtual(BlockableEventLoop.class,"runAllTasks",MethodType.methodType(void.class));}
        catch(ReflectiveOperationException e){throw new ExceptionInInitializerError(e);}
    }
    static boolean localTicksFirst(Minecraft mc,net.minecraft.world.entity.Entity other) throws ReflectiveOperationException {
        var f=net.minecraft.client.multiplayer.ClientLevel.class.getDeclaredField("tickingEntities");
        f.setAccessible(true);
        var list=(net.minecraft.world.level.entity.EntityTickList)f.get(mc.level);
        // Login/respawn insertion order otherwise changes an overlap push between repeats.
        // At the neutral opening both orders are legal. The orientation picks one per seat.
        if(!list.contains(mc.player)||!list.contains(other))throw new IllegalStateException("Opening outside ticking sections");
        var last=mc.player.getX()>=0?other:mc.player;
        list.remove(last);list.add(last);
        var order=new java.util.ArrayList<net.minecraft.world.entity.Entity>(2);
        list.forEach(e->{if(e==mc.player||e==other)order.add(e);});
        if(order.size()!=2)throw new IllegalStateException("Missing fighter in the opening tick list");
        return order.getFirst()==mc.player;
    }
    public static void beforeTick(Minecraft mc){
        var listener=mc.getConnection();if(listener==null)return;
        var c=listener.getConnection();
        if(!Lockstep.active){
            if(!Lockstep.ready(c))return;
            Lockstep.acknowledge(c);Lockstep.active=true;
        }
        Lockstep.tick++;Lockstep.refreshLatency();
        Lockstep.drain(c,Lockstep.tick-1-Lockstep.latency);
    }
    public static void afterTick(Minecraft mc){
        if(Lockstep.active)Lockstep.endClient(mc.getConnection().getConnection());
    }
    public static boolean fastFrame(Minecraft mc){
        if(!Lockstep.unpaced()||mc.getConnection()==null||!mc.getConnection().hasClientLoaded()
                ||mc.gui.screen()!=null||mc.gui.overlay()!=null)return false;
        mc.packetProcessor().processQueuedPackets();
        try{tasks.invoke(mc);}catch(Throwable e){throw new IllegalStateException(e);}
        mc.tick();return true;
    }
}
