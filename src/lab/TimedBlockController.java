package lab;

import java.util.Properties;

/** Brief ordinary mouse holds. W-tap movement is handled independently by Client. */
public final class TimedBlockController {
    public record Settings(int ticks,long lead,long late,long retry,boolean alignAttack,boolean latch,boolean contact){
        public static Settings from(Properties p){return new Settings(
            Math.max(1,Math.min(2,Integer.parseInt(p.getProperty("block_hold_ticks","1")))),
            Long.parseLong(p.getProperty("block_lead_ms","25")),Long.parseLong(p.getProperty("block_late_ms","50")),
            Long.parseLong(p.getProperty("block_retry_ms","100")),Boolean.parseBoolean(p.getProperty("block_align_attack","true")),
            Boolean.parseBoolean(p.getProperty("block_latch","false")),p.getProperty("block_trigger","prediction").equals("contact"));}
    }
    public record Input(long now,long tick,long freshHurt,double commandAt,boolean attackQueued,boolean targetInRange){}
    public record Decision(boolean block,String reason,long releaseTick){}
    long releaseTick,lastStart,lastHurt;boolean guarding;double threat=Double.NaN;
    public void reset(){releaseTick=lastStart=lastHurt=0;guarding=false;threat=Double.NaN;}
    public Decision decide(Input in,Settings s){
        if(in.freshHurt!=lastHurt){lastHurt=in.freshHurt;threat=Double.NaN;}
        if(guarding){
            if(in.tick<releaseTick)return new Decision(true,"brief_guard",releaseTick);
            guarding=false;return new Decision(false,"release",releaseTick);
        }
        double at=s.contact?in.now:in.commandAt;
        if(!Double.isFinite(at)){threat=Double.NaN;return new Decision(false,"no_near_hit",releaseTick);}
        if(s.latch){
            if(!Double.isFinite(threat)||in.now>threat+s.late)threat=at;
            else if(at<in.now+125)threat=Math.min(threat,at);
            at=threat;
        }
        if(in.now<at-s.lead||in.now>at+s.late)return new Decision(false,"outside_window",releaseTick);
        if(!s.contact&&in.freshHurt>0&&in.now<in.freshHurt+200)return new Decision(false,"fresh_hurt_release",releaseTick);
        if(in.now-lastStart<s.retry)return new Decision(false,"attack_gap",releaseTick);
        // Left-click is consumed before right-click in vanilla's non-using
        // branch. A one-tick guard then fits between two capped 10 CPS inputs.
        if(s.alignAttack&&(!in.attackQueued||!in.targetInRange))return new Decision(false,"wait_for_swing",releaseTick);
        guarding=true;lastStart=in.now;releaseTick=in.tick+s.ticks;
        return new Decision(true,"predicted_hit",releaseTick);
    }
}
