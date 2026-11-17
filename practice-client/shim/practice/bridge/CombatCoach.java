package practice.bridge;

import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.network.play.server.S06PacketUpdateHealth;
import net.minecraft.network.play.server.S08PacketPlayerPosLook;
import net.minecraft.network.play.server.S12PacketEntityVelocity;
import net.minecraft.network.play.server.S19PacketEntityStatus;
import net.minecraft.util.AxisAlignedBB;
import practice.api.Frame;
import practice.core.CombatCoachMath;
import practice.core.CombatCoachMath.Block;

/** Read-only coaching uses this client's applied packets and native movement boundaries. */
final class CombatCoach {
    private static final long DISPLAY = 1_800_000_000L;
    private static final int HIT_WINDOW = 10, LATE_WINDOW = 4;
    private static final int NOMINAL_TICK_MS = 50;
    private static final double MEANINGFUL_REDUCTION = .03, PERFECT_REDUCTION = .18;
    private static final boolean VERIFY = Boolean.getBoolean("practice.coach.verify");
    private static final CombatCoach INSTANCE = new CombatCoach();

    private static final class Damage {
        final int tick, armor, resistance, protection;
        final float hp, absorption;
        final List<MeleeObservations.Candidate> candidates;
        final boolean directionReliable;
        boolean ambiguous;
        double lost, pushX, pushZ;
        boolean observed;

        Damage(int tick, float hp, EntityPlayerSP player, List<MeleeObservations.Candidate> candidates, boolean ambiguous) {
            this.tick = tick;
            this.hp = hp;
            absorption = player.func_110139_bj();
            armor = player.func_70658_aO();
            resistance = MeleeObservations.resistance(player);
            protection = MeleeObservations.protection(player);
            this.candidates = candidates;
            this.ambiguous = ambiguous;
            directionReliable = player.field_70122_E && Math.hypot(player.field_70159_w, player.field_70179_y) <= .28;
        }
    }

    private static final class Impact {
        final int received;
        final double pushX, pushZ;
        int hurt;
        boolean overlapping;
        AxisAlignedBB startBox;
        double startX, startZ, jumpX, jumpZ, noJumpY;
        boolean grounded, jumped, comparable;

        Impact(int received, int hurt, double pushX, double pushZ, boolean overlapping) {
            this.received = received;
            this.hurt = hurt;
            this.pushX = pushX;
            this.pushZ = pushZ;
            this.overlapping = overlapping;
        }
    }

    private static final class Note {
        final String title, detail;
        final int color;
        final long until;
        Note(String title, String detail, int color) {
            this.title = title;
            this.detail = detail;
            this.color = color;
            until = System.nanoTime() + DISPLAY;
        }
    }

    private EntityPlayerSP player;
    private Object world;
    private volatile boolean block, jump;
    private boolean suspended;
    private int movement, lastHurt = -1000;
    private int lastTakeoff = -1000, takeoffFor = -1000, missed = -1000;
    private boolean previousJump, jumpKey, delaying, overlappingHurt, meleeCue;
    private double preJumpX, preJumpY, preJumpZ, changeX, changeZ;
    private double jumpBack, jumpSaved;
    private int knockbackDelay;
    private float health;
    private Note blockNote, jumpNote;
    private Impact pending, moving;
    private Damage damage;
    private final Deque<List<MeleeObservations.Candidate>> history = new ArrayDeque<>();

    static void settings(Properties settings) {
        OldBlockAnimation.enabled = Boolean.parseBoolean(settings.getProperty("oldblock.enabled", "true"));
        boolean block = Boolean.parseBoolean(settings.getProperty("blockhit.enabled", "false"));
        boolean jump = Boolean.parseBoolean(settings.getProperty("jumpreset.enabled", "false"));
        if (INSTANCE.block != block || INSTANCE.jump != jump) INSTANCE.reset(null, null);
        INSTANCE.block = block;
        INSTANCE.jump = jump;
    }

    static void tick(Minecraft mc, boolean suspended) {
        INSTANCE.suspended = suspended;
        if (INSTANCE.block || INSTANCE.jump) INSTANCE.bind(mc);
    }

    private boolean bind(Minecraft mc) {
        if (suspended || mc.field_71439_g == null || mc.field_71441_e == null) {
            if (player != null) reset(null, null);
            return false;
        }
        if (mc.field_71439_g != player || mc.field_71441_e != world) reset(mc.field_71439_g, mc.field_71441_e);
        return true;
    }

