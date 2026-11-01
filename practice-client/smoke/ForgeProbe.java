package practice.smoke;

import practice.api.Frame;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraftforge.client.ClientCommandHandler;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.*;
import net.minecraftforge.fml.common.event.*;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

@Mod(modid="practiceprobe",name="Practice smoke test",version="1",clientSideOnly=true,dependencies="required-after:practicebridge")
public final class ForgeProbe {
    private int ticks,draws; private Object bridge;
    private Frame frame()throws Exception{Field f=bridge.getClass().getDeclaredField("frame");f.setAccessible(true);return (Frame)f.get(bridge);}
    private Map<?,?> linked()throws Exception{Field f=bridge.getClass().getDeclaredField("capture");f.setAccessible(true);Object capture=f.get(bridge);
        Method m=capture.getClass().getDeclaredMethod("linkedState");m.setAccessible(true);return (Map<?,?>)m.invoke(capture);}
    private void cleanHooks()throws Exception{check(Class.forName("practice.bridge.ObserverHooks").getField("errors").getLong(null)==0,"native observer hooks report no errors");}
    private void check(boolean ok,String message){if(!ok)throw new AssertionError(message);System.out.println("[PracticeProbe] PASS "+message);}
    @Mod.EventHandler public void init(FMLInitializationEvent e){
        bridge=Loader.instance().getIndexedModList().get("practicebridge").getMod();
        FMLCommonHandler.instance().bus().register(this);MinecraftForge.EVENT_BUS.register(this);
    }
    @SubscribeEvent public void render(RenderGameOverlayEvent.Post e){if(e.type==RenderGameOverlayEvent.ElementType.ALL)draws++;}
    @SubscribeEvent public void tick(TickEvent.ClientTickEvent e){
        if(e.phase!=TickEvent.Phase.END)return;
        Minecraft mc=Minecraft.func_71410_x();if(mc.field_71439_g==null)return;
        try{
            ticks++;
            if(ticks==25){
                check(frame().holdSprint,"Forge tick drives module");
                check(frame().lines.get(0).right,"top-right HUD");
                check(draws>0,"Forge overlay event renders successfully");
                check(mc.field_71474_y.field_151444_V.func_151470_d(),"normal Sprint key held");
                KeyBinding.func_74510_a(mc.field_71474_y.field_74351_w.func_151463_i(),true);
            }
            if(ticks==35){
                check(mc.field_71439_g.func_70051_ag(),"vanilla starts sprinting with forward input");
                Field f=bridge.getClass().getDeclaredField("sprintPressed");f.setAccessible(true);f.setBoolean(bridge,true);
            }
            if(ticks==37){
                check(!frame().holdSprint,"toggle releases ordinary sprint input");
                KeyBinding.func_74510_a(mc.field_71474_y.field_74351_w.func_151463_i(),false);
                int handled=ClientCommandHandler.instance.func_71556_a(mc.field_71439_g,"/practice metrics on");
                check(handled==1,"client command handled locally");
            }
            if(ticks==40){
                check(frame().lines.stream().anyMatch(l->l.text.contains("m/s (client)")),"command updates live HUD");
                Path config=mc.field_71412_D.toPath().resolve("config/pvp-practice/settings.properties");
                Files.write(config,"sprint.label=Live Forge reload\npotions.enabled=true\n".getBytes("UTF-8"));
            }
            if(ticks==70){
                check(frame().lines.get(0).text.equals("Live Forge reload"),"automatic reload inside live Forge client");
                cleanHooks();
            }
            if(ticks==90){
                check("state".equals(linked().get("type")),"server responds through existing Minecraft connection");
                mc.field_71439_g.func_71165_d("/duel wtap boxing ping=80");
            }
            if(ticks==220){
                ClientCommandHandler.instance.func_71556_a(mc.field_71439_g,"/practice mark forge-integration");
                ClientCommandHandler.instance.func_71556_a(mc.field_71439_g,"/practice ping");
            }
            if(ticks>=220&&ticks<310){
                EntityPlayer closest=null;double distance=Double.POSITIVE_INFINITY;
                for(Object o:mc.field_71441_e.field_73010_i){EntityPlayer p=(EntityPlayer)o;
                    if(p==mc.field_71439_g||p.func_175149_v())continue;double d=p.func_70068_e(mc.field_71439_g);
                    if(d<distance){distance=d;closest=p;}}
                if(closest!=null){
                    double dx=closest.field_70165_t-mc.field_71439_g.field_70165_t,dz=closest.field_70161_v-mc.field_71439_g.field_70161_v;
                    double dy=closest.field_70163_u+1.0-mc.field_71439_g.field_70163_u-mc.field_71439_g.func_70047_e();
                    mc.field_71439_g.field_70177_z=(float)Math.toDegrees(Math.atan2(-dx,dz));
                    mc.field_71439_g.field_70125_A=(float)-Math.toDegrees(Math.atan2(dy,Math.sqrt(dx*dx+dz*dz)));
                    KeyBinding.func_74510_a(mc.field_71474_y.field_74351_w.func_151463_i(),true);
                    if(ticks%2==0)KeyBinding.func_74507_a(mc.field_71474_y.field_74312_F.func_151463_i());
                    if(ticks%10==0){KeyBinding.func_74507_a(mc.field_71474_y.field_74313_G.func_151463_i());KeyBinding.func_74510_a(mc.field_71474_y.field_74313_G.func_151463_i(),true);}
                    if(ticks%10==2)KeyBinding.func_74510_a(mc.field_71474_y.field_74313_G.func_151463_i(),false);
                }
            }
            if(ticks==310){
                Map<?,?> state=linked();
                check(state.get("match") instanceof String&&!state.get("match").equals(""),"live state identifies the current duel");
                check(state.get("server") instanceof Map&&((Map<?,?>)state.get("server")).size()==2,"authoritative state covers both participants");
                check(state.get("clients") instanceof Map&&((Map<?,?>)state.get("clients")).size()==2,"human and bot client views join the same server snapshot");
                cleanHooks();
                KeyBinding.func_74510_a(mc.field_71474_y.field_74351_w.func_151463_i(),false);
                KeyBinding.func_74510_a(mc.field_71474_y.field_74313_G.func_151463_i(),false);
                mc.field_71439_g.func_71165_d("/stop");
            }
            if(ticks==325){
                System.out.println("[PracticeProbe] ALL CHECKS PASSED");mc.func_71400_g();
            }
        }catch(Throwable t){System.out.println("[PracticeProbe] FAILED");t.printStackTrace();mc.func_71400_g();}
    }
}
