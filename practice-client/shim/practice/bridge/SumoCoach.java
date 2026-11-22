package practice.bridge;

import com.google.gson.Gson;
import io.netty.channel.Channel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.PositionedSoundRecord;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.network.play.server.S08PacketPlayerPosLook;
import net.minecraft.network.play.server.S12PacketEntityVelocity;
import net.minecraft.network.play.server.S19PacketEntityStatus;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.Vec3;
import practice.api.Frame;
import practice.core.SumoStats;
import practice.core.SumoStats.Cue;
import practice.core.SumoStats.Exchange;
import practice.core.replay.MmcSumoReplay;

import java.io.IOException;
import java.awt.Point;
import java.awt.Rectangle;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;

/** Observes one participant's native inputs. Lab data supplies boundaries and final server results. */
final class SumoCoach implements SumoStats.Listener {
    static SumoCoach hooks;
    private static final Gson JSON = new Gson();
    private static final int HISTORY_LIMIT = 20;
    private static final long MAX_HISTORY_BYTES = 1024 * 1024;
    private static final int GREEN = 0xFF74E6A6, AMBER = 0xFFFFBD72;

    enum HudPart {
        STATS("sumo-stats", 77, "KB saved ~1.45 / ideal ~2.16 blocks", "Peak slide 1.62 blocks / W up 0 moves / click +24 ms"),
        NOTE("sumo-note", 55, "W-tap brake missed ~0.84 blocks");

        final String id;
        final int bottomInset;
        final String[] sample;
        HudPart(String id, int bottomInset, String... sample) {
            this.id = id;
            this.bottomInset = bottomInset;
            this.sample = sample;
        }
        Rectangle bounds(FontRenderer font, int width, int height) {
            int textWidth = 0;
            for (String row : sample) textWidth = Math.max(textWidth, font.func_78256_a(row));
            textWidth = Math.min(width, textWidth + 12);
            return new Rectangle((width - textWidth) / 2, height - bottomInset, textWidth, sample.length * 11 - 2);
        }
        Frame.Line line(FontRenderer font, Rectangle box, int row, String text) {
            int color = this == NOTE ? AMBER : row == 0 ? 0xFFEAF0EC : 0xFFADC0C4;
            return new Frame.Line(text, box.x + (box.width - font.func_78256_a(text)) / 2, box.y + row * 11, color, false);
        }
    }

    enum Option {
        LIVE("Live coaching", false), HUD("Live stats HUD", true), SUMMARY("Summary after a match", true),
        BRAKE_FLASH("Flash and count", true), SPRINT_SOUND("Sprint-off sound", true), SPRINT_TEXT("Sprint-off note", true),
        TIMER_SOUND("Miss timer sound", false), TIMER_TEXT("Miss timer note", true),
        WINDOW_SOUND("Unclicked window sound", false), WINDOW_TEXT("Unclicked window note", true),
        AIM_FLASH("Aim miss outline", true), COSTLY_BRAKE_SOUND("Costly brake sound", true);

        final String label, key;
        final boolean defaultValue;
        Option(String label, boolean defaultValue) {
            this.label = label;
            this.defaultValue = defaultValue;
            key = "sumocoach." + name().toLowerCase(Locale.ROOT).replace('_', '.');
        }
    }

    enum BrakeSound {
        PITCH("Grouped / pitch"), VOLUME("Grouped / volume"), TONES("Grouped / tones"),
        FIRST("First brake only"), EVERY("Every brake"), OFF("Off");

        final String label;
        BrakeSound(String label) { this.label = label; }
        boolean grouped() { return this == PITCH || this == VOLUME || this == TONES; }
    }

    enum Sound {
        SILENT("Silent", ""), HARP("Harp", "note.harp"), PLING("Pling", "note.pling"), ORB("XP orb", "random.orb"),
        BASS("Bass", "note.bass"), BASS_ATTACK("Sharp bass", "note.bassattack"), CLICK("Click", "random.click"),
        SNARE("Snare", "note.snare"), HAT("Hi-hat", "note.hat"), LEVEL_UP("Level up", "random.levelup"),
        ANVIL("Anvil", "random.anvil_land");

        final String label, event;
        Sound(String label, String event) { this.label = label; this.event = event; }
    }

    enum SoundCue {
        BRAKE("Successful brake", Sound.HARP, .55f, 1.25f), DOUBLE("Two brakes (tones)", Sound.PLING, .55f, 1.25f),
        TRIPLE("Three brakes (tones)", Sound.ORB, .55f, 1.25f), SPRINT("Sprint off", Sound.BASS, .55f, .85f),
        COSTLY("Costly missed brake", Sound.BASS_ATTACK, .95f, 1.35f), TIMER("Miss timer", Sound.CLICK, .45f, .8f),
        UNCLICKED("Unclicked opening", Sound.HARP, .40f, .7f), AIM("Aim miss", Sound.SILENT, .45f, 1f);

        final String label, key;
        final Sound fallback;
        final float strength, pitch;
        SoundCue(String label, Sound fallback, float strength, float pitch) {
            this.label = label;
            key = "sumocoach.sound." + name().toLowerCase(Locale.ROOT);
            this.fallback = fallback;
            this.strength = strength;
            this.pitch = pitch;
        }
    }

    static final class Summary {
        String id, venue, opponent, result, headline;
        long finished;
        int launches, targets, opportunities, early, brakes, unclicked, sprint, aim, timer;
        List<String> details;
        String title() { return venue + " vs " + opponent + "  /  " + result; }
    }

    private static final class Round {
        final String id, opponent, venue;
        final Channel connection;
        final boolean lab;
        final SumoStats stats;
        String opponentLabel;
        long start, hurtAt = Long.MIN_VALUE, jumpAt = Long.MIN_VALUE, floorAt = Long.MIN_VALUE;
        double floor;
        boolean partial, slotSeen;
        Object world;
        Round(String id, String opponent, boolean lab, long start, long now, SumoCoach coach) {
            this.id = id;
            this.opponent = opponent;
            opponentLabel = opponent;
            this.lab = lab;
            this.venue = lab ? "Practice lab" : "MMC";
            this.start = start;
            connection = ConnectionScope.current();
            partial = now - start > 250;
            stats = new SumoStats(coach);
        }
    }

