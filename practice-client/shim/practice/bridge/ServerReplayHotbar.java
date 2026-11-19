package practice.bridge;

import net.minecraft.client.Minecraft;
import net.minecraftforge.client.event.MouseEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

/** Keep vanilla hotbar rendering and controls tied to the viewer during a lab replay. */
public final class ServerReplayHotbar {
    private final PracticeCapture capture;
    ServerReplayHotbar(PracticeCapture capture){this.capture=capture;}

    // Called only from the two camera lookups inside GuiIngame.renderTooltip.
    public static Object owner(Object camera){
        if(!(ObserverHooks.sink instanceof PracticeCapture))return camera;
        PracticeCapture c=(PracticeCapture)ObserverHooks.sink;
        if(!c.replay.active()||!c.practiceAllowed())return camera;
        Minecraft mc=Minecraft.func_71410_x();
        return mc.field_71439_g==null?camera:mc.field_71439_g;
    }

    @SubscribeEvent public void click(MouseEvent e){
        if(e.button<0||!capture.replay.active()||!capture.practiceAllowed())return;
        Minecraft mc=Minecraft.func_71410_x();
        if(mc.field_71462_r!=null||mc.field_71439_g==null)return;
        int binding=e.button-100;
        boolean use=binding==mc.field_71474_y.field_74313_G.func_151463_i();
        if(!use&&binding!=mc.field_71474_y.field_74312_F.func_151463_i())return;
        if(e.buttonstate)capture.send("replay_control","control "+mc.field_71439_g.field_71071_by.field_70461_c+(use?" right":" left"));
        e.setCanceled(true);
    }
}
