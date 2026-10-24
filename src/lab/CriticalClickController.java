package lab;

/** One optional local critical opportunity per visible hurt cue; no damage oracle. */
public final class CriticalClickController {
    private long attemptedCue;
    public void reset(){attemptedCue=0;}
    public boolean eligible(long now,long cue,long attackStart,boolean opening,boolean attackWindow,
                            boolean nativeInputPoll,boolean localContactReady,boolean localCritical) {
        return cue>0&&cue<=now&&cue!=attemptedCue&&!opening&&!attackWindow&&nativeInputPoll
                &&localContactReady&&localCritical&&now+100<attackStart;
    }
    /** Charge the budget only after an actual physical input was issued. */
    public void injected(long cue){if(cue<=0)throw new IllegalArgumentException("Missing visible cue");attemptedCue=cue;}
}