    private static final class Click {
        final Exchange exchange;
        final long time;
        final int cooldown;
        final boolean target, missed, sprint;
        final double x, y, z, distance;
        Click(Exchange exchange, long time, int cooldown, boolean target, boolean missed, boolean sprint,
              double x, double y, double z, double distance) {
            this.exchange = exchange;
            this.time = time;
            this.cooldown = cooldown;
            this.target = target;
            this.missed = missed;
            this.sprint = sprint;
            this.x = x;
            this.y = y;
            this.z = z;
            this.distance = distance;
        }
    }

    private final PracticeBridge bridge;
    private final Path historyFile;
    private Path labReplays;
    private final List<Summary> history = new ArrayList<>();
    private final EnumSet<Option> enabled = EnumSet.noneOf(Option.class);
    private final EnumMap<SoundCue, Sound> sounds = new EnumMap<>(SoundCue.class);
    private final EnumSet<Cue> deferredSounds = EnumSet.noneOf(Cue.class);
    BrakeSound brakeSound = BrakeSound.PITCH;
    int volume = 50;
    private Round round;
    private Summary interrupted;
    private Click click;
    private boolean moving, ground, forward;
    private double beforeX, beforeZ;
    private float travelDrag;
    private long noteUntil;
    private practice.core.replay.ReplayCoachFeedback crosshair=new practice.core.replay.ReplayCoachFeedback();
    private Exchange flashedBrake;
    private String note = "";
    private Exchange soundExchange;
    private boolean sounded, playedBrake;
    private String saveError = "";

    SumoCoach(PracticeBridge bridge, Path directory) {
        this.bridge = bridge;
        historyFile = directory.resolve("sumo-stats.json");
        settings(new Properties());
        try {
            if (Files.isRegularFile(historyFile) && Files.size(historyFile) <= MAX_HISTORY_BYTES) {
                Summary[] saved = JSON.fromJson(new String(Files.readAllBytes(historyFile), StandardCharsets.UTF_8), Summary[].class);
                if (saved != null) for (Summary summary : saved) {
                    if (valid(summary) && history.size() < HISTORY_LIMIT) history.add(summary);
                }
            }
        } catch (IOException | RuntimeException error) {
            saveError = "Could not read previous match stats";
        }
    }

    private static boolean valid(Summary summary) {
        return summary != null && summary.id != null && summary.result != null && summary.venue != null &&
                summary.opponent != null && summary.details != null && !summary.details.contains(null);
    }

    boolean enabled(Option option) { return enabled.contains(option); }

    void settings(Properties props) {
        for (Option option : Option.values()) {
            if (Boolean.parseBoolean(props.getProperty(option.key, Boolean.toString(option.defaultValue)))) enabled.add(option);
            else enabled.remove(option);
        }
        try { brakeSound = BrakeSound.valueOf(props.getProperty("sumocoach.brake.sound", "PITCH")); }
        catch (IllegalArgumentException ignored) { brakeSound = BrakeSound.PITCH; }
        for (SoundCue cue : SoundCue.values()) {
            try { sounds.put(cue, Sound.valueOf(props.getProperty(cue.key, cue.fallback.name()))); }
            catch (IllegalArgumentException ignored) { sounds.put(cue, cue.fallback); }
        }
        try { volume = Math.max(0, Math.min(100, Integer.parseInt(props.getProperty("sumocoach.volume", "50")))); }
        catch (NumberFormatException ignored) { volume = 50; }
        String archive = props.getProperty("archive.directory", "").trim();
        try { labReplays = archive.isEmpty() ? null : Paths.get(archive).resolveSibling("replays"); }
        catch (java.nio.file.InvalidPathException ignored) { labReplays = null; }
        if (!enabled(Option.LIVE)) clearFeedback();
    }

    void toggle(Option option) throws IOException { bridge.storeSetting(option.key, Boolean.toString(!enabled(option))); }
    void nextSound() throws IOException {
        BrakeSound[] modes = BrakeSound.values();
        bridge.storeSetting("sumocoach.brake.sound", modes[(brakeSound.ordinal() + 1) % modes.length].name());
    }
    Sound selectedSound(SoundCue cue) { return sounds.get(cue); }
    void nextSound(SoundCue cue) throws IOException {
        Sound[] choices = Sound.values();
        bridge.storeSetting(cue.key, choices[(sounds.get(cue).ordinal() + 1) % choices.length].name());
    }
    void preview(SoundCue cue) {
        if (cue == SoundCue.BRAKE && brakeSound != BrakeSound.OFF) playBrake(1);
        else play(cue);
    }
    void nextVolume() throws IOException { bridge.storeSetting("sumocoach.volume", Integer.toString(volume >= 100 ? 0 : Math.min(100, volume + 25))); }
    void toggleJump() throws IOException { bridge.storeSetting("jumpreset.enabled", Boolean.toString(!CombatCoach.jumpEnabled())); }

    private boolean collecting() { return enabled(Option.LIVE) || enabled(Option.SUMMARY); }
    private static long now() { return System.currentTimeMillis(); }

    void tick(boolean offline, boolean recordingMmc) {
        if (round != null && (offline || round.connection != ConnectionScope.current() || !collecting() ||
                !round.lab && !recordingMmc)) interrupt();
        if (round == null) return;
        round.slotSeen = false;
        long time = now();
        if (!round.lab && time - round.start > 600000) {
            interrupt();
            return;
        }
        if (round.stats.active != null && time - round.stats.active.impact > 600) round.stats.closeWindow();
        round.stats.expireEdge(time);
    }

