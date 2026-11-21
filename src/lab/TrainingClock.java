package lab;

/** Fixed-rate combat clock shared by the two sandboxed JVMs of a slow practice lab. */
public final class TrainingClock {
    public static final double RATE = Double.parseDouble(System.getProperty("lab.trainingSpeed", "1"));
    public static final boolean ROOM = Boolean.getBoolean("lab.trainingRoom");
    private static final long EPOCH = Long.getLong("lab.trainingEpoch", 0L);
    static {
        if (!Double.isFinite(RATE) || RATE < .1 || RATE > 1 || (RATE != 1 && EPOCH <= 0))
            throw new IllegalArgumentException("Invalid slow sumo clock");
    }
    private TrainingClock() {}
    public static long gameMillis(long wall) {
        return RATE == 1 ? wall : EPOCH + Math.round((wall-EPOCH)*RATE);
    }
    public static long millis() { return gameMillis(System.currentTimeMillis()); }
    public static long nanos() {
        return gameNanos(System.nanoTime());
    }
    public static long gameNanos(long now) {
        return RATE == 1 ? now : (long)(now*RATE);
    }
    public static long wallDuration(long gameMs) {
        return RATE == 1 ? gameMs : Math.round(gameMs/RATE);
    }
    public static long wallTimestamp(long gameMs) {
        return RATE == 1 ? gameMs : EPOCH + wallDuration(gameMs-EPOCH);
    }
    public static void sleep(long gameMs) throws InterruptedException {
        Thread.sleep(wallDuration(gameMs));
    }
}