    private void reset(EntityPlayerSP next, Object nextWorld) {
        player = next;
        world = nextWorld;
        health = next == null ? 0 : next.func_110143_aJ();
        movement = 0;
        lastHurt = lastTakeoff = takeoffFor = missed = -1000;
        previousJump = jumpKey = delaying = overlappingHurt = meleeCue = false;
        pending = moving = null;
        damage = null;
        blockNote = jumpNote = null;
        history.clear();
    }

    static void packet(Object packet, boolean after) {
        CombatCoach coach = INSTANCE;
        if ((!coach.jump && !coach.block) || !after ||
            !(packet instanceof S19PacketEntityStatus || packet instanceof S12PacketEntityVelocity ||
                packet instanceof S06PacketUpdateHealth || packet instanceof S08PacketPlayerPosLook)) return;
        Minecraft mc = Minecraft.func_71410_x();
        if (!mc.func_152345_ab() || !coach.bind(mc)) return;
        if (packet instanceof S19PacketEntityStatus) {
            S19PacketEntityStatus status = (S19PacketEntityStatus) packet;
            if (status.func_149160_c() == 2 && status.func_149161_a(mc.field_71441_e) == coach.player) coach.hurt(mc);
        } else if (packet instanceof S12PacketEntityVelocity) {
            S12PacketEntityVelocity velocity = (S12PacketEntityVelocity) packet;
            if (velocity.func_149412_c() == coach.player.func_145782_y()) {
                coach.velocity(velocity.func_149411_d() / 8000.0, velocity.func_149409_f() / 8000.0);
            }
        } else if (packet instanceof S06PacketUpdateHealth) {
            coach.health(((S06PacketUpdateHealth) packet).func_149332_c());
        } else {
            coach.reset(mc.field_71439_g, mc.field_71441_e);
        }
    }

    private void hurt(Minecraft mc) {
        overlappingHurt = delaying && movement - lastHurt <= HIT_WINDOW;
        lastHurt = movement;
        meleeCue = MeleeObservations.hasNearbyPlayer(mc, player) && !MeleeObservations.mixedSources(mc, player);
        delaying = jump && meleeCue;
        if (pending != null) {
            pending.overlapping |= overlappingHurt || pending.hurt >= 0;
            pending.hurt = delaying ? movement : -1000;
        }
        if (!block) return;
        if (damage != null && !damage.observed && movement - damage.tick <= HIT_WINDOW) {
            damage.ambiguous = true;
            return;
        }
        if (damage != null) resolveDamage(true);
        if (!meleeCue) return;
        List<MeleeObservations.Candidate> candidates = MeleeObservations.nearby(mc, player);
        for (List<MeleeObservations.Candidate> prior : history) candidates.addAll(prior);
        damage = new Damage(movement, health, player, candidates, false);
    }

    private void velocity(double x, double z) {
        if (damage != null && movement - damage.tick <= HIT_WINDOW) {
            if (damage.pushX != 0 || damage.pushZ != 0) damage.ambiguous = true;
            damage.pushX = x;
            damage.pushZ = z;
        }
        if (!jump) return;
        if (Math.hypot(x, z) < .01) {
            pending = null;
            return;
        }
        int hurt = delaying && movement - lastHurt <= HIT_WINDOW ? lastHurt : -1000;
        pending = new Impact(movement, hurt, x, z, pending != null || overlappingHurt);
    }

    private void health(float next) {
        double lost = health - next;
        health = next;
        if (!block || lost < .015) return;
        if (damage == null || damage.observed || movement - damage.tick > HIT_WINDOW) {
            // Upgrades and custom health changes may omit the fresh-hurt cue.
            damage = null;
            if (meleeCue || movement - lastHurt > HIT_WINDOW && !history.isEmpty() && !history.getLast().isEmpty())
                showBlock(Block.UNCERTAIN, false, lost);
            return;
        }
        damage.observed = true;
        damage.lost = lost;
    }

    private void resolveDamage(boolean superseded) {
        Damage d = damage;
        if (!block || d == null) return;
        if (!d.observed) {
            if (superseded || movement - d.tick > HIT_WINDOW) damage = null;
            return;
        }
        Block verdict = d.ambiguous ? Block.UNCERTAIN : estimate(d, -1);
        boolean direction = false;
        if (verdict == Block.UNCERTAIN && !d.ambiguous && d.directionReliable) {
            int attacker = MeleeObservations.fromDirection(d.candidates, d.pushX, d.pushZ);
            if (attacker != -1) {
                verdict = estimate(d, attacker);
                direction = verdict != Block.UNCERTAIN;
            }
        }
        if (superseded || verdict != Block.UNCERTAIN || movement - d.tick >= LATE_WINDOW) {
            showBlock(verdict, direction, d.lost);
            damage = null;
        }
    }

