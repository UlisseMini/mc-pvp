package practice.bridge;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemBow;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemSword;
import net.minecraft.network.play.client.C03PacketPlayer.C05PacketPlayerLook;
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
import practice.core.bow.BowReleasePolicy;
import practice.core.bow.BowReleasePolicy.ReleaseMode;
import practice.core.bow.BridgeMotion;
import practice.core.bow.BowHitChance;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

/** Capped aim correction and an independent bow trajectory overlay. */
public final class BowAim {
    static BowAim hooks;
    private static final double RANGE = 65, NEAR_AIM = 8, TURN_PER_TICK = 1;
    private static final double[] AIM_HEIGHTS = {.50, .76, .88, .32};
    private static final int DEFAULT_LIMIT = 3;
    private final Map<UUID, Track> tracks = new HashMap<>();
    private final BowMotion motion;
    private final BridgeMotion bridge;
    private final BowHitChance chance;
    private final BowNeuralMotion neural;
    private final BowReleasePolicy releasePolicy = new BowReleasePolicy();
    private final PracticeCapture capture;
    private final BowAimTeams teams = new BowAimTeams();
    private final Path settings;
    private WorldClient world;
    private Mode mode;
    private boolean legacyAdaptive;
    private int tick, limit = DEFAULT_LIMIT;
    private Selection target;
    private UUID releaseTargetId;
    private float yawOffset, pitchOffset;
    private boolean intercept;
    private boolean showChance = true, autoRelease, releasedWhileHeld, autoFiring;
    private ReleaseMode releaseMode = ReleaseMode.SMART;
    private double probability = Double.NaN;
    private int threshold = 75;
    private Trajectory trajectory = Trajectory.AUTO;

    private enum Trajectory { AUTO, ON, OFF }

    static final class Selection {
        final EntityPlayer player;
        final Shot ideal;
        final double[][] path;
        final BowNeuralMotion.Prediction prediction;
        final AxisAlignedBB bounds;
        final boolean building;
        final double bodyHeight;

