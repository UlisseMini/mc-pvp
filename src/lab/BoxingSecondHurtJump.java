package lab;

import java.util.ArrayDeque;

/** A single ordinary grounded jump at the second locally observed incoming hurt. */
public final class BoxingSecondHurtJump {
    public enum Mode { OBSERVE, VISIBLE, GUARDED }
    public enum Decision { NONE, OBSERVE, JUMP, SUPPRESSED }

    private static final double ACTIVE_GAP_MS = 1100;
    private static final double MAX_CONTACT_AGE_MS = 240;
    private final Mode mode;
    private final double replyBufferMs;
    private final double serviceDelayMs;
    private final ArrayDeque<Double> contacts = new ArrayDeque<>();
    private double latestHurt = Double.NEGATIVE_INFINITY;
    private double evaluatedHurt = Double.NEGATIVE_INFINITY;
    private int ownStreak;

    public BoxingSecondHurtJump(Mode mode) {
        this(mode,25,24);
    }

    public BoxingSecondHurtJump(Mode mode, double replyBufferMs, double serviceDelayMs) {
        this.mode = mode;
        this.replyBufferMs = replyBufferMs;
        this.serviceDelayMs = serviceDelayMs;
    }

    public BoxingSecondHurtJump(BoxingSecondHurtJump source, Mode mode) {
        this(mode,source.replyBufferMs,source.serviceDelayMs);
        latestHurt = source.latestHurt;
        evaluatedHurt = source.evaluatedHurt;
        ownStreak = source.ownStreak;
        contacts.addAll(source.contacts);
    }

    public Mode mode() { return mode; }

    public void ownHurt(double at) {
        if (at-latestHurt > ACTIVE_GAP_MS) ownStreak = 0;
        latestHurt = at;
        ownStreak++;
    }

    public void targetHurt() { ownStreak = 0; }

    public void contact(double at) { contacts.addLast(at); }

    public Decision decide(double now, boolean receivedImpact, boolean grounded, double rtt) {
        while (!contacts.isEmpty() && contacts.peekFirst() < now-MAX_CONTACT_AGE_MS) contacts.removeFirst();
        if (!receivedImpact || latestHurt <= evaluatedHurt) return Decision.NONE;
        evaluatedHurt = latestHurt;
        if (!grounded || ownStreak != 2 || now-latestHurt > ACTIVE_GAP_MS) return Decision.NONE;
        if (mode == Mode.OBSERVE) return Decision.OBSERVE;
        if (mode == Mode.GUARDED) {
            for (double contact : contacts) {
                if (latestHurt-contact <= rtt+replyBufferMs && now-contact >= rtt/2+serviceDelayMs)
                    return Decision.SUPPRESSED;
            }
        }
        return Decision.JUMP;
    }
}
