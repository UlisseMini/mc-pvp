package lab;

/** Timing-contract ablations of FrozenLegalCycleController; unchanged two tables.
 * No server/other-client state. This proposal has not yet been compiled/tested.
 * Constructor switches independently select own-tick cue slots and one opening
 * injection per requested own slot. Use the same opening switch for W-tap.
 */
public final class TickIndexedLegalCycleController {
    private static final String KEYS="00100100100112111112211111111121111121112111111112211111111121111111122121111112211121002121111111122111110020012122111112111111121111112112121111111221111111112111111112211112111121112110211211111112";
    private static final String EARLY_CLICKS="00000000010000000001000000000100000010010000000001000000000100000000010010000001100010000100000000111000010001000011000100000000110000001001000000000100000000010000000001000001000100001010010000000011";
    private final boolean tickIndexedMaintenance,oneOpeningInjectionPerSlot;
    public TickIndexedLegalCycleController(boolean tickIndexedMaintenance,boolean oneOpeningInjectionPerSlot){
        this.tickIndexedMaintenance=tickIndexedMaintenance;
        this.oneOpeningInjectionPerSlot=oneOpeningInjectionPerSlot;
    }
    public static final class Decision {
        public final int key,phase,contactPhase;
        public final boolean click,persistent,maintenance;
        public final long cueTick;
        Decision(int key,boolean click,boolean persistent,boolean maintenance,int phase,int contactPhase,long cueTick){
            this.key=key;this.click=click;this.persistent=persistent;this.maintenance=maintenance;
            this.phase=phase;this.contactPhase=contactPhase;this.cueTick=cueTick;
        }
    }
    private long anchor=-1,cueTick=-1,lastDecisionTick=-1,queuedOpeningTick=Long.MIN_VALUE;
    private double cue=Double.NEGATIVE_INFINITY;
    private int maintenanceCues;
    private boolean began,persistent,maintenance,gate;
    private long earlyToken=-1,queuedEarlyToken=-2;
    public void reset(){
        anchor=cueTick=lastDecisionTick=-1;queuedOpeningTick=Long.MIN_VALUE;
        cue=Double.NEGATIVE_INFINITY;maintenanceCues=0;began=persistent=maintenance=gate=false;
        earlyToken=-1;queuedEarlyToken=-2;
    }
    /** Own local engine contact only. Caller MUST apply the returned gate.
     * The causal first click can have been injected in the preceding native tick;
     * its actual consumption is slot0, which must be marked used immediately.
     * This closes future admission only; it never cancels an already queued input.
     */
    public boolean contact(long tick){
        if(anchor<0){
            anchor=tick;
            if(oneOpeningInjectionPerSlot&&!maintenance){queuedOpeningTick=tick;gate=false;}
        }
        return gate;
    }
    /** One call per own native input tick. Multiple frame polls reuse its gate.
     * New visible-cue timestamps only identify which own input tick first saw the
     * cue. No configured server phase or private hurt-resistant timer is read.
     */
    public Decision decide(long tick,double elapsedMs,double nowMs,double observedTargetHurtMs){
        lastDecisionTick=tick;
        boolean newCue=observedTargetHurtMs>cue;
        if(newCue)cueTick=tick;
        if(elapsedMs<2000){
            cue=observedTargetHurtMs;maintenance=false;
            int phase=anchor<0?-1:(int)Math.floorMod(tick-anchor,20L);
            int key=phase>=10&&phase<14?0:2;
            boolean requested=phase<0||phase%10<2;
            gate=requested&&(!oneOpeningInjectionPerSlot||queuedOpeningTick!=tick);
            persistent=gate&&!oneOpeningInjectionPerSlot;
            return new Decision(key,gate,persistent,false,phase,-1,cueTick);
        }
        maintenance=true;
        if(!began){began=true;cue=observedTargetHurtMs;}
        else if(newCue){cue=observedTargetHurtMs;maintenanceCues++;}
        int contactPhase=maintenanceCues<10?maintenanceCues:10+(maintenanceCues-10)%10;
        int phase=tickIndexedMaintenance?(int)Math.max(0L,Math.min(9L,tick-cueTick)):
            Math.max(0,Math.min(9,(int)((nowMs-cue)/50)));
        int at=contactPhase*10+phase;
        // Request ordinary retries from slot9 onward. The normal20CPS pacing and
        // native immunity decide whether an attempt occurs and is accepted.
        persistent=tickIndexedMaintenance?phase>=9:nowMs-cue>=450;
        earlyToken=((long)maintenanceCues)*10+phase;
        boolean early=EARLY_CLICKS.charAt(at)=='1'&&earlyToken!=queuedEarlyToken;
        gate=persistent||early;
        return new Decision(KEYS.charAt(at)-'0',gate,persistent,true,phase,contactPhase,cueTick);
    }
    /** Only after an actual native key injection, never after CPS suppression.
     * Caller applies the result to the frame gate. Persistent ready-window retries
     * survive admission; opening/immune one-slot inputs close until next decision.
     */
    public boolean queued(){
        if(!maintenance&&oneOpeningInjectionPerSlot){queuedOpeningTick=lastDecisionTick;gate=false;return false;}
        if(!persistent)queuedEarlyToken=earlyToken;
        gate=persistent;return gate;
    }
}
