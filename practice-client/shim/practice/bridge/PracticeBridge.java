package practice.bridge;

import practice.api.*;
import practice.core.CaptureLog;
import practice.core.ReloadHost;
import practice.core.replay.MmcSumoReplay;
import java.io.*;
import java.awt.Point;
import java.lang.reflect.Field;
import java.nio.file.*;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiIngame;
import net.minecraft.client.gui.GuiIngameMenu;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.client.resources.I18n;
import net.minecraft.command.*;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.event.ClickEvent;
import net.minecraft.potion.*;
import net.minecraft.util.*;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.client.ClientCommandHandler;
import net.minecraftforge.client.event.*;
import net.minecraftforge.fml.common.*;
import net.minecraftforge.fml.common.event.*;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.*;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

/** Forge-only adapter. SRG names intentionally match the production 1.8.9 API. */
@Mod(modid="practicebridge",name="PvP Practice Bridge",version="0.4.0",
     clientSideOnly=true,acceptedMinecraftVersions="[1.8.9]",acceptableRemoteVersions="*")
public final class PracticeBridge {
    private static final Field HEART_FLASH = heartFlashField();
    private static final int LOCAL_REPLAYS_BUTTON=27481, RETURN_TO_MMC_BUTTON=27482, SUMO_COACH_BUTTON=27483;
    private static final int EDIT_HUD_BUTTON=27484;
    private ReloadHost host;
    private PracticeCapture capture;
    private OfflineReplay offline;
    private SumoCoach sumoCoach;
    private RemoteCrosshair remoteCrosshair;
    private BedWarsForecastOverlay bedWarsForecast;
    private BowAim bowAim;
    private final MmcSumoReplay sumoReplay=new MmcSumoReplay();
    private Path directory;
    private Frame frame=Frame.empty();
    private long ticks;
    private boolean sprintPressed;
    private String lastStatus="";
    private final KeystrokesHud keystrokes=new KeystrokesHud();
    boolean sprintHitHud=true;
    private HudLayout hudLayout;
    private boolean hudEditorQueued;
    private boolean sumoScreenQueued;
    private boolean bedWarsForecastScreenQueued;
    private SumoCoach.Summary queuedSumoSummary;
    private Frame.Line sprintPreview=sprintPreview(new Properties());

