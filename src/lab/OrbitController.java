package lab;

import java.util.Properties;

/** A falsifiable two-mode hypothesis: acquire with W taps, then maintain with
 * ordinary hits. Uses only locally observed hurt cues, never referee state. */
public final class OrbitController {
    public static final class Settings {
        public int entryHits=2,resetDelayMs=150,recoverMs=1000,repairEvery=0,minContactMs=0;
        public double openingRange=0,spacingRange=0,spacingHysteresis=.03;
        public boolean enabled=true,maintenanceCtrl=false;
        public static Settings from(Properties p){
            Settings s=new Settings();
            s.entryHits=(int)Double.parseDouble(p.getProperty("orbit_entry_hits","2"));
            s.resetDelayMs=(int)Double.parseDouble(p.getProperty("reset_delay_ms","150"));
            s.recoverMs=(int)Double.parseDouble(p.getProperty("orbit_recover_ms","1000"));
            s.maintenanceCtrl=p.getProperty("orbit_maintenance_ctrl","0").equals("1");
            s.repairEvery=(int)Double.parseDouble(p.getProperty("orbit_repair_every","0"));
            s.minContactMs=(int)Double.parseDouble(p.getProperty("orbit_min_contact_ms","0"));
            s.openingRange=Double.parseDouble(p.getProperty("orbit_opening_range","0"));
            s.spacingRange=Double.parseDouble(p.getProperty("orbit_spacing_range","0"));
            s.spacingHysteresis=Double.parseDouble(p.getProperty("orbit_spacing_hysteresis",".03"));
            s.enabled=!p.getProperty("orbit_enabled","1").equals("0");s.validate();return s;
        }
        void validate(){if(entryHits<1||entryHits>8||resetDelayMs<0||resetDelayMs>500||recoverMs<600||recoverMs>5000
                ||repairEvery<0||repairEvery>8||minContactMs<0||minContactMs>1500||!Double.isFinite(openingRange)||openingRange!=0&&(openingRange<=3||openingRange>6)
                ||!Double.isFinite(spacingRange)||spacingRange!=0&&(spacingRange<1.5||spacingRange>3)
                ||!Double.isFinite(spacingHysteresis)||spacingHysteresis<0||spacingHysteresis>.3)throw new IllegalArgumentException("Invalid orbit settings");}
    }
    public static final class Decision {
        public final boolean forward,sprintKey,maintenance,resetStarted,openingWait,click;
        public final int unanswered;
        public final long scheduledResetAt;
        public final double projectedRange;
        Decision(boolean w,boolean ctrl,boolean normal,boolean reset,boolean wait,boolean click,int count,long pending,double projected){
            forward=w;sprintKey=ctrl;maintenance=normal;resetStarted=reset;openingWait=wait;unanswered=count;scheduledResetAt=pending;
            projectedRange=projected;this.click=click;
        }
    }
    private long outgoing,incoming,scheduled=Long.MAX_VALUE;
    private int unanswered;
    private boolean normal,opened,previousForward=true;
    public void reset(){outgoing=incoming=0;scheduled=Long.MAX_VALUE;unanswered=0;normal=false;opened=false;previousForward=true;}
    public boolean maintaining(){return normal;}
    public Decision decide(long now,long landedCue,long receivedCue,Settings s){
        return decide(now,landedCue,receivedCue,Double.POSITIVE_INFINITY,s);
    }
    public Decision decide(long now,long landedCue,long receivedCue,double localRange,Settings s){
        return decide(now,landedCue,receivedCue,localRange,0,0,true,false,s);
    }
    /** ownToward is local motion in blocks/tick; targetAway is a slope of
     * rendered opponent positions in blocks/second. No remote motion fields. */
    public Decision decide(long now,long landedCue,long receivedCue,double localRange,
            double ownToward,double targetAway,boolean grounded,boolean localSprint,Settings s){
        s.validate();
        if(landedCue<0||receivedCue<0||landedCue>now||receivedCue>now)throw new IllegalArgumentException("Invalid observed cue");
        // Only the initial approach waits. Any locally observed interaction
        // starts ordinary control; recovery never re-enters the opening wait.
        opened|=s.openingRange==0||localRange<=s.openingRange||landedCue>0||receivedCue>0;
        if(receivedCue>incoming){incoming=receivedCue;unanswered=0;normal=false;scheduled=Long.MAX_VALUE;}
        if(normal&&now-outgoing>s.recoverMs){normal=false;unanswered=0;}
        if(landedCue>outgoing){
            outgoing=landedCue;unanswered++;
            if(s.enabled&&unanswered>=s.entryHits){
                if(!normal)scheduled=Long.MAX_VALUE;
                normal=true;
                if(s.repairEvery>0&&unanswered%s.repairEvery==0)scheduled=Math.max(now,landedCue+s.resetDelayMs);
            }
            else if(!normal)scheduled=Math.max(now,landedCue+s.resetDelayMs);
        }
        boolean tap=now>=scheduled;
        if(tap)scheduled=Long.MAX_VALUE;
        // Exactly one decide call per native movement update. These are key
        // requests, never forced sprint states. Holding Ctrl after contact can
        // restore local sprint without a new START packet if the previous sent
        // state was already sprinting. The server's attack can have cleared its
        // sprint meanwhile. Verify this distinction offline; do not assume it.
        boolean w=opened&&!tap,ctrl=!normal||s.maintenanceCtrl;
        // A declared cadence policy, using only visible hurt cues. No immunity
        // oracle and no filtering of clicks already issued to the native game.
        boolean click=outgoing==0||now-outgoing>=s.minContactMs;
        double projected=Double.NaN;
        if(normal&&s.spacingRange>0&&!tap){
            // One-movement-update forecast. Continuous clicks are the initial
            // spacing experiment: a legal locally sprinting contact causes .6
            // horizontal drag even if the server will reject it as immune.
            // This is an approximate observer, not the exact-travel kernel.
            double motion=ownToward*(click&&localSprint&&localRange<=3?.6:1);
            if(Math.abs(motion)<.005)motion=0;
            double coast=localRange+targetAway*.05-motion;
            double acceleration=grounded?(ctrl||localSprint&&(!click||localRange>3)?.1274:.098):(localSprint?.02548:.0196);
            double walking=coast-acceleration;
            double keep=previousForward?walking:coast,change=previousForward?coast:walking;
            w=Math.abs(change-s.spacingRange)+s.spacingHysteresis<Math.abs(keep-s.spacingRange)?!previousForward:previousForward;
            projected=w?walking:coast;
        }
        previousForward=w;
        return new Decision(w,w&&ctrl,normal,tap,!opened,click,unanswered,scheduled,projected);
    }
}