    void mmc(MmcSumoReplay parser, MmcSumoReplay.Match finished) {
        if (!collecting() || ConnectionScope.practice() || ConnectionScope.current() == null) return;
        if (finished != null) {
            if (matches(finished.id, false)) complete(finished.finish, finished.won ? "Won" : "Lost", !finished.won, -1, -1);
            return;
        }
        String id = parser.activeId();
        if (matches(id, false)) return;
        if (round != null && !round.lab) interrupt();
        if (id != null) start(id, parser.opponent(), false, parser.combatStart());
    }

    void lab(Map<String, Object> state, String type) {
        if (!collecting() || !ConnectionScope.practice()) return;
        String id = string(state, "match");
        if (type.equals("sumo_finish")) {
            if (!matches(id, true)) return;
            long finish = integer(state, "finish", now());
            String reason = string(state, "reason");
            if (reason.equals("stopped") || finish < round.start || reason.startsWith("invalid")) { interrupt(); return; }
            String result = string(state, "result");
            complete(finish, result, reason.equals("ring_out") && result.equals("Lost"),
                    (int)integer(state, "sprint_hits", -1), (int)integer(state, "fresh_hits", -1));
            return;
        }
        if (!type.equals("state")) return;
        EntityPlayerSP player = Minecraft.func_71410_x().field_71439_g;
        Object participants = state.get("players");
        if (!"sumo".equals(state.get("mode")) || id.isEmpty() || player == null || !(participants instanceof List)) {
            if (round != null && round.lab) interrupt();
            return;
        }
        List<?> names = (List<?>)participants;
        int side = names.indexOf(player.func_70005_c_());
        if (names.size() != 2 || side < 0 || !(names.get(1 - side) instanceof String)) return;
        long start = integer(state, "combat_start", 0);
        if (start <= 0) return;
        if (matches(id, true)) round.start = start;
        else start(id, (String)names.get(1 - side), true, start);
        Object policies = state.get("policies");
        if (matches(id, true) && round.opponent.startsWith("LabSlot") && policies instanceof List) {
            List<?> values = (List<?>)policies;
            if (values.size() == 2 && values.get(1 - side) instanceof String) {
                String policy = (String)values.get(1 - side);
                if (policy.matches("[a-zA-Z0-9_-]{1,36}")) round.opponentLabel = policy;
            }
        }
    }

    /** Older lab servers already announce ordinary duel results with the match ID. */
    void labResultChat(String text) {
        if (round == null || !round.lab || !ConnectionScope.practice()) return;
        String plain = text.replaceAll("(?i)§[0-9A-FK-OR]", "");
        String prefix = "Finished " + round.id + " [sumo]";
        boolean drill = plain.startsWith("Jump Reset #") && plain.endsWith("/replay " + round.id);
        if (!plain.startsWith(prefix) && !drill) return;
        String result = drill ? plain : plain.substring(prefix.length());
        if (result.contains("YOU WON")) complete(now(), "Won", false, -1, -1);
        else if (result.contains("YOU LOST")) complete(now(), "Lost", false, -1, -1);
        else if (result.contains("double ring out")) complete(now(), "Draw", false, -1, -1);
        else if (result.contains("timeout")) complete(now(), "Draw", false, -1, -1);
        else interrupt();
    }

    private boolean matches(String id, boolean lab) {
        return round != null && round.lab == lab && round.id.equals(id) && round.connection == ConnectionScope.current();
    }

    private void start(String id, String opponent, boolean lab, long start) {
        for (Summary summary : history) if (summary.id.equals(id) && summary.venue.equals(lab ? "Practice lab" : "MMC")) return;
        if (round != null) interrupt();
        round = new Round(id, opponent, lab, start, now(), this);
        clearFeedback();
    }

    private EntityPlayerSP ready(Minecraft mc) {
        if (round == null || round.connection != ConnectionScope.current() || mc.field_71439_g == null ||
                mc.field_71441_e == null || now() < round.start) return null;
        if (round.world != mc.field_71441_e) {
            if (round.world != null) resetPosition();
            round.world = mc.field_71441_e;
        }
        return mc.field_71439_g;
    }

    void packet(Object packet) {
        if (round == null || !(packet instanceof S12PacketEntityVelocity || packet instanceof S19PacketEntityStatus ||
                packet instanceof S08PacketPlayerPosLook)) return;
        Minecraft mc = Minecraft.func_71410_x();
        if (!mc.func_152345_ab()) return;
        EntityPlayerSP player = ready(mc);
        if (player == null) return;
        long time = now();
        if (packet instanceof S08PacketPlayerPosLook) {
            resetPosition();
        } else if (packet instanceof S19PacketEntityStatus) {
            S19PacketEntityStatus status = (S19PacketEntityStatus)packet;
            if (status.func_149160_c() != 2 || status.func_149161_a(mc.field_71441_e) != player) return;
            Exchange exchange = round.stats.active;
            if (exchange != null && !exchange.confirmed && time - exchange.impact <= SumoStats.HIT_PAIR_MS) {
                round.stats.confirm();
                round.hurtAt = Long.MIN_VALUE;
            } else round.hurtAt = time;
        } else {
            S12PacketEntityVelocity velocity = (S12PacketEntityVelocity)packet;
            if (velocity.func_149412_c() != player.func_145782_y()) return;
            double x = velocity.func_149411_d() / 8000.0, z = velocity.func_149409_f() / 8000.0;
            double speed = Math.hypot(x, z);
            if (speed < .1 || opponent(mc, player) == null) return;
            boolean confirmed = round.hurtAt != Long.MIN_VALUE && time - round.hurtAt <= SumoStats.HIT_PAIR_MS;
            round.hurtAt = Long.MIN_VALUE;
            if (player.field_70122_E) rememberFloor(player, time);
            double margin = time - round.floorAt <= 1500 && round.floorAt != Long.MIN_VALUE
                    ? floorMargin(mc, player, round.floor, x / speed, z / speed) : Double.NaN;
            int jumpOffset = round.jumpAt == Long.MIN_VALUE ? Integer.MIN_VALUE : (int)(round.jumpAt - time);
            round.stats.impact(time, x, z, margin, jumpOffset, confirmed);
        }
    }