        Selection(EntityPlayer player, Shot ideal, double[][] path, BowNeuralMotion.Prediction prediction,
                  boolean building, double bodyHeight) {
            this.player = player;
            this.ideal = ideal;
            this.path = path;
            this.prediction = prediction;
            this.building = building;
            this.bodyHeight = bodyHeight;
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
        try (InputStream data = BowAim.class.getResourceAsStream("/practice/bow-neural.bin")) {
            if (data != null) loadedNeural = BowNeuralMotion.read(data);
        } catch (IOException failure) {
            System.err.println("[PracticeBridge] Could not load bow neural model: " + failure.getMessage());
        }
        neural = loadedNeural;
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
                releaseMode = ReleaseMode.valueOf(saved.getProperty("releasemode", "smart").toUpperCase(Locale.ROOT));
            } catch (IOException | IllegalArgumentException ignored) {}
        }
    }

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
                      boolean display, boolean automatic, int minimum, ReleaseMode rMode) throws IOException {
        Properties values = new Properties();
        values.setProperty("trajectory", path.name().toLowerCase(Locale.ROOT));
        values.setProperty("intercept", String.valueOf(marker));
        values.setProperty("limit", String.valueOf(maximum));
        values.setProperty("chance", String.valueOf(display));
        values.setProperty("autorelease", String.valueOf(automatic));
        values.setProperty("threshold", String.valueOf(minimum));
        values.setProperty("releasemode", rMode.name().toLowerCase(Locale.ROOT));
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
                save(Trajectory.ON, false, limit, showChance, autoRelease, threshold, releaseMode);
                mode = null;
                reset();
                return "Bow trajectory preview on · aim off";
            case "trajectory":
                if (!value.isEmpty()) {
                    Trajectory path;
                    try { path = Trajectory.valueOf(value.toUpperCase(Locale.ROOT)); }
                    catch (IllegalArgumentException e) { throw new IllegalArgumentException("Use trajectory on, off or auto"); }
                    save(path, intercept, limit, showChance, autoRelease, threshold, releaseMode);
                }
                return "Bow trajectory " + trajectory.name().toLowerCase(Locale.ROOT);
            case "intercept":
                if (!value.isEmpty()) save(trajectory, toggle(value), limit, showChance, autoRelease, threshold, releaseMode);
                return "Bow intercept " + (intercept ? "on" : "off");
            case "limit":
                if (!value.isEmpty()) save(trajectory, intercept, degrees(value), showChance, autoRelease, threshold, releaseMode);
                return "Bow aim correction limit " + limit + "°";
            case "chance":
                if (!value.isEmpty()) save(trajectory, intercept, limit, toggle(value), autoRelease, threshold, releaseMode);
                return "Bow hit chance display " + (showChance ? "on" : "off");
            case "autorelease":
                if (!value.isEmpty()) save(trajectory, intercept, limit, showChance, toggle(value), threshold, releaseMode);
                return "Bow auto-release " + (autoRelease
                        ? "on (" + releaseMode.name().toLowerCase(Locale.ROOT) + ", layup " + threshold + "%)" : "off");
            case "release":
                if (!value.isEmpty()) {
                    ReleaseMode chosen;
                    try { chosen = ReleaseMode.valueOf(value.toUpperCase(Locale.ROOT)); }
                    catch (IllegalArgumentException e) { throw new IllegalArgumentException("Use release smart or threshold"); }
                    save(trajectory, intercept, limit, showChance, autoRelease, threshold, chosen);
                }
                return "Bow auto-release policy " + releaseMode.name().toLowerCase(Locale.ROOT)
                        + " (layup " + threshold + "%)";
            case "threshold":
                if (!value.isEmpty()) save(trajectory, intercept, limit, showChance, autoRelease, percent(value), releaseMode);
                return "Bow auto-release threshold " + threshold + "%";
            case "status": break;
            default: throw new IllegalArgumentException("Use /p help bowaim");
        }
        if (mode != null && motion == null && neural == null) {
            mode = null;
            return "Bow aim model missing from the installed bridge";
        }
        String activeName = mode == null ? "off"
                : mode == Mode.ADAPTIVE ? (neural != null && !legacyAdaptive ? "neural (K=" + neural.modes() + " + voxels)" : "legacy adaptive")
                : mode.name().toLowerCase(Locale.ROOT);
        return "Bow aim " + activeName + (target == null ? "" : " · " + target.player.func_70005_c_());
    }

    void chat(Minecraft mc, String message) { teams.chat(mc, message); }

    private void forgetAim() {
        yawOffset = pitchOffset = 0;
        target = null;
        releaseTargetId = null;
        probability = Double.NaN;
        releasePolicy.reset();
    }

    void reset() {
        tracks.clear();
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
        for (EntityPlayer player : world.field_73010_i) {
            if (!eligible(self, player) || !teams.opponent(mc, self, player)) continue;
            ItemStack held = player.func_70694_bm();
            Item heldItem = held == null ? null : held.func_77973_b();
            boolean isBlock = heldItem instanceof ItemBlock;
            int cue = BridgeMotion.cue(player.func_70093_af(), isBlock, player.field_70125_A);
            int stateFlags = 0;
            if (player.func_70093_af()) stateFlags |= 1;
            if (isBlock) stateFlags |= 2;
            if (heldItem instanceof ItemSword) stateFlags |= 4;
            if (heldItem instanceof ItemBow) stateFlags |= 8;
            if (player.func_71011_bu() != null) stateFlags |= 16;
            if (player.field_70737_aN > 0) stateFlags |= 32;
            if (player.field_70733_aJ > 0f) stateFlags |= 64;
            tracks.computeIfAbsent(player.func_110124_au(), ignored -> new Track()).add(tick,
                    player.field_70165_t, player.field_70163_u, player.field_70161_v, cue,
                    player.field_70759_as, player.field_70125_A, stateFlags);
        }
        tracks.values().removeIf(track -> track.tick() < tick - BowMotion.CAPACITY);
        if ((mode == null && !intercept) || !drawing(mc, self) || (motion == null && neural == null)) {
            forgetAim();
            return;
        }
        float rawYaw = self.field_70177_z - yawOffset, rawPitch = pitch(self.field_70125_A - pitchOffset);
        target = choose(mc, self, rawYaw, rawPitch);
        if (mode != null) {
            adjust(self, rawYaw, rawPitch, target);
            boolean canEstimate = mode == Mode.ADAPTIVE && target != null
                    && (target.prediction != null || chance != null) && (showChance || autoRelease);
            probability = canEstimate ? estimate(self, target) : Double.NaN;
            tryAutoRelease(mc, self, mc.field_71474_y.field_74313_G.func_151470_d());
        }
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
        if (!releasePolicy.shouldFire(probability, threshold, releaseMode)) return;
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

    private double estimate(EntityPlayerSP self, Selection selected) {
        double speed = BowBallistics.speed(self.func_71057_bx());
        Shot shot = BowBallistics.fromAim(self.field_70165_t, self.field_70163_u + self.func_70047_e(),
                self.field_70161_v, speed, self.field_70177_z, self.field_70125_A);
        double ideal = selected.ideal.time;
        double flight = Math.min(BowBallistics.MAX_TICKS, ideal + Math.max(4, ideal * .30));
        BowAimPreview.Guide guide = BowAimPreview.trace(world, shot, flight, null);
        if (selected.prediction != null && neural != null) {
            if (guide.blocked && guide.travelTicks < ideal - 0.5) return 0.0;
            Shot evaluated = BowBallistics.fromAim(self.field_70165_t, self.field_70163_u + self.func_70047_e(),
                    self.field_70161_v, speed, self.field_70177_z, self.field_70125_A);
            // Evaluate analytic multi-mode hit probability along the player's actual current aim at flight time ideal
            Shot timed = BowBallistics.withTime(evaluated, ideal);
            return neural.calibratedHitProb(timed, selected.prediction);
        }
        return chance.estimate(tracks.get(selected.player.func_110124_au()), selected.building, shot, ideal,
                guide.travelTicks, selected.path);
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

    private Selection choose(Minecraft mc, EntityPlayerSP self, float yaw, float pitch) {
        double speed = BowBallistics.speed(self.func_71057_bx());
        if (speed <= 0) return null;
        double eyeY = self.field_70163_u + self.func_70047_e();
        double yr = Math.toRadians(yaw), pr = Math.toRadians(pitch);
        Vec3 from = new Vec3(self.field_70165_t, eyeY, self.field_70161_v);
        Vec3 to = new Vec3(from.field_72450_a - Math.sin(yr) * Math.cos(pr) * RANGE,
                eyeY - Math.sin(pr) * RANGE, from.field_72449_c + Math.cos(yr) * Math.cos(pr) * RANGE);
        MovingObjectPosition wall = world.func_72933_a(from, to);
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
        if (pointedAt != null) return solution(mc, self, pointedAt, speed, eyeY);

        Selection best = null;
        double bestAngle = NEAR_AIM, bestDistance = Double.POSITIVE_INFINITY;
        for (EntityPlayer other : world.field_73010_i) {
            if (!eligible(self, other)) continue;
            double dx = other.field_70165_t - from.field_72450_a, dz = other.field_70161_v - from.field_72449_c;
            double dy = other.field_70163_u + .9 - eyeY;
            double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (distance < 2 || distance > RANGE) continue;
            double bearing = Math.toDegrees(Math.atan2(-dx, dz));
            if (Math.abs(wrap(bearing - yaw)) > 20) continue;
            Selection candidate = solution(mc, self, other, speed, eyeY);
            if (candidate == null) continue;
            double angle = Math.hypot(wrap(candidate.ideal.yaw - yaw), candidate.ideal.pitch - pitch);
            if (angle <= NEAR_AIM && (best == null || angle < bestAngle - .35
                    || Math.abs(angle - bestAngle) <= .35 && distance < bestDistance)) {
                best = candidate;
                bestAngle = angle;
                bestDistance = distance;
            }
        }
        return best;
    }

    private byte[] sampleLocalVoxels(double x, double y, double z) {
        byte[] vox = new byte[BowNeuralMotion.VOX_DIM];
        if (world == null) return vox;
        int bx = (int)Math.floor(x), by = (int)Math.floor(y), bz = (int)Math.floor(z);
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int dy = -3; dy <= 3; dy++) {
            int wy = by + dy;
            if (wy < 0 || wy >= 256) continue;
            int yOff = (dy + 3) * 81;
            for (int dz = -4; dz <= 4; dz++) {
                int wz = bz + dz;
                int zOff = yOff + (dz + 4) * 9;
                for (int dx = -4; dx <= 4; dx++) {
                    cursor.func_181079_c(bx + dx, wy, wz);
                    Block block = world.func_180495_p(cursor).func_177230_c();
                    if (block != null && block.func_180646_a(world, cursor) != null) {
                        vox[zOff + (dx + 4)] = 1;
                    }
                }
            }
        }
        return vox;
    }

    private Selection solution(Minecraft mc, EntityPlayerSP self, EntityPlayer player, double speed, double eyeY) {
        if (!teams.opponent(mc, self, player)) return null;
        Track track = tracks.get(player.func_110124_au());
        if (track == null || track.tick() != tick || track.length() < 2) return null;
        Mode chosen = mode == null ? Mode.ADAPTIVE : mode;
        AxisAlignedBB bounds = player.func_174813_aQ();
        double height = bounds.field_72337_e - bounds.field_72338_b;

        // Unified End-to-End Multi-Modal Neural + 9x7x9 Voxel Aim Solver
        if (chosen == Mode.ADAPTIVE && neural != null && !legacyAdaptive) {
            byte[] vox = sampleLocalVoxels(player.field_70165_t, player.field_70163_u, player.field_70161_v);
            BowNeuralMotion.Prediction pred = neural.predict(track, vox, true);
            Shot bestShot = null;
            double[][] bestPath = null;
            double bestHeight = height * AIM_HEIGHTS[0];
            double bestProb = -1.0;
            int totalCandidates = pred.paths.length + 1;
            for (int c = 0; c < totalCandidates; c++) {
                double[][] candPath = c < pred.paths.length ? pred.paths[c] : pred.meanPath;
                for (double fraction : AIM_HEIGHTS) {
                    double aimAt = height * fraction;
                    Shot shot = BowBallistics.intercept(self.field_70165_t, eyeY, self.field_70161_v, speed, candPath, aimAt);
                    if (shot != null && !BowAimPreview.trace(world, shot, shot.time, null).blocked) {
                        double p = neural.rawHitProb(shot, pred);
                        if (p > bestProb) {
                            bestProb = p;
                            bestShot = shot;
                            bestPath = candPath;
                            bestHeight = aimAt;
                        }
                        break; // first unblocked body height for this mode
                    }
                }
            }
            if (bestShot != null) {
                return new Selection(player, bestShot, bestPath, pred, false, bestHeight);
            }
            return null;
        }

        if (motion == null) return null;
        double[][] base = motion.path(track, chosen, BowBallistics.MAX_TICKS);
        double[][] path = bridge != null && chosen == Mode.ADAPTIVE ? bridge.apply(track, base) : base;
        for (double fraction : AIM_HEIGHTS) {
            double aimAt = height * fraction;
            Shot shot = BowBallistics.intercept(self.field_70165_t, eyeY, self.field_70161_v, speed, path, aimAt);
            // Current eye-to-eye visibility can differ from the arrow's route to the future position.
            if (shot != null && !BowAimPreview.trace(world, shot, shot.time, null).blocked)
                return new Selection(player, shot, path, null, path != base, aimAt);
        }
        return null;
    }

    private void adjust(EntityPlayerSP self, float yaw, float pitch, Selection selected) {
        double dy = selected == null ? 0 : wrap(selected.ideal.yaw - yaw);
        double dp = selected == null ? 0 : selected.ideal.pitch - pitch;
        double scale = Math.min(1, limit / Math.max(1e-9, Math.hypot(dy, dp)));
        dy *= scale;
        dp *= scale;
        double step = selected == null ? 1 : Math.min(1, TURN_PER_TICK / Math.max(1e-9,
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
        if (!showChance || mode == null || !Double.isFinite(probability) || mc.field_71439_g == null
                || !drawing(mc,mc.field_71439_g)) return;
        boolean smartSpiking = autoRelease && releaseMode == ReleaseMode.SMART && releasePolicy.isSpiking();
        String text = (int)Math.floor(probability * 100) + "%" + (smartSpiking ? " ▲" : "");
        int y = height / 2 - 18, textWidth = mc.field_71466_p.func_78256_a(text);
        int x = (width - textWidth)/2;
        Gui.func_73734_a(x-3,y-2,x+textWidth+3,y+10,0xA5111A24);
        int color = autoRelease && probability * 100 >= threshold ? 0xFFFFDC84
                : smartSpiking ? 0xFF7CF5A8 : 0xFFB7EEFF;
        mc.field_71466_p.func_175063_a(text,x,y,color);
    }

    void draw(Minecraft mc, float partial) {
        boolean path = trajectory == Trajectory.ON || trajectory == Trajectory.AUTO && mode != null;
        EntityPlayerSP self = mc.field_71439_g;
        if ((!path && !intercept) || self == null || mc.field_71441_e == null || !drawing(mc, self)) return;
        double speed = BowBallistics.speed(self.func_71057_bx());
        if (speed <= 0) return;
        double x = self.field_70142_S + (self.field_70165_t - self.field_70142_S) * partial;
        double y = self.field_70137_T + (self.field_70163_u - self.field_70137_T) * partial + self.func_70047_e();
        double z = self.field_70136_U + (self.field_70161_v - self.field_70136_U) * partial;
        Shot current = BowBallistics.fromAim(x, y, z, speed, self.field_70177_z, self.field_70125_A);
        Selection forecast = world == mc.field_71441_e && (mode != null || intercept) ? target : null;
        BowAimPreview.draw(mc, BowAimPreview.trace(mc.field_71441_e, current, current.time, forecast),
                path, intercept, partial);
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
        Selection current = aim.choose(mc, self, yaw, pitch);
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
