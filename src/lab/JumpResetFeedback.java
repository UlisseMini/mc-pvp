package lab;

import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Map;

/** Infers takeoff from vanilla position reports bracketed around a native melee impulse. */
public final class JumpResetFeedback {
    private static final double JUMP_RISE = .42f;
    private static final double EPSILON = 1e-6;
    private static final long TIMEOUT_MS = 1200;
    private static final Map<Object, JumpResetFeedback> active = new IdentityHashMap<>();
    private static short marker = Short.MIN_VALUE;

    record Movement(double x, double y, double z, boolean grounded, boolean hasPosition) {}
    enum Grade {
        ON_TIME("§aPerfect"), EARLY("§eEarly"), MISSED("§eMissed impact tick"), AIRBORNE("§eAirborne at impact"),
        UNKNOWN("§7Timing unclear");

        final String label;
        Grade(String label) { this.label = label; }
    }

    private static final class Impact {
        final Object velocityPacket;
        final short begin, end;
        final double vx, vy, vz;
        final long sentAt;
        Movement previous;
        int earlyTicks;
        boolean opened, closed, movedInside;

        Impact(Object packet, short begin, short end, double vx, double vy, double vz, long sentAt) {
            velocityPacket = packet;
            this.begin = begin;
            this.end = end;
            this.vx = vx;
            this.vy = vy;
            this.vz = vz;
            this.sentAt = sentAt;
        }
    }

    private final Referee.Match match;
    private final Object handler, bot;
    private Movement last;
    private Impact impact;
    private Grade grade;
    private double back;
    private int timed, graded, movementTick, takeoffTick, earlyTicks;

    JumpResetFeedback(Referee.Match match, Object player, Object bot) {
        this.match = match;
        this.bot = bot;
        handler = R.get(player, "playerNetServerHandler");
        active.put(handler, this);
    }

    void close() { active.remove(handler); }

    /** S32 is acknowledged on the client's game thread, in the same ordered
     * queue as S12. It changes no inventory on the client. Keep drill IDs away
     * from ordinary clicks and skip the last real pending inventory ID. */
    private static short nextMarker(Object handler) {
        Object pending = R.call(R.get(handler, "field_147372_n"), "lookup", 0);
        short id;
        do {
            id = marker++;
            if (marker == -16384) marker = Short.MIN_VALUE;
        } while (Short.valueOf(id).equals(pending));
        return id;
    }

    private void sendMarker(short id) {
        Object packet = R.construct("net.minecraft.network.play.server.S32PacketConfirmTransaction", 0, id, false);
        R.call(handler, "sendPacket", packet);
    }

    public static void beforeVelocity(Object handler, Object packet, Object attacker) {
        JumpResetFeedback feedback = active.get(handler);
        if (feedback == null || feedback.bot != attacker) return;
        long now = System.currentTimeMillis();
        if (now < feedback.match.start) return;
        double vx = R.num(packet, "motionX") / 8000, vy = R.num(packet, "motionY") / 8000;
        double vz = R.num(packet, "motionZ") / 8000;
        if (vx == 0 && vz == 0) return;
        if (feedback.impact != null) feedback.finish(Grade.UNKNOWN, null);
        short begin = nextMarker(handler), end = nextMarker(handler);
        feedback.impact = new Impact(packet, begin, end, vx, vy, vz, now);
        feedback.sendMarker(begin);
    }

    public static void afterVelocity(Object handler, Object packet) {
        JumpResetFeedback feedback = active.get(handler);
        if (feedback != null && feedback.impact != null && feedback.impact.velocityPacket == packet)
            feedback.sendMarker(feedback.impact.end);
    }

    // Called after vanilla processes the confirmation on the server thread.
    public static void confirm(Object handler, Object packet) {
        JumpResetFeedback feedback = active.get(handler);
        if (feedback == null || feedback.impact == null || R.num(packet, "windowId") != 0) return;
        Impact hit = feedback.impact;
        int id = ((Number) R.call(packet, "getUid")).intValue();
        if (id == hit.begin && !hit.opened) {
            hit.opened = true;
            hit.previous = feedback.last;
            if (feedback.takeoffTick > 0) hit.earlyTicks = feedback.movementTick + 1 - feedback.takeoffTick;
        } else if (id == hit.end && !hit.closed) {
            if (hit.opened && !hit.movedInside) hit.closed = true;
            else feedback.finish(Grade.UNKNOWN, null);
        }
    }