    private void resetPosition() {
        round.stats.discontinuity();
        round.hurtAt = round.jumpAt = round.floorAt = Long.MIN_VALUE;
        moving = false;
        click = null;
    }

    void jump(Object entity) {
        if (round == null) return;
        Minecraft mc = Minecraft.func_71410_x();
        if (entity == mc.field_71439_g && ready(mc) != null) round.jumpAt = now();
    }

    void travel(Object entity, boolean after) {
        if (round == null) return;
        Minecraft mc = Minecraft.func_71410_x();
        if (entity != mc.field_71439_g) return;
        EntityPlayerSP player = ready(mc);
        if (player == null) return;
        if (after) {
            if (moving) {
                double dx = player.field_70165_t - beforeX, dz = player.field_70161_v - beforeZ;
                double drag = !player.field_70123_F && !player.field_70145_X &&
                        Math.abs(dx * travelDrag - player.field_70159_w) < 1e-4 &&
                        Math.abs(dz * travelDrag - player.field_70179_y) < 1e-4 ? travelDrag : Double.NaN;
                round.stats.move(now(), dx, dz, ground, forward, drag);
            }
            moving = false;
            return;
        }
        moving = true;
        beforeX = player.field_70165_t;
        beforeZ = player.field_70161_v;
        ground = player.field_70122_E;
        forward = mc.field_71474_y.field_74351_w.func_151470_d();
        travelDrag = .91f;
        if (ground) {
            rememberFloor(player, now());
            if (round.stats.active != null) {
                BlockPos floor = new BlockPos(beforeX, Math.floor(round.floor) - 1, beforeZ);
                travelDrag *= mc.field_71441_e.func_180495_p(floor).func_177230_c().field_149765_K;
            }
        }
    }

    private void rememberFloor(EntityPlayerSP player, long time) {
        round.floor = player.func_174813_aQ().field_72338_b;
        round.floorAt = time;
    }

    void attackSlot() {
        if (round == null || round.slotSeen) return;
        round.slotSeen = true;
        Minecraft mc = Minecraft.func_71410_x();
        EntityPlayerSP player = ready(mc);
        Exchange exchange = round.stats.active;
        if (player == null || exchange == null || mc.field_71462_r != null) return;
        Entity hit = rayEntity(mc);
        int counter = missTimer(mc);
        if (counter >= 0) round.stats.inputSlot(now(), isOpponent(hit), player.func_70051_ag(), counter,
                exchange.outward(player.field_70159_w, player.field_70179_y));
    }

    void click(boolean after) {
        if (round == null) return;
        Minecraft mc = Minecraft.func_71410_x();
        EntityPlayerSP player = ready(mc);
        Exchange exchange = round.stats.active;
        if (player == null || exchange == null || mc.field_71462_r != null) { click = null; return; }
        if (after) {
            Click previous = click;
            click = null;
            if (previous == null || previous.exchange != exchange) return;
            boolean cut = previous.target && previous.sprint && previous.cooldown == 0 &&
                    SumoStats.sprintCut(previous.x,previous.y,previous.z,player.field_70159_w,player.field_70181_x,player.field_70179_y);
            round.stats.click(previous.time, previous.target, previous.missed, previous.sprint, previous.cooldown,
                    exchange.outward(previous.x, previous.z), previous.distance, cut);
            return;
        }
        click = null;
        int counter = missTimer(mc);
        if (counter < 0 || exchange.outward(player.field_70159_w, player.field_70179_y) <= SumoStats.MIN_OUTWARD) return;
        boolean target = isOpponent(rayEntity(mc));
        EntityPlayer other = opponent(mc, player);
        double distance = other == null ? Double.NaN : hitboxDistance(player, other);
        boolean missed = other != null && mc.field_71476_x != null &&
                mc.field_71476_x.field_72313_a == MovingObjectPosition.MovingObjectType.MISS &&
                distance <= 2.95 && player.func_70685_l(other);
        click = new Click(exchange, now(), counter, target, missed, player.func_70051_ag(),
                player.field_70159_w, player.field_70181_x, player.field_70179_y, distance);
    }

    private static int missTimer(Minecraft mc) {
        Object value = GameObservations.field(mc, "leftClickCounter");
        return value instanceof Number ? Math.max(0, ((Number)value).intValue()) : -1;
    }

    static Entity rayEntity(Minecraft mc) { return mc.field_71476_x == null ? null : mc.field_71476_x.field_72308_g; }

    private boolean isOpponent(Entity entity) {
        return entity instanceof EntityPlayer && round.opponent.equalsIgnoreCase(entity.func_70005_c_());
    }

    private EntityPlayer opponent(Minecraft mc, EntityPlayerSP player) {
        for (Object other : mc.field_71441_e.field_73010_i) {
            if (other != player && isOpponent((Entity)other)) return (EntityPlayer)other;
        }
        return null;
    }

    static double hitboxDistance(EntityPlayerSP player, EntityPlayer target) {
        Vec3 eyes = player.func_174824_e(1);
        AxisAlignedBB box = target.func_174813_aQ();
        double border = target.func_70111_Y();
        double x = gap(eyes.field_72450_a, box.field_72340_a - border, box.field_72336_d + border);
        double y = gap(eyes.field_72448_b, box.field_72338_b - border, box.field_72337_e + border);
        double z = gap(eyes.field_72449_c, box.field_72339_c - border, box.field_72334_f + border);
        return Math.sqrt(x*x + y*y + z*z);
    }

    private static double gap(double value, double min, double max) { return Math.max(0, Math.max(min - value, value - max)); }

