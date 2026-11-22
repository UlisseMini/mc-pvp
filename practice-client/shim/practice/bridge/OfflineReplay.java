package practice.bridge;

import practice.core.replay.ReplayRecording;
import practice.core.replay.ReplayRecoil;
import practice.core.replay.MmcSumoReplay;
import practice.core.replay.ReplayMotion;
import practice.core.replay.ReplayVisuals;
import practice.core.CaptureLog;
import practice.api.*;
import practice.replay.*;
import com.google.gson.Gson;
import com.mojang.authlib.GameProfile;
import io.netty.buffer.Unpooled;
import io.netty.util.concurrent.*;
import java.nio.file.*;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.*;
import net.minecraft.client.entity.EntityOtherPlayerMP;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.multiplayer.GuiConnecting;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.ServerList;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.entity.Entity;
import net.minecraft.network.*;
import net.minecraft.network.play.server.S01PacketJoinGame;
import net.minecraft.network.play.server.S38PacketPlayerListItem;
import net.minecraft.stats.StatFileWriter;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.World;
import org.lwjgl.input.Keyboard;
import org.lwjgl.opengl.Display;
import static practice.core.JsonData.map;
import static practice.replay.PoseTrack.*;
import static practice.bridge.ReplayDisplay.Layer.*;

/** Offline PLAY reconstruction. A sink NetworkManager has no Channel and cannot
 * connect; both send overloads discard replies, movement, chat and mod payloads. */
