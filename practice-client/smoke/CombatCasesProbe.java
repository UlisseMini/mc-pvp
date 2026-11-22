package practice.smoke;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.client.gui.GuiIngame;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.client.event.sound.PlaySoundEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

@Mod(modid="combatcasesprobe",name="Native melee cases",version="1",clientSideOnly=true,dependencies="required-after:practicebridge")
public final class CombatCasesProbe {
    private int ticks, arena;
    private boolean guard, attacked, second;
    private Method jump;
    private Field flashTimer;
    private int healthFrames, staleFrames, vanillaFlashRequests;
    private boolean enableChime;
    private static final int PERIOD=46;
    private static final int CASES=20;
    private static final int SUSTAINED_HITS=6;

    @Mod.EventHandler public void init(FMLInitializationEvent event) throws Exception {
        FMLCommonHandler.instance().bus().register(this);
        MinecraftForge.EVENT_BUS.register(this);
        jump=EntityLivingBase.class.getDeclaredMethod("func_70664_aZ");
        jump.setAccessible(true);
        flashTimer=GuiIngame.class.getDeclaredField("field_175191_F");
        flashTimer.setAccessible(true);
    }

    private static void say(EntityPlayerSP player,String text){
        player.func_71165_d(text);
    }

    @SubscribeEvent public void chat(ClientChatReceivedEvent event) {
        String message=event.message.func_150260_c();
        if(message.contains("probe-block-on"))guard=true;
        if(message.contains("probe-block-off"))guard=false;
        if(message.contains("probe-chime-on")&&!Boolean.getBoolean("probe.hidden"))enableChime=true;
        if(message.contains("probe-done")&&!Boolean.getBoolean("probe.attacker"))arena=-100000;
    }

    @SubscribeEvent public void sound(PlaySoundEvent event) {
        if(event.name.endsWith("random.anvil_land")&&!Boolean.getBoolean("probe.attacker"))
            System.out.println("[Cases] sound "+System.currentTimeMillis()+" "+event.sound.func_147655_f());
    }

    @SubscribeEvent(priority=EventPriority.LOWEST)
    public void hearts(RenderGameOverlayEvent.Pre event) throws IllegalAccessException {
        if(event.type!=RenderGameOverlayEvent.ElementType.HEALTH||Boolean.getBoolean("probe.attacker"))return;
        healthFrames++;
        if(flashTimer.getLong(Minecraft.func_71410_x().field_71456_v)!=0)staleFrames++;
    }

    @SubscribeEvent public void afterHearts(RenderGameOverlayEvent.Post event) throws IllegalAccessException {
        if(event.type==RenderGameOverlayEvent.ElementType.HEALTH&&!Boolean.getBoolean("probe.attacker")&&
                flashTimer.getLong(Minecraft.func_71410_x().field_71456_v)!=0)vanillaFlashRequests++;
    }

    private static void face(EntityPlayerSP self, EntityPlayer target, double hitY) {
        double x=target.field_70165_t-self.field_70165_t;
        double z=target.field_70161_v-self.field_70161_v;
        double y=target.field_70163_u+hitY-self.field_70163_u-self.func_70047_e();
        self.field_70177_z=(float)Math.toDegrees(Math.atan2(-x,z));
        self.field_70125_A=(float)-Math.toDegrees(Math.atan2(y,Math.hypot(x,z)));
    }

    private static void strike(Minecraft mc, EntityPlayerSP self, EntityPlayer victim, int trial, String label) {
        self.field_71071_by.field_70461_c=0;
        self.func_71038_i();
        mc.field_71442_b.func_78764_a(self,victim);
        System.out.println("[Cases] strike "+System.currentTimeMillis()+" "+trial+" "+label+
            " fall="+self.field_70143_R+" distance="+self.func_70032_d(victim));
    }

    private void sustainedGuard(Minecraft mc, EntityPlayerSP self, EntityPlayer victim, int elapsed) {
        if (elapsed == 0) {
            say(self, "/tell LabObserver probe-sustained");
            say(self, "/tell LabObserver probe-block-on");
            say(self, "/effect LabObserver clear");
            say(self, "/effect LabObserver 6 1 5 true");
            say(self, "/effect LabObserver 22 12 0 true");
            say(self, "/replaceitem entity LabObserver slot.armor.head diamond_helmet");
            say(self, "/replaceitem entity LabSlot0 slot.hotbar.0 diamond_sword 1 0 {ench:[{id:16s,lvl:2s}]}");
        }
        int hit = (elapsed - 2 * PERIOD) / 16;
        int phase = (elapsed - 2 * PERIOD) % 16;
        if (elapsed >= 2 * PERIOD && hit < SUSTAINED_HITS) {
            if (phase == 0) say(self, String.format(Locale.ROOT, "/tp LabSlot0 %.3f 4.0 %.3f 90 0",
                victim.field_70165_t + (hit % 2 == 0 ? 1 : -1), victim.field_70161_v));
            if (phase == 5) {
                face(self, victim, 1.45);
                strike(mc, self, victim, CASES, "sustained-guard");
            }
        }
        if (elapsed == 2 * PERIOD + SUSTAINED_HITS * 16 + 5) say(self, "/effect LabObserver 7 1 0 true");
        int afterSustained = elapsed - (2 * PERIOD + SUSTAINED_HITS * 16 + 20);
        if (afterSustained < 0) return;
        if (Boolean.getBoolean("probe.arrows")) arrows(self, victim, afterSustained);
        else if (afterSustained == 0) say(self, "/tell LabObserver probe-done");
    }

