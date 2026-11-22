package practice.bridge;

import practice.api.*;
import practice.core.*;
import static practice.core.JsonData.map;
import com.google.gson.*;
import io.netty.buffer.*;
import io.netty.channel.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import net.minecraft.client.Minecraft;
import net.minecraft.network.*;
import net.minecraft.network.play.client.C17PacketCustomPayload;
import net.minecraft.network.play.server.S3FPacketCustomPayload;

/** Wire, application, input and view observations with one shared event sequence. */
final class PracticeCapture implements ObserverHooks.Sink,PracticeServices {
    static final String CHANNEL="PvpLab|v1";
    private final ReloadHost host;
    private final Path directory;
    private final String bridgeSha256;
    private final AtomicLong sequence=new AtomicLong(),packetSequence=new AtomicLong(),eventDrops=new AtomicLong();
    private final Map<Object,Long> packetIds=Collections.synchronizedMap(new WeakHashMap<>());
    private final Set<Channel> unrecordedPlay=Collections.newSetFromMap(new ConcurrentHashMap<Channel,Boolean>());
    private final Set<Channel> clients=Collections.newSetFromMap(new ConcurrentHashMap<Channel,Boolean>());
    private final ArrayBlockingQueue<PracticeEvent> events=new ArrayBlockingQueue<>(2048);
    private final ThreadLocal<Raw> pending=new ThreadLocal<>();
    private final ThreadLocal<Object> schedulingPacket=new ThreadLocal<>();
    private volatile CaptureLog log;
    private final Queue<CaptureLog> finishing=new ConcurrentLinkedQueue<>();
    private volatile Channel active;
    private volatile String session="",match="";
    private volatile boolean offlinePlayback;
    private volatile boolean recording=true,frames=true,coordination=true,remoteRecording;
    private final RemoteCaptureUpload remoteUpload;
    private volatile String remoteSession="";
    private volatile long tick;
    private long lastFrame;
    private volatile long lastHello,lastTelemetry;
    private volatile boolean started;
    private volatile long lastServer;
    private String sprintHitMatch="";
    private long sprintHitUntil;
    private boolean sprintHitBonus;