    private static Block estimate(Damage d, int attacker) {
        return CombatCoachMath.block(d.lost, d.hp, d.absorption, d.armor, d.resistance, d.protection,
            MeleeObservations.damage(d.candidates, attacker));
    }

    private void showBlock(Block verdict, boolean direction, double lost) {
        String title = verdict == Block.BLOCKED ? "Blocked" : verdict == Block.UNBLOCKED ? "Unblocked" : "Block uncertain";
        if (direction) title = "Likely " + title.toLowerCase(Locale.ROOT);
        blockNote = new Note(title, String.format(Locale.ROOT, "%.2f hearts lost", lost / 2),
            verdict == Block.BLOCKED ? 0x77FFAA : 0xFFBB66);
        if (VERIFY) System.out.println("[CombatCoach] block " + System.currentTimeMillis() + " " + verdict +
            " " + direction + " " + lost);
    }

    static void jump(Object entity, boolean after) {
        CombatCoach c = INSTANCE;
        if (!c.jump || c.suspended || entity != c.player) return;
        EntityPlayerSP p = c.player;
        if (!after) {
            c.preJumpX = p.field_70159_w;
            c.preJumpY = p.field_70181_x;
            c.preJumpZ = p.field_70179_y;
        } else {
            c.changeX = p.field_70159_w - c.preJumpX;
            c.changeZ = p.field_70179_y - c.preJumpZ;
            c.takeoffFor = c.movement + 1;
        }
    }

    static void travel(Object entity, boolean after) {
        CombatCoach c = INSTANCE;
        if ((!c.block && !c.jump) || c.suspended || entity != c.player) return;
        if (after) c.afterTravel();
        else c.beforeTravel();
    }

    private void beforeTravel() {
        movement++;
        jumpKey = player.field_71158_b != null && player.field_71158_b.field_78901_c;
        moving = pending;
        pending = null;
        if (!jump || moving == null) return;
        moving.startX = player.field_70165_t;
        moving.startZ = player.field_70161_v;
        moving.grounded = player.field_70122_E;
        moving.jumped = takeoffFor == movement;
        moving.comparable = moving.grounded && moving.jumped && !player.field_71075_bZ.field_75100_b &&
            !player.func_70090_H() && !player.func_180799_ab() && !player.func_70617_f_() && !player.func_70093_af() &&
            !Boolean.TRUE.equals(GameObservations.field(player, "isInWeb")) && preJumpY > 0;
        if (moving.comparable) {
            moving.startBox = player.func_174813_aQ();
            moving.jumpX = changeX;
            moving.jumpZ = changeZ;
            moving.noJumpY = preJumpY;
        }
    }

    private void afterTravel() {
        if (jump) finishJump();
        if (block) {
            Minecraft mc = Minecraft.func_71410_x();
            history.addLast(MeleeObservations.nearby(mc, player));
            if (history.size() > 3) history.removeFirst();
            resolveDamage(false);
        }
    }

    private void finishJump() {
        Impact kb = moving;
        moving = null;
        if (kb != null && kb.hurt >= 0) {
            delaying = false;
            overlappingHurt = false;
            knockbackDelay = Math.max(0, kb.received - kb.hurt);
            double x = player.field_70165_t - kb.startX, z = player.field_70161_v - kb.startZ;
            jumpBack = Math.max(0, CombatCoachMath.projected(x, z, kb.pushX, kb.pushZ));
            jumpSaved = Double.NaN;
            missed = -1000;
            if (kb.overlapping) showJump("Overlapping knockback", 0xFFBB66);
            else if (kb.jumped) {
                if (unobstructed(kb, x, z)) jumpSaved = CombatCoachMath.reduction(x, z, kb.jumpX, kb.jumpZ, kb.pushX, kb.pushZ);
                showJump(jumpEffect(), jumpSaved >= PERFECT_REDUCTION ? 0x77FFAA : 0xFFBB66);
            } else if (kb.grounded) {
                if (!jumpKey) missed = movement;
                showJump(jumpKey ? "Jump didn't take off" : "Missed impact tick", 0xFFBB66);
            } else {
                showJump(movement - lastTakeoff <= 14 ? timing("Early by ", movement - lastTakeoff) : "Airborne at impact", 0xFFBB66);
            }
            lastTakeoff = -1000;
        } else if (movement - missed <= LATE_WINDOW && jumpKey && !previousJump) {
            showJump(timing("Late by ", movement - missed), 0xFFBB66);
            missed = -1000;
        }
        if (kb == null && takeoffFor == movement) lastTakeoff = movement;
        if (player.field_70122_E) lastTakeoff = -1000;
        if (delaying && movement - lastHurt > HIT_WINDOW) {
            jumpNote = new Note("No measurable knockback", "No clear horizontal impulse after the hit", 0xFFBB66);
            if (VERIFY) System.out.println("[CombatCoach] jump " + System.currentTimeMillis() +
                " No measurable knockback | NaN NaN 0");
            delaying = false;
            overlappingHurt = false;
        }
        previousJump = jumpKey;
    }

