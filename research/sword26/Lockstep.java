package sword;

import io.netty.channel.ChannelHandlerContext;
import java.lang.invoke.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundPingPacket;
import net.minecraft.network.protocol.common.ServerboundPongPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/** Tick-indexed transport for a private pair of stock game clients. Netty still
 * encodes/decodes every packet. Fences delimit complete batches on each stream. */
public final class Lockstep {
    static final String MODE=System.getProperty("sword.clock","");
    static final boolean ENABLED=!MODE.isEmpty(), FAST=MODE.equals("fast");
    private static final int HELLO=-1_650_000_000, BASE=HELLO+1, MAX_TICKS=1_000_000;
    private static final Map<Connection,Inbox> inboxes=new ConcurrentHashMap<>();
    private static final ThreadLocal<Boolean> delivering=ThreadLocal.withInitial(()->false);
    private static final MethodHandle nativeRead;
    static volatile boolean active;
    static long tick=-1;
    static int latency;
    private static ServerPlayer[] players;
    private static Path startFile;
    private static String startId;
    static {
        try{
            nativeRead=MethodHandles.privateLookupIn(Connection.class,MethodHandles.lookup()).findVirtual(Connection.class,
                "channelRead0",MethodType.methodType(void.class,ChannelHandlerContext.class,Packet.class));
        }catch(ReflectiveOperationException e){throw new ExceptionInInitializerError(e);}
    }
    record Received(ChannelHandlerContext context,Packet<?> packet){}
    static final class Inbox {
        boolean enabled;
        int read=-1;
        List<Received> pending=new ArrayList<>();
        final Map<Integer,List<Received>> completed=new HashMap<>();
    }
    static Inbox inbox(Connection connection){return inboxes.computeIfAbsent(connection,k->new Inbox());}
    public static boolean capture(Connection connection,ChannelHandlerContext ctx,Packet<?> packet){
        if(delivering.get())return false;
        Integer fence=null;
        if(packet instanceof ClientboundPingPacket p)fence=p.getId();
        if(packet instanceof ServerboundPongPacket p)fence=p.getId();
        Inbox box=inbox(connection);
        synchronized(box){
            if(fence!=null&&fence==HELLO){
                box.enabled=true;box.notifyAll();return true;
            }
            if(!box.enabled)return false;
            if(fence!=null&&fence>=BASE&&fence<BASE+MAX_TICKS){
                if(box.completed.put(fence-BASE,box.pending)!=null)throw new IllegalStateException("Duplicate clock fence "+fence);
                box.pending=new ArrayList<>();box.notifyAll();return true;
            }
            box.pending.add(new Received(ctx,packet));return true;
        }
    }
    static void drain(Connection connection,long through){
        Inbox box=inbox(connection);
        while(box.read<through){
            List<Received> batch;
            synchronized(box){
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(12);
                while((batch=box.completed.remove(box.read+1))==null){
                    long left=deadline-System.nanoTime();
                    if(left<=0)throw new IllegalStateException("Missing packet fence "+(box.read+1)+" at logical tick "+tick);
                    try{TimeUnit.NANOSECONDS.timedWait(box,left);}catch(InterruptedException e){throw new IllegalStateException(e);}
                }
                box.read++;
            }
            delivering.set(true);
            try{
                for(var received:batch)nativeRead.invokeExact(connection,received.context(),(Packet)received.packet());
            }catch(Throwable e){throw new IllegalStateException("Native packet delivery failed",e);}
            finally{delivering.remove();}
        }
    }
    static boolean ready(Connection c){Inbox box=inbox(c);synchronized(box){return box.enabled;}}
    static void acknowledge(Connection c){c.send(new ServerboundPongPacket(HELLO));}
    static void endClient(Connection c){c.send(new ServerboundPongPacket(marker()));}
    static int marker(){
        if(tick<0||tick>=MAX_TICKS)throw new IllegalStateException("Clock out of bounds "+tick);
        return BASE+(int)tick;
    }
    static Connection connection(ServerPlayer player){
        for(var c:inboxes.keySet())if(c.getPacketListener()==player.connection)return c;
        throw new IllegalStateException("Missing connection for "+player.getGameProfile().name());
    }
    static void refreshLatency(){
        try{
            if(startFile==null)startFile=Path.of(System.getProperty("sword.spool")).resolve("clock-start");
            String line=Files.readString(startFile).strip();
            if(!line.equals(startId)){latency=Integer.parseInt(line);startId=line;}
        }catch(java.io.IOException e){throw new IllegalStateException(e);}
    }
    public static void beforeServer(MinecraftServer server){
        if(!active){
            if(startFile==null)startFile=Path.of(System.getProperty("sword.spool")).resolve("clock-start");
            if(!Files.exists(startFile))return;
            var list=server.getPlayerList();
            players=new ServerPlayer[]{list.getPlayerByName("SwordLabA"),list.getPlayerByName("SwordLabB")};
            if(players[0]==null||players[1]==null)return;
            var connections=server.getConnection().getConnections();
            synchronized(connections){
                var second=connection(players[1]);connections.remove(second);connections.add(second);
            }
            for(var p:players)p.connection.send(new ClientboundPingPacket(HELLO));
            active=true;
        }
        tick++;refreshLatency();
        // Respawn replaces ServerPlayer while preserving its existing connection.
        for(int i=0;i<2;i++)players[i]=server.getPlayerList().getPlayerByName(i==0?"SwordLabA":"SwordLabB");
        for(var p:players)drain(connection(p),tick-latency);
    }
    /** Set the two tracker phases and exact display origins during the neutral
     * countdown. This removes the players' login timing from matched openings. */
    static void alignTracker(ServerPlayer p,ServerPlayer viewer,int phase) {
        try {
            var chunkMap=p.level().getChunkSource().chunkMap;
            var mapField=chunkMap.getClass().getDeclaredField("entityMap");mapField.setAccessible(true);
            var map=(it.unimi.dsi.fastutil.ints.Int2ObjectMap<?>)mapField.get(chunkMap);
            var tracked=map.get(p.getId());
            var field=tracked.getClass().getDeclaredField("serverEntity");field.setAccessible(true);
            var tracker=(net.minecraft.server.level.ServerEntity)field.get(tracked);
            var type=tracker.getClass();
            var count=type.getDeclaredField("tickCount");count.setAccessible(true);count.setInt(tracker,phase);
            var delay=type.getDeclaredField("teleportDelay");delay.setAccessible(true);delay.setInt(tracker,0);
            var codec=type.getDeclaredField("positionCodec");codec.setAccessible(true);
            ((net.minecraft.network.protocol.game.VecDeltaCodec)codec.get(tracker)).setBase(p.trackingPosition());
            var interp=type.getDeclaredField("interpolationTracker");interp.setAccessible(true);
            ((net.minecraft.world.entity.InterpolationTracker)interp.get(tracker)).clear();
            viewer.connection.send(net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket.of(p));
        }catch(ReflectiveOperationException e){throw new IllegalStateException("Cannot set initial 26.3 entity tracker phase",e);}
    }
    public static void afterServer(){
        if(active)for(var p:players)p.connection.send(new ClientboundPingPacket(marker()));
    }
    public static boolean unpaced(){return FAST&&active;}
    static long time(){return active?1_000_000+50*tick:System.currentTimeMillis();}
}