    /** Distance along the push until the player's footprint loses flat floor support. */
    private static double floorMargin(Minecraft mc, EntityPlayerSP player, double top, double dx, double dz) {
        if (Math.abs(top - Math.rint(top)) > 1e-5) return Double.NaN;
        AxisAlignedBB feet = player.func_174813_aQ();
        if (support(mc, feet, (int)top - 1, 0, 0) != 1) return Double.NaN;
        for (int step = 1; step <= 40; step++) {
            double t = step * .1;
            int support = support(mc, feet, (int)top - 1, t * dx, t * dz);
            if (support < 0) return Double.NaN;
            if (support == 0) return t;
        }
        return Double.NaN;
    }

    private static int support(Minecraft mc, AxisAlignedBB feet, int y, double x, double z) {
        boolean unknown = false;
        for (int bx = (int)Math.floor(feet.field_72340_a + x); bx <= Math.floor(feet.field_72336_d + x - 1e-6); bx++) {
            for (int bz = (int)Math.floor(feet.field_72339_c + z); bz <= Math.floor(feet.field_72334_f + z - 1e-6); bz++) {
                BlockPos pos = new BlockPos(bx, y, bz);
                if (!mc.field_71441_e.func_175667_e(pos)) { unknown = true; continue; }
                AxisAlignedBB block = mc.field_71441_e.func_180495_p(pos).func_177230_c().func_180646_a(mc.field_71441_e, pos);
                if (block != null && Math.abs(block.field_72337_e - (y + 1)) < 1e-5 &&
                        block.field_72336_d > feet.field_72340_a + x && block.field_72340_a < feet.field_72336_d + x &&
                        block.field_72334_f > feet.field_72339_c + z && block.field_72339_c < feet.field_72334_f + z) return 1;
            }
        }
        return unknown ? -1 : 0;
    }

    private boolean live() {
        return round != null && round.lab && round.connection == ConnectionScope.current() &&
                ConnectionScope.practice() && enabled(Option.LIVE) && Minecraft.func_71410_x().field_71462_r == null;
    }

    @Override public void cue(Exchange exchange, Cue cue) {
        long time = now();
        if(round!=null&&round.lab&&(cue==Cue.BRAKE||cue==Cue.AIM)){
            boolean first=flashedBrake!=exchange;
            if(cue==Cue.BRAKE){crosshair.brake(time,exchange.cuts,first);flashedBrake=exchange;}
            else crosshair.aim(time);
            bridge.recordCoachCue(cue.name(),exchange.cuts,first);
        }
        if (!live()) return;
        soundWindow(exchange);
        switch (cue) {
            case BRAKE:
                if (brakeSound == BrakeSound.EVERY || brakeSound == BrakeSound.FIRST && !playedBrake && !sounded) {
                    playBrake(1);
                    playedBrake = sounded = true;
                }
                return;
            case AIM:
                play(SoundCue.AIM);
                return;
            case SPRINT: if (enabled(Option.SPRINT_TEXT)) show(exchange.note(cue)); break;
            case COOLDOWN: if (enabled(Option.TIMER_TEXT)) show(exchange.note(cue)); break;
            case UNCLICKED: if (enabled(Option.WINDOW_TEXT)) show(exchange.note(cue)); break;
        }
        if (!soundEnabled(cue)) return;
        if (brakeSound.grouped() || brakeSound == BrakeSound.FIRST) deferredSounds.add(cue);
        else if (!sounded) { playIssue(cue); sounded = true; }
    }

    @Override public void closed(Exchange exchange) {
        if (!live()) return;
        soundWindow(exchange);
        if (exchange.missedBrakeBlocks >= SumoStats.COSTLY_BRAKE_BLOCKS) {
            if (enabled(Option.SPRINT_TEXT)) show((exchange.missedBrakeAfterRelease ? "W-tap brake missed ~" : "Brake missed ~") +
                    SumoStats.blocks(exchange.missedBrakeBlocks));
            if (enabled(Option.COSTLY_BRAKE_SOUND)) {
                if (sounds.get(SoundCue.COSTLY) != Sound.SILENT) {
                    play(SoundCue.COSTLY);
                    return;
                }
            }
        }
        if (exchange.cuts == 0 && exchange.unclickedTicks > 0 && exchange.firstIssue() == Cue.UNCLICKED) cue(exchange, Cue.UNCLICKED);
        if (exchange.cuts > 0 && brakeSound.grouped()) playBrake(exchange.cuts);
        else if (exchange.cuts == 0 && !deferredSounds.isEmpty()) {
            Cue first = exchange.firstIssue();
            playIssue(first != null && deferredSounds.contains(first) ? first : deferredSounds.iterator().next());
        }
    }

    private void soundWindow(Exchange exchange) {
        if (soundExchange == exchange) return;
        soundExchange = exchange;
        sounded = playedBrake = false;
        deferredSounds.clear();
    }

    private boolean soundEnabled(Cue cue) {
        switch (cue) {
            case SPRINT: return enabled(Option.SPRINT_SOUND);
            case COOLDOWN: return enabled(Option.TIMER_SOUND);
            case UNCLICKED: return enabled(Option.WINDOW_SOUND);
            default: return false;
        }
    }

    private void playBrake(int cuts) {
        int level = Math.min(3, cuts);
        SoundCue cue = brakeSound == BrakeSound.TONES ? level == 3 ? SoundCue.TRIPLE : level == 2 ? SoundCue.DOUBLE : SoundCue.BRAKE : SoundCue.BRAKE;
        float strength = brakeSound == BrakeSound.VOLUME ? .48f + .19f * (level - 1) : .55f;
        float pitch = brakeSound == BrakeSound.PITCH ? 1.05f + .27f * (level - 1) : 1.25f;
        sound(sounds.get(cue), strength, pitch);
    }

    private void playIssue(Cue cue) {
        if (cue == Cue.SPRINT) play(SoundCue.SPRINT);
        if (cue == Cue.COOLDOWN) play(SoundCue.TIMER);
        if (cue == Cue.UNCLICKED) play(SoundCue.UNCLICKED);
    }