    @Mod.EventHandler public void preInit(FMLPreInitializationEvent event) throws IOException {
        directory=event.getModConfigurationDirectory().toPath().resolve("pvp-practice");
        Files.createDirectories(directory);
        hudLayout=new HudLayout(directory);
        Path pendingModule=directory.resolve("practice-module-next.jar");if(Files.isRegularFile(pendingModule))Files.move(pendingModule,directory.resolve("practice-module.jar"),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
        host=new ReloadHost(directory);
        capture=new PracticeCapture(host,directory,event.getSourceFile().toPath());host.services(capture);ObserverHooks.sink=capture;offline=new OfflineReplay(this,capture,directory);
        if(!"bot".equals(System.getProperty("practice.role","human")))sumoCoach=SumoCoach.hooks=new SumoCoach(this,directory);
        if(sumoCoach!=null)bedWarsForecast=new BedWarsForecastOverlay(directory);
        if(sumoCoach!=null)bowAim=BowAim.hooks=new BowAim(directory,capture);
        if(sumoCoach!=null)remoteCrosshair=RemoteCrosshair.hooks=new RemoteCrosshair(directory);
        settings();host.reload(true);
        BotRuntime.start(capture,event.getModConfigurationDirectory().toPath().getParent());
        Runtime.getRuntime().addShutdownHook(new Thread(()->{if(BotRuntime.enabled())BotRuntime.instance.close();if(bedWarsForecast!=null)bedWarsForecast.close();host.close();capture.close();},"practice-close"));
    }
    @Mod.EventHandler public void init(FMLInitializationEvent event) {
        FMLCommonHandler.instance().bus().register(this);
        MinecraftForge.EVENT_BUS.register(this);
        MinecraftForge.EVENT_BUS.register(new ServerReplayHotbar(capture));
        ClientCommandHandler.instance.func_71560_a(new Command());
        System.out.println("[PracticeBridge] Ready. "+host.status());
    }
    @SubscribeEvent public void key(InputEvent.KeyInputEvent event) {
        Minecraft mc=Minecraft.func_71410_x();
        // Do not log text typed into chat or menus.
        if(mc.field_71462_r==null&&Keyboard.getEventKeyState()&&!Keyboard.isRepeatEvent()&&Keyboard.getEventKey()==Keyboard.KEY_F8){offline.browser();return;}
        if(offline.active){
            if(mc.field_71462_r==null&&Keyboard.getEventKeyState()&&!Keyboard.isRepeatEvent()){
                int key=Keyboard.getEventKey();
                boolean shift=Keyboard.isKeyDown(Keyboard.KEY_LSHIFT)||Keyboard.isKeyDown(Keyboard.KEY_RSHIFT);
                if(!offline.openCommandChat(key)&&!offline.key(key,shift)&&key==mc.field_71474_y.field_151457_aa.func_151463_i())offline.togglePerspective();
            }
            offline.clearGameplayInputs();return;
        }
        if(mc.field_71462_r==null)capture.inputEvent("key",practice.core.JsonData.map("key",Keyboard.getEventKey(),"down",Keyboard.getEventKeyState(),"repeat",Keyboard.isRepeatEvent(),"event_ns",Keyboard.getEventNanoseconds()));
        if(mc.field_71462_r==null && Keyboard.getEventKeyState() && !Keyboard.isRepeatEvent()
            && Keyboard.getEventKey()==mc.field_71474_y.field_151444_V.func_151463_i())sprintPressed=true;
        if(mc.field_71462_r==null && Keyboard.getEventKeyState() && !Keyboard.isRepeatEvent()
            && Keyboard.getEventKey()==mc.field_71474_y.field_74312_F.func_151463_i())OldBlockAnimation.clicked(mc);
    }
    @SubscribeEvent public void replayMouse(MouseEvent event){if(offline.active&&Minecraft.func_71410_x().field_71462_r==null&&((event.button>=0&&event.button<=2)||event.dwheel!=0))event.setCanceled(true);}
    @SubscribeEvent public void mouse(InputEvent.MouseInputEvent event) {
        Minecraft mc=Minecraft.func_71410_x();
        if(!offline.active&&Mouse.getEventButtonState()&&KeystrokesHud.playing(mc))
            keystrokes.clicked(Mouse.getEventButton(),System.nanoTime());
        if(mc.field_71462_r==null)capture.inputEvent("mouse",practice.core.JsonData.map("button",Mouse.getEventButton(),"down",Mouse.getEventButtonState(),"dx",Mouse.getEventDX(),"dy",Mouse.getEventDY(),"wheel",Mouse.getEventDWheel(),"event_ns",Mouse.getEventNanoseconds()));
        if(mc.field_71462_r==null && Mouse.getEventButtonState()
            && Mouse.getEventButton()-100==mc.field_71474_y.field_151444_V.func_151463_i())sprintPressed=true;
        if(mc.field_71462_r==null && Mouse.getEventButtonState()
            && Mouse.getEventButton()-100==mc.field_71474_y.field_74312_F.func_151463_i())OldBlockAnimation.clicked(mc);
    }
    static boolean physical(int code){
        if(code>0 && code<Keyboard.KEYBOARD_SIZE)return Keyboard.isKeyDown(code);
        if(code<0 && code>=-100){int b=code+100;return b<Mouse.getButtonCount() && Mouse.isButtonDown(b);}
        return false;
    }
    @SubscribeEvent public void tick(TickEvent.ClientTickEvent event) {
        if(event.phase!=TickEvent.Phase.START){
            OldSneakAnimation.tick(Minecraft.func_71410_x(),offline.active);
            if(!offline.active){
                if(keystrokes.gameplayEnded(Minecraft.func_71410_x()))capture.inputEvent("keystrokes_reset",Collections.emptyMap());
                capture.tick(ticks,true);
                if(bedWarsForecast!=null)bedWarsForecast.tick(Minecraft.func_71410_x());
                if(bowAim!=null)bowAim.tick(Minecraft.func_71410_x(),capture.replay.active());
            }
            return;
        }
        if(hudEditorQueued){hudEditorQueued=false;editHud(Minecraft.func_71410_x().field_71462_r);}
        if(bedWarsForecastScreenQueued&&bedWarsForecast!=null){
            bedWarsForecastScreenQueued=false;
            Minecraft mc=Minecraft.func_71410_x();mc.func_147108_a(bedWarsForecast.screen(mc.field_71462_r));
        }
        if(sumoCoach!=null){
            if(sumoScreenQueued){sumoScreenQueued=false;openSumo(Minecraft.func_71410_x().field_71462_r,queuedSumoSummary);queuedSumoSummary=null;}
            sumoCoach.tick(offline.active,capture.remoteLog()!=null);
        }
        CombatCoach.tick(Minecraft.func_71410_x(),offline.active);
        if(remoteCrosshair!=null)remoteCrosshair.replay(offline.active||capture.replay.active());
        if(offline.active){offline.tick();return;}
        offline.openQueuedMatch();
        Minecraft mc=Minecraft.func_71410_x();ticks++;
        if(bowAim!=null&&!offline.active&&!capture.replay.active())bowAim.startTick(mc);
        if(ticks%20==0){settings();host.reload(false);}
        capture.tick(ticks,false);
        boolean playing=mc.field_71439_g!=null && mc.field_71441_e!=null;
        List<String> effects=new ArrayList<>();EntityPlayer target=null;
        if(playing){
            double nearest=36;
            for(Object object:mc.field_71441_e.field_73010_i){
                EntityPlayer p=(EntityPlayer)object;if(p==mc.field_71439_g || p.func_175149_v())continue;
                double dx=p.field_70165_t-mc.field_71439_g.field_70165_t,dz=p.field_70161_v-mc.field_71439_g.field_70161_v;
                double dy=p.field_70163_u-mc.field_71439_g.field_70163_u,d=dx*dx+dy*dy+dz*dz;
                if(d<nearest){nearest=d;target=p;}
            }
            // Prefer the player actually under the vanilla crosshair, when present.
            if(mc.field_71476_x!=null && mc.field_71476_x.field_72308_g instanceof EntityPlayer)
                target=(EntityPlayer)mc.field_71476_x.field_72308_g;
            List<PotionEffect> sorted=new ArrayList<>();
            for(Object p:mc.field_71439_g.func_70651_bq())sorted.add((PotionEffect)p);
            Collections.sort(sorted,Comparator.comparingInt(PotionEffect::func_76456_a));
            for(PotionEffect effect:sorted){
                int id=effect.func_76456_a();
                if(id<0||id>=Potion.field_76425_a.length||Potion.field_76425_a[id]==null)continue;
                String name=I18n.func_135052_a(Potion.field_76425_a[id].func_76393_a());
                int amplifier=effect.func_76458_c();
                if(amplifier>0)name+=" "+(amplifier+1);
                effects.add(name+"  "+Potion.func_76389_a(effect));
            }
        }
        Snapshot snap=new Snapshot(System.currentTimeMillis(),System.nanoTime(),ticks,playing,
            mc.field_71462_r!=null,sprintPressed,playing?player(mc.field_71439_g):null,target==null?null:player(target),effects,capture.practiceAllowed());
        sprintPressed=false;frame=host.tick(snap);
        KeyBinding sprint=mc.field_71474_y.field_151444_V;int key=sprint.func_151463_i();
        // Hold only the ordinary Sprint input. Vanilla still applies hunger, movement,
        // item-use, collision and sprint-reset rules; no setSprinting or motion writes.
        if(!BotRuntime.enabled()&&key!=0)KeyBinding.func_74510_a(key,playing&&mc.field_71462_r==null&&(frame.holdSprint||physical(key)));
        String status=host.status();
        if(!status.equals(lastStatus)){System.out.println("[PracticeBridge] "+status);capture.inputEvent("module_status",practice.core.JsonData.map("status",status));lastStatus=status;}
    }
    void offlineStarted(){sprintPressed=false;if(bedWarsForecast!=null)bedWarsForecast.suspend();if(bowAim!=null)bowAim.reset();}
    void offlineStopped(){sprintPressed=false;host.event(new PracticeEvent("replay_frame",0,0,0,Collections.emptyMap()));scope();}
    @SubscribeEvent public void replaySound(net.minecraftforge.client.event.sound.PlaySoundEvent event){
        if(offline.muteSound(event.name))event.result=null;
    }
    private static final int SLOW_MOTION_BUTTON=8137;
    @SubscribeEvent public void menuButtons(GuiScreenEvent.InitGuiEvent.Post e){
        if(e.gui instanceof GuiIngameMenu && TrainingTimer.configurable())
            e.buttonList.add(new GuiButton(SLOW_MOTION_BUTTON,4,28,100,20,"Slow motion"));
        if(e.gui instanceof GuiMainMenu)e.buttonList.add(new GuiButton(LOCAL_REPLAYS_BUTTON,4,4,100,20,"Local replays"));
        if(e.gui instanceof GuiIngameMenu)e.buttonList.add(new GuiButton(EDIT_HUD_BUTTON,4,4,100,20,"Edit HUD"));
        if(sumoCoach!=null&&(e.gui instanceof GuiIngameMenu||e.gui instanceof GuiMainMenu))
            e.buttonList.add(new GuiButton(SUMO_COACH_BUTTON,e.gui.field_146294_l-104,4,100,20,"Sumo Coach"));
        if(offline.active&&e.gui instanceof GuiIngameMenu){
            int x=e.gui.field_146294_l/2-100, y=e.gui.field_146295_m/4+128;
            e.buttonList.add(new GuiButton(RETURN_TO_MMC_BUTTON,x,y,"Return to MMC"));
        }
    }
    @SubscribeEvent public void menuAction(GuiScreenEvent.ActionPerformedEvent.Post e){
        if(e.gui instanceof GuiIngameMenu && e.button.field_146127_k==SLOW_MOTION_BUTTON && TrainingTimer.configurable())
            Minecraft.func_71410_x().func_147108_a(new SlowMotionScreen(e.gui));
        if(e.gui instanceof GuiMainMenu&&e.button.field_146127_k==LOCAL_REPLAYS_BUTTON)offline.browser();
        if(e.gui instanceof GuiIngameMenu&&e.button.field_146127_k==EDIT_HUD_BUTTON)editHud(e.gui);
        if(e.gui instanceof GuiIngameMenu&&e.button.field_146127_k==RETURN_TO_MMC_BUTTON)offline.returnToMmc();
        if(sumoCoach!=null&&e.button.field_146127_k==SUMO_COACH_BUTTON)openSumo(e.gui,null);
    }
    private void openSumo(GuiScreen parent,SumoCoach.Summary summary){Minecraft.func_71410_x().func_147108_a(new SumoCoachScreen(parent,sumoCoach,summary));}
    private void scope(){host.event(new PracticeEvent("connection_scope",0,System.currentTimeMillis(),System.nanoTime(),practice.core.JsonData.map("practice_allowed",offline.active||capture.practiceAllowed())));}
    private static Snapshot.Player player(EntityPlayer p) {
        return new Snapshot.Player(p.func_145782_y(),p.field_70165_t,p.field_70163_u,p.field_70161_v,
            p.field_70165_t-p.field_70169_q,p.field_70163_u-p.field_70167_r,p.field_70161_v-p.field_70166_s,
            p.field_70163_u+p.func_70047_e(),p.func_110143_aJ(),p.field_70737_aN,
            p.field_70122_E,p.func_70632_aY(),p.func_70051_ag());
    }
    @SubscribeEvent public void chat(ClientChatReceivedEvent event){
        String text=event.message.func_150260_c();
        host.chat(text,event.type);
        if(event.type==2||offline.active)return;
        if(bedWarsForecast!=null)bedWarsForecast.chat(text);
        if(bowAim!=null)bowAim.chat(Minecraft.func_71410_x(),text);
        if(sumoCoach!=null)sumoCoach.labResultChat(text);
        Minecraft mc=Minecraft.func_71410_x();
        net.minecraft.client.multiplayer.ServerData server=mc.func_147104_D();
        CaptureLog writer=capture.remoteLog();
        if(writer==null||server==null||mc.field_71439_g==null||!MmcSumoReplay.server(server.field_78845_b)){
            sumoReplay.reset();
            return;
        }
        MmcSumoReplay.Match match=sumoReplay.chat(text,mc.field_71439_g.func_70005_c_(),System.currentTimeMillis(),
                writer.path.getFileName().toString(),writer.currentPart());
        if(sumoCoach!=null)sumoCoach.mmc(sumoReplay,match);
        if(match==null)return;
        try{
            match.save(directory.resolve("sumo-replays"));
            ChatComponentText message=new ChatComponentText("§b[Practice] §fSumo vs "+match.opponent+"  ");
            message.func_150257_a(sumoLink(match.id));
            SumoCoach.Summary coached=sumoCoach!=null&&sumoCoach.enabled(SumoCoach.Option.SUMMARY)?sumoCoach.find(match.id):null;
            if(coached!=null) message.func_150257_a(sumoStatsLink(match.id));
            mc.field_71456_v.func_146158_b().func_146227_a(message);
            if(coached!=null&&coached.headline!=null&&!coached.headline.isEmpty())
                mc.field_71456_v.func_146158_b().func_146227_a(new ChatComponentText("§7  ↳ §f"+coached.headline));
        }catch(IOException failure){
            System.out.println("[PracticeReplay] Could not index MMC Sumo replay: "+failure.getMessage());
        }
    }
    private IChatComponent sumoLink(String id){
        return new ChatComponentText("[Watch replay]").func_150255_a(new ChatStyle().func_150238_a(EnumChatFormatting.AQUA)
                .func_150241_a(new ClickEvent(ClickEvent.Action.RUN_COMMAND,"/p watchsumo "+id)));
    }
    private IChatComponent sumoStatsLink(String id){
        return new ChatComponentText("  [Match analysis]").func_150255_a(new ChatStyle().func_150238_a(EnumChatFormatting.AQUA)
                .func_150241_a(new ClickEvent(ClickEvent.Action.RUN_COMMAND,"/p sumoanalysis "+id)));
    }
    void announceSumo(SumoCoach.Summary summary){
        ChatComponentText line=new ChatComponentText("§b[Practice] §fSumo vs "+summary.opponent+" · "+summary.result);
        line.func_150257_a(sumoStatsLink(summary.id));
        Minecraft.func_71410_x().field_71456_v.func_146158_b().func_146227_a(line);
        if(summary.headline!=null&&!summary.headline.isEmpty())
            Minecraft.func_71410_x().field_71456_v.func_146158_b().func_146227_a(new ChatComponentText("§7  ↳ §f"+summary.headline));
    }
    @SubscribeEvent public void renderTick(TickEvent.RenderTickEvent event){if(event.phase==TickEvent.Phase.END)capture.render(event.renderTickTime);else if(offline.active){offline.renderPose(event.renderTickTime);host.event(new PracticeEvent("replay_frame",0,System.currentTimeMillis(),System.nanoTime(),offline.replayFrame()));}else capture.replayRender();}
    @SubscribeEvent public void world(RenderWorldLastEvent event){
        scope();capture.viewFrame(event.partialTicks);
        if(offline.active||capture.practiceAllowed())WorldDrawing.draw(host.world(event.partialTicks),event.partialTicks);
        if(!offline.active&&bedWarsForecast!=null)bedWarsForecast.draw(Minecraft.func_71410_x(),event.partialTicks);
        if(!offline.active&&!capture.replay.active()&&bowAim!=null)bowAim.draw(Minecraft.func_71410_x(),event.partialTicks);
    }
    private static Field heartFlashField() {
        for(String name:new String[]{"field_175191_F","healthUpdateCounter"}) {
            try {
                Field field=GuiIngame.class.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch(NoSuchFieldException ignored) {}
        }
        return null;
    }
    @SubscribeEvent public void hearts(RenderGameOverlayEvent.Pre event) throws IllegalAccessException {
        if(event.type==RenderGameOverlayEvent.ElementType.HEALTH && HEART_FLASH!=null)
            HEART_FLASH.setLong(Minecraft.func_71410_x().field_71456_v,0);
    }
    @SubscribeEvent public void replayCrosshair(RenderGameOverlayEvent.Pre event){
        if(event.type!=RenderGameOverlayEvent.ElementType.CROSSHAIRS||!offline.active||!offline.recoilFlash()
                ||Minecraft.func_71410_x().field_71462_r!=null)return;
        event.setCanceled(true);
        int x=event.resolution.func_78326_a()/2,y=event.resolution.func_78328_b()/2;
        Gui.func_73734_a(x-5,y-1,x+6,y+2,0xFF102619);
        Gui.func_73734_a(x-1,y-5,x+2,y+6,0xFF102619);
        Gui.func_73734_a(x-4,y,x+5,y+1,0xFF6CFF9A);
        Gui.func_73734_a(x,y-4,x+1,y+5,0xFF6CFF9A);
    }
    @SubscribeEvent public void overlay(RenderGameOverlayEvent.Post event) {
        if(event.type!=RenderGameOverlayEvent.ElementType.ALL)return;
        Minecraft mc=Minecraft.func_71410_x();
        if(mc.field_71462_r instanceof HudEditor||offline.active&&mc.field_71462_r instanceof OfflineReplayMenu)return;
        if(mc.field_71439_g==null||mc.field_71474_y.field_74319_N)return;
        scope();
        int width=event.resolution.func_78326_a(),height=event.resolution.func_78328_b();
        if(!offline.active&&bedWarsForecast!=null)bedWarsForecast.hud(mc,width,height);
        if(!offline.active&&!capture.replay.active()&&bowAim!=null)bowAim.hud(mc,width,height);
        Frame rendered=host.render(event.partialTicks,width,height);Frame display=rendered==null?frame:rendered;if(offline.active)display=offline.hud(display,width,height);
        if(!offline.active&&mc.field_71462_r==null)display=CombatCoach.hud(display,height);
        if(sumoCoach!=null&&mc.field_71462_r==null){
            if(offline.active)display=sumoCoach.replayFeedback(display,width,height,offline.coachFeedback());
            else if(capture.replay.active())display=sumoCoach.replayFeedback(display,width,height,
                    practice.replay.PoseTrack.object(capture.replayFrame().get("coach_feedback")));
            else display=sumoCoach.hud(display,width,height);
        }
        if(remoteCrosshair!=null&&!offline.active&&!capture.replay.active())
            display=remoteCrosshair.hud(display,mc,width,height);
        int shapes=0;for(Frame.Rectangle r:display.rectangles){if(++shapes>256)break;
            Gui.func_73734_a(Math.max(0,Math.min(width,r.left)),Math.max(0,Math.min(height,r.top)),Math.max(0,Math.min(width,r.right)),Math.max(0,Math.min(height,r.bottom)),r.color);}
        int count=0;
        for(Frame.Line line:display.lines){
            if(++count>40)break;
            String text=line.text==null?"":line.text.substring(0,Math.min(256,line.text.length()));
            float scale=Float.isFinite(line.scale)?Math.max(.5f,Math.min(4,line.scale)):1;
            float textWidth=mc.field_71466_p.func_78256_a(text)*scale;
            float x=line.centered?(width-textWidth)/2+line.x:line.right?width-line.x-textWidth:line.x;
            int y=line.y;
            if(line.hudId!=null){
                Point position=hudLayout.position(line.hudId,Math.round(x),y,width-(int)Math.ceil(textWidth),height-(int)Math.ceil(10*scale));
                x=position.x;y=position.y;
            }
            GL11.glPushMatrix();try{GL11.glScalef(scale,scale,1);
                mc.field_71466_p.func_175063_a(text,Math.max(0,Math.min(width-1,x))/scale,Math.max(0,Math.min(height-10*scale,y))/scale,line.color);
            }finally{GL11.glPopMatrix();}
        }
        int edge=Math.min(30,Math.max(0,display.edgeWidth));
        if((display.edgeColor>>>24)!=0 && edge>0){
            Gui.func_73734_a(0,0,width,edge,display.edgeColor);Gui.func_73734_a(0,height-edge,width,height,display.edgeColor);
            Gui.func_73734_a(0,edge,edge,height-edge,display.edgeColor);Gui.func_73734_a(width-edge,edge,width,height-edge,display.edgeColor);
        }
        if(sprintHitHud&&mc.field_71462_r==null){
            Boolean bonus=null;
            if(offline.active){
                bonus=offline.sprintHit();
            }else if(capture.replay.active()){
                Map<String,Object> replay=capture.replayFrame();
                Object cues=replay.get("sprint_hits");
                double time=practice.replay.PoseTrack.number(replay,"time_ms",-1);
                if(cues instanceof List)for(Object cue:(List<?>)cues){
                    Map<String,Object> hit=practice.replay.PoseTrack.object(cue);
                    double age=time-practice.replay.PoseTrack.number(hit,"at",Double.NEGATIVE_INFINITY);
                    if(age>=0&&age<practice.replay.SprintHit.DISPLAY_MS&&hit.get("sprint") instanceof Boolean)bonus=(Boolean)hit.get("sprint");
                }
            }else bonus=capture.liveSprintHit();
            if(bonus!=null){
                String label=bonus?"Sprint KB":"Base KB";
                mc.field_71466_p.func_175063_a(label,(width-mc.field_71466_p.func_78256_a(label))/2f,height/2+13,bonus?0xFF82F2AA:0xFFFFCA79);
            }
        }
        drawServerSprint(mc, width, height);
        if(offline.active)offline.drawKeystrokes(mc.field_71466_p,width,height,false);
        else if(keystrokes.enabled&&KeystrokesHud.playing(mc)){
            Map<String,Object> replay=capture.replayFrame();
            Object source=replay.get("keystrokes_source");
            if("a".equals(source)||"b".equals(source))KeystrokesHud.drawServerReplay(mc.field_71466_p,replay,width,height,hudLayout);
            else if(replay.isEmpty()||"live".equals(source))KeystrokesHud.drawAtLayout(mc.field_71466_p,keystrokes.live(System.nanoTime()),width,height,hudLayout);
        }
    }
    private void drawServerSprint(Minecraft mc, int width, int height) {
        ServerSprintCue cue = capture.serverSprint;
        if (sumoCoach == null || mc.field_71462_r != null) { cue.clearReplay(); return; }
        if (offline.active && offline.sprintReplayVisible()) {
            double time = offline.playhead();
            practice.replay.SprintTransitions.Sample transitions = offline.serverSprintAt(time);
            cue.replay("offline", offline.sprintReplayRevision, time, offline.sprintReplayPlaying(), transitions, sumoCoach);
            cue.drawReplay(mc.field_71466_p, width, height, time, transitions);
        } else if (capture.replay.active() && !offline.active) {
            Map<String,Object> replay = capture.replayFrame();
            double time = practice.replay.PoseTrack.number(replay, "time_ms", -1);
            long revision = (long)practice.replay.PoseTrack.number(replay, "playback_revision", 0);
            String subject = replay.get("match") + ":" + ReplayPresentation.sprintSubject(replay);
            practice.replay.SprintTransitions.Sample transitions = capture.replay.serverSprintAt(replay, time);
            // Older servers supply the playhead but cannot distinguish a seek from playback.
            boolean playing = replay.get("playback_revision") instanceof Number && !Boolean.TRUE.equals(replay.get("paused"));
            cue.replay(subject, revision, time, playing, transitions, sumoCoach);
            cue.drawReplay(mc.field_71466_p, width, height, time, transitions);
        } else {
            cue.clearReplay();
            if (!offline.active) capture.drawServerSprint(mc, width, height);
        }
    }
    void recordCoachCue(String cue,int cuts,boolean first){capture.inputEvent("sumo_coach_cue",practice.core.JsonData.map("cue",cue,"cuts",cuts,"first",first));}
    HudLayout hudLayout(){return hudLayout;}
    void editHud(GuiScreen parent){Minecraft.func_71410_x().func_147108_a(new HudEditor(hudLayout,sprintPreview,parent,this));}
    private static Frame.Line sprintPreview(Properties settings){
        String label=settings.getProperty("sprint.label","[Sprinting (Toggled)]");
        int inset=6,top=6,color=0x55FFFF;
        try{inset=Integer.parseInt(settings.getProperty("hud.rightInset","6"));
            top=Integer.parseInt(settings.getProperty("hud.top","6"));
            color=Integer.parseInt(settings.getProperty("hud.color","55FFFF"),16);
        }catch(NumberFormatException ignored){}
        return new Frame.Line(label,inset,top,color,true,1,false,"sprint");
    }
    private void settings(){try{Properties p=new Properties();try(InputStream in=Files.newInputStream(directory.resolve("settings.properties"))){p.load(in);}capture.settings(p);CombatCoach.settings(p);if(sumoCoach!=null)sumoCoach.settings(p);OldSneakAnimation.settings(p);HurtCamera.settings(p);MovementFov.settings(p);offline.settings(p);keystrokes.enabled=Boolean.parseBoolean(p.getProperty("keystrokes.enabled","true"));sprintHitHud=Boolean.parseBoolean(p.getProperty("sprintkb.enabled","true"));sprintPreview=sprintPreview(p);}catch(IOException ignored){}}
    private void say(String text){
        Minecraft mc=Minecraft.func_71410_x();if(mc.field_71439_g!=null)mc.field_71439_g.func_145747_a(new ChatComponentText("\u00a7b[Practice] \u00a7f"+text));
    }
    void storeSetting(String key,String value) throws IOException {
        Properties props=new Properties();Path config=directory.resolve("settings.properties");
        if(Files.exists(config))try(InputStream in=Files.newInputStream(config)){props.load(in);}
        props.setProperty(key,value);Path tmp=Files.createTempFile(directory,"settings-",".tmp");
        try{
            try(OutputStream out=Files.newOutputStream(tmp)){props.store(out,"PvP Practice: reloads automatically");}
            Files.move(tmp,config,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
        }finally{Files.deleteIfExists(tmp);}
        settings();host.reload(true);
    }
    ServerSprintCue.Mode serverSprintMode(boolean gain) { return capture.serverSprint.mode(gain); }
    void setServerSprintMode(boolean gain, ServerSprintCue.Mode mode) throws IOException {
        storeSetting(ServerSprintCue.key(gain), mode.setting());
    }
    private void set(String key,String value) throws IOException {
        storeSetting(key,value);
        String coach=key.equals("jumpreset.enabled")?"Jump reset trainer":key.equals("blockhit.enabled")?"Blockhit trainer":
            key.equals("oldblock.enabled")?"1.7 item-use animation":key.equals("oldsneak.enabled")?"1.7 sneak animation":
            key.equals("hurtcam.enabled")?"Damage camera tilt":key.equals("dynamicfov.enabled")?"Movement FOV":key.equals("keystrokes.enabled")?"Keystrokes":key.equals("sprintkb.enabled")?"Sprint KB cue":
            key.equals("reach.enabled")?"Reach fields":key.equals("range.crosshair.enabled")?"In-range crosshair":null;
        if(key.equals("jumpreset.sound"))say("Jump-reset sound set to "+value+".");
        else if(key.endsWith(".hud"))say((key.startsWith("jumpreset")?"Jump reset":"Blockhit")+" HUD set to "+value+".");
        else say(coach==null?host.status():coach+(Boolean.parseBoolean(value)?" on.":" off."));
    }
    private final class Command extends CommandBase {
        private final List<String> helpTopics=Arrays.asList("replay","hud","range","range-style","training","recording","forecast","bowaim","settings");
        public String func_71517_b(){return "practice";}
        @Override public List<String> func_71514_a(){return Collections.singletonList("p");}
        public String func_71518_a(ICommandSender sender){return "/p help";}
        public int func_82362_a(){return 0;}
        public boolean func_71519_b(ICommandSender sender){return true;}
        @Override public List<String> func_180525_a(ICommandSender sender,String[] args,BlockPos pos){
            if(args.length==1)return func_175762_a(args,Arrays.asList("help","replays","ledger","hud","sumocoach","sumoanalysis","keystrokes","sprint","sprintkb","serversprint","potions","reach","range","crosshair","opening","advantage","ghost","jumpreset","blockhit","oldblock","oldsneak","hurtcam","dynamicfov","remote","capture","record","mark","forecast","bowaim","status","reload","metrics","link","debug","ping"));
            if(args.length==2&&args[0].equals("help"))return func_175762_a(args,helpTopics);
            if(args[0].equals("serversprint")){
                if(args.length==2)return func_175762_a(args,Arrays.asList("gain","loss"));
                if(args.length==3)return func_175762_a(args,Arrays.asList("off","flash","sound","both"));
            }
            if(bowAim!=null&&args[0].equals("bowaim")){
                if(args.length==2)return func_175762_a(args,Arrays.asList("on","legacy","linear","off","status","preview","trajectory","intercept","ghosts","throwables","bridgehack","limit","chance","autorelease","release","threshold","ping"));
                if(args.length==3&&args[1].equals("trajectory"))return func_175762_a(args,Arrays.asList("on","off","auto"));
                if(args.length==3&&args[1].equals("release"))return func_175762_a(args,Arrays.asList("ev","smart","threshold"));
                if(args.length==3&&args[1].equals("ping"))return func_175762_a(args,Arrays.asList("auto","0","40","80","120"));
                if(args.length==3&&Arrays.asList("intercept","ghosts","throwables","bridgehack","chance","autorelease").contains(args[1]))
                    return func_175762_a(args,Arrays.asList("on","off"));
            }
            if(args.length>=2&&args[0].equals("remote")){
                if(args.length==2)return func_175762_a(args,Arrays.asList("record","crosshair"));
                if(args[1].equals("crosshair")){
                    if(args.length==3)return func_175762_a(args,Arrays.asList("on","off","brake","reach"));
                    if(args.length==4)return func_175762_a(args,Arrays.asList("on","off"));
                }
            }
            if(bedWarsForecast!=null&&args.length>=2&&args[0].equals("forecast"))
                return func_175762_a(args,bedWarsForecast.completeCommand(Minecraft.func_71410_x(),args));
            return Collections.emptyList();
        }
        private void helpLine(String syntax,String description,String command,ClickEvent.Action action){
            Minecraft mc=Minecraft.func_71410_x();
            if(mc.field_71439_g==null)return;
            IChatComponent row=new ChatComponentText(syntax).func_150255_a(new ChatStyle().func_150238_a(EnumChatFormatting.AQUA)
                    .func_150241_a(new ClickEvent(action,command)));
            row.func_150257_a(new ChatComponentText("  "+description).func_150255_a(new ChatStyle().func_150238_a(EnumChatFormatting.GRAY)));
            mc.field_71439_g.func_145747_a(row);
        }
        private void usage(String command,String options,String description){
            String full="/p "+command;
            helpLine(full+options,description,full+(options.isEmpty()?"":" "),ClickEvent.Action.SUGGEST_COMMAND);
        }
        private void topic(String name,String description){
            String command="/p help "+name;
            helpLine(command,description,command,ClickEvent.Action.RUN_COMMAND);
        }
        private void serverSprint(String[] args) throws IOException {
            if (args.length == 3 && (args[1].equals("gain") || args[1].equals("loss"))) {
                ServerSprintCue.Mode mode = ServerSprintCue.Mode.parse(args[2]);
                if (mode != null) {
                    setServerSprintMode(args[1].equals("gain"), mode);
                    say("Server sprint " + args[1] + " cue set to " + mode.setting() + ".");
                    return;
                }
            }
            say("Server sprint gain: "+serverSprintMode(true).setting()
                    +" · loss: "+serverSprintMode(false).setting());
            usage("serversprint", " <gain|loss> <off|flash|sound|both>", "Configure the cue for each server sprint change");
        }
        private void help(String topic){
            if(!helpTopics.contains(topic)){
                if(!topic.isEmpty())say("Unknown help topic. Pick a category below.");
                say("Commands · Click a category");
                topic("replay","Open and control replays");
                topic("hud","Move and show HUD elements");
                topic("range","Reach fields and range indicators");
                topic("training","Combat trainers and animations");
                topic("recording","Record matches and mark moments");
                topic("forecast","BedWars forecast overlay");
                topic("bowaim","Adaptive bow aim");
                topic("settings","Status, metrics, and connection tools");
                return;
            }
            say("Help · "+topic);
            switch(topic){
                case "replay":
                    usage("replays","","Open saved matches");
                    usage("ledger"," <match> [page]","Open the generated trade bookmarks");
                    say("F8 controls · Space plays or pauses");
                    say("Hold ←/→ to scrub · ↑/↓ switches matches");
                    say(", / . steps a tick · [ / ] changes speed · O hides text");
                    break;
                case "hud":
                    usage("hud","","Drag HUD elements");
                    usage("keystrokes"," on/off","Show keys and CPS live and in server replays");
                    usage("sprint"," on/off","Enable ToggleSprint");
                    usage("serversprint","","Configure server sprint gain and loss cues");
                    usage("potions"," on/off","Show potion timers");
                    say("F8 → Display adjusts overlays per replay view.");
                    break;
                case "range":
                    usage("reach"," on/off","Show reach fields");
                    usage("crosshair"," on/off","Show reach brackets in the lab and replays");
                    usage("remote crosshair"," on/off","Toggle public-server brake and reach cues");
                    usage("remote crosshair brake"," on/off","Toggle the public-server KB brake flash");
                    usage("remote crosshair reach"," on/off","Toggle your public-server reach brackets");
                    usage("sprintkb"," on/off","Show sprint or base knockback on fresh lab hits");
                    usage("range yours"," on/off","Show your reach indicator");
                    usage("range theirs"," on/off","Show opponent reach indicator");
                    usage("range both off","","Hide both range indicators");
                    topic("range-style","Change styles, colors and opacity");
                    break;
                case "range-style":
                    usage("range yours crosshair","","Use crosshair style");
                    usage("range yours edge","","Use edge style");
                    usage("range yours screen","","Use screen style");
                    usage("range yours color"," FF5555","Set RGB color");
                    usage("range yours opacity"," 0–100/auto","Set opacity");
                    usage("range yours pulse"," on/off","Flash when entering reach");
                    say("Use yours, theirs or both to choose the indicators.");
                    break;
                case "training":
                    usage("jumpreset"," on/off","Jump reset trainer");
                    usage("sumocoach","","Configure Sumo Coach");
                    usage("sumoanalysis","","Open the last Sumo analysis");
                    usage("blockhit"," on/off","Blockhit trainer");
                    usage("jumpreset hud"," hotbar/center/off","Move the prompt");
                    usage("blockhit hud"," hotbar/center/off","Move the prompt");
                    usage("jumpreset sound"," orb/harp/pling/levelup","Set sound");
                    usage("oldblock"," on/off","1.7 item-use animation");
                    usage("oldsneak"," on/off","1.7 sneak animation");
                    usage("hurtcam"," on/off","Damage camera tilt");
                    usage("dynamicfov"," on/off","Sprint and speed FOV changes");
                    break;
                case "recording":
                    usage("remote record"," on/off","Record on remote servers");
                    usage("capture"," on/off","Capture details in the lab");
                    usage("record"," on/off","Save observations to CSV");
                    usage("mark"," <text>","Mark a moment");
                    break;
                case "bowaim":
                    usage("bowaim"," on/legacy/linear/off","Choose neural v3 EV+void aim, legacy adaptive, straight-line, or off");
                    usage("bowaim preview","","Show only the projectile trajectory");
                    usage("bowaim trajectory"," on/off/auto","Show the flight path with or without assist");
                    usage("bowaim intercept"," on/off","Show the predicted hit position and time");
                    usage("bowaim ghosts"," on/off","Show multi-mode landing ghost players when locked on");
                    usage("bowaim throwables"," on/off","Enable snowball & egg aim assist with airborne volley coverage");
                    usage("bowaim bridgehack"," on/off","Optional explicit kinematic bridge continuation override (default off)");
                    usage("bowaim chance"," on/off","Show Hit-Equivalent EV & wait cue over the crosshair");
                    usage("bowaim autorelease"," on/off","Auto-release bow at full draw using the active release policy");
                    usage("bowaim release"," ev/smart/threshold","Learned V_wait EV timing, smart peak-spike, or fixed threshold");
                    usage("bowaim threshold"," <1–100>","Set the auto-release layup/cutoff percentage");
                    usage("bowaim ping"," auto/<0–250>","Own-ping RTT lead shift (auto uses live packet RTT)");
                    usage("bowaim limit"," <0–8>","Set the maximum correction in degrees");
                    usage("bowaim status","","Show current bow aim mode");
                    break;
                case "forecast":
                    usage("forecast","","Open the BedWars forecast controls");
                    usage("forecast"," on/off","Toggle the live overlay");
                    usage("forecast horizons"," 5,10,30,60,120","Show one or more look-ahead times");
                    usage("forecast display"," heatmap/ghosts/both","Choose how predictions appear in the world");
                    usage("forecast projection"," sum-y/3d","Choose a terrain heatmap or height bands");
                    usage("forecast threshold"," <0–100>","Set the minimum hotspot percentage for a ghost");
                    usage("forecast ghost-limit"," <1–8>","Limit the number of ghost markers");
                    usage("forecast models","","List model IDs");
                    usage("forecast model"," <id>","Choose a prediction model");
                    usage("forecast players"," <names>","Select players with comma-separated names");
                    usage("forecast teams"," <colors>","Select teams with comma-separated colors");
                    usage("forecast attach"," <elapsed seconds>","Set the match clock after joining late");
                    usage("forecast reload","","Reload the model bundle");
                    break;
                case "settings":
                    usage("status","","Show client and recording status");
                    usage("reload","","Reload settings");
                    usage("metrics"," on/off","Show movement metrics");
                    usage("opening"," on/off","Show opening lead");
                    usage("opening"," large/small","Resize opening lead");
                    usage("advantage"," on/off","Show reach advantage");
                    usage("ghost bot"," on/off","Show the bot silhouette");
                    usage("ghost me"," on/off","Show your silhouette");
                    usage("link"," on/off","Lab coordination");
                    usage("debug"," on/off","Show timing diagnostics");
                    usage("ping","","Record a lab clock probe");
                    break;
            }
        }
        public void func_71515_b(ICommandSender sender,String[] args) {
            try {
                if(args.length==0||args[0].equals("help")){help(args.length>=2?args[1].toLowerCase(Locale.ROOT):"");}
                else if(args.length==1&&args[0].equals("hud")){hudEditorQueued=true;}
                else if(args[0].equals("forecast")&&bedWarsForecast!=null){
                    if(args.length==1)bedWarsForecastScreenQueued=true;
                    else say(bedWarsForecast.command(Minecraft.func_71410_x(),args));
                }
                else if(args[0].equals("bowaim")&&bowAim!=null){
                    say(bowAim.command(args.length==1?"status":String.join(" ",Arrays.copyOfRange(args,1,args.length))));
                }
                else if(sumoCoach!=null&&args.length==1&&args[0].equals("sumocoach")){queuedSumoSummary=null;sumoScreenQueued=true;}
                else if(sumoCoach!=null&&args.length<=2&&(args[0].equals("sumostats")||args[0].equals("sumoanalysis"))){
                    queuedSumoSummary=args.length==1?sumoCoach.last():sumoCoach.find(args[1]);
                    if(queuedSumoSummary==null)say("No completed Sumo match found.");
                    else sumoScreenQueued=true;
                }
                else if(args.length==1&&args[0].equals("replays")){offline.browser();}
                else if(args.length>=1&&args.length<=3&&args[0].equals("ledger")){
                    String id=args.length>=2?args[1]:offline.entry==null?"":offline.entry.id.replaceFirst("^sumo:","");
                    LedgerLinks.show(directory,offline.gson,id,args.length==3?Integer.parseInt(args[2]):null);
                }
                else if(args.length==3&&args[0].equals("replayat")){
                    if(!LedgerLinks.validId(args[1])||!args[2].matches("[0-9]{13}"))
                        throw new IllegalArgumentException("Use a replay seek command from a ledger");
                    if(!offline.watchAt(args[1],Long.parseLong(args[2])))say("This replay recording is unavailable.");
                }
                else if(args.length==2&&args[0].equals("watchsumo")){
                    if(!offline.watchSumo(args[1]))say("This Sumo recording is unavailable.");
                }
                else if(args.length==1 && args[0].equals("reload")){settings();host.reload(true);say(host.status());}
                else if(args.length==1 && args[0].equals("status")){say(host.status()+" | "+directory);say(capture.status());}
                else if(args.length==3&&args[0].equals("remote")&&args[1].equals("record")&&Arrays.asList("on","off").contains(args[2])){capture.remoteRecording(args[2].equals("on"));say(capture.status());}
                else if(args.length>=2&&args[0].equals("remote")&&args[1].equals("crosshair")&&remoteCrosshair!=null){
                    if(args.length==2){
                        say(remoteCrosshair.status());
                        usage("remote crosshair"," on/off","Both public-server cues");
                        usage("remote crosshair brake"," on/off","KB brake flash");
                        usage("remote crosshair reach"," on/off","Your reach brackets");
                    }else if((args.length==3||args.length==4)&&Arrays.asList("on","off").contains(args[args.length-1])){
                        remoteCrosshair.set(args.length==3?"both":args[2],args[args.length-1].equals("on"));
                        say(remoteCrosshair.status());
                    }else throw new IllegalArgumentException("Use /p remote crosshair [brake|reach] on/off.");
                }
                else if(args.length>=2 && args[0].equals("mark")){capture.marker(String.join(" ",Arrays.copyOfRange(args,1,args.length)));say("Marker recorded.");}
                else if(args.length==1 && args[0].equals("ping")){say(capture.send("ping","manual")?"Clock probe sent.":"No local practice connection.");}
                else if(args.length==3 && Arrays.asList("jumpreset","blockhit").contains(args[0]) && args[1].equals("hud")){
                    if(!Arrays.asList("hotbar","center","off").contains(args[2]))throw new IllegalArgumentException("Use hud hotbar, center or off");
                    set(args[0]+".hud",args[2]);
                }
                else if(args.length==3 && args[0].equals("jumpreset") && args[1].equals("sound")){
                    if(!Arrays.asList("orb","harp","pling","levelup").contains(args[2]))throw new IllegalArgumentException("Use sound orb, harp, pling or levelup");
                    set("jumpreset.sound",args[2]);
                }
                else if(args[0].equals("serversprint"))serverSprint(args);
                else if(args.length==3&&args[0].equals("ghost")&&Arrays.asList("bot","me").contains(args[1])&&Arrays.asList("on","off").contains(args[2]))set("ghost."+args[1]+".enabled",String.valueOf(args[2].equals("on")));
                else if(args.length==2&&args[0].equals("opening")&&Arrays.asList("large","small").contains(args[1]))set("opening.large",String.valueOf(args[1].equals("large")));
                else if(args.length==2&&args[0].equals("crosshair")&&Arrays.asList("on","off").contains(args[1]))
                    set("range.crosshair.enabled",String.valueOf(args[1].equals("on")));
                else if(args.length==2 && Arrays.asList("metrics","potions","record","sprint","capture","link","opening","reach","debug","advantage","jumpreset","blockhit","oldblock","oldsneak","hurtcam","dynamicfov","keystrokes","sprintkb").contains(args[0])
                    && Arrays.asList("on","off").contains(args[1]))set(args[0]+".enabled",String.valueOf(args[1].equals("on")));
                else if(!host.command(args))say("Unknown command or options. Use /p help.");
            }catch(Exception e){say("Could not update settings: "+e.getMessage());}
        }
    }
}
