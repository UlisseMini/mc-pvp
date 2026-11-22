package practice.bridge;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemBow;
import net.minecraft.item.ItemEgg;
import net.minecraft.item.ItemSnowball;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemSword;
import net.minecraft.network.play.client.C03PacketPlayer.C05PacketPlayerLook;
import net.minecraft.network.play.client.C07PacketPlayerDigging;
import net.minecraft.network.play.client.C08PacketPlayerBlockPlacement;
import net.minecraft.network.play.client.C0EPacketClickWindow;
import net.minecraft.network.play.server.S0EPacketSpawnObject;
import net.minecraft.network.play.server.S22PacketMultiBlockChange;
import net.minecraft.network.play.server.S23PacketBlockChange;
import net.minecraft.network.play.server.S32PacketConfirmTransaction;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import practice.core.bow.BowBallistics;
import practice.core.bow.BowBallistics.Shot;
import practice.core.bow.BowMotion;
import practice.core.bow.BowMotion.Mode;
import practice.core.bow.BowMotion.Track;
import practice.core.bow.BowNeuralMotion;
import practice.core.bow.BowNeuralV4;
import practice.core.bow.BowReleasePolicy;
import practice.core.bow.BowReleasePolicy.ReleaseMode;
import practice.core.bow.BowWaitModel;
import practice.core.bow.BridgeMotion;
import practice.core.bow.BowHitChance;
import practice.core.bow.VoidKbValue;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Capped aim correction and an independent bow trajectory overlay. */
public final class BowAim {
    static BowAim hooks;
    private static final double RANGE = 65, THROW_RANGE = 56, NEAR_AIM = 9.5;
    private static final double BOW_TURN_PER_TICK = 2.8, THROW_TURN_PER_TICK = 4.2;
    private static final double[] AIM_HEIGHTS = {.50, .76, .88, .32};
    private static final int DEFAULT_LIMIT = 3;
    private final Map<UUID, Track> tracks = new HashMap<>();
    private final BowMotion motion;
    private final BridgeMotion bridge;
    private final BowHitChance chance;
    private final BowNeuralMotion neural;
    private final BowNeuralV4 neuralV4;
    private final BowWaitModel waitModel;
    private final BowReleasePolicy releasePolicy = new BowReleasePolicy();
    private final PracticeCapture capture;
    private final BowAimTeams teams = new BowAimTeams();
    private final Path settings;
    private final BlockPos.MutableBlockPos solidCursor = new BlockPos.MutableBlockPos();
    private WorldClient world;
    private final VoidKbValue.SolidQuery solidQuery = this::isBlockSolid;
    private final ConcurrentHashMap<Long, Integer> recentPlaced = new ConcurrentHashMap<>();
    private static final int RECENT_PLACE_HORIZON = 120;

    private static long packBlock(int x, int y, int z) {
        return (((long)(x & 0x3FFFFF)) << 42) | (((long)(y & 0xFFFFF)) << 22) | ((long)(z & 0x3FFFFF));
    }

    private static int unpackX(long k) { int v = (int)((k >>> 42) & 0x3FFFFFL); return (v & 0x200000) != 0 ? (v | 0xFFC00000) : v; }
    private static int unpackY(long k) { int v = (int)((k >>> 22) & 0xFFFFFL);  return (v & 0x80000) != 0 ? (v | 0xFFF00000) : v; }
    private static int unpackZ(long k) { int v = (int)(k & 0x3FFFFFL);          return (v & 0x200000) != 0 ? (v | 0xFFC00000) : v; }

    private boolean isBlockSolid(int wx, int wy, int wz) {
        if (world == null || wy < 0 || wy >= 256) return false;
        solidCursor.func_181079_c(wx, wy, wz);
        if (!world.func_175667_e(solidCursor)) return true; // unloaded chunk -> safe solid, never hallucinate void
        Block b = world.func_180495_p(solidCursor).func_177230_c();
        return b != null && b.func_180646_a(world, solidCursor) != null;
    }
    private Mode mode;
    private boolean legacyAdaptive;
    private int tick, limit = DEFAULT_LIMIT;
    private Selection target;
    private UUID releaseTargetId;
    private float yawOffset, pitchOffset;
    private boolean intercept;
    private boolean showChance = true, autoRelease, releasedWhileHeld, autoFiring;
    private boolean throwablesEnabled = true, ghostsEnabled = true, bridgeHack = false;
    private ReleaseMode releaseMode = ReleaseMode.EV;
    private double probability = Double.NaN, currentEv = Double.NaN, currentPVoid = 0.0, currentVWait = Double.NaN;
    private int threshold = 75;
    private int pingOverrideMs = -1; // -1 = auto (passive live packet RTT -> server-list ping -> non-spoofed tab ping)
    private volatile double livePacketPingMs = 0.0;
    private double serverListPingMs = 0.0;
    private double tabPingMs = 0.0;
    private final Map<Integer, Long> pendingClicks = new HashMap<>();
    private volatile long lastBowReleaseNs = 0L;
    private final List<AirborneThrow> airborneThrows = new ArrayList<>();
    private volatile int snapTick;
    private volatile double snapEyeX, snapEyeY, snapEyeZ, snapThrowTime = 14.0, snapLeadShift;
    private volatile float snapYaw, snapPitch;
    private volatile boolean snapCanThrow;
    private NetHandlerPlayClient lastConn;
    private Trajectory trajectory = Trajectory.AUTO;

    private enum Trajectory { AUTO, ON, OFF }

    private static final class AirborneThrow {
        final int thrownTick;
        final double leadShift;
        final Shot shot;

        AirborneThrow(int thrownTick, double leadShift, Shot shot) {
            this.thrownTick = thrownTick;
            this.leadShift = leadShift;
            this.shot = shot;
        }
    }

    static final class Selection {
        final EntityPlayer player;
        final Shot ideal;
        final double[][] path;
        final BowNeuralMotion.Prediction prediction;
        final AxisAlignedBB bounds;
        final boolean building;
        final double bodyHeight;
        final boolean throwable;
        final double ev;
        final double pVoid;
        final double leadShift;
        final double[] modeValues;
        final double[] modeVoids;
        final double[] modeUncovered;
        final int aimedMode;

        Selection(EntityPlayer player, Shot ideal, double[][] path, BowNeuralMotion.Prediction prediction,
                  boolean building, double bodyHeight, boolean throwable,
                  double ev, double pVoid, double leadShift,
                  double[] modeValues, double[] modeVoids, double[] modeUncovered, int aimedMode) {
            this.player = player;
            this.ideal = ideal;
            this.path = path;
            this.prediction = prediction;
            this.building = building;
            this.bodyHeight = bodyHeight;
            this.throwable = throwable;
            this.ev = ev;
            this.pVoid = pVoid;
            this.leadShift = leadShift;
            this.modeValues = modeValues;
            this.modeVoids = modeVoids;
            this.modeUncovered = modeUncovered;
            this.aimedMode = aimedMode;
            bounds = player.func_174813_aQ().func_72317_d(
                    -player.field_70165_t, -player.field_70163_u, -player.field_70161_v);
        }
    }

    BowAim(Path directory, PracticeCapture capture) {
        settings = directory.resolve("bow-aim.properties");
        this.capture = capture;
        BowMotion loaded = null;
        try (InputStream data = BowAim.class.getResourceAsStream("/practice/bow-motion.bin")) {
            if (data != null) loaded = BowMotion.read(data);
        } catch (IOException failure) {
            System.err.println("[PracticeBridge] Could not load bow motion model: " + failure.getMessage());
        }
        motion = loaded;
        BridgeMotion specialist = null;
        try (InputStream data = BowAim.class.getResourceAsStream("/practice/bridge-motion.bin")) {
            if (data != null) specialist = BridgeMotion.read(data);
        } catch (IOException failure) {
            System.err.println("[PracticeBridge] Could not load bridge motion model: " + failure.getMessage());
        }
        bridge = specialist;
        BowHitChance probabilities = null;
        try (InputStream data = BowAim.class.getResourceAsStream("/practice/bow-hit-chance.bin")) {
            if (data != null) probabilities = BowHitChance.read(data);
        } catch (IOException failure) {
            System.err.println("[PracticeBridge] Could not load bow hit chance model: " + failure.getMessage());
        }
        chance = probabilities;
        BowNeuralMotion loadedNeural = null;
        BowNeuralV4 loadedV4 = null;
        try (InputStream raw = BowAim.class.getResourceAsStream("/practice/bow-neural.bin")) {
            if (raw != null) {
                BufferedInputStream buf = new BufferedInputStream(raw);
                buf.mark(8);
                int magic = new DataInputStream(buf).readInt();
                buf.reset();
                if (magic == BowNeuralV4.MAGIC) {
                    loadedV4 = BowNeuralV4.read(buf);
                } else {
                    loadedNeural = BowNeuralMotion.read(buf);
                }
            }
        } catch (IOException failure) {
            System.err.println("[PracticeBridge] Could not load bow neural model: " + failure.getMessage());
        }
        neural = loadedNeural;
        neuralV4 = loadedV4;
        BowWaitModel loadedWait = null;
        try (InputStream data = BowAim.class.getResourceAsStream("/practice/bow-wait.bin")) {
            if (data != null) loadedWait = BowWaitModel.read(data);
        } catch (IOException failure) {
            System.err.println("[PracticeBridge] Could not load bow wait model: " + failure.getMessage());
        }
        waitModel = loadedWait;
        if (Files.isRegularFile(settings)) {
            Properties saved = new Properties();
            try (InputStream stream = Files.newInputStream(settings)) {
                saved.load(stream);
                trajectory = Trajectory.valueOf(saved.getProperty("trajectory", "auto").toUpperCase(Locale.ROOT));
                intercept = Boolean.parseBoolean(saved.getProperty("intercept", "false"));
                limit = degrees(saved.getProperty("limit", "3"));
                showChance = Boolean.parseBoolean(saved.getProperty("chance", "true"));
                autoRelease = Boolean.parseBoolean(saved.getProperty("autorelease", "false"));
                threshold = percent(saved.getProperty("threshold", "75"));
                releaseMode = ReleaseMode.valueOf(saved.getProperty("releasemode", "ev").toUpperCase(Locale.ROOT));
                pingOverrideMs = parsePing(saved.getProperty("ping", "auto"));
                throwablesEnabled = Boolean.parseBoolean(saved.getProperty("throwables", "true"));
                ghostsEnabled = Boolean.parseBoolean(saved.getProperty("ghosts", "true"));
                bridgeHack = Boolean.parseBoolean(saved.getProperty("bridgehack", "false"));
            } catch (IOException | IllegalArgumentException ignored) {}
        }
    }

