package practice.core;

import java.util.ArrayDeque;
import java.util.Deque;

/** Outbound sword-use intervals, measured on the same monotonic clock as incoming cues. */
public final class BlockhitGuard {
    private static final long MS = 1_000_000L;
    private static final class Interval {
        final long start;
        long end = Long.MAX_VALUE;

        Interval(long start) { this.start = start; }
    }

    private final Deque<Interval> intervals = new ArrayDeque<>();
    private Object connection;

    public synchronized void transition(Object connection, long now, boolean usingSword) {
        if (this.connection != connection) {
            intervals.clear();
            this.connection = connection;
        }
        Interval last = intervals.peekLast();
        if (usingSword) {
            if (last != null && last.end == Long.MAX_VALUE) return;
            intervals.addLast(new Interval(now));
            if (intervals.size() > 32) intervals.removeFirst();
        } else if (last != null && last.end == Long.MAX_VALUE) {
            last.end = now;
        }
    }

    public synchronized boolean covers(Object connection, long received, int ping) {
        if (connection == null || connection != this.connection) return false;
        // A full RTT maps receipt back onto the local timeline of commands.
        // Allow one server tick on either side and require that whole range.
        long earliest = received - (Math.max(0L, ping) + 50) * MS;
        long latest = received - Math.max(0L, ping - 50L) * MS;
        for (Interval interval : intervals)
            if (interval.start <= earliest && interval.end > latest) return true;
        return false;
    }

    public synchronized void clear() {
        intervals.clear();
        connection = null;
    }
}
