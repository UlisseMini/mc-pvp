package lab;

import java.util.Properties;

/** Small visible-only W/Ctrl controller. It never controls or suppresses clicks. */
public final class MomentumController {
    public static final class Settings {
        public int resetEvery=1, resetDelayMs=0, pauseMs=50;
        public boolean adaptiveReset=false, spacing=false, rttAdjustedDelay=false, freshPhase=false, walkReset=false;
        public int openingWaitMs=0, clickIntervalMs=105, freshCorrectionMs=25;
        public double gap=3.10, hysteresis=.06, leadMs=100, targetAwayMin=.5;
        public double viewExtraMs=150, windowBias=.8, reachMargin=.15;
        public int incomingGuardMs=300;
        /** Preplanned normal A/D alternation; zero preserves the W-only experiment. */
        public int strafePeriodMs=0;

        public static Settings from(Properties p) {
            Settings s=new Settings();
            s.resetEvery=(int)value(p,"reset_every",1);s.resetDelayMs=(int)value(p,"reset_delay_ms",0);
            s.pauseMs=(int)value(p,"pause_ms",50);s.adaptiveReset=value(p,"adaptive_reset",0)>0;
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
            if(resetEvery<1||resetEvery>8||pauseMs<0||pauseMs>500||resetDelayMs<0||resetDelayMs>500
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
    public record Input(long now,long observedAt,long landedCue,long incomingCue,long lastClickMs,
                        double distance,double ownToward,double targetAway,double motionToward,
                        double ownY,double targetY,boolean grounded,double ownRtt,double opponentRtt) {}

    public record Decision(boolean forward,boolean sprintKey,int lateral,boolean resetStarted,boolean resetSkipped,
                           long cue,long releaseUntil,long scheduledResetAt,int landedCount,boolean pursuing,
                           double predictedGap,double desiredGap,double lower,double upper) {}

    private long handledCue,scheduledCue,scheduledAt=Long.MAX_VALUE,tapUntil;
    private int landedCount;
    private boolean previousForward=true;
    private long startedAt=Long.MIN_VALUE;
    private long lastIssuedAt=Long.MIN_VALUE;

    public void reset() {
        handledCue=scheduledCue=tapUntil=0;scheduledAt=Long.MAX_VALUE;landedCount=0;previousForward=true;startedAt=lastIssuedAt=Long.MIN_VALUE;
    }

    public Decision decide(Input in,Settings s) {
        s.validate();
        if(in.now-in.observedAt<150||in.landedCue>in.observedAt||in.incomingCue>in.observedAt)
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
                scheduledAt=s.freshPhase?freshPhaseReset(in,s):Math.max(in.now,handledCue+150+(long)delay);
            } else skipped=true;
        }
        if(in.now>=scheduledAt) {
            tapUntil=in.now+s.pauseMs;scheduledAt=Long.MAX_VALUE;started=true;
        }
        boolean resetActive=in.now<tapUntil;
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