    private static int parsePing(String value) {
        String s = value.trim().toLowerCase(Locale.ROOT);
        if (s.equals("auto")) return -1;
        if (s.endsWith("ms")) s = s.substring(0, s.length() - 2).trim();
        try {
            int n = Integer.parseInt(s);
            if (n >= 0 && n <= 250) return n;
        } catch (NumberFormatException ignored) {}
        throw new IllegalArgumentException("Choose ping auto or 0 to 250 ms");
    }

    private double effectivePingMs(Minecraft mc) {
        if (pingOverrideMs >= 0) return pingOverrideMs;
        if (mc.func_71356_B()) return 0.0; // integrated singleplayer
        if (livePacketPingMs > 0.5) return livePacketPingMs;
        if (serverListPingMs > 5.0) return serverListPingMs;
        if (tabPingMs > 5.0) return tabPingMs;
        return 0.0;
    }

    private String pingSourceLabel(Minecraft mc) {
        if (pingOverrideMs >= 0) return pingOverrideMs + " ms (manual)";
        if (mc.func_71356_B()) return "0 ms (local)";
        if (livePacketPingMs > 0.5) return "auto " + Math.round(livePacketPingMs) + " ms (live rtt)";
        if (serverListPingMs > 5.0) return "auto " + Math.round(serverListPingMs) + " ms (server list)";
        if (tabPingMs > 5.0) return "auto " + Math.round(tabPingMs) + " ms (tab)";
        return "auto (measuring — or /p bowaim ping <ms>)";
    }

    private double leadShiftTicks(Minecraft mc) {
        double ms = effectivePingMs(mc);
        return ms <= 8.0 ? 0.0 : Math.min(5.0, ms / 50.0);
    }

    /** Passive zero-extra-packet RTT measurement from own C0E->S32 clicks & C07->S0E bow arrow spawns. */
    static void observePacket(boolean outbound, Object packet, long ns) {
        BowAim aim = hooks;
        if (aim == null || packet == null) return;
        if (outbound) {
            if (packet instanceof C0EPacketClickWindow) {
                C0EPacketClickWindow click = (C0EPacketClickWindow)packet;
                int key = (click.func_149548_c() << 16) | (click.func_149547_f() & 0xFFFF);
                synchronized (aim.pendingClicks) {
                    if (aim.pendingClicks.size() > 32) aim.pendingClicks.clear();
                    aim.pendingClicks.put(key, ns);
                }
            } else if (packet instanceof C07PacketPlayerDigging) {
                if (((C07PacketPlayerDigging)packet).func_180762_c() == C07PacketPlayerDigging.Action.RELEASE_USE_ITEM) {
                    aim.lastBowReleaseNs = ns;
                }
            } else if (packet instanceof C08PacketPlayerBlockPlacement) {
                C08PacketPlayerBlockPlacement use = (C08PacketPlayerBlockPlacement)packet;
                if (use.func_149568_f() == 255 && aim.snapCanThrow) {
                    ItemStack st = use.func_149574_g();
                    if (st != null && (st.func_77973_b() instanceof ItemSnowball || st.func_77973_b() instanceof ItemEgg)) {
                        Shot thrown = BowBallistics.withTime(
                                BowBallistics.fromAimThrowable(aim.snapEyeX, aim.snapEyeY, aim.snapEyeZ,
                                        aim.snapYaw, aim.snapPitch),
                                aim.snapThrowTime);
                        synchronized (aim.airborneThrows) {
                            if (aim.airborneThrows.size() >= 20) aim.airborneThrows.remove(0);
                            aim.airborneThrows.add(new AirborneThrow(aim.snapTick, aim.snapLeadShift, thrown));
                        }
                    }
                }
            }
            return;
        }
        if (packet instanceof S32PacketConfirmTransaction) {
            S32PacketConfirmTransaction tx = (S32PacketConfirmTransaction)packet;
            int key = (tx.func_148889_c() << 16) | (tx.func_148890_d() & 0xFFFF);
            Long sent;
            synchronized (aim.pendingClicks) { sent = aim.pendingClicks.remove(key); }
            if (sent != null) aim.recordRttSample((ns - sent) / 1_000_000.0);
        } else if (packet instanceof S23PacketBlockChange) {
            S23PacketBlockChange bc = (S23PacketBlockChange)packet;
            aim.recordDynamicBlock(bc.func_179827_b(), bc.func_180728_a());
        } else if (packet instanceof S22PacketMultiBlockChange) {
            S22PacketMultiBlockChange mbc = (S22PacketMultiBlockChange)packet;
            S22PacketMultiBlockChange.BlockUpdateData[] updates = mbc.func_179844_a();
            if (updates != null) {
                for (S22PacketMultiBlockChange.BlockUpdateData u : updates) {
                    if (u != null) aim.recordDynamicBlock(u.func_180090_a(), u.func_180088_c());
                }
            }
        } else if (packet instanceof S0EPacketSpawnObject) {
            S0EPacketSpawnObject spawn = (S0EPacketSpawnObject)packet;
            if (spawn.func_148993_l() == 60) { // arrow
                long sent = aim.lastBowReleaseNs;
                if (sent > 0L) {
                    double ms = (ns - sent) / 1_000_000.0;
                    if (ms >= 5.0 && ms <= 500.0) {
                        Minecraft mc = Minecraft.func_71410_x();
                        EntityPlayerSP self = mc == null ? null : mc.field_71439_g;
                        if (self != null) {
                            double ax = spawn.func_148997_d() / 32.0;
                            double ay = spawn.func_148998_e() / 32.0;
                            double az = spawn.func_148994_f() / 32.0;
                            double d2 = sq(ax - self.field_70165_t) + sq(ay - (self.field_70163_u + self.func_70047_e()))
                                    + sq(az - self.field_70161_v);
                            if (d2 <= 9.0) {
                                aim.lastBowReleaseNs = 0L;
                                aim.recordRttSample(ms);
                            }
                        }
                    } else if (ms > 500.0) {
                        aim.lastBowReleaseNs = 0L;
                    }
                }
            }
        }
    }

    private void recordDynamicBlock(BlockPos pos, IBlockState state) {
        if (pos == null) return;
        Block b = state == null ? null : state.func_177230_c();
        boolean solid = b != null && b.func_149688_o().func_76230_c();
        long key = packBlock(pos.func_177958_n(), pos.func_177956_o(), pos.func_177952_p());
        if (solid) {
            if (recentPlaced.size() < 2048) recentPlaced.put(key, snapTick);
        } else {
            recentPlaced.remove(key);
        }
    }

    private void recordRttSample(double ms) {
        if (!(ms >= 5.0 && ms <= 500.0)) return;
        double cur = livePacketPingMs;
        livePacketPingMs = cur <= 0.5 ? ms : 0.78 * cur + 0.22 * ms;
    }

    private static double sq(double v) { return v * v; }

    private static int degrees(String value) {
        try {
            int n = Integer.parseInt(value);
            if (n >= 0 && n <= 8) return n;
        } catch (NumberFormatException ignored) {}
        throw new IllegalArgumentException("Choose an aim limit from 0 to 8 degrees");
    }

