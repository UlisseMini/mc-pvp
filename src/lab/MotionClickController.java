package lab;

import java.util.Properties;

/** Instantaneous local-motion click scheduling. No server immunity input. */
public final class MotionClickController {
    public record Settings(int windowLeadMs,boolean recoilClicks) {
        public Settings {
            if(windowLeadMs<0||windowLeadMs>150)throw new IllegalArgumentException("Invalid attack lead");
        }
        public static Settings from(Properties p) {
            return new Settings(Integer.parseInt(p.getProperty("burst_window_lead_ms","50")),
                    !p.getProperty("burst_recoil","1").equals("0"));
        }
    }
    public record Input(long now,long landedCue,double ownRtt,double radialMotion,
                        boolean sprint,boolean nativeInputPoll,boolean localContactReady) {}
    public record Decision(boolean click,boolean opening,boolean attackWindow,boolean recoil,
                           boolean recoilOpportunity,boolean reserved,long readyAt,long attackStartAt) {}

    public static Decision decide(Input in,Settings s) {
        if(in.landedCue<0||in.landedCue>in.now||!Double.isFinite(in.ownRtt)||in.ownRtt<0||in.ownRtt>500
                ||!Double.isFinite(in.radialMotion))throw new IllegalArgumentException("Invalid local click observation");
        boolean opening=in.landedCue==0;
        long ready=opening?0:in.landedCue+500-Math.round(in.ownRtt),start=opening?0:ready-s.windowLeadMs;
        boolean attack=!opening&&in.now>=start;
        boolean opportunity=in.sprint&&in.radialMotion<-.005;
        // Optional recoil cancellation must not occupy the physical click slot
        // needed at the next attack window. It is evaluated at native input
        // polling, not queued a frame earlier using a stale velocity sign.
        boolean reserved=!opening&&!attack&&in.now+100>=start;
        boolean recoil=s.recoilClicks&&in.nativeInputPoll&&in.localContactReady&&opportunity&&!opening&&!attack&&!reserved;
        return new Decision(opening||attack||recoil,opening,attack,recoil,opportunity,reserved,ready,start);
    }
}
