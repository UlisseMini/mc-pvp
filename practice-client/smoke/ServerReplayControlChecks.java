package practice.smoke;

import java.lang.reflect.Field;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTTagList;
import net.minecraftforge.client.event.MouseEvent;
import net.minecraftforge.common.MinecraftForge;

/** Exercises the actual Forge mouse event, native camera, inventory, menu and server on m2151. */
final class ServerReplayControlChecks {
    private String initialInventory,first,second;
    private int initialSlot;
    private long equalTime,tickTime;
    private Map<?,?> humanInputs, humanTickInputs;
    private static String inventory(EntityPlayer p){return p.field_71071_by.func_70442_a(new NBTTagList()).toString();}
    private static String label(Map<?,?> state){return String.valueOf(state.get("step_label"));}
    private static long time(Map<?,?> state){return ((Number)state.get("time_ms")).longValue();}
    private static Map<?,?> checkInputs(ForgeProbe probe,String side,int mask,int leftCps)throws Exception{
        Map<?,?> frame=probe.replayFrame(), keys=(Map<?,?>)frame.get("keystrokes");
        probe.check(side.equals(frame.get("keystrokes_source"))&&keys!=null
            &&Integer.valueOf(mask).equals(keys.get("mask"))&&Integer.valueOf(leftCps).equals(keys.get("left_cps")),
            "selected POV "+side+" shows its recorded keys and clicks at "+time(frame)+" ms: "+keys);
        return keys;
    }
    private static void click(Minecraft mc,int slot,boolean right)throws Exception{
        mc.field_71439_g.field_71071_by.field_70461_c=slot-1;
        MouseEvent e=new MouseEvent();
        Field b=MouseEvent.class.getField("button"),down=MouseEvent.class.getField("buttonstate");
        b.setAccessible(true);down.setAccessible(true);b.setInt(e,right?1:0);down.setBoolean(e,true);
        if(!MinecraftForge.EVENT_BUS.post(e))throw new AssertionError("Replay click reached vanilla item use");
    }
    private static void toolbar(ForgeProbe probe,Minecraft mc,boolean follow)throws Exception{
        Object camera=mc.func_175606_aa();
        probe.check((camera!=mc.field_71439_g)==follow,"native camera "+(follow?"attached":"free"));
        EntityPlayer owner=(EntityPlayer)Class.forName("practice.bridge.ServerReplayHotbar").getMethod("owner",Object.class).invoke(null,camera);
        probe.check(owner==mc.field_71439_g,"native hotbar renders the viewer's inventory");
        String[] names={"Play / Pause","Event","Recorded tick","Seek","Speed","Viewpoint","Settings","Combo","Exit replay"};
        for(int i=0;i<9;i++)if(owner.field_71071_by.field_70462_a[i]==null||!owner.field_71071_by.field_70462_a[i].func_82833_r().contains(names[i]))throw new AssertionError("Missing toolbar slot "+(i+1));
        probe.check(true,"all nine named tools present in "+probe.replay().get("source")+" POV");
    }
    void tick(ForgeProbe probe,Minecraft mc,int tick)throws Exception{
        Map<?,?> state=probe.replay();
        switch(tick){
            case 20:mc.field_71439_g.field_71071_by.field_70461_c=4;break;
            case 35:
                initialInventory=inventory(mc.field_71439_g);initialSlot=mc.field_71439_g.field_71071_by.field_70461_c;
                mc.field_71439_g.func_71165_d("/replay m2151");break;
            case 115:
                toolbar(probe,mc,false);click(mc,1,true);break;
            case 126:
                probe.check(Boolean.TRUE.equals(state.get("paused")),"play/pause item pauses");mc.field_71439_g.func_71165_d("/replay seek 0");break;
            case 138:click(mc,2,true);break;
            case 150:
                first=label(state);equalTime=time(state);
                probe.check(first.contains("fresh hit")&&equalTime==537,"first hit selected at 537 ms");
                Map<?,?> frame=probe.replayFrame();
                probe.check("live".equals(frame.get("keystrokes_source"))&&!frame.containsKey("keystrokes"),"server free camera uses the viewer's live keystrokes");
                click(mc,2,true);break;
            case 162:
                second=label(state);probe.check(time(state)==equalTime&&second.contains("knockback H 0.854")&&!first.equals(second),"next event selects same-ms knockback separately");
                click(mc,2,false);break;
            case 174:
                probe.check(time(state)==equalTime&&first.equals(label(state)),"left click returns to the exact first event");
                click(mc,6,true);break;
            case 194:
                toolbar(probe,mc,true);probe.check("a".equals(state.get("source")),"compass selected human POV");
                humanInputs=checkInputs(probe,"a",33,1);click(mc,2,true);break;
            case 218:
                probe.check(label(state).contains("applied velocity")&&time(state)==542,"human applied its velocity at 542 ms");
                probe.check(humanInputs.equals(probe.replayFrame().get("keystrokes")),"human POV keeps the held input at 542 ms");
                click(mc,3,true);break;
            case 232:
                tickTime=time(state);humanTickInputs=(Map<?,?>)probe.replayFrame().get("keystrokes");
                probe.check(label(state).contains("client tick"),"steps to a recorded human tick");click(mc,3,true);break;
            case 244:
                probe.check(time(state)>tickTime,"next tick advances");click(mc,3,false);break;
            case 256:
                probe.check(time(state)==tickTime,"previous tick restores exact recorded timestamp");
                probe.check(humanTickInputs!=null&&humanTickInputs.equals(probe.replayFrame().get("keystrokes")),"backward step restores the selected POV's keystrokes");
                mc.field_71439_g.func_71165_d("/replay seek 0.537");
                click(mc,6,true);break;
            case 272:
                toolbar(probe,mc,true);probe.check("b".equals(state.get("source")),"compass selected bot POV");
                checkInputs(probe,"b",129,3);
                probe.check(String.valueOf(probe.replayFrame().get("sprint_hits")).contains("sprint=true"),"outgoing sprint knockback comes from the server replay");
                mc.field_71439_g.func_71165_d("/replay camera free");break;
            case 284:
                toolbar(probe,mc,false);
                checkInputs(probe,"b",129,3);
                mc.field_71439_g.func_71165_d("/replay camera b");click(mc,3,true);break;
            case 296:
                probe.check(label(state).contains("client tick")&&Boolean.TRUE.equals(state.get("paused")),"bot tick inspected while paused");
                click(mc,7,true);break;
            case 308:
                probe.check(mc.field_71462_r!=null&&mc.field_71462_r.getClass().getSimpleName().equals("ReplayMenu"),"settings opens inside player POV");
                mc.func_147108_a(null);click(mc,6,true);break;
            case 322:
                toolbar(probe,mc,false);probe.check("server".equals(state.get("source")),"compass returns to free server view");
                probe.check("live".equals(probe.replayFrame().get("keystrokes_source")),"returning to free server view restores live keystrokes");
                mc.field_71439_g.func_71165_d("/replay camera a");click(mc,9,false);break;
            case 330:
                probe.check(Boolean.TRUE.equals(state.get("active")),"exit ignores left click");
                probe.check("a".equals(state.get("camera"))&&!probe.replayFrame().containsKey("keystrokes_source"),"following an actor in Server POV leaves the keystrokes HUD hidden");
                click(mc,9,true);break;
            case 348:
                probe.check(!Boolean.TRUE.equals(state.get("active"))&&mc.func_175606_aa()==mc.field_71439_g,"exit restores normal view");
                probe.check(inventory(mc.field_71439_g).equals(initialInventory)&&mc.field_71439_g.field_71071_by.field_70461_c==initialSlot,"exit restores inventory and selected slot");
                mc.field_71439_g.func_71165_d("/replay m2151");break;
            case 405:
                mc.field_71439_g.func_71165_d("/replay pause");
                mc.field_71439_g.func_71165_d("/replay pov human");
                mc.field_71439_g.func_71165_d("/replay seek 1.620");break;
            case 429:
                probe.check(Boolean.TRUE.equals(((Map<?,?>)probe.replayFrame().get("coach_feedback")).get("brake")),"human replay shows its confirmed brake");
                mc.field_71439_g.func_71165_d("/replay pov bot");
                mc.field_71439_g.func_71165_d("/replay seek 0.700");break;
            case 453:
                probe.check(Boolean.TRUE.equals(((Map<?,?>)probe.replayFrame().get("coach_feedback")).get("brake")),"bot replay shows its own confirmed brake");
                mc.field_71439_g.func_71165_d("/replay seek 0.670");break;
            case 477:
                probe.check(!Boolean.TRUE.equals(((Map<?,?>)probe.replayFrame().get("coach_feedback")).get("brake")),"rewinding before the contact clears the cue");
                mc.field_71439_g.func_71165_d("/replay stop");break;
            case 490:System.out.println("[PracticeProbe] ALL CHECKS PASSED");mc.func_71400_g();break;
        }
    }
}
