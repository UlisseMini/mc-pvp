package practice.smoke;

import com.google.gson.Gson;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ScreenShotHelper;
import net.minecraft.world.WorldSettings;
import net.minecraftforge.client.event.GuiOpenEvent;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.*;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.LWJGLException;
import practice.core.replay.ReplayRecording;

/** Offline graphical renderer for showcase GIFs & stills (BowAim K=6 ghosts + Two-POV Sumo). */
@Mod(modid="showcaseprobe", name="Showcase Render Probe", version="1", clientSideOnly=true, dependencies="required-after:practicebridge")
public final class ShowcaseRenderProbe {
    private Object replay, bowAim;
    private ReplayRecording rec;
    private String mode, archiveId;
    private Path archiveRoot, outDir;
    private double startSec, stepMs, firstStepFrom;
    private int warmupTicks, totalFrames, ticks, stage, stepCount, savedFrames;
    private int settleTicks;
    private boolean bowDrawActive, pendingShot;
    private ItemStack bowStack;
    private Field modeField, trajField, ghostsField, interceptField, chanceField, autoRelField, limitField, pingField;
    private Field itemInUseField, itemInUseCountField;
    private Method bowTickMethod, bowDrawMethod, bowHudMethod;
    private final GuiScreen invisibleScreen = new GuiScreen() {
        @Override public boolean func_73868_f() { return false; }
        @Override public void func_73863_a(int mouseX, int mouseY, float partialTicks) {}
    };

    private static Object getField(Object o, String n) throws Exception {
        Class<?> c = o.getClass();
        while (c != null) {
            try { Field f = c.getDeclaredField(n); f.setAccessible(true); return f.get(o); }
            catch (NoSuchFieldException e) { c = c.getSuperclass(); }
        }
        throw new NoSuchFieldException(n + " on " + o.getClass());
    }
    private static Field findField(Class<?> c, String... names) throws Exception {
        for (String n : names) {
            Class<?> cur = c;
            while (cur != null) {
                try { Field f = cur.getDeclaredField(n); f.setAccessible(true); return f; }
                catch (NoSuchFieldException e) { cur = cur.getSuperclass(); }
            }
        }
        throw new NoSuchFieldException(Arrays.toString(names) + " on " + c);
    }
    private static Object call(Object o, String n, Class<?>[] t, Object... v) throws Exception {
        Method m = o.getClass().getDeclaredMethod(n, t);
        m.setAccessible(true);
        return m.invoke(o, v);
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent e) throws Exception {
        Object bridge = Loader.instance().getIndexedModList().get("practicebridge").getMod();
        replay = getField(bridge, "offline");
        bowAim = getField(bridge, "bowAim");
        mode = System.getProperty("showcase.mode", "bowaim");
        archiveId = System.getProperty("showcase.id");
        archiveRoot = Paths.get(System.getProperty("showcase.archiveRoot"));
        outDir = Paths.get(System.getProperty("showcase.outDir"));
        Files.createDirectories(outDir);
        startSec = Double.parseDouble(System.getProperty("showcase.startSec", "10.0"));
        stepMs = Double.parseDouble(System.getProperty("showcase.stepMs", "50.0"));
        warmupTicks = Integer.parseInt(System.getProperty("showcase.warmupTicks", "40"));
        totalFrames = Integer.parseInt(System.getProperty("showcase.frames", "80"));
        bowStack = new ItemStack(Items.field_151031_f);

        if ("bowaim".equals(mode)) {
            if (bowAim == null) throw new IllegalStateException("Bow Aim is unavailable");
            // The bridge clears live BowAim state during offline playback. The probe drives this instance.
            Field bridgeBow = findField(bridge.getClass(), "bowAim");
            bridgeBow.set(bridge, null);

            Class<?> bc = bowAim.getClass();
            modeField = findField(bc, "mode");
            trajField = findField(bc, "trajectory");
            ghostsField = findField(bc, "ghostsEnabled");
            interceptField = findField(bc, "intercept");
            chanceField = findField(bc, "showChance");
            autoRelField = findField(bc, "autoRelease");
            limitField = findField(bc, "limit");
            pingField = findField(bc, "pingOverrideMs");
            bowTickMethod = bc.getDeclaredMethod("tick", Minecraft.class, boolean.class);
            bowTickMethod.setAccessible(true);
            bowDrawMethod = bc.getDeclaredMethod("draw", Minecraft.class, float.class);
            bowDrawMethod.setAccessible(true);
            bowHudMethod = bc.getDeclaredMethod("hud", Minecraft.class, int.class, int.class);
            bowHudMethod.setAccessible(true);
            itemInUseField = findField(EntityPlayer.class, "field_71074_e", "itemInUse");
            itemInUseCountField = findField(EntityPlayer.class, "field_71072_f", "itemInUseCount");
        }

        FMLCommonHandler.instance().bus().register(this);
        MinecraftForge.EVENT_BUS.register(this);
    }

