package lab;

import java.util.Properties;

/** Experimental ordinary-key controllers. Local contacts and visible hurt cues
 * are observations, not server acceptance/other-client reads. A hurt cue can
 * also represent fall damage, so cue synchronization is deliberately optional.
 */
public final class CycleController {
    public static final class Settings {
        public int hitTicks=10,coastStart=6,coastShort=7,coastLong=8;
        public double switchRange=2.70;
        public String word="";public int burst=1,strafe=0;
        public boolean syncCue=false,syncMovement=false;public double cueLeadMs=75;
        public static Settings from(Properties p){
            Settings s=new Settings();s.hitTicks=Integer.parseInt(p.getProperty("cycle_hit_ticks","10"));
            s.coastStart=Integer.parseInt(p.getProperty("cycle_coast_start","6"));
            s.coastShort=Integer.parseInt(p.getProperty("cycle_coast_short","7"));
            s.coastLong=Integer.parseInt(p.getProperty("cycle_coast_long","8"));
            s.switchRange=Double.parseDouble(p.getProperty("cycle_switch_range","2.70"));
            s.word=p.getProperty("cycle_word","");s.burst=Integer.parseInt(p.getProperty("cycle_burst","1"));
            s.strafe=Integer.parseInt(p.getProperty("cycle_strafe","0"));
            s.syncCue=Integer.parseInt(p.getProperty("cycle_sync_cue","0"))!=0;
            s.syncMovement=Integer.parseInt(p.getProperty("cycle_sync_movement","0"))!=0;
            s.cueLeadMs=Double.parseDouble(p.getProperty("cycle_cue_lead_ms","75"));
            if(s.hitTicks<2||s.hitTicks>20||!s.word.isEmpty()&&(!s.word.matches("[RWS]{2,80}")||s.word.length()%s.hitTicks!=0)||s.burst<1||s.burst>s.hitTicks||Math.abs(s.strafe)>1)
                throw new IllegalArgumentException("cycle_word uses R/W/S and whole hit periods; burst 1..hit_ticks; strafe -1/0/1");
            if(!Double.isFinite(s.cueLeadMs)||s.cueLeadMs<0||s.cueLeadMs>250||s.syncCue&&s.word.isEmpty())
                throw new IllegalArgumentException("Cue sync requires a word; lead must be 0..250ms");
            if(s.hitTicks<2||s.hitTicks>20||s.coastStart<1||s.coastStart>=s.hitTicks||
                s.coastShort<1||s.coastLong<s.coastShort||s.coastStart+s.coastLong>=2*s.hitTicks||
                !Double.isFinite(s.switchRange)||s.switchRange<1||s.switchRange>4)
                throw new IllegalArgumentException("Invalid two-hit cycle parameters");
            return s;
        }
    }
    public static final class Decision {
        public final boolean forward,sprint,click,anchored;
        public final int phase,coastTicks;
        Decision(boolean w,boolean c,boolean anchored,int phase,int coast){this(w,w,c,anchored,phase,coast);}
        Decision(boolean w,boolean s,boolean c,boolean anchored,int phase,int coast){forward=w;sprint=s;click=c;this.anchored=anchored;this.phase=phase;coastTicks=coast;}
    }
    private long anchor=-1,lastQueued=-1,lastPeriod=-1,lastContact=-1,confirmedContact=-1,cues;
    private double lastCue=Double.NEGATIVE_INFINITY,clickStart=Double.NEGATIVE_INFINITY;
    private int coast;
    public void reset(){anchor=lastQueued=lastPeriod=lastContact=confirmedContact=-1;cues=0;lastCue=clickStart=Double.NEGATIVE_INFINITY;coast=0;}
    public void contact(long tick){lastContact=tick;if(anchor<0){anchor=tick;lastPeriod=0;}}
    public void queued(long tick){lastQueued=tick;}
    public long cues(){return cues;}
    public double clickStart(){return clickStart;}
    public Decision decide(long tick,double ownRange,Settings s){
        return decide(tick,ownRange,s,tick*50.0,Double.NEGATIVE_INFINITY,0);
    }
    public Decision decide(long tick,double ownRange,Settings s,double now,double observedCue,double ownRtt){
        if(s.syncCue&&observedCue>lastCue){
            lastCue=observedCue;
            // A recent unconfirmed local contact is a necessary attribution
            // check. At high own RTT, matching to the latest contact is only an
            // estimate; this initial experiment is calibrated at own RTT=0.
            if(lastContact>confirmedContact&&tick-lastContact<=6){
                confirmedContact=lastContact;cues++;
                if(s.syncMovement)anchor=confirmedContact-(cues-1)*s.hitTicks;
                clickStart=observedCue+s.hitTicks*50.0-ownRtt-s.cueLeadMs;
            }
        }
        if(anchor<0){coast=ownRange<s.switchRange?s.coastLong:s.coastShort;return new Decision(true,lastQueued!=tick,false,-1,coast);}
        if(!s.word.isEmpty()){
            long age=tick-anchor;int phase=(int)(age%s.word.length());char key=s.word.charAt(phase);
            // Retry at the normal CPS cap until a new cue. Missing one server
            // boundary must not force a whole extra immunity period of silence.
            boolean click=s.syncCue?now>=clickStart:age%s.hitTicks<s.burst;
            return new Decision(key!='R',key=='S',click&&lastQueued!=tick,true,phase,0);
        }
        long age=tick-anchor,period=age/(2*s.hitTicks);int phase=(int)(age%(2*s.hitTicks));
        if(coast==0||period!=lastPeriod){coast=ownRange<s.switchRange?s.coastLong:s.coastShort;lastPeriod=period;}
        boolean w=phase<s.coastStart||phase>=s.coastStart+coast;
        return new Decision(w,(phase==0||phase==s.hitTicks)&&lastQueued!=tick,true,phase,coast);
    }
}
