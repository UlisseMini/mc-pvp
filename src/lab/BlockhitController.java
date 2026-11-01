package lab;

import java.util.Properties;

/** Predict a server hit window using the native fresh-hurt status receipt and known own RTT. */
public final class BlockhitController {
    public static final class Settings {
        private final long period; private final long lead; private final long late; private final long opening; private final long retry; private final double range;
        public Settings(long period, long lead, long late, long opening, long retry, double range){this.period=period; this.lead=lead; this.late=late; this.opening=opening; this.retry=retry; this.range=range;}
        public long period(){return period;} public long lead(){return lead;} public long late(){return late;} public long opening(){return opening;} public long retry(){return retry;} public double range(){return range;}
        @Override public boolean equals(Object o){if(!(o instanceof Settings))return false;Settings that=(Settings)o;return java.util.Objects.equals(period, that.period) && java.util.Objects.equals(lead, that.lead) && java.util.Objects.equals(late, that.late) && java.util.Objects.equals(opening, that.opening) && java.util.Objects.equals(retry, that.retry) && java.util.Objects.equals(range, that.range);} @Override public int hashCode(){return java.util.Objects.hash(period,lead,late,opening,retry,range);}

        static long value(Properties p,String key,long fallback){return Long.parseLong(p.getProperty(key,Long.toString(fallback)));}
        public static Settings from(Properties p){return new Settings(value(p,"block_period_ms",500),value(p,"block_lead_ms",75),
            value(p,"block_late_ms",100),value(p,"block_open_ms",200),value(p,"block_retry_ms",150),
            Double.parseDouble(p.getProperty("block_range","3.65")));}
    }
    public static final class Input {
        private final long now; private final long incomingCue; private final double ownRtt; private final double distance; private final double closing;
        public Input(long now, long incomingCue, double ownRtt, double distance, double closing){this.now=now; this.incomingCue=incomingCue; this.ownRtt=ownRtt; this.distance=distance; this.closing=closing;}
        public long now(){return now;} public long incomingCue(){return incomingCue;} public double ownRtt(){return ownRtt;} public double distance(){return distance;} public double closing(){return closing;}
        @Override public boolean equals(Object o){if(!(o instanceof Input))return false;Input that=(Input)o;return java.util.Objects.equals(now, that.now) && java.util.Objects.equals(incomingCue, that.incomingCue) && java.util.Objects.equals(ownRtt, that.ownRtt) && java.util.Objects.equals(distance, that.distance) && java.util.Objects.equals(closing, that.closing);} @Override public int hashCode(){return java.util.Objects.hash(now,incomingCue,ownRtt,distance,closing);}
}
    public static final class Decision {
        private final boolean block; private final long readyAt; private final long windowStart; private final long windowEnd; private final String reason;
        public Decision(boolean block, long readyAt, long windowStart, long windowEnd, String reason){this.block=block; this.readyAt=readyAt; this.windowStart=windowStart; this.windowEnd=windowEnd; this.reason=reason;}
        public boolean block(){return block;} public long readyAt(){return readyAt;} public long windowStart(){return windowStart;} public long windowEnd(){return windowEnd;} public String reason(){return reason;}
        @Override public boolean equals(Object o){if(!(o instanceof Decision))return false;Decision that=(Decision)o;return java.util.Objects.equals(block, that.block) && java.util.Objects.equals(readyAt, that.readyAt) && java.util.Objects.equals(windowStart, that.windowStart) && java.util.Objects.equals(windowEnd, that.windowEnd) && java.util.Objects.equals(reason, that.reason);} @Override public int hashCode(){return java.util.Objects.hash(block,readyAt,windowStart,windowEnd,reason);}
}
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
