package practice.bridge;

import java.util.*;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemSword;
import net.minecraft.network.play.client.C07PacketPlayerDigging;
import net.minecraft.network.play.client.C08PacketPlayerBlockPlacement;
import net.minecraft.network.play.client.C09PacketHeldItemChange;
import net.minecraft.network.play.server.S06PacketUpdateHealth;
import net.minecraft.network.play.server.S08PacketPlayerPosLook;
import net.minecraft.network.play.server.S0BPacketAnimation;
import net.minecraft.network.play.server.S12PacketEntityVelocity;
import net.minecraft.network.play.server.S19PacketEntityStatus;
import net.minecraft.network.play.server.S1CPacketEntityMetadata;
import net.minecraft.network.play.server.S1EPacketRemoveEntityEffect;
import net.minecraft.potion.Potion;
import net.minecraft.network.play.server.S29PacketSoundEffect;
import net.minecraft.util.AxisAlignedBB;
import practice.api.Frame;
import practice.core.BlockhitGuard;
import practice.core.BlockhitTimeline;
import practice.core.BlockhitTimeline.Hit;
import practice.core.CombatCoachMath;
import practice.core.CombatCoachMath.Block;
import practice.core.CombatCoachMath.DamageMatch;

/** Read-only coaching uses this client's applied packets and native movement boundaries. */
final class CombatCoach {
    private static final long DISPLAY = 1_800_000_000L;
    private static final int HIT_WINDOW = BlockhitTimeline.HURT_TICKS;
    private static final int PACKET_PAIR_WINDOW = BlockhitTimeline.PAIR_TICKS;
    private static final int JUMP_LATE_WINDOW = 4;
    private static final int NOMINAL_TICK_MS = 50;
    private static final long GUARD_QUIET = 650_000_000L;
    private static final double MEANINGFUL_REDUCTION = CombatCoachMath.MEANINGFUL_JUMP_REDUCTION;
    private static final double PERFECT_REDUCTION = .18;
    private static final String BLOCK_CHIME = "random.anvil_land";
    private static final float BLOCK_CHIME_VOLUME = .25f, BLOCK_CHIME_PITCH = 1.6f;
    private static final boolean VERIFY = Boolean.getBoolean("practice.coach.verify");
    private static final CombatCoach INSTANCE = new CombatCoach();

    static boolean jumpEnabled() { return INSTANCE.jump; }

    enum Hud { HOTBAR, CENTER, OFF }

    enum JumpSound {
        ORB("random.orb"), HARP("note.harp"), PLING("note.pling"), LEVELUP("random.levelup");

        final String event;
        JumpSound(String event) { this.event = event; }
    }