    @SuppressWarnings("unchecked")
    private void configureBowAim() throws Exception {
        if (bowAim == null) return;
        Class<?> modeEnum = modeField.getType();
        Object adaptive = Enum.valueOf((Class<Enum>)modeEnum, "ADAPTIVE");
        modeField.set(bowAim, adaptive);
        Class<?> trajEnum = trajField.getType();
        Object trajOn = Enum.valueOf((Class<Enum>)trajEnum, "ON");
        trajField.set(bowAim, trajOn);
        ghostsField.set(bowAim, true);
        interceptField.set(bowAim, true);
        chanceField.set(bowAim, true);
        autoRelField.set(bowAim, false);
        limitField.set(bowAim, 8);
        pingField.set(bowAim, 60);
    }

    private void syncSelfToOwnActor(Minecraft mc) throws Exception {
        Entity own = (Entity)getField(replay, "ownActor");
        EntityPlayerSP self = mc.field_71439_g;
        if (own == null || self == null) return;
        self.field_70142_S = own.field_70142_S;
        self.field_70137_T = own.field_70137_T;
        self.field_70136_U = own.field_70136_U;
        self.field_70169_q = own.field_70169_q;
        self.field_70167_r = own.field_70167_r;
        self.field_70166_s = own.field_70166_s;
        self.field_70165_t = own.field_70165_t;
        self.field_70163_u = own.field_70163_u;
        self.field_70161_v = own.field_70161_v;
        self.field_70177_z = own.field_70177_z;
        self.field_70126_B = own.field_70126_B;
        self.field_70125_A = own.field_70125_A;
        self.field_70127_C = own.field_70127_C;
        self.field_70759_as = own.field_70177_z;
        self.field_70758_at = own.field_70126_B;
        self.field_70122_E = own.field_70122_E;
        mc.field_71442_b.func_78746_a(WorldSettings.GameType.SURVIVAL);
        self.field_71071_by.field_70462_a[self.field_71071_by.field_70461_c] = bowStack;
        itemInUseField.set(self, bowStack);
        itemInUseCountField.set(self, 72000 - 20);
    }

    @SubscribeEvent
    public void onGuiOpen(GuiOpenEvent event) throws LWJGLException {
        if (event.gui instanceof GuiMainMenu) ReplayGraphics.restoreContext();
    }

