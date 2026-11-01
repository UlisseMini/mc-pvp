package lab;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/** Read-only packet/tracker diagnostics. No trace value is available to policies. */
public final class ReplicationTrace {
    private static final class Context {final String match; final int self,opponent; Context(String m,int s,int o){match=m;self=s;opponent=o;}}
    private static volatile Context context;
    private static final BlockingQueue<Map<String,Object>> received=new ArrayBlockingQueue<>(8192);
    private static final Map<Object,Long> packetIds=Collections.synchronizedMap(new WeakHashMap<>());
    private static final AtomicLong nextPacket=new AtomicLong(), sequence=new AtomicLong(), dropped=new AtomicLong();
    private static volatile long frames;
    private static String lastLoopMatch="";
    private static long lastLoopTick=Long.MIN_VALUE;

    private static boolean timingEnabled(){
        return ScienceTrace.client()&&Client.controls.getProperty("science_network_trace","0").equals("1");
    }
    /** Recorded after setup/guard work, but its timestamps are the original
     * native tick entry reads. Wall now remains the value used by W-tap/opening. */
    public static void inputHead(long wallNow,long monotonicNanos,long decisionNanos){
        try{if(!timingEnabled())return;
            Map<String,Object> e=event("native_input_head");e.put("emitted_monotonic_ns",e.get("monotonic_ns"));
            e.put("time",wallNow);e.put("wall_now_ms",wallNow);e.put("monotonic_ns",monotonicNanos);
            e.put("decision_ns",decisionNanos);
            e.put("match",Client.match);e.put("native_tick",Client.ticks);Client.log(e);
        }catch(Throwable e){dropped.incrementAndGet();}
    }
    /** This emits only after the original admission branch. Poll/completion
     * timestamps are the existing cap reads, never replacement clock samples. */
    public static void capPoll(long pollNanos,long previousCompleted,boolean nativePoll,boolean gate,boolean admitted,long completion){
        try{if(!timingEnabled())return;
            Map<String,Object> e=event("native_cap_poll");e.put("emitted_monotonic_ns",e.get("monotonic_ns"));
            // Keep the envelope's wall/mono pair consistent for generic clock
            // calibration; the earlier actual opportunity is explicitly poll_ns.
            e.put("poll_ns",pollNanos);e.put("match",Client.match);e.put("native_tick",Client.ticks);
            e.put("source",nativePoll?"native_tick":"frame");e.put("previous_completed_ns",previousCompleted);
            e.put("minimum_interval_ns",ClickRate.intervalNanos(Client.controls));e.put("gate_at_poll",gate);
            e.put("admitted",admitted);e.put("completion_ns",completion);
            e.put("poll_to_completion_ns",admitted?completion-pollNanos:-1);Client.log(e);
        }catch(Throwable e){dropped.incrementAndGet();}
    }
    static boolean firstAttackLoop(String match,long tick){
        if(match.equals(lastLoopMatch)&&tick==lastLoopTick)return false;
        lastLoopMatch=match;lastLoopTick=tick;return true;
    }
    /** The hook executes at the native loop condition, including its empty
     * false check. Repeated conditions in one press loop produce one marker. */
    public static void attackLoop(){
        try{if(!timingEnabled()||!firstAttackLoop(Client.match,Client.ticks))return;
            Map<String,Object> e=event("native_attack_loop");e.put("match",Client.match);e.put("native_tick",Client.ticks);Client.log(e);
        }catch(Throwable e){dropped.incrementAndGet();}
    }