    private static <T extends Enum<T>> T preference(String value, T fallback) {
        try { return Enum.valueOf(fallback.getDeclaringClass(), value.toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException invalid) { return fallback; }
    }

    private static final class Defense {
        final float hp, absorption;
        final int armor, resistance, protection;
        final List<MeleeObservations.Candidate> candidates;
        final boolean mixed, arrowThreat, rodCue, open, settledGuard, directionReliable;

        Defense(Minecraft mc, CombatCoach coach, long received) {
            EntityPlayerSP player = coach.player;
            hp = coach.health;
            absorption = coach.absorption;
            armor = player.func_70658_aO();
            resistance = MeleeObservations.resistance(player);
            protection = MeleeObservations.protection(player);
            candidates = coach.candidates(mc);
            mixed = MeleeObservations.mixedSources(mc, player);
            arrowThreat = MeleeObservations.arrowThreat(mc, player);
            rodCue = MeleeObservations.nearbyRod(mc, player) || candidates.stream().anyMatch(c -> c.rod);
            settledGuard = coach.settledGuard(mc, received);
            open = !player.func_70632_aY() && received - coach.lastGuard >= GUARD_QUIET && !settledGuard;
            directionReliable = player.field_70122_E && Math.hypot(player.field_70159_w, player.field_70179_y) <= .28;
        }
    }

    private static final class Swing {
        final int id, tick;
        Swing(int id, int tick) { this.id = id; this.tick = tick; }
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
    private Hud jumpHud = Hud.HOTBAR, blockHud = Hud.HOTBAR;
    private JumpSound jumpSound = JumpSound.ORB;
    private volatile boolean suspended;
    private int movement, blockTick, lastHurt = -1000;
    private int lastTakeoff = -1000, takeoffFor = -1000, missed = -1000;
    private boolean previousJump, jumpKey, delaying, overlappingHurt, meleeCue;
    private double preJumpX, preJumpY, preJumpZ, changeX, changeZ;
    private double jumpBack, jumpSaved;
    private int knockbackDelay;
    private float health, absorption;
    private Note blockNote, jumpNote;
    private Impact pending, moving;
    private final BlockhitTimeline<Defense> damage = new BlockhitTimeline<>();
    private final BlockhitGuard guardPackets = new BlockhitGuard();
    private final Map<Object, Long> receivedTimes = Collections.synchronizedMap(new WeakHashMap<>());
    private int absorptionRemovedAt = -1000, arrowCount;
    private Set<Float> previousRaw = Collections.emptySet();
    private int knownDamageTick = -1000;
    private long lastGuard, guardStartedAt;
    private int guardDuration;
    private final Deque<Swing> swings = new ArrayDeque<>();
    private final Map<String, Set<Float>> learnedWeapons = new HashMap<>();
    private final Map<String, Set<Float>> provisionalWeapons = new HashMap<>();
    private final Deque<List<MeleeObservations.Candidate>> history = new ArrayDeque<>();

    static void settings(Properties settings) {
        OldBlockAnimation.enabled = Boolean.parseBoolean(settings.getProperty("oldblock.enabled", "true"));
        boolean block = Boolean.parseBoolean(settings.getProperty("blockhit.enabled", "false"));
        boolean jump = Boolean.parseBoolean(settings.getProperty("jumpreset.enabled", "false"));
        if (INSTANCE.block != block || INSTANCE.jump != jump) INSTANCE.reset(null, null);
        INSTANCE.block = block;
        INSTANCE.jump = jump;
        INSTANCE.jumpHud = preference(settings.getProperty("jumpreset.hud", "hotbar"), Hud.HOTBAR);
        INSTANCE.blockHud = preference(settings.getProperty("blockhit.hud", "hotbar"), Hud.HOTBAR);
        INSTANCE.jumpSound = preference(settings.getProperty("jumpreset.sound", "orb"), JumpSound.ORB);
    }

    static void tick(Minecraft mc, boolean suspended) {
        INSTANCE.suspended = suspended;
        CombatCoach coach = INSTANCE;
        if ((coach.block || coach.jump) && coach.bind(mc) && coach.block) {
            coach.blockTick++;
            coach.resolveDamage();
            while (!coach.swings.isEmpty() &&
                    coach.blockTick - coach.swings.peekFirst().tick > HIT_WINDOW + PACKET_PAIR_WINDOW)
                coach.swings.removeFirst();
        }
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
        if (next != player || nextWorld != world) {
            learnedWeapons.clear();
            provisionalWeapons.clear();
            guardPackets.clear();
            lastGuard = System.nanoTime();
        }
        player = next;
        world = nextWorld;
        health = next == null ? 0 : next.func_110143_aJ();
        absorption = next == null ? 0 : next.func_110139_bj();
        arrowCount = next == null ? 0 : next.func_85035_bI();
        guardDuration = 0;
        movement = blockTick = 0;
        lastHurt = lastTakeoff = takeoffFor = missed = absorptionRemovedAt = -1000;
        previousJump = jumpKey = delaying = overlappingHurt = meleeCue = false;
        pending = moving = null;
        damage.clear();
        previousRaw = Collections.emptySet();
        knownDamageTick = -1000;
        blockNote = jumpNote = null;
        history.clear();
        swings.clear();
    }

    static void packet(Object packet, boolean after) {
        CombatCoach coach = INSTANCE;
        if ((!coach.jump && !coach.block) || !after ||
            !(packet instanceof S19PacketEntityStatus || packet instanceof S12PacketEntityVelocity ||
                packet instanceof S06PacketUpdateHealth || packet instanceof S08PacketPlayerPosLook ||
                coach.block && (packet instanceof S29PacketSoundEffect || packet instanceof S0BPacketAnimation ||
                    packet instanceof S1CPacketEntityMetadata || packet instanceof S1EPacketRemoveEntityEffect))) return;
        Minecraft mc = Minecraft.func_71410_x();
        if (!mc.func_152345_ab() || !coach.bind(mc)) return;
        Long encoded = coach.receivedTimes.remove(packet);
        long received = encoded == null ? System.nanoTime() : encoded;
        if (packet instanceof S19PacketEntityStatus) {
            S19PacketEntityStatus status = (S19PacketEntityStatus) packet;
            if (status.func_149160_c() == 2 && status.func_149161_a(mc.field_71441_e) == coach.player) coach.hurt(mc, received);
        } else if (packet instanceof S12PacketEntityVelocity) {
            S12PacketEntityVelocity velocity = (S12PacketEntityVelocity) packet;
            if (velocity.func_149412_c() == coach.player.func_145782_y()) {
                coach.velocity(velocity.func_149411_d() / 8000.0, velocity.func_149409_f() / 8000.0);
            }
        } else if (packet instanceof S06PacketUpdateHealth) {
            coach.health(mc, ((S06PacketUpdateHealth) packet).func_149332_c(), received);
        } else if (packet instanceof S1CPacketEntityMetadata) {
            if (((S1CPacketEntityMetadata) packet).func_149375_d() == coach.player.func_145782_y())
                coach.metadata(mc, received);
        } else if (packet instanceof S1EPacketRemoveEntityEffect) {
            S1EPacketRemoveEntityEffect effect = (S1EPacketRemoveEntityEffect) packet;
            if (effect.func_149076_c() == coach.player.func_145782_y() &&
                    effect.func_149075_d() == Potion.field_76444_x.field_76415_H)
                coach.absorptionRemovedAt = coach.blockTick;
        } else if (packet instanceof S0BPacketAnimation) {
            S0BPacketAnimation animation = (S0BPacketAnimation) packet;
            if (animation.func_148977_d() == 4 && animation.func_148978_c() == coach.player.func_145782_y())
                coach.damage.critical(coach.blockTick);
            else if (animation.func_148977_d() == 0) coach.swing(mc, animation.func_148978_c());
        } else if (packet instanceof S29PacketSoundEffect) {
            coach.serverSound((S29PacketSoundEffect) packet);
        } else {
            coach.lastHurt = coach.lastTakeoff = coach.takeoffFor = coach.missed = -1000;
            coach.delaying = coach.overlappingHurt = coach.meleeCue = false;
            coach.pending = coach.moving = null;
            coach.jumpNote = null;
            coach.history.clear();
        }
    }

    static void inbound(Object packets) {
        CombatCoach coach = INSTANCE;
        if (!coach.block || coach.suspended || !(packets instanceof List)) return;
        for (Object packet : (List<?>) packets)
            if (packet instanceof S19PacketEntityStatus || packet instanceof S06PacketUpdateHealth)
                coach.receivedTimes.put(packet, System.nanoTime());
    }

    static void outbound(Object context, Object packet) {
        CombatCoach coach = INSTANCE;
        if (!coach.block || coach.suspended || !(context instanceof ChannelHandlerContext) ||
                !(packet instanceof C08PacketPlayerBlockPlacement || packet instanceof C07PacketPlayerDigging ||
                    packet instanceof C09PacketHeldItemChange)) return;
        Channel channel = ((ChannelHandlerContext) context).channel();
        if (channel != ConnectionScope.current()) return;
        boolean guard = false;
        if (packet instanceof C08PacketPlayerBlockPlacement) {
            C08PacketPlayerBlockPlacement use = (C08PacketPlayerBlockPlacement) packet;
            if (use.func_149568_f() != 255) return;
            ItemStack stack = use.func_149574_g();
            guard = stack != null && stack.func_77973_b() instanceof ItemSword;
        } else if (packet instanceof C07PacketPlayerDigging &&
                ((C07PacketPlayerDigging) packet).func_180762_c() != C07PacketPlayerDigging.Action.RELEASE_USE_ITEM) {
            return;
        }
        coach.guardPackets.transition(channel, System.nanoTime(), guard);
    }

    private List<MeleeObservations.Candidate> candidates(Minecraft mc) {
        List<MeleeObservations.Candidate> result = MeleeObservations.nearby(mc, player);
        for (List<MeleeObservations.Candidate> prior : history) result.addAll(prior);
        return result;
    }

    private void swing(Minecraft mc, int id) {
        Entity source = mc.field_71441_e.func_73045_a(id);
        if (source instanceof EntityPlayer && source != player &&
                player.func_70068_e(source) <= MeleeObservations.RANGE * MeleeObservations.RANGE) {
            swings.addLast(new Swing(id, blockTick));
            if (swings.size() > 64) swings.removeFirst();
        }
    }

    private void observeGuard(long now) {
        if (!player.func_70632_aY()) {
            guardDuration = 0;
            return;
        }
        int duration = player.func_71057_bx();
        if (guardDuration == 0 || duration < guardDuration) guardStartedAt = now;
        guardDuration = duration;
        lastGuard = now;
    }

    private boolean settledGuard(Minecraft mc, long received) {
        if (mc.func_147114_u() == null) return false;
        NetworkPlayerInfo info = mc.func_147114_u().func_175102_a(player.func_110124_au());
        int ping = info == null ? 0 : info.func_178853_c();
        if (info != null && ping >= 0 && guardPackets.covers(ConnectionScope.current(), received, ping)) return true;
        // Native-use history also covers enabling the trainer in the middle of a hold.
        return guardDuration >= 3 && received - guardStartedAt >= Math.max(150, ping + 50L) * 1_000_000L;
    }

    private void serverSound(S29PacketSoundEffect sound) {
        // The lab sends its private chime before the hurt packet. Match its
        // quantized pitch and nearby position so the native sound can play once.
        float wirePitch = (int) (BLOCK_CHIME_PITCH * 63) / 63f;
        if (BLOCK_CHIME.equals(sound.func_149212_c()) && sound.func_149208_g() == BLOCK_CHIME_VOLUME &&
                sound.func_149209_h() == wirePitch &&
                player.func_70092_e(sound.func_149207_d(), sound.func_149211_e(), sound.func_149210_f()) <= 9)
            damage.chime(blockTick);
    }

    private void hurt(Minecraft mc, long received) {
        overlappingHurt = delaying && movement - lastHurt <= HIT_WINDOW;
        lastHurt = movement;
        meleeCue = MeleeObservations.hasNearbyPlayer(mc, player) && !MeleeObservations.mixedSources(mc, player);
        delaying = jump && meleeCue;
        if (pending != null) {
            pending.overlapping |= overlappingHurt || pending.hurt >= 0;
            pending.hurt = delaying ? movement : -1000;
        }
        if (!block) return;
        observeGuard(System.nanoTime());
        Defense defense = new Defense(mc, this, received);
        damage.hurt(blockTick, defense, defense.rodCue);
    }

    private void velocity(double x, double z) {
        if (block) damage.push(blockTick, x, z);
        if (!jump) return;
        if (Math.hypot(x, z) < .01) {
            pending = null;
            return;
        }
        int hurt = delaying && movement - lastHurt <= HIT_WINDOW ? lastHurt : -1000;
        pending = new Impact(movement, hurt, x, z, pending != null || overlappingHurt);
    }

    private void health(Minecraft mc, float next, long received) {
        double lost = health - next;
        if (block && lost > .015)
            damage.loss(blockTick, new Defense(mc, this, received), lost, false, absorption > .015);
        health = next;
    }

    private void metadata(Minecraft mc, long received) {
        int nextArrows = player.func_85035_bI();
        if (nextArrows > arrowCount) damage.arrow(blockTick);
        arrowCount = nextArrows;
        float next = player.func_110139_bj();
        double lost = absorption - next;
        if (lost > .015 && blockTick - absorptionRemovedAt > PACKET_PAIR_WINDOW)
            damage.loss(blockTick, new Defense(mc, this, received), lost, true, next <= .015);
        absorption = next;
    }

    private void resolveDamage() {
        Hit<Defense> hit;
        while ((hit = damage.poll(blockTick)) != null) grade(hit);
    }

    private void grade(Hit<Defense> hit) {
        Defense loss = hit.atLoss;
        Defense source = hit.atHurt == null ? loss : hit.atHurt;
        double lost = hit.health + hit.absorption;
        boolean fresh = hit.atHurt != null;
        if (hit.arrow) {
            gradeArrow(hit, source, lost);
            previousRaw = Collections.emptySet();
            knownDamageTick = hit.hurtTick;
            return;
        }
        List<MeleeObservations.Candidate> nearby = new ArrayList<>(source.candidates);
        nearby.addAll(loss.candidates);
        if (!history.isEmpty()) nearby.addAll(history.getLast());
        if (nearby.isEmpty()) {
            if (fresh) previousRaw = Collections.emptySet();
            return;
        }
        int swordSwing = recentSwordSwing(nearby, hit.tick, hit.hurtTick);
        boolean movingPush = hit.clearPush && Math.hypot(hit.pushX, hit.pushZ) >= .01;
        int aligned = movingPush ? MeleeObservations.fromDirection(nearby, hit.pushX, hit.pushZ) : -1;
        boolean mixed = source.mixed || loss.mixed;
        boolean arrowThreat = source.arrowThreat || loss.arrowThreat;
        boolean meleeEvidence = hit.critical || swordSwing != -1 && (!mixed || movingPush && !arrowThreat);
        if (mixed && !meleeEvidence || hit.health == 0 && !fresh && !hit.critical && swordSwing == -1) {
            if (fresh) previousRaw = Collections.emptySet();
            showBlock(Block.UNCERTAIN, false, lost);
            return;
        }

        // Some servers skip hurt status even for a fresh hit. Require a new impulse
        // and corroborated melee outside the window of the last observed damage.
        fresh |= movingPush && meleeEvidence && hit.tick - knownDamageTick > HIT_WINDOW;
        // A stronger hit can update the bars without another hurt packet.
        Set<Float> prior = CombatCoachMath.FRESH;
        if (!fresh) {
            if (hit.tick - knownDamageTick > HIT_WINDOW || previousRaw.isEmpty() || !meleeEvidence) {
                showBlock(Block.UNCERTAIN, false, lost);
                return;
            }
            prior = previousRaw;
        }
        boolean rod = source.rodCue && hit.hurtTick < hit.tick - 1;
        Set<Float> modeled = MeleeObservations.damage(nearby, -1, hit.critical, rod, learnedWeapons);
        DamageMatch match = estimate(hit, modeled, prior);
        Block verdict = match.verdict;
        boolean likely = false;
        if (verdict == Block.UNCERTAIN && !rod && source.directionReliable && aligned != -1) {
            Block directed = estimate(hit, MeleeObservations.damage(nearby, aligned, hit.critical, false, learnedWeapons), prior).verdict;
            if (directed != Block.UNCERTAIN) {
                verdict = directed;
                likely = true;
            }
        }
        boolean settled = fresh ? source.settledGuard : loss.settledGuard;
        boolean nonlethal = hit.health < loss.hp - .015;
        boolean linkedMelee = meleeEvidence && (hit.critical || !arrowThreat &&
            (movingPush || fresh && !mixed && swordSwing != -1));
        if (verdict == Block.UNBLOCKED && settled && linkedMelee && nonlethal) {
            Set<Float> visible = MeleeObservations.damage(nearby, -1, hit.critical, rod);
            if (!visible.equals(modeled) && estimate(hit, visible, prior).verdict == Block.BLOCKED) {
                verdict = Block.BLOCKED;
                likely = true;
            }
        }
        if (verdict == Block.UNCERTAIN && settled && linkedMelee &&
                (nonlethal || hit.critical || aligned == swordSwing && aligned != -1)) {
            verdict = Block.BLOCKED;
            likely = true;
        }

        showBlock(verdict, likely, lost);
        if (verdict == Block.BLOCKED && (!likely || linkedMelee)) chime(hit.serverChime);
        if (!match.rawDamage.isEmpty()) previousRaw = match.rawDamage;
        else if (fresh) previousRaw = meleeEvidence ? modeled : Collections.emptySet();
        if (fresh) {
            knownDamageTick = hit.hurtTick;
            if (hit.atHurt != null && !mixed && !rod && !source.rodCue && source.open && loss.absorption == 0)
                learnWeapon(hit, source.candidates, swordSwing);
        }
        if (VERIFY && (hit.critical || rod))
            System.out.println("[CombatCoach] melee detail " + System.currentTimeMillis() +
                " critical " + hit.critical + " rod " + rod + " upgrade " + !fresh);
    }

    private void gradeArrow(Hit<Defense> hit, Defense defense, double lost) {
        // Vanilla increments the stuck-arrow count only when an arrow hit is accepted.
        // Damage above this bound guarantees that the raw arrow damage exceeded one.
        float onePoint = CombatCoachMath.healthLoss(1, false, hit.atLoss.hp + hit.atLoss.absorption, 0,
            defense.armor, defense.resistance, 0);
        boolean blocked = defense.settledGuard && !hit.critical && lost > onePoint + .015;
        Block verdict = blocked ? Block.BLOCKED : defense.open && hit.atLoss.open && !hit.critical
            ? Block.UNBLOCKED : Block.UNCERTAIN;
        showBlock(verdict, blocked, lost);
        if (blocked) chime(hit.serverChime);
        if (VERIFY) System.out.println("[CombatCoach] arrow " + System.currentTimeMillis() + " " + verdict);
    }

    private void chime(boolean serverChime) {
        if (!serverChime) player.func_85030_a(BLOCK_CHIME, BLOCK_CHIME_VOLUME, BLOCK_CHIME_PITCH);
        if (VERIFY) System.out.println("[CombatCoach] block chime " + System.currentTimeMillis() +
            (serverChime ? " server" : " local"));
    }

    private int recentSwordSwing(List<MeleeObservations.Candidate> candidates, int lossTick, int hurtTick) {
        int attacker = -1;
        for (Swing swing : swings) {
            if (Math.abs(swing.tick - lossTick) > PACKET_PAIR_WINDOW &&
                    Math.abs(swing.tick - hurtTick) > PACKET_PAIR_WINDOW) continue;
            for (MeleeObservations.Candidate c : candidates) {
                if (!c.sword || c.id != swing.id) continue;
                if (attacker != -1 && attacker != c.id) return -1;
                attacker = c.id;
            }
        }
        return attacker;
    }

    private DamageMatch estimate(Hit<Defense> hit, Set<Float> raw, Set<Float> prior) {
        Defense loss = hit.atLoss, defense = hit.atHurt == null ? loss : hit.atHurt;
        return CombatCoachMath.match(hit.health + hit.absorption, loss.hp + loss.absorption, 0,
            defense.armor, defense.resistance, defense.protection, raw, prior);
    }

    private void learnWeapon(Hit<Defense> hit, List<MeleeObservations.Candidate> candidates, int attacker) {
        MeleeObservations.Candidate weapon = null;
        for (MeleeObservations.Candidate c : candidates) {
            if (attacker != -1 && attacker != c.id) continue;
            if (c.weaponKey == null || !hit.critical && !c.grounded) return;
            if (weapon == null) weapon = c;
            else if (!weapon.weaponKey.equals(c.weaponKey) || c.base != weapon.base) return;
        }
        if (weapon == null) return;
        Defense defense = hit.atHurt;
        Set<Float> observed = CombatCoachMath.weaponBonuses(hit.health, hit.atLoss.hp, defense.armor,
            defense.resistance, defense.protection, weapon.base, weapon.visibleBonus, hit.critical);
        if (observed.isEmpty()) return;
        String key = weapon.weaponKey;
        Set<Float> previous = learnedWeapons.get(key);
        Set<Float> repeated = sharedBonuses(previous, observed);
        if (repeated.isEmpty()) repeated = sharedBonuses(provisionalWeapons.get(key), observed);
        if (repeated.isEmpty()) {
            provisionalWeapons.put(key, observed);
            return;
        }
        provisionalWeapons.remove(key);
        learnedWeapons.put(key, repeated);
        if (VERIFY && !repeated.equals(previous))
            System.out.println("[CombatCoach] weapon calibration " + System.currentTimeMillis() + " " + repeated);
    }

    private static Set<Float> sharedBonuses(Set<Float> earlier, Set<Float> observed) {
        Set<Float> shared = new LinkedHashSet<>();
        if (earlier != null)
            for (float candidate : earlier)
                for (float next : observed)
                    if (Math.abs(candidate - next) < .06f) shared.add(candidate);
        return shared;
    }

    private void showBlock(Block verdict, boolean likely, double lost) {
        String title = verdict == Block.BLOCKED ? "Blocked" : verdict == Block.UNBLOCKED ? "Unblocked" : "Block uncertain";
        if (likely) title = "Likely " + title.toLowerCase(Locale.ROOT);
        blockNote = new Note(title, String.format(Locale.ROOT, "%.2f hearts lost", lost / 2),
            verdict == Block.BLOCKED ? 0x77FFAA : 0xFFBB66);
        if (VERIFY) System.out.println("[CombatCoach] block " + System.currentTimeMillis() + " " + verdict +
            " " + likely + " " + lost);
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
        if (block) observeGuard(System.nanoTime());
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
                float volume = CombatCoachMath.jumpCueVolume(jumpSaved);
                if (volume > 0) player.func_85030_a(jumpSound.event, volume, 1f);
            } else if (kb.grounded) {
                if (!jumpKey) missed = movement;
                showJump(jumpKey ? "Jump didn't take off" : "Missed impact tick", 0xFFBB66);
            } else {
                showJump(movement - lastTakeoff <= 14 ? timing("Early by ", movement - lastTakeoff) : "Airborne at impact", 0xFFBB66);
            }
            lastTakeoff = -1000;
        } else if (movement - missed <= JUMP_LATE_WINDOW && jumpKey && !previousJump) {
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
        boolean twoHotbar = c.block && c.blockHud == Hud.HOTBAR && c.jumpHud == Hud.HOTBAR;
        boolean twoCenter = c.jump && c.jumpHud == Hud.CENTER && c.blockHud == Hud.CENTER;
        if (c.jump) note(lines, c.jumpNote, c.jumpHud, height / 2 + 34, height - (twoHotbar ? 68 : 56));
        if (c.block) note(lines, c.blockNote, c.blockHud, height / 2 + (twoCenter ? 66 : 34), height - 56);
        return new Frame(base.holdSprint, lines, base.edgeColor, base.edgeWidth, base.rectangles);
    }

    private static void note(List<Frame.Line> lines, Note note, Hud placement, int centerY, int hotbarY) {
        if (placement == Hud.OFF || note == null || System.nanoTime() >= note.until) return;
        if (placement == Hud.HOTBAR) {
            String text = note.detail.isEmpty() ? note.title : note.title + " · " + note.detail;
            lines.add(new Frame.Line(text, 0, hotbarY, note.color, false, 1, true));
        } else {
            lines.add(new Frame.Line(note.title, 0, centerY, note.color, false, 1.5f, true));
            if (!note.detail.isEmpty()) lines.add(new Frame.Line(note.detail, 0, centerY + 15, 0xFFFFFF, false, 1, true));
        }
    }
}
