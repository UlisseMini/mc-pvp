package practice.smoke;

import practice.api.Frame;
import practice.api.WorldFrame;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraftforge.client.ClientCommandHandler;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.*;
import net.minecraftforge.fml.common.event.*;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

@Mod(modid="practiceprobe",name="Practice smoke test",version="1",clientSideOnly=true,dependencies="required-after:practicebridge")
public final class ForgeProbe {
    private int ticks,draws,worldDraws,stopAt; private Object bridge;
    private final Set<Double> drillGaps=new HashSet<>();
    private final Set<String> drillMatches=new LinkedHashSet<>();
    private boolean drillCountdownSeen,drillGoSeen,drillVisitedLobby;
    private EntityPlayer drillOpponent;private int drillContinuitySamples;private boolean drillLostOpponent;
    private String sumoMatch;private int sumoEnd;private double sumoCenter;
    private int openingResults;private String replayMatch;private double pausedTime;private String inventoryBefore;private int poseChecks;private double poseError;
    private Map<?,?> replay()throws Exception{Field f=bridge.getClass().getDeclaredField("capture");f.setAccessible(true);Object c=f.get(bridge);Field r=c.getClass().getDeclaredField("replay");r.setAccessible(true);Object p=r.get(c);Method m=p.getClass().getDeclaredMethod("state");m.setAccessible(true);return (Map<?,?>)m.invoke(p);}
    private void right(int slot){Minecraft mc=Minecraft.func_71410_x();mc.field_71439_g.field_71071_by.field_70461_c=slot;KeyBinding.func_74507_a(mc.field_71474_y.field_74313_G.func_151463_i());}
    private void left(int slot){Minecraft mc=Minecraft.func_71410_x();mc.field_71439_g.field_71071_by.field_70461_c=slot;KeyBinding.func_74507_a(mc.field_71474_y.field_74312_F.func_151463_i());}
    private Frame frame()throws Exception{Field f=bridge.getClass().getDeclaredField("frame");f.setAccessible(true);return (Frame)f.get(bridge);}
    private Map<?,?> linked()throws Exception{Field f=bridge.getClass().getDeclaredField("capture");f.setAccessible(true);Object capture=f.get(bridge);
        Method m=capture.getClass().getDeclaredMethod("linkedState");m.setAccessible(true);return (Map<?,?>)m.invoke(capture);}
    private Map<?,?> replayFrame()throws Exception{Field f=bridge.getClass().getDeclaredField("capture");f.setAccessible(true);Object c=f.get(bridge);Method m=c.getClass().getDeclaredMethod("replayFrame");m.setAccessible(true);return (Map<?,?>)m.invoke(c);}
    private Map<?,?> views()throws Exception{Field f=bridge.getClass().getDeclaredField("capture");f.setAccessible(true);Object capture=f.get(bridge);Method m=capture.getClass().getDeclaredMethod("viewState");m.setAccessible(true);return (Map<?,?>)m.invoke(capture);}
    private WorldFrame world()throws Exception{Field f=bridge.getClass().getDeclaredField("host");f.setAccessible(true);Object host=f.get(bridge);return (WorldFrame)host.getClass().getMethod("world",float.class).invoke(host,0f);}
    private void command(String text){ClientCommandHandler.instance.func_71556_a(Minecraft.func_71410_x().field_71439_g,text);}
    private void nativeReachCheck(){
        net.minecraft.util.Vec3 eye=new net.minecraft.util.Vec3(0,5.62,0),end=new net.minecraft.util.Vec3(3,5.62,0);
        for(double gap:new double[]{3.399,3.401}){
            net.minecraft.util.AxisAlignedBB raw=new net.minecraft.util.AxisAlignedBB(gap-.3,4,-.3,gap+.3,5.8,.3),expanded=raw.func_72314_b(.1,.1,.1);
            boolean nativeHit=expanded.func_72327_a(eye,end)!=null;
            double measured=practice.math.ReachMath.distance(new double[]{0,5.62,0},practice.math.ReachMath.expand(new double[]{gap-.3,4,-.3,gap+.3,5.8,.3},.1));
            check(nativeHit==(measured<=3),"distance boundary matches vanilla ray-box intersection at gap "+gap);
        }
    }
    private void checkHealth(Minecraft mc){if(mc.field_71439_g.func_110143_aJ()!=20)throw new AssertionError("sumo lost health");}
    private void sumoPlatform(double center,boolean present){
        Minecraft mc=Minecraft.func_71410_x();int count=0;
        for(int x=-5;x<5;x++)for(int z=-5;z<5;z++){
            net.minecraft.block.Block block=mc.field_71441_e.func_180495_p(new net.minecraft.util.BlockPos(center+x,11,z)).func_177230_c();
            boolean expected=present&&(x+.5)*(x+.5)+(z+.5)*(z+.5)<=25;
            if((block==net.minecraft.init.Blocks.field_150357_h)!=expected)throw new AssertionError("sumo platform mismatch "+x+","+z);
            if(expected)count++;
        }
        check(count==(present?80:0),present?"native client sees complete 10-block-wide circular platform":"ordinary replay clears previous sumo platform");
    }
    private void cleanHooks()throws Exception{check(Class.forName("practice.bridge.ObserverHooks").getField("errors").getLong(null)==0,"native observer hooks report no errors");}
    private void check(boolean ok,String message){if(!ok)throw new AssertionError(message);System.out.println("[PracticeProbe] PASS "+message);}
    @Mod.EventHandler public void init(FMLInitializationEvent e){
        bridge=Loader.instance().getIndexedModList().get("practicebridge").getMod();
        FMLCommonHandler.instance().bus().register(this);MinecraftForge.EVENT_BUS.register(this);
    }
    @SubscribeEvent public void render(RenderGameOverlayEvent.Post e){if(e.type==RenderGameOverlayEvent.ElementType.ALL)draws++;}
    @SubscribeEvent public void worldRender(RenderWorldLastEvent e){worldDraws++;try{Map<?,?> frame=replayFrame();if(Boolean.TRUE.equals(frame.get("active"))&&Boolean.TRUE.equals(frame.get("paused"))&&"a".equals(frame.get("source"))){
            Map<?,?> views=(Map<?,?>)frame.get("views"),pair=(Map<?,?>)views.get("a");if(pair!=null){Minecraft mc=Minecraft.func_71410_x();List<?> ids=(List<?>)frame.get("actor_ids");
                for(int i=0;i<2;i++){List<?> pos=(List<?>)((Map<?,?>)pair.get(i==0?"a":"b")).get("render");net.minecraft.entity.Entity actor=mc.field_71441_e.func_73045_a(((Number)ids.get(i)).intValue());if(actor!=null){double dx=actor.field_70165_t-((Number)pos.get(0)).doubleValue(),dy=actor.field_70163_u-((Number)pos.get(1)).doubleValue(),dz=actor.field_70161_v-((Number)pos.get(2)).doubleValue();poseError=Math.max(poseError,Math.sqrt(dx*dx+dy*dy+dz*dz));poseChecks++;}}
            }
        }}catch(Exception failure){throw new RuntimeException(failure);}}
    @SubscribeEvent public void tick(TickEvent.ClientTickEvent e){
        if(e.phase!=TickEvent.Phase.END)return;
        Minecraft mc=Minecraft.func_71410_x();if(mc.field_71439_g==null)return;
        try{
            ticks++;
            if(ticks==25){nativeReachCheck();
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
                Properties props=new Properties();try(java.io.InputStream in=Files.newInputStream(config)){props.load(in);}props.setProperty("sprint.label","Live Forge reload");props.setProperty("potions.enabled","true");try(java.io.OutputStream out=Files.newOutputStream(config)){props.store(out,"Smoke configuration");}
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
                command("/practice reach on");command("/practice ghost bot on");command("/practice ghost me on");command("/practice debug on");
                ClientCommandHandler.instance.func_71556_a(mc.field_71439_g,"/practice mark forge-integration");
                ClientCommandHandler.instance.func_71556_a(mc.field_71439_g,"/practice ping");
            }
            if((ticks>=220&&ticks<310)||(ticks>340&&stopAt==0)){
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
                Map<?,?> state=linked();replayMatch=String.valueOf(state.get("match"));
                check(state.get("match") instanceof String&&!state.get("match").equals(""),"live state identifies the current duel");
                check(state.get("server") instanceof Map&&((Map<?,?>)state.get("server")).size()==2,"authoritative state covers both participants");
                check(state.get("clients") instanceof Map&&((Map<?,?>)state.get("clients")).size()==2,"human and bot client views join the same server snapshot");
                check(Boolean.TRUE.equals(views().get("available")),"direct bot view arrives despite 80 ms gameplay RTT");Map<?,?> advantage=(Map<?,?>)views().get("reach_advantage");check(advantage!=null&&Math.abs(((Number)advantage.get("advantage")).doubleValue()-(((Number)advantage.get("d1_theirs")).doubleValue()-((Number)advantage.get("d2_yours")).doubleValue()))<1e-9,"reach advantage uses both actual client views and the positive-in-our-favor sign");
                check(worldDraws>0&&world().boxes.size()==12&&world().spheres.size()==2&&world().lines.isEmpty(),"real Forge world callback draws two ghosts and two reach views");
                System.out.println("[PracticeProbe] TIMING "+views().get("timing"));
                command("/practice ghost bot off");
                cleanHooks();
                KeyBinding.func_74510_a(mc.field_71474_y.field_74351_w.func_151463_i(),false);
                KeyBinding.func_74510_a(mc.field_71474_y.field_74313_G.func_151463_i(),false);
                mc.field_71439_g.func_71165_d("/stop");
            }
            if(ticks==330)mc.field_71439_g.func_71165_d("/drill mixed");
            if(ticks>340&&stopAt==0){
                if(!drillMatches.isEmpty()&&Math.abs(mc.field_71439_g.field_70165_t)<1000)drillVisitedLobby=true;
                if(drillOpponent!=null){drillContinuitySamples++;if(mc.field_71441_e.func_73045_a(drillOpponent.func_145782_y())!=drillOpponent||!mc.field_71441_e.field_72996_f.contains(drillOpponent))drillLostOpponent=true;}
                Field titleField=net.minecraft.client.gui.GuiIngame.class.getDeclaredField("field_175201_x");titleField.setAccessible(true);
                String title=((String)titleField.get(mc.field_71456_v)).replaceAll("§[0-9A-FK-ORa-fk-or]","");
                if(title.matches("2\\.0|1\\.[0-9]"))drillCountdownSeen=true;
                if(title.equals("GO!"))drillGoSeen=true;
                Map<?,?> state=linked(),v=views();Object drill=state.get("drill");
                if(drill instanceof Map){Map<?,?> d=(Map<?,?>)drill;double gap=((Number)d.get("gap")).doubleValue();drillGaps.add(gap);drillMatches.add(String.valueOf(state.get("match")));
                    if(drillOpponent==null&&((Number)d.get("round")).intValue()==1&&System.currentTimeMillis()>((Number)state.get("combat_start")).longValue()+1000){
                        List<?> names=(List<?>)state.get("players");for(Object object:mc.field_71441_e.field_73010_i){EntityPlayer p=(EntityPlayer)object;if(p!=mc.field_71439_g&&names.contains(p.func_70005_c_())){drillOpponent=p;break;}}
                    }
                    Object o=v.get("opening");if(o instanceof Map&&((Map<?,?>)((Map<?,?>)o).get("last")).size()>0)openingResults++;
                    if(drillGaps.size()==3&&System.currentTimeMillis()-((Number)state.get("combat_start")).longValue()>3000){
                        check(drillGaps.containsAll(Arrays.asList(4.5,6.,9.)),"mixed drill automatically cycles all three starting distances");
                        check(drillCountdownSeen&&drillGoSeen,"native center-screen two-second drill countdown and GO title both displayed");
                        check(drillMatches.size()>=3&&!drillVisitedLobby,"three drill rounds reset directly without a client lobby teleport");
                        check(drillContinuitySamples>100&&!drillLostOpponent,"same native bot entity stays loaded across both drill resets ("+drillContinuitySamples+" observations)");
                        System.out.println("[PracticeProbe] DRILL_MATCHES "+drillMatches);
                        check(openingResults>0,"paired local reach streams produce opening intervals during drills");
                        check(Boolean.TRUE.equals(v.get("available"))&&world().boxes.size()==6,"independent ghost toggle persists across automatic rounds");
                        System.out.println("[PracticeProbe] DRILL_TIMING "+v.get("timing"));cleanHooks();
                        mc.field_71439_g.func_71165_d("/stop");KeyBinding.func_74510_a(mc.field_71474_y.field_74351_w.func_151463_i(),false);KeyBinding.func_74510_a(mc.field_71474_y.field_74313_G.func_151463_i(),false);stopAt=ticks;
                    }
                }
            }
            if(stopAt>0&&ticks==stopAt+100){
                check("".equals(linked().get("match")),"stop cancels automatic drill restart");
                inventoryBefore=mc.field_71439_g.field_71071_by.func_70442_a(new net.minecraft.nbt.NBTTagList()).toString();mc.field_71439_g.func_71165_d("/replay "+replayMatch);
            }
            if(stopAt>0){int t=ticks-stopAt;
                if(t==160){check(Boolean.TRUE.equals(replay().get("active")),"replay stream active");check(mc.field_71439_g.field_71075_bZ.field_75101_c&&mc.field_71439_g.field_71075_bZ.field_75100_b,"hotbar replay permits invulnerable free flight");right(0);}
                if(t==175){check(Boolean.TRUE.equals(replay().get("paused")),"clock item pauses replay");pausedTime=((Number)replay().get("time_ms")).doubleValue();right(2);}
                if(t==190){check(Math.abs(((Number)replay().get("time_ms")).doubleValue()-Math.min(pausedTime+5000,((Number)replay().get("duration_ms")).doubleValue()))<100,"forward item seeks five seconds, clamped at recording end");left(1);}
                if(t==205){check(Math.abs(((Number)replay().get("time_ms")).doubleValue()-Math.max(0,Math.min(pausedTime+5000,((Number)replay().get("duration_ms")).doubleValue())-1000))<100,"rewind item left click seeks one second");mc.field_71439_g.func_71165_d("/replay seek 2");mc.field_71439_g.func_71165_d("/replay pov human");}
                if(t==225){check("a".equals(replay().get("source")),"human POV selects the human recording");check(mc.func_175606_aa()!=mc.field_71439_g,"POV switches native camera to recorded actor");mc.field_71439_g.func_71165_d("/replay ghost human on");mc.field_71439_g.func_71165_d("/replay ghost bot on");}
                if(t==245){check(world().boxes.size()==24&&world().spheres.size()==2,"all four client ghosts and both reach fields coexist in replay");Map<?,?> av=(Map<?,?>)replay().get("availability");check("recorded client poses".equals(((Map<?,?>)av.get("a")).get("quality"))&&"recorded client poses".equals(((Map<?,?>)av.get("b")).get("quality")),"both clients recorded high-rate pose files");mc.field_71439_g.func_71165_d("/replay pov bot");}
                if(t==265){check(poseChecks>10&&poseError<1e-7,"rendered replay actors match archived poses exactly ("+poseChecks+" samples, error "+poseError+")");check(!((Map<?,?>)replayFrame().get("reach_advantage")).isEmpty(),"replay computes reach advantage from both recorded client views");check("b".equals(replay().get("source")),"bot POV selects the bot recording");right(6);}
                if(t==280){check(mc.field_71462_r!=null&&mc.field_71462_r.getClass().getSimpleName().equals("ReplayMenu"),"ghost hotbar item opens native settings menu");mc.func_147108_a(null);mc.field_71439_g.func_71165_d("/replay speed 0.1");mc.field_71439_g.func_71165_d("/replay play");}
                if(t==300){check(Math.abs(((Number)replay().get("speed")).doubleValue()-.1)<.0001&&!Boolean.TRUE.equals(replay().get("paused")),"client replay follows slow-motion clock");right(8);}
                if(t==325){check(inventoryBefore.equals(mc.field_71439_g.field_71071_by.func_70442_a(new net.minecraft.nbt.NBTTagList()).toString()),"replay exit restores the complete prior inventory");check(!Boolean.TRUE.equals(replay().get("active")),"exit item clears replay views");check(mc.func_175606_aa()==mc.field_71439_g&&!mc.field_71439_g.field_71075_bZ.field_75101_c,"exit restores camera and ordinary survival abilities");cleanHooks();}
            }
            if(stopAt>0&&ticks==stopAt+345)mc.field_71439_g.func_71165_d("/duel standstill sumo ping=80");
            if(stopAt>0&&ticks==stopAt+400){
                check("sumo".equals(linked().get("mode")),"sumo command starts the requested mode");
                sumoMatch=String.valueOf(linked().get("match"));sumoCenter=Math.rint(mc.field_71439_g.field_70165_t/4096)*4096;
                sumoPlatform(sumoCenter,true);
                for(Object item:mc.field_71439_g.field_71071_by.field_70462_a)check(item==null,"sumo main inventory is empty");
                for(Object item:mc.field_71439_g.field_71071_by.field_70460_b)check(item==null,"sumo armor is empty");
                System.out.println("[PracticeProbe] SUMO_MATCH "+sumoMatch);
            }
            if(sumoMatch!=null&&sumoEnd==0){
                if(sumoMatch.equals(linked().get("match"))){
                    checkHealth(mc);EntityPlayer target=null;
                    for(Object object:mc.field_71441_e.field_73010_i){EntityPlayer p=(EntityPlayer)object;if(p!=mc.field_71439_g&&p.func_70005_c_().startsWith("LabSlot")&&mc.field_71439_g.func_70068_e(p)<100){target=p;break;}}
                    if(target!=null){double dx=target.field_70165_t-mc.field_71439_g.field_70165_t,dz=target.field_70161_v-mc.field_71439_g.field_70161_v;
                        mc.field_71439_g.field_70177_z=(float)Math.toDegrees(Math.atan2(-dx,dz));mc.field_71439_g.field_70125_A=0;
                        KeyBinding.func_74510_a(mc.field_71474_y.field_74351_w.func_151463_i(),true);
                        KeyBinding.func_74510_a(mc.field_71474_y.field_151444_V.func_151463_i(),true);
                        if(ticks%2==0)KeyBinding.func_74507_a(mc.field_71474_y.field_74312_F.func_151463_i());
                    }
                }else if("".equals(linked().get("match"))){
                    sumoEnd=ticks;KeyBinding.func_74510_a(mc.field_71474_y.field_74351_w.func_151463_i(),false);
                    check(mc.field_71439_g.func_110143_aJ()==20,"sumo ends alive at full health");
                    mc.field_71439_g.func_71165_d("/replay "+sumoMatch);
                }
            }
            if(sumoEnd>0&&ticks==sumoEnd+70){
                check(Boolean.TRUE.equals(replay().get("active")),"sumo recording opens in native replay");
                Map<?,?> r=replay();double offset=((Number)r.get("offset_x")).doubleValue();sumoPlatform(sumoCenter+offset,true);
                for(Object id:(List<?>)r.get("actor_ids")){EntityPlayer actor=(EntityPlayer)mc.field_71441_e.func_73045_a(((Number)id).intValue());
                    check(actor!=null&&actor.func_70694_bm()==null,"sumo replay actor has bare hands");
                    for(Object item:actor.field_71071_by.field_70460_b)check(item==null,"sumo replay has no armor");
                }
                mc.field_71439_g.func_71165_d("/replay "+replayMatch);
            }
            if(sumoEnd>0&&ticks==sumoEnd+140){
                double center=Math.rint(mc.field_71439_g.field_70165_t/4096)*4096;sumoPlatform(center,false);
                check(Boolean.TRUE.equals(replay().get("active")),"ordinary replay works after sumo replay");
                mc.field_71439_g.func_71165_d("/stop");cleanHooks();System.out.println("[PracticeProbe] ALL CHECKS PASSED");mc.func_71400_g();
            }
            if(ticks>3000)throw new AssertionError("drill/sumo test did not complete");
        }catch(Throwable t){System.out.println("[PracticeProbe] FAILED");t.printStackTrace();mc.func_71400_g();}
    }
}
