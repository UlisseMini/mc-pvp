package practice.module;

import practice.api.Frame;
import practice.api.PracticeEvent;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Scores native takeoffs against velocity applied on the owning client's game thread. */
final class JumpResetHud {
    private static final int LATE_WINDOW_TICKS = 4;

    private static final class Step {
        final int id, jumpTicks;
        final boolean grounded, sprinting, jumping;
        final double x, y, z;

        Step(Map<?, ?> self, List<?> pos) {
            id = ((Number) self.get("id")).intValue();
            Object cooldown = self.get("jumpTicks");
            jumpTicks = cooldown instanceof Number ? ((Number) cooldown).intValue() : -1;
            grounded = Boolean.TRUE.equals(self.get("ground"));
            sprinting = Boolean.TRUE.equals(self.get("sprinting"));
            Object movement = self.get("movement_input");
            jumping = movement instanceof Map && Boolean.TRUE.equals(((Map<?, ?>) movement).get("jump"));
            x = ((Number) pos.get(0)).doubleValue();
            y = ((Number) pos.get(1)).doubleValue();
            z = ((Number) pos.get(2)).doubleValue();
        }
    }

    private static final class Velocity {
        final int id, x, z;

        Velocity(int id, int x, int z) {
            this.id = id;
            this.x = x;
            this.z = z;
        }
    }

    private final Map<Long, Velocity> packets = new LinkedHashMap<>();
    private String match = "", result = "", distance = "";
    private int round, attempts, timed, color, selfId = -1;
    private int movementTick, takeoffTick, missedTick;
    private long startedAt, lastLink, lateUntil;
    private Step before;
    private Velocity hit;

    void event(PracticeEvent event) {
        Map<String, Object> data = event.data;
        if (event.type.equals("link_state")) {
            Object drill = data.get("drill");
            if (!(drill instanceof Map) || !"jumpreset".equals(((Map<?, ?>) drill).get("preset"))) {
                clear();
                return;
            }
            String next = (String) data.get("match");
            int nextRound = ((Number) ((Map<?, ?>) drill).get("round")).intValue();
            if (!match.equals(next)) {
                if (nextRound <= round) clear();
                match = next;
                round = nextRound;
                packets.clear();
                hit = null;
                before = null;
                selfId = -1;
                movementTick = takeoffTick = missedTick = 0;
                lateUntil = 0;
                result = distance = "";
            }
            startedAt = ((Number) data.get("combat_start")).longValue();
            lastLink = event.timeMillis;
        }
        if (match.isEmpty()) return;
        switch (event.type) {
            case "packet_wire":
                if ("in".equals(data.get("direction")) && "S12PacketEntityVelocity".equals(data.get("kind"))) {
                    Velocity velocity = decode(data.get("base64"));
                    if (velocity != null) {
                        packets.put(((Number) data.get("packet_id")).longValue(), velocity);
                        if (packets.size() > 32) packets.remove(packets.keySet().iterator().next());
                    }
                }
                break;
            case "packet_handler_return":
                if (!Boolean.TRUE.equals(data.get("main_thread"))) break;
                if ("S08PacketPlayerPosLook".equals(data.get("kind"))) {
                    hit = null;
                    before = null;
                    takeoffTick = missedTick = 0;
                    lateUntil = 0;
                } else if ("S12PacketEntityVelocity".equals(data.get("kind"))) {
                    Velocity velocity = packets.remove(((Number) data.get("packet_id")).longValue());
                    if (velocity != null && selfId == velocity.id) {
                        // Round resets send zero velocity. Discard any earlier impulse they replaced.
                        hit = event.timeMillis >= startedAt && (velocity.x != 0 || velocity.z != 0) ? velocity : null;
                    }
                }
                break;
            case "tick_start":
                before = step(data);
                if (before != null) selfId = before.id;
                break;
            case "tick_end":
                finishStep(step(data), event.timeMillis);
                break;
            default:
                break;
        }
    }