    private void arrows(EntityPlayerSP self, EntityPlayer victim, int elapsed) {
        if (elapsed == 0) say(self, "/tell LabObserver probe-arrows");
        if (elapsed >= 120) {
            if (elapsed == 120) say(self, "/tell LabObserver probe-done");
            return;
        }
        int phase = elapsed % 60;
        if (phase == 0) {
            say(self, "/tell LabObserver probe-block-" + (elapsed == 0 ? "on" : "off"));
            say(self, "/effect LabObserver 6 1 5 true");
            say(self, String.format(Locale.ROOT, "/tp LabSlot0 %.3f 4.0 %.3f 90 0",
                victim.field_70165_t + 7, victim.field_70161_v + 3));
        }
        if (phase == 20) {
            say(self, String.format(Locale.ROOT, "/summon Arrow %.3f 5.4 %.3f {Motion:[-1.5d,0d,0d],damage:4d}",
                victim.field_70165_t + 7, victim.field_70161_v));
        }
    }

    @SubscribeEvent public void tick(TickEvent.ClientTickEvent event) throws Exception {
        if(event.phase!=TickEvent.Phase.START)return;
        Minecraft mc=Minecraft.func_71410_x();
        EntityPlayerSP self=mc.field_71439_g;
        if(self==null)return;
        ticks++;
        if(!Boolean.getBoolean("probe.attacker")){
            if(ticks==20)say(self,"/blocksound off");
            if(enableChime){
                say(self,"/blocksound on");
                enableChime=false;
            }
            if(ticks==23)say(self,"/duel probe-stand rod_diamond 0 "+Integer.getInteger("probe.rtt",0));
            KeyBinding use=mc.field_71474_y.field_74313_G;
            KeyBinding.func_74510_a(use.func_151463_i(),guard);
            if(guard&&!self.func_71039_bw()&&mc.field_71462_r==null)KeyBinding.func_74507_a(use.func_151463_i());
            if(arena==-100000){
                say(self,"/stop");
                arena=-ticks;
            }
            if(arena<0&&ticks+arena>25||ticks>1600){
                long errors=Class.forName("practice.bridge.ObserverHooks").getField("errors").getLong(null);
                System.out.println("[Cases] hearts "+healthFrames+" "+staleFrames+" "+vanillaFlashRequests);
                System.out.println("[Cases] DONE errors="+errors);
                mc.func_71400_g();
            }
            return;
        }
        EntityPlayer victim=mc.field_71441_e.func_72924_a("LabObserver");
        if(self.field_70165_t<2048||victim==null||victim.field_70165_t<2048){
            arena=0;
            return;
        }
        int elapsed=++arena-65;
        if(elapsed<0)return;
        int trial=elapsed/PERIOD, phase=elapsed%PERIOD;
        if(trial>=CASES) {
            sustainedGuard(mc,self,victim,elapsed-CASES*PERIOD);
            return;
        }
        int kind=(trial/2)%5;
        boolean rod=kind==2||kind==3;
        boolean critical=kind==1||kind==3||kind==4;
        if(phase==0){
            attacked=second=false;
            say(self, "/tell LabObserver probe-block-"+(trial%2==1?"on":"off"));
            say(self, "/tp LabObserver 4100.4 4.0 0.1 270 0");
            say(self, "/tp LabSlot0 4101.35 4.0 0.1 90 0");
            say(self, "/effect LabObserver 6 1 5 true");
            if(trial==10)say(self,"/replaceitem entity LabSlot0 slot.hotbar.0 diamond_sword 1 0 {ench:[{id:16s,lvl:4s}]}");
            if(trial==12)say(self,"/tell LabObserver probe-chime-on");
            if(trial==14)say(self,"/replaceitem entity LabObserver slot.armor.head iron_helmet 1 0 {ench:[{id:0s,lvl:3s}]}");
            if(trial==17)say(self,"/effect LabObserver 11 5 0 true");
            System.out.println("[Cases] trial "+System.currentTimeMillis()+" "+trial+" "+kind);
        }
        boolean earlySwitch=rod&&(trial==6||trial==7||trial==14||trial==15);
        int cast=critical?17:12;
        if(phase<8||phase>27)return;
        face(self,victim,rod&&!attacked?1.1:1.45);
        if(critical&&phase==(earlySwitch?13:11))jump.invoke(self);
        if(kind==4&&phase==12)strike(mc,self,victim,trial,"rising-normal");
        if(rod&&phase==cast){
            self.field_71071_by.field_70461_c=1;
            mc.field_71442_b.func_78769_a(self,mc.field_71441_e,self.func_70694_bm());
            System.out.println("[Cases] cast "+System.currentTimeMillis()+" "+trial);
        }
        if(earlySwitch&&phase==cast+2){
            self.field_71071_by.field_70461_c=0;
            mc.field_71442_b.func_78765_e();
        }
        if(rod&&phase==cast+(earlySwitch?5:3) || !rod&&(!critical&&phase==16 || critical&&phase>=17&&!attacked&&self.field_70143_R>0)){
            strike(mc,self,victim,trial,rod?"rod-sword":critical?"falling":"grounded");
            attacked=true;
        }
        if(rod&&attacked&&!second&&phase==cast+10&&self.func_70032_d(victim)<3){
            strike(mc,self,victim,trial,"rod-fresh");
            second=true;
        }
    }
}
