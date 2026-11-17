package lab;

import java.util.Properties;

/** Delayed visible-cue click scheduling; it never reads immunity or a native ray. */
public final class BurstClickController {
    public static final class Settings {
        private final int defensiveMs; private final int windowLeadMs;
        public Settings(int defensiveMs, int windowLeadMs){
            if(defensiveMs<0||defensiveMs>400||windowLeadMs<0||windowLeadMs>200)
                throw new IllegalArgumentException("Invalid burst window");
        this.defensiveMs=defensiveMs; this.windowLeadMs=windowLeadMs;}
        public int defensiveMs(){return defensiveMs;} public int windowLeadMs(){return windowLeadMs;}
        @Override public boolean equals(Object o){if(!(o instanceof Settings))return false;Settings that=(Settings)o;return java.util.Objects.equals(defensiveMs, that.defensiveMs) && java.util.Objects.equals(windowLeadMs, that.windowLeadMs);} @Override public int hashCode(){return java.util.Objects.hash(defensiveMs,windowLeadMs);}


        public static Settings from(Properties p) {
            return new Settings(Integer.parseInt(p.getProperty("burst_defensive_ms","150")),
                    Integer.parseInt(p.getProperty("burst_window_lead_ms","75")));
        }
    }
    public static final class Input {
        private final long now; private final long observedAt; private final long landedCue; private final long incomingCue; private final double ownRtt;
        public Input(long now, long observedAt, long landedCue, long incomingCue, double ownRtt){this.now=now; this.observedAt=observedAt; this.landedCue=landedCue; this.incomingCue=incomingCue; this.ownRtt=ownRtt;}
        public long now(){return now;} public long observedAt(){return observedAt;} public long landedCue(){return landedCue;} public long incomingCue(){return incomingCue;} public double ownRtt(){return ownRtt;}
        @Override public boolean equals(Object o){if(!(o instanceof Input))return false;Input that=(Input)o;return java.util.Objects.equals(now, that.now) && java.util.Objects.equals(observedAt, that.observedAt) && java.util.Objects.equals(landedCue, that.landedCue) && java.util.Objects.equals(incomingCue, that.incomingCue) && java.util.Objects.equals(ownRtt, that.ownRtt);} @Override public int hashCode(){return java.util.Objects.hash(now,observedAt,landedCue,incomingCue,ownRtt);}
}
    public static final class Decision {
        private final boolean click; private final boolean opening; private final boolean defensive; private final boolean attackWindow; private final long defensiveUntil; private final long estimatedReadyAt; private final long attackStartAt;
        public Decision(boolean click, boolean opening, boolean defensive, boolean attackWindow, long defensiveUntil, long estimatedReadyAt, long attackStartAt){this.click=click; this.opening=opening; this.defensive=defensive; this.attackWindow=attackWindow; this.defensiveUntil=defensiveUntil; this.estimatedReadyAt=estimatedReadyAt; this.attackStartAt=attackStartAt;}
        public boolean click(){return click;} public boolean opening(){return opening;} public boolean defensive(){return defensive;} public boolean attackWindow(){return attackWindow;} public long defensiveUntil(){return defensiveUntil;} public long estimatedReadyAt(){return estimatedReadyAt;} public long attackStartAt(){return attackStartAt;}
        @Override public boolean equals(Object o){if(!(o instanceof Decision))return false;Decision that=(Decision)o;return java.util.Objects.equals(click, that.click) && java.util.Objects.equals(opening, that.opening) && java.util.Objects.equals(defensive, that.defensive) && java.util.Objects.equals(attackWindow, that.attackWindow) && java.util.Objects.equals(defensiveUntil, that.defensiveUntil) && java.util.Objects.equals(estimatedReadyAt, that.estimatedReadyAt) && java.util.Objects.equals(attackStartAt, that.attackStartAt);} @Override public int hashCode(){return java.util.Objects.hash(click,opening,defensive,attackWindow,defensiveUntil,estimatedReadyAt,attackStartAt);}
}
    private long handledIncoming,defensiveUntil;
    public void reset(){handledIncoming=defensiveUntil=0;}

    public Decision decide(Input in,Settings settings) {
        return decide(in,settings,DuelSettings.VANILLA_HIT_TICKS);
    }

    public Decision decide(Input in,Settings settings,int hitWindowTicks) {
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
        long ready=opening?0:in.landedCue+hitWindowTicks*50L-Math.round(in.ownRtt);
        long start=opening?0:ready-settings.windowLeadMs;
        boolean defensive=in.now<defensiveUntil;
        // Keep trying after the predicted window opens until another visible
        // landed cue arrives. A missed attempt does not restart the estimate.
        boolean attack=!opening&&in.now>=start;
        return new Decision(opening||defensive||attack,opening,defensive,attack,defensiveUntil,ready,start);
    }
}
