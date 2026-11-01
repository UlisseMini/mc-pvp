package lab;

import java.util.Properties;

/** Per-policy physical input ceiling. Legacy policies retain their 10 CPS cap. */
public final class ClickRate {
    public static int cps(Properties p) {
        int value=Integer.parseInt(p.getProperty("click_cps","10"));
        if(value<1||value>30)throw new IllegalArgumentException("click_cps must be 1 through 30");
        return value;
    }
    public static long intervalNanos(Properties p){
        double pacing=Double.parseDouble(p.getProperty("click_min_interval_ms","0"));
        if(!Double.isFinite(pacing)||pacing<0||pacing>1000)
            throw new IllegalArgumentException("click_min_interval_ms must be 0 through 1000");
        return Math.max((1_000_000_000L+cps(p)-1)/cps(p),(long)Math.ceil(pacing*1_000_000));
    }
    public static boolean ready(long now,long lastCompletedInput,Properties p){
        return now-lastCompletedInput>=intervalNanos(p);
    }
}
