package practice.core;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;

/** Joins client observations before grading. A damage update can precede its hurt cue. */
public final class BlockhitTimeline<C> {
    public static final int PAIR_TICKS = 4;
    public static final int HURT_TICKS = 10;
    private static final int CAPACITY = 64;

    private enum Cue { HURT, CRITICAL, PUSH, CHIME, ARROW }

    private static final class Signal<C> {
        final Cue cue;
        final int tick;
        final long order;
        final C context;
        final boolean rod;
        final double x, z;

        Signal(Cue cue, int tick, long order, C context, boolean rod, double x, double z) {
            this.cue = cue;
            this.tick = tick;
            this.order = order;
            this.context = context;
            this.rod = rod;
            this.x = x;
            this.z = z;
        }
    }

    private static final class Loss<C> {
        final int tick;
        final long order;
        final C context;
        final double amount;
        final boolean yellow, splitPossible;

        Loss(int tick, long order, C context, double amount, boolean yellow, boolean splitPossible) {
            this.tick = tick;
            this.order = order;
            this.context = context;
            this.amount = amount;
            this.yellow = yellow;
            this.splitPossible = splitPossible;
        }
    }

    public static final class Hit<C> {
        public final C atLoss, atHurt;
        public final int tick, hurtTick;
        public final double health, absorption;
        public final boolean critical, serverChime, arrow, clearPush;
        public final double pushX, pushZ;

        private Hit(Loss<C> first, Loss<C> second, Signal<C> hurt, Signal<C> push, boolean clearPush,
                    boolean critical, boolean serverChime, boolean arrow) {
            atLoss = first.context;
            atHurt = hurt == null ? null : hurt.context;
            tick = first.tick;
            hurtTick = hurt == null ? first.tick : hurt.tick;
            health = first.yellow ? second == null ? 0 : second.amount : first.amount;
            absorption = first.yellow ? first.amount : second == null ? 0 : second.amount;
            this.critical = critical;
            this.serverChime = serverChime;
            this.clearPush = clearPush && push != null;
            this.arrow = arrow;
            pushX = push == null ? 0 : push.x;
            pushZ = push == null ? 0 : push.z;
        }
    }

    private final Deque<Signal<C>> signals = new ArrayDeque<>();
    private final Deque<Loss<C>> losses = new ArrayDeque<>();
    private long order;

    public void hurt(int tick, C context, boolean rod) { signal(Cue.HURT, tick, context, rod, 0, 0); }
    public void critical(int tick) { signal(Cue.CRITICAL, tick, null, false, 0, 0); }
    public void push(int tick, double x, double z) { signal(Cue.PUSH, tick, null, false, x, z); }
    public void arrow(int tick) { signal(Cue.ARROW, tick, null, false, 0, 0); }
    public void chime(int tick) { signal(Cue.CHIME, tick, null, false, 0, 0); }

    private void signal(Cue cue, int tick, C context, boolean rod, double x, double z) {
        signals.addLast(new Signal<>(cue, tick, ++order, context, rod, x, z));
        if (signals.size() > CAPACITY) signals.removeFirst();
    }

    public void loss(int tick, C context, double amount, boolean yellow, boolean splitPossible) {
        losses.addLast(new Loss<>(tick, ++order, context, amount, yellow, splitPossible));
        if (losses.size() > CAPACITY) losses.removeFirst();
    }

    public void clear() {
        signals.clear();
        losses.clear();
        order = 0;
    }

    public Hit<C> poll(int now) {
        Loss<C> first = losses.peekFirst();
        if (first == null || now - first.tick < PAIR_TICKS) {
            int oldest = first == null ? now : first.tick;
            while (!signals.isEmpty() && oldest - signals.peekFirst().tick > HURT_TICKS) signals.removeFirst();
            return null;
        }
        losses.removeFirst();
        Loss<C> next = losses.peekFirst();
        Signal<C> hurt = findHurt(first, next);
        Loss<C> second = null;
        // Reaching red hearts must consume the rest of the yellow hearts first.
        // A second hurt between the updates points to two separate damage events.
        if (next != null && next.yellow != first.yellow && (first.splitPossible && (!next.yellow || next.splitPossible)) &&
                next.tick - first.tick <= PAIR_TICKS && !interveningHurt(first, next, hurt)) {
            second = losses.removeFirst();
            next = losses.peekFirst();
        }
        long through = second == null ? first.order : second.order;
        if (hurt != null) through = Math.max(through, hurt.order);
        long boundary = next == null ? Long.MAX_VALUE : next.order;
        for (Signal<C> signal : signals) {
            if (signal.cue != Cue.HURT || signal == hurt) continue;
            if (signal.order > (hurt != null && next != null ? hurt.order : through)) {
                boundary = Math.min(boundary, signal.order);
                break;
            }
        }

        Signal<C> push = null;
        boolean clearPush = true, critical = false, chime = false, arrow = false;
        int anchorTick = hurt == null ? first.tick : hurt.tick;
        long start = hurt == null ? 0 : previousHurt(hurt);
        for (Iterator<Signal<C>> it = signals.iterator(); it.hasNext();) {
            Signal<C> signal = it.next();
            if (signal.cue == Cue.HURT) {
                if (hurt != null && signal.order <= hurt.order) it.remove();
                continue;
            }
            if (signal.order <= start || signal.order >= boundary) continue;
            if (signal.cue == Cue.PUSH && Math.abs(signal.tick - anchorTick) <= PAIR_TICKS) {
                if (push != null && (push.x != signal.x || push.z != signal.z)) clearPush = false;
                push = signal;
                it.remove();
            }
            int age = Math.abs(signal.tick - first.tick);
            if (signal.cue == Cue.PUSH || age > PAIR_TICKS ||
                    next != null && age > Math.abs(signal.tick - next.tick)) continue;
            if (signal.cue == Cue.CRITICAL) critical = true;
            if (signal.cue == Cue.CHIME) chime = true;
            if (signal.cue == Cue.ARROW) arrow = true;
            it.remove();
        }
        return new Hit<>(first, second, hurt, push, clearPush, critical, chime, arrow);
    }

    private long previousHurt(Signal<C> hurt) {
        long previous = 0;
        for (Signal<C> signal : signals) {
            if (signal == hurt) break;
            if (signal.cue == Cue.HURT) previous = signal.order;
        }
        return previous;
    }

    private Signal<C> findHurt(Loss<C> first, Loss<C> next) {
        Signal<C> before = null, after = null;
        for (Signal<C> signal : signals) {
            if (signal.cue != Cue.HURT) continue;
            if (signal.order < first.order) {
                if (first.tick - signal.tick > (signal.rod ? HURT_TICKS : PAIR_TICKS)) continue;
                // Reserve a later hurt when another same-bar loss is already waiting.
                if (before != null && next != null && next.yellow == first.yellow &&
                        Math.abs(next.tick - signal.tick) <= PAIR_TICKS) break;
                before = signal;
            } else if (signal.tick - first.tick <= PAIR_TICKS) {
                after = signal;
                break;
            }
        }
        if (before != null) return before;
        return next == null || after == null || after.order < next.order ? after : null;
    }

    private boolean interveningHurt(Loss<C> first, Loss<C> second, Signal<C> assigned) {
        for (Signal<C> signal : signals)
            if (signal.cue == Cue.HURT && signal != assigned &&
                    signal.order > first.order && signal.order < second.order) return true;
        return false;
    }
}
