package lab;

import java.util.Properties;

/** Predict a server hit window using the native fresh-hurt status receipt and known own RTT. */
public final class BlockhitController {
    public record Settings(long period,long lead,long late,long opening,long retry,double range){
        static long value(Properties p,String key,long fallback){return Long.parseLong(p.getProperty(key,Long.toString(fallback)));}
        public static Settings from(Properties p){return new Settings(value(p,"block_period_ms",500),value(p,"block_lead_ms",75),
            value(p,"block_late_ms",100),value(p,"block_open_ms",200),value(p,"block_retry_ms",150),
            Double.parseDouble(p.getProperty("block_range","3.65")));}
    }
    public record Input(long now,long incomingCue,double ownRtt,double distance,double closing){}
    public record Decision(boolean block,long readyAt,long windowStart,long windowEnd,String reason){}
    public void reset(){}
    public Decision decide(Input in,Settings s){
        double predicted=in.distance-Math.max(0,Math.min(8,in.closing))*(in.ownRtt/1000.0+.05);
        if(predicted>s.range)return new Decision(false,0,0,0,"out_of_range");
        // Obtain the opening trade before guarding: pre-blocking at approach
        // slows movement and gives a W-tapper the first-hit spacing advantage.
        if(in.incomingCue==0)return new Decision(false,0,0,0,"opening_trade");
        long ready=in.incomingCue+s.period-(long)in.ownRtt,start=ready-s.lead,end=ready+s.late;
        if(in.now<start)return new Decision(false,ready,start,end,"protected_window");
        if(in.now<=end)return new Decision(true,ready,start,end,"predicted_hit");
        // A missed/late approach invalidates the phase. Alternate brief guards
        // with attack opportunities until another visible hit re-synchronizes it.
        long phase=(in.now-end)%(s.opening+s.retry);
        return new Decision(phase>=s.retry,ready,start,end,"late_retry");
    }
}
