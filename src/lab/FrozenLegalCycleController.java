package lab;

/** Frozen own-observation POC, 2026-11-01. Ordinary inputs only.
 * Runtime inputs: own tick/time, own visible target hurt-cue timestamp, and own
 * local contact callback. The two100-tick tables are an offline witness-derived
 * temporal program; no world/server/other-client object is available here.
 * Assumed declared scenario:20CPS, own RTT0, opponent RTT120, vanilla combat,
 * own/opponent reach3/1.4,6-block start, opponent no clicks for first2000ms.
 */
public final class FrozenLegalCycleController {
    private static final String KEYS="00100100100112111112211111111121111121112111111112211111111121111111122121111112211121002121111111122111110020012122111112111111121111112112121111111221111111112111111112211112111121112110211211111112";
    private static final String EARLY_CLICKS="00000000010000000001000000000100000010010000000001000000000100000000010010000001100010000100000000111000010001000011000100000000110000001001000000000100000000010000000001000001000100001010010000000011";
    public static final class Decision {
        public final int key,phase,contactPhase;
        public final boolean click,persistent,maintenance;
        Decision(int key,boolean click,boolean persistent,boolean maintenance,int phase,int contactPhase){
            this.key=key;this.click=click;this.persistent=persistent;this.maintenance=maintenance;this.phase=phase;this.contactPhase=contactPhase;
        }
    }
    private long anchor=-1;
    private double cue=Double.NEGATIVE_INFINITY;
    private int maintenanceCues;
    private boolean began,persistent;
    private long earlyToken=-1,queuedEarlyToken=-2;
    public void reset(){anchor=-1;cue=Double.NEGATIVE_INFINITY;maintenanceCues=0;began=false;persistent=false;earlyToken=-1;queuedEarlyToken=-2;}
    /** Called only for our native local selected-entity contact, never acceptance. */
    public void contact(long tick){if(anchor<0)anchor=tick;}
    /** Called once per own native input tick, before ordinary movement/attack. */
    public Decision decide(long tick,double elapsedMs,double nowMs,double observedTargetHurtMs){
        if(elapsedMs<2000){
            cue=observedTargetHurtMs;
            if(anchor<0){persistent=true;return new Decision(2,true,true,false,-1,-1);}
            int phase=(int)Math.floorMod(tick-anchor,20L),hitPhase=phase%10;
            int key=phase>=10&&phase<14?0:2;
            // Opening shares the historical2-slot gate. Native timing transfer
            // is separately audited; maintenance below uses persistent retries.
            persistent=hitPhase<2;return new Decision(key,persistent,persistent,false,phase,-1);
        }
        if(!began){began=true;cue=observedTargetHurtMs;}
        else if(observedTargetHurtMs>cue){cue=observedTargetHurtMs;maintenanceCues++;}
        int contactPhase=maintenanceCues<10?maintenanceCues:10+(maintenanceCues-10)%10;
        int phase=Math.max(0,Math.min(9,(int)((nowMs-cue)/50)));
        int at=contactPhase*10+phase;
        persistent=nowMs-cue>=450;
        earlyToken=((long)maintenanceCues)*10+phase;
        boolean early=EARLY_CLICKS.charAt(at)=='1'&&earlyToken!=queuedEarlyToken;
        return new Decision(KEYS.charAt(at)-'0',persistent||early,persistent,true,phase,contactPhase);
    }
    /** After an actual normal key injection, retain a ready-window retry gate.
     * An immune contact slot requests at most one injection; never reset the
     * persistent gate merely because a tick-level CPS check suppressed input. */
    public boolean queued(){if(!persistent)queuedEarlyToken=earlyToken;return persistent;}
}
