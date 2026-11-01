package lab;

import java.util.Properties;

/** Small visible-only W/Ctrl controller. It never controls or suppresses clicks. */
public final class MomentumController {
    public static final class Settings {
        public int resetEvery=1, resetDelayMs=0, pauseMs=50, pauseTicks=0;
        public int reactionMs=150;
        public boolean resetOnIssuedClick=false;
        public boolean adaptiveReset=false, spacing=false, rttAdjustedDelay=false, freshPhase=false, walkReset=false;
        public int openingWaitMs=0, clickIntervalMs=105, freshCorrectionMs=25;
        public double gap=3.10, hysteresis=.06, leadMs=100, targetAwayMin=.5;
        public double viewExtraMs=150, windowBias=.8, reachMargin=.15;
        public int incomingGuardMs=300;
        /** Preplanned normal A/D alternation; zero preserves the W-only experiment. */
        public int strafePeriodMs=0;

        public static Settings from(Properties p) {
            Settings s=new Settings();
            s.reactionMs=(int)value(p,"reaction_ms",150);
            s.resetEvery=(int)value(p,"reset_every",1);s.resetDelayMs=(int)value(p,"reset_delay_ms",0);
            s.pauseMs=(int)value(p,"pause_ms",50);s.adaptiveReset=value(p,"adaptive_reset",0)>0;
            s.pauseTicks=(int)value(p,"pause_ticks",0);s.resetOnIssuedClick=value(p,"reset_on_issued_click",0)>0;
            s.rttAdjustedDelay=value(p,"reset_rtt_adjusted",0)>0;s.freshPhase=value(p,"reset_fresh_phase",0)>0;
            s.walkReset=value(p,"reset_walk",0)>0;
            s.openingWaitMs=(int)value(p,"opening_wait_ms",0);
            s.clickIntervalMs=(int)value(p,"momentum_click_ms",105);s.freshCorrectionMs=(int)value(p,"fresh_correction_ms",25);
            s.spacing=value(p,"momentum_spacing",0)>0;s.gap=value(p,"momentum_gap",3.10);
            s.hysteresis=value(p,"momentum_hysteresis",.06);s.leadMs=value(p,"momentum_lead_ms",100);
            s.targetAwayMin=value(p,"momentum_away_min",.5);s.viewExtraMs=value(p,"momentum_view_extra_ms",150);
            s.windowBias=value(p,"momentum_window_bias",.8);s.reachMargin=value(p,"momentum_reach_margin",.15);
            s.incomingGuardMs=(int)value(p,"momentum_incoming_guard_ms",300);
            s.strafePeriodMs=(int)value(p,"momentum_strafe_ms",0);
            s.validate();return s;
        }
        private static double value(Properties p,String key,double fallback) {
            return Double.parseDouble(p.getProperty(key,Double.toString(fallback)));
        }
        void validate() {
            if(reactionMs<0||reactionMs>1000||resetEvery<1||resetEvery>8||pauseMs<0||pauseMs>500||pauseTicks<0||pauseTicks>4
                    ||(resetOnIssuedClick&&pauseTicks!=1)||resetDelayMs<0||resetDelayMs>500
                    ||incomingGuardMs<150||incomingGuardMs>1500||(strafePeriodMs!=0&&strafePeriodMs<150)
                    ||openingWaitMs<0||openingWaitMs>500||clickIntervalMs<100||clickIntervalMs>200
                    ||freshCorrectionMs<0||freshCorrectionMs>50
                    ||!Double.isFinite(gap)||gap<2||gap>3.4||!Double.isFinite(hysteresis)||hysteresis<0
                    ||!Double.isFinite(leadMs)||leadMs<50||leadMs>300
                    ||!Double.isFinite(targetAwayMin)||targetAwayMin<0
                    ||!Double.isFinite(viewExtraMs)||viewExtraMs<0||viewExtraMs>500
                    ||!Double.isFinite(windowBias)||windowBias<0||windowBias>1
                    ||!Double.isFinite(reachMargin)||reachMargin<0||reachMargin>.5)
                throw new IllegalArgumentException("Invalid momentum policy settings");
        }
    }

