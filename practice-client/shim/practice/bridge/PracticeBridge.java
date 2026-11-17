package practice.bridge;

import practice.api.*;
import practice.core.ReloadHost;
import java.io.*;
import java.lang.reflect.Field;
import java.nio.file.*;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiIngame;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.client.resources.I18n;
import net.minecraft.command.*;
import net.minecraft.entity.player.EntityPlayer;
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
    private ReloadHost host;
    private PracticeCapture capture;
    private Path directory;
    private Frame frame=Frame.empty();
    private long ticks;
    private boolean sprintPressed;
    private String lastStatus="";
    private long afkManualLookAt;
    private int previousAfkKeys;
    private final practice.core.AntiAfk antiAfk=new practice.core.AntiAfk();
    private final practice.core.AntiAfk.Ownership afkKeys=new practice.core.AntiAfk.Ownership();

    @Mod.EventHandler public void preInit(FMLPreInitializationEvent event) throws IOException {
        directory=event.getModConfigurationDirectory().toPath().resolve("pvp-practice");
        Files.createDirectories(directory);
        Path pendingModule=directory.resolve("practice-module-next.jar");if(Files.isRegularFile(pendingModule))Files.move(pendingModule,directory.resolve("practice-module.jar"),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
        host=new ReloadHost(directory);
        capture=new PracticeCapture(host,directory,event.getSourceFile().toPath());host.services(capture);ObserverHooks.sink=capture;settings();host.reload(true);
        BotRuntime.start(capture,event.getModConfigurationDirectory().toPath().getParent());
        Runtime.getRuntime().addShutdownHook(new Thread(()->{if(BotRuntime.enabled())BotRuntime.instance.close();host.close();capture.close();},"practice-close"));
    }
    @Mod.EventHandler public void init(FMLInitializationEvent event) {
        FMLCommonHandler.instance().bus().register(this);
        MinecraftForge.EVENT_BUS.register(this);
        ClientCommandHandler.instance.func_71560_a(new Command());
        System.out.println("[PracticeBridge] Ready. "+host.status());
    }
    @SubscribeEvent public void key(InputEvent.KeyInputEvent event) {
        Minecraft mc=Minecraft.func_71410_x();
        // Do not log text typed into chat or menus.
        if(mc.field_71462_r==null)capture.inputEvent("key",practice.core.JsonData.map("key",Keyboard.getEventKey(),"down",Keyboard.getEventKeyState(),"repeat",Keyboard.isRepeatEvent(),"event_ns",Keyboard.getEventNanoseconds()));
        if(mc.field_71462_r==null && Keyboard.getEventKeyState() && !Keyboard.isRepeatEvent()
            && Keyboard.getEventKey()==mc.field_71474_y.field_151444_V.func_151463_i())sprintPressed=true;
        if(mc.field_71462_r==null && Keyboard.getEventKeyState() && !Keyboard.isRepeatEvent()
            && Keyboard.getEventKey()==mc.field_71474_y.field_74312_F.func_151463_i())OldBlockAnimation.clicked(mc);
    }
    @SubscribeEvent public void mouse(InputEvent.MouseInputEvent event) {
        Minecraft mc=Minecraft.func_71410_x();
        if(Mouse.getEventDX()!=0||Mouse.getEventDY()!=0||Mouse.getEventButtonState())afkManualLookAt=System.nanoTime();
        if(mc.field_71462_r==null)capture.inputEvent("mouse",practice.core.JsonData.map("button",Mouse.getEventButton(),"down",Mouse.getEventButtonState(),"dx",Mouse.getEventDX(),"dy",Mouse.getEventDY(),"wheel",Mouse.getEventDWheel(),"event_ns",Mouse.getEventNanoseconds()));
        if(mc.field_71462_r==null && Mouse.getEventButtonState()
            && Mouse.getEventButton()-100==mc.field_71474_y.field_151444_V.func_151463_i())sprintPressed=true;
        if(mc.field_71462_r==null && Mouse.getEventButtonState()
            && Mouse.getEventButton()-100==mc.field_71474_y.field_74312_F.func_151463_i())OldBlockAnimation.clicked(mc);
    }
    private static boolean physical(int code){
        if(code>0 && code<Keyboard.KEYBOARD_SIZE)return Keyboard.isKeyDown(code);
        if(code<0 && code>=-100){int b=code+100;return b<Mouse.getButtonCount() && Mouse.isButtonDown(b);}
        return false;
    }
    @SubscribeEvent public void tick(TickEvent.ClientTickEvent event) {
        if(event.phase!=TickEvent.Phase.START){
            OldSneakAnimation.tick(Minecraft.func_71410_x(),false);
            capture.tick(ticks,true);
            return;
        }
        Minecraft mc=Minecraft.func_71410_x();ticks++;
        CombatCoach.tick(mc,false);
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
        boolean manual=physical(mc.field_71474_y.field_74351_w.func_151463_i())||physical(mc.field_71474_y.field_74368_y.func_151463_i())||physical(mc.field_71474_y.field_74370_x.func_151463_i())||physical(mc.field_71474_y.field_74366_z.func_151463_i())||physical(mc.field_71474_y.field_74314_A.func_151463_i())||physical(mc.field_71474_y.field_74311_E.func_151463_i())||physical(mc.field_71474_y.field_151444_V.func_151463_i());
        long afkNow=System.nanoTime();manual|=afkNow-afkManualLookAt<150_000_000L;
        boolean wasAfk=antiAfk.enabled();int pulse=antiAfk.tick(afkNow,ConnectionScope.current(),playing,mc.field_71462_r!=null,manual);
        if(wasAfk&&!antiAfk.enabled())say("Anti-AFK off: connection changed.");
        applyAfk(mc,pulse);
        if(playing&&!manual&&mc.field_71462_r==null){
            mc.field_71439_g.field_70177_z+=(float)antiAfk.yawDelta();
            mc.field_71439_g.field_70125_A=Math.max(-90,Math.min(90,mc.field_71439_g.field_70125_A+(float)antiAfk.pitchDelta()));
        }
        if(pulse!=previousAfkKeys){capture.inputEvent("anti_afk",practice.core.JsonData.map("keys",pulse,"status",antiAfk.status()));previousAfkKeys=pulse;}
        KeyBinding sprint=mc.field_71474_y.field_151444_V;int key=sprint.func_151463_i();
        // Hold only the ordinary Sprint input. Vanilla still applies hunger, movement,
        // item-use, collision and sprint-reset rules; no setSprinting or motion writes.
        if(!BotRuntime.enabled()&&key!=0)KeyBinding.func_74510_a(key,playing&&mc.field_71462_r==null&&((!antiAfk.pulsing()&&frame.holdSprint)||physical(key)));
        String status=host.status();
        if(!status.equals(lastStatus)){System.out.println("[PracticeBridge] "+status);capture.inputEvent("module_status",practice.core.JsonData.map("status",status));lastStatus=status;}
    }
    private void applyAfk(Minecraft mc,int pulse){
        KeyBinding[] keys={mc.field_71474_y.field_74351_w,mc.field_71474_y.field_74368_y,mc.field_71474_y.field_74370_x,mc.field_71474_y.field_74366_z,mc.field_71474_y.field_74311_E};
        int human=0;for(int i=0;i<keys.length;i++)if(physical(keys[i].func_151463_i()))human|=1<<i;
        int[] changes=afkKeys.applyMask(pulse,human);
        for(int i=0;i<keys.length;i++){
            if((changes[1]&(1<<i))!=0)KeyBinding.func_74510_a(keys[i].func_151463_i(),false);
            if((changes[0]&(1<<i))!=0)KeyBinding.func_74510_a(keys[i].func_151463_i(),true);
        }
    }
    private void scope(){host.event(new PracticeEvent("connection_scope",0,System.currentTimeMillis(),System.nanoTime(),practice.core.JsonData.map("practice_allowed",capture.practiceAllowed())));}
    private static Snapshot.Player player(EntityPlayer p) {
        return new Snapshot.Player(p.func_145782_y(),p.field_70165_t,p.field_70163_u,p.field_70161_v,
            p.field_70165_t-p.field_70169_q,p.field_70163_u-p.field_70167_r,p.field_70161_v-p.field_70166_s,
            p.field_70163_u+p.func_70047_e(),p.func_110143_aJ(),p.field_70737_aN,
            p.field_70122_E,p.func_70632_aY(),p.func_70051_ag());
    }
    @SubscribeEvent public void chat(ClientChatReceivedEvent event){host.chat(event.message.func_150260_c(),event.type);}
    @SubscribeEvent public void renderTick(TickEvent.RenderTickEvent event){if(event.phase==TickEvent.Phase.END)capture.render(event.renderTickTime);else capture.replayRender();}
    @SubscribeEvent public void world(RenderWorldLastEvent event){scope();capture.viewFrame(event.partialTicks);if(capture.practiceAllowed())WorldDrawing.draw(host.world(event.partialTicks),event.partialTicks);}
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
    @SubscribeEvent public void overlay(RenderGameOverlayEvent.Post event) {
        if(event.type!=RenderGameOverlayEvent.ElementType.ALL)return;
        Minecraft mc=Minecraft.func_71410_x();
        if(mc.field_71439_g==null||mc.field_71474_y.field_74319_N)return;
        scope();
        int width=event.resolution.func_78326_a(),height=event.resolution.func_78328_b();
        Frame rendered=host.render(event.partialTicks,width,height);Frame display=rendered==null?frame:rendered;
        if(mc.field_71462_r==null)display=CombatCoach.hud(display,height);
        int shapes=0;for(Frame.Rectangle r:display.rectangles){if(++shapes>256)break;
            Gui.func_73734_a(Math.max(0,Math.min(width,r.left)),Math.max(0,Math.min(height,r.top)),Math.max(0,Math.min(width,r.right)),Math.max(0,Math.min(height,r.bottom)),r.color);}
        int count=0;
        for(Frame.Line line:display.lines){
            if(++count>40)break;
            String text=line.text==null?"":line.text.substring(0,Math.min(256,line.text.length()));
            float scale=Float.isFinite(line.scale)?Math.max(.5f,Math.min(4,line.scale)):1;
            float textWidth=mc.field_71466_p.func_78256_a(text)*scale;
            float x=line.centered?(width-textWidth)/2+line.x:line.right?width-line.x-textWidth:line.x;
            GL11.glPushMatrix();try{GL11.glScalef(scale,scale,1);
                mc.field_71466_p.func_175063_a(text,Math.max(0,Math.min(width-1,x))/scale,Math.max(0,Math.min(height-10*scale,line.y))/scale,line.color);
            }finally{GL11.glPopMatrix();}
        }
        int edge=Math.min(30,Math.max(0,display.edgeWidth));
        if((display.edgeColor>>>24)!=0 && edge>0){
            Gui.func_73734_a(0,0,width,edge,display.edgeColor);Gui.func_73734_a(0,height-edge,width,height,display.edgeColor);
            Gui.func_73734_a(0,edge,edge,height-edge,display.edgeColor);Gui.func_73734_a(width-edge,edge,width,height-edge,display.edgeColor);
        }
    }
    private void settings(){try{Properties p=new Properties();try(InputStream in=Files.newInputStream(directory.resolve("settings.properties"))){p.load(in);}capture.settings(p);CombatCoach.settings(p);OldSneakAnimation.settings(p);HurtCamera.settings(p);}catch(IOException ignored){}}
    private void say(String text){
        Minecraft mc=Minecraft.func_71410_x();if(mc.field_71439_g!=null)mc.field_71439_g.func_145747_a(new ChatComponentText("\u00a7b[Practice] \u00a7f"+text));
    }
    private void set(String key,String value) throws IOException {
        Properties props=new Properties();Path config=directory.resolve("settings.properties");
        try(InputStream in=Files.newInputStream(config)){props.load(in);}
        props.setProperty(key,value);Path tmp=Files.createTempFile(directory,"settings-",".tmp");
        try(OutputStream out=Files.newOutputStream(tmp)){props.store(out,"PvP Practice: reloads automatically");}
        Files.move(tmp,config,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
        settings();host.reload(true);
        String coach=key.equals("jumpreset.enabled")?"Jump reset trainer":key.equals("blockhit.enabled")?"Blockhit trainer":
            key.equals("oldblock.enabled")?"1.7 item-use animation":key.equals("oldsneak.enabled")?"1.7 sneak animation":
            key.equals("hurtcam.enabled")?"Damage camera tilt":null;
        if(key.equals("jumpreset.sound"))say("Jump-reset sound set to "+value+".");
        else if(key.endsWith(".hud"))say((key.startsWith("jumpreset")?"Jump reset":"Blockhit")+" HUD set to "+value+".");
        else say(coach==null?host.status():coach+(Boolean.parseBoolean(value)?" on.":" off."));
    }
    private final class Command extends CommandBase {
        public String func_71517_b(){return "practice";}
        @Override public List<String> func_71514_a(){return Collections.singletonList("p");}
        public String func_71518_a(ICommandSender sender){return "/p jumpreset/blockhit on/off or hud hotbar/center/off | jumpreset sound orb/harp/pling/levelup | oldblock/oldsneak/hurtcam on/off | remote record on/off | antiafk on [seconds|MIN-MAX]/off | opening on/off/large/small | advantage on/off | reach on/off | ghost bot/me on/off | debug on/off | reload | status | capture/link/metrics/potions/record/sprint on/off | mark <text> | ping";}
        public int func_82362_a(){return 0;}
        public boolean func_71519_b(ICommandSender sender){return true;}
        public void func_71515_b(ICommandSender sender,String[] args) {
            try {
                if(args.length==1 && args[0].equals("reload")){settings();host.reload(true);say(host.status());}
                else if(args.length==1 && args[0].equals("status")){say(antiAfk.status());say(host.status()+" | "+directory);say(capture.status());}
                else if(args.length==3&&args[0].equals("remote")&&args[1].equals("record")&&Arrays.asList("on","off").contains(args[2])){capture.remoteRecording(args[2].equals("on"));say(capture.status());}
                else if(args.length>=2&&args.length<=3&&args[0].equals("antiafk")){
                    if(args[1].equals("off")&&args.length==2){antiAfk.disable();applyAfk(Minecraft.func_71410_x(),0);}
                    else if(args[1].equals("on")){String interval=args.length==3?args[2]:"30-90";antiAfk.enable(System.nanoTime(),ConnectionScope.current(),interval);}
                    else throw new IllegalArgumentException("Use /p antiafk on [seconds or MIN-MAX] or off");say(antiAfk.status());}
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
                else if(args.length==3&&args[0].equals("ghost")&&Arrays.asList("bot","me").contains(args[1])&&Arrays.asList("on","off").contains(args[2]))set("ghost."+args[1]+".enabled",String.valueOf(args[2].equals("on")));
                else if(args.length==2&&args[0].equals("opening")&&Arrays.asList("large","small").contains(args[1]))set("opening.large",String.valueOf(args[1].equals("large")));
                else if(args.length==2 && Arrays.asList("metrics","potions","record","sprint","capture","link","opening","reach","debug","advantage","jumpreset","blockhit","oldblock","oldsneak","hurtcam").contains(args[0])
                    && Arrays.asList("on","off").contains(args[1]))set(args[0]+".enabled",String.valueOf(args[1].equals("on")));
                else if(!host.command(args))say(func_71518_a(sender));
            }catch(Exception e){say("Could not update settings: "+e.getMessage());}
        }
    }
}
