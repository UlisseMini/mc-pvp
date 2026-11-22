package practice.smoke;
import practice.api.*;
import practice.core.JsonData;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import io.netty.channel.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityOtherPlayerMP;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.network.play.server.S12PacketEntityVelocity;
import net.minecraft.network.play.server.S19PacketEntityStatus;
import net.minecraft.util.MovingObjectPosition;
import com.mojang.authlib.GameProfile;
import net.minecraft.network.play.client.*;
import net.minecraftforge.client.ClientCommandHandler;
import net.minecraftforge.fml.common.*;
import net.minecraftforge.fml.common.event.*;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

/** Isolated local connection denied all lab privileges. Never starts a duel. */
@Mod(modid="practiceremoteprobe",name="Remote recorder safety probe",version="1",clientSideOnly=true,dependencies="required-after:practicebridge")
public final class RemoteProbe {
    private Object bridge,capture,host;private volatile int ticks;
    private Channel channel;private int offStart,offEnd,onStart,onEnd;
    private final AtomicInteger movements=new AtomicInteger(),stockBrand=new AtomicInteger();
    private String statusBeforeRecord;
    private int checks;
    private final AtomicInteger custom=new AtomicInteger(),chat=new AtomicInteger(),unexpected=new AtomicInteger(),offMovement=new AtomicInteger(),onMovement=new AtomicInteger();
    private final Map<String,AtomicInteger> outbound=new ConcurrentHashMap<>();
    private Object field(Object o,String n)throws Exception{Field f=o.getClass().getDeclaredField(n);f.setAccessible(true);return f.get(o);}
    private Object call(Object o,String n,Class<?>[] types,Object...args)throws Exception{Method m=o.getClass().getDeclaredMethod(n,types);m.setAccessible(true);return m.invoke(o,args);}
    private int snapshot(){return channel.eventLoop().submit(new java.util.concurrent.Callable<Integer>(){public Integer call(){return movements.get();}}).syncUninterruptibly().getNow();}
    private void check(boolean yes,String why){if(!yes)throw new AssertionError(why);checks++;System.out.println("[RemoteProbe] PASS "+why);}
    private void command(String s){check(ClientCommandHandler.instance.func_71556_a(Minecraft.func_71410_x().field_71439_g,s)==1,"client intercepts "+s);}
    private Frame crosshair(Object overlay, Minecraft mc) throws Exception {
        return (Frame)call(overlay,"hud",new Class<?>[]{Frame.class,Minecraft.class,int.class,int.class},Frame.empty(),mc,800,600);
    }

