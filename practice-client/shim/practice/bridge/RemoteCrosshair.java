package practice.bridge;

import io.netty.channel.Channel;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.network.play.server.S08PacketPlayerPosLook;
import net.minecraft.network.play.server.S12PacketEntityVelocity;
import net.minecraft.network.play.server.S19PacketEntityStatus;
import practice.api.Frame;
import practice.core.SumoStats;
import practice.core.replay.ReplayCoachFeedback;

/** Optional first-person cues from the public-server client's own view. */
final class RemoteCrosshair {
    static RemoteCrosshair hooks;
    private static final int RANGE_COLOR = 0xD955AAFF, BRAKE_COLOR = 0xFF74E6A6;
    private static final long MILLIS = 1_000_000L;
    private static final long PAIR_WINDOW = SumoStats.HIT_PAIR_MS * MILLIS;
    private static final long BRAKE_WINDOW = 600 * MILLIS;
    private static final double TARGET_LIMIT = 6;

    private enum Mode {
        OFF(false, false), BRAKE(true, false), REACH(false, true), BOTH(true, true);

        final boolean brake, reach;
        Mode(boolean brake, boolean reach) {
            this.brake = brake;
            this.reach = reach;
        }

        static Mode of(boolean brake, boolean reach) {
            if (brake && reach) return BOTH;
            if (brake) return BRAKE;
            return reach ? REACH : OFF;
        }
    }

    private static final class Launch {
        final long at;
        final double x, z;
        boolean confirmed;
        int moves, cuts;
        long firstCut, lastCut, shownFirst, shownLast;

        Launch(long at, double x, double z, double speed, boolean confirmed) {
            this.at = at;
            this.x = x / speed;
            this.z = z / speed;
            this.confirmed = confirmed;
        }
    }

    private static final class Click {
        final Launch launch;
        final double x, y, z;

        Click(Launch launch, EntityPlayerSP player) {
            this.launch = launch;
            x = player.field_70159_w;
            y = player.field_70181_x;
            z = player.field_70179_y;
        }
    }

    private final Path settings;
    private Mode mode = Mode.OFF;
    private Channel channel;
    private WorldClient world;
    private EntityPlayerSP player;
    private boolean replay;
    private long hurtAt;
    private Launch launch;
    private Click click;

    RemoteCrosshair(Path directory) {
        settings = directory.resolve("remote-crosshair.txt");
        try {
            if (Files.isRegularFile(settings) && Files.size(settings) <= 32) {
                String saved = new String(Files.readAllBytes(settings), StandardCharsets.UTF_8).trim();
                mode = Mode.valueOf(saved.toUpperCase(Locale.ROOT));
            }
        } catch (IOException | IllegalArgumentException ignored) {
            // An absent or unreadable preference starts with both cues off.
        }
    }

    String status() {
        return "Public-server crosshair · KB brake " + (mode.brake ? "on" : "off") +
            " · Your reach " + (mode.reach ? "on" : "off");
    }

