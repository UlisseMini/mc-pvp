package lab;

import java.util.*;
import java.nio.file.Path;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

/** Server-only replication diagnostics. */
public final class ServerReplicationTrace {
    private static final AtomicLong sequence=new AtomicLong();
    private static Object currentTracker;
    private static final List<Map<String,Object>> clockEvents=new ArrayList<>();
    private static final List<Referee.Match> clockMatches=new ArrayList<>();
    private static int clockWorld;
    private static final Map<Object,Long> packetIds=Collections.synchronizedMap(new WeakHashMap<>());
    private static final AtomicLong nextPacket=new AtomicLong(),timingDropped=new AtomicLong();
    private record TimingContext(String match,String player,Path replay,long snapshotTick){}
    private record PacketContext(TimingContext route,long packetId,String kind){}
    private record TimingRecord(Path replay,Map<String,Object> event){}
    private static volatile Map<String,TimingContext> timingContexts=Map.of();
    private static final ThreadLocal<PacketContext> scheduling=new ThreadLocal<>();
    private static final ThreadLocal<ArrayDeque<PacketContext>> handling=ThreadLocal.withInitial(ArrayDeque::new);
    private static final ArrayBlockingQueue<TimingRecord> timingRecords=new ArrayBlockingQueue<>(8192);

    public static synchronized long packetId(Object packet){return packetIds.computeIfAbsent(packet,p->nextPacket.incrementAndGet());}
    public static long currentPacketId(){PacketContext c=handling.get().peek();return c==null?-1:c.packetId;}
    private static boolean inputKind(Object packet){String k=kind(packet);return k.equals("ip")||k.startsWith("ip$")||k.equals("in")||k.equals("is");}
    private static void offer(PacketContext c,String type,boolean main,long ms,long ns){
        Map<String,Object> e=event(type);e.put("time",ms);e.put("monotonic_ns",ns);
        e.put("match",c.route.match);e.put("player",c.route.player);e.put("packet_id",c.packetId);
        e.put("packet_kind",c.kind);e.put("main_thread",main);
        if(main)e.put("server_tick",Referee.ticks);else e.put("server_tick_snapshot",c.route.snapshotTick);
        if(!timingRecords.offer(new TimingRecord(c.route.replay,e)))timingDropped.incrementAndGet();
    }
    /** Immutable context publication happens only on the server thread; Netty
     * never traverses Referee's mutable match map or reads live match settings. */
    private static void refreshTimingContexts(){
        Map<String,TimingContext> next=new HashMap<>();
        for(Referee.Match m:clockMatches)if(m.configA.getProperty("science_trace","0").equals("1")||m.configB.getProperty("science_trace","0").equals("1"))
            for(String player:List.of(m.a,m.b))next.put(player,new TimingContext(m.id,player,m.replay,Referee.ticks));
        timingContexts=Map.copyOf(next);
    }
    private static void flushTiming(){
        int count=timingRecords.size();TimingRecord record;
        for(int i=0;i<count&&(record=timingRecords.poll())!=null;i++){
            try{
                record.event.put("emitted_monotonic_ns",System.nanoTime());Referee.append(record.replay,record.event);
                var archive=PovArchives.active.get(record.event.get("match"));
                if(archive!=null)archive.event(Json.encode(record.event));
            }catch(Throwable e){timingDropped.incrementAndGet();}
        }
        long lost=timingDropped.getAndSet(0);
        if(lost>0)for(Referee.Match m:clockMatches){
            Map<String,Object> e=event("server_timing_drop");e.put("match",m.id);e.put("count",lost);
            try{Referee.append(m.replay,e);var archive=PovArchives.active.get(m.id);if(archive!=null)archive.event(Json.encode(e));}
            catch(Throwable ignored){}
        }
    }
    /** Both Netty entry (before native enqueue/quick-exit) and main-thread
     * service are recorded. Normal returns are separate; no game work is caught. */
    public static void handler(Object handler,Object packet,boolean after){
        if(!"server".equals(System.getProperty("lab.role")))return;
        long ns=System.nanoTime(),ms=System.currentTimeMillis();
        try{
            if(!inputKind(packet))return;
            if(!after){
                scheduling.remove();Object player=R.get(handler,"playerEntity");
                TimingContext route=timingContexts.get(R.name(player));if(route==null)return;
                boolean main=Referee.server!=null&&(Boolean)R.call(Referee.server,"isCallingFromMinecraftThread");
                PacketContext c=new PacketContext(route,packetId(packet),kind(packet));
                if(main)handling.get().push(c);else scheduling.set(c);
                offer(c,"server_packet_handler_enter",main,ms,ns);
            }else{
                ArrayDeque<PacketContext> stack=handling.get();PacketContext c=stack.peek();
                if(c!=null&&c.packetId==packetId(packet)){stack.pop();offer(c,"server_packet_handler_return",true,ms,ns);}
                else{c=scheduling.get();scheduling.remove();if(c!=null&&c.packetId==packetId(packet))offer(c,"server_packet_handler_return",false,ms,ns);}
            }
        }catch(Throwable e){timingDropped.incrementAndGet();}
    }
    /** Injected immediately after native Queue.add while its monitor is held.
     * Only a bounded offer occurs here: no file/JSON I/O and no queue mutation. */
    public static void queued(){
        if(!"server".equals(System.getProperty("lab.role")))return;
        long ns=System.nanoTime(),ms=System.currentTimeMillis();
        try{PacketContext c=scheduling.get();scheduling.remove();if(c!=null)offer(c,"server_packet_task_enqueued",false,ms,ns);}
        catch(Throwable e){timingDropped.incrementAndGet();}
    }

