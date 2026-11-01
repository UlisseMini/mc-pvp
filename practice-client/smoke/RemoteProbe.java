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
    private int checks,forward,back,left,right;private boolean pulsed,afkDone;private int finishAt;
    private final AtomicInteger custom=new AtomicInteger(),chat=new AtomicInteger(),unexpected=new AtomicInteger(),offMovement=new AtomicInteger(),onMovement=new AtomicInteger();
    private final Map<String,AtomicInteger> outbound=new ConcurrentHashMap<>();
    private Object field(Object o,String n)throws Exception{Field f=o.getClass().getDeclaredField(n);f.setAccessible(true);return f.get(o);}
    private Object call(Object o,String n,Class<?>[] types,Object...args)throws Exception{Method m=o.getClass().getDeclaredMethod(n,types);m.setAccessible(true);return m.invoke(o,args);}
    private int snapshot(){return channel.eventLoop().submit(new java.util.concurrent.Callable<Integer>(){public Integer call(){return movements.get();}}).syncUninterruptibly().getNow();}
    private void check(boolean yes,String why){if(!yes)throw new AssertionError(why);checks++;System.out.println("[RemoteProbe] PASS "+why);}
    private void command(String s){check(ClientCommandHandler.instance.func_71556_a(Minecraft.func_71410_x().field_71439_g,s)==1,"client intercepts "+s);}
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
                command("/p antiafk on 10");
            }
            if(ticks>75&&!afkDone){
                boolean w=mc.field_71474_y.field_74351_w.func_151470_d(),s=mc.field_71474_y.field_74368_y.func_151470_d(),l=mc.field_71474_y.field_74370_x.func_151470_d(),r=mc.field_71474_y.field_74366_z.func_151470_d();
                if(w)forward++;if(s)back++;if(l)left++;if(r)right++;
                if(w||s||l||r){pulsed=true;check(!mc.field_71474_y.field_151444_V.func_151470_d(),"synthetic movement suppresses ToggleSprint");}
                if(pulsed&&!w&&!s&&!l&&!r){int tap=Math.max(forward,left);check(forward==back&&left==right&&tap>=1&&tap<=4,"random pulse uses balanced1–4tick opposite inputs");command("/p antiafk off");check(!mc.field_71474_y.field_74351_w.func_151470_d()&&!mc.field_71474_y.field_74368_y.func_151470_d()&&!mc.field_71474_y.field_74370_x.func_151470_d()&&!mc.field_71474_y.field_74366_z.func_151470_d(),"off releases owned keys");command("/p remote record off");afkDone=true;finishAt=ticks+50;}
            }
            if(ticks>430&&!afkDone)throw new AssertionError("AFK pulse deadline");
            if(finishAt>0&&ticks>=finishAt){
                check(field(capture,"log")==null,"remote off closes capture");check(Class.forName("practice.bridge.ObserverHooks").getField("errors").getLong(null)==0,"observer hooks clean");check(custom.get()==0&&chat.get()==0&&unexpected.get()==0,"recording and AFK never send custom/chat/extra packet kinds");
                Map<String,Object> result=JsonData.map("checks",checks,"custom_payloads_after_join_probe",custom.get(),"stock_vanilla_brand_packets",stockBrand.get(),"chat_packets",chat.get(),"unexpected_packets",unexpected.get(),"idle_movement_off",offMovement.get(),"idle_movement_on",onMovement.get(),"forward_ticks",forward,"back_ticks",back,"left_ticks",left,"right_ticks",right,"remote_session",field(capture,"remoteSession"),"outbound",outbound.toString());
                Files.write(Paths.get(System.getProperty("practice.test.output")),JsonData.encode(result).getBytes("UTF-8"));System.out.println("[RemoteProbe] ALL CHECKS PASSED");mc.func_71400_g();
            }
        }catch(Throwable error){error.printStackTrace();System.err.println("[RemoteProbe] FAILED "+error+" counters="+offStart+","+offEnd+","+onStart+","+onEnd+" custom="+custom+" unexpected="+unexpected+" outbound="+outbound);net.minecraftforge.fml.common.FMLCommonHandler.instance().exitJava(2,false);}
    }
}