final class OfflineReplay {
    private static final int OBSERVED_TARGET_ID = -2147483001;
    static final double[] PLAYBACK_SPEEDS={.1,.25,.5,1,2,4};
    final class RecordedPlayer extends EntityOtherPlayerMP {
        final boolean self;
        RecordedPlayer(WorldClient world,GameProfile profile,boolean self){super(world,profile);this.self=self;}
        @Override public ResourceLocation func_110306_p(){return skins.texture(func_110124_au());}
        @Override public String func_175154_l(){return skins.model(func_110124_au());}
    }
    final class ReplayViewer extends EntityPlayerSP {
        ReplayViewer(Minecraft mc,World world,NetHandlerPlayClient handler,StatFileWriter stats){super(mc,world,handler,stats);}
        @Override public ResourceLocation func_110306_p(){return ownActor==null?super.func_110306_p():ownActor.func_110306_p();}
        @Override public String func_175154_l(){return ownActor==null?super.func_175154_l():ownActor.func_175154_l();}
    }
    final class ReplayWorld extends WorldClient {
        ReplayWorld(OfflineHandler handler,S01PacketJoinGame join){
            super(handler,new net.minecraft.world.WorldSettings(0L,join.func_149198_e(),false,join.func_149195_d(),join.func_149196_i()),
                    join.func_149194_f(),join.func_149192_g(),handler.mc.field_71424_I);
        }
        boolean frozen(){return paused&&scrubDirection==0;}
        @Override public void func_72835_b(){if(!frozen())super.func_72835_b();}
        @Override public void func_72939_s(){if(!frozen())super.func_72939_s();}
    }
    static final class SinkNetwork extends NetworkManager {
        long discarded;
        SinkNetwork(){super(EnumPacketDirection.CLIENTBOUND);}
        @Override public void func_179290_a(Packet packet){discarded++;}
        @Override public void func_179288_a(Packet packet,GenericFutureListener<? extends Future<? super Void>> first,GenericFutureListener<? extends Future<? super Void>>... rest){discarded++;}
        @Override public boolean func_150731_c(){return true;} // permits vanilla's local world loop, never a socket
        @Override public boolean func_179292_f(){return true;}
        @Override public void func_150718_a(net.minecraft.util.IChatComponent why){}
        @Override public void func_150721_g(){}
    }
    /** Vanilla world bootstrap without Forge's live NetworkDispatcher handshake.
     * All subsequent safe PLAY packets still use the normal native handlers. */
    final class OfflineHandler extends NetHandlerPlayClient {
        final Minecraft mc;
        private final Map<NetworkPlayerInfo,NetworkPlayerInfo> localInfo=new WeakHashMap<>();
        OfflineHandler(Minecraft mc,NetworkManager network,GameProfile profile){super(mc,null,network,profile);this.mc=mc;}
        @Override public void func_147282_a(S01PacketJoinGame p){
            mc.field_71442_b=new net.minecraft.client.multiplayer.PlayerControllerMP(mc,this){
                @Override public boolean func_78747_a(){return spectatorCamera()||super.func_78747_a();}
                @Override public EntityPlayerSP func_178892_a(World world,StatFileWriter stats){return new ReplayViewer(mc,world,OfflineHandler.this,stats);}
            };
            WorldClient world=new ReplayWorld(this,p);
            try{for(java.lang.reflect.Field f:NetHandlerPlayClient.class.getDeclaredFields())if(f.getType()==net.minecraft.client.multiplayer.WorldClient.class){f.setAccessible(true);f.set(this,world);}}catch(ReflectiveOperationException e){throw new IllegalStateException("Pinned play-handler world field unavailable",e);}
            mc.field_71439_g=null;
            ObserverHooks.replayWorldLoad.set(true);
            try{mc.func_71403_a(world);}finally{ObserverHooks.replayWorldLoad.remove();}
            mc.field_71439_g.field_71093_bK=p.func_149194_f();mc.field_71439_g.func_145769_d(p.func_149197_c());mc.field_71442_b.func_78746_a(p.func_149198_e());field_147304_c=p.func_149193_h();
        }
        @Override public void func_147280_a(net.minecraft.network.play.server.S07PacketRespawn p){func_147282_a(new S01PacketJoinGame(mc.field_71439_g==null?0:mc.field_71439_g.func_145782_y(),p.func_149083_e(),false,p.func_149082_c(),p.func_149081_d(),field_147304_c,p.func_149080_f(),false));}
        @Override public void func_147237_a(net.minecraft.network.play.server.S0CPacketSpawnPlayer p){
            // Partial recordings can begin after the matching player-list entry.
            if(func_175102_a(p.func_179819_c())!=null)super.func_147237_a(p);
        }
        @Override public void func_147256_a(S38PacketPlayerListItem packet){
            if(packet.func_179768_b()==S38PacketPlayerListItem.Action.ADD_PLAYER)
                for(S38PacketPlayerListItem.AddPlayerData player:packet.func_179767_a())skins.remember(player.func_179962_a());
            super.func_147256_a(packet);
        }
        // Keep network-backed lookup out of the offline play handler.
        private NetworkPlayerInfo local(NetworkPlayerInfo original){
            if(original==null)return null;
            GameProfile profile=original.func_178845_a();
            return localInfo.computeIfAbsent(original,ignored->new NetworkPlayerInfo(new GameProfile(profile.getId(),profile.getName())){
                @Override public ResourceLocation func_178837_g(){return skins.texture(profile.getId());}
                @Override public ResourceLocation func_178861_h(){return null;}
                @Override public String func_178851_f(){return skins.model(profile.getId());}
                @Override protected void func_178841_j(){}
            });
        }
        @Override public net.minecraft.client.network.NetworkPlayerInfo func_175102_a(UUID id){return local(super.func_175102_a(id));}
        @Override public net.minecraft.client.network.NetworkPlayerInfo func_175104_a(String name){return local(super.func_175104_a(name));}
        @Override public Collection<net.minecraft.client.network.NetworkPlayerInfo> func_175106_d(){List<net.minecraft.client.network.NetworkPlayerInfo> out=new ArrayList<>();for(net.minecraft.client.network.NetworkPlayerInfo p:super.func_175106_d())out.add(local(p));return out;}
    }
    final PracticeBridge bridge;final PracticeCapture capture;final Path directory;
    private final ReplayDisplay display;
    private boolean reachEnabled;
    final Gson gson=new Gson();
    private final ReplaySkins skins;
    ReplayRecording recording;ReplayRecording.Entry entry;
    ReplayRecording.Entry queuedMatch;
    private Long queuedSeekWall;
    private CaptureLog queuedWriter;
    SinkNetwork network;NetHandlerPlayClient handler;EntityOtherPlayerMP ownActor;
    private EntityOtherPlayerMP observedTarget;
    private int observedTargetId, savedPerspective;
    Map<String,PoseTrack> linked=Collections.emptyMap();String ownSource,opponentName;
    boolean active,paused=true,loading,seeking;double time,speed=1;long lastNs;int cursor,applied,skipped,errors;
    private boolean hideText;
    Boolean sprintHit(){return recording!=null&&!hideText?recording.sprintHitAt(playhead()):null;}
    Map<String,Object> coachFeedback(){
        return recording!=null&&!hideText&&shows(RECOIL_FLASH)?recording.coachAt(playhead()):Collections.emptyMap();
    }
    private int scrubDirection;
    private boolean worldAheadOfPlayhead;
    private long loadToken;
    private List<ReplayRecording.Entry> sumoMatches=Collections.emptyList();
    private final Map<String,java.util.concurrent.Future<ReplayRecording>> prepared=new HashMap<>();
    private final java.util.concurrent.ExecutorService preloader=java.util.concurrent.Executors.newFixedThreadPool(2,task->{
        Thread thread=new Thread(task,"practice-replay-prefetch");thread.setDaemon(true);return thread;
    });
    double[] freePosition;
    private double[] previousFreePosition;
    private Map<String,Object> visualSelf=Collections.emptyMap(),visualTarget=Collections.emptyMap();
    private float spectatorYaw,spectatorPitch;
    String camera="recorded",message="",failure="";List<ReplayRecording.Entry> entries=Collections.emptyList();
    OfflineReplay(PracticeBridge bridge,PracticeCapture capture,Path directory){
        this.bridge=bridge;this.capture=capture;this.directory=directory;
        display=new ReplayDisplay(directory);skins=new ReplaySkins(directory,gson);
    }
    private Map<String,Object> parse(String s){return gson.fromJson(s,Map.class);}
    void browser(){if(!active)refresh();Minecraft.func_71410_x().func_147108_a(new OfflineReplayMenu(this));}
    boolean watchSumo(String id)throws java.io.IOException{
        MmcSumoReplay.Match match=MmcSumoReplay.read(directory.resolve("sumo-replays"),id);
        ReplayRecording.Entry selected=ReplayRecording.sumoEntry(directory.resolve("captures"),match);
        if(selected==null)return false;
        select(selected);
        return true;
    }
    boolean watchAt(String id,long wallMs)throws java.io.IOException{
        if(entry!=null&&(entry.id.equals(id)||entry.id.equals("sumo:"+id))&&active&&!loading){
            paused=true;scrubDirection=0;
            seek(Math.max(visibleStart(recording,entry),wallMs-recording.startMs));
            return true;
        }
        ReplayRecording.Entry selected;
        if(id.matches("[a-f0-9]{32}")){
            MmcSumoReplay.Match match=MmcSumoReplay.read(directory.resolve("sumo-replays"),id);
            selected=ReplayRecording.sumoEntry(directory.resolve("captures"),match);
        }else if(id.matches("m[0-9]{4,9}")){
            Path archive=archiveRoot();
            selected=archive==null?null:ReplayRecording.archiveEntry(archive.resolve(id),this::parse);
        }else return false;
        if(selected==null)return false;
        select(selected,wallMs);
        return true;
    }
    void select(ReplayRecording.Entry selected){select(selected,null);}
    private void select(ReplayRecording.Entry selected,Long seekWall){
        Minecraft mc=Minecraft.func_71410_x();
        ServerData server=mc.func_147104_D();
        if(live()&&mc.field_71441_e!=null&&ConnectionScope.current()!=null&&server!=null&&MmcSumoReplay.server(server.field_78845_b)){
            queuedMatch=selected;
            queuedSeekWall=seekWall;
            queuedWriter=capture.remoteLog();
            message="Disconnecting from MMC…";
            mc.field_71441_e.func_72882_A();
            mc.func_71403_a(null);
            mc.func_147108_a(new OfflineReplayMenu(this));
            return;
        }
        if(selected.isSumo()||seekWall!=null){
            if(live()){queuedMatch=selected;queuedSeekWall=seekWall;message="";}
            mc.func_147108_a(new OfflineReplayMenu(this));
            if(queuedMatch==selected)return;
        }
        load(selected,seekWall);
    }
    void openQueuedMatch(){
        if(queuedMatch==null||loading||live())return;
        if(queuedWriter!=null){
            try{if(!queuedWriter.awaitClosed(1))return;}
            catch(InterruptedException interrupted){Thread.currentThread().interrupt();return;}
            queuedWriter=null;
        }
        Minecraft mc=Minecraft.func_71410_x();
        GuiScreen screen=mc.field_71462_r;
        if(!(screen instanceof GuiMainMenu||screen instanceof GuiMultiplayer||screen instanceof OfflineReplayMenu))return;
        ReplayRecording.Entry selected=queuedMatch;
        Long seekWall=queuedSeekWall;
        queuedMatch=null;
        queuedSeekWall=null;
        mc.func_147108_a(new OfflineReplayMenu(this));
        load(selected,seekWall);
    }
    private Path archiveRoot()throws java.io.IOException{
        Properties p=new Properties();
        try(java.io.InputStream in=Files.newInputStream(directory.resolve("settings.properties"))){p.load(in);}
        String archive=p.getProperty("archive.directory","");
        return archive.isEmpty()?null:Paths.get(archive);
    }
    void refresh(){try{entries=ReplayRecording.catalog(directory.resolve("captures"),archiveRoot(),this::parse);}catch(Exception e){message="Could not list captures: "+e.getMessage();}}
    boolean live(){Minecraft mc=Minecraft.func_71410_x();return ConnectionScope.current()!=null||(!active&&mc.field_71441_e!=null);}
    private void prepareNeighbors(ReplayRecording previous){
        int index=sumoIndex();
        Set<String> keep=new HashSet<>();
        for(int neighbor:new int[]{index-1,index+1}){
            if(index<0||neighbor<0||neighbor>=sumoMatches.size())continue;
            ReplayRecording.Entry next=sumoMatches.get(neighbor);
            if(next.durationMs>60000)continue;
            keep.add(next.id);
            if(prepared.containsKey(next.id))continue;
            if(previous!=null&&previous.entry.id.equals(next.id)&&previous.poses.size()<=2400
                    &&previous.packets.stream().mapToLong(p->p.bytes.length).sum()<=24000000){
                prepared.put(next.id,java.util.concurrent.CompletableFuture.completedFuture(previous));
            }else{
                ReplayRecording.Limits limits=new ReplayRecording.Limits(24L*1024*1024,256L*1024*1024,
                        2*1024*1024,320000,64,2400,8L*1024*1024);
                prepared.put(next.id,preloader.submit(()->ReplayRecording.load(next,this::parse,limits)));
            }
        }
        Iterator<Map.Entry<String,java.util.concurrent.Future<ReplayRecording>>> iter=prepared.entrySet().iterator();
        while(iter.hasNext()){
            Map.Entry<String,java.util.concurrent.Future<ReplayRecording>> cached=iter.next();
            if(!keep.contains(cached.getKey())){cached.getValue().cancel(true);iter.remove();}
        }
    }
    private int sumoIndex(){
        if(entry==null||!entry.isSumo())return -1;
        for(int index=0;index<sumoMatches.size();index++)if(sumoMatches.get(index).id.equals(entry.id))return index;
        return -1;
    }
    void stepMatch(int offset){
        int index=sumoIndex(),next=index+offset;
        if(index>=0&&next>=0&&next<sumoMatches.size())load(sumoMatches.get(next));
    }
    void load(ReplayRecording.Entry selected){load(selected,null);}
    private void load(ReplayRecording.Entry selected,Long seekWall){
        if(live()){message="Disconnect to the title screen first. Playback never disconnects a live session.";return;}
        if(loading)return;loading=true;message="Loading local capture…";
        long token=++loadToken;
        java.util.concurrent.Future<ReplayRecording> cached=prepared.remove(selected.id);
        Thread worker=new Thread(()->{try{
            ReplayRecording read=null;
            if(cached!=null)try{read=cached.get();}catch(java.util.concurrent.ExecutionException|java.util.concurrent.CancellationException ignored){}
            final ReplayRecording value=read==null?readRecording(selected,seekWall):read;
            Minecraft.func_71410_x().func_152344_a(()->{if(token!=loadToken)return;loading=false;if(live()){message="Live connection appeared; playback not started.";return;}try{start(value.entry,value,seekWall);}catch(Exception e){failure="Replay could not start: "+e.getMessage();stop();message=failure;}});
        }catch(OutOfMemoryError memory){loadFailed(token,"Capture exceeds available replay memory. Use a shorter capture or a larger client heap.");}
        catch(Exception e){loadFailed(token,"Capture unavailable: "+e.getMessage());}},"practice-offline-reader");
        worker.setDaemon(true);worker.start();
    }
    private ReplayRecording readRecording(ReplayRecording.Entry selected,Long seekWall)throws java.io.IOException{
        try{return ReplayRecording.load(selected,this::parse);}
        catch(java.io.IOException unavailable){
            if(seekWall==null||selected.archiveFolder==null)throw unavailable;
            ReplayRecording.Entry clip=ReplayRecording.archiveEntry(selected.archiveFolder,this::parse,false);
            if(clip==null||clip.firstFile.equals(selected.firstFile))throw unavailable;
            System.out.println("[PracticeReplay] Using archived match clip: "+unavailable.getMessage());
            return ReplayRecording.load(clip,this::parse);
        }
    }
    private void loadFailed(long token,String text){
        Minecraft.func_71410_x().func_152344_a(()->{if(token==loadToken){loading=false;message=text;}});
    }
    void start(ReplayRecording.Entry selected,ReplayRecording value)throws Exception{start(selected,value,null);}
    private static double visibleStart(ReplayRecording value,ReplayRecording.Entry selected){
        return value.sumo!=null?value.playStart():Math.max(0,selected.preferredStartMs-value.startMs);
    }
    private void start(ReplayRecording.Entry selected,ReplayRecording value,Long seekWall)throws Exception{
        if(live())throw new IllegalStateException("Disconnect first (socket="+(ConnectionScope.current()!=null)+", integrated="+(Minecraft.func_71410_x().func_71401_C()!=null)+", world="+(Minecraft.func_71410_x().field_71441_e!=null)+", active="+active+")");
        Minecraft mc=Minecraft.func_71410_x();
        boolean replacing=active;
        boolean closed=mc.field_71462_r==null;
        GuiScreen controls=mc.field_71462_r;
        ReplayRecording previous=recording;
        entry=selected;recording=value;for(String warning:value.warnings)System.out.println("[PracticeReplay] "+warning);
        if(!replacing){
            savedPerspective=mc.field_71474_y.field_74320_O;
            skins.begin(mc);
            active=true;capture.offlinePlayback(true);bridge.offlineStarted();paused=true;speed=1;camera="recorded";
        }
        time=0;scrubDirection=0;worldAheadOfPlayhead=false;
        freePosition=previousFreePosition=null;items.clear();failure="";message="";
        if(selected.isSumo()&&sumoIndex()<0)try{sumoMatches=ReplayRecording.sumoEntries(directory.resolve("captures"));}
        catch(java.io.IOException ignored){}
        prepareNeighbors(previous);
        linked=Collections.emptyMap();ownSource=null;opponentName=null;
        if(selected.archiveFolder!=null)try{Path manifest=selected.archiveFolder.resolve("manifest.json");if(Files.isRegularFile(manifest)&&Files.size(manifest)<=1048576){Map<String,Object> players=object(parse(new String(Files.readAllBytes(manifest),"UTF-8")).get("players"));ReplayRecording.Pose first=value.poseAt(Math.max(0,selected.preferredStartMs-value.startMs));if(first==null&&!value.poses.isEmpty())first=value.poses.get(0);String name=first==null?"":String.valueOf(first.self.get("name"));for(String side:Arrays.asList("a","b"))if(name.equals(object(players.get(side)).get("name"))){ownSource=side;Object otherName=object(players.get(side.equals("a")?"b":"a")).get("name");if(otherName instanceof String)opponentName=(String)otherName;}}
            linked=OfflinePoseLinks.load(selected.archiveFolder,value.startMs,ownSource,this::parse);}catch(Exception ignored){}
        double start=visibleStart(value,selected);
        if(seekWall!=null){
            paused=true;
            start=Math.min(value.playEnd(),Math.max(start,seekWall-value.startMs));
        }
        seeking=true;
        try{
            reset();advance(start);seedArena(start);time=start;pose();
        }finally{seeking=false;}
        if(replacing&&closed)mc.func_147108_a(null);
        else mc.func_147108_a(controls instanceof OfflineReplayMenu?controls:new OfflineReplayMenu(this));
        lastNs=System.nanoTime();
    }
    void reset()throws Exception{
        Minecraft mc=Minecraft.func_71410_x();ownActor=null;observedTarget=null;mc.func_175607_a(null);
        skins.resetWorld();
        mc.field_71456_v.func_146158_b().func_146231_a();
        network=new SinkNetwork();handler=new OfflineHandler(mc,network,new GameProfile(UUID.nameUUIDFromBytes("practice-offline-viewer".getBytes("UTF-8")),"OfflineViewer"));network.func_150719_a(handler);
        cursor=applied=skipped=errors=0;
        // Missing JOIN gets an explicitly empty reconstruction, never a fabricated saved world.
        if(!recording.hasJoin){S01PacketJoinGame join=new S01PacketJoinGame();PacketBuffer b=new PacketBuffer(Unpooled.buffer());try{b.writeInt(recording.selfId);b.writeByte(0);b.writeByte(0);b.writeByte(1);b.writeByte(20);b.func_180714_a("default");b.writeBoolean(false);join.func_148837_a(b);}finally{b.release();}handler.func_147282_a(join);}
    }
    void returnToMmc(){
        if(!active||live())return;
        Minecraft mc=Minecraft.func_71410_x();
        ServerData server=mmcServer(mc);
        stop();
        mc.func_147108_a(new GuiConnecting(new GuiMultiplayer(new GuiMainMenu()),mc,server));
    }
    private static ServerData mmcServer(Minecraft mc){
        ServerData recent=mc.func_147104_D();
        if(recent!=null&&MmcSumoReplay.server(recent.field_78845_b))return recent;
        ServerList servers=new ServerList(mc);
        for(int i=0;i<servers.func_78856_c();i++){
            ServerData saved=servers.func_78850_a(i);
            if(MmcSumoReplay.server(saved.field_78845_b))return saved;
        }
        return new ServerData("Minemen Club","mineman.club",false);
    }
    void stop(){
        loadToken++;loading=false;
        if(!active)return;active=false;capture.offlinePlayback(false);bridge.offlineStopped();Minecraft mc=Minecraft.func_71410_x();
        mc.field_71474_y.field_74320_O=savedPerspective;
        if(ConnectionScope.current()==null){mc.func_175607_a(null);mc.func_71403_a(null);mc.func_147108_a(new GuiMainMenu());}
        skins.close(mc);
        ownActor=null;observedTarget=null;handler=null;network=null;linked=Collections.emptyMap();recording=null;entry=null;items.clear();
        for(java.util.concurrent.Future<ReplayRecording> pending:prepared.values())pending.cancel(true);
        prepared.clear();
        sumoMatches=Collections.emptyList();
    }
    static void blockBinding(net.minecraft.client.settings.KeyBinding key){net.minecraft.client.settings.KeyBinding.func_74510_a(key.func_151463_i(),false);while(key.func_151468_f()){} }
    void clearGameplayInputs(){Minecraft mc=Minecraft.func_71410_x();for(net.minecraft.client.settings.KeyBinding key:new net.minecraft.client.settings.KeyBinding[]{mc.field_71474_y.field_74312_F,mc.field_71474_y.field_74313_G,mc.field_71474_y.field_74322_I,mc.field_71474_y.field_74316_C,mc.field_71474_y.field_151445_Q})blockBinding(key);for(net.minecraft.client.settings.KeyBinding key:mc.field_71474_y.field_151456_ac)blockBinding(key);}
    void tick(){
        if(!active)return;if(Minecraft.func_71410_x().field_71441_e==null&&ownActor!=null){stop();return;}clearGameplayInputs();if(ConnectionScope.current()!=null){stop();message="Replay closed because a live connection opened.";return;}
        Minecraft mc=Minecraft.func_71410_x();
        long now=System.nanoTime();double delta=Math.min(250,Math.max(0,(now-lastNs)/1e6));lastNs=now;
        updatePlayback(delta,scrubKeys(mc.field_71462_r));
        if(spectatorCamera()&&freePosition!=null)previousFreePosition=freePosition.clone();
        if(spectatorCamera()&&freePosition!=null&&mc.field_71439_g!=null&&mc.field_71462_r==null){
            double forward=(mc.field_71474_y.field_74351_w.func_151470_d()?1:0)-(mc.field_71474_y.field_74368_y.func_151470_d()?1:0);
            double strafe=(mc.field_71474_y.field_74370_x.func_151470_d()?1:0)-(mc.field_71474_y.field_74366_z.func_151470_d()?1:0);
            double yaw=Math.toRadians(mc.field_71439_g.field_70177_z),scale=.3/Math.max(1,Math.hypot(forward,strafe));
            freePosition[0]+=(-Math.sin(yaw)*forward+Math.cos(yaw)*strafe)*scale;
            freePosition[2]+=(Math.cos(yaw)*forward+Math.sin(yaw)*strafe)*scale;
            freePosition[1]+=(Keyboard.isKeyDown(Keyboard.KEY_E)?.3:0)-(Keyboard.isKeyDown(Keyboard.KEY_Q)?.3:0);
        }
        pose();
    }
    private int scrubKeys(GuiScreen screen){
        if((screen!=null&&!(screen instanceof OfflineReplayMenu))||!Display.isActive()
                ||Keyboard.isKeyDown(Keyboard.KEY_LSHIFT)||Keyboard.isKeyDown(Keyboard.KEY_RSHIFT))return 0;
        return (Keyboard.isKeyDown(Keyboard.KEY_RIGHT)?1:0)-(Keyboard.isKeyDown(Keyboard.KEY_LEFT)?1:0);
    }
    private void updatePlayback(double elapsed,int nextDirection){
        double target=projectedTime(elapsed);
        if(scrubDirection<0&&target<time){
            // Keep the arena loaded during reverse preview. Replay poses and
            // readouts follow the cursor, then native packets catch up on release.
            time=target;
            worldAheadOfPlayhead=true;
        }else if(target>time){
            seekForward(target);
        }
        if(worldAheadOfPlayhead&&nextDirection>=0)seek(time);
        scrubDirection=nextDirection;
    }
    private double projectedTime(double elapsed){
        int direction=scrubDirection!=0?scrubDirection:paused?0:1;
        return Math.max(recording.playStart(),Math.min(recording.playEnd(),time+direction*elapsed*speed));
    }
    double playhead(){
        return projectedTime(Math.max(0,Math.min(100,(System.nanoTime()-lastNs)/1e6)));
    }
    void seekForward(double target){
        if(!seeking)saveSpectatorLook();
        try{advance(target);time=target;if(time>=recording.playEnd())paused=true;}
        catch(Exception e){paused=true;e.printStackTrace();failure="Playback stopped at "+String.format(Locale.ROOT,"%.3fs: ",time/1000)+e.getClass().getSimpleName()+": "+e.getMessage();}
        finally{restoreSpectatorLook();}
    }
    void seek(double target){
        if(!active)return;
        GuiScreen controls=Minecraft.func_71410_x().field_71462_r;
        saveSpectatorLook();seeking=true;
        target=Math.max(recording.playStart(),Math.min(recording.playEnd(),target));
        try{
            boolean rewind=target<time||worldAheadOfPlayhead;
            if(rewind){reset();time=0;worldAheadOfPlayhead=false;seedEarlierChunks();}
            seekForward(target);
            if(rewind)seedRemainingChunks(target);
            pose();
        }catch(Exception e){paused=true;failure="Seek failed: "+e.getMessage();}
        finally{seeking=false;if(controls instanceof OfflineReplayMenu)Minecraft.func_71410_x().func_147108_a(controls);}
        lastNs=System.nanoTime();
    }
    void togglePause(){
        if(paused&&time>=recording.playEnd())seek(recording.playStart());
        paused=!paused;
        lastNs=System.nanoTime();
    }
    boolean openCommandChat(int key){
        Minecraft mc=Minecraft.func_71410_x();
        net.minecraft.client.settings.GameSettings settings=mc.field_71474_y;
        if(key!=Keyboard.KEY_SLASH&&key!=settings.field_74323_J.func_151463_i()&&key!=settings.field_74310_D.func_151463_i())return false;
        blockBinding(settings.field_74323_J);
        blockBinding(settings.field_74310_D);
        mc.func_147108_a(new ReplayCommandChat(mc.field_71462_r));
        return true;
    }
    boolean key(int key,boolean shift){
        switch(key){
            case Keyboard.KEY_SPACE:togglePause();return true;
            case Keyboard.KEY_LEFT:if(shift)backTick();return true;
            case Keyboard.KEY_RIGHT:if(shift)step();return true;
            case Keyboard.KEY_COMMA:backTick();return true;
            case Keyboard.KEY_PERIOD:step();return true;
            case Keyboard.KEY_LBRACKET:stepSpeed(false);return true;
            case Keyboard.KEY_RBRACKET:stepSpeed(true);return true;
            case Keyboard.KEY_UP:stepMatch(-1);return true;
            case Keyboard.KEY_DOWN:stepMatch(1);return true;
            case Keyboard.KEY_O:hideText=!hideText;return true;
            default:return false;
        }
    }
    void step(){paused=true;scrubDirection=0;seek(recording.tickStep(time));}
    void backTick(){paused=true;scrubDirection=0;seek(recording.previousTick(time));}
    void stepMotion(boolean forward){
        double next=recording.motion.step(time,forward);
        if(Double.isFinite(next)){paused=true;seek(next);}
    }
    void speed(double next){speed=Math.max(PLAYBACK_SPEEDS[0],Math.min(PLAYBACK_SPEEDS[PLAYBACK_SPEEDS.length-1],next));lastNs=System.nanoTime();}
    private void stepSpeed(boolean faster){
        int index=faster?0:PLAYBACK_SPEEDS.length-1,step=faster?1:-1;
        for(;index>=0&&index<PLAYBACK_SPEEDS.length;index+=step){
            double preset=PLAYBACK_SPEEDS[index];
            if(faster?preset>speed:preset<speed){speed(preset);return;}
        }
    }
    @SuppressWarnings("unchecked") void advance(double target)throws Exception{
        List<ReplayRecording.Wire> packets=recording.packets;
        while(cursor<packets.size()&&packets.get(cursor).atMs<=target){ReplayRecording.Wire wire=packets.get(cursor++);if(!wire.inbound)continue;
            // Resource-pack URLs, custom mod channels, disconnect screens and compression negotiation
            // never execute in an offline world. No authenticated connection exists.
            if((seeking&&(wire.protocolId==0x29||wire.protocolId==0x28||wire.protocolId==0x2a))||wire.protocolId==0x2d||wire.protocolId==0x36||wire.protocolId==0x48||wire.protocolId==0x3f||wire.protocolId==0x40||wire.protocolId==0x46){skipped++;continue;}
            // Scoreboard changes in a clipped recording may refer to teams or
            // objectives created before the recording. They do not affect poses.
            if(!recording.hasJoin&&wire.protocolId>=0x3b&&wire.protocolId<=0x3e){skipped++;continue;}
            if(recording.sumo!=null&&wire.atMs<recording.sumo.countdownMs&&(wire.protocolId==2||wire.protocolId==0x45)){skipped++;continue;}
            applyWire(wire);
        }
    }
    private void seedEarlierChunks()throws Exception{
        if(recording.sumo==null)return;
        for(ReplayRecording.Wire wire:recording.sumo.earlierChunks)applyWire(wire);
    }
    private void seedRemainingChunks(double target)throws Exception{
        if(recording.sumo==null)return;
        for(ReplayRecording.Wire wire:recording.sumo.initialChunks)if(wire.atMs>target)applyWire(wire);
    }
    private void seedArena(double target)throws Exception{
        seedEarlierChunks();
        seedRemainingChunks(target);
    }
    @SuppressWarnings("unchecked") private void applyWire(ReplayRecording.Wire wire)throws Exception{
        PacketBuffer bytes=new PacketBuffer(Unpooled.wrappedBuffer(wire.bytes));
        try{
            int id=bytes.func_150792_a();
            Packet packet=EnumConnectionState.PLAY.func_179244_a(EnumPacketDirection.CLIENTBOUND,id);
            if(packet==null)throw new java.io.IOException("Unknown PLAY packet "+id);
            packet.func_148837_a(bytes);packet.func_148833_a(handler);applied++;
        }catch(Exception e){errors++;throw new java.io.IOException("packet "+wire.protocolId+" at "+wire.atMs+"ms",e);}
        finally{bytes.release();}
    }
    static void apply(Entity e,Map<String,Object> p){
        if(e==null||p.isEmpty())return;List<?> pos=p.get("render")instanceof List?(List<?>)p.get("render"):p.get("pos")instanceof List?(List<?>)p.get("pos"):null;
        double x=pos!=null?((Number)pos.get(0)).doubleValue():number(p,"posX",0),y=pos!=null?((Number)pos.get(1)).doubleValue():number(p,"posY",4),z=pos!=null?((Number)pos.get(2)).doubleValue():number(p,"posZ",0);
        e.func_70107_b(x,y,z);e.field_70169_q=e.field_70142_S=x;e.field_70167_r=e.field_70137_T=y;e.field_70166_s=e.field_70136_U=z;
        e.field_70177_z=e.field_70126_B=(float)number(p,"render_yaw",number(p,"yaw",number(p,"rotationYaw",0)));e.field_70125_A=e.field_70127_C=(float)number(p,"render_pitch",number(p,"pitch",number(p,"rotationPitch",0)));
        e.field_70159_w=e.field_70181_x=e.field_70179_y=0;e.field_70122_E=Boolean.TRUE.equals(p.get("ground"));
        if(p.containsKey("sneaking"))e.func_70095_a(Boolean.TRUE.equals(p.get("sneaking")));
        if(e instanceof net.minecraft.entity.EntityLivingBase){net.minecraft.entity.EntityLivingBase l=(net.minecraft.entity.EntityLivingBase)e;l.field_70737_aN=(int)number(p,"hurtTime",0);l.field_70759_as=l.field_70758_at=(float)number(p,"rotationYawHead",e.field_70177_z);l.field_70761_aq=l.field_70760_ar=(float)number(p,"renderYawOffset",e.field_70177_z);l.field_70733_aJ=(float)number(p,"swingProgress",0);l.field_70732_aI=(float)number(p,"prevSwingProgress",l.field_70733_aJ);l.field_70754_ba=(float)number(p,"limbSwing",0);l.field_70721_aZ=l.field_70722_aY=(float)number(p,"limbSwingAmount",0);}
    }
    private final Map<String,net.minecraft.item.ItemStack> items=new LinkedHashMap<>();
    private net.minecraft.item.ItemStack item(Object raw){if(!(raw instanceof String))return null;String tag=(String)raw;if(items.containsKey(tag)){net.minecraft.item.ItemStack cached=items.get(tag);return cached==null?null:cached.func_77946_l();}try{net.minecraft.item.ItemStack value=net.minecraft.item.ItemStack.func_77949_a(net.minecraft.nbt.JsonToNBT.func_180713_a(tag));if(items.size()>=128)items.clear();items.put(tag,value);return value==null?null:value.func_77946_l();}catch(Exception bad){return null;}}
    void equipment(EntityOtherPlayerMP actor,Map<String,Object> pose,Minecraft mc){
        actor.field_71071_by.field_70461_c=(int)Math.max(0,Math.min(8,number(pose,"slot",0)));
        net.minecraft.item.ItemStack held=pose.containsKey("held")?item(pose.get("held")):actor==ownActor?mc.field_71439_g.func_70694_bm():null;actor.func_70062_b(0,held);
        Object armor=pose.get("armor");for(int i=0;i<4;i++)actor.func_70062_b(i+1,armor instanceof List&&((List<?>)armor).size()>i?item(((List<?>)armor).get(i)):actor==ownActor?mc.field_71439_g.func_71124_b(i+1):null);
        if(Boolean.TRUE.equals(pose.get("using_item"))&&held!=null)actor.func_71008_a(held,(int)Math.max(1,number(pose,"use_ticks",1)));else actor.func_71034_by();
    }
    double poseAge(){ReplayRecording.Pose p=recording.poseAt(time);return p==null?Double.POSITIVE_INFINITY:Math.max(0,time-p.atMs);}
    Map<String,Object> self(){ReplayRecording.Pose p=recording.poseAt(time);return p==null?Collections.emptyMap():p.self;}
    Map<String,Object> opponent(){return opponent(recording.poseAt(time));}
    private Map<String,Object> opponent(ReplayRecording.Pose pose){
        if(pose==null)return Collections.emptyMap();
        if(opponentName!=null&&pose.state.get("entities")instanceof List)for(Object o:(List<?>)pose.state.get("entities")){
            Map<String,Object> entity=object(o);if(opponentName.equals(entity.get("name")))return entity;
        }
        return pose.opponent;
    }
    private Entity target(Minecraft mc,Map<String,Object> pose){
        int id=(int)number(pose,"id",Integer.MIN_VALUE);
        Entity target=mc.field_71441_e.func_73045_a(id);
        if(observedTarget!=null&&(observedTarget.field_70170_p!=mc.field_71441_e||observedTargetId!=id||target!=null)){
            if(observedTarget.field_70170_p==mc.field_71441_e)mc.field_71441_e.func_73028_b(OBSERVED_TARGET_ID);
            observedTarget=null;
        }
        if(target!=null||id==Integer.MIN_VALUE||(recording.hasJoin&&!worldAheadOfPlayhead)||!(pose.get("name") instanceof String))return target;
        if(observedTarget==null){
            String name=(String)pose.get("name");
            UUID uuid=recordedId(pose,"recorded-target-"+id);
            observedTarget=new RecordedPlayer(mc.field_71441_e,new GameProfile(uuid,name),false);
            observedTargetId=id;
            mc.field_71441_e.func_73027_a(OBSERVED_TARGET_ID,observedTarget);
        }
        equipment(observedTarget,pose,mc);
        return observedTarget;
    }
    void renderPose(float partial){
        pose(playhead(),partial);
    }
    void pose(){pose(time,1);}
    private static UUID recordedId(Map<String,Object> pose,String fallback){
        UUID id=ReplaySkins.uuid(pose.get("uuid"));
        return id!=null?id:UUID.nameUUIDFromBytes(fallback.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static void applyHand(EntityPlayerSP viewer,Map<String,Object> pose){
        apply(viewer,pose);
        viewer.field_70732_aI=viewer.field_70733_aJ;
        // Vanilla subtracts these arm angles from the local player's view.
        // Older recordings have no arm lag sample, so align directly to the recorded view.
        viewer.field_71154_f=viewer.field_71163_h=viewer.field_70177_z-(float)number(pose,"hand_yaw_offset",0);
        viewer.field_71155_g=viewer.field_71164_i=viewer.field_70125_A-(float)number(pose,"hand_pitch_offset",0);
    }
    private void pose(double clock,float spectatorPartial){
        Minecraft mc=Minecraft.func_71410_x();if(!active||mc.field_71441_e==null||mc.field_71439_g==null)return;
        ReplayRecording.Pose snapshot=recording.poseAt(clock),next=recording.nextPose(clock);
        double span=snapshot==null||next==null?0:next.atMs-snapshot.atMs;
        double fraction=span>0&&span<=175&&(recording.sumo==null||next.atMs<=recording.playEnd())?(clock-snapshot.atMs)/span:0;
        Map<String,Object> self=snapshot==null?Collections.emptyMap():snapshot.self;
        visualSelf=fraction>0?ReplayVisuals.between(self,next.self,fraction):self;
        if(!self.isEmpty()){
            skins.remember(self);
            if(ownActor==null||ownActor.field_70170_p!=mc.field_71441_e){ownActor=new RecordedPlayer(mc.field_71441_e,new GameProfile(recordedId(self,"recorded-self"),String.valueOf(self.getOrDefault("name","RecordedSelf"))),true);mc.field_71441_e.func_73027_a(-2147483000,ownActor);}
            apply(ownActor,visualSelf);equipment(ownActor,self,mc);
        }
        Map<Object,Map<String,Object>> nextEntities=new HashMap<>();
        if(fraction>0&&next.state.get("entities")instanceof List)for(Object o:(List<?>)next.state.get("entities")){
            Map<String,Object> entity=object(o);nextEntities.put(entity.get("id"),entity);
        }
        if(snapshot!=null&&snapshot.state.get("entities")instanceof List)for(Object o:(List<?>)snapshot.state.get("entities")){
            Map<String,Object> state=object(o);
            Entity entity=mc.field_71441_e.func_73045_a((int)number(state,"id",Integer.MIN_VALUE));
            if(entity==null||entity==mc.field_71439_g)continue;
            skins.remember(state);
            Map<String,Object> future=nextEntities.get(state.get("id"));
            apply(entity,future==null?state:ReplayVisuals.between(state,future,fraction));
        }
        Map<String,Object> other=opponent(snapshot);
        skins.remember(other);
        visualTarget=fraction>0?ReplayVisuals.between(other,opponent(next),fraction):other;
        Entity target=target(mc,other);if(target!=mc.field_71439_g)apply(target,visualTarget);
        mc.field_71439_g.func_82142_c(true);mc.field_71439_g.field_70145_X=true;mc.field_71439_g.field_71075_bZ.field_75100_b=true;
        mc.field_71474_y.field_74320_O=0;
        if(camera.equals("recorded")&&ownActor!=null){
            // ItemRenderer draws the local player's hand, even with another camera entity.
            applyHand(mc.field_71439_g,visualSelf);
            mc.func_175607_a(ownActor);
        }
        else if(camera.equals("opponent")&&target!=null)mc.func_175607_a(target);
        else if(spectatorCamera()){
            EntityPlayerSP viewer=mc.field_71439_g;
            mc.func_175607_a(viewer);
            if(freePosition==null)centerSpectator();
            if(freePosition!=null){
                double[] prev=previousFreePosition==null?freePosition:previousFreePosition;
                double x=prev[0]+(freePosition[0]-prev[0])*spectatorPartial;
                double y=prev[1]+(freePosition[1]-prev[1])*spectatorPartial;
                double z=prev[2]+(freePosition[2]-prev[2])*spectatorPartial;
                viewer.func_70107_b(x,y,z);
                viewer.field_70142_S=x;viewer.field_70137_T=y;viewer.field_70136_U=z;
                viewer.field_70159_w=viewer.field_70181_x=viewer.field_70179_y=0;
            }
        }else mc.func_175607_a(ownActor!=null?ownActor:mc.field_71439_g);
    }
    boolean spectatorCamera(){return camera.equals("third_person");}
    void camera(String mode){
        saveSpectatorLook();
        camera=mode.equals("free")?"third_person":mode;
        restoreSpectatorLook();
        pose();
    }
    void centerSpectator(){
        if(ownActor==null)return;
        spectatorYaw=ownActor.field_70177_z;
        spectatorPitch=30;
        double yaw=Math.toRadians(spectatorYaw);
        freePosition=new double[]{ownActor.field_70165_t+3.5*Math.sin(yaw),
                ownActor.field_70163_u+2,ownActor.field_70161_v-3.5*Math.cos(yaw)};
        previousFreePosition=freePosition.clone();
        restoreSpectatorLook();
    }
    private void saveSpectatorLook(){
        EntityPlayerSP viewer=Minecraft.func_71410_x().field_71439_g;
        if(!spectatorCamera()||freePosition==null||viewer==null)return;
        spectatorYaw=viewer.field_70177_z;
        spectatorPitch=viewer.field_70125_A;
    }
    private void restoreSpectatorLook(){
        EntityPlayerSP viewer=Minecraft.func_71410_x().field_71439_g;
        if(!spectatorCamera()||freePosition==null||viewer==null)return;
        viewer.field_70177_z=viewer.field_70126_B=spectatorYaw;
        viewer.field_70125_A=viewer.field_70127_C=spectatorPitch;
    }
    void togglePerspective(){camera(spectatorCamera()?"recorded":"third_person");}
    String cameraLabel(){
        switch(camera){
            case "third_person":return "Spectator";
            case "opponent":return "Follow target";
            default:return "First person";
        }
    }
    void settings(Properties settings){reachEnabled=Boolean.parseBoolean(settings.getProperty("reach.enabled","false"));}
    boolean displayAvailable(ReplayDisplay.Layer layer){return layer!=REACH_FIELDS||reachEnabled;}
    boolean shows(ReplayDisplay.Layer layer){return displayAvailable(layer)&&display.shows(camera,layer);}
    void drawKeystrokes(FontRenderer font,int width,int height,boolean compact){
        if(hideText||!shows(KEYSTROKES)||!recording.inputs.available)return;
        practice.core.replay.ReplayInputs.State state=recording.inputs.at(playhead());
        if(compact)KeystrokesHud.drawCompact(font,state,width);
        else KeystrokesHud.drawAtLayout(font,state,width,height,bridge.hudLayout());
    }
    void editHud(){bridge.editHud(Minecraft.func_71410_x().field_71462_r);}
    void toggleDisplay(ReplayDisplay.Layer layer){
        try{display.toggle(camera,layer);message="";}
        catch(java.io.IOException failure){message="Display changed for this session. Could not save settings.";}
    }
    String recoilLabel(){
        if(!recording.hasAttackSamples)return "KB reductions · no paired attack samples";
        ReplayRecoil.Count count=recording.recoilAt(time);
        String last=count.hasKnockback?Integer.toString(count.afterKnockback):"—";
        return "KB reductions · after last hit "+last+" · total "+count.total;
    }
    boolean recoilFlash(){
        return !spectatorCamera()&&shows(RECOIL_FLASH)&&recording.attackScaleFlash(playhead());
    }
    private List<Frame.Line> motionLines(int height){
        List<Frame.Line> lines=new ArrayList<>();
        if(shows(OWN_VELOCITY)){
            ReplayMotion.Current own=recording.motion.at(time);
            String local=own==null||own.value==null?"unavailable":own.value.display()
                    +(time-own.at>250?" (older sample)":"");
            String sprint=ReplayMotion.sprintLabel(own==null?null:own.sprinting);
            lines.add(new Frame.Line("Your velocity "+local+" b/t · sprint "+sprint,6,0,0xB9E9FF,false));
        }
        if(!shows(CHANGE_LOG))return lines;
        List<ReplayMotion.Change> changes=recording.motion.recent(time,height>=330?7:4);
        for(ReplayMotion.Change change:changes){
            if(change.at<recording.playStart())continue;
            String value=change.value==null?"":" "+change.value.display();
            if(change.packet&&change.value!=null)value+=String.format(Locale.ROOT," H %.6f",change.value.horizontal());
            if(change.value==null&&change.delta!=null)value=" Δ "+change.delta.display();
            if(change.reason.startsWith("Attack ×")&&change.delta!=null)value+=String.format(Locale.ROOT," · ΔH %.6f",change.delta.horizontal());
            lines.add(new Frame.Line(String.format(Locale.ROOT,"%+.0fms  %s%s",change.at-time,change.reason,value),
                    6,0,change.packet?0x9FE4FF:0xFFFFFF,false));
        }
        return lines;
    }
    void seekReduction(boolean forward){
        double before=Double.NaN;
        for(ReplayRecording.Mark mark:recording.timeline){
            if(!mark.kind.equals("recoil_reduction"))continue;
            if(mark.atMs<recording.playStart())continue;
            if(mark.atMs>time){
                if(forward){paused=true;seek(mark.atMs);return;}
                break;
            }
            if(mark.atMs<time)before=mark.atMs;
        }
        if(!forward&&Double.isFinite(before)){paused=true;seek(before);}
    }
    Map<String,Object> replayFrame(){
        Map<String,Object> views=new LinkedHashMap<>();
        views.put("a",poseAge()>250?Collections.emptyMap():map("a",displayObservation(self(),visualSelf),"b",displayObservation(opponent(),visualTarget)));
        if(ownSource!=null)for(Map.Entry<String,PoseTrack> e:linked.entrySet()){if(!usableLinkedSource(ownSource,e.getKey()))continue;Map<String,Object> frame=e.getValue().at(time);if(!frame.isEmpty())views.put(e.getKey().equals("server")?"server":"b",linkedView(frame,ownSource));}
        Map<String,Object> av=map("a",map("label","Recorded own client","role","own-client","available",true),"b",map("label","Opponent client","available",views.containsKey("b")),"server",map("label","Server","available",views.containsKey("server")));
        Map<String,Object> a=object(views.get("a")),b=object(views.get("b"));
        return map("active",true,"offline",true,"match",entry.id,"offline_reach",shows(REACH_FIELDS),
                "offline_range_yours",shows(OWN_RANGE),"offline_range_theirs",shows(TARGET_RANGE),
                "opponent_pov_available",views.containsKey("b"),"paused",paused,"speed",speed,"time_ms",time-recording.playStart(),
                "duration_ms",recording.playEnd()-recording.playStart(),"source","a","availability",av,"views",views,"reach",Arrays.asList(3,3),
                "reach_advantage",practice.math.ReachAdvantage.compare(object(a.get("a")),object(a.get("b")),object(b.get("b")),object(b.get("a"))),
                "ghosts",Collections.emptyList(),"view_missing",self().isEmpty());
    }
    static boolean usableLinkedSource(String own,String source){return ("a".equals(own)||"b".equals(own))&&!source.equals(own)&&Arrays.asList("a","b","server").contains(source);}
    static Map<String,Object> linkedView(Map<String,Object> frame,String own){String other=own.equals("a")?"b":"a";return map("a",ReplayPresentation.observation(object(frame.get(own)),0),"b",ReplayPresentation.observation(object(frame.get(other)),0));}
    static Map<String,Object> observation(Map<String,Object> p){if(p.isEmpty())return p;Map<String,Object> out=new LinkedHashMap<>(p);if(!(out.get("pos")instanceof List))return ReplayPresentation.observation(out,0);return out;}
    private static Map<String,Object> displayObservation(Map<String,Object> logical,Map<String,Object> visual){
        Map<String,Object> observation=observation(logical);
        Object id=logical.get("id");
        if(id!=null&&id.equals(visual.get("id"))){
            Object render=visual.getOrDefault("render",visual.get("pos"));
            if(render!=null)observation.put("render",render);
        }
        return observation;
    }
    Frame hud(Frame base,int width,int height){
        if(!active)return base;List<Frame.Line> lines=new ArrayList<>(base.lines);int y=54;
        if(hideText)return new Frame(false,Collections.emptyList(),base.edgeColor,base.edgeWidth,base.rectangles);
        lines.add(new Frame.Line("REPLAY · "+cameraLabel()+" · F8 controls",6,y,0x88DDFF,false));y+=11;
        if(shows(KB_COUNT)){lines.add(new Frame.Line(recoilLabel(),6,y,0x91FFB0,false));y+=11;}
        if(recording.sumo!=null){
            if(!recording.sumo.hasArenaTerrain){lines.add(new Frame.Line("Arena terrain incomplete in this capture",6,y,0xFF8888,false));y+=11;}
        }else if(!recording.hasJoin||entry.midConnection){lines.add(new Frame.Line("MISSING INITIAL WORLD: partial capture; chunks/entities may be absent",6,y,0xFF8888,false));y+=11;}
        if(!failure.isEmpty()){lines.add(new Frame.Line(failure,6,y,0xFF7777,false));y+=11;}
        lines.addAll(motionLines(height));
        if(shows(DETAILS)){
        if(!recording.warnings.isEmpty()){String warning=recording.warnings.get(0);for(String w:recording.warnings)if(w.toLowerCase(Locale.ROOT).contains("oversize")||w.contains("2MiB")){warning=w;break;}lines.add(new Frame.Line("Warnings ("+recording.warnings.size()+"): "+warning,6,y,0xFFCC77,false));y+=11;}
        if(skipped>0){lines.add(new Frame.Line(skipped+" external/custom/disconnect or historical effects skipped",6,y,0xAAAAAA,false));y+=11;}
        lines.add(new Frame.Line(String.format(Locale.ROOT,"Pose sample age %.1f ms%s | 3-block reach fields assumed unless separately known",poseAge(),poseAge()>250?" — STALE: reach suppressed":""),6,y,0xAAAAAA,false));y+=11;
        double ownRange=poseAge()>250?Double.NaN:practice.math.ReachAdvantage.distance(observation(self()),observation(opponent()));
        if(Double.isFinite(ownRange)){lines.add(new Frame.Line(String.format(Locale.ROOT,"Recorded own POV nearest reach: %.3f blocks",ownRange),6,y,0x55FFFF,false));y+=11;}
        Map<String,Object> advantage=object(replayFrame().get("reach_advantage"));
        lines.add(new Frame.Line(advantage.isEmpty()?"Opponent POV unavailable: true reach advantage unavailable":String.format(Locale.ROOT,"Opponent POV reach %.3f | reach advantage %+.3f blocks",number(advantage,"d1_theirs",0),number(advantage,"advantage",0)),6,y,0xBBBBBB,false));y+=11;
        lines.add(new Frame.Line("Target hurt: "+(int)number(opponent(),"hurtTime",0)+" observed client ticks; server immunity not inferred",6,y,0xBBBBBB,false));y+=11;
        ReplayRecording.Mark attack=null,velocity=null,rtt=null,hurt=null;
        for(ReplayRecording.Mark mark:recording.timeline){if(mark.atMs>time)break;if(mark.kind.equals("attack"))attack=mark;if(mark.kind.contains("velocity")&&Boolean.TRUE.equals(mark.details.get("own")))velocity=mark;if(mark.kind.contains("rtt"))rtt=mark;if(mark.kind.contains("hurt"))hurt=mark;}
        if(attack!=null){lines.add(new Frame.Line(String.format(Locale.ROOT,"Since outbound attack: %.0f ms | since observed hurt: %s",time-attack.atMs,hurt==null?"unavailable":String.format(Locale.ROOT,"%.0f ms",time-hurt.atMs)),6,y,0xFFCC77,false));y+=11;}
        if(velocity!=null){lines.add(new Frame.Line("Own S12 exact velocity: "+velocity.details.get("velocity")+(velocity.details.containsKey("since_attack_ms")?" | prior attack +"+String.format(Locale.ROOT,"%.1fms (unconfirmed)",number(velocity.details,"since_attack_ms",0)):""),6,y,0x55FFFF,false));y+=11;}
        lines.add(new Frame.Line(rtt==null?"Measured RTT unavailable; attack→self-S12 is not a causal hit confirmation":String.format(Locale.ROOT,"Transaction round trip %.1f ms (includes server processing)",number(rtt.details,"rtt_ms",0)),6,y,0xAAAAAA,false));y+=11;
        ReplayRecording.Mark last=null;for(ReplayRecording.Mark mark:recording.timeline)if(mark.atMs<=time)last=mark;else break;
        if(last!=null)lines.add(new Frame.Line(String.format(Locale.ROOT,"Last: %s  (%.0f ms ago)",last.label,time-last.atMs),6,y,0xFFFFFF,false));
        }
        List<Frame.Line> fitted=new ArrayList<>();int nextY=54,max=Math.max(24,(width-12)/6);
        for(Frame.Line line:lines){if(line.centered||line.right){fitted.add(line);continue;}String text=line.text;while(text.length()>max){int split=text.lastIndexOf(' ',max);if(split<8)split=max;fitted.add(new Frame.Line(text.substring(0,split),6,nextY,line.color,false));nextY+=11;text=text.substring(split).trim();}fitted.add(new Frame.Line(text,6,nextY,line.color,false));nextY+=11;}
        return new Frame(false,fitted,base.edgeColor,base.edgeWidth,base.rectangles);
    }
}