    /** All observed values come from one native sample at least150ms old. Speeds are blocks/second, motion blocks/tick. */
    public static final class Input {
        private final long now; private final long observedAt; private final long landedCue; private final long incomingCue; private final long lastClickMs; private final double distance; private final double ownToward; private final double targetAway; private final double motionToward; private final double ownY; private final double targetY; private final boolean grounded; private final double ownRtt; private final double opponentRtt;
        public Input(long now, long observedAt, long landedCue, long incomingCue, long lastClickMs, double distance, double ownToward, double targetAway, double motionToward, double ownY, double targetY, boolean grounded, double ownRtt, double opponentRtt){this.now=now; this.observedAt=observedAt; this.landedCue=landedCue; this.incomingCue=incomingCue; this.lastClickMs=lastClickMs; this.distance=distance; this.ownToward=ownToward; this.targetAway=targetAway; this.motionToward=motionToward; this.ownY=ownY; this.targetY=targetY; this.grounded=grounded; this.ownRtt=ownRtt; this.opponentRtt=opponentRtt;}
        public long now(){return now;} public long observedAt(){return observedAt;} public long landedCue(){return landedCue;} public long incomingCue(){return incomingCue;} public long lastClickMs(){return lastClickMs;} public double distance(){return distance;} public double ownToward(){return ownToward;} public double targetAway(){return targetAway;} public double motionToward(){return motionToward;} public double ownY(){return ownY;} public double targetY(){return targetY;} public boolean grounded(){return grounded;} public double ownRtt(){return ownRtt;} public double opponentRtt(){return opponentRtt;}
        @Override public boolean equals(Object o){if(!(o instanceof Input))return false;Input that=(Input)o;return java.util.Objects.equals(now, that.now) && java.util.Objects.equals(observedAt, that.observedAt) && java.util.Objects.equals(landedCue, that.landedCue) && java.util.Objects.equals(incomingCue, that.incomingCue) && java.util.Objects.equals(lastClickMs, that.lastClickMs) && java.util.Objects.equals(distance, that.distance) && java.util.Objects.equals(ownToward, that.ownToward) && java.util.Objects.equals(targetAway, that.targetAway) && java.util.Objects.equals(motionToward, that.motionToward) && java.util.Objects.equals(ownY, that.ownY) && java.util.Objects.equals(targetY, that.targetY) && java.util.Objects.equals(grounded, that.grounded) && java.util.Objects.equals(ownRtt, that.ownRtt) && java.util.Objects.equals(opponentRtt, that.opponentRtt);} @Override public int hashCode(){return java.util.Objects.hash(now,observedAt,landedCue,incomingCue,lastClickMs,distance,ownToward,targetAway,motionToward,ownY,targetY,grounded,ownRtt,opponentRtt);}
}

    public static final class Decision {
        private final boolean forward; private final boolean sprintKey; private final int lateral; private final boolean resetStarted; private final boolean resetSkipped; private final long cue; private final long releaseUntil; private final long scheduledResetAt; private final int landedCount; private final boolean pursuing; private final double predictedGap; private final double desiredGap; private final double lower; private final double upper;
        public Decision(boolean forward, boolean sprintKey, int lateral, boolean resetStarted, boolean resetSkipped, long cue, long releaseUntil, long scheduledResetAt, int landedCount, boolean pursuing, double predictedGap, double desiredGap, double lower, double upper){this.forward=forward; this.sprintKey=sprintKey; this.lateral=lateral; this.resetStarted=resetStarted; this.resetSkipped=resetSkipped; this.cue=cue; this.releaseUntil=releaseUntil; this.scheduledResetAt=scheduledResetAt; this.landedCount=landedCount; this.pursuing=pursuing; this.predictedGap=predictedGap; this.desiredGap=desiredGap; this.lower=lower; this.upper=upper;}
        public boolean forward(){return forward;} public boolean sprintKey(){return sprintKey;} public int lateral(){return lateral;} public boolean resetStarted(){return resetStarted;} public boolean resetSkipped(){return resetSkipped;} public long cue(){return cue;} public long releaseUntil(){return releaseUntil;} public long scheduledResetAt(){return scheduledResetAt;} public int landedCount(){return landedCount;} public boolean pursuing(){return pursuing;} public double predictedGap(){return predictedGap;} public double desiredGap(){return desiredGap;} public double lower(){return lower;} public double upper(){return upper;}
        @Override public boolean equals(Object o){if(!(o instanceof Decision))return false;Decision that=(Decision)o;return java.util.Objects.equals(forward, that.forward) && java.util.Objects.equals(sprintKey, that.sprintKey) && java.util.Objects.equals(lateral, that.lateral) && java.util.Objects.equals(resetStarted, that.resetStarted) && java.util.Objects.equals(resetSkipped, that.resetSkipped) && java.util.Objects.equals(cue, that.cue) && java.util.Objects.equals(releaseUntil, that.releaseUntil) && java.util.Objects.equals(scheduledResetAt, that.scheduledResetAt) && java.util.Objects.equals(landedCount, that.landedCount) && java.util.Objects.equals(pursuing, that.pursuing) && java.util.Objects.equals(predictedGap, that.predictedGap) && java.util.Objects.equals(desiredGap, that.desiredGap) && java.util.Objects.equals(lower, that.lower) && java.util.Objects.equals(upper, that.upper);} @Override public int hashCode(){return java.util.Objects.hash(forward,sprintKey,lateral,resetStarted,resetSkipped,cue,releaseUntil,scheduledResetAt,landedCount,pursuing,predictedGap,desiredGap,lower,upper);}
}

    private long handledCue,scheduledCue,scheduledAt=Long.MAX_VALUE,tapUntil;
    private int landedCount;
    private int releaseTicks;
    private boolean previousForward=true;
    private long startedAt=Long.MIN_VALUE;
    private long lastIssuedAt=Long.MIN_VALUE;

    public void reset() {
        handledCue=scheduledCue=tapUntil=0;scheduledAt=Long.MAX_VALUE;landedCount=releaseTicks=0;previousForward=true;startedAt=lastIssuedAt=Long.MIN_VALUE;
    }

