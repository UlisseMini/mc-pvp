package practice.smoke;

import java.io.IOException;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.multiplayer.GuiConnecting;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.entity.projectile.EntityArrow;
import net.minecraft.entity.item.EntityEnderPearl;
import net.minecraft.init.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.BlockPos;
import net.minecraft.world.WorldServer;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.WorldType;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

/** Two real Forge clients, an authoritative server and identical repeatable control pulses. */
@Mod(modid="bowparity", name="Bow Duel Parity Probe", version="1", clientSideOnly=true,
        dependencies="required-after:practicebridge")
public final class NativeParity {
    private static final String[] CASES = {
        "walk", "strafe", "diagonal", "sprint", "sneak", "jump", "draw_walk",
        "arrow8", "arrow20", "arrow20_cover", "pearl", "mutual_arrows", "wall_walk", "wall_jump"
    };
    private static final int SETTLE = 25, PLAY = 68;
    private static final String A = "ParityA", B = "ParityB";
    private final boolean first = Boolean.getBoolean("bowparity.first");
    private final boolean host = Boolean.getBoolean("bowparity.host");
    private final int port = Integer.getInteger("bowparity.port", 29321);
    private final Path dir = Paths.get(System.getProperty("bowparity.dir"));
    private PrintWriter pov, server;
    private int clientTick, serverTick, scenario = -1, deadline, observed = -1, actionTick = -1;
    private volatile boolean listening;
    private boolean holdUse;

    @Mod.EventHandler public void init(FMLInitializationEvent event) throws IOException {
        if (host) server = writer("server.tsv");
        else pov = writer(first ? "client-a.tsv" : "client-b.tsv");
        FMLCommonHandler.instance().bus().register(this);
        MinecraftForge.EVENT_BUS.register(this);
    }

    private PrintWriter writer(String name) throws IOException {
        PrintWriter out = new PrintWriter(Files.newBufferedWriter(dir.resolve(name), StandardCharsets.UTF_8), true);
        out.println("event\tcase\ttick\tns\tentity\tid\tx\ty\tz\tvx\tvy\tvz\tyaw\tpitch\tground\tsprint\tsneak\tdraw\thp\thurt\tslot\tdetail");
        return out;
    }

    private static void sample(PrintWriter log, String event, int stage, int tick, Entity e, String detail) {
        EntityPlayer p = e instanceof EntityPlayer ? (EntityPlayer)e : null;
        log.printf(Locale.ROOT, "%s\t%d\t%d\t%d\t%s\t%d\t%.9f\t%.9f\t%.9f\t%.9f\t%.9f\t%.9f\t%.6f\t%.6f\t%b\t%b\t%b\t%d\t%.3f\t%d\t%d\t%s%n",
                event, stage, tick, System.nanoTime(), p == null ? e.getClass().getSimpleName() : p.func_70005_c_(),
                e.func_145782_y(), e.field_70165_t, e.field_70163_u, e.field_70161_v,
                e.field_70159_w, e.field_70181_x, e.field_70179_y, e.field_70177_z, e.field_70125_A,
                e.field_70122_E, e.func_70051_ag(), e.func_70093_af(),
                p == null ? 0 : p.func_71057_bx(), p == null ? 0 : p.func_110143_aJ(),
                p == null ? 0 : p.field_70737_aN, p == null ? 0 : p.field_71071_by.field_70461_c, detail);
    }

    @SubscribeEvent public void spawn(EntityJoinWorldEvent event) {
        if (host && !event.world.field_72995_K && projectile(event.entity))
            sample(server, "spawn", scenario, serverTick, event.entity, "");
    }

    @SubscribeEvent public void hurt(LivingHurtEvent event) {
        if (host && !event.entityLiving.field_70170_p.field_72995_K && event.entityLiving instanceof EntityPlayer)
            sample(server, "damage", scenario, serverTick, event.entityLiving, Float.toString(event.ammount));
    }

    private static boolean projectile(Entity e) {
        return e instanceof EntityArrow || e instanceof EntityEnderPearl;
    }

