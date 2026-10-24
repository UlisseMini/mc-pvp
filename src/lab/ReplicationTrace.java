package lab;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/** Read-only packet/tracker diagnostics. No trace value is available to policies. */
public final class ReplicationTrace {
    private record Context(String match,int self,int opponent) {}
    private static volatile Context context;
    private static final BlockingQueue<Map<String,Object>> received=new ArrayBlockingQueue<>(8192);
    private static final Map<Object,Long> packetIds=Collections.synchronizedMap(new WeakHashMap<>());
    private static final AtomicLong nextPacket=new AtomicLong(), sequence=new AtomicLong(), dropped=new AtomicLong();
    private static volatile long frames;
    private static Object currentTracker;

    private static synchronized long packetId(Object packet){
        return packetIds.computeIfAbsent(packet,key->nextPacket.incrementAndGet());
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
            if(lost>0)Client.log(Map.of("type","replication_trace_drop","time",System.currentTimeMillis(),"match",Client.match,"count",lost));
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
                e.put("server_position",List.of(R.num(entity,"serverPosX"),R.num(entity,"serverPosY"),R.num(entity,"serverPosZ")));
                if(entity==Client.opponent){
                    e.put("interpolation_steps",R.num(entity,"otherPlayerMPPosRotationIncrements"));
                    e.put("interpolation_goal",List.of(R.num(entity,"otherPlayerMPX"),R.num(entity,"otherPlayerMPY"),R.num(entity,"otherPlayerMPZ")));
                }
            }
            Client.log(e);
        }catch(Throwable e){Client.fail(e);}
    }
    private static Referee.Match match(Object player){
        Referee.Match m=ScienceTrace.tracedMatch(player);
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
            String k=kind(packet);
            if(k.equals("ip")||k.startsWith("ip$")){
                for(String key:List.of("x","y","z","yaw","pitch"))e.put(key,R.num(packet,key));
                for(String key:List.of("onGround","moving","rotating"))e.put(key,R.get(packet,key));
            }else e.put("action",R.get(packet,"action").toString());
            Referee.append(m.replay,e);
        }catch(Throwable e){if(Referee.errors++<10)e.printStackTrace();}
    }
}