    // Invoked only after vanilla accepts an ordinary movement packet.
    public static void moved(Object handler, Object packet) {
        JumpResetFeedback feedback = active.get(handler);
        if (feedback == null) return;
        feedback.movementTick++;
        Movement old = feedback.last;
        boolean ground = (Boolean) R.call(packet, "isOnGround");
        boolean hasPosition = (Boolean) R.call(packet, "isMoving");
        Movement step = hasPosition ? new Movement((double) R.call(packet, "getPositionX"),
            (double) R.call(packet, "getPositionY"), (double) R.call(packet, "getPositionZ"), ground, true)
            : old == null ? null : new Movement(old.x, old.y, old.z, ground, false);
        feedback.last = step;
        Impact hit = feedback.impact;
        if (step != null && step.grounded) feedback.takeoffTick = 0;
        if (hit == null || !hit.opened) {
            // A movement sent before the first acknowledgement precedes this
            // hit. The exact .42F jump rise also differs from packet-quantized .42.
            if (floorLaunch(old, step) && Math.abs(step.y - old.y - JUMP_RISE) < 1e-9)
                feedback.takeoffTick = feedback.movementTick;
        } else if (hit.closed) feedback.finish(classify(hit.previous, step, hit.vy, hit.earlyTicks), step);
        else hit.movedInside = true;
    }

    public static void corrected(Object handler) {
        JumpResetFeedback feedback = active.get(handler);
        if (feedback == null) return;
        feedback.last = null;
        feedback.takeoffTick = 0;
        if (feedback.impact != null) feedback.finish(Grade.UNKNOWN, null);
    }

    private static boolean floorLaunch(Movement before, Movement after) {
        return before != null && after != null && before.grounded && after.hasPosition &&
            !after.grounded && Math.abs(before.y - SumoArena.TOP_Y) <= EPSILON;
    }

    static Grade classify(Movement before, Movement after, double vy, int earlyTicks) {
        if (before == null || after == null) return Grade.UNKNOWN;
        if (!before.grounded) return earlyTicks > 0 ? Grade.EARLY : Grade.AIRBORNE;
        if (!floorLaunch(before, after)) return Grade.UNKNOWN;
        double dy = after.y - before.y;
        boolean jumped = Math.abs(dy - JUMP_RISE) < EPSILON;
        boolean launched = vy > 0 && Math.abs(dy - vy) < EPSILON;
        if (jumped == launched) return Grade.UNKNOWN;
        return jumped ? Grade.ON_TIME : Grade.MISSED;
    }

    private void finish(Grade result, Movement after) {
        Impact hit = impact;
        impact = null;
        takeoffTick = 0;
        grade = result;
        earlyTicks = grade == Grade.EARLY ? hit.earlyTicks : 0;
        back = Double.NaN;
        if (grade == Grade.ON_TIME || grade == Grade.MISSED || grade == Grade.EARLY) {
            Movement before = hit.previous;
            back = Math.max(0, ((after.x - before.x) * hit.vx + (after.z - before.z) * hit.vz) / Math.hypot(hit.vx, hit.vz));
            graded++;
            if (grade == Grade.ON_TIME) timed++;
        }
        Referee.append(match.replay, Map.of("type", "jump_reset_feedback", "time", System.currentTimeMillis(),
            "grade", grade.name(), "first_step_back", Double.isFinite(back) ? back : -1,
            "timed", timed, "graded", graded, "early_ticks", earlyTicks, "hit_sent_at", hit.sentAt));
        Referee.overlay(R.get(handler, "playerEntity"), text());
        if (grade == Grade.ON_TIME || grade == Grade.EARLY || grade == Grade.MISSED) showTitle();
    }

    private void showTitle() {
        String title = switch (grade) {
            case ON_TIME -> "PERFECT";
            case EARLY -> "EARLY";
            default -> "MISSED";
        };
        String distance = String.format(Locale.ROOT, "~%.2f blocks back", back);
        String subtitle = grade == Grade.EARLY ? tickOffset() + " early · " + distance : distance;
        String command = "title " + match.a + " ";
        Referee.vanilla(command + "times 0 20 4");
        Referee.vanilla(command + "subtitle " + Json.encode(Map.of("text", subtitle, "color", "white")));
        Referee.vanilla(command + "title " + Json.encode(Map.of(
            "text", title, "color", grade == Grade.ON_TIME ? "green" : "yellow", "bold", true)));
    }

    private String tickOffset() {
        return earlyTicks + (earlyTicks == 1 ? " tick" : " ticks");
    }

    String text(long now) {
        if (impact != null && now - impact.sentAt > TIMEOUT_MS) finish(Grade.UNKNOWN, null);
        return text();
    }

    private String text() {
        if (grade == null) return "§bJump Reset §8| §fSprint in and tap Jump on impact";
        String distance = Double.isFinite(back) ? String.format(Locale.ROOT, " §8| §f~%.2f blocks back", back) : "";
        String offset = earlyTicks > 0 ? " by " + tickOffset() : "";
        return grade.label + offset + distance + " §8| §7" + timed + "/" + graded + " on time";
    }
}