    private static int percent(String value) {
        try {
            int n = Integer.parseInt(value.endsWith("%") ? value.substring(0,value.length()-1) : value);
            if (n >= 1 && n <= 100) return n;
        } catch (NumberFormatException ignored) {}
        throw new IllegalArgumentException("Choose a hit chance from 1 to 100 percent");
    }

    private void save(Trajectory path, boolean marker, int maximum,
                      boolean display, boolean automatic, int minimum, ReleaseMode rMode, int pingMs,
                      boolean allowThrowables, boolean showGhosts, boolean enableBridgeHack) throws IOException {
        Properties values = new Properties();
        values.setProperty("trajectory", path.name().toLowerCase(Locale.ROOT));
        values.setProperty("intercept", String.valueOf(marker));
        values.setProperty("limit", String.valueOf(maximum));
        values.setProperty("chance", String.valueOf(display));
        values.setProperty("autorelease", String.valueOf(automatic));
        values.setProperty("threshold", String.valueOf(minimum));
        values.setProperty("releasemode", rMode.name().toLowerCase(Locale.ROOT));
        values.setProperty("ping", pingMs < 0 ? "auto" : String.valueOf(pingMs));
        values.setProperty("throwables", String.valueOf(allowThrowables));
        values.setProperty("ghosts", String.valueOf(showGhosts));
        values.setProperty("bridgehack", String.valueOf(enableBridgeHack));
        Path temp = Files.createTempFile(settings.getParent(), "bow-aim-", ".tmp");
        try {
            try (OutputStream stream = Files.newOutputStream(temp)) { values.store(stream, "Bow aim display"); }
            Files.move(temp, settings, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temp);
        }
        trajectory = path;
        intercept = marker;
        limit = maximum;
        showChance = display;
        autoRelease = automatic;
        threshold = minimum;
        releaseMode = rMode;
        pingOverrideMs = pingMs;
        throwablesEnabled = allowThrowables;
        ghostsEnabled = showGhosts;
        bridgeHack = enableBridgeHack;
        if (!automatic) releasedWhileHeld = false;
        releasePolicy.reset();
    }

    private static boolean toggle(String text) {
        if (!"on".equals(text) && !"off".equals(text)) throw new IllegalArgumentException("Choose on or off");
        return "on".equals(text);
    }

    String command(String text) throws IOException {
        String[] args = text.toLowerCase(Locale.ROOT).split("\\s+", 2);
        String value = args.length > 1 ? args[1] : "";
        switch (args[0]) {
            case "on": case "adaptive": case "neural": mode = Mode.ADAPTIVE; legacyAdaptive = false; break;
            case "legacy": mode = Mode.ADAPTIVE; legacyAdaptive = true; break;
            case "linear": mode = Mode.LINEAR; legacyAdaptive = false; break;
            case "off": mode = null; reset(); break;
            case "preview":
                save(Trajectory.ON, false, limit, showChance, autoRelease, threshold, releaseMode, pingOverrideMs,
                        throwablesEnabled, ghostsEnabled, bridgeHack);
                mode = null;
                reset();
                return "Bow trajectory preview on · aim off";
            case "trajectory":
                if (!value.isEmpty()) {
                    Trajectory path;
                    try { path = Trajectory.valueOf(value.toUpperCase(Locale.ROOT)); }
                    catch (IllegalArgumentException e) { throw new IllegalArgumentException("Use trajectory on, off or auto"); }
                    save(path, intercept, limit, showChance, autoRelease, threshold, releaseMode, pingOverrideMs,
                            throwablesEnabled, ghostsEnabled, bridgeHack);
                }
                return "Bow trajectory " + trajectory.name().toLowerCase(Locale.ROOT);
            case "intercept":
                if (!value.isEmpty()) save(trajectory, toggle(value), limit, showChance, autoRelease, threshold,
                        releaseMode, pingOverrideMs, throwablesEnabled, ghostsEnabled, bridgeHack);
                return "Bow intercept " + (intercept ? "on" : "off");
            case "ghosts":
                if (!value.isEmpty()) save(trajectory, intercept, limit, showChance, autoRelease, threshold,
                        releaseMode, pingOverrideMs, throwablesEnabled, toggle(value), bridgeHack);
                return "Bow multi-mode ghosts " + (ghostsEnabled ? "on" : "off");
            case "throwables":
                if (!value.isEmpty()) save(trajectory, intercept, limit, showChance, autoRelease, threshold,
                        releaseMode, pingOverrideMs, toggle(value), ghostsEnabled, bridgeHack);
                return "Snowball/egg volley aim " + (throwablesEnabled ? "on" : "off");
            case "bridgehack":
                if (!value.isEmpty()) save(trajectory, intercept, limit, showChance, autoRelease, threshold,
                        releaseMode, pingOverrideMs, throwablesEnabled, ghostsEnabled, toggle(value));
                return "Explicit bridge continuation override " + (bridgeHack ? "on" : "off (pure learned NN)");
            case "limit":
                if (!value.isEmpty()) save(trajectory, intercept, degrees(value), showChance, autoRelease, threshold,
                        releaseMode, pingOverrideMs, throwablesEnabled, ghostsEnabled, bridgeHack);
                return "Bow aim correction limit " + limit + "°";
            case "chance":
                if (!value.isEmpty()) save(trajectory, intercept, limit, toggle(value), autoRelease, threshold,
                        releaseMode, pingOverrideMs, throwablesEnabled, ghostsEnabled, bridgeHack);
                return "Bow EV/chance display " + (showChance ? "on" : "off");
            case "autorelease":
                if (!value.isEmpty()) save(trajectory, intercept, limit, showChance, toggle(value), threshold,
                        releaseMode, pingOverrideMs, throwablesEnabled, ghostsEnabled, bridgeHack);
                return "Bow auto-release " + (autoRelease
                        ? "on (" + releaseMode.name().toLowerCase(Locale.ROOT) + ", layup " + threshold + "%)" : "off");
            case "release":
                if (!value.isEmpty()) {
                    ReleaseMode chosen;
                    try { chosen = ReleaseMode.valueOf(value.toUpperCase(Locale.ROOT)); }
                    catch (IllegalArgumentException e) { throw new IllegalArgumentException("Use release ev, smart or threshold"); }
                    save(trajectory, intercept, limit, showChance, autoRelease, threshold, chosen, pingOverrideMs,
                            throwablesEnabled, ghostsEnabled, bridgeHack);
                }
                return "Bow auto-release policy " + releaseMode.name().toLowerCase(Locale.ROOT)
                        + " (layup " + threshold + "%)";
            case "threshold":
                if (!value.isEmpty()) save(trajectory, intercept, limit, showChance, autoRelease, percent(value),
                        releaseMode, pingOverrideMs, throwablesEnabled, ghostsEnabled, bridgeHack);
                return "Bow auto-release threshold " + threshold + "%";
            case "ping":
                if (!value.isEmpty()) save(trajectory, intercept, limit, showChance, autoRelease, threshold,
                        releaseMode, parsePing(value), throwablesEnabled, ghostsEnabled, bridgeHack);
                return "Bow ping lead " + pingSourceLabel(Minecraft.func_71410_x());
            case "status": break;
            default: throw new IllegalArgumentException("Use /p help bowaim");
        }
        if (mode != null && motion == null && neural == null && neuralV4 == null) {
            mode = null;
            return "Bow aim model missing from the installed bridge";
        }
        boolean hasNeural = neuralV4 != null || neural != null;
        String verTag = neuralV4 != null ? "neural v4 (3D+25x25+dyn-blk" : "neural v3 (EV+void";
        String activeName = mode == null ? "off"
                : mode == Mode.ADAPTIVE ? (hasNeural && !legacyAdaptive
                        ? verTag + (waitModel != null ? "+V_wait" : "")
                          + (throwablesEnabled ? "+snow/egg" : "")
                          + (ghostsEnabled ? "+ghosts" : "")
                          + (bridgeHack ? "+bridgehack" : "")
                          + ", ping=" + pingSourceLabel(Minecraft.func_71410_x()) + ")"
                        : "legacy adaptive")
                : mode.name().toLowerCase(Locale.ROOT);
        return "Bow aim " + activeName + (target == null ? "" : " · " + target.player.func_70005_c_());
    }

    void chat(Minecraft mc, String message) { teams.chat(mc, message); }

    private void forgetAim() {
        yawOffset = pitchOffset = 0;
        target = null;
        releaseTargetId = null;
        probability = Double.NaN;
        currentEv = Double.NaN;
        currentPVoid = 0.0;
        currentVWait = Double.NaN;
        snapCanThrow = false;
        releasePolicy.reset();
    }

    void reset() {
        tracks.clear();
        recentPlaced.clear();
        synchronized (airborneThrows) { airborneThrows.clear(); }
        forgetAim();
        releasedWhileHeld = false;
        world = null;
    }