    private void play(SoundCue cue) { sound(sounds.get(cue), cue.strength, cue.pitch); }

    private void sound(Sound sound, float strength, float pitch) {
        if (volume <= 0 || sound == Sound.SILENT) return;
        Minecraft mc = Minecraft.func_71410_x();
        if (mc.field_71439_g != null) mc.field_71439_g.func_85030_a(sound.event, strength * volume / 100f, pitch);
        else mc.func_147118_V().func_147682_a(PositionedSoundRecord.func_147674_a(new ResourceLocation(sound.event), pitch));
    }

    private void show(String text) { note = text; noteUntil = now() + 1350; }
    private void clearFeedback() { noteUntil=0;crosshair=new practice.core.replay.ReplayCoachFeedback(); flashedBrake = null; }

    private Rectangle hudBounds(HudPart part, FontRenderer font, int width, int height) {
        Rectangle box = part.bounds(font, width, height);
        Point position = bridge.hudLayout().position(part.id, box.x, box.y, width - box.width, height - box.height);
        box.setLocation(position);
        return box;
    }

    Frame hud(Frame base, int width, int height) {
        if (!live()) return base;
        List<Frame.Line> lines = new ArrayList<>(base.lines);
        List<Frame.Rectangle> rectangles = new ArrayList<>(base.rectangles);
        long time = now();
        FontRenderer font = Minecraft.func_71410_x().field_71466_p;
        if (enabled(Option.HUD) && time >= round.start) {
            Exchange last = round.stats.last;
            double saved = last == null ? Double.NaN : last.kbSaved();
            String headline = last == null ? "KB saved: waiting for a hit" : Double.isFinite(saved)
                    ? String.format(Locale.ROOT, "KB saved ~%.2f / ideal ~%.2f blocks", saved, last.idealKbSaved())
                    : "KB saved: no complete estimate";
            Rectangle box = hudBounds(HudPart.STATS, font, width, height);
            lines.add(HudPart.STATS.line(font, box, 0, headline));
            if (last != null) lines.add(HudPart.STATS.line(font, box, 1,
                    "Peak slide " + SumoStats.blocks(last.peakSlide()) + " / W up " + last.wUp +
                    " moves" + (last.firstClickMs >= 0 ? " / click +" + last.firstClickMs + " ms" : "")));
        }
        if (time < noteUntil) lines.add(HudPart.NOTE.line(font, hudBounds(HudPart.NOTE, font, width, height), 0, note));
        return crosshair(base,width,height,lines,rectangles,crosshair.at(time));
    }

    Frame replayFeedback(Frame base,int width,int height,Map<String,Object> cues){
        if(!enabled(Option.LIVE)||cues.isEmpty())return base;
        return crosshair(base,width,height,new ArrayList<>(base.lines),new ArrayList<>(base.rectangles),cues);
    }

    private Frame crosshair(Frame base,int width,int height,List<Frame.Line> lines,List<Frame.Rectangle> rectangles,Map<String,Object> cues){
        boolean brake=enabled(Option.BRAKE_FLASH)&&Boolean.TRUE.equals(cues.get("brake"));
        boolean aim=enabled(Option.AIM_FLASH)&&Boolean.TRUE.equals(cues.get("aim"));
        if (brake || aim) crosshairMarks(rectangles, width, height, aim ? AMBER : GREEN);
        if (enabled(Option.BRAKE_FLASH) && cues.get("cuts") instanceof Number)
            lines.add(new Frame.Line("×" + ((Number)cues.get("cuts")).intValue(), width / 2 + 12, height / 2 + 7, GREEN, false));
        return new Frame(base.holdSprint, lines, base.edgeColor, base.edgeWidth, rectangles);
    }

    static void crosshairMarks(List<Frame.Rectangle> rectangles, int width, int height, int color) {
        int x = width / 2, y = height / 2;
        for (int side : new int[]{-1, 1}) {
            int left = x + side * 8, top = y + side * 8;
            rectangles.add(new Frame.Rectangle(left - 2, y - 3, left - 1, y + 4, color));
            rectangles.add(new Frame.Rectangle(x - 3, top, x + 4, top + 1, color));
        }
    }

    private void interrupt() {
        if (round == null) return;
        Round previous = round;
        round = null;
        previous.stats.finish(now(), false);
        interrupted = summary(previous, now(), "Interrupted", -1, -1);
        clearFeedback();
        click = null;
        moving = false;
    }

    private void complete(long finished, String result, boolean ringOutLoss, int sprintHits, int freshHits) {
        Round previous = round;
        round = null;
        previous.stats.finish(finished, ringOutLoss);
        Summary summary = summary(previous, finished, result, sprintHits, freshHits);
        history.add(0, summary);
        if (history.size() > HISTORY_LIMIT) history.remove(history.size() - 1);
        save();
        if (previous.lab && freshHits < 0 && labReplays != null) recoverServerCounts(summary);
        if (previous.lab && labReplays != null) loadBotAnalysis(summary);
        clearFeedback();
        click = null;
        moving = false;
        if (previous.lab && enabled(Option.SUMMARY)) bridge.announceSumo(summary);
    }

    private void recoverServerCounts(Summary summary) {
        Minecraft mc = Minecraft.func_71410_x();
        if (mc.field_71439_g == null) return;
        String player = mc.field_71439_g.func_70005_c_();
        Path replays = labReplays;
        CompletableFuture.supplyAsync(() -> LabSumoHistory.sprintHits(replays, summary.id, player)).thenAccept(counts -> {
            if (counts != null) mc.func_152344_a(() -> {
                if (!history.contains(summary)) return;
                summary.details.add(serverSprintLine(counts[0], counts[1]));
                save();
            });
        });
    }

