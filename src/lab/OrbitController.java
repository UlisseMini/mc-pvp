package lab;

import java.util.Properties;

/** A falsifiable two-mode hypothesis: acquire with W taps, then maintain with
 * ordinary hits. Uses only locally observed hurt cues, never referee state. */
public final class OrbitController {
    public static final class Settings {
        public int entryHits=2,resetDelayMs=150,recoverMs=1000;
        public boolean enabled=true;
        public static Settings from(Properties p){
            Settings s=new Settings();
            s.entryHits=(int)Double.parseDouble(p.getProperty("orbit_entry_hits","2"));
            s.resetDelayMs=(int)Double.parseDouble(p.getProperty("reset_delay_ms","150"));
            s.recoverMs=(int)Double.parseDouble(p.getProperty("orbit_recover_ms","1000"));
            s.enabled=!p.getProperty("orbit_enabled","1").equals("0");s.validate();return s;
        }
        void validate(){if(entryHits<1||entryHits>8||resetDelayMs<0||resetDelayMs>500||recoverMs<600||recoverMs>5000)throw new IllegalArgumentException("Invalid orbit settings");}
    }
    public static final class Decision {
        public final boolean forward,sprintKey,maintenance,resetStarted;
        public final int unanswered;
        public final long scheduledResetAt;
        Decision(boolean w,boolean ctrl,boolean normal,boolean reset,int count,long pending){
            forward=w;sprintKey=ctrl;maintenance=normal;resetStarted=reset;unanswered=count;scheduledResetAt=pending;
        }
    }
    private long outgoing,incoming,scheduled=Long.MAX_VALUE;
    private int unanswered;
    private boolean normal;
    public void reset(){outgoing=incoming=0;scheduled=Long.MAX_VALUE;unanswered=0;normal=false;}
    public boolean maintaining(){return normal;}
    public Decision decide(long now,long landedCue,long receivedCue,Settings s){
        s.validate();
        if(landedCue<0||receivedCue<0||landedCue>now||receivedCue>now)throw new IllegalArgumentException("Invalid observed cue");
        if(receivedCue>incoming){incoming=receivedCue;unanswered=0;normal=false;scheduled=Long.MAX_VALUE;}
        if(normal&&now-outgoing>s.recoverMs){normal=false;unanswered=0;}
        if(landedCue>outgoing){
            outgoing=landedCue;unanswered++;
            if(s.enabled&&unanswered>=s.entryHits){normal=true;scheduled=Long.MAX_VALUE;}
            else if(!normal)scheduled=Math.max(now,landedCue+s.resetDelayMs);
        }
        boolean tap=!normal&&now>=scheduled;
        if(tap)scheduled=Long.MAX_VALUE;
        // Exactly one decide call per native movement update. Ctrl-up is a
        // request, not a forced sprint state: vanilla contact/double-tap rules
        // still decide whether the player is actually sprinting.
        return new Decision(!tap,!tap&&!normal,normal,tap,unanswered,scheduled);
    }
}