    @SubscribeEvent public void serverTick(TickEvent.ServerTickEvent event) {
        if (!host || event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.func_71410_x();
        if (mc.func_71401_C() == null) return;
        WorldServer world = mc.func_71401_C().func_71218_a(0);
        if (world == null) return;
        try {
            ++serverTick;
            if (!listening) {
                if (mc.field_71439_g == null) return;
                EntityPlayerMP observer = mc.func_71401_C().func_71203_ab().func_152612_a("ParityHost");
                if (observer == null) return;
                observer.func_71033_a(WorldSettings.GameType.SPECTATOR);
                world.func_82736_K().func_82764_b("doMobSpawning","false");
                world.func_82736_K().func_82764_b("naturalRegeneration","false");
                mc.func_71401_C().func_71229_d(false);
                mc.func_71401_C().func_147137_ag().func_151265_a(InetAddress.getByName("127.0.0.1"), port);
                Files.write(dir.resolve("ready"), new byte[0]);
                listening = true;
            }
            EntityPlayerMP a = mc.func_71401_C().func_71203_ab().func_152612_a(A);
            EntityPlayerMP b = mc.func_71401_C().func_71203_ab().func_152612_a(B);
            if (a == null || b == null || scenario >= CASES.length) return;
            if (serverTick >= deadline) {
                ++scenario;
                if (scenario == CASES.length) {
                    Files.write(dir.resolve("done"), new byte[0]);
                    return;
                }
                deadline = serverTick + SETTLE + PLAY;
                prepare(world, a, b, CASES[scenario]);
            }
            if (serverTick == deadline - PLAY) {
                Path pending = dir.resolve("phase.tmp");
                Files.write(pending, Integer.toString(scenario).getBytes(StandardCharsets.UTF_8));
                Files.move(pending, dir.resolve("phase"), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            }
            sample(server, "tick", scenario, serverTick, a, "");
            sample(server, "tick", scenario, serverTick, b, "");
            for (Entity e : world.field_72996_f) if (projectile(e)) sample(server, "tick", scenario, serverTick, e, "");
        } catch (Exception failure) {
            throw new IllegalStateException("Server parity probe failed", failure);
        }
    }

    private static void prepare(WorldServer world, EntityPlayerMP a, EntityPlayerMP b, String mode) {
        for (Entity e : new ArrayList<>(world.field_72996_f)) if (projectile(e)) e.func_70106_y();
        // Let the arrow-hit targets land without falling off the fixture before the next reset.
        for (int x = -10; x <= 10; x++) for (int z = -26; z <= 26; z++) {
            BlockPos floor = new BlockPos(x,149,z);
            if (world.func_180495_p(floor).func_177230_c() != Blocks.field_150348_b)
                world.func_180501_a(floor,Blocks.field_150348_b.func_176223_P(),3);
        }
        for (int y = 150; y <= 152; y++) {
            BlockPos cover = new BlockPos(0,y,4);
            if (mode.equals("arrow20_cover")) world.func_180501_a(cover,Blocks.field_150348_b.func_176223_P(),3);
            else world.func_175698_g(cover);
            BlockPos wall = new BlockPos(0,y,-7);
            if (mode.equals("wall_walk") || (mode.equals("wall_jump") && y == 150))
                world.func_180501_a(wall,Blocks.field_150348_b.func_176223_P(),3);
            else world.func_175698_g(wall);
        }
        kit(a, -10.5, 0, mode.equals("pearl") ? 12 : -3.5f);
        kit(b, 10.5, 180, -3.5f);
    }

    private static void kit(EntityPlayerMP p, double z, float yaw, float pitch) {
        p.func_71033_a(WorldSettings.GameType.SURVIVAL);
        p.func_71041_bz();
        p.func_70606_j(20);
        p.field_70143_R = 0;
        p.field_70159_w = p.field_70181_x = p.field_70179_y = 0;
        p.field_71135_a.func_147364_a(.5,150,z,yaw,pitch);
        ItemStack bow = new ItemStack(Item.func_150899_d(261));
        bow.func_77966_a(Enchantment.func_180306_c(49),1);
        bow.func_77966_a(Enchantment.func_180306_c(51),1);
        p.field_71071_by.func_70299_a(0,bow);
        p.field_71071_by.func_70299_a(1,new ItemStack(Item.func_150899_d(368)));
        p.field_71071_by.func_70299_a(9,new ItemStack(Item.func_150899_d(262)));
        for (int slot = 0; slot < 4; slot++)
            p.field_71071_by.field_70460_b[slot] = new ItemStack(Item.func_150899_d(305-slot));
        p.field_71071_by.field_70461_c = 0;
    }

    @SubscribeEvent(priority=EventPriority.LOWEST) public void client(TickEvent.ClientTickEvent event) {
        Minecraft mc = Minecraft.func_71410_x();
        try {
            if (event.phase == TickEvent.Phase.START) {
                if (++clientTick == 8) {
                    if (host) {
                        WorldSettings setup = new WorldSettings(61391L, WorldSettings.GameType.SURVIVAL, false, false, WorldType.field_77138_c);
                        mc.func_71371_a("BowParity", "Bow Parity", setup);
                    } else {
                        ServerData data = new ServerData("Bow Parity", "127.0.0.1:" + port, false);
                        mc.func_147108_a(new GuiConnecting(new GuiMainMenu(), mc, data));
                    }
                }
                if (Files.exists(dir.resolve("done"))) { mc.func_71400_g(); return; }
                if (clientTick > 2200) throw new IllegalStateException("Bow parity probe timed out");
                if (mc.field_71439_g == null || !Files.exists(dir.resolve("phase"))) return;
                mc.field_71415_G = true;
                if (mc.field_71462_r != null) mc.func_147108_a(null);
                if (host) return;
                int current = Integer.parseInt(new String(Files.readAllBytes(dir.resolve("phase")), StandardCharsets.UTF_8));
                if (current != observed) { observed = current; actionTick = 0; holdUse = false; }
                act(mc, CASES[current], actionTick++);
            } else if (!host && mc.field_71439_g != null) {
                sample(pov, "after", observed, clientTick, mc.field_71439_g, "");
                for (Entity e : mc.field_71441_e.field_72996_f)
                    if (projectile(e) || (e instanceof EntityPlayer && e != mc.field_71439_g))
                        sample(pov,"observed",observed,clientTick,e,"");
            }
        } catch (Exception failure) {
            throw new IllegalStateException("Client parity probe failed", failure);
        }
    }

    private void act(Minecraft mc, String mode, int t) {
        EntityPlayerSP p = mc.field_71439_g;
        GameSettings keys = mc.field_71474_y;
        boolean moving = first && t < 30;
        boolean forward = moving && (mode.equals("walk") || mode.equals("diagonal") || mode.equals("sprint")
                || mode.equals("sneak") || mode.equals("draw_walk") || mode.startsWith("wall_"));
        boolean right = moving && (mode.equals("strafe") || mode.equals("diagonal"));
        boolean jump = first && (mode.equals("jump") && t == 3 || mode.equals("wall_jump") && t == 8);
        boolean sprint = forward && mode.equals("sprint");
        boolean sneak = forward && mode.equals("sneak");
        boolean bow = (first && (mode.startsWith("arrow") || mode.equals("draw_walk"))) || mode.equals("mutual_arrows");
        boolean pearl = first && mode.equals("pearl") && t == 2;
        int draw = mode.equals("arrow8") ? 8 : mode.equals("draw_walk") ? 35 : 20;
        boolean use = bow && t >= 2 && t < draw + 2;
        binding(keys.field_74351_w,forward); binding(keys.field_74366_z,right);
        binding(keys.field_74314_A,jump); binding(keys.field_151444_V,sprint); binding(keys.field_74311_E,sneak);
        binding(keys.field_74313_G,use);
        if (pearl) p.field_71071_by.field_70461_c = 1;
        else if (t == 0) p.field_71071_by.field_70461_c = 0;
        String input = (forward?2:1)+","+(right?2:1)+","+(jump?1:0)+","+(sprint?1:0)+","
                +(sneak?1:0)+","+((use||pearl)?1:0)+",0,"+p.field_71071_by.field_70461_c+","+t;
        sample(pov, "input", observed, clientTick, p, input);
        if ((use && !holdUse) || pearl) mc.field_71442_b.func_78769_a(p,mc.field_71441_e,p.func_70694_bm());
        if (!use && holdUse) mc.field_71442_b.func_78766_c(p);
        holdUse = use;
    }

    private static void binding(KeyBinding key, boolean down) {
        KeyBinding.func_74510_a(key.func_151463_i(), down);
    }
}