    public Decision decide(Input in,Settings s) {
        s.validate();
        if(in.now-in.observedAt<s.reactionMs||in.landedCue>in.observedAt||in.incomingCue>in.observedAt)
            throw new IllegalArgumentException("Momentum decisions require delayed visible observations");
        if(startedAt==Long.MIN_VALUE)startedAt=in.now;
        double own=Math.max(-8,Math.min(8,in.ownToward));
        double target=Math.max(-8,Math.min(8,in.targetAway));
        double age=(in.now-in.observedAt)/1000.0,lead=s.leadMs/1000.0;
        double friction=in.grounded?.54600006:.91;
        double ticks=s.leadMs/50.0;
        double coast=in.motionToward*(1-Math.pow(friction,ticks))/(1-friction);
        double currentGap=in.distance+(target-own)*age;
        double predictedGap=currentGap+target*lead-coast;
        double viewAge=(in.ownRtt+in.opponentRtt)/2000.0+s.viewExtraMs/1000.0;
        double ourReach=horizontalReach(in.ownY,in.targetY);
        double theirReach=horizontalReach(in.targetY,in.ownY);
        double lower=theirReach-(target+own)*viewAge+.1,upper=ourReach-s.reachMargin;
        boolean pursuing=target>s.targetAwayMin&&own>0&&in.motionToward>0&&in.landedCue>0;
        boolean band=pursuing&&lower<upper;
        double desired=band?lower+(upper-lower)*s.windowBias:s.gap;
        // Explicit gap knob caps how deeply we pursue within the estimated band.
        desired=Math.min(s.gap,desired);
        boolean skipped=false,started=false;
        if(in.landedCue>handledCue) {
            handledCue=in.landedCue;landedCount++;
            boolean selected=(landedCount-1)%s.resetEvery==0;
            boolean comfortable=band&&currentGap>lower+s.hysteresis&&currentGap<upper
                    &&predictedGap>lower+s.hysteresis&&predictedGap<upper;
            if(selected&&!(s.adaptiveReset&&comfortable)) {
                double delay=s.rttAdjustedDelay?Math.max(0,s.resetDelayMs-in.ownRtt):s.resetDelayMs;
                scheduledCue=handledCue;
                scheduledAt=s.resetOnIssuedClick
                        ?Math.max(in.now,(long)(handledCue+500-in.ownRtt-s.freshCorrectionMs-s.clickIntervalMs))
                        :s.freshPhase?freshPhaseReset(in,s):Math.max(in.now,handledCue+s.reactionMs+(long)delay);
            } else skipped=true;
        }
        // Own issued inputs are immediately known; no new world observation is
        // consulted here. ATTACK happens before living movement and C0B state.
        boolean clickQueued=in.lastClickMs>lastIssuedAt&&in.lastClickMs<=in.now;
        if(in.now>=scheduledAt&&(!s.resetOnIssuedClick||clickQueued)) {
            tapUntil=in.now+s.pauseMs;releaseTicks=s.pauseTicks;scheduledAt=Long.MAX_VALUE;started=true;
        }
        boolean resetActive=s.pauseTicks>0?releaseTicks>0:in.now<tapUntil;
        if(releaseTicks>0)releaseTicks--;
        boolean w=(!resetActive||s.walkReset)&&in.now-startedAt>=s.openingWaitMs;
        boolean incomingSettled=in.incomingCue==0||in.now-in.incomingCue>=s.incomingGuardMs;
        if(s.spacing&&pursuing&&incomingSettled&&in.now-in.landedCue<1500) {
            w &= predictedGap>desired+(previousForward?-s.hysteresis:s.hysteresis);
        }
        previousForward=w;
        lastIssuedAt=in.now;
        int lateral=s.strafePeriodMs==0?0:((in.now-startedAt)/s.strafePeriodMs)%2==0?1:-1;
        return new Decision(w,w&&!resetActive,lateral,started,skipped,started?scheduledCue:handledCue,tapUntil,
                            scheduledAt==Long.MAX_VALUE?0:scheduledAt,landedCount,
                            pursuing,predictedGap,desired,lower,upper);
    }

    private long freshPhaseReset(Input in,Settings s) {
        double firstFresh=in.landedCue+500-in.ownRtt-s.freshCorrectionMs;
        long before=in.now;
        long injection=in.lastClickMs>0?in.lastClickMs:in.now;
        for(int index=0;index<16;index++,injection+=s.clickIntervalMs) {
            if(index==0&&injection<=lastIssuedAt)continue;
            long consumption=in.now+50*(long)Math.ceil(Math.max(0,injection-in.now)/50.0);
            if(consumption>=firstFresh)break;
            before=consumption;
        }
        // ATTACK precedes sprint state transmission within a tick. Release on
        // the prior click, then resume during the intervening non-click tick.
        return Math.max(in.now,before);
    }

    private static double horizontalReach(double eyeBase,double targetBase) {
        double eye=eyeBase+1.62;
        double vertical=Math.max(0,Math.max(targetBase-.1-eye,eye-(targetBase+1.9)));
        return Math.sqrt(Math.max(0,9-vertical*vertical))+.4;
    }
}
