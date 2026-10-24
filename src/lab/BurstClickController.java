package lab;

import java.util.Properties;

/** Delayed visible-cue click scheduling; it never reads immunity or a native ray. */
public final class BurstClickController {
    public record Settings(int defensiveMs,int windowLeadMs) {
        public Settings {
            if(defensiveMs<0||defensiveMs>400||windowLeadMs<0||windowLeadMs>200)
                throw new IllegalArgumentException("Invalid burst window");
        }
        public static Settings from(Properties p) {
            return new Settings(Integer.parseInt(p.getProperty("burst_defensive_ms","150")),
                    Integer.parseInt(p.getProperty("burst_window_lead_ms","75")));
        }
    }
    public record Input(long now,long observedAt,long landedCue,long incomingCue,double ownRtt) {}
    public record Decision(boolean click,boolean opening,boolean defensive,boolean attackWindow,
                           long defensiveUntil,long estimatedReadyAt,long attackStartAt) {}
    private long handledIncoming,defensiveUntil;
    public void reset(){handledIncoming=defensiveUntil=0;}

    public Decision decide(Input in,Settings settings) {
        if(in.now-in.observedAt<150||in.landedCue>in.observedAt||in.incomingCue>in.observedAt
                ||in.landedCue<0||in.incomingCue<0||!Double.isFinite(in.ownRtt)||in.ownRtt<0||in.ownRtt>500)
            throw new IllegalArgumentException("Burst schedule requires delayed visible cues and a valid RTT");
        if(in.incomingCue>handledIncoming) {
            handledIncoming=in.incomingCue;
            // Starts when the cue becomes legally usable, not on raw packet receipt.
            defensiveUntil=in.now+settings.defensiveMs;
        }
        boolean opening=in.landedCue==0;
        // The cue travels down our connection; the next attack travels up it.
        // A configurable early margin covers uncertain tick/consumption phase.
        // This estimates a fresh-damage opportunity; there is no immunity oracle.
        long ready=opening?0:in.landedCue+500-Math.round(in.ownRtt);
        long start=opening?0:ready-settings.windowLeadMs;
        boolean defensive=in.now<defensiveUntil;
        // Keep trying after the predicted window opens until another visible
        // landed cue arrives. A missed attempt must not silently rearm a 500ms timer.
        boolean attack=!opening&&in.now>=start;
        return new Decision(opening||defensive||attack,opening,defensive,attack,defensiveUntil,ready,start);
    }
}
