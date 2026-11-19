package lab;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/** Local approximation based on a public first-hit fit and landing prediction. */
public final class MmcKnockback {
    private static final Map<Object, Fighter> players = new IdentityHashMap<>();

    private static final class Fighter {
        final Object entity, handler;
        double floor, lastY, lastDy;
        boolean moved, hasDy, grounded, capped;

        Fighter(Object entity, double floor) {
            this.entity = entity;
            this.floor = floor;
            handler = R.get(entity, "playerNetServerHandler");
            players.put(handler, this);
        }
    }

    private final Referee.Match match;
    private final Fighter a, b;

    MmcKnockback(Referee.Match match, Object first, Object second) {
        this.match = match;
        double floor = match.sumo() ? SumoArena.TOP_Y : 4;
        a = new Fighter(first, floor);
        b = new Fighter(second, floor);
    }

    void close() {
        players.remove(a.handler);
        players.remove(b.handler);
    }


    public static void playerMove(Object handler, Object packet) {
        Fighter player = players.get(handler);
        if (player == null) return;
        boolean onGround = (Boolean) R.call(packet, "isOnGround");
        boolean position = (Boolean) R.call(packet, "isMoving");
        double y = R.num(player.entity, "posY");
        player.hasDy = position && player.moved;
        player.lastDy = y - player.lastY;
        player.lastY = y;
        player.moved = position;
        player.grounded = onGround;
        if (onGround) {
            player.floor = y;
            player.capped = false;
        }
    }

    public static void corrected(Object handler) {
        Fighter player = players.get(handler);
        if (player == null) return;
        player.moved = player.hasDy = player.grounded = player.capped = false;
    }

    static boolean landsBeforeDelivery(double height, double lastDy, double addedRtt) {
        if (height <= 0 || height > 1.3 || addedRtt <= 25) return false;
        int ticks = (int) Math.ceil((addedRtt - 25) / 50);
        double vy = lastDy;
        for (int tick = 0; tick < ticks; tick++) {
            vy = (vy - .08) * .9800000190734863;
            height += vy;
            if (height <= 0) return true;
        }
        return false;
    }

    void outgoing(Object attacker, Object target, boolean preAttackSprint) {
        Fighter victim = target == a.entity ? a : b;
        CombatProfile profile = match.combat;
        double vx = R.num(target, "posX") - R.num(attacker, "posX");
        double vz = R.num(target, "posZ") - R.num(attacker, "posZ");
        double yaw = Math.toRadians(R.num(attacker, "rotationYaw"));
        double facingX = -Math.sin(yaw), facingZ = Math.cos(yaw);
        double length = Math.hypot(vx, vz);
        int extra = ((Number) R.call(R.cls("net.minecraft.enchantment.EnchantmentHelper"), "getKnockbackModifier", attacker)).intValue();
        if (preAttackSprint) extra++;
        vx = (length < .01 ? facingX : vx / length) * CombatProfile.MMC_HORIZONTAL +
            facingX * extra * CombatProfile.MMC_SPRINT_EXTRA;
        vz = (length < .01 ? facingZ : vz / length) * CombatProfile.MMC_HORIZONTAL +
            facingZ * extra * CombatProfile.MMC_SPRINT_EXTRA;
        double height = R.num(target, "posY") - victim.floor;
        if (height > CombatProfile.MMC_HEIGHT_LIMIT) victim.capped = true;
        double rtt = victim == a ? match.pingA : match.pingB;
        boolean supported = !match.sumo() || SumoArena.supports(match.arena, R.num(target, "posX"), R.num(target, "posZ"));
        boolean landing = supported && victim.hasDy && !victim.grounded &&
            landsBeforeDelivery(height, victim.lastDy, rtt);
        double vy = victim.capped && !landing ? 0 : CombatProfile.MMC_VERTICAL + extra * profile.sprintVertical();
        vx *= profile.horizontal();
        vz *= profile.horizontal();
        vy *= profile.vertical();
        R.set(target, "motionX", vx);
        R.set(target, "motionY", vy);
        R.set(target, "motionZ", vz);
        Referee.append(match.replay, Map.of("type", "mmc_knockback", "time", System.currentTimeMillis(),
            "target", R.name(target), "velocity", List.of(vx, vy, vz), "extra_levels", extra, "pre_attack_server_sprint", preAttackSprint,
            "victim_height", height, "height_capped", victim.capped, "predicted_landing", landing));
    }
}