    @SuppressWarnings("unchecked")
    @SubscribeEvent
    public void onTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.func_71410_x();
        ticks++;
        try {
            if (stage == 0 && ticks >= 15) {
                ReplayGraphics.prepare(mc);
                Gson gson = new Gson();
                ReplayRecording.Entry entry = ReplayRecording.archiveEntry(archiveRoot.resolve(archiveId),
                        json -> (Map<String, Object>)gson.fromJson(json, Map.class));
                if (entry == null) throw new IllegalStateException("Archive entry not found: " + archiveId);
                rec = ReplayRecording.load(entry, s -> (Map<String, Object>)gson.fromJson(s, Map.class));
                if (!rec.hasJoin) throw new IllegalStateException("Showcase capture is missing the initial world");
                if ("bowaim".equals(mode)) {
                    // The Hypixel fixture contains scoreboard changes that fail during offline seek.
                    // Suppress scoreboard packets for this showcase only.
                    Field modField = Field.class.getDeclaredField("modifiers");
                    modField.setAccessible(true);
                    for (String fname : new String[]{"packets", "inbound"}) {
                        Field f = ReplayRecording.class.getDeclaredField(fname);
                        f.setAccessible(true);
                        List<ReplayRecording.Wire> orig = (List<ReplayRecording.Wire>)f.get(rec);
                        List<ReplayRecording.Wire> kept = new ArrayList<>();
                        for (ReplayRecording.Wire w : orig) {
                            if (w.inbound && (w.protocolId == 0x3B || w.protocolId == 0x3C || w.protocolId == 0x3D || w.protocolId == 0x3E)) continue;
                            kept.add(w);
                        }
                        modField.setInt(f, f.getModifiers() & ~Modifier.FINAL);
                        f.set(rec, Collections.unmodifiableList(kept));
                        System.out.println("[ShowcaseProbe] Filtered " + fname + ": " + orig.size() + " -> " + kept.size());
                    }
                }
                System.out.println("[ShowcaseProbe] Loaded " + archiveId + " dur=" + rec.durationMs + "ms hasJoin=" + rec.hasJoin + " poses=" + rec.poses.size());
                call(replay, "start", new Class[]{ReplayRecording.Entry.class, ReplayRecording.class}, entry, rec);

                double targetMs = startSec * 1000.0;
                Path mf = archiveRoot.resolve(archiveId).resolve("manifest.json");
                if (Files.isRegularFile(mf)) {
                    Map<String, Object> m = gson.fromJson(new String(Files.readAllBytes(mf), "UTF-8"), Map.class);
                    if (m.get("start_ms") instanceof Number && !"bowaim".equals(mode)) {
                        targetMs = ((Number)m.get("start_ms")).doubleValue() - rec.startMs + startSec * 1000.0;
                    }
                }
                targetMs = Math.max(0.0, Math.min(rec.durationMs - 100.0, targetMs));
                call(replay, "seek", new Class[]{double.class}, targetMs);
                String fail = String.valueOf(getField(replay, "failure"));
                if (!fail.isEmpty()) throw new IllegalStateException("Replay seek failure: " + fail);
                firstStepFrom = (double)getField(replay, "time");
                if (firstStepFrom + (warmupTicks + totalFrames) * stepMs > rec.playEnd())
                    throw new IllegalStateException("Showcase runs past the end of the recording");

                mc.func_147108_a(invisibleScreen);
                mc.func_71364_i();
                if ("bowaim".equals(mode)) {
                    configureBowAim();
                    findField(replay.getClass(), "hideText").set(replay, true);
                }
                stage = 1;
                settleTicks = 0;
                System.out.println("[ShowcaseProbe] Seeked to " + targetMs + "ms; settling chunks...");
                return;
            }
            if (stage == 1) {
                if (mc.field_71462_r != invisibleScreen) mc.func_147108_a(invisibleScreen);
                settleTicks++;
                if (settleTicks >= 45) {
                    stage = 2;
                    stepCount = 0;
                    savedFrames = 0;
                    System.out.println("[ShowcaseProbe] Starting playback capture...");
                }
                return;
            }
            if (stage == 2) {
                if (pendingShot) return; // Capture the current state before advancing the replay.
                if (mc.field_71462_r != invisibleScreen) mc.func_147108_a(invisibleScreen);
                double targetMs = firstStepFrom + ++stepCount * stepMs;
                call(replay, "seekForward", new Class[]{double.class}, targetMs);
                String failure = (String)getField(replay, "failure");
                if (!failure.isEmpty()) throw new IllegalStateException(failure);
                call(replay, "pose", new Class<?>[0]);

                if ("bowaim".equals(mode) && bowAim != null) {
                    Entity own = (Entity)getField(replay, "ownActor");
                    syncSelfToOwnActor(mc);
                    boolean wasDead = own != null && own.field_70128_L;
                    GuiScreen savedGui = mc.field_71462_r;
                    boolean savedFocus = mc.field_71415_G;
                    if (own != null) own.field_70128_L = true;
                    mc.field_71462_r = null;
                    mc.field_71415_G = true;
                    try {
                        bowTickMethod.invoke(bowAim, mc, false);
                    } finally {
                        mc.field_71462_r = savedGui;
                        mc.field_71415_G = savedFocus;
                        if (own != null) own.field_70128_L = wasDead;
                    }
                    bowDrawActive = true;
                }

                if (stepCount > warmupTicks) {
                    pendingShot = true;
                }
            }
        } catch (Exception t) {
            t.printStackTrace();
            stage = 3;
            mc.func_71400_g();
        }
    }

    @SubscribeEvent
    public void onRenderTick(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.END || stage != 2 || !pendingShot) return;
        Minecraft mc = Minecraft.func_71410_x();
        try {
            String fname = String.format(Locale.ROOT, "frame_%03d.png", savedFrames);
            ScreenShotHelper.func_148259_a(outDir.toFile(), fname, mc.field_71443_c, mc.field_71440_d, mc.func_147110_a());
            savedFrames++;
            pendingShot = false;
            if (savedFrames >= totalFrames) {
                System.out.println("[ShowcaseProbe] CAPTURE COMPLETE frames=" + savedFrames);
                stage = 3;
                mc.func_71400_g();
            }
        } catch (Exception t) {
            t.printStackTrace();
            stage = 3;
            mc.func_71400_g();
        }
    }

    @SubscribeEvent
    public void onWorldRender(RenderWorldLastEvent event) {
        if (!"bowaim".equals(mode) || !bowDrawActive || bowAim == null || stage != 2) return;
        Minecraft mc = Minecraft.func_71410_x();
        GuiScreen savedGui = mc.field_71462_r;
        boolean savedFocus = mc.field_71415_G;
        mc.field_71462_r = null;
        mc.field_71415_G = true;
        try {
            bowDrawMethod.invoke(bowAim, mc, event.partialTicks);
        } catch (Exception t) {
            t.printStackTrace();
        } finally {
            mc.field_71462_r = savedGui;
            mc.field_71415_G = savedFocus;
        }
    }

    @SubscribeEvent
    public void onOverlay(RenderGameOverlayEvent.Post event) {
        if (event.type != RenderGameOverlayEvent.ElementType.ALL) return;
        if (!"bowaim".equals(mode) || !bowDrawActive || bowAim == null || stage != 2) return;
        Minecraft mc = Minecraft.func_71410_x();
        GuiScreen savedGui = mc.field_71462_r;
        boolean savedFocus = mc.field_71415_G;
        mc.field_71462_r = null;
        mc.field_71415_G = true;
        try {
            int w = event.resolution.func_78326_a(), h = event.resolution.func_78328_b();
            bowHudMethod.invoke(bowAim, mc, w, h);
        } catch (Exception t) {
            t.printStackTrace();
        } finally {
            mc.field_71462_r = savedGui;
            mc.field_71415_G = savedFocus;
        }
    }
}