    private static Velocity decode(Object encoded) {
        if (!(encoded instanceof String)) return null;
        try {
            ByteBuffer bytes = ByteBuffer.wrap(Base64.getDecoder().decode((String) encoded));
            if (bytes.remaining() < 8 || bytes.remaining() > 12 || bytes.get() != 0x12) return null;
            int id = 0;
            for (int shift = 0; bytes.remaining() > 6 && shift < 35; shift += 7) {
                int part = bytes.get() & 255;
                id |= (part & 127) << shift;
                if ((part & 128) != 0) continue;
                if (bytes.remaining() != 6) return null;
                short x = bytes.getShort();
                bytes.getShort(); // vertical velocity
                return new Velocity(id, x, bytes.getShort());
            }
        } catch (IllegalArgumentException ignored) {
            // Ignore malformed or incomplete observations.
        }
        return null;
    }

    private static Step step(Map<String, Object> data) {
        if (data.get("screen") != null || !(data.get("self") instanceof Map)) return null;
        Map<?, ?> self = (Map<?, ?>) data.get("self");
        Object pos = self.get("pos");
        return pos instanceof List && ((List<?>) pos).size() == 3 ? new Step(self, (List<?>) pos) : null;
    }

    private void finishStep(Step after, long now) {
        if (before != null && after != null && before.id == after.id && after.jumpTicks >= 0) {
            movementTick++;
            // Vanilla sets jumpTicks to 10 on takeoff. Its unboosted arena jump moves Y by .42F.
            boolean takeoff = before.grounded && after.jumpTicks == 10 && Math.abs(after.y - before.y - .42f) < 1e-6;
            if (hit != null && hit.id == before.id) {
                missedTick = 0;
                lateUntil = 0;
                if (before.grounded || takeoffTick > 0) attempts++;
                if (takeoff) {
                    timed++;
                    result = after.sprinting ? "Perfect timing" : "Perfect timing · sprint into the hit";
                    color = 0x77FFAA;
                } else {
                    if (before.grounded) {
                        result = after.jumping ? "Jump didn't take off" : "Missed impact tick";
                        if (!after.grounded && !after.jumping) missedTick = movementTick;
                    } else {
                        result = takeoffTick > 0 ? offset("Early", movementTick - takeoffTick) : "Airborne at impact";
                    }
                    color = 0xFFBB66;
                }
                double back = ((after.x - before.x) * hit.x + (after.z - before.z) * hit.z) / Math.hypot(hit.x, hit.z);
                distance = String.format(Locale.ROOT, " · %.2f blocks back", Math.max(0, back));
                takeoffTick = 0;
            } else {
                if (missedTick > 0) {
                    int late = movementTick - missedTick;
                    if (!before.grounded && after.jumping && !before.jumping && late <= LATE_WINDOW_TICKS) {
                        result = offset("Late", late);
                        missedTick = 0;
                        lateUntil = now + 1000;
                    } else if (after.grounded || late >= LATE_WINDOW_TICKS) missedTick = 0;
                }
                if (takeoff) takeoffTick = movementTick;
                else if (after.grounded) takeoffTick = 0;
            }
        } else takeoffTick = missedTick = 0;
        hit = null;
        before = null;
    }

    private static String offset(String kind, int ticks) {
        return kind + " by " + ticks + (ticks == 1 ? " tick" : " ticks");
    }

    Frame hud(Frame base, long now) {
        if (match.isEmpty() || now - lastLink > 1500) return base;
        List<Frame.Line> lines = new ArrayList<>(base.lines);
        lines.add(new Frame.Line("Jump Reset · " + timed + "/" + attempts + " on time", 0, 14, 0xFFFFFF, false, 1, true));
        String cue = now < startedAt ? "Get ready" : result.isEmpty() ? "Sprint toward W-tap and jump on impact" : result + distance;
        lines.add(new Frame.Line(cue, 0, 26, result.isEmpty() ? 0xAAAAAA : color, false, 1, true));
        return new Frame(base.holdSprint, lines, base.edgeColor, base.edgeWidth, base.rectangles);
    }

    Frame renderLate(Frame base, int height, long now) {
        if (match.isEmpty() || now - lastLink > 1500 || now >= lateUntil) return base;
        List<Frame.Line> lines = new ArrayList<>(base.lines);
        lines.add(new Frame.Line(result.toUpperCase(Locale.ROOT), 0, height / 2 + 34, 0xFFBB66, false, 2, true));
        return new Frame(base.holdSprint, lines, base.edgeColor, base.edgeWidth, base.rectangles);
    }

    void clear() {
        match = "";
        round = attempts = timed = 0;
        selfId = -1;
        before = null;
        hit = null;
        packets.clear();
        movementTick = takeoffTick = missedTick = 0;
        lateUntil = 0;
    }
}
