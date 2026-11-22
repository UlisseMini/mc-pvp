package practice.smoke;

import com.google.common.util.concurrent.ListenableFuture;
import com.mojang.authlib.GameProfile;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityOtherPlayerMP;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.monster.EntityZombie;
import net.minecraft.entity.projectile.EntityArrow;
import net.minecraft.init.Items;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.network.play.client.C03PacketPlayer.C05PacketPlayerLook;
import net.minecraft.network.play.client.C07PacketPlayerDigging;
import net.minecraft.scoreboard.IScoreObjectiveCriteria;
import net.minecraft.scoreboard.ScoreObjective;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.util.BlockPos;
import net.minecraft.world.WorldServer;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.WorldType;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import practice.core.bow.BowBallistics;
import org.lwjgl.opengl.GL11;

import java.io.*;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.*;
import java.util.*;

@Mod(modid="bowaimprobe", name="Native Bow Aim Probe", version="1", clientSideOnly=true,
        dependencies="required-after:practicebridge")
public final class ForgeProbe {
    private ListenableFuture<?> scoreboard;
    private int ticks;
    private Object assist;
    private final List<Integer> releaseOrder = Collections.synchronizedList(new ArrayList<>());
    private boolean checkedApi;

    @Mod.EventHandler public void init(FMLInitializationEvent ignored) {
        FMLCommonHandler.instance().bus().register(this);
    }

    private static void require(boolean yes, String description) {
        if (!yes) throw new AssertionError(description);
    }

    private static Method method(Object object, String name, Class<?>... args) throws Exception {
        Method m = object.getClass().getDeclaredMethod(name, args);
        m.setAccessible(true);
        return m;
    }

    private static Object field(Object object, String name) throws Exception {
        Field f = object.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(object);
    }