    private void loadBotAnalysis(Summary summary) {
        Minecraft mc = Minecraft.func_71410_x();
        if (mc.field_71439_g == null) return;
        String player = mc.field_71439_g.func_70005_c_();
        Path replays = labReplays;
        CompletableFuture.supplyAsync(() -> LabSumoHistory.opponentAnalysis(replays, summary.id, player)).thenAccept(lines -> {
            if (!lines.isEmpty()) mc.func_152344_a(() -> {
                if (!history.contains(summary)) return;
                summary.details.addAll(lines);
                save();
            });
        });
    }

    Summary last() { return history.isEmpty() ? null : history.get(0); }
    Summary interrupted() { return interrupted; }
    Summary current() { return round == null ? null : summary(round, now(), now() < round.start ? "Countdown" : "In progress", -1, -1); }
    Summary find(String id) {
        for (Summary summary : history) if (summary.id.equals(id)) return summary;
        return null;
    }
    List<Summary> recent() { return Collections.unmodifiableList(history); }
    String saveError() { return saveError; }

    private static Summary summary(Round round, long finished, String result, int sprintHits, int freshHits) {
        Summary summary = new Summary();
        summary.id = round.id;
        summary.venue = round.venue;
        summary.opponent = round.opponentLabel;
        summary.result = result;
        summary.finished = finished;
        SumoStats stats = round.stats;
        summary.launches = stats.launches;
        summary.targets = stats.earlyTargets;
        summary.opportunities = stats.earlyOpenings;
        summary.early = stats.earlyBrakes;
        summary.brakes = stats.brakes;
        summary.unclicked = stats.losses(Cue.UNCLICKED);
        summary.sprint = stats.losses(Cue.SPRINT);
        summary.aim = stats.losses(Cue.AIM);
        summary.timer = stats.losses(Cue.COOLDOWN);
        summary.headline = headline(summary, stats);
        List<String> details = new ArrayList<>();
        summary.details = details;
        if (round.partial) details.add("Partial observations: joined the round after combat began.");
        if (!result.equals("Countdown") && !result.equals("In progress")) {
            analyze(details, round, finished, result.equals("Lost"));
            if (freshHits >= 0 && sprintHits >= 0) details.add(serverSprintLine(sprintHits, freshHits));
            return summary;
        }
        if (stats.savingSamples > 0) details.add("KB saved ~" + SumoStats.blocks(stats.totalKbSaved) + " across " + stats.savingSamples +
                (stats.savingSamples == 1 ? " measured hit" : " measured hits"));
        details.add("Early brakes: " + stats.earlyBrakes + "/" + stats.earlyOpenings + " clear early openings");
        if (stats.launches > 0) details.add("Next round: " + focus(summary));
        if (stats.worst != null) describe(details, "Biggest slide", stats.worst, false);
        details.add("Early target selected: " + stats.earlyTargets + "/" + stats.launches + " measured launches");
        if (stats.launches > stats.earlyOpenings)
            details.add("No clear early opening observed on " + (stats.launches - stats.earlyOpenings) + " launches");
        details.add("Total brakes: " + stats.brakes + " / late brakes on " + stats.lateBrakes + " launches");
        details.add("Missed early brake: " + summary.unclicked + " unclicked, " + summary.sprint + " sprint off, " +
                summary.aim + " aim, " + summary.timer + " timer");
        details.add("W releases: " + stats.oneMoveReleases + " one move / " + stats.longerReleases + " two or more");
        details.add("On-target sprint-off exchanges: " + stats.sprintOffContacts);
        details.add("Costly missed brakes: " + stats.costlyBrakeExchanges + " / " + stats.costlyBrakeAfterRelease +
                " after W up" + (stats.largestMissedBrake >= SumoStats.COSTLY_BRAKE_BLOCKS
                ? " / worst ~" + SumoStats.blocks(stats.largestMissedBrake) : ""));
        if (freshHits >= 0 && sprintHits >= 0) details.add(serverSprintLine(sprintHits, freshHits));
        details.add("First move: " + stats.grounded + " grounded / " + stats.airborne + " airborne");
        details.add("Airborne launches within 120 ms of jump: " + stats.beforeImpactJumps);
        details.add(stats.slides());
        if (stats.edgeChecked > 0) details.add("Near-edge recoveries: " + stats.edgeSaved + "/" + stats.edgeChecked + " assessed");
        if (stats.lastEdge != null) details.add("Last near-edge launch: ~" + SumoStats.blocks(stats.lastEdge.margin) + " of floor support in push direction");
        if (stats.best != null && stats.best != stats.worst) describe(details, "Quickest brake recovery", stats.best, true);
        if (result.equals("Lost") && stats.last != null && stats.last != stats.worst && stats.last != stats.best)
            describe(details, "Last measured exchange", stats.last, false);
        return summary;
    }