    private void remoteCrosshair(Minecraft mc) throws Exception {
        Object overlay = field(bridge,"remoteCrosshair");
        EntityPlayerSP self = mc.field_71439_g;
        EntityOtherPlayerMP target = new EntityOtherPlayerMP(mc.field_71441_e,
            new GameProfile(UUID.fromString("00000000-0000-0000-0000-000000000011"),"CueProbe"));
        MovingObjectPosition ray = mc.field_71476_x;
        boolean sprint = self.func_70051_ag();
        double vx = self.field_70159_w, vy = self.field_70181_x, vz = self.field_70179_y;
        int targetId = 987654;
        try {
            target.func_70107_b(self.field_70165_t + 2,self.field_70163_u,self.field_70161_v);
            mc.field_71441_e.func_73027_a(targetId,target);
            mc.field_71476_x = new MovingObjectPosition(target);
            check(crosshair(overlay,mc).rectangles.isEmpty(),"public-server crosshair cues default off");
            command("/p remote crosshair reach on");
            check(crosshair(overlay,mc).rectangles.size()==8,"your reach brackets show for a close player on a remote connection");
            target.func_70107_b(self.field_70165_t + 4,self.field_70163_u,self.field_70161_v);
            check(crosshair(overlay,mc).rectangles.isEmpty(),"reach brackets disappear outside survival reach");
            target.func_70107_b(self.field_70165_t + 2,self.field_70163_u,self.field_70161_v);
            target.func_82142_c(true);
            check(crosshair(overlay,mc).rectangles.isEmpty(),"range stays hidden for an invisible player");
            target.func_82142_c(false);
            command("/p remote crosshair brake on");

            Method apply = Class.forName("practice.bridge.ObserverHooks").getMethod("apply",Object.class,boolean.class);
            Method input = Class.forName("practice.bridge.ObserverHooks").getMethod("input",String.class,boolean.class);
            self.func_70031_b(true);
            self.field_70159_w = .5;
            self.field_70181_x = .1;
            self.field_70179_y = 0;
            apply.invoke(null,new S12PacketEntityVelocity(self.func_145782_y(),.5,.1,0),true);
            input.invoke(null,"attack_click",false);
            self.field_70159_w *= .6;
            input.invoke(null,"attack_click",true);
            check(crosshair(overlay,mc).rectangles.size()==8,"unpaired velocity alone does not show a brake flash");
            apply.invoke(null,new S19PacketEntityStatus(self,(byte)2),true);
            Frame both = crosshair(overlay,mc);
            check(both.rectangles.size()==12&&both.rectangles.stream().anyMatch(r->r.color==0xFF74E6A6),
                "paired hurt plus matching sprint slowdown shows the green brake flash beside reach");
            input.invoke(null,"attack_click",false);
            self.field_70159_w *= .6;
            input.invoke(null,"attack_click",true);
            check(crosshair(overlay,mc).lines.stream().anyMatch(l->l.text.equals("×2")),
                "a second brake in the same incoming push increments the count");

            command("/p remote crosshair reach off");
            check(crosshair(overlay,mc).rectangles.size()==4,"turning reach off leaves the brake flash enabled");
            Path settings = ((Path)field(bridge,"directory")).resolve("remote-crosshair.txt");
            check(new String(Files.readAllBytes(settings),"UTF-8").trim().equals("brake"),"the independent choice is saved");
            command("/p remote crosshair on");
            String scope = System.getProperty("practice.test.forceRemote");
            try {
                System.clearProperty("practice.test.forceRemote");
                check(crosshair(overlay,mc).rectangles.isEmpty(),"remote toggles leave local lab rendering to its own overlay");
            } finally {
                if (scope != null) System.setProperty("practice.test.forceRemote",scope);
            }
            call(overlay,"replay",new Class<?>[]{boolean.class},true);
            check(crosshair(overlay,mc).rectangles.isEmpty(),"public-server cues are suppressed during replay");
            call(overlay,"replay",new Class<?>[]{boolean.class},false);
            check(crosshair(overlay,mc).rectangles.size()==8,"returning to live play drops the previous brake");
            command("/p remote crosshair off");
            check(crosshair(overlay,mc).rectangles.isEmpty(),"both cues can be disabled immediately");
        } finally {
            mc.field_71476_x = ray;
            self.func_70031_b(sprint);
            self.field_70159_w = vx;
            self.field_70181_x = vy;
            self.field_70179_y = vz;
            mc.field_71441_e.func_73028_b(targetId);
        }
    }
    @Mod.EventHandler public void init(FMLInitializationEvent e)throws Exception{
        bridge=Loader.instance().getIndexedModList().get("practicebridge").getMod();capture=field(bridge,"capture");host=field(bridge,"host");FMLCommonHandler.instance().bus().register(this);
    }
    @SubscribeEvent public void tick(TickEvent.ClientTickEvent e){
        if(e.phase!=TickEvent.Phase.END)return;Minecraft mc=Minecraft.func_71410_x();if(mc.field_71439_g==null)return;
        try{ticks++;
            if(ticks==1){
                Object manager=mc.func_147114_u().func_147298_b();Field f=null;for(Field x:manager.getClass().getDeclaredFields())if(Channel.class.isAssignableFrom(x.getType())){f=x;break;}if(f==null)throw new AssertionError("channel missing");f.setAccessible(true);channel=(Channel)f.get(manager);
                channel.pipeline().addLast("remote-safety-probe",new ChannelOutboundHandlerAdapter(){public void write(ChannelHandlerContext ctx,Object p,ChannelPromise promise)throws Exception{
                    String kind=p.getClass().getSimpleName();outbound.computeIfAbsent(kind,k->new AtomicInteger()).incrementAndGet();
                    if(p instanceof C17PacketCustomPayload){if(ticks<=5&&((C17PacketCustomPayload)p).func_149559_c().equals("MC|Brand"))stockBrand.incrementAndGet();else custom.incrementAndGet();}if(p instanceof C01PacketChatMessage)chat.incrementAndGet();
                    if(p instanceof C03PacketPlayer){movements.incrementAndGet();}
                    else if(!(p instanceof C00PacketKeepAlive)&&!(p instanceof C0FPacketConfirmTransaction)&&ticks>5)unexpected.incrementAndGet();
                    super.write(ctx,p,promise);
                }});
            }
            if(ticks==10)offStart=snapshot();if(ticks==30)offEnd=snapshot();if(ticks==50)onStart=snapshot();if(ticks==70)onEnd=snapshot();
            if(ticks==25){check(!(Boolean)field(capture,"remoteRecording"),"remote recording defaults off");check(field(capture,"log")==null,"off produces no remote capture");check(!(Boolean)call(capture,"practiceAllowed",new Class<?>[0]),"actual socket classification denies pretend-remote lab privileges");
                host.getClass().getMethod("tick",Snapshot.class).invoke(host,new Snapshot(System.currentTimeMillis(),System.nanoTime(),0,true,false,true,null,null,Collections.emptyList(),false));
                statusBeforeRecord=String.valueOf(host.getClass().getMethod("status").invoke(host));}
            if(ticks==30){command("/p remote record on");check(statusBeforeRecord.equals(String.valueOf(host.getClass().getMethod("status").invoke(host))),"remote command preserves live module instance/status");}
            if(ticks==35)check(!((Frame)field(bridge,"frame")).holdSprint,"record on preserves currently OFF ToggleSprint across settings refresh");
            if(ticks==40){command("/p metrics on");command("/p reach on");command("/p opening on");command("/p advantage on");command("/p ghost bot on");command("/p ghost me on");command("/p debug on");command("/p range both on");command("/p ping");command("/p mark local-remote-test");}
            if(ticks==75){
                check(field(capture,"log")!=null,"remote opt-in records live PLAY connection");check(((Number)field(capture,"lastServer")).longValue()==0,"no link hello/telemetry roundtrip");
                Frame frame=(Frame)host.getClass().getMethod("render",float.class,int.class,int.class).invoke(host,0f,800,600);check(frame.rectangles.isEmpty()&&frame.edgeColor==0,"remote has no range/debug/flash rectangles");
                for(Frame.Line line:frame.lines)check(line.right,"only sprint/potion HUD survives all practice toggles");
                WorldFrame world=(WorldFrame)host.getClass().getMethod("world",float.class).invoke(host,0f);check(world.lines.isEmpty()&&world.boxes.isEmpty()&&world.spheres.isEmpty(),"all remote world overlays suppressed");
                offMovement.set(offEnd-offStart);onMovement.set(onEnd-onStart);check(offMovement.get()==onMovement.get()&&onMovement.get()>10,"recording on/off preserves idle Minecraft movement packet count: "+offMovement+"/"+onMovement);
                check(custom.get()==0&&chat.get()==0&&unexpected.get()==0,"zero added remote MC packet types or command chat; only vanilla movement/keepalive/transaction");
                command("/p remote record off");
            }
            if(ticks==95)remoteCrosshair(mc);
            if(ticks==125){
                check(field(capture,"log")==null,"remote off closes capture");check(Class.forName("practice.bridge.ObserverHooks").getField("errors").getLong(null)==0,"observer hooks clean");check(custom.get()==0&&chat.get()==0&&unexpected.get()==0,"recording never sends custom, chat, or extra packet types");
                Map<String,Object> result=JsonData.map("checks",checks,"custom_payloads_after_join_probe",custom.get(),"stock_vanilla_brand_packets",stockBrand.get(),"chat_packets",chat.get(),"unexpected_packets",unexpected.get(),"idle_movement_off",offMovement.get(),"idle_movement_on",onMovement.get(),"remote_session",field(capture,"remoteSession"),"outbound",outbound.toString());
                Files.write(Paths.get(System.getProperty("practice.test.output")),JsonData.encode(result).getBytes("UTF-8"));System.out.println("[RemoteProbe] ALL CHECKS PASSED");mc.func_71400_g();
            }
        }catch(Throwable error){error.printStackTrace();System.err.println("[RemoteProbe] FAILED "+error+" counters="+offStart+","+offEnd+","+onStart+","+onEnd+" custom="+custom+" unexpected="+unexpected+" outbound="+outbound);net.minecraftforge.fml.common.FMLCommonHandler.instance().exitJava(2,false);}
    }
}
