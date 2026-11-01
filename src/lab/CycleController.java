package lab;

import java.util.Properties;

/** Two-hit shooting solution implemented with ordinary keys and sparse clicks.
 * Anchor is the first local native sword contact, not a server/other-client read.
 * W+Ctrl 0..5; coast 6..12 or 13; pursue until 19. Click at 0 and 10.
 */
public final class CycleController {
    public static final class Settings {
        public int hitTicks=10,coastStart=6,coastShort=7,coastLong=8;
        public double switchRange=2.70;
        public static Settings from(Properties p){
            Settings s=new Settings();s.hitTicks=Integer.parseInt(p.getProperty("cycle_hit_ticks","10"));
            s.coastStart=Integer.parseInt(p.getProperty("cycle_coast_start","6"));
            s.coastShort=Integer.parseInt(p.getProperty("cycle_coast_short","7"));
            s.coastLong=Integer.parseInt(p.getProperty("cycle_coast_long","8"));
            s.switchRange=Double.parseDouble(p.getProperty("cycle_switch_range","2.70"));
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
        Decision(boolean w,boolean c,boolean anchored,int phase,int coast){forward=sprint=w;click=c;this.anchored=anchored;this.phase=phase;coastTicks=coast;}
    }
    private long anchor=-1,lastQueued=-1,lastPeriod=-1;
    private int coast;
    public void reset(){anchor=lastQueued=lastPeriod=-1;coast=0;}
    public void contact(long tick){if(anchor<0){anchor=tick;lastPeriod=0;}}
    public void queued(long tick){lastQueued=tick;}
    public Decision decide(long tick,double ownRange,Settings s){
        if(anchor<0){coast=ownRange<s.switchRange?s.coastLong:s.coastShort;return new Decision(true,lastQueued!=tick,false,-1,coast);}
        long age=tick-anchor,period=age/(2*s.hitTicks);int phase=(int)(age%(2*s.hitTicks));
        if(coast==0||period!=lastPeriod){coast=ownRange<s.switchRange?s.coastLong:s.coastShort;lastPeriod=period;}
        boolean w=phase<s.coastStart||phase>=s.coastStart+coast;
        return new Decision(w,(phase==0||phase==s.hitTicks)&&lastQueued!=tick,true,phase,coast);
    }
}