    Boolean liveSprintHit(){
        return practiceAllowed()&&sprintHitMatch.equals(match)&&System.nanoTime()<sprintHitUntil?sprintHitBonus:null;
    }
    private volatile Map<String,Object> lastState=Collections.emptyMap();
    private final Gson gson=new Gson();
    private final PracticeViews views=new PracticeViews();
    private final PracticeViewPublisher publisher=new PracticeViewPublisher();
    private long botEndAt;
    final ReplayPresentation replay=new ReplayPresentation();
    private long lastViewRecord;
    private Map<String,Object> lastViews=Collections.emptyMap();
    private Map<String,Object> lastReplayFrame=Collections.emptyMap();
    Map<String,Object> replayFrame(){return lastReplayFrame;}
    private final MatchArchive archive=new MatchArchive();
    private long lastPose;
    private static final class Raw {Channel channel;byte[] bytes;long ms,ns;Raw(Channel c,byte[] b){channel=c;bytes=b;ms=System.currentTimeMillis();ns=System.nanoTime();}}
    PracticeCapture(ReloadHost host,Path directory,Path source){this.host=host;this.directory=directory;this.bridgeSha256=bridgeHash(source);this.remoteUpload=new RemoteCaptureUpload(directory);}
    String moduleSha256(){return host.moduleSha256();}
    void settings(Properties p){
        boolean next=Boolean.parseBoolean(p.getProperty("capture.enabled","true"));
        frames=Boolean.parseBoolean(p.getProperty("capture.frames","true"));coordination=Boolean.parseBoolean(p.getProperty("link.enabled","true"));
        String archivePath=p.getProperty("archive.directory","").trim();baseArchive=archivePath.isEmpty()?null:Paths.get(archivePath);
        if(recording&&!next&&log!=null&&active!=null&&loopback(active)){emit("capture_stopped",map("reason","user"),false);finishLog();}recording=next;archiveLocation();
    }
    synchronized void remoteRecording(boolean next){
        if(remoteRecording&&!next&&active!=null&&!loopback(active)){emit("capture_stopped",map("reason","remote_record_off"),false);finishLog();}
        remoteRecording=next;
    }
    private static boolean loopback(Channel c){return ConnectionScope.local(c);}
    void offlinePlayback(boolean value){offlinePlayback=value;if(value){finishLog();archive.end();publisher.close();views.close();replay.clear();lastReplayFrame=Collections.emptyMap();}}
    boolean practiceAllowed(){return !offlinePlayback&&ConnectionScope.practice();}
    private boolean observing(){return !offlinePlayback&&active!=null&&active==ConnectionScope.current()&&active.isActive()&&(loopback(active)||remoteRecording);}
    private boolean remote(){return active!=null&&!loopback(active);}
    CaptureLog remoteLog(){
        CaptureLog writer=log;
        return writer!=null&&writer.accepting()&&observing()&&remote()?writer:null;
    }
    private String serverHost(Channel c){
        Object address=GameObservations.field(Minecraft.func_71410_x().func_147104_D(),"serverIP");
        String host=address instanceof String?(String)address:ConnectionScope.host(c);host=host.replaceAll("[\\r\\n\\p{Cntrl}]","");return host.substring(0,Math.min(255,host.length()));
    }
    private boolean shouldRecord(Channel c){return loopback(c)?recording:remoteRecording;}
    public void connected(Object manager,Object context){
        Channel c=((ChannelHandlerContext)context).channel();
        Object direction=GameObservations.field(manager,"direction");
        // The same class is also used by an integrated server. Observe client sockets only.
        if(direction==null || !direction.toString().equals("CLIENTBOUND"))return;
        ConnectionScope.connected(c);clients.add(c);c.closeFuture().addListener(f->{TrainingTimer.disconnected(c);ConnectionScope.disconnected(c);clients.remove(c);unrecordedPlay.remove(c);disconnected(c);});
    }
    private synchronized void disconnected(Channel c){if(active==c){emit("connection_end",map(),false);finishLog();active=null;match="";lastServer=0;lastState=Collections.emptyMap();archive.end();publisher.close();archiveLocation();}}
    private java.nio.file.Path baseArchive;
    private void archiveLocation(){
        java.nio.file.Path dir=baseArchive;
        if(dir!=null && !BotRuntime.enabled() && TrainingTimer.active())dir=dir.resolveSibling("slow-sumo").resolve("runtime/pov");
        archive.root(recording?dir:null);replay.root(dir);
    }
    private void finishLog(){CaptureLog l=log;log=null;if(l!=null){l.close();finishing.add(l);}}
    private boolean play(Channel c){if(offlinePlayback)return false;boolean play=clients.contains(c)&&c.attr(NetworkManager.field_150739_c).get()==EnumConnectionState.PLAY;if(play&&!loopback(c)&&!remoteRecording)unrecordedPlay.add(c);return play&&(loopback(c)||remoteRecording);}
    private synchronized void session(Channel c){
        if(active!=c){finishLog();active=c;session=System.currentTimeMillis()+"-"+UUID.randomUUID().toString();match="";lastServer=0;lastState=Collections.emptyMap();lastHello=0;lastTelemetry=0;started=unrecordedPlay.contains(c);remoteSession="r"+UUID.randomUUID().toString().replace("-","");archive.end();publisher.close();views.close();lastViews=Collections.emptyMap();replay.clear();}
        if(shouldRecord(c)&&log==null){
            boolean remote=remote();
            String name=(remote?"a-client-":"")+session+"-"+System.nanoTime()+(remote?"-events":"")+".jsonl.gz";
            Path path=directory.resolve("captures").resolve(name);
            if(remote){
                log=new CaptureLog(path,16L*1024*1024,10000,2000,8192,96L*1024*1024,
                    remoteUpload.register(remoteSession,serverHost(c),path));
            }else log=new CaptureLog(path,256L*1024*1024,128);
            emit("capture_start",map("schema",2,"minecraft","1.8.9","protocol",47,"bridge","0.4.0","module_status",host.status(),"clock","unix_ms_shared_host; monotonic_ns_process_local",
                "packet_bytes","uncompressed PLAY packet id + body, base64","login_recorded",false,
                "role",remote()?"human-remote":BotRuntime.role(),"server_host",serverHost(c),"server_endpoint",ConnectionScope.host(c),"remote_session",remote()?remoteSession:null,"coordination_allowed",loopback(c),
                "started_mid_connection",started,"render_sampling_hz",60,"segment_limit_bytes",(remote()?16L:256L)*1024*1024,"max_segments",remote()?10000:128,"rotation_ms",remote()?2000:0),false);
        }
        started=true;
    }
    public void wireBefore(Object context,Object buffer){
        pending.remove();Channel c=((ChannelHandlerContext)context).channel();if(!play(c))return;session(c);
        ByteBuf b=(ByteBuf)buffer;int size=b.readableBytes();if(size>2097152){emit("packet_oversize",map("bytes",size),true);return;}
        byte[] copy=new byte[size];b.getBytes(b.readerIndex(),copy);pending.set(new Raw(c,copy));
    }
    public void wireAfter(Object packets){
        Raw raw=pending.get();pending.remove();if(raw==null)return;
        List<?> list=(List<?>)packets;if(list.isEmpty())return;Object packet=list.get(list.size()-1);
        if(ConnectionScope.canSend(raw.channel))BotHooks.receive(packet);
        wire("in",packet,raw);
    }
    public void wireOut(Object context,Object packet,Object buffer){
        Channel c=((ChannelHandlerContext)context).channel();if(!play(c))return;session(c);
        ByteBuf b=(ByteBuf)buffer;int size=b.readableBytes();if(size>2097152){emit("packet_oversize",map("bytes",size),true);return;}
        byte[] copy=new byte[size];b.getBytes(b.readerIndex(),copy);wire("out",packet,new Raw(c,copy));
    }
    private long packetId(Object p){synchronized(packetIds){Long id=packetIds.get(p);if(id==null){id=packetSequence.incrementAndGet();packetIds.put(p,id);}return id;}}
    private void wire(String direction,Object packet,Raw raw){
        long id=packetId(packet);Map<String,Object> meta=map("packet_id",id,"direction",direction,"kind",packet.getClass().getSimpleName(),"bytes",raw.bytes.length);
        boolean local=loopback(raw.channel),deliver=local||host.wantsRemoteCaptureEvents();
        boolean modulePayload=deliver&&raw.bytes.length<=16384;
        String encoded=local||modulePayload?Base64.getEncoder().encodeToString(raw.bytes):null;
        long seq=sequence.incrementAndGet();
        Map<String,Object> data=map("packet",meta,"base64",encoded);
        if(local){
            record("packet_wire",seq,raw.ms,raw.ns,data);
        }else{
            CaptureLog writer=log;
            if(writer!=null){
                Map<String,Object> row=observation("packet_wire",seq,raw.ms,raw.ns,data);
                byte[] bytes=raw.bytes;
                writer.offerDeferred(1024L+bytes.length+(encoded==null?0:2L*encoded.length()),()->{
                    if(encoded==null)data.put("base64",Base64.getEncoder().encodeToString(bytes));
                    return JsonData.encode(row);
                });
            }
        }
        // Live modules can decode normal gameplay packets without another hook
        // restart. Large chunk payloads stay in the full on-disk capture only.
        if(deliver){
            Map<String,Object> event=new LinkedHashMap<>(meta);
            if(modulePayload)event.put("base64",encoded);else event.put("module_payload_omitted",true);
            enqueue(new PracticeEvent("packet_wire",seq,raw.ms,raw.ns,event));
        }
    }
    public void apply(Object packet,boolean after){
        if(!observing()||!(packet instanceof Packet)||EnumConnectionState.func_150752_a((Packet)packet)!=EnumConnectionState.PLAY)return;
        Minecraft mc=Minecraft.func_71410_x();boolean main=mc.func_152345_ab();
        if(!main){if(after)schedulingPacket.remove();else schedulingPacket.set(packet);}
        if(main&&ConnectionScope.canSend(active))BotHooks.apply(packet,after);
        emit(after?"packet_handler_return":"packet_handler_enter",map("packet_id",packetId(packet),"kind",packet.getClass().getSimpleName(),"main_thread",main),true);
        if(!after&&main&&ConnectionScope.canSend(active)&&packet instanceof S3FPacketCustomPayload){
            S3FPacketCustomPayload p=(S3FPacketCustomPayload)packet;
            if(CHANNEL.equals(p.func_149169_c()))receive(p.func_180735_b());
        }
    }
    public void input(String kind,boolean after){
        if(observing())emit(kind+(after?"_after":"_before"),GameObservations.state(Minecraft.func_71410_x(),false),true);
    }
    public void motion(String kind,Object entity,boolean after){
        if(log==null)return;
        Minecraft mc=Minecraft.func_71410_x();
        net.minecraft.client.entity.EntityPlayerSP player=mc.field_71439_g;
        if(player==null||entity!=player||!observing())return;
        emit("motion_"+kind+(after?"_after":"_before"),map("self",map(
            "id",player.func_145782_y(),"motion",Arrays.asList(player.field_70159_w,player.field_70181_x,player.field_70179_y),
            "sprinting",player.func_70051_ag(),"ground",player.field_70122_E,
            "isCollidedHorizontally",player.field_70123_F,"isCollidedVertically",player.field_70124_G)),false);
    }
    public void clock(String phase){
        if(observing()&&(!archive.match().isEmpty()||remote()))emit("client_task_drain",map("phase",phase),false);
    }
    /** Called just after queue.add, before releasing the scheduled-task lock. */
    public void queued(){
        Object packet=schedulingPacket.get();schedulingPacket.remove();
        if(packet!=null&&observing()&&(!archive.match().isEmpty()||remote()))
            emit("packet_task_enqueued",map("packet_id",packetId(packet),"kind",packet.getClass().getSimpleName()),false);
    }
    void inputEvent(String kind,Map<String,Object> fields){if(observing())emit(kind,fields,true);}
    void tick(long tick,boolean after){
        this.tick=tick;if(!observing()){publisher.close();views.close();archive.end();replay.clear();lastViews=Collections.emptyMap();return;}
        if(!after&&practiceAllowed())publisher.tick();
        Minecraft mc=Minecraft.func_71410_x();if(mc.field_71439_g==null)return;
        Map<String,Object> sample=GameObservations.state(mc,true);
        net.minecraft.client.settings.KeyBinding sprint=mc.field_71474_y.field_151444_V;
        sample.put("sprint_key_code",sprint.func_151463_i());
        sample.put("sprint_key_down",sprint.func_151470_d());
        emit(after?"tick_end":"tick_start",sample,true);
        if(!after){
            if(tick%20==0)emit("capture_health",map("observer_errors",ObserverHooks.errors,"writer_drops",log==null?0:log.dropped()),true);
            if(tick%20==0)finishing.removeIf(CaptureLog::closed);
            int n=0;PracticeEvent e;while(n++<512 && (e=events.poll())!=null)host.event(e);
            long lost=eventDrops.getAndSet(0);if(lost>0)emit("module_event_drop",map("count",lost),false);
            long now=System.currentTimeMillis();
            if(ConnectionScope.canSend(active)&&coordination&&now-lastHello>=(BotRuntime.enabled()?500:2000)){lastHello=now;sendRaw(map("type","hello","version",1,"session",session,"client_ms",now,"fast_view",publisher.descriptor(),"role",BotRuntime.role(),"training_rate_ack",TrainingTimer.acknowledged()));}
            if(ConnectionScope.canSend(active)&&coordination&&lastServer>0&&now-lastTelemetry>=200){lastTelemetry=now;sendRaw(map("type","telemetry","version",1,"session",session,"match",match,"client_ms",now,"client_tick",tick,"view",GameObservations.telemetry(mc,lastState.get("players"))));}
        }
    }
    void replayRender(){if(!practiceAllowed()){replay.clear();lastReplayFrame=Collections.emptyMap();return;}Map<String,Object> data=replay.render(Minecraft.func_71410_x());lastReplayFrame=data;host.event(new PracticeEvent("replay_frame",sequence.incrementAndGet(),System.currentTimeMillis(),System.nanoTime(),data));}
    void render(float partial){
        if(offlinePlayback)return;
        if(!observing()||!frames)return;long now=System.nanoTime();if(now-lastFrame<16666666)return;lastFrame=now;
        Map<String,Object> view=GameObservations.state(Minecraft.func_71410_x(),true);view.put("partial_ticks",partial);emit("render_view",view,true);
    }
    Map<String,Object> linkedState(){return lastState;}
    Map<String,Object> viewState(){return lastViews;}
    void viewFrame(float partial){
        if(offlinePlayback)return;
        if(!practiceAllowed()){
            lastViews=Collections.emptyMap();views.close();publisher.close();
            if(observing()&&remote()&&System.nanoTime()-lastPose>=16666666){lastPose=System.nanoTime();emit("remote_pose",map("source","a-client","view",GameObservations.remoteReplayView(Minecraft.func_71410_x(),partial)),false);}
            return;
        }
        if(practiceAllowed())publisher.update(Minecraft.func_71410_x(),lastState,partial);
        Map<String,Object> data=views.update(Minecraft.func_71410_x(),active!=null&&coordination?lastState:Collections.emptyMap(),partial);
        lastViews=data;
        if(active!=null&&!archive.match().isEmpty()&&System.nanoTime()-lastPose>=16666666){lastPose=System.nanoTime();Map<String,Object> v=GameObservations.replayView(Minecraft.func_71410_x(),lastState.get("players"),partial);archive.pose(System.currentTimeMillis(),lastPose,v);}
        long seq=sequence.incrementAndGet(),ms=System.currentTimeMillis(),ns=System.nanoTime();
        // Deliver directly on the render thread, not through the next 20Hz tick.
        host.event(new PracticeEvent("practice_views",seq,ms,ns,data));
        if(!data.isEmpty()&&ns-lastViewRecord>=50000000){lastViewRecord=ns;record("practice_views",seq,ms,ns,data);}
    }
    /** Same observation/capture path, sampled by the native loop when no GL world is rendered. */
    void headlessFrame(){
        Minecraft mc=Minecraft.func_71410_x();
        Object timer=GameObservations.field(mc,"timer"),value=GameObservations.field(timer,"renderPartialTicks");
        float partial=value instanceof Number?((Number)value).floatValue():1;
        if(practiceAllowed())publisher.update(mc,lastState,partial);
        if(!practiceAllowed())viewFrame(partial);
        long ns=System.nanoTime();
        if(active!=null&&!archive.match().isEmpty()&&ns-lastPose>=16666666){lastPose=ns;
            archive.pose(System.currentTimeMillis(),ns,GameObservations.replayView(mc,lastState.get("players"),partial));}
        render(partial);
    }
    void botEvent(String encoded){archive.event(encoded);}
    /** Trusted local route precedes delayed server messages; only this route owns bot archives. */
    void botRoute(Properties p){
        if(!BotRuntime.enabled())throw new IllegalStateException("Bot route called by human host");
        String id=p.getProperty("match",""),side=p.getProperty("archive_side","");
        if(!Boolean.parseBoolean(p.getProperty("enabled","false"))){
            if(botEndAt==0)botEndAt=System.currentTimeMillis()+1000;
            if(System.currentTimeMillis()>=botEndAt){archive.end();match="";lastState=Collections.emptyMap();publisher.close();}
            return;
        }
        if(!id.matches("m[0-9]{4,9}")||!Arrays.asList("a","b").contains(side))throw new IllegalArgumentException("Invalid bot match archive route");
        String mine="LabSlot"+BotRuntime.instance.slot,other=p.getProperty("opponent","");
        if(!other.matches("[A-Za-z0-9_]{1,16}")||mine.equals(other))throw new IllegalArgumentException("Invalid bot opponent");
        botEndAt=0;match=id;
        List<String> players=side.equals("a")?Arrays.asList(mine,other):Arrays.asList(other,mine);
        lastState=map("match",id,"players",players,"combat_start",Long.parseLong(p.getProperty("start_at","0")));
        if(!archive.match().equals(id))archive.begin(id,side+"-client",metadata(mine));
    }
    private Map<String,Object> metadata(String player){return map("player",player,"session",session,"role",BotRuntime.role(),"backend",BotRuntime.enabled()?"forge":"human-forge","pose_hz",60,
        "minecraft","1.8.9","forge","11.15.1.2318","bridge_sha256",bridgeSha256,
        "module_sha256",host.moduleSha256(),"initial",GameObservations.state(Minecraft.func_71410_x(),true),"full_session_capture",log==null?null:log.path.toString());}
    private static String bridgeHash(Path path){
        try{
            if(!Files.isRegularFile(path))return "unreported";
            byte[] digest=java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path));
            StringBuilder hex=new StringBuilder();for(byte b:digest)hex.append(String.format("%02x",b&255));return hex.toString();
        }catch(Exception e){return "unreported";}
    }
    String status(){return "Remote record "+(remoteRecording?"ON":"OFF")+" | "+(log==null?"Capture off":log.status())+" | "+remoteUpload.status()+" | observer errors "+ObserverHooks.errors+" | link "+(lastServer==0?"waiting":System.currentTimeMillis()-lastServer+" ms old")+" | "+(remote()?remoteSession:match);}
    void emit(String type,Map<String,Object> data,boolean deliver){
        long seq=sequence.incrementAndGet(),ms=System.currentTimeMillis(),ns=System.nanoTime();record(type,seq,ms,ns,data);
        if(deliver&&(!remote()||host.wantsRemoteCaptureEvents()))enqueue(new PracticeEvent(type,seq,ms,ns,data));
    }
    private void record(String type,long seq,long ms,long ns,Map<String,Object> data){
        CaptureLog writer=log;
        if(writer==null&&archive.match().isEmpty())return;
        Map<String,Object> row=observation(type,seq,ms,ns,data);
        if(writer!=null&&remote()&&archive.match().isEmpty()){
            // Observation maps are detached from Minecraft and are no longer mutated by their producers.
            writer.offerDeferred(retainedBytes(data),()->JsonData.encode(row));
        }else{
            String line=JsonData.encode(row);
            if(writer!=null)writer.offer(line);
            archive.event(line);
        }
    }
    private Map<String,Object> observation(String type,long seq,long ms,long ns,Map<String,Object> data){
        return map("type",type,"sequence",seq,"time_ms",ms,"monotonic_ns",ns,"client_tick",tick,"session",session,"match",match,"thread",Thread.currentThread().getName(),"data",data);
    }
    /** Account for snapshot objects and their only potentially large strings, the item tags. */
    private static long retainedBytes(Map<String,Object> data){
        Object view=data.get("view");
        Map<?,?> state=view instanceof Map?(Map<?,?>)view:data;
        long bytes=4096+entityBytes(state.get("self"))+entityBytes(state.get("opponent"));
        Object entities=state.get("entities");
        if(entities instanceof List)for(Object entity:(List<?>)entities)bytes+=entityBytes(entity);
        return bytes;
    }
    private static long entityBytes(Object value){
        if(!(value instanceof Map))return 0;
        Map<?,?> entity=(Map<?,?>)value;
        long bytes=4096+stringBytes(entity.get("held"));
        Object armor=entity.get("armor");
        if(armor instanceof List)for(Object item:(List<?>)armor)bytes+=stringBytes(item);
        return bytes;
    }
    private static long stringBytes(Object value){return value instanceof String?2L*((String)value).length():0;}
    private void enqueue(PracticeEvent e){if(!events.offer(e))eventDrops.incrementAndGet();}
    public void marker(String label){if(label==null)return;String s=label.substring(0,Math.min(120,label.length()));emit("marker",map("label",s),true);send("marker",s);}
    public boolean send(String type,String value){
        if(offlinePlayback)return false;
        if(!type.equals("marker")&&!type.equals("ping")&&!type.equals("replay_control"))return false;
        if(value==null||value.length()>120)return false;
        return sendRaw(map("type",type,"version",1,"session",session,"value",value,"client_ms",System.currentTimeMillis()));
    }
    private boolean sendRaw(Map<String,Object> data){
        Minecraft mc=Minecraft.func_71410_x();if(offlinePlayback||!ConnectionScope.canSend(active)||!coordination||mc.field_71439_g==null||!mc.func_152345_ab())return false;
        byte[] bytes=JsonData.encode(data).getBytes(StandardCharsets.UTF_8);if(bytes.length>30000)return false;
        mc.field_71439_g.field_71174_a.func_147297_a(new C17PacketCustomPayload(CHANNEL,new PacketBuffer(Unpooled.wrappedBuffer(bytes))));return true;
    }
    @SuppressWarnings("unchecked") private void receive(PacketBuffer buffer){
        if(offlinePlayback||!ConnectionScope.canSend(active)||!coordination||buffer.readableBytes()>30000)return;byte[] bytes=new byte[buffer.readableBytes()];buffer.getBytes(buffer.readerIndex(),bytes);
        String json=new String(bytes,StandardCharsets.UTF_8);if(!boundedJson(json))return;
        try{
            Map<String,Object> data=gson.fromJson(json,Map.class);
            if(data==null||!(data.get("version") instanceof Number)||((Number)data.get("version")).intValue()!=1)return;
            String type=String.valueOf(data.get("type"));
            if(type.equals("sprint_hit")){
                Minecraft mc=Minecraft.func_71410_x();
                if(!BotRuntime.enabled()&&mc.field_71439_g!=null&&mc.field_71439_g.func_70005_c_().equals(data.get("attacker"))
                        &&!match.isEmpty()&&match.equals(data.get("match"))&&data.get("sprint") instanceof Boolean){
                    sprintHitMatch=match;sprintHitBonus=(Boolean)data.get("sprint");
                    sprintHitUntil=System.nanoTime()+practice.replay.SprintHit.DISPLAY_MS*1_000_000L;
                    inputEvent("link_sprint_hit",data);
                }
                return;
            }
            if(!Arrays.asList("welcome","state","pong","marker_ack","replay_view","replay_menu","sumo_finish").contains(type))return;
            if(type.equals("welcome") && data.get("training_speed") instanceof Number){
                TrainingTimer.accept(active,data);archiveLocation();
            }
            if(type.equals("replay_view")){replay.receive(data);return;}
            if(type.equals("replay_menu")){Minecraft.func_71410_x().func_147108_a(new ReplayMenu(this));return;}
            if(SumoCoach.hooks!=null)SumoCoach.hooks.lab(data,type);
            if(type.equals("sumo_finish"))return;
            lastServer=System.currentTimeMillis();Object id=data.get("match");if(!BotRuntime.enabled()&&id instanceof String)match=(String)id;
            PracticeEvent event=new PracticeEvent("link_"+type,sequence.incrementAndGet(),lastServer,System.nanoTime(),data);
            if(type.equals("state")&&!BotRuntime.enabled()){
                lastState=event.data;Object players=data.get("players");Minecraft mc=Minecraft.func_71410_x();
                if(!match.isEmpty()&&players instanceof List&&mc.field_71439_g!=null){int side=((List<?>)players).indexOf(mc.field_71439_g.func_70005_c_());
                    if(side>=0&&side<2&&!match.equals(archive.match())){archive.begin(match,(side==0?"a":"b")+"-client",metadata(mc.field_71439_g.func_70005_c_()));}
                }else archive.end();
            }record(event.type,event.sequence,event.timeMillis,event.timeNanos,event.data);enqueue(event);
        }catch(RuntimeException ignored){emit("link_rejected",map("reason","invalid_data"),true);}
    }
    static boolean boundedJson(String s){
        boolean quoted=false,escape=false;int depth=0;
        for(char c:s.toCharArray()){if(escape){escape=false;continue;}if(quoted&&c=='\\'){escape=true;continue;}if(c=='"'){quoted=!quoted;continue;}
            if(!quoted){if(c=='{'||c=='['){if(++depth>24)return false;}if(c=='}'||c==']')if(--depth<0)return false;}}
        return !quoted&&depth==0;
    }
    public void sound(String id,float volume,float pitch){
        Minecraft mc=Minecraft.func_71410_x();if(mc.field_71439_g==null||id==null||!id.matches("[a-z0-9_.:]{1,80}")||!Float.isFinite(volume)||!Float.isFinite(pitch))return;
        mc.field_71439_g.func_85030_a(id,Math.min(1,Math.max(0,volume)),Math.min(2,Math.max(.5f,pitch)));
    }
    synchronized void close(){publisher.close();views.close();archive.close();finishLog();long deadline=System.currentTimeMillis()+10000;
        for(CaptureLog l:finishing)try{l.awaitClosed(Math.max(1,deadline-System.currentTimeMillis()));}catch(InterruptedException e){Thread.currentThread().interrupt();break;}remoteUpload.close();}
}