    void tick(Minecraft mc, boolean replay) {
        if (replay || mc.field_71441_e == null) {
            if (world != null) reset();
            return;
        }
        teams.observe(mc);
        if (world != mc.field_71441_e) {
            reset();
            world = mc.field_71441_e;
        }
        EntityPlayerSP self = mc.field_71439_g;
        if (self == null) return;
        tick++;
        // Reset measured ping on server switch; seed from ServerData.pingToServer & ignore spoofed <=5ms tab pings (Hypixel)
        NetHandlerPlayClient conn = mc.func_147114_u();
        if (conn != lastConn) {
            lastConn = conn;
            livePacketPingMs = 0.0;
            serverListPingMs = 0.0;
            tabPingMs = 0.0;
            lastBowReleaseNs = 0L;
            synchronized (pendingClicks) { pendingClicks.clear(); }
        }
        ServerData serverData = mc.func_147104_D();
        if (serverData != null && serverData.field_78844_e > 5L && serverData.field_78844_e <= 500L) {
            serverListPingMs = serverData.field_78844_e;
        }
        if (conn != null) {
            NetworkPlayerInfo ownInfo = conn.func_175102_a(self.func_110124_au());
            if (ownInfo != null) {
                int ms = ownInfo.func_178853_c();
                if (ms > 5 && ms <= 500) {
                    tabPingMs = tabPingMs <= 0.5 ? ms : 0.92 * tabPingMs + 0.08 * ms;
                }
            }
        }
        // Gather all valid living players (including self) for Priority 1 relational threat features
        List<EntityPlayer> living = new ArrayList<>();
        List<Integer> livingFlags = new ArrayList<>();
        if (self.func_70089_S() && !self.func_175149_v()) {
            living.add(self);
            livingFlags.add(playerFlags(self));
        }
        for (EntityPlayer player : world.field_73010_i) {
            if (!eligible(self, player)) continue;
            living.add(player);
            livingFlags.add(playerFlags(player));
        }
        for (int idx = 0; idx < living.size(); idx++) {
            EntityPlayer player = living.get(idx);
            if (player == self || !teams.opponent(mc, self, player)) continue;
            int stateFlags = livingFlags.get(idx);
            boolean isBlock = (stateFlags & 2) != 0;
            int cue = BridgeMotion.cue(player.func_70093_af(), isBlock, player.field_70125_A);
            double px = player.field_70165_t, py = player.field_70163_u, pz = player.field_70161_v;
            double sx = px, sy = py, sz = pz;
            if (player.field_70118_ct != 0 || player.field_70117_cu != 0 || player.field_70116_cv != 0) {
                double rx = player.field_70118_ct / 32.0, ry = player.field_70117_cu / 32.0, rz = player.field_70116_cv / 32.0;
                if (Math.abs(rx - px) <= 3.5 && Math.abs(ry - py) <= 3.5 && Math.abs(rz - pz) <= 3.5) {
                    sx = rx; sy = ry; sz = rz;
                }
            }
            float[] rel10 = computeRelational(idx, living, livingFlags);
            float[] recentBlk4 = computeRecentBlk4(px, py, pz);
            tracks.computeIfAbsent(player.func_110124_au(), ignored -> new Track()).add(tick,
                    px, py, pz, cue, player.field_70759_as, player.field_70125_A, stateFlags, sx, sy, sz, rel10, recentBlk4);
        }
        tracks.values().removeIf(track -> track.tick() < tick - BowMotion.CAPACITY);
        if (tick % 40 == 0 && !recentPlaced.isEmpty()) {
            int cutoff = tick - RECENT_PLACE_HORIZON;
            recentPlaced.entrySet().removeIf(e -> e.getValue() < cutoff);
        }
        synchronized (airborneThrows) {
            airborneThrows.removeIf(at -> tick - at.thrownTick > BowBallistics.MAX_THROW_TICKS + 4
                    || tick - at.thrownTick > at.shot.time + at.leadShift + 3.0);
        }
        boolean bowActive = drawing(mc, self);
        boolean throwActive = !bowActive && mode != null && throwablesEnabled && holdingThrowable(mc, self);
        if (((mode == null && !intercept) || (!bowActive && !throwActive)) || (motion == null && neural == null && neuralV4 == null)) {
            forgetAim();
            return;
        }
        float rawYaw = self.field_70177_z - yawOffset, rawPitch = pitch(self.field_70125_A - pitchOffset);
        target = choose(mc, self, rawYaw, rawPitch, throwActive);
        if (mode != null) {
            adjust(self, rawYaw, rawPitch, target);
            boolean canEstimate = mode == Mode.ADAPTIVE && target != null
                    && (target.prediction != null || chance != null) && (showChance || autoRelease);
            if (canEstimate) {
                evaluateCurrentAim(mc, self, target);
            } else {
                probability = Double.NaN;
                currentEv = Double.NaN;
                currentPVoid = 0.0;
                currentVWait = Double.NaN;
            }
            if (bowActive) {
                tryAutoRelease(mc, self, mc.field_71474_y.field_74313_G.func_151470_d());
            } else {
                releasePolicy.reset();
                releaseTargetId = null;
            }
        }
        // Snapshot throw state on main thread for lock-free packet observer
        snapTick = tick;
        snapEyeX = self.field_70165_t;
        snapEyeY = self.field_70163_u + self.func_70047_e();
        snapEyeZ = self.field_70161_v;
        snapYaw = self.field_70177_z;
        snapPitch = self.field_70125_A;
        snapThrowTime = (target != null && target.throwable) ? target.ideal.time : 14.0;
        snapLeadShift = leadShiftTicks(mc);
        snapCanThrow = throwActive;
    }

    private static int playerFlags(EntityPlayer player) {
        ItemStack held = player.func_70694_bm();
        Item heldItem = held == null ? null : held.func_77973_b();
        int f = 0;
        if (player.func_70093_af()) f |= 1;
        if (heldItem instanceof ItemBlock) f |= 2;
        if (heldItem instanceof ItemSword) f |= 4;
        if (heldItem instanceof ItemBow) f |= 8;
        if (player.func_71011_bu() != null) f |= 16;
        if (player.field_70737_aN > 0) f |= 32;
        if (player.field_70733_aJ > 0f) f |= 64;
        return f;
    }

    private static float[] computeRelational(int targetIdx, List<EntityPlayer> living, List<Integer> flagsList) {
        EntityPlayer t = living.get(targetIdx);
        double tx = t.field_70165_t, ty = t.field_70163_u, tz = t.field_70161_v;
        double bestNearD2 = 32.0 * 32.0;
        double bestRangedScore = -1.0;
        float[] out = new float[10];
        for (int j = 0; j < living.size(); j++) {
            if (j == targetIdx) continue;
            EntityPlayer o = living.get(j);
            double dx = o.field_70165_t - tx, dy = o.field_70163_u - ty, dz = o.field_70161_v - tz;
            double d2 = dx * dx + dy * dy + dz * dz;
            if (d2 < 0.01) continue;
            double dist = Math.sqrt(d2);
            double oyaw = Math.toRadians(o.field_70759_as);
            double hdist = Math.max(0.1, Math.hypot(dx, dz));
            double lookAtT = (-Math.sin(oyaw) * (-dx) + Math.cos(oyaw) * (-dz)) / hdist;
            int ofl = flagsList.get(j);

            if (d2 < bestNearD2) {
                bestNearD2 = d2;
                double threat = ((ofl & 4) != 0 ? 0.4 : 0.0) + ((ofl & 64) != 0 ? 0.35 : 0.0)
                        + (lookAtT > 0.7 ? 0.25 : 0.0);
                out[0] = (float)dx; out[1] = (float)dy; out[2] = (float)dz;
                out[3] = (float)(1.0 / (1.0 + dist / 6.0));
                out[4] = (float)threat;
            }
            if (dist >= 5.0 && dist <= 56.0) {
                boolean isBow = (ofl & 8) != 0;
                boolean isDraw = isBow && (ofl & 16) != 0;
                double score = (isDraw ? 3.0 : isBow ? 1.5 : 0.3) * Math.max(0.1, lookAtT + 0.3) / (1.0 + dist / 24.0);
                if (score > bestRangedScore) {
                    bestRangedScore = score;
                    double rThreat = (isDraw ? 1.0 : isBow ? 0.5 : 0.15) * Math.max(0.0, lookAtT);
                    out[5] = (float)dx; out[6] = (float)dy; out[7] = (float)dz;
                    out[8] = (float)(1.0 / (1.0 + dist / 16.0));
                    out[9] = (float)rThreat;
                }
            }
        }
        return out;
    }

    void startTick(Minecraft mc) {
        if (!releasedWhileHeld) return;
        KeyBinding use = mc.field_71474_y.field_74313_G;
        ItemStack held = mc.field_71439_g == null ? null : mc.field_71439_g.func_70694_bm();
        if (held == null || !(held.func_77973_b() instanceof ItemBow) || !PracticeBridge.physical(use.func_151463_i())) {
            releasedWhileHeld = false;
            return;
        }
        clearUse(use);
    }

