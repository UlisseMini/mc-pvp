package practice.smoke;

import practice.core.replay.ReplayRecording;
import practice.core.replay.ReplayInputs;
import practice.api.*;
import practice.core.*;
import com.google.gson.Gson;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.client.gui.GuiIngameMenu;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.gui.ChatLine;
import net.minecraft.client.gui.GuiNewChat;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.multiplayer.GuiConnecting;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.util.IChatComponent;
import net.minecraft.event.ClickEvent;
import net.minecraftforge.client.event.GuiOpenEvent;
import net.minecraftforge.client.ClientCommandHandler;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.*;
import net.minecraftforge.fml.common.event.*;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.*;

/** One passive lobby guest captures from before PLAY, then disconnects itself
 * and replays remote + existing lab data without any network connection. */
@Mod(modid="practiceofflineprobe",name="Offline replay probe",version="1",clientSideOnly=true,dependencies="required-after:practicebridge")
public final class OfflineReplayProbe {
    Object bridge,capture,replay,host;int ticks,stage,at,checks;Path remoteFile;ReplayRecording remote,lab;Map<String,Object> result=new LinkedHashMap<>();
    String adjacentId;
    long switchTime;
    long ledgerSeekWall;
    Object field(Object o,String n)throws Exception{Field f=o.getClass().getDeclaredField(n);f.setAccessible(true);return f.get(o);}
    Object call(Object o,String n,Class<?>[] t,Object...v)throws Exception{Method m=o.getClass().getDeclaredMethod(n,t);m.setAccessible(true);return m.invoke(o,v);}
    void check(boolean ok,String why){if(!ok)throw new AssertionError(why);checks++;System.out.println("[OfflineProbe] PASS "+why);}
    @Mod.EventHandler public void init(FMLInitializationEvent e)throws Exception{
        bridge=Loader.instance().getIndexedModList().get("practicebridge").getMod();capture=field(bridge,"capture");replay=field(bridge,"offline");host=field(bridge,"host");
        // Test-only: production remains OFF at launch. Called before auto-connect.
        call(capture,"remoteRecording",new Class[]{boolean.class},true);if(Boolean.getBoolean("practice.test.graphical")){stage=4;}
        if(System.getProperty("practice.test.reuseRemote")!=null){stage=2;remoteFile=Paths.get(System.getProperty("practice.test.reuseRemote"));}
        FMLCommonHandler.instance().bus().register(this);
        if(stage==4)MinecraftForge.EVENT_BUS.register(this);
    }
    @SubscribeEvent public void graphicalMenu(GuiOpenEvent event) throws org.lwjgl.LWJGLException {
        if(stage==4&&event.gui instanceof GuiMainMenu)ReplayGraphics.restoreContext();
    }
    void play(ReplayRecording.Entry e,ReplayRecording r)throws Exception{call(replay,"start",new Class[]{ReplayRecording.Entry.class,ReplayRecording.class},e,r);}
    void seek(double t)throws Exception{call(replay,"seek",new Class[]{double.class},t);}
    private static final class ChatClick extends GuiScreen {
        boolean click(IChatComponent link){field_146297_k=Minecraft.func_71410_x();return func_175276_a(link);}
    }
    private void replayButton(String label)throws Exception{
        GuiScreen screen=Minecraft.func_71410_x().field_71462_r;
        GuiButton button=findButton(screen,label);
        if(button==null)throw new AssertionError("Replay button missing: "+label);
        call(screen,"func_146284_a",new Class[]{GuiButton.class},button);
    }
    private GuiButton findButton(GuiScreen screen,String label)throws Exception{
        Field buttons=GuiScreen.class.getDeclaredField("field_146292_n");buttons.setAccessible(true);
        for(GuiButton button:(List<GuiButton>)buttons.get(screen)){
            if(button.field_146126_j.startsWith(label))return button;
        }
        return null;
    }
    private void checkReturnToMmc()throws Exception{
        Minecraft mc=Minecraft.func_71410_x();
        call(replay,"browser",new Class[0]);
        check(findButton(mc.field_71462_r,"Return to MMC")!=null,"F8 replay controls offer MMC return");
        mc.func_147108_a(new GuiIngameMenu());
        GuiScreen menu=mc.field_71462_r;
        GuiButton back=findButton(menu,"Return to MMC");
        check(back!=null,"Esc pause menu offers MMC return during playback");

        // The smoke launcher denies public outbound sockets. This exercises the vanilla handoff only.
        ServerData recent=new ServerData("MMC fixture","smoke.mineman.club:25566",false);
        mc.func_71351_a(recent);
        Method click=GuiScreen.class.getDeclaredMethod("func_73864_a",int.class,int.class,int.class);
        click.setAccessible(true);
        click.invoke(menu,back.field_146128_h+1,back.field_146129_i+1,0);
        check(mc.field_71462_r instanceof GuiConnecting&&mc.func_147104_D()==recent,
                "Esc button passes the same MMC server entry to vanilla connecting");
        check(!(Boolean)field(replay,"active")&&mc.field_71441_e==null&&field(replay,"network")==null,
                "MMC return unloads the replay and clears its offline network");
        GuiScreen ordinary=new GuiIngameMenu();
        ordinary.func_146280_a(mc,menu.field_146294_l,menu.field_146295_m);
        check(findButton(ordinary,"Return to MMC")==null,"non-replay pause menu has no MMC return button");
    }
    private Frame replayHud()throws Exception{
        return (Frame)call(replay,"hud",new Class[]{Frame.class,int.class,int.class},Frame.empty(),960,540);
    }
    private WorldFrame replayWorld()throws Exception{
        call(bridge,"scope",new Class[0]);
        Map<String,Object> frame=(Map<String,Object>)call(replay,"replayFrame",new Class[0]);
        host.getClass().getMethod("event",PracticeEvent.class).invoke(host,new PracticeEvent("replay_frame",0,0,0,frame));
        return (WorldFrame)host.getClass().getMethod("world",float.class).invoke(host,0f);
    }
    private void finishOrCheckAppearance()throws Exception{
        String appearance=System.getProperty("practice.test.appearanceMatch");
        if(appearance==null){finishOrCheckLedger();return;}
        check((Boolean)call(replay,"watchSumo",new Class[]{String.class},appearance),"saved skin and hand fixture selected");
        stage=13;at=ticks;
    }
    private void finishOrCheckLedger()throws Exception{
        String practice=System.getProperty("practice.test.ledgerPractice");
        if(practice!=null){clickLedgerBookmark(practice);stage=14;at=ticks;}
        else finishOrCheckSumoLedger();
    }
    private void finishOrCheckSumoLedger()throws Exception{
        String sumo=System.getProperty("practice.test.ledgerSumo");
        if(sumo==null){checkReturnToMmc();finish();return;}
        clickLedgerBookmark(sumo);
        stage=15;at=ticks;
    }
    private void clickLedgerBookmark(String id)throws Exception{
        Minecraft mc=Minecraft.func_71410_x();
        int handled=ClientCommandHandler.instance.func_71556_a(mc.field_71439_g,"/p ledger "+id);
        check(handled==1,"client recognizes the generated ledger command");
        Field lines=GuiNewChat.class.getDeclaredField("field_146252_h");lines.setAccessible(true);
        for(ChatLine row:(List<ChatLine>)lines.get(mc.field_71456_v.func_146158_b())){
            IChatComponent link=row.func_151461_a();
            ClickEvent click=link.func_150256_b().func_150235_h();
            if(click==null||!click.func_150668_b().startsWith("/p replayat "+id+" "))continue;
            ledgerSeekWall=Long.parseLong(click.func_150668_b().substring(click.func_150668_b().lastIndexOf(' ')+1));
            check(new ChatClick().click(link),"clicking the generated local chat bookmark triggers seek");
            return;
        }
        throw new AssertionError("Seek bookmark did not appear for "+id);
    }
    private void checkLedgerSeek(String expected)throws Exception{
        ReplayRecording selected=(ReplayRecording)field(replay,"recording");
        double time=((Number)field(replay,"time")).doubleValue();
        boolean paused=(Boolean)field(replay,"paused");
        if(selected==null||!selected.entry.id.equals(expected)||Math.abs(time+selected.startMs-ledgerSeekWall)>1||!paused){
            throw new AssertionError("Expected paused "+expected+" at "+ledgerSeekWall+", got "
                    +(selected==null?"no recording":selected.entry.id+" at "+(time+selected.startMs))
                    +" (paused="+paused+"): "+field(replay,"message")+" "+field(replay,"failure"));
        }
        check(true,"ledger link opens "+expected+" and pauses at the requested epoch");
    }
    private boolean arenaHasBlocks(Minecraft mc)throws Exception{
        net.minecraft.entity.Entity player=(net.minecraft.entity.Entity)field(replay,"ownActor");
        for(int x=-4;x<=4;x++)for(int y=-4;y<1;y++)for(int z=-4;z<=4;z++){
            net.minecraft.util.BlockPos pos=new net.minecraft.util.BlockPos(player.field_70165_t+x,player.field_70163_u+y,player.field_70161_v+z);
            if(!mc.field_71441_e.func_175623_d(pos))return true;
        }
        return false;
    }
    void finish()throws Exception{
        result.put("checks",checks);result.put("scope","socket-free replay reconstruction");
        Files.write(Paths.get(System.getProperty("practice.test.output")),JsonData.encode(result).getBytes("UTF-8"));
        System.out.println("[OfflineProbe] ALL CHECKS PASSED");
        Minecraft.func_71410_x().func_71400_g();
        stage=3;
    }
    @SubscribeEvent public void tick(TickEvent.ClientTickEvent event){if(event.phase!=TickEvent.Phase.END)return;Minecraft mc=Minecraft.func_71410_x();ticks++;
        try{
            if(stage==4&&ticks>=25){
                ReplayGraphics.prepare(mc);
                Gson gson=new Gson();Path folder=Paths.get(System.getProperty("practice.test.labArchive"));ReplayRecording.Entry le=ReplayRecording.archiveEntry(folder,s->(Map<String,Object>)gson.fromJson(s,Map.class));check(le!=null,"graphical lab fixture found");lab=ReplayRecording.load(le,s->(Map<String,Object>)gson.fromJson(s,Map.class));play(le,lab);
                Map<String,Object> manifest=gson.fromJson(new String(Files.readAllBytes(folder.resolve("manifest.json")),"UTF-8"),Map.class);double target=((Number)manifest.get("start_ms")).doubleValue()-lab.startMs+12000;seek(Math.min(lab.durationMs-1,target));check(String.valueOf(field(replay,"failure")).isEmpty(),"graphical lab reconstruction");mc.func_147108_a(new net.minecraft.client.gui.GuiScreen(){public boolean func_73868_f(){return false;}});mc.func_71364_i();stage=5;at=ticks;return;
            }
            if(stage==5){if(ticks-at>=60){check(mc.field_71441_e!=null,"graphical replay world exists");net.minecraft.util.ScreenShotHelper.func_148259_a(mc.field_71412_D,"offline-replay.png",mc.field_71443_c,mc.field_71440_d,mc.func_147110_a());System.out.println("[OfflineProbe] GRAPHICAL SCENE SAVED");call(replay,"camera",new Class[]{String.class},"free");double[] fp=(double[])field(replay,"freePosition");double yaw=Math.toRadians(mc.field_71439_g.field_70177_z);fp[0]+=Math.sin(yaw)*4;fp[2]-=Math.cos(yaw)*4;mc.field_71439_g.field_70125_A=20;stage=6;at=ticks;}return;}
            if(stage==6){if(ticks-at>=10){net.minecraft.util.ScreenShotHelper.func_148259_a(mc.field_71412_D,"offline-free.png",mc.field_71443_c,mc.field_71440_d,mc.func_147110_a());call(replay,"browser",new Class[0]);stage=7;at=ticks;}return;}
            if(stage==7){if(ticks-at>=10){net.minecraft.util.ScreenShotHelper.func_148259_a(mc.field_71412_D,"offline-controls.png",mc.field_71443_c,mc.field_71440_d,mc.func_147110_a());System.out.println("[OfflineProbe] GRAPHICAL SCREENSHOT SAVED");mc.func_71400_g();stage=8;}return;}
            if(stage==8)return;
            if(stage==0&&mc.field_71439_g!=null){stage=1;at=ticks;check(ClientCommandHandler.instance.func_71555_a().containsKey("practice")&&ClientCommandHandler.instance.func_71555_a().containsKey("p")&&!ClientCommandHandler.instance.func_71555_a().containsKey("replay"),"only practice/p client roots; server /replay not shadowed");}
            if(stage==1&&ticks-at==20){check(field(capture,"log")!=null,"test-only before-PLAY opt-in records");remoteFile=((CaptureLog)field(capture,"log")).path;
                call(replay,"refresh",new Class[0]);List<ReplayRecording.Entry> list=(List<ReplayRecording.Entry>)field(replay,"entries");check(!list.isEmpty(),"capture list available while connected");
                ReplayRecording.Entry e=list.stream().filter(v->v.firstFile.equals(remoteFile.toAbsolutePath())).findFirst().get();call(replay,"load",new Class[]{ReplayRecording.Entry.class},e);check(!(Boolean)field(replay,"active")&&String.valueOf(field(replay,"message")).contains("Disconnect"),"live playback refused without disconnecting player");}
            if(stage==1&&ticks-at>=130){
                String id=System.getProperty("practice.test.sumoMatch");
                if(id!=null){
                    // Only the passive test client's displayed hostname changes.
                    // Its real socket still points to the local lab.
                    mc.func_71351_a(new net.minecraft.client.multiplayer.ServerData("MMC fixture","mineman.club",false));
                    IChatComponent link=(IChatComponent)call(bridge,"sumoLink",new Class[]{String.class},id);
                    check(new ChatClick().click(link)&&mc.field_71441_e==null&&field(replay,"queuedMatch")!=null,
                            "MMC chat click closes the live world before starting playback");
                    stage=9;at=ticks;return;
                }
                call(capture,"remoteRecording",new Class[]{boolean.class},false);mc.field_71441_e.func_72882_A();mc.func_71403_a(null);mc.func_147108_a(new GuiMainMenu());stage=2;at=ticks;
            }
            if(stage==2&&ticks-at>=25){call(replay,"refresh",new Class[0]);List<ReplayRecording.Entry> list=(List<ReplayRecording.Entry>)field(replay,"entries");ReplayRecording.Entry e=list.stream().filter(v->v.firstFile.equals(remoteFile.toAbsolutePath())).findFirst().get();
                Gson gson=new Gson();remote=ReplayRecording.load(e,s->(Map<String,Object>)gson.fromJson(s,Map.class));check(remote.hasJoin&&!e.midConnection,"remote full capture contains JOIN and initial world");check(remote.packets.size()>20&&remote.poses.size()>20,"remote packet/pose stream loaded");play(e,remote);seek(remote.durationMs-100);check(String.valueOf(field(replay,"failure")).isEmpty(),"full remote packet reconstruction has no handler failure: "+field(replay,"failure"));check(mc.field_71441_e!=null&&mc.field_71439_g!=null,"offline remote PLAY built native world/player");check((Integer)field(replay,"applied")>20,"native inbound handlers executed");
                Object network=field(replay,"network");for(Field f:net.minecraft.network.NetworkManager.class.getDeclaredFields())if(io.netty.channel.Channel.class.isAssignableFrom(f.getType())){f.setAccessible(true);check(f.get(network)==null,"fake play connection has no socket/channel");}
                check((Long)field(network,"discarded")>0,"normal client replies discarded");check(!(Boolean)call(capture,"practiceAllowed",new Class[0])&&field(capture,"log")==null,"offline playback cannot capture or link");
                double t=((Number)field(replay,"time")).doubleValue(),expected=remote.tickStep(t);call(replay,"step",new Class[0]);check(Math.abs(((Number)field(replay,"time")).doubleValue()-expected)<1e-6,"single captured client tick step uses exact recorded boundary");
                call(replay,"speed",new Class[]{double.class},4.0);check(((Number)field(replay,"speed")).doubleValue()==4,"4x speed");call(replay,"speed",new Class[]{double.class},.1);check(((Number)field(replay,"speed")).doubleValue()==.1,"0.1x speed");seek(remote.durationMs/2);check(String.valueOf(field(replay,"failure")).isEmpty(),"backward seek reconstructs from start");
                call(replay,"camera",new Class[]{String.class},"free");
                check(mc.func_175606_aa()==mc.field_71439_g,"free camera");
                call(replay,"camera",new Class[]{String.class},"recorded");
                Object actor=mc.func_175606_aa();
                check(actor!=mc.field_71439_g&&mc.field_71474_y.field_74320_O==0,"recorded first-person camera");
                String stats=(String)call(replay,"recoilLabel",new Class[0]);
                call(replay,"togglePerspective",new Class[0]);
                check(mc.func_175606_aa()==mc.field_71439_g&&mc.field_71474_y.field_74320_O==0
                        &&mc.field_71442_b.func_78747_a(),"spectator camera detaches from the recorded player");
                check(stats.equals(call(replay,"recoilLabel",new Class[0])),"both perspectives show the same recoil evidence");
                call(replay,"togglePerspective",new Class[0]);
                check(mc.field_71474_y.field_74320_O==0,"perspective toggles back to first person");
                result.put("remote",JsonData.map("has_join",remote.hasJoin,"packets",remote.packets.size(),"poses",remote.poses.size(),"warnings",remote.warnings,"applied",field(replay,"applied")));
                call(replay,"stop",new Class[0]);
                Path folder=Paths.get(System.getProperty("practice.test.labArchive"));ReplayRecording.Entry le=ReplayRecording.archiveEntry(folder,s->(Map<String,Object>)gson.fromJson(s,Map.class));lab=ReplayRecording.load(le,s->(Map<String,Object>)gson.fromJson(s,Map.class));check(lab.hasJoin,"existing real lab full session contains JOIN");play(le,lab);
                Map<String,Object> lm=gson.fromJson(new String(Files.readAllBytes(folder.resolve("manifest.json")),"UTF-8"),Map.class);double sample=Math.min(lab.durationMs-1,Math.max(1000,((Number)lm.get("start_ms")).doubleValue()-lab.startMs+12000));seek(sample);check(String.valueOf(field(replay,"failure")).isEmpty(),"real lab packet reconstruction has no handler failure");check(lab.timeline.stream().anyMatch(m->m.kind.equals("attack"))&&lab.timeline.stream().anyMatch(m->m.kind.contains("velocity")),"lab attack/S12 timeline decoded");
                Map<String,Object> own=(Map<String,Object>)call(replay,"self",new Class[0]),target=(Map<String,Object>)call(replay,"opponent",new Class[0]);check(!target.isEmpty(),"lab observed target selected from recorded own view");check(Double.isFinite(practice.math.ReachAdvantage.distance(own,target)),"recorded own reach is measurable");Map<String,Object> data=(Map<String,Object>)call(replay,"replayFrame",new Class[0]);check(!((Map)data.get("reach_advantage")).isEmpty(),"independent linked client POV supports real advantage");host.getClass().getMethod("event",PracticeEvent.class).invoke(host,new PracticeEvent("connection_scope",0,0,0,JsonData.map("practice_allowed",true)));host.getClass().getMethod("event",PracticeEvent.class).invoke(host,new PracticeEvent("replay_frame",0,0,0,data));Frame frame=(Frame)host.getClass().getMethod("render",float.class,int.class,int.class).invoke(host,0f,960,540);frame=(Frame)call(replay,"hud",new Class[]{Frame.class,int.class,int.class},frame,960,540);
                List<Object> lines=new ArrayList<>();for(Frame.Line line:frame.lines)lines.add(JsonData.map("text",line.text,"x",line.x,"y",line.y,"color",line.color,"scale",line.scale,"right",line.right,"centered",line.centered));result.put("overlay",lines);result.put("lab",JsonData.map("has_join",lab.hasJoin,"packets",lab.packets.size(),"poses",lab.poses.size(),"timeline",lab.timeline.size(),"warnings",lab.warnings,"time_ms",field(replay,"time"),"applied",field(replay,"applied")));check(!lines.isEmpty(),"actual viewer overlay frame exported");
                call(replay,"stop",new Class[0]);check(mc.field_71441_e==null,"exit unloads only offline world");
                // A mid-connection capture is visibly partial, never fabricated world evidence.
                ReplayRecording.Entry partial=new ReplayRecording.Entry("partial","partial","local",0,0,Paths.get(System.getProperty("practice.test.partialCapture")),true,null);ReplayRecording pr=ReplayRecording.load(partial,s->(Map<String,Object>)gson.fromJson(s,Map.class));check(!pr.hasJoin,"existing mid-connection remote fixture lacks JOIN");play(partial,pr);seek(Math.min(1000,pr.durationMs));Frame pf=(Frame)call(replay,"hud",new Class[]{Frame.class,int.class,int.class},Frame.empty(),960,540);check(pf.lines.stream().anyMatch(l->l.text.contains("MISSING INITIAL WORLD")),"partial capture has explicit missing-world warning");
                String id=System.getProperty("practice.test.sumoMatch");
                if(id==null){finishOrCheckLedger();}
                else{
                    IChatComponent link=(IChatComponent)call(bridge,"sumoLink",new Class[]{String.class},id);
                    check(new ChatClick().click(link)&&(Boolean)field(replay,"loading"),"MMC chat click routes into the local replay loader");
                    stage=9;at=ticks;
                }
            }
            if(stage==9&&ticks-at>160)throw new AssertionError("Queued MMC replay did not open: "+field(replay,"message"));
            if(stage==9&&field(replay,"queuedMatch")==null&&!(Boolean)field(replay,"loading")){
                ReplayRecording selected=(ReplayRecording)field(replay,"recording");
                check(selected.entry.id.equals("sumo:"+System.getProperty("practice.test.sumoMatch")),"chat link opens its exact archived MMC duel");
                check(selected.sumo.capturedCountdown&&selected.sumo.capturedResult
                        &&((Number)field(replay,"time")).doubleValue()==selected.openingTime()
                        &&String.valueOf(field(replay,"failure")).isEmpty(),"MMC replay opens at its decisive moment");
                check(arenaHasBlocks(mc)&&selected.sumo.hasArenaTerrain,"captured arena blocks are present at the opening frame");
                Object arenaWorld=mc.field_71441_e,ownActor=field(replay,"ownActor");
                seek(0);
                check(((Number)field(replay,"time")).doubleValue()==selected.playStart()&&arenaHasBlocks(mc)
                                &&mc.field_71441_e==arenaWorld&&field(replay,"ownActor")==ownActor,
                        "rewind to the countdown retains terrain, world and the recorded player");
                call(replay,"key",new Class[]{int.class,boolean.class},org.lwjgl.input.Keyboard.KEY_SPACE,false);
                check(!((Boolean)field(replay,"paused")),"Space resumes replay");
                call(replay,"key",new Class[]{int.class,boolean.class},org.lwjgl.input.Keyboard.KEY_SPACE,false);
                check((Boolean)field(replay,"paused"),"Space pauses replay");
                seek(selected.playStart()+1500);
                double initial=((Number)field(replay,"time")).doubleValue();
                call(mc.field_71462_r,"func_73869_a",new Class[]{char.class,int.class},'[',org.lwjgl.input.Keyboard.KEY_LBRACKET);
                check(((Number)field(replay,"speed")).doubleValue()==.5,"left bracket selects the slower F8 preset");
                call(replay,"key",new Class[]{int.class,boolean.class},org.lwjgl.input.Keyboard.KEY_RIGHT,false);
                check(((Number)field(replay,"time")).doubleValue()==initial,"right arrow press has no fixed seek jump");
                Class<?>[] playbackClock={double.class,int.class};
                call(replay,"updatePlayback",playbackClock,0d,1);
                call(replay,"updatePlayback",playbackClock,400d,1);
                check(((Number)field(replay,"time")).doubleValue()==initial+200,"holding right advances at the selected half speed");
                call(replay,"updatePlayback",playbackClock,0d,-1);
                Object forwardWorld=field(replay,"network");
                call(replay,"updatePlayback",playbackClock,150d,-1);
                call(replay,"updatePlayback",playbackClock,150d,-1);
                check(((Number)field(replay,"time")).doubleValue()==initial+50&&field(replay,"network")==forwardWorld,
                        "holding left rewinds at half speed without rebuilding the world per tick");
                check(((Number)call(replay,"projectedTime",new Class[]{double.class},40d)).doubleValue()==initial+30,
                        "reverse preview interpolates between playback ticks");
                call(replay,"updatePlayback",playbackClock,0d,0);
                check(field(replay,"network")==forwardWorld&&(Boolean)field(replay,"paused")&&arenaHasBlocks(mc),
                        "releasing left keeps the arena loaded and preserves the pause state");
                call(replay,"key",new Class[]{int.class,boolean.class},org.lwjgl.input.Keyboard.KEY_RBRACKET,false);
                check(((Number)field(replay,"speed")).doubleValue()==1,"right bracket restores the 1x F8 preset");
                call(replay,"key",new Class[]{int.class,boolean.class},org.lwjgl.input.Keyboard.KEY_PERIOD,false);
                check(((Number)field(replay,"time")).doubleValue()==selected.tickStep(initial+50),"period steps one recorded tick forward");
                call(replay,"key",new Class[]{int.class,boolean.class},org.lwjgl.input.Keyboard.KEY_COMMA,false);
                check(((Number)field(replay,"time")).doubleValue()==selected.previousTick(selected.tickStep(initial+50))
                                &&mc.field_71441_e==arenaWorld&&field(replay,"ownActor")==ownActor,
                        "comma steps one recorded tick back without replacing the world or recorded player");
                ReplayRecording.Pose swing=selected.poses.stream().filter(p->p.atMs>=selected.playStart()&&p.atMs<=selected.playEnd()
                        &&p.self.get("swingProgress") instanceof Number&&((Number)p.self.get("swingProgress")).floatValue()>.2f
                        &&p.self.containsKey("held")&&p.self.get("held")==null).findFirst().get();
                seek(swing.atMs);
                Method hideHand=Class.forName("practice.bridge.ObserverHooks").getMethod("hideEmptyHand",boolean.class);
                check(mc.field_71439_g.func_82150_aj()&&!(Boolean)hideHand.invoke(null,true)
                        &&mc.field_71439_g.func_70678_g(.5f)==((Number)swing.self.get("swingProgress")).floatValue(),
                        "first-person empty-hand renderer can draw the recorded swing while the viewer stays invisible");
                check(selected.inputs.available&&selected.poses.stream().filter(p->p.atMs>=selected.playStart()&&p.atMs<=selected.playEnd())
                        .anyMatch(p->selected.inputs.at(p.atMs).leftCps>0&&selected.inputs.at(p.atMs).down(ReplayInputs.Key.W)),
                        "existing MMC recording indexes actual movement keys and click rate");
                Object liveKeys=field(bridge,"keystrokes");
                Class<?>[] mouseClick={int.class,long.class};
                call(liveKeys,"clicked",mouseClick,0,1000000000L);
                call(liveKeys,"clicked",mouseClick,0,1500000000L);
                call(liveKeys,"clicked",mouseClick,1,1800000000L);
                ReplayInputs.State liveState=(ReplayInputs.State)call(liveKeys,"live",new Class[]{long.class},2000000000L);
                check(liveState.leftCps==1&&liveState.rightCps==1,"live CPS expires each mouse button's clicks after one second");
                replayButton("Edit HUD");
                GuiScreen editor=mc.field_71462_r;
                Field widthField=GuiScreen.class.getDeclaredField("field_146294_l"),heightField=GuiScreen.class.getDeclaredField("field_146295_m");
                widthField.setAccessible(true);heightField.setAccessible(true);
                int width=widthField.getInt(editor),height=heightField.getInt(editor);
                Class<?>[] pointer={int.class,int.class,int.class};
                call(editor,"func_73864_a",pointer,width-56,height-98,0);
                call(editor,"func_146273_a",new Class[]{int.class,int.class,int.class,long.class},55,45,0,100L);
                call(editor,"func_146286_b",pointer,55,45,0);
                call(editor,"func_73864_a",pointer,width-12,8,0);
                call(editor,"func_146286_b",pointer,160,80,0);
                Properties layout=new Properties();
                try(java.io.InputStream in=Files.newInputStream(mc.field_71412_D.toPath().resolve("config/pvp-practice/hud-layout.properties"))){layout.load(in);}
                check(layout.containsKey("keystrokes.x")&&Double.parseDouble(layout.getProperty("keystrokes.x"))<.5,"HUD drag writes the new keystrokes position");
                check(layout.containsKey("sprint.y")&&Double.parseDouble(layout.getProperty("sprint.y"))>.1,"the editor also saves sprint indicator movement");
                replayButton("Done");
                Object playbackNetwork=field(replay,"network");
                for(Field f:net.minecraft.network.NetworkManager.class.getDeclaredFields())if(io.netty.channel.Channel.class.isAssignableFrom(f.getType())){
                    f.setAccessible(true);check(f.get(playbackNetwork)==null,"MMC playback uses a socket-free network manager");
                }
                double cut=selected.timeline.stream().filter(m->m.atMs>=selected.playStart()&&m.kind.equals("recoil_reduction"))
                        .findFirst().get().atMs;
                seek(cut);
                check((Boolean)call(replay,"recoilFlash",new Class[0]),"confirmed MMC cut flashes the first-person crosshair");
                check(!replayWorld().spheres.isEmpty(),"configured reach fields initially appear in the MMC replay");
                check((Boolean)call(replay,"openCommandChat",new Class[]{int.class},org.lwjgl.input.Keyboard.KEY_SLASH),"slash opens local command chat in replay");
                GuiScreen commands=mc.field_71462_r;
                Class<?>[] submit={String.class,boolean.class};
                call(commands,"func_175281_b",submit,"/p help",true);
                call(commands,"func_175281_b",submit,"/p reach off",true);
                check(replayWorld().spheres.isEmpty(),"/p reach off immediately hides the replay reach fields");
                call(commands,"func_175281_b",submit,"/p reach on",true);
                check(!replayWorld().spheres.isEmpty(),"/p reach on restores the replay reach fields");
                call(commands,"func_175281_b",submit,"/p crosshair off",true);
                Properties replayCommands=new Properties();
                try(java.io.InputStream in=Files.newInputStream(mc.field_71412_D.toPath().resolve("config/pvp-practice/settings.properties"))){replayCommands.load(in);}
                check("false".equals(replayCommands.getProperty("range.crosshair.enabled"))
                        &&(Boolean)call(replay,"recoilFlash",new Class[0]),"crosshair command leaves the confirmed recoil flash available");
                call(commands,"func_175281_b",submit,"/p crosshair on",true);
                call(commands,"func_73869_a",new Class[]{char.class,int.class},'\0',org.lwjgl.input.Keyboard.KEY_ESCAPE);
                call(replay,"key",new Class[]{int.class,boolean.class},org.lwjgl.input.Keyboard.KEY_O,false);
                check(replayHud().lines.isEmpty()&&(Boolean)call(replay,"recoilFlash",new Class[0]),"O hides extra text while preserving the recoil flash");
                call(replay,"key",new Class[]{int.class,boolean.class},org.lwjgl.input.Keyboard.KEY_O,false);
                call(replay,"togglePerspective",new Class[0]);
                check(mc.func_175606_aa()==mc.field_71439_g&&mc.field_71474_y.field_74320_O==0
                        &&(Boolean)hideHand.invoke(null,true),"detached spectator still hides the first-person hand");
                GuiScreen controls=mc.field_71462_r;
                double[] position=(double[])field(replay,"freePosition");
                double x=position[0],z=position[2];
                mc.func_147108_a(null);
                int forward=mc.field_71474_y.field_74351_w.func_151463_i();
                KeyBinding.func_74510_a(forward,true);
                try{call(replay,"tick",new Class[0]);}
                finally{KeyBinding.func_74510_a(forward,false);mc.func_147108_a(controls);}
                check(Math.hypot(position[0]-x,position[2]-z)>.25,"W flies the spectator camera while replay is paused");
                mc.field_71439_g.field_70177_z=137;mc.field_71439_g.field_70125_A=11;
                double[] heldPosition=position.clone();
                seek(Math.max(0,cut-500));
                check(Arrays.equals(heldPosition,(double[])field(replay,"freePosition"))
                        &&mc.field_71439_g.field_70177_z==137&&mc.field_71439_g.field_70125_A==11,
                        "spectator position and look survive a backward seek");
                seek(cut);
                replayButton("Display");
                replayButton("Your velocity");
                replayButton("Keystrokes and CPS");
                Frame spectatorHud=replayHud();
                check(spectatorHud.lines.stream().noneMatch(line->line.text.startsWith("Your velocity"))
                        &&spectatorHud.lines.stream().noneMatch(line->line.text.startsWith("Target visible velocity")),
                        "spectator honors the velocity switch and does not show the removed estimate");
                replayButton("First person");
                check(replayHud().lines.stream().anyMatch(line->line.text.startsWith("Your velocity"))
                        &&(Boolean)call(replay,"recoilFlash",new Class[0]),"first person keeps its own overlay choices");
                Properties settings=new Properties();
                try(java.io.InputStream in=Files.newInputStream(mc.field_71412_D.toPath().resolve("config/pvp-practice/replay-display.properties"))){settings.load(in);}
                check("false".equals(settings.getProperty("third_person.own_velocity"))
                        &&"false".equals(settings.getProperty("third_person.keystrokes"))
                        &&!settings.containsKey("recorded.own_velocity"),"display preferences are saved for the selected camera");
                seek(selected.durationMs);
                check(((Number)field(replay,"time")).doubleValue()==selected.playEnd(),"fast-forward stops at the result before the lobby");
                check(selected.recoilAt(selected.playEnd()).total>0&&String.valueOf(field(replay,"failure")).isEmpty(),
                        "MMC replay counts cuts and plays through the result: "+field(replay,"failure"));
                check(selected.motion.recent(selected.durationMs,400).stream().anyMatch(c->c.reason.startsWith("S12"))
                        &&selected.motion.recent(selected.durationMs,400).stream().anyMatch(c->c.reason.contains("client sprint")),
                        "MMC capture exposes server velocity and local sprint at attack");
                double back=selected.previousTick(selected.playEnd());
                call(replay,"backTick",new Class[0]);
                check(((Number)field(replay,"time")).doubleValue()==back,"reverse tick restores the recorded playhead");
                List<ReplayRecording.Entry> matches=(List<ReplayRecording.Entry>)field(replay,"sumoMatches");
                for(int i=0;i+1<matches.size();i++)if(matches.get(i).id.equals(selected.entry.id))adjacentId=matches.get(i+1).id;
                if(adjacentId!=null){
                    Map<?,?> prepared=(Map<?,?>)field(replay,"prepared");
                    check(prepared.containsKey(adjacentId),"neighboring Sumo replay is prepared in the background");
                    switchTime=System.nanoTime();
                    call(replay,"key",new Class[]{int.class,boolean.class},org.lwjgl.input.Keyboard.KEY_DOWN,false);
                    stage=10;at=ticks;
                }else{finishOrCheckLedger();}
            }
            if(stage>=10&&stage<=15&&ticks-at>400)throw new AssertionError("Additional replay did not load");
            if(stage==10&&!(Boolean)field(replay,"loading")){
                ReplayRecording selected=(ReplayRecording)field(replay,"recording");
                check(selected.entry.id.equals(adjacentId)&&((Number)field(replay,"time")).doubleValue()==selected.openingTime(),
                        "Down opens the next match at its decisive moment");
                result.put("next_match_switch_ms",(System.nanoTime()-switchTime)/1e6);
                switchTime=System.nanoTime();
                call(replay,"key",new Class[]{int.class,boolean.class},org.lwjgl.input.Keyboard.KEY_UP,false);
                stage=11;at=ticks;
            }
            if(stage==11&&!(Boolean)field(replay,"loading")){
                ReplayRecording selected=(ReplayRecording)field(replay,"recording");
                check(selected.entry.id.equals("sumo:"+System.getProperty("practice.test.sumoMatch")),
                        "Up switches back to the cached match");
                result.put("previous_match_switch_ms",(System.nanoTime()-switchTime)/1e6);
                String cached=System.getProperty("practice.test.cachedTerrainMatch");
                if(cached==null){finishOrCheckAppearance();}
                else{
                    check((Boolean)call(replay,"watchSumo",new Class[]{String.class},cached),"cached-terrain fixture selected");
                    stage=12;at=ticks;
                }
            }
            if(stage==12&&!(Boolean)field(replay,"loading")){
                ReplayRecording selected=(ReplayRecording)field(replay,"recording");
                seek(selected.playStart());
                check(selected.entry.id.equals("sumo:"+System.getProperty("practice.test.cachedTerrainMatch"))
                        &&!selected.sumo.earlierChunks.isEmpty()&&arenaHasBlocks(mc),
                        "previously cached MMC arena is rebuilt with captured earlier chunks");
                finishOrCheckAppearance();
            }
            if(stage==13&&!(Boolean)field(replay,"loading")){
                ReplayRecording selected=(ReplayRecording)field(replay,"recording");
                check(selected.entry.id.equals("sumo:"+System.getProperty("practice.test.appearanceMatch")),
                        "opened the match from the appearance screenshot: "+field(replay,"message"));
                seek(Math.min(selected.playStart()+6500,selected.playEnd()));
                AbstractClientPlayer player=(AbstractClientPlayer)field(replay,"ownActor");
                Map<String,Object> targetPose=(Map<String,Object>)call(replay,"opponent",new Class[0]);
                AbstractClientPlayer target=(AbstractClientPlayer)mc.field_71441_e.func_73045_a(((Number)targetPose.get("id")).intValue());
                if(target==null)target=(AbstractClientPlayer)field(replay,"observedTarget");
                check("pvp-practice".equals(player.func_110306_p().func_110624_b())
                        &&mc.field_71439_g.func_110306_p().equals(player.func_110306_p())
                        &&mc.field_71439_g.func_175154_l().equals(player.func_175154_l()),"recorded local player and first-person arm share the cached skin and arm model");
                check(target!=null&&"pvp-practice".equals(target.func_110306_p().func_110624_b())
                        &&!player.func_110306_p().equals(target.func_110306_p()),"opponent uses its own recorded cached skin");
                check(mc.field_71439_g.field_71154_f==player.field_70177_z&&mc.field_71439_g.field_71155_g==player.field_70125_A
                        &&mc.field_71439_g.field_71163_h==player.field_70177_z&&mc.field_71439_g.field_71164_i==player.field_70125_A,
                        "old recording aligns hand yaw and pitch to its own camera");
                long worldTime=mc.field_71441_e.func_82737_E();
                int age=target.field_70173_aa;
                mc.field_71441_e.func_72835_b();mc.field_71441_e.func_72939_s();
                check(mc.field_71441_e.func_82737_E()==worldTime&&target.field_70173_aa==age,"paused replay holds world time and native entity updates");
                Method partial=Class.forName("practice.bridge.ObserverHooks").getMethod("replayWorldPartial",float.class);
                check((Float)partial.invoke(null,.2f)==1f&&(Float)partial.invoke(null,.8f)==1f,"paused world renders at one stable fraction");
                call(replay,"togglePause",new Class[0]);
                mc.field_71441_e.func_72835_b();
                check(mc.field_71441_e.func_82737_E()>worldTime&&(Float)partial.invoke(null,.2f)==.2f,"resuming resumes world ticks and frame interpolation");
                finishOrCheckLedger();
            }
            if(stage==14&&!(Boolean)field(replay,"loading")){
                checkLedgerSeek(System.getProperty("practice.test.ledgerPractice"));
                finishOrCheckSumoLedger();
            }
            if(stage==15&&!(Boolean)field(replay,"loading")){
                String id=System.getProperty("practice.test.ledgerSumo");
                checkLedgerSeek("sumo:"+id);
                ReplayRecording selected=(ReplayRecording)field(replay,"recording");
                double bookmarked=((Number)field(replay,"time")).doubleValue();
                seek(bookmarked>selected.playStart()?selected.playStart():selected.playEnd());
                check(((Number)field(replay,"time")).doubleValue()!=bookmarked,"moved away from the bookmark before clicking it again");
                clickLedgerBookmark(id);
                checkLedgerSeek("sumo:"+id);
                checkReturnToMmc();finish();
            }
        }catch(Throwable e){e.printStackTrace();System.err.println("[OfflineProbe] FAILED "+e);net.minecraftforge.fml.common.FMLCommonHandler.instance().exitJava(2,false);}
    }
}