    @SubscribeEvent public void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.func_71410_x();
        try {
            if (++ticks == 8) {
                Object bridge = Loader.instance().getIndexedModList().get("practicebridge").getMod();
                assist = field(bridge,"bowAim");
                require(mode("status").equals("Bow aim off"), "defaults off");
                WorldSettings setup = new WorldSettings(752693L, WorldSettings.GameType.CREATIVE, false, false, WorldType.field_77138_c);
                mc.func_71371_a("BowVerification", "Bow Verification", setup);
            }
            if (ticks > 8 && scoreboard == null && mc.field_71439_g != null && mc.func_71401_C() != null) {
                WorldServer world = mc.func_71401_C().func_71218_a(0);
                scoreboard = world.func_152344_a(() -> {
                    try { nativeShots(world); }
                    catch (Exception failure) { throw new IllegalStateException(failure); }
                });
            }
            if (scoreboard != null && scoreboard.isDone() && !checkedApi) {
                scoreboard.get();
                setupRelease(mc);
                checkedApi = true;
            }
            if (checkedApi && releaseOrder.contains(1)) {
                synchronized (releaseOrder) {
                    int release = releaseOrder.indexOf(1);
                    require(release > 0 && releaseOrder.get(release-1) == 0, "native look precedes RELEASE_USE_ITEM");
                }
                require(mode("off").equals("Bow aim off"), "off restores manual controls");
                System.out.println("BOW_NATIVE_PASS BedWars roster, bounded POV, preview, bridge cues, cover exit, charge, chance HUD, threshold and one release");
                mc.func_71400_g();
            }
            if (ticks > 800) throw new AssertionError("Native bow probe timed out");
        } catch(Throwable failure) {
            failure.printStackTrace();
            System.exit(2);
        }
    }

    private String mode(String value) throws Exception {
        return method(assist,"command",String.class).invoke(assist,value).toString();
    }

    private void setupRelease(Minecraft mc) throws Exception {
        require(mode("linear").equals("Bow aim linear") && mode("on").equals("Bow aim adaptive"), "model modes");
        require(mode("autorelease").endsWith("off"),"auto-release defaults off");
        EntityPlayerSP self = mc.field_71439_g;
        Object network = mc.func_147114_u().func_147298_b();
        Channel channel = null;
        for (Field f : network.getClass().getDeclaredFields()) if (Channel.class.isAssignableFrom(f.getType())) {
            f.setAccessible(true);
            channel = (Channel)f.get(network);
        }
        require(channel != null, "native packet queue");
        channel.pipeline().addLast("bow-order-probe", new ChannelOutboundHandlerAdapter() {
            @Override public void write(ChannelHandlerContext ctx, Object packet, ChannelPromise promise) throws Exception {
                if (packet instanceof C05PacketPlayerLook) releaseOrder.add(0);
                if (packet instanceof C07PacketPlayerDigging &&
                        ((C07PacketPlayerDigging)packet).func_180762_c() == C07PacketPlayerDigging.Action.RELEASE_USE_ITEM)
                    releaseOrder.add(1);
                super.write(ctx,packet,promise);
            }
        });
        mc.func_147108_a(null);
        mc.field_71415_G = true;
        double x = self.field_70165_t, z = self.field_70161_v;
        self.func_70107_b(x, 146, z);
        Scoreboard board = mc.field_71441_e.func_96441_U();
        ScoreObjective sidebar = board.func_96535_a("bow_probe", IScoreObjectiveCriteria.field_96641_b);
        sidebar.func_96681_a("§e§lBED WARS");
        board.func_96530_a(1, sidebar);
        squad(board, self.func_70005_c_(), "Red0", "§c§lR §r§c");
        EntityOtherPlayerMP ally = spawn(mc,8513,"RedMate",x+7,z);
        squad(board, "RedMate", "Red6", "§c§lR §r§c");
        EntityOtherPlayerMP target = spawn(mc,8514,"BowDummy",x+21,z);
        squad(board, "BowDummy", "Blue3", "§9§lB §r§9");
        EntityOtherPlayerMP far = spawn(mc,8516,"FurtherBlue",x+28,z);
        squad(board, "FurtherBlue", "Blue10", "§9§lB §r§9");
        EntityOtherPlayerMP unknown = spawn(mc,8515,"NoTeam",x+8,z+8);
        self.field_70177_z = -90;
        self.field_70125_A = 0;
        ItemStack bow = new ItemStack(Items.field_151031_f);
        self.field_71071_by.field_70462_a[self.field_71071_by.field_70461_c] = bow;
        self.func_71008_a(bow,72000-20);
        Method tickAim = method(assist,"tick",Minecraft.class,boolean.class);
        require(mode("preview").contains("aim off") && mode("status").equals("Bow aim off"),"preview-only mode");
        tickAim.invoke(assist,mc,false);
        require(self.field_70177_z == -90 && self.field_70125_A == 0,"preview leaves POV alone");
        Method draw = method(assist,"draw",Minecraft.class,float.class);
        draw.invoke(assist,mc,1f);
        require(GL11.glGetError() == GL11.GL_NO_ERROR,"standalone path draw");
        BowBallistics.Shot straight = BowBallistics.fromAim(x,146 + self.func_70047_e(),z,3,-90,0);
        BlockPos wall = new BlockPos(x+5,147,z+.16);
        mc.field_71441_e.func_180501_a(wall,Blocks.field_150348_b.func_176223_P(),3);
        Object blocked = trace(mc,straight,null);
        require((boolean)field(blocked,"blocked") && ((List<?>)field(blocked,"points")).size() < 5,
                "visual path stops at close terrain");
        mc.field_71441_e.func_175698_g(wall);
        require(mode("on").equals("Bow aim adaptive") && mode("limit 1").endsWith("1°"), "adjustment configuration");
        tickAim.invoke(assist,mc,false);
        tickAim.invoke(assist,mc,false);
        require(field(assist,"target") == null && self.field_70125_A == 0,
                "BedWars teammate with different scoreboard ID blocks acquisition");
        ally.func_70107_b(x+7,146,z+9);
        unknown.func_70107_b(x+8,146,z);
        tickAim.invoke(assist,mc,false);
        require(field(assist,"target") == null, "BedWars unknown colors are skipped");
        unknown.func_70107_b(x+8,146,z+9);
        for (int i = 0; i < 10; i++) tickAim.invoke(assist,mc,false);
        Object chosen = field(assist,"target");
        require(chosen != null && field(chosen,"player") == target && far.func_70089_S(),
                "nearest enemy on the view ray");
        require(Math.abs(self.field_70125_A) > .1 &&
                        Math.hypot(self.field_70177_z + 90, self.field_70125_A) <= 1.001,
                "capped visible correction stays bounded across repeated ticks");
        require(mode("intercept on").endsWith("on"),"intercept toggle");
        double standardHeight = (double)field(chosen,"bodyHeight");
        BlockPos chestCover = new BlockPos(x+20,146,z);
        mc.field_71441_e.func_180501_a(chestCover,Blocks.field_150348_b.func_176223_P(),3);
        tickAim.invoke(assist,mc,false);
        Object covered = field(assist,"target");
        require(covered != null && field(covered,"player")==target &&
                (double)field(covered,"bodyHeight") > standardHeight, "exposed aim above cover");
        mc.field_71441_e.func_175698_g(chestCover);
        far.func_70107_b(x+28,146,z+8);
        double coverCenter = Math.floor(z) + .5;
        BlockPos cover = new BlockPos(x+19,146,coverCenter);
        BlockPos coverTop = new BlockPos(x+19,147,coverCenter);
        mc.field_71441_e.func_180501_a(cover,Blocks.field_150348_b.func_176223_P(),3);
        mc.field_71441_e.func_180501_a(coverTop,Blocks.field_150348_b.func_176223_P(),3);
        mode("linear");
        target.func_70107_b(x+21,146,coverCenter);
        self.field_70177_z = (float)Math.toDegrees(Math.atan2(-21, coverCenter-z)) + (float)field(assist,"yawOffset");
        self.field_70125_A = (float)field(assist,"pitchOffset");
        for (int i=0;i<16;i++) tickAim.invoke(assist,mc,false);
        require(!self.func_70685_l(target) && field(assist,"target") == null,
                "stationary target with a blocked trajectory is skipped");
        for (int i=-15;i<=0;i++) {
            target.func_70107_b(x+21,146,coverCenter+i*.23);
            tickAim.invoke(assist,mc,false);
        }
        Object emerging = field(assist,"target");
        require(!self.func_70685_l(target) && emerging != null && field(emerging,"player") == target,
                "currently occluded enemy acquires when the predicted intercept clears cover");
        self.field_70177_z = 90 + (float)field(assist,"yawOffset");
        tickAim.invoke(assist,mc,false);
        require(field(assist,"target") == null, "covered enemy outside the aim cone is skipped");
        mc.field_71441_e.func_175698_g(cover);
        mc.field_71441_e.func_175698_g(coverTop);
        self.field_70177_z = -90 + (float)field(assist,"yawOffset");
        self.field_70125_A = (float)field(assist,"pitchOffset");
        mode("on");
        require(field(assist,"bridge") != null, "trained bridge model is packaged");
        target.field_71071_by.field_70462_a[target.field_71071_by.field_70461_c] = new ItemStack(Blocks.field_150348_b);
        target.field_70125_A = 63;
        target.func_70095_a(true);
        for (int i=0;i<26;i++) {
            target.func_70107_b(x+21,146,z+i*.033);
            tickAim.invoke(assist,mc,false);
        }
        chosen = field(assist,"target");
        require(chosen != null && field(chosen,"player")==target &&
                (boolean)field(chosen,"building"), "visible block, sneak and movement activate expert");
        BowBallistics.Shot perfect = (BowBallistics.Shot)field(chosen,"ideal");
        Object hit = trace(mc,perfect,chosen);
        require(field(hit,"feet") != null && (double)field(hit,"hitTicks") > 0
                && (double)field(hit,"hitTicks") <= perfect.time, "moving target intercept and ETA");
        draw.invoke(assist,mc,1f);
        Method render = hit.getClass().getEnclosingClass().getDeclaredMethod("draw",Minecraft.class,hit.getClass(),
                boolean.class, boolean.class,float.class);
        render.setAccessible(true);
        render.invoke(null,mc,hit,true,true,1f);
        require(GL11.glGetError() == GL11.GL_NO_ERROR,"trajectory and ETA draw");
        require(releaseOrder.isEmpty(), "aim neither shoots nor sends a release");

        self.field_70177_z = perfect.yaw + (float)field(assist,"yawOffset");
        self.field_70125_A = perfect.pitch + (float)field(assist,"pitchOffset");
        tickAim.invoke(assist,mc,false);
        double p = (double)field(assist,"probability");
        require(p > .10 && p < 1, "replay-fitted chance at the actual view");
        Method hud = method(assist,"hud",Minecraft.class,int.class,int.class);
        hud.invoke(assist,mc,320,240);
        require(GL11.glGetError() == GL11.GL_NO_ERROR,"percent renders above the crosshair");
        Method auto = method(assist,"tryAutoRelease",Minecraft.class,EntityPlayerSP.class,boolean.class);
        require(mode("autorelease on").contains("75%"),"opt-in auto-release and default threshold");
        mode("threshold 100%");
        auto.invoke(assist,mc,self,true);
        require(self.func_71039_bw(),"full charge waits below threshold");
        mode("threshold 1");
        self.func_71041_bz();
        self.func_71008_a(bow,72000-8);
        require(self.func_71057_bx()==8 && BowBallistics.speed(self.func_71057_bx()) < 1.1,"elapsed partial bow power");
        auto.invoke(assist,mc,self,true);
        require(self.func_71039_bw() && releaseOrder.isEmpty(),"high chance never releases a partial draw");
        self.func_71041_bz();
        self.func_71008_a(bow,72000-20);
        auto.invoke(assist,mc,self,false);
        require(self.func_71039_bw(),"use must still be held");
        auto.invoke(assist,mc,self,true);
        require(!self.func_71039_bw() && (boolean)field(assist,"releasedWhileHeld"),"one automatic release");
        self.func_71008_a(bow,72000-20);
        auto.invoke(assist,mc,self,true);
        require(self.func_71039_bw(),"latched use does not release again");
        self.func_71041_bz();
        for (int id : new int[]{8513,8514,8515,8516}) mc.field_71441_e.func_73028_b(id);
    }

    private static void squad(Scoreboard board, String player, String id, String prefix) {
        board.func_96527_f(id).func_96666_b(prefix);
        board.func_151392_a(player,id);
    }

    private static EntityOtherPlayerMP spawn(Minecraft mc, int id, String name, double x, double z) {
        EntityOtherPlayerMP target = new EntityOtherPlayerMP(mc.field_71441_e,new GameProfile(UUID.randomUUID(),name));
        target.func_70107_b(x,146,z);
        mc.field_71441_e.func_73027_a(id,target);
        return target;
    }

    private static Object trace(Minecraft mc, BowBallistics.Shot shot, Object target) throws Exception {
        Class<?> implementation = Class.forName("practice.bridge.BowAimPreview");
        Method trace = implementation.getDeclaredMethod("trace", WorldClient.class, BowBallistics.Shot.class,
                double.class, Class.forName("practice.bridge.BowAim$Selection"));
        trace.setAccessible(true);
        return trace.invoke(null,mc.field_71441_e,shot,shot.time,target);
    }

    private static class Target extends EntityZombie {
        Target(WorldServer world) { super(world); func_70105_a(.6f,1.8f); }
    }

    private static class Arrow extends EntityArrow {
        Arrow(WorldServer world, EntityLivingBase shooter, long seed) {
            super(world,shooter,2f);
            field_70146_Z.setSeed(seed);
            double yaw = Math.toRadians(shooter.field_70177_z), pitch = Math.toRadians(shooter.field_70125_A);
            func_70186_c(-Math.sin(yaw)*Math.cos(pitch), -Math.sin(pitch), Math.cos(yaw)*Math.cos(pitch), 3f, 1f);
        }
    }

    private static void nativeShots(WorldServer world) throws IOException {
        int[][] n = new int[13][4];
        int[][][] hit = new int[13][4][3];
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(
                Files.newInputStream(Paths.get("native-cases.bin"))))) {
            require(in.readInt() == 0x424f574e,"native case file");
            for (int x = -4; x <= 4; x++) for (int z = -4; z <= 4; z++) world.func_72964_e(x,z);
            for (int game = in.readInt(); game != 0; game = in.readInt()) {
                int range = in.readInt(), index = range == 12 ? 0 : range == 24 ? 1 : range == 38 ? 2 : 3;
                long seed = in.readLong();
                double x = in.readDouble(), z = in.readDouble();
                float[][] path = new float[BowBallistics.MAX_TICKS + 1][3];
                for (float[] xyz : path) for (int k = 0; k < 3; k++) xyz[k] = in.readFloat();
                n[game][index]++;
                for (int mode = 0; mode < 3; mode++) {
                    float yaw = in.readFloat(), pitch = in.readFloat();
                    if (Float.isFinite(yaw) && tryShot(world, path, x, z, yaw, pitch, seed)) hit[game][index][mode]++;
                }
            }
        }
        System.out.println("BOW_NATIVE game,range,n,linear,shared,adaptive");
        for (int g = 9; g <= 12; g++) for (int i = 0; i < 4; i++) {
            System.out.printf("BOW_NATIVE %d,%d,%d,%d,%d,%d%n",g,new int[]{12,24,38,54}[i],n[g][i],
                    hit[g][i][0],hit[g][i][1],hit[g][i][2]);
        }
    }

    private static boolean tryShot(WorldServer world,float[][] path, double x, double z, float yaw, float pitch, long seed) {
        EntityZombie shooter = new EntityZombie(world);
        shooter.func_70107_b(x, 160 + 1.62 - shooter.func_70047_e(), z);
        shooter.field_70177_z = yaw;
        shooter.field_70125_A = pitch;
        Arrow arrow = new Arrow(world, shooter, seed);
        Target target = new Target(world);
        target.func_70107_b(8,160,8);
        require(world.func_72838_d(target),"spawn native target");
        boolean hit = false;
        for (int t = 1; t < path.length && !arrow.field_70128_L; t++) {
            target.func_70107_b(8 + path[t][0],160 + path[t][1],8 + path[t][2]);
            world.func_72866_a(target,false);
            arrow.func_70071_h_();
            if (target.func_110143_aJ() < target.func_110138_aP()) {
                hit = true;
                break;
            }
        }
        world.func_72900_e(target);
        arrow.func_70106_y();
        return hit;
    }
}