    private static synchronized long packetId(Object packet){
        return packetIds.computeIfAbsent(packet,key->nextPacket.incrementAndGet());
    }
    private static Map<String,Object> event(String type){
        Map<String,Object> e=new LinkedHashMap<>();e.put("type",type);e.put("time",System.currentTimeMillis());
        e.put("monotonic_ns",System.nanoTime());e.put("sequence",sequence.incrementAndGet());
        e.put("thread",Thread.currentThread().getName());return e;
    }
    private static String kind(Object packet){return R.classTag(packet);}
    private static boolean relevant(Object packet){
        String k=kind(packet);return k.equals("gv")||k.startsWith("gv$")||Compat.set("hz","hm","gi","hk").contains(k);
    }
    private static int targetId(Object packet){return (int)R.num(packet,kind(packet).equals("hm")?"entityID":"entityId");}
    private static Map<String,Object> packet(Object packet){
        Map<String,Object> p=new LinkedHashMap<>();String k=kind(packet);p.put("kind",k);p.put("entity_id",targetId(packet));
        if(k.equals("gv")||k.startsWith("gv$")||k.equals("hz")){
            for(String key:Compat.list("posX","posY","posZ","yaw","pitch"))p.put(key,R.num(packet,key));
            p.put("onGround",R.get(packet,"onGround"));
            if(k.startsWith("gv"))p.put("rotating",R.get(packet,"field_149069_g"));
        }else if(k.equals("hm"))for(String key:Compat.list("motionX","motionY","motionZ"))p.put(key,R.num(packet,key));
        else if(k.equals("gi"))p.put("status",R.num(packet,"logicOpcode"));
        return p;
    }
    private static boolean concerns(Context c,Object packet){
        if(c==null||!relevant(packet))return false;
        int id=targetId(packet);return id==c.self||id==c.opponent;
    }
    private static Map<String,Object> clientPacket(String type,Context c,Object packet){
        Map<String,Object> e=event(type);e.put("match",c.match);e.put("packet_id",packetId(packet));
        e.put("packet",packet(packet));e.put("client_tick",Client.ticks);e.put("frame",frames);return e;
    }
    public static void frame(){
        if(!"client".equals(System.getProperty("lab.role")))return;
        try{
            frames++;
            Context next=ScienceTrace.client()&&Client.opponent!=null&&Client.controls.getProperty("science_network_trace","0").equals("1")
                ?new Context(Client.match,((Number)R.call(Client.player,"getEntityId")).intValue(),((Number)R.call(Client.opponent,"getEntityId")).intValue()):null;
            context=next;
            Map<String,Object> e;while((e=received.poll())!=null)Client.log(e);
            long lost=dropped.getAndSet(0);
            if(lost>0)Client.log(Compat.map("type","replication_trace_drop","time",System.currentTimeMillis(),"match",Client.match,"count",lost));
            if(next!=null){e=event("replication_frame");e.put("match",next.match);e.put("frame",frames);e.put("client_tick",Client.ticks);Client.log(e);}
        }catch(Throwable e){Client.fail(e);}
    }
    /** Netty thread: enqueue immutable diagnostics; never write the client file here. */
    public static void receive(Object packet){
        if(!"client".equals(System.getProperty("lab.role")))return;
        try{Context c=context;if(!concerns(c,packet))return;
            if(!received.offer(clientPacket("replication_receive",c,packet)))dropped.incrementAndGet();
        }catch(Throwable e){dropped.incrementAndGet();}
    }
    public static void apply(Object packet,boolean after){
        if(!"client".equals(System.getProperty("lab.role")))return;
        try{
            Context c=context;if(!concerns(c,packet)||!(Boolean)R.call(Client.mc,"isCallingFromMinecraftThread"))return;
            Map<String,Object> e=clientPacket(after?"replication_apply_after":"replication_apply_before",c,packet);
            Object entity=R.call(R.get(Client.mc,"theWorld"),"getEntityByID",targetId(packet));
            if(entity!=null){
                e.put("state",ScienceTrace.state(entity));
                e.put("server_position",Compat.list(R.num(entity,"serverPosX"),R.num(entity,"serverPosY"),R.num(entity,"serverPosZ")));
                if(entity==Client.opponent){
                    e.put("interpolation_steps",R.num(entity,"otherPlayerMPPosRotationIncrements"));
                    e.put("interpolation_goal",Compat.list(R.num(entity,"otherPlayerMPX"),R.num(entity,"otherPlayerMPY"),R.num(entity,"otherPlayerMPZ")));
                }
            }
            Client.log(e);
        }catch(Throwable e){Client.fail(e);}
    }
}
