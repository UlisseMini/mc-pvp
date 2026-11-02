package lab;

import java.nio.file.*;
import java.util.*;

/** Explicit local research setup, restricted to bot-v-bot timed boxing. */
public final class SeededStartServer {
    public static final class Session {
        final SeedState seed;final String name,hash;Path directory;long prepareNs,releaseNs;boolean released;
        final Set<String> validatedAcks=new HashSet<>();
        long suppressed;
        final Map<String,Integer> suppressedOutbound=new TreeMap<>();
        Session(SeedState seed,String name){this.seed=seed;this.name=name;this.hash=seed.sha256();}
        Map<String,Object> state(){return Map.of("name",name,"sha256",hash,"schema",1,
            "released",released,"release_ns",releaseNs,"scope","synthetic per-actor state; measured independent native clocks; empty setup combat queues");}
    }
    static final String[][] TRACKER={{"encoded_y","encodedPosY"},{"encoded_z","encodedPosZ"},
        {"yaw","encodedRotationYaw"},{"pitch","encodedRotationPitch"},{"head","lastHeadMotion"},
        {"update_counter","updateCounter"},{"forced_teleport_ticks","ticksSinceLastForcedTeleport"},
        {"vx","lastTrackedEntityMotionX"},{"vy","lastTrackedEntityMotionY"},{"vz","motionZ"},
        {"ground","onGround"},{"first_update_done","firstUpdateDone"},{"player_entities_updated","playerEntitiesUpdated"},
        {"last_position_y","lastTrackedEntityPosY"},{"last_position_z","lastTrackedEntityPosZ"}};
    public static SeedState named(String name)throws Exception{
        if(!name.matches("[a-zA-Z0-9_-]{1,64}"))throw new IllegalArgumentException("Seed name uses letters, digits, dash or underscore");
        return SeedState.load(R.ROOT.resolve("seeds/"+name+".properties"));
    }
    static void configure(Referee.Match m)throws Exception{
        String name=m.configA.getProperty("seed_name","");if(name.isEmpty())return;
        if(!m.scenario||!m.boxing()||!Referee.isBot(m.a)||!Referee.isBot(m.b))throw new IllegalArgumentException("Seeds require timed bot-v-bot boxing");
        if(m.pingA>500||m.pingB>500)throw new IllegalArgumentException("Seed quiescence currently supports RTT up to500ms");
        if(!name.equals(m.configB.getProperty("seed_name")))throw new IllegalArgumentException("Both sides must share one seed");
        m.seed=new Session(named(name),name);
    }
    static void prepare(Referee.Match m)throws Exception{
        if(m.seed==null)return;Session s=m.seed;s.prepareNs=System.nanoTime();
        s.directory=Referee.DATA.resolve("seed-starts/"+m.id);Files.createDirectories(s.directory);
        Files.writeString(s.directory.resolve("seed.properties"),s.seed.canonicalText());
        m.start=System.currentTimeMillis()+30_000;m.setupReseated=true;
        for(Properties config:List.of(m.configA,m.configB)){
            config.setProperty("seed_file","runtime/seed-starts/"+m.id+"/seed.properties");
            config.setProperty("seed_sha256",s.hash);config.setProperty("seed_origin_x",Double.toString(4096.0*(m.arena+1)));
            config.setProperty("science_trace","1");config.setProperty("science_network_trace","1");
        }
    }
    static void tick(Referee.Match m)throws Exception{
        Session s=m.seed;if(s==null)return;
        long ns=System.nanoTime();
        if(s.releaseNs==0){
            if(ns-s.prepareNs>25_000_000_000L){Referee.finish(m,"invalid_seed_readiness_timeout");return;}
            if(ready(m,"a")&&ready(m,"b")){
                long delay=2500;long wall=System.currentTimeMillis(),anchor=System.nanoTime();
                s.releaseNs=anchor+delay*1_000_000;m.start=wall+delay;
                Properties commit=new Properties();commit.setProperty("match",m.id);commit.setProperty("seed_sha256",s.hash);
                commit.setProperty("release_ns",Long.toString(s.releaseNs));commit.setProperty("release_ms",Long.toString(m.start));
                R.writeProperties(s.directory.resolve("commit.properties"),commit);
                Referee.control(m.a,m.pa,m.b,m,m.pingA,m.pingB);Referee.control(m.b,m.pb,m.a,m,m.pingB,m.pingA);
                Referee.append(m.replay,Map.of("type","seed_commit","match",m.id,"time",System.currentTimeMillis(),
                    "monotonic_ns",anchor,"release_ns",s.releaseNs,"release_ms",m.start,"seed_sha256",s.hash));
                PovArchives.manifest(m,false);
            }
        }else if(ns>s.releaseNs+2_000_000_000L&&(!released(m,"a")||!released(m,"b")||!s.released))Referee.finish(m,"invalid_seed_release_timeout");
    }
    private static boolean ack(Referee.Match m,String side,String phase){
        String key=side+"-"+phase;if(m.seed.validatedAcks.contains(key))return true;
        String who=side.equals("a")?m.a:m.b;
        Properties p=R.properties(Referee.DATA.resolve("bots/"+who.substring(7)+"/seed-"+m.id+"-"+phase+".properties"));
        boolean valid=m.id.equals(p.getProperty("match"))&&m.seed.hash.equals(p.getProperty("seed_sha256"));
        if(valid&&!Files.exists(m.seed.directory.resolve(side+"-"+phase+".properties")))
            R.writeProperties(m.seed.directory.resolve(side+"-"+phase+".properties"),p);
        if(valid)m.seed.validatedAcks.add(key);
        return valid;
    }
    static boolean ready(Referee.Match m,String side){return ack(m,side,"ready");}
    static boolean released(Referee.Match m,String side){return ack(m,side,"released");}
    /** Called at the native server tick entry, before task drain/world updates. */
    public static void stage(Object server,String phase){
        if(!phase.equals("tick_start"))return;
        long ns=System.nanoTime();
        for(Referee.Match m:new ArrayList<>(Referee.matches.values())){
            Session s=m.seed;if(s==null||s.released||s.releaseNs==0||ns<s.releaseNs+(long)(s.seed.phaseServerMs*1_000_000))continue;
            try{
                Properties p=s.seed.canonicalProperties();double origin=4096.0*(m.arena+1);Map<String,Object> fields=new TreeMap<>();
                Map<String,Object> cleanup=new LinkedHashMap<>();
                cleanup.put("a",apply(Referee.player(m.a),s.seed.a,p,"a",origin,fields));
                cleanup.put("b",apply(Referee.player(m.b),s.seed.b,p,"b",origin,fields));
                s.released=true;
                Map<String,Object> e=new LinkedHashMap<>();e.put("type","server_seed_released");e.put("match",m.id);
                e.put("time",System.currentTimeMillis());e.put("monotonic_ns",ns);e.put("server_tick",Referee.ticks);
                e.put("target_release_ns",s.releaseNs+(long)(s.seed.phaseServerMs*1_000_000));e.put("seed_sha256",s.hash);
                e.put("origin_x",origin);e.put("fields",fields);e.put("suppressed_setup_packets",s.suppressed);
                e.put("setup_dirty_cleanup",cleanup);
                e.put("suppressed_setup_outbound",new TreeMap<>(s.suppressedOutbound));
                e.put("apply_complete_ns",System.nanoTime());Referee.append(m.replay,e);
            }catch(Throwable error){error.printStackTrace();Referee.finish(m,"invalid_seed_server_apply");}
        }
    }
    private static Map<String,Object> apply(Object player,SeedState.Side s,Properties p,String side,double origin,Map<String,Object> out){
        if(player==null)throw new IllegalStateException("Seed player missing");String prefix="server."+side+".";
        SeededEntity.body(player,s.server,p,prefix,origin);SeededEntity.bodySnapshot(player,out,prefix,origin);
        // A cold seed has no setup-caused metadata/attribute updates pending.
        // getChanged drains both per-entry and global watcher dirty flags;
        // func_111144_e alone would leave the per-entry dirty flags behind.
        Object watcher=R.call(player,"getDataWatcher");
        boolean watcherBefore=(Boolean)R.call(watcher,"hasObjectChanged");
        List<?> changed=(List<?>)R.call(watcher,"getChanged");
        Object attributes=R.call(player,"getAttributeMap");
        Set<?> changedAttributes=(Set<?>)R.call(attributes,"getAttributeInstanceSet");
        int attributesBefore=changedAttributes.size();
        float speedBefore=((Number)R.call(player,"getAIMoveSpeed")).floatValue();
        changedAttributes.clear();
        boolean watcherAfter=(Boolean)R.call(watcher,"hasObjectChanged");
        int attributesAfter=((Set<?>)R.call(attributes,"getAttributeInstanceSet")).size();
        float speedAfter=((Number)R.call(player,"getAIMoveSpeed")).floatValue();
        if(watcherAfter||attributesAfter!=0||Float.floatToIntBits(speedBefore)!=Float.floatToIntBits(speedAfter))
            throw new IllegalStateException("Seed setup update queues did not clear without changing movement speed");
        Map<String,Object> cleanup=new LinkedHashMap<>();
        cleanup.put("watcher_dirty_before",watcherBefore);cleanup.put("watcher_entries_drained",changed==null?0:changed.size());
        cleanup.put("watcher_dirty_after",watcherAfter);cleanup.put("changed_attributes_before",attributesBefore);
        cleanup.put("changed_attributes_after",attributesAfter);cleanup.put("movement_speed_before",speedBefore);
        cleanup.put("movement_speed_after",speedAfter);
        Object manager=R.call(R.get(player,"worldObj"),"getEntityTracker");
        Object tracker=R.call(R.get(manager,"trackedEntityHashTable"),"lookup",(int)R.call(player,"getEntityId"));
        if(tracker==null)throw new IllegalStateException("Seed tracker missing");String tp=prefix+"tracker.";
        SeededEntity.assign(tracker,p,tp,TRACKER);
        R.set(tracker,"encodedPosX",(int)Math.floor(origin*32)+s.tracker.encodedX);
        R.set(tracker,"lastTrackedEntityPosX",origin+s.tracker.lastPositionX);
        SeededEntity.read(tracker,out,tp,TRACKER);
        out.put(tp+"encoded_x",(int)R.num(tracker,"encodedPosX")-(int)Math.floor(origin*32));
        out.put(tp+"last_position_x",R.num(tracker,"lastTrackedEntityPosX")-origin);
        Object handler=R.get(player,"playerNetServerHandler");
        R.set(handler,"lastPosX",origin+s.netHandler.x);R.set(handler,"lastPosY",s.netHandler.y);R.set(handler,"lastPosZ",s.netHandler.z);R.set(handler,"hasMoved",s.netHandler.hasMoved);
        out.put(prefix+"handler.x",R.num(handler,"lastPosX")-origin);out.put(prefix+"handler.y",R.get(handler,"lastPosY"));
        out.put(prefix+"handler.z",R.get(handler,"lastPosZ"));out.put(prefix+"handler.has_moved",R.get(handler,"hasMoved"));
        return cleanup;
    }
    /** Only the server thread rejects setup combat/movement packets. Netty still
     * follows vanilla enqueue/quick-exit; no world state is read from it. */
    public static boolean suppressInput(Object handler,Object packet){
        if(!"server".equals(System.getProperty("lab.role"))||Referee.server==null||!(Boolean)R.call(Referee.server,"isCallingFromMinecraftThread"))return false;
        Referee.Match m=Referee.find(R.name(R.get(handler,"playerEntity")));
        if(m==null||m.seed==null||m.seed.released)return false;
        m.seed.suppressed++;return true;
    }
    /** Quiet tracker/combat output only after BOTH clients can already see their
     * canonical opponent. Spawn/chunk traffic remains normal during preparation.
     * Final server release permanently ends this setup-only suppression. */
    public static boolean suppressOutput(Object handler,Object packet){
        if(!"server".equals(System.getProperty("lab.role"))||Referee.server==null||!(Boolean)R.call(Referee.server,"isCallingFromMinecraftThread"))return false;
        Object recipient=R.get(handler,"playerEntity");Referee.Match m=Referee.find(R.name(recipient));
        if(m==null||m.seed==null||m.seed.releaseNs==0||m.seed.released)return false;
        String kind=R.classTag(packet);
        if(!(kind.equals("gv")||kind.startsWith("gv$")||Set.of("hz","hm","gi","hf","hk","ia","fq").contains(kind)))return false;
        int target=(int)R.num(packet,kind.equals("hm")?"entityID":"entityId");
        Object entity=R.call(R.get(recipient,"worldObj"),"getEntityByID",target);
        if(entity==null||!(R.name(entity).equals(m.a)||R.name(entity).equals(m.b)))return false;
        m.seed.suppressedOutbound.merge(kind,1,Integer::sum);return true;
    }
}