    private static void clearUse(KeyBinding use) {
        KeyBinding.func_74510_a(use.func_151463_i(), false);
        while (use.func_151468_f()) { /* discard any queued repeats for this held draw */ }
    }

    private void tryAutoRelease(Minecraft mc, EntityPlayerSP self, boolean holdingUse) {
        if (mode != Mode.ADAPTIVE || !autoRelease || releasedWhileHeld || !holdingUse || target == null
                || self.func_71057_bx() < 20) {
            releasePolicy.reset();
            releaseTargetId = null;
            return;
        }
        UUID id = target.player.func_110124_au();
        if (!id.equals(releaseTargetId)) {
            releaseTargetId = id;
            releasePolicy.reset();
        }
        // Wait 1-2 ticks if aim is still slewing toward ideal (unconstrained by limit) so we never fire mid-slew
        double residualDeg = Math.hypot(wrap(target.ideal.yaw - self.field_70177_z),
                target.ideal.pitch - self.field_70125_A);
        double appliedDeg = Math.hypot(yawOffset, pitchOffset);
        if (residualDeg > 0.9 && appliedDeg < limit - 0.15) return;

        boolean losingCover = false;
        if ((releaseMode == ReleaseMode.SMART || releaseMode == ReleaseMode.EV)
                && target.prediction != null && world != null) {
            double[] ahead = target.prediction.meanPath[Math.min(4, target.prediction.meanPath.length - 1)];
            Vec3 eye = new Vec3(self.field_70165_t, self.field_70163_u + self.func_70047_e(), self.field_70161_v);
            Vec3 futurePt = new Vec3(ahead[0], ahead[1] + target.bodyHeight, ahead[2]);
            losingCover = world.func_147447_a(eye, futurePt, false, true, false) != null;
        }
        boolean fire;
        if (releaseMode == ReleaseMode.EV && waitModel != null && Double.isFinite(currentEv) && Double.isFinite(currentVWait)) {
            fire = releasePolicy.shouldFireEv(currentEv, currentVWait, probability, threshold, losingCover,
                    waitModel.fireAlpha());
        } else {
            ReleaseMode fallback = releaseMode == ReleaseMode.EV ? ReleaseMode.SMART : releaseMode;
            fire = releasePolicy.shouldFire(probability, threshold, fallback, losingCover);
        }
        if (!fire) return;
        releasedWhileHeld = true;
        autoFiring = true;
        try {
            mc.field_71442_b.func_78766_c(self);
        } finally {
            autoFiring = false;
            releasePolicy.reset();
            clearUse(mc.field_71474_y.field_74313_G);
        }
    }

    private void evaluateCurrentAim(Minecraft mc, EntityPlayerSP self, Selection selected) {
        double speed = selected.throwable ? BowBallistics.THROW_SPEED
                : (autoRelease ? 3.0 : BowBallistics.speed(self.func_71057_bx()));
        double gravity = selected.throwable ? BowBallistics.THROW_GRAVITY : BowBallistics.GRAVITY;
        double eyeY = self.field_70163_u + self.func_70047_e();
        Shot shot = BowBallistics.fromAim(self.field_70165_t, eyeY,
                self.field_70161_v, speed, self.field_70177_z, self.field_70125_A, gravity);
        double ideal = selected.ideal.time;
        int maxFlight = selected.throwable ? BowBallistics.MAX_THROW_TICKS : BowBallistics.MAX_TICKS;
        double flight = Math.min(maxFlight, ideal + Math.max(4, ideal * .30));
        BowAimPreview.Guide guide = BowAimPreview.trace(world, shot, flight, null);
        if (selected.prediction != null && (neuralV4 != null || neural != null)) {
            boolean obstructed = guide.blocked && guide.travelTicks < ideal - 0.5;
            double shift = selected.leadShift;
            if (obstructed) {
                probability = 0.0;
                currentEv = 0.0;
                currentPVoid = 0.0;
            } else {
                Shot timed = BowBallistics.withTime(shot, ideal);
                double[] mp = neuralV4 != null
                        ? neuralV4.modeHitProbs(timed, selected.prediction, shift)
                        : neural.modeHitProbs(timed, selected.prediction, shift);
                double rawP = 0.0;
                double rawEv = 0.0;
                double rawPVoid = 0.0;
                int K = selected.prediction.weights.length;
                for (int k = 0; k < K; k++) {
                    double w = selected.prediction.weights[k];
                    double wEff = (selected.modeUncovered != null && k < selected.modeUncovered.length)
                            ? w * selected.modeUncovered[k] : w;
                    double val = (selected.modeValues != null && k < selected.modeValues.length)
                            ? selected.modeValues[k] : (selected.throwable ? VoidKbValue.THROW_BASE : VoidKbValue.BOW_BASE);
                    double vp = (selected.modeVoids != null && k < selected.modeVoids.length)
                            ? selected.modeVoids[k] : 0.0;
                    double mkP = mp != null ? mp[k] : 0.0;
                    rawP += w * mkP;
                    rawEv += wEff * mkP * val;
                    rawPVoid += w * mkP * vp;
                }
                double clampedP = Math.max(0.0, Math.min(1.0, rawP));
                double calP = neuralV4 != null ? neuralV4.calibrate(clampedP) : neural.calibrate(clampedP);
                double calScale = rawP > 1e-4 ? calP / rawP : 1.0;
                probability = calP;
                currentEv = rawEv * calScale;
                currentPVoid = Math.max(0.0, Math.min(1.0, rawPVoid * calScale));
            }
            if (waitModel != null) {
                currentVWait = waitModel.predictWaitEv(selected.prediction,
                        self.field_70165_t, eyeY, self.field_70161_v,
                        selected.player.field_70165_t, selected.player.field_70163_u, selected.player.field_70161_v,
                        currentEv, probability, currentPVoid, selected.throwable, !obstructed);
            } else {
                currentVWait = Double.NaN;
            }
            return;
        }
        probability = chance != null ? chance.estimate(tracks.get(selected.player.func_110124_au()),
                selected.building, shot, ideal, guide.travelTicks, selected.path) : Double.NaN;
        currentEv = Double.isFinite(probability) ? probability : Double.NaN;
        currentPVoid = 0.0;
        currentVWait = Double.NaN;
    }

    private static boolean eligible(EntityPlayer self, EntityPlayer other) {
        UUID uuid = other.func_110124_au();
        return self != other && other.func_70089_S() && !other.func_175149_v() && !other.func_82150_aj()
                && uuid != null && uuid.version() == 4;
    }

    private static boolean drawing(Minecraft mc, EntityPlayerSP self) {
        ItemStack inUse = self.func_71011_bu();
        return mc.field_71462_r == null && mc.field_71415_G && self.func_70089_S()
                && !self.func_175149_v() && !self.func_70090_H()
                && inUse != null && inUse.func_77973_b() instanceof ItemBow;
    }

    private static boolean holdingThrowable(Minecraft mc, EntityPlayerSP self) {
        ItemStack held = self.func_70694_bm();
        return mc.field_71462_r == null && mc.field_71415_G && self.func_70089_S()
                && !self.func_175149_v() && !self.func_70090_H()
                && self.func_71011_bu() == null
                && held != null && (held.func_77973_b() instanceof ItemSnowball || held.func_77973_b() instanceof ItemEgg);
    }