    void set(String cue, boolean on) throws IOException {
        Mode next;
        switch (cue) {
            case "both": next = on ? Mode.BOTH : Mode.OFF; break;
            case "brake": next = Mode.of(on, mode.reach); break;
            case "reach": next = Mode.of(mode.brake, on); break;
            default: throw new IllegalArgumentException("Choose brake or reach.");
        }
        if (next == mode) return;
        Path temporary = Files.createTempFile(settings.getParent(), "remote-crosshair-", ".tmp");
        try {
            Files.write(temporary, (next.name().toLowerCase(Locale.ROOT) + "\n").getBytes(StandardCharsets.UTF_8));
            Files.move(temporary, settings, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(temporary);
        }
        if (next == Mode.OFF) forgetConnection();
        else if (next.brake != mode.brake) clearBrake();
        mode = next;
    }

    void replay(boolean active) {
        if (replay != active) clearBrake();
        replay = active;
        if (channel != null && (active || channel != ConnectionScope.current())) forgetConnection();
    }

    private void forgetConnection() {
        channel = null;
        world = null;
        player = null;
        clearBrake();
    }

    private boolean ready(Minecraft mc) {
        Channel current = ConnectionScope.current();
        if (replay || current == null || !(current.remoteAddress() instanceof InetSocketAddress) ||
                ConnectionScope.local(current) || mc.field_71441_e == null || mc.field_71439_g == null) {
            forgetConnection();
            return false;
        }
        if (channel != current || world != mc.field_71441_e || player != mc.field_71439_g) {
            channel = current;
            world = mc.field_71441_e;
            player = mc.field_71439_g;
            clearBrake();
        }
        if (!player.func_70089_S() || player.func_175149_v() || mc.field_71462_r != null ||
                mc.field_71474_y.field_74320_O != 0 || mc.func_175606_aa() != player) {
            clearBrake();
            return false;
        }
        return true;
    }

    private void clearBrake() {
        hurtAt = 0;
        launch = null;
        click = null;
    }

    void packet(Object packet) {
        if (!mode.brake || !(packet instanceof S19PacketEntityStatus || packet instanceof S12PacketEntityVelocity ||
                packet instanceof S08PacketPlayerPosLook)) return;
        Minecraft mc = Minecraft.func_71410_x();
        if (!mc.func_152345_ab() || !ready(mc)) return;
        long now = System.nanoTime();
        if (packet instanceof S08PacketPlayerPosLook) {
            clearBrake();
        } else if (packet instanceof S19PacketEntityStatus) {
            S19PacketEntityStatus status = (S19PacketEntityStatus)packet;
            if (status.func_149160_c() != 2 || status.func_149161_a(world) != player) return;
            if (launch != null && !launch.confirmed && now - launch.at <= PAIR_WINDOW) {
                launch.confirmed = true;
                if (launch.cuts > 0) {
                    launch.shownFirst = now;
                    launch.shownLast = now;
                }
            } else hurtAt = now;
        } else {
            S12PacketEntityVelocity velocity = (S12PacketEntityVelocity)packet;
            if (velocity.func_149412_c() != player.func_145782_y()) return;
            double x = velocity.func_149411_d() / 8000.0, z = velocity.func_149409_f() / 8000.0;
            double speed = Math.hypot(x, z);
            launch = speed >= .1 ? new Launch(now, x, z, speed, hurtAt != 0 && now - hurtAt <= PAIR_WINDOW) : null;
            hurtAt = 0;
        }
    }

    void moved(Object entity) {
        if (launch != null && entity == player && ready(Minecraft.func_71410_x())) launch.moves++;
    }

    void click(boolean after) {
        if (!mode.brake) return;
        Minecraft mc = Minecraft.func_71410_x();
        if (!ready(mc)) return;
        long now = System.nanoTime();
        if (after) {
            Click before = click;
            click = null;
            if (before == null || before.launch != launch ||
                    !SumoStats.sprintCut(before.x, before.y, before.z, player.field_70159_w, player.field_70181_x,
                        player.field_70179_y)) return;
            launch.cuts++;
            if (launch.confirmed) {
                if (launch.cuts == 1) launch.shownFirst = now;
                launch.shownLast = now;
            }
            return;
        }
        click = null;
        if (launch == null || launch.moves >= SumoStats.MOVES || now - launch.at > BRAKE_WINDOW ||
                !player.func_70051_ag() || !eligible(SumoCoach.rayEntity(mc))) return;
        double outward = player.field_70159_w * launch.x + player.field_70179_y * launch.z;
        if (outward > SumoStats.MIN_OUTWARD) click = new Click(launch, player);
    }

    private boolean eligible(Entity entity) {
        return entity instanceof EntityPlayer && entity != player && !((EntityPlayer)entity).func_175149_v() &&
            player.func_96122_a((EntityPlayer)entity) && entity.func_70089_S() &&
            !entity.func_98034_c(player) && entity.func_70067_L() &&
            world.func_73045_a(entity.func_145782_y()) == entity;
    }

    private boolean inReach(Minecraft mc) {
        if (mc.field_71442_b == null) return false;
        double limit = mc.field_71442_b.func_78749_i() ? TARGET_LIMIT : Math.min(3, mc.field_71442_b.func_78757_d());
        Entity aimed = SumoCoach.rayEntity(mc);
        if (eligible(aimed)) return SumoCoach.hitboxDistance(player, (EntityPlayer)aimed) <= limit;
        // Any visible player within ideal aim qualifies when the crosshair is between targets.
        for (EntityPlayer other : world.field_73010_i) {
            if (eligible(other) && player.func_70068_e(other) <= (TARGET_LIMIT + 2) * (TARGET_LIMIT + 2) &&
                    SumoCoach.hitboxDistance(player, other) <= limit && player.func_70685_l(other)) return true;
        }
        return false;
    }

    Frame hud(Frame base, Minecraft mc, int width, int height) {
        if (mode == Mode.OFF || !ready(mc)) return base;
        long now = System.nanoTime();
        boolean reach = mode.reach && inReach(mc);
        boolean brake = mode.brake && launch != null && recent(now, launch.shownFirst, ReplayCoachFeedback.BRAKE_MS);
        boolean count = mode.brake && launch != null && launch.cuts > 1 &&
            recent(now, launch.shownLast, ReplayCoachFeedback.COUNT_MS);
        if (!reach && !brake && !count) return base;
        List<Frame.Rectangle> marks = new ArrayList<>(base.rectangles);
        List<Frame.Line> lines = new ArrayList<>(base.lines);
        if (reach) rangeMarks(marks, width, height);
        if (brake) SumoCoach.crosshairMarks(marks, width, height, BRAKE_COLOR);
        if (count) lines.add(new Frame.Line("×" + launch.cuts, width / 2 + 12, height / 2 + 7, BRAKE_COLOR, false));
        return new Frame(base.holdSprint, lines, base.edgeColor, base.edgeWidth, marks);
    }

    private static boolean recent(long now, long time, long durationMs) {
        return time != 0 && now - time < durationMs * MILLIS;
    }

    private static void rangeMarks(List<Frame.Rectangle> marks, int width, int height) {
        int x = width / 2, y = height / 2;
        for (int sx : new int[]{-1, 1}) for (int sy : new int[]{-1, 1}) {
            int left = sx < 0 ? x - 11 : x + 7, top = y + sy * 11;
            marks.add(new Frame.Rectangle(left, top, left + 5, top + 1, RANGE_COLOR));
            int vx = x + sx * 11, vy = sy < 0 ? y - 10 : y + 7;
            marks.add(new Frame.Rectangle(vx, vy, vx + 1, vy + 4, RANGE_COLOR));
        }
    }
}