    private static void analyze(List<String> lines, Round round, long finished, boolean lost) {
        SumoStats stats = round.stats;
        List<Exchange> moments = stats.lossMoments(finished, lost);
        Exchange finalSlide = lost ? stats.terminalLoss(finished) : null;
        if (moments.isEmpty()) lines.add("No large backward slides were measured.");
        else lines.add("Where you lost ground");
        for (Exchange exchange : moments) {
            String timestamp = SumoStats.timestamp(exchange.impact - round.start);
            lines.add(timestamp + "   Slid " + SumoStats.blocks(exchange.peakSlide()) + (exchange == finalSlide ? " near the finish" : ""));
            String footing = exchange.grounded ? "grounded" : "airborne";
            lines.add(String.format(Locale.ROOT, "%.2f blocks/tick at impact, %s. W up for %d/%d moves.",
                    exchange.speed, footing, exchange.wUp, exchange.steps));
            List<String> brakeTimeline = exchange.brakeTimeline();
            if (brakeTimeline.isEmpty()) lines.add(exchange.explanation());
            else lines.addAll(brakeTimeline);
            if (Double.isFinite(exchange.kbSaved()) && exchange.idealKbSaved() - exchange.kbSaved() >= .25)
                lines.add("Brakes saved ~" + SumoStats.blocks(exchange.kbSaved()) + " / ideal across all openings ~" +
                        SumoStats.blocks(exchange.idealKbSaved()) + ".");
            if (Double.isFinite(exchange.margin) && (exchange.margin <= 2.5 || exchange == finalSlide)) {
                lines.add("At impact you had ~" + SumoStats.blocks(exchange.margin) + " of floor support in the push direction.");
                if (stats.reachesEstimatedEdge(exchange)) lines.add("The slide reached that estimated edge.");
            }
            if (exchange == finalSlide) {
                Exchange previous = stats.precedingAlignedLoss(exchange);
                if (previous != null) lines.add("At " + SumoStats.timestamp(previous.impact - round.start) +
                        " the previous hit slid you " + SumoStats.blocks(previous.peakSlide()) + " toward the same side.");
            }
            if (exchange.peakSlide() - exchange.distance >= .25)
                lines.add("Moved forward " + SumoStats.blocks(exchange.peakSlide() - exchange.distance) + " from the peak within this exchange.");
            lines.add("");
        }
        if (stats.costlyBrakeExchanges > 0)
            lines.add("Hits with a costly missed brake " + stats.costlyBrakeExchanges + " (" + stats.costlyBrakeAfterRelease + " after W up).");
        int repeated = stats.earlySprintOffAfterRelease();
        if (repeated >= 2) lines.add("Repeated pattern in " + repeated + " slides. An early on-target click came with sprint off after W up.");
        lines.add("");
        lines.add("Where you gained ground");
        List<Exchange> gains = stats.gainMoments();
        if (gains.isEmpty()) lines.add("No clear distance saved by your own brakes was measured.");
        for (Exchange exchange : gains) {
            String brakes = exchange.cuts == 1 ? "1 brake" : exchange.cuts + " brakes";
            lines.add(SumoStats.timestamp(exchange.impact - round.start) + "   Kept ~" + SumoStats.blocks(exchange.kbSaved()) + " with " + brakes + ".");
            lines.add("First brake +" + exchange.firstCutMs + " ms. With no brakes, you would have slid ~" +
                    SumoStats.blocks(exchange.peakSlide() + exchange.kbSaved()) + ". Actual slide " + SumoStats.blocks(exchange.peakSlide()) + ".");
        }
    }


    private static void describe(List<String> lines, String label, Exchange exchange, boolean showBrake) {
        lines.add(label + ": " + SumoStats.blocks(exchange.distance) + (exchange.steps == SumoStats.MOVES ? "" : " in " + exchange.steps + " moves") + " / launch " +
                String.format(Locale.ROOT, "%.2f blocks/tick", exchange.speed) + " / " + (exchange.grounded ? "grounded" : "airborne"));
        if (!exchange.grounded && exchange.jumpOffset >= -120 && exchange.jumpOffset <= 0)
            lines.add("Takeoff " + -exchange.jumpOffset + " ms before impact");
        if (Double.isFinite(exchange.kbSaved())) lines.add("KB saved ~" + SumoStats.blocks(exchange.kbSaved()));
        lines.add(showBrake ? "First brake +" + exchange.firstCutMs + " ms. " + exchange.cuts + " in this exchange." : exchange.explanation());
    }

    private static String serverSprintLine(int sprint, int fresh) {
        return "Server sprint on fresh accepted hits: " + sprint + "/" + fresh;
    }

    private static String headline(Summary summary, SumoStats stats) {
        if (stats.launches == 0) return "";
        StringBuilder out = new StringBuilder();
        out.append("Early brakes ").append(stats.earlyBrakes).append('/').append(stats.earlyOpenings);
        if (stats.worst != null) out.append(" · worst slide ").append(SumoStats.blocks(stats.worst.peakSlide()));
        if (stats.savingSamples > 0 && stats.totalKbSaved >= 0.25)
            out.append(" · KB saved ~").append(SumoStats.blocks(stats.totalKbSaved));
        int sprintAfterW = stats.earlySprintOffAfterRelease();
        if (sprintAfterW >= 2) {
            out.append(" · §e").append(sprintAfterW).append(" sprint-off after W-up (restart sprint before click)");
        } else if (stats.costlyBrakeExchanges > 0) {
            out.append(" · §e").append(stats.costlyBrakeExchanges).append(" costly missed brake")
               .append(stats.costlyBrakeExchanges == 1 ? "" : "s").append(" — ").append(focus(summary));
        } else {
            out.append(" · §a").append(focus(summary));
        }
        return out.toString();
    }

    private static String focus(Summary summary) {
        int maximum = Math.max(Math.max(summary.unclicked, summary.sprint), Math.max(summary.aim, summary.timer));
        if (maximum == 0) return "Keep the opponent in view through the trade and notice your footing.";
        if (maximum == summary.unclicked) return "Catch the first clear opening with a click.";
        if (maximum == summary.sprint) return "Check whether sprint had restarted before the return click.";
        if (maximum == summary.aim) return "Keep your crosshair on their torso through impact.";
        return "Track them through the approach and watch for swings into air.";
    }

    private void save() {
        Path temp = null;
        try {
            temp = Files.createTempFile(historyFile.getParent(), "sumo-stats-", ".tmp");
            Files.write(temp, JSON.toJson(history).getBytes(StandardCharsets.UTF_8));
            Files.move(temp, historyFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            saveError = "";
        } catch (IOException error) {
            saveError = "Could not save match stats";
        } finally {
            if (temp != null) try { Files.deleteIfExists(temp); } catch (IOException ignored) {}
        }
    }

    private static String string(Map<String, Object> data, String key) {
        Object value = data.get(key);
        return value instanceof String ? (String)value : "";
    }
    private static long integer(Map<String, Object> data, String key, long fallback) {
        Object value = data.get(key);
        return value instanceof Number ? ((Number)value).longValue() : fallback;
    }
}