    private Selection choose(Minecraft mc, EntityPlayerSP self, float yaw, float pitch, boolean isThrowable) {
        // When autoRelease is enabled, pre-aim for full-draw speed (3.0) during charge so pitch never lags at tick 20
        double speed = isThrowable ? BowBallistics.THROW_SPEED
                : (autoRelease ? 3.0 : BowBallistics.speed(self.func_71057_bx()));
        double gravity = isThrowable ? BowBallistics.THROW_GRAVITY : BowBallistics.GRAVITY;
        if (speed <= 0) return null;
        ItemStack held = isThrowable ? self.func_70694_bm() : self.func_71011_bu();
        int punchLevel = (!isThrowable && held != null) ? EnchantmentHelper.func_77506_a(49, held) : 0;
        int powerLevel = (!isThrowable && held != null) ? EnchantmentHelper.func_77506_a(48, held) : 0;
        double maxRange = isThrowable ? THROW_RANGE : RANGE;
        double eyeY = self.field_70163_u + self.func_70047_e();
        double yr = Math.toRadians(yaw), pr = Math.toRadians(pitch);
        Vec3 from = new Vec3(self.field_70165_t, eyeY, self.field_70161_v);
        Vec3 to = new Vec3(from.field_72450_a - Math.sin(yr) * Math.cos(pr) * maxRange,
                eyeY - Math.sin(pr) * maxRange, from.field_72449_c + Math.cos(yr) * Math.cos(pr) * maxRange);
        MovingObjectPosition wall = world.func_147447_a(from, to, false, true, false);
        if (wall != null && wall.field_72307_f != null) to = wall.field_72307_f;
        EntityPlayer pointedAt = null;
        double first = from.func_72438_d(to);
        for (EntityPlayer other : world.field_73010_i) {
            if (!eligible(self, other)) continue;
            MovingObjectPosition hit = other.func_174813_aQ().func_72327_a(from, to);
            if (hit == null) continue;
            double distance = from.func_72438_d(hit.field_72307_f);
            if (distance < first) { first = distance; pointedAt = other; }
        }
        if (pointedAt != null) return solution(mc, self, pointedAt, speed, gravity, eyeY, isThrowable, punchLevel, powerLevel);

        Selection best = null;
        double bestScore = Double.POSITIVE_INFINITY, bestDistance = Double.POSITIVE_INFINITY;
        double bearingGate = isThrowable ? 38.0 : 26.0;
        for (EntityPlayer other : world.field_73010_i) {
            if (!eligible(self, other)) continue;
            double dx = other.field_70165_t - from.field_72450_a, dz = other.field_70161_v - from.field_72449_c;
            double dy = other.field_70163_u + .9 - eyeY;
            double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (distance < 2 || distance > maxRange) continue;
            double bearing = Math.toDegrees(Math.atan2(-dx, dz));
            if (Math.abs(wrap(bearing - yaw)) > bearingGate) continue;
            Selection candidate = solution(mc, self, other, speed, gravity, eyeY, isThrowable, punchLevel, powerLevel);
            if (candidate == null) continue;
            double angleIdeal = Math.hypot(wrap(candidate.ideal.yaw - yaw), candidate.ideal.pitch - pitch);
            double nowPitch = Math.toDegrees(-Math.atan2(dy, Math.hypot(dx, dz)));
            double angleNow = Math.hypot(wrap(bearing - yaw), nowPitch - pitch);
            // Lock whether aiming near the predicted lead OR directly near the target (crucial for long snowball leads!)
            double angle = Math.min(angleIdeal, angleNow);
            double score = angle - Math.min(2.2, 0.35 * candidate.ev);
            if (angle <= NEAR_AIM && (best == null || score < bestScore - .35
                    || Math.abs(score - bestScore) <= .35 && distance < bestDistance)) {
                best = candidate;
                bestScore = score;
                bestDistance = distance;
            }
        }
        return best;
    }