    private String jumpEffect() {
        if (!Double.isFinite(jumpSaved)) return "On time · effect unclear";
        if (jumpSaved >= PERFECT_REDUCTION) return "Perfect · less pushback";
        if (jumpSaved > MEANINGFUL_REDUCTION) return "On time · some reduction";
        if (jumpSaved < -MEANINGFUL_REDUCTION) return "On time · more pushback";
        return "On time · little reduction";
    }

    private boolean unobstructed(Impact kb, double x, double z) {
        if (!kb.comparable || player.field_70123_F || player.field_70124_G || !Double.isFinite(x) || !Double.isFinite(z))
            return false;
        // Native movement clips Y before X and Z. Check the same sweeps for
        // the no-jump path, replacing only the changes made by jump().
        AxisAlignedBB rise = kb.startBox.func_72321_a(0, kb.noJumpY, 0);
        AxisAlignedBB step = kb.startBox.func_72317_d(0, kb.noJumpY, 0)
            .func_72321_a(x - kb.jumpX, 0, z - kb.jumpZ);
        Minecraft mc = Minecraft.func_71410_x();
        return mc.field_71441_e.func_72945_a(player, rise).isEmpty() &&
            mc.field_71441_e.func_72945_a(player, step).isEmpty();
    }

    private static String ticks(String prefix, int count) {
        return prefix + count + (count == 1 ? " tick" : " ticks");
    }

    private static String timing(String prefix, int count) {
        return ticks(prefix, count) + " (~" + count * NOMINAL_TICK_MS + " ms)";
    }

    private void showJump(String title, int color) {
        String detail = String.format(Locale.ROOT, "%.2f blocks back", jumpBack);
        if (Double.isFinite(jumpSaved) && Math.abs(jumpSaved) > MEANINGFUL_REDUCTION)
            detail = String.format(Locale.ROOT, "%.2f %s · %s", Math.abs(jumpSaved), jumpSaved > 0 ? "less" : "more", detail);
        if (knockbackDelay > 0) detail += " · " + ticks("KB +", knockbackDelay);
        jumpNote = new Note(title, detail, color);
        if (VERIFY) System.out.println("[CombatCoach] jump " + System.currentTimeMillis() + " " + title + " | " +
            jumpBack + " " + jumpSaved + " " + knockbackDelay);
    }

    static Frame hud(Frame base, int height) {
        CombatCoach c = INSTANCE;
        if (c.suspended || c.player == null || (!c.block && !c.jump)) return base;
        List<Frame.Line> lines = new ArrayList<>(base.lines);
        note(lines, c.jumpNote, height / 2 + 34, "Jump reset · sprint into the hit", c.jump);
        note(lines, c.blockNote, height / 2 + (c.jump ? 66 : 34), "Blockhit · time your sword guard", c.block);
        return new Frame(base.holdSprint, lines, base.edgeColor, base.edgeWidth, base.rectangles);
    }

    private static void note(List<Frame.Line> lines, Note note, int y, String idle, boolean enabled) {
        if (!enabled) return;
        if (note == null || System.nanoTime() >= note.until) {
            lines.add(new Frame.Line(idle, 0, y, 0xAAAAAA, false, 1, true));
            return;
        }
        lines.add(new Frame.Line(note.title, 0, y, note.color, false, 1.5f, true));
        lines.add(new Frame.Line(note.detail, 0, y + 15, 0xFFFFFF, false, 1, true));
    }
}