    /** Timestamp queue lock boundaries without file I/O while the job lock is held. */
    public static void clock(Object server,String phase){
        if(!"server".equals(System.getProperty("lab.role")))return;
        try{
            if(phase.equals("tick_start")){
                clockEvents.clear();clockMatches.clear();clockWorld=-1;
                for(Referee.Match m:Referee.matches.values())if(m.configA.getProperty("science_network_trace","0").equals("1")
                    ||m.configB.getProperty("science_network_trace","0").equals("1"))clockMatches.add(m);
                refreshTimingContexts();
            }
            if(clockMatches.isEmpty()){if(phase.equals("tick_end"))flushTiming();return;}
            if(phase.equals("world_tick_start"))clockWorld++;
            Map<String,Object> e=event("replication_server_clock");
            e.put("server_tick",Referee.ticks);e.put("phase",phase);e.put("world_ordinal",clockWorld);
            clockEvents.add(e);
            if(phase.equals("tick_end")){
                for(Referee.Match m:clockMatches)for(Map<String,Object> entry:clockEvents){
                    Map<String,Object> copy=new LinkedHashMap<>(entry);copy.put("match",m.id);Referee.append(m.replay,copy);
                }
                flushTiming();
                clockEvents.clear();clockMatches.clear();
            }
        }catch(Throwable e){if(Referee.errors++<10)e.printStackTrace();}
    }
    private static Map<String,Object> event(String type){
        Map<String,Object> e=new LinkedHashMap<>();e.put("type",type);e.put("time",System.currentTimeMillis());
        e.put("monotonic_ns",System.nanoTime());e.put("sequence",sequence.incrementAndGet());
        e.put("thread",Thread.currentThread().getName());return e;
    }
    private static String kind(Object packet){return packet.getClass().getName();}
    private static boolean relevant(Object packet){
        String k=kind(packet);return k.equals("gv")||k.startsWith("gv$")||Set.of("hz","hm","gi","hk").contains(k);
    }
    private static int targetId(Object packet){return (int)R.num(packet,kind(packet).equals("hm")?"entityID":"entityId");}
    private static Map<String,Object> packet(Object packet){
        Map<String,Object> p=new LinkedHashMap<>();String k=kind(packet);p.put("kind",k);p.put("entity_id",targetId(packet));
        if(k.equals("gv")||k.startsWith("gv$")||k.equals("hz")){
            for(String key:List.of("posX","posY","posZ","yaw","pitch"))p.put(key,R.num(packet,key));
            p.put("onGround",R.get(packet,"onGround"));
            if(k.startsWith("gv"))p.put("rotating",R.get(packet,"field_149069_g"));
        }else if(k.equals("hm"))for(String key:List.of("motionX","motionY","motionZ"))p.put(key,R.num(packet,key));
        else if(k.equals("gi"))p.put("status",R.num(packet,"logicOpcode"));
        return p;
    }
    private static Referee.Match match(Object player){
        Referee.Match m=ServerScienceTrace.tracedMatch(player);
        return m!=null&&(m.configA.getProperty("science_network_trace","0").equals("1")||m.configB.getProperty("science_network_trace","0").equals("1"))?m:null;
    }
    private static Map<String,Object> tracker(Object entry){
        Map<String,Object> s=new LinkedHashMap<>();Object target=R.get(entry,"trackedEntity");
        s.put("entity_id",R.call(target,"getEntityId"));s.put("player",R.name(target));
        for(String k:List.of("updateCounter","updateFrequency","encodedPosX","encodedPosY","encodedPosZ","encodedRotationYaw","encodedRotationPitch","ticksSinceLastForcedTeleport"))s.put(k,R.num(entry,k));
        s.put("previous_onGround",R.get(entry,"onGround"));s.put("onGround",R.get(target,"onGround"));
        s.put("isAirBorne",R.get(target,"isAirBorne"));s.put("velocityChanged",R.get(target,"velocityChanged"));
        s.put("watcher_dirty",R.call(R.call(target,"getDataWatcher"),"hasObjectChanged"));
        s.put("position",List.of(R.num(target,"posX"),R.num(target,"posY"),R.num(target,"posZ")));
        s.put("sprint",R.call(target,"isSprinting"));return s;
    }
    public static void tracker(Object entry,boolean after){
        if(!"server".equals(System.getProperty("lab.role")))return;
        try{
            if(after)currentTracker=null;
            Object target=R.get(entry,"trackedEntity");Referee.Match m=match(target);
            if(m!=null){Map<String,Object> e=event(after?"replication_tracker_after":"replication_tracker_before");
                e.put("match",m.id);e.put("server_tick",Referee.ticks);e.put("tracker",tracker(entry));Referee.append(m.replay,e);
                currentTracker=after?null:entry;
            }
        }catch(Throwable e){if(Referee.errors++<10)e.printStackTrace();}
    }
    public static void send(Object handler,Object packet){
        if(!"server".equals(System.getProperty("lab.role"))||!relevant(packet))return;
        try{
            Object recipient=R.get(handler,"playerEntity");Referee.Match m=match(recipient);if(m==null)return;
            Object target=R.call(R.get(recipient,"worldObj"),"getEntityByID",targetId(packet));
            if(target==null||(!R.name(target).equals(m.a)&&!R.name(target).equals(m.b)))return;
            Map<String,Object> e=event("replication_send");e.put("match",m.id);e.put("server_tick",Referee.ticks);
            e.put("recipient",R.name(recipient));e.put("target",R.name(target));e.put("packet",packet(packet));
            if(currentTracker!=null)e.put("tracker_at_send",tracker(currentTracker));Referee.append(m.replay,e);
        }catch(Throwable e){if(Referee.errors++<10)e.printStackTrace();}
    }
    public static void input(Object handler,Object packet){
        if(!"server".equals(System.getProperty("lab.role")))return;
        try{
            if(Referee.server==null||!(Boolean)R.call(Referee.server,"isCallingFromMinecraftThread"))return;
            Object player=R.get(handler,"playerEntity");Referee.Match m=match(player);if(m==null)return;
            Map<String,Object> e=event("replication_server_input");e.put("match",m.id);e.put("server_tick",Referee.ticks);e.put("player",R.name(player));e.put("packet_kind",kind(packet));
            e.put("packet_id",packetId(packet));
            String k=kind(packet);
            if(k.equals("ip")||k.startsWith("ip$")){
                for(String key:List.of("x","y","z","yaw","pitch"))e.put(key,R.num(packet,key));
                for(String key:List.of("onGround","moving","rotating"))e.put(key,R.get(packet,key));
            }else e.put("action",R.get(packet,"action").toString());
            Referee.append(m.replay,e);
        }catch(Throwable e){if(Referee.errors++<10)e.printStackTrace();}
    }
}
