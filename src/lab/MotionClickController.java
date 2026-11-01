package lab;

import java.util.Properties;

/** Instantaneous local-motion click scheduling. No server immunity input. */
public final class MotionClickController {
    public static final class Settings {
        private final int windowLeadMs; private final boolean recoilClicks; private final double intervalMs;
        public Settings(int windowLeadMs, boolean recoilClicks){
            this(windowLeadMs,recoilClicks,100);
        }
        public Settings(int windowLeadMs, boolean recoilClicks,double intervalMs){
            if(windowLeadMs<0||windowLeadMs>150)throw new IllegalArgumentException("Invalid attack lead");
            if(!Double.isFinite(intervalMs)||intervalMs<1000.0/30||intervalMs>1000)throw new IllegalArgumentException("Invalid physical interval");
        this.windowLeadMs=windowLeadMs; this.recoilClicks=recoilClicks;this.intervalMs=intervalMs;}
        public int windowLeadMs(){return windowLeadMs;} public boolean recoilClicks(){return recoilClicks;}
        @Override public boolean equals(Object o){if(!(o instanceof Settings))return false;Settings that=(Settings)o;return windowLeadMs==that.windowLeadMs&&recoilClicks==that.recoilClicks&&Double.compare(intervalMs,that.intervalMs)==0;} @Override public int hashCode(){return java.util.Objects.hash(windowLeadMs,recoilClicks,intervalMs);}


        public static Settings from(Properties p) {
            return new Settings(Integer.parseInt(p.getProperty("burst_window_lead_ms","50")),
                    !p.getProperty("burst_recoil","1").equals("0"),ClickRate.intervalNanos(p)/1e6);
        }
    }
    public static final class Input {
        private final long now; private final long landedCue; private final double ownRtt; private final double radialMotion; private final boolean sprint; private final boolean nativeInputPoll; private final boolean localContactReady;
        public Input(long now, long landedCue, double ownRtt, double radialMotion, boolean sprint, boolean nativeInputPoll, boolean localContactReady){this.now=now; this.landedCue=landedCue; this.ownRtt=ownRtt; this.radialMotion=radialMotion; this.sprint=sprint; this.nativeInputPoll=nativeInputPoll; this.localContactReady=localContactReady;}
        public long now(){return now;} public long landedCue(){return landedCue;} public double ownRtt(){return ownRtt;} public double radialMotion(){return radialMotion;} public boolean sprint(){return sprint;} public boolean nativeInputPoll(){return nativeInputPoll;} public boolean localContactReady(){return localContactReady;}
        @Override public boolean equals(Object o){if(!(o instanceof Input))return false;Input that=(Input)o;return java.util.Objects.equals(now, that.now) && java.util.Objects.equals(landedCue, that.landedCue) && java.util.Objects.equals(ownRtt, that.ownRtt) && java.util.Objects.equals(radialMotion, that.radialMotion) && java.util.Objects.equals(sprint, that.sprint) && java.util.Objects.equals(nativeInputPoll, that.nativeInputPoll) && java.util.Objects.equals(localContactReady, that.localContactReady);} @Override public int hashCode(){return java.util.Objects.hash(now,landedCue,ownRtt,radialMotion,sprint,nativeInputPoll,localContactReady);}
}
    public static final class Decision {
        private final boolean click; private final boolean opening; private final boolean attackWindow; private final boolean recoil; private final boolean recoilOpportunity; private final boolean reserved; private final long readyAt; private final long attackStartAt;
        public Decision(boolean click, boolean opening, boolean attackWindow, boolean recoil, boolean recoilOpportunity, boolean reserved, long readyAt, long attackStartAt){this.click=click; this.opening=opening; this.attackWindow=attackWindow; this.recoil=recoil; this.recoilOpportunity=recoilOpportunity; this.reserved=reserved; this.readyAt=readyAt; this.attackStartAt=attackStartAt;}
        public boolean click(){return click;} public boolean opening(){return opening;} public boolean attackWindow(){return attackWindow;} public boolean recoil(){return recoil;} public boolean recoilOpportunity(){return recoilOpportunity;} public boolean reserved(){return reserved;} public long readyAt(){return readyAt;} public long attackStartAt(){return attackStartAt;}
        @Override public boolean equals(Object o){if(!(o instanceof Decision))return false;Decision that=(Decision)o;return java.util.Objects.equals(click, that.click) && java.util.Objects.equals(opening, that.opening) && java.util.Objects.equals(attackWindow, that.attackWindow) && java.util.Objects.equals(recoil, that.recoil) && java.util.Objects.equals(recoilOpportunity, that.recoilOpportunity) && java.util.Objects.equals(reserved, that.reserved) && java.util.Objects.equals(readyAt, that.readyAt) && java.util.Objects.equals(attackStartAt, that.attackStartAt);} @Override public int hashCode(){return java.util.Objects.hash(click,opening,attackWindow,recoil,recoilOpportunity,reserved,readyAt,attackStartAt);}
}

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
        boolean reserved=!opening&&!attack&&in.now+s.intervalMs>=start;
        boolean recoil=s.recoilClicks&&in.nativeInputPoll&&in.localContactReady&&opportunity&&!opening&&!attack&&!reserved;
        return new Decision(opening||attack||recoil,opening,attack,recoil,opportunity,reserved,ready,start);
    }
}
