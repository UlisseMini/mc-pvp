package lab;

/** Frozen own-observation gap/coast law and handled-hurt click admission.
 * Model qualification: H1.1/V1/hit8, RTT150/0, reach3/3, seven phases.
 * No opponent-private state or server clock is read. Times share the caller's
 * monotonic millisecond clock; this class never edits native input queues.
 */
public final class HurtWindowCoastController {
    public static final class Decision {
        public final int key;
        public final float strafe;
        public final boolean click, pulseStarted, saturation, historyGuard, pending;
        public final long tick, lastWRiseTick;
        public final double rawDuty;
        Decision(int key,float strafe,boolean click,boolean started,boolean saturation,
                 boolean guard,boolean pending,long tick,long lastRise,double raw){
            this.key=key;this.strafe=strafe;this.click=click;this.pulseStarted=started;
            this.saturation=saturation;this.historyGuard=guard;this.pending=pending;
            this.tick=tick;this.lastWRiseTick=lastRise;this.rawDuty=raw;
        }
    }
    private double lastInputMs=Double.NaN;
    public Decision input(long ownTick,double nowMs,double ownNearest,double handledTargetCueMs){
        lastInputMs=nowMs;
        return step(ownTick,ownNearest,Double.isFinite(handledTargetCueMs));
    }
    public boolean allowInjection(double nowMs,boolean nativeTickPoll,double handledTargetCueMs,double ownRtt,int hitTicks){
        if(!Double.isFinite(lastInputMs)||!Double.isFinite(handledTargetCueMs))return true;
        double consume=nativeTickPoll?nowMs:Math.max(nowMs,lastInputMs+50);
        double latestDrain=consume+ownRtt/2+5+50;
        double readyLow=handledTargetCueMs-ownRtt/2+hitTicks*50-5;
        return latestDrain>=readyLow;
    }
    private final StrongRelaxationPolicy gap=new StrongRelaxationPolicy();
    private long previousTick=Long.MIN_VALUE,lastWRiseTick;
    private int previousKey=-1,remaining;
    private boolean wasEnabled,wasSaturated,pending;
    private Decision step(long ownTick,double ownNearest,boolean enabled){
        if(previousTick!=Long.MIN_VALUE&&ownTick<=previousTick)throw new IllegalArgumentException("One call per increasing own input tick");
        StrongRelaxationPolicy.Control base=gap.step(ownNearest);
        double raw=.283+2*(2.55-ownNearest);boolean saturated=raw>=1;
        // Unknown initial tap state is conservatively covered by eight observed
        // own ticks. No native/private tap counter is read by this policy.
        if(previousKey<0)lastWRiseTick=ownTick;
        if(!enabled){pending=false;remaining=0;}
        else {
            if(!saturated)pending=false;
            else if(!wasEnabled||!wasSaturated)pending=true;
        }
        // Release for at least two inputs; resume only after own range reaches
        // 2.55. The W-rise restarts the native double-tap avoidance guard.
        boolean guard=previousKey!=0&&ownTick-lastWRiseTick>=8;
        boolean start=enabled&&remaining==0&&pending&&guard;
        if(start){remaining=2;pending=false;}
        boolean coast=enabled&&(remaining>0||(previousKey==0&&ownNearest<2.55));
        int key=coast?0:base.key;float strafe=coast?0:base.strafe;
        if(key!=0&&previousKey==0)lastWRiseTick=ownTick;
        if(coast&&remaining>0)remaining--;
        previousKey=key;previousTick=ownTick;wasEnabled=enabled;wasSaturated=saturated;
        return new Decision(key,strafe,base.click,start,saturated,guard,pending,ownTick,lastWRiseTick,raw);
    }
}