    /** Sample BOTH fine 9x7x9 (+-4b) and coarse 11x5x11 (2x2x2 max-pooled over 22x10x22, +-11b) in one pass. */
    private byte[][] sampleMultiScaleVoxels(double x, double y, double z) {
        byte[] fine = new byte[BowNeuralMotion.FINE_DIM];
        byte[] coarse = new byte[BowNeuralMotion.COARSE_DIM];
        if (world == null) return new byte[][]{fine, coarse};
        int bx = (int)Math.floor(x), by = (int)Math.floor(y), bz = (int)Math.floor(z);
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int cy = 0; cy < 10; cy++) {
            int wy = by - 4 + cy;
            if (wy < 0 || wy >= 256) continue;
            int fy = cy - 1;
            int coarseYOff = (cy >> 1) * 121;
            for (int cz = 0; cz < 22; cz++) {
                int wz = bz - 10 + cz;
                int fz = cz - 6;
                int coarseZOff = coarseYOff + (cz >> 1) * 11;
                for (int cx = 0; cx < 22; cx++) {
                    int wx = bx - 10 + cx;
                    cursor.func_181079_c(wx, wy, wz);
                    Block block = world.func_180495_p(cursor).func_177230_c();
                    if (block != null && block.func_180646_a(world, cursor) != null) {
                        coarse[coarseZOff + (cx >> 1)] = 1;
                        int fx = cx - 6;
                        if (fy >= 0 && fy < 7 && fz >= 0 && fz < 9 && fx >= 0 && fx < 9) {
                            fine[fy * 81 + fz * 9 + fx] = 1;
                        }
                    }
                }
            }
        }
        return new byte[][]{fine, coarse};
    }

    private float[] computeRecentBlk4(double px, double py, double pz) {
        float[] out = new float[4];
        if (recentPlaced.isEmpty()) return out;
        int bx = (int)Math.floor(px), by = (int)Math.floor(py), bz = (int)Math.floor(pz);
        int cnt = 0;
        double sdx = 0, sdy = 0, sdz = 0;
        for (Map.Entry<Long, Integer> e : recentPlaced.entrySet()) {
            int age = tick - e.getValue();
            if (age < 0 || age > 10) continue;
            long k = e.getKey();
            int dx = unpackX(k) - bx, dy = unpackY(k) - by, dz = unpackZ(k) - bz;
            if (dx >= -3 && dx <= 3 && dz >= -3 && dz <= 3 && dy >= -2 && dy <= 2) {
                cnt++; sdx += dx; sdy += dy; sdz += dz;
            }
        }
        if (cnt > 0) {
            out[0] = (float)Math.min(1.0, cnt / 4.0);
            out[1] = (float)(sdx / cnt);
            out[2] = (float)(sdy / cnt);
            out[3] = (float)(sdz / cnt);
        }
        return out;
    }

    /** Sample v4 spatial inputs: [0]=fineSolid(567), [1]=finePlaced(567), [2]=top25x25x4(2500 int8). */
    private byte[][] sampleV4Spatial(double x, double y, double z) {
        byte[] fineSolid = new byte[BowNeuralV4.FINE_DIM];
        byte[] finePlaced = new byte[BowNeuralV4.FINE_DIM];
        byte[] top = new byte[BowNeuralV4.TOP_DIM];
        int plane = BowNeuralV4.TOP_S * BowNeuralV4.TOP_S;
        for (int i = 0; i < plane; i++) {
            top[i] = -127;          // no floor
            top[2 * plane + i] = 100; // void until solid found
        }
        if (world == null) return new byte[][]{fineSolid, finePlaced, top};
        int bx = (int)Math.floor(x), by = (int)Math.floor(y), bz = (int)Math.floor(z);
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int tz = 0; tz < 25; tz++) {
            int wz = bz - 12 + tz;
            int fz = tz - 8;
            int rowOff = tz * 25;
            for (int tx = 0; tx < 25; tx++) {
                int wx = bx - 12 + tx;
                int fx = tx - 8;
                int cell = rowOff + tx;
                boolean inFineXZ = (fx >= 0 && fx < 9 && fz >= 0 && fz < 9);
                boolean foundFloor = false;
                boolean foundAny = false;
                for (int dy = 4; dy >= -12; dy--) {
                    int wy = by + dy;
                    if (wy < 0 || wy >= 256) continue;
                    // Once we've scanned past fine Y (-3) and already found floor + any solid, skip deeper void scan!
                    if (dy < -3 && foundAny && foundFloor) break;
                    cursor.func_181079_c(wx, wy, wz);
                    Block block = world.func_180495_p(cursor).func_177230_c();
                    if (block != null && block.func_180646_a(world, cursor) != null) {
                        foundAny = true;
                        top[2 * plane + cell] = 0;
                        if (dy == 1 || dy == 2) top[plane + cell] = 100;
                        if (!foundFloor && dy >= -6) {
                            foundFloor = true;
                            top[cell] = (byte)Math.max(-120, Math.min(100, (dy + 1) * 20));
                        }
                        if (inFineXZ && dy >= -3 && dy <= 3) {
                            fineSolid[(dy + 3) * 81 + fz * 9 + fx] = 1;
                        }
                    }
                }
            }
        }
        if (!recentPlaced.isEmpty()) {
            int cutoff = tick - RECENT_PLACE_HORIZON;
            for (Map.Entry<Long, Integer> e : recentPlaced.entrySet()) {
                int pt = e.getValue();
                if (pt < cutoff) continue;
                long k = e.getKey();
                int dx = unpackX(k) - bx, dy = unpackY(k) - by, dz = unpackZ(k) - bz;
                if (dx >= -12 && dx <= 12 && dz >= -12 && dz <= 12 && dy >= -4 && dy <= 3) {
                    int age = Math.max(0, tick - pt);
                    byte strength = (byte)Math.round(100.0 * Math.exp(-age / 60.0));
                    int cell = (dz + 12) * 25 + (dx + 12);
                    if (strength > top[3 * plane + cell]) top[3 * plane + cell] = strength;
                    if (dx >= -4 && dx <= 4 && dz >= -4 && dz <= 4 && dy >= -3 && dy <= 3) {
                        finePlaced[(dy + 3) * 81 + (dz + 4) * 9 + (dx + 4)] = 1;
                    }
                }
            }
        }
        return new byte[][]{fineSolid, finePlaced, top};
    }

    private double[] computeModeUncovered(BowNeuralMotion.Prediction pred, boolean isThrowable) {
        int K = pred.weights.length;
        double[] uncovered = new double[K];
        for (int k = 0; k < K; k++) uncovered[k] = 1.0;
        if (!isThrowable || (neuralV4 == null && neural == null)) return uncovered;
        List<AirborneThrow> snapshot;
        synchronized (airborneThrows) {
            if (airborneThrows.isEmpty()) return uncovered;
            snapshot = new ArrayList<>(airborneThrows);
        }
        int maxAllowed = Math.max(pred.maxHorizon(), BowBallistics.MAX_THROW_TICKS);
        for (AirborneThrow at : snapshot) {
            double rem = (at.thrownTick + at.leadShift + at.shot.time) - tick;
            if (rem <= 0.5 || rem > maxAllowed) continue;
            for (int k = 0; k < K; k++) {
                double pCov = neuralV4 != null
                        ? neuralV4.modeHitProbAt(at.shot, pred, k, rem)
                        : neural.modeHitProbAt(at.shot, pred, k, rem);
                uncovered[k] *= Math.max(0.0, 1.0 - pCov);
            }
        }
        return uncovered;
    }

    private void scoreModesForShot(Shot shot, BowNeuralMotion.Prediction pred, double shift,
                                   boolean isThrowable, int punchLevel, double targetHealth,
                                   double expectedBowDmg, double[] outVals, double[] outVoids) {
        int K = pred.weights.length;
        int maxBase = pred.maxHorizon();
        int maxAllowed = isThrowable ? Math.max(maxBase, BowBallistics.MAX_THROW_TICKS) : maxBase;
        double ivx = shot.vxAt(shot.time), ivz = shot.vzAt(shot.time);
        double tEval = Math.min(maxAllowed, shot.time + Math.max(0.0, shift));
        for (int k = 0; k < K; k++) {
            double mx, my, mz, sx, sz, tvx, tvy, tvz;
            if (tEval <= maxBase) {
                int lo = (int)tEval, hi = Math.min(maxBase, lo + 1);
                double frac = tEval - lo;
                int prevT = Math.max(0, lo - 1);
                int dt = Math.max(1, hi - prevT);
                double[] pLo = pred.paths[k][lo], pHi = pred.paths[k][hi];
                double[] sLo = pred.stds[k][lo], sHi = pred.stds[k][hi];
                mx = pLo[0] + frac * (pHi[0] - pLo[0]);
                my = pLo[1] + frac * (pHi[1] - pLo[1]);
                mz = pLo[2] + frac * (pHi[2] - pLo[2]);
                sx = sLo[0] + frac * (sHi[0] - sLo[0]);
                sz = sLo[2] + frac * (sHi[2] - sLo[2]);
                double[] pPrev = pred.paths[k][prevT];
                tvx = (pHi[0] - pPrev[0]) / dt;
                tvy = (pHi[1] - pPrev[1]) / dt;
                tvz = (pHi[2] - pPrev[2]) / dt;
            } else {
                double extra = tEval - maxBase;
                double[] pLast = pred.paths[k][maxBase], pRef = pred.paths[k][maxBase - 6];
                tvx = (pLast[0] - pRef[0]) / 6.0;
                tvy = (pLast[1] - pRef[1]) / 6.0;
                tvz = (pLast[2] - pRef[2]) / 6.0;
                mx = pLast[0] + tvx * extra;
                my = pLast[1] + tvy * extra;
                mz = pLast[2] + tvz * extra;
                double scale = 1.0 + extra / (double)maxBase;
                sx = pred.stds[k][maxBase][0] * scale;
                sz = pred.stds[k][maxBase][2] * scale;
            }
            VoidKbValue.Score sc = VoidKbValue.scoreMode(mx, my, mz, tvx, tvy, tvz, sx, sz, ivx, ivz,
                    isThrowable, punchLevel, targetHealth, expectedBowDmg, solidQuery);
            outVoids[k] = sc.voidProb;
            outVals[k] = sc.value;
        }
    }

    private Selection solution(Minecraft mc, EntityPlayerSP self, EntityPlayer player,
                               double speed, double gravity, double eyeY,
                               boolean isThrowable, int punchLevel, int powerLevel) {
        if (!teams.opponent(mc, self, player)) return null;
        Track track = tracks.get(player.func_110124_au());
        if (track == null || track.tick() != tick || track.length() < 2) return null;
        Mode chosen = mode == null ? Mode.ADAPTIVE : mode;
        AxisAlignedBB bounds = player.func_174813_aQ();
        double height = bounds.field_72337_e - bounds.field_72338_b;

        // BowAim Neural (v4 BNN4 or v2/v3 BNN2) + Exact 1.8.9 Void-KB EV + Snowball Volley Coverage
        if (chosen == Mode.ADAPTIVE && (neuralV4 != null || neural != null) && !legacyAdaptive) {
            BowNeuralMotion.Prediction pred;
            if (neuralV4 != null) {
                byte[][] sp = sampleV4Spatial(player.field_70165_t, player.field_70163_u, player.field_70161_v);
                pred = neuralV4.predict(track, sp[0], sp[1], sp[2], true, bridgeHack);
            } else {
                byte[][] vox = sampleMultiScaleVoxels(player.field_70165_t, player.field_70163_u, player.field_70161_v);
                pred = neural.predict(track, vox[0], vox[1], true, bridgeHack);
            }
            double shift = leadShiftTicks(mc);
            int K = pred.weights.length;
            double[] uncovered = computeModeUncovered(pred, isThrowable);
            double[] effWeights = new double[K];
            for (int k = 0; k < K; k++) effWeights[k] = pred.weights[k] * uncovered[k];

            double targetHealth = player.func_110143_aJ();
            double expectedBowDmg = isThrowable ? 0.0 : VoidKbValue.expectedBowDamage(speed, powerLevel);

            Shot bestShot = null;
            double[][] bestPath = null;
            double bestHeight = height * AIM_HEIGHTS[0];
            double bestEv = -1.0;
            double[] bestVals = new double[K];
            double[] bestVoids = new double[K];
            boolean valsScored = false;

            int maxTicks = isThrowable ? Math.max(pred.maxHorizon(), BowBallistics.MAX_THROW_TICKS) : BowBallistics.MAX_TICKS;
            int totalCandidates = K + 1;
            for (int c = 0; c < totalCandidates; c++) {
                if (c < K && effWeights[c] < 0.02) continue;
                double[][] rawPath = c < K ? pred.paths[c] : pred.meanPath;
                double[][] candPath = BowNeuralMotion.shiftAndExtendPath(rawPath, shift, maxTicks);
                for (double fraction : AIM_HEIGHTS) {
                    double aimAt = height * fraction;
                    Shot shot = BowBallistics.intercept(self.field_70165_t, eyeY, self.field_70161_v,
                            speed, candPath, aimAt, gravity);
                    if (shot != null && !BowAimPreview.trace(world, shot, shot.time, null).blocked) {
                        if (!valsScored) {
                            scoreModesForShot(shot, pred, shift, isThrowable, punchLevel, targetHealth,
                                    expectedBowDmg, bestVals, bestVoids);
                            valsScored = true;
                        }
                        double ev = neuralV4 != null
                                ? neuralV4.expectedValue(shot, pred, shift, effWeights, bestVals)
                                : neural.expectedValue(shot, pred, shift, effWeights, bestVals);
                        if (ev > bestEv) {
                            bestEv = ev;
                            bestShot = shot;
                            bestPath = candPath;
                            bestHeight = aimAt;
                        }
                        break;
                    }
                }
            }
            if (bestShot != null) {
                Shot refined = neuralV4 != null
                        ? neuralV4.refineShotEv(self.field_70165_t, eyeY, self.field_70161_v, speed,
                                bestShot, pred, shift, effWeights, bestVals,
                                cand -> !BowAimPreview.trace(world, cand, cand.time, null).blocked)
                        : neural.refineShotEv(self.field_70165_t, eyeY, self.field_70161_v, speed,
                                bestShot, pred, shift, effWeights, bestVals,
                                cand -> !BowAimPreview.trace(world, cand, cand.time, null).blocked);
                double[] mp = neuralV4 != null
                        ? neuralV4.modeHitProbs(refined, pred, shift)
                        : neural.modeHitProbs(refined, pred, shift);
                double evFin = 0.0, pVoidFin = 0.0;
                int aimedMode = 0;
                double bestModeContrib = -1.0;
                for (int k = 0; k < K; k++) {
                    double mkP = mp != null ? mp[k] : 0.0;
                    double contrib = effWeights[k] * mkP * bestVals[k];
                    evFin += contrib;
                    pVoidFin += pred.weights[k] * mkP * bestVoids[k];
                    if (contrib > bestModeContrib) {
                        bestModeContrib = contrib;
                        aimedMode = k;
                    }
                }
                return new Selection(player, refined, bestPath, pred, false, bestHeight, isThrowable,
                        evFin, Math.min(1.0, pVoidFin), shift, bestVals, bestVoids, uncovered, aimedMode);
            }
            return null;
        }

        if (motion == null) return null;
        double[][] base = motion.path(track, chosen, BowBallistics.MAX_TICKS);
        double[][] path = bridge != null && chosen == Mode.ADAPTIVE ? bridge.apply(track, base) : base;
        for (double fraction : AIM_HEIGHTS) {
            double aimAt = height * fraction;
            Shot shot = BowBallistics.intercept(self.field_70165_t, eyeY, self.field_70161_v,
                    speed, path, aimAt, gravity);
            if (shot != null && !BowAimPreview.trace(world, shot, shot.time, null).blocked)
                return new Selection(player, shot, path, null, path != base, aimAt, isThrowable,
                        0.0, 0.0, 0.0, null, null, null, 0);
        }
        return null;
    }

    private void adjust(EntityPlayerSP self, float yaw, float pitch, Selection selected) {
        double dy = selected == null ? 0 : wrap(selected.ideal.yaw - yaw);
        double dp = selected == null ? 0 : selected.ideal.pitch - pitch;
        double scale = Math.min(1, limit / Math.max(1e-9, Math.hypot(dy, dp)));
        dy *= scale;
        dp *= scale;
        double turnSpeed = (selected != null && selected.throwable) ? THROW_TURN_PER_TICK : BOW_TURN_PER_TICK;
        double step = selected == null ? 1 : Math.min(1, turnSpeed / Math.max(1e-9,
                Math.hypot(dy - yawOffset, dp - pitchOffset)));
        dy = yawOffset + step * (dy - yawOffset);
        dp = pitchOffset + step * (dp - pitchOffset);
        scale = Math.min(1, limit / Math.max(1e-9, Math.hypot(dy, dp)));
        yawOffset = (float)(dy * scale);
        pitchOffset = pitch((float)(pitch + dp * scale)) - pitch;
        self.field_70177_z = yaw + yawOffset;
        self.field_70125_A = pitch + pitchOffset;
    }

    private static double wrap(double angle) {
        angle %= 360;
        if (angle >= 180) angle -= 360;
        if (angle < -180) angle += 360;
        return angle;
    }

    private static float pitch(float angle) { return Math.max(-90, Math.min(90, angle)); }

    void hud(Minecraft mc, int width, int height) {
        EntityPlayerSP self = mc.field_71439_g;
        if (!showChance || mode == null || !Double.isFinite(probability) || self == null) return;
        boolean bowActive = drawing(mc, self);
        boolean throwActive = !bowActive && throwablesEnabled && holdingThrowable(mc, self) && target != null;
        if (!bowActive && !throwActive) return;

        int pct = (int)Math.floor(probability * 100.0);
        String text;
        int color;
        if (Double.isFinite(currentEv) && target != null && target.prediction != null) {
            boolean voidShot = currentPVoid >= 0.12 || currentEv >= 2.0;
            double waitBar = waitModel != null && Double.isFinite(currentVWait)
                    ? waitModel.fireAlpha() * currentVWait : Double.NaN;
            boolean shouldWait = bowActive && Double.isFinite(waitBar)
                    && waitBar > Math.max(0.45, currentEv * 1.15) && currentEv < 3.2;
            boolean fireReady = (bowActive && autoRelease && releasePolicy.isSpiking())
                    || (bowActive && Double.isFinite(waitBar) && currentEv >= waitBar && probability >= 0.25)
                    || (voidShot && probability >= 0.45);
            if (shouldWait) {
                text = String.format(Locale.ROOT, "NOW %.1fx · WAIT %.1fx", currentEv, currentVWait);
                color = 0xFFFFB86C;
            } else if (voidShot) {
                text = String.format(Locale.ROOT, "%.1fx VOID (%d%%)", currentEv, pct) + (fireReady ? " ▲" : "");
                color = fireReady ? 0xFFFFDC84 : 0xFFFF8E53;
            } else {
                text = String.format(Locale.ROOT, "%.1fx (%d%%)", currentEv, pct) + (fireReady ? " ▲" : "");
                color = fireReady ? 0xFF7CF5A8 : 0xFFB7EEFF;
            }
        } else {
            boolean smartSpiking = autoRelease && releaseMode == ReleaseMode.SMART && releasePolicy.isSpiking();
            text = pct + "%" + (smartSpiking ? " ▲" : "");
            color = autoRelease && probability * 100 >= threshold ? 0xFFFFDC84
                    : smartSpiking ? 0xFF7CF5A8 : 0xFFB7EEFF;
        }
        int y = height / 2 - 18, textWidth = mc.field_71466_p.func_78256_a(text);
        int x = (width - textWidth) / 2;
        Gui.func_73734_a(x - 3, y - 2, x + textWidth + 3, y + 10, 0xA5111A24);
        mc.field_71466_p.func_175063_a(text, x, y, color);
    }

    void draw(Minecraft mc, float partial) {
        boolean path = trajectory == Trajectory.ON || trajectory == Trajectory.AUTO && mode != null;
        EntityPlayerSP self = mc.field_71439_g;
        if (self == null || mc.field_71441_e == null) return;
        boolean bowActive = drawing(mc, self);
        boolean throwActive = !bowActive && mode != null && throwablesEnabled && holdingThrowable(mc, self) && target != null;
        if ((!path && !intercept && !ghostsEnabled) || (!bowActive && !throwActive)) return;
        double speed = throwActive ? BowBallistics.THROW_SPEED
                : (autoRelease ? 3.0 : BowBallistics.speed(self.func_71057_bx()));
        if (speed <= 0) return;
        double gravity = throwActive ? BowBallistics.THROW_GRAVITY : BowBallistics.GRAVITY;
        double x = self.field_70142_S + (self.field_70165_t - self.field_70142_S) * partial;
        double y = self.field_70137_T + (self.field_70163_u - self.field_70137_T) * partial + self.func_70047_e();
        double z = self.field_70136_U + (self.field_70161_v - self.field_70136_U) * partial;
        Shot current = BowBallistics.fromAim(x, y, z, speed, self.field_70177_z, self.field_70125_A, gravity);
        Selection forecast = world == mc.field_71441_e && (mode != null || intercept) ? target : null;
        boolean showGhosts = ghostsEnabled && mode != null && forecast != null && forecast.prediction != null;
        BowAimPreview.draw(mc, BowAimPreview.trace(mc.field_71441_e, current, current.time, forecast),
                path, intercept, showGhosts, forecast, partial);
    }

    /** Send the displayed correction before the normal release request. */
    public static void beforeRelease(Object player) {
        BowAim aim = hooks;
        if (aim == null || aim.mode == null) return;
        Minecraft mc = Minecraft.func_71410_x();
        EntityPlayerSP self = mc.field_71439_g;
        if (self == null || self != player || mc.field_71441_e != aim.world || !drawing(mc, self)) return;
        boolean nudged = aim.yawOffset != 0 || aim.pitchOffset != 0;
        boolean recording = aim.capture.remoteLog() != null;
        if (!nudged && !recording && !aim.autoFiring) return;
        aim.teams.observe(mc);
        float yaw = self.field_70177_z - aim.yawOffset, pitch = pitch(self.field_70125_A - aim.pitchOffset);
        Selection current = aim.choose(mc, self, yaw, pitch, false);
        if (current == null || aim.target == null || current.player != aim.target.player) {
            self.field_70177_z = yaw;
            self.field_70125_A = pitch;
        }
        if (recording) aim.recordRelease(self, current, yaw, pitch);
        NetHandlerPlayClient connection = mc.func_147114_u();
        if ((nudged || aim.autoFiring) && connection != null)
            connection.func_147297_a(new C05PacketPlayerLook(self.field_70177_z, self.field_70125_A, self.field_70122_E));
        aim.forgetAim();
    }

    private void recordRelease(EntityPlayerSP self, Selection current, float yaw, float pitch) {
        int charge = self.func_71057_bx();
        double speed = BowBallistics.speed(charge);
        Shot shot = BowBallistics.fromAim(self.field_70165_t, self.field_70163_u + self.func_70047_e(),
                self.field_70161_v, speed, self.field_70177_z, self.field_70125_A);
        BowAimPreview.Guide guide = BowAimPreview.trace(world, shot, shot.time, current);
        capture.inputEvent("bow_aim_release", practice.core.JsonData.map(
                "mode", mode.name(), "draw_ticks", charge, "correction_limit", limit,
                "raw_yaw", yaw, "raw_pitch", pitch, "yaw", self.field_70177_z, "pitch", self.field_70125_A,
                "target", current == null ? null : current.player.func_145782_y(),
                "bridge_specialist", current != null && current.building,
                "ideal_yaw", current == null ? null : current.ideal.yaw,
                "ideal_pitch", current == null ? null : current.ideal.pitch,
                "aim_height", current == null ? null : current.bodyHeight,
                "automatic", autoFiring, "estimated_chance", Double.isFinite(probability) ? probability : null,
                "predicted_contact_ticks", guide.feet == null ? null : guide.hitTicks,
                "trajectory_stopped", guide.blocked));
    }
}
