package lab;

import java.util.Properties;

/** Brief ordinary mouse holds. W-tap movement is handled independently by Client. */
public final class TimedBlockController {
    public static final class Settings {
        private final int ticks; private final long lead; private final long late; private final long retry; private final boolean alignAttack; private final boolean latch; private final boolean contact;
        public Settings(int ticks, long lead, long late, long retry, boolean alignAttack, boolean latch, boolean contact){this.ticks=ticks; this.lead=lead; this.late=late; this.retry=retry; this.alignAttack=alignAttack; this.latch=latch; this.contact=contact;}
        public int ticks(){return ticks;} public long lead(){return lead;} public long late(){return late;} public long retry(){return retry;} public boolean alignAttack(){return alignAttack;} public boolean latch(){return latch;} public boolean contact(){return contact;}
        @Override public boolean equals(Object o){if(!(o instanceof Settings))return false;Settings that=(Settings)o;return java.util.Objects.equals(ticks, that.ticks) && java.util.Objects.equals(lead, that.lead) && java.util.Objects.equals(late, that.late) && java.util.Objects.equals(retry, that.retry) && java.util.Objects.equals(alignAttack, that.alignAttack) && java.util.Objects.equals(latch, that.latch) && java.util.Objects.equals(contact, that.contact);} @Override public int hashCode(){return java.util.Objects.hash(ticks,lead,late,retry,alignAttack,latch,contact);}

        public static Settings from(Properties p){return new Settings(
            Math.max(1,Math.min(2,Integer.parseInt(p.getProperty("block_hold_ticks","1")))),
            Long.parseLong(p.getProperty("block_lead_ms","25")),Long.parseLong(p.getProperty("block_late_ms","50")),
            Long.parseLong(p.getProperty("block_retry_ms","100")),Boolean.parseBoolean(p.getProperty("block_align_attack","true")),
            Boolean.parseBoolean(p.getProperty("block_latch","false")),p.getProperty("block_trigger","prediction").equals("contact"));}
    }
    public static final class Input {
        private final long now; private final long tick; private final long freshHurt; private final double commandAt; private final boolean attackQueued; private final boolean targetInRange;
        public Input(long now, long tick, long freshHurt, double commandAt, boolean attackQueued, boolean targetInRange){this.now=now; this.tick=tick; this.freshHurt=freshHurt; this.commandAt=commandAt; this.attackQueued=attackQueued; this.targetInRange=targetInRange;}
        public long now(){return now;} public long tick(){return tick;} public long freshHurt(){return freshHurt;} public double commandAt(){return commandAt;} public boolean attackQueued(){return attackQueued;} public boolean targetInRange(){return targetInRange;}
        @Override public boolean equals(Object o){if(!(o instanceof Input))return false;Input that=(Input)o;return java.util.Objects.equals(now, that.now) && java.util.Objects.equals(tick, that.tick) && java.util.Objects.equals(freshHurt, that.freshHurt) && java.util.Objects.equals(commandAt, that.commandAt) && java.util.Objects.equals(attackQueued, that.attackQueued) && java.util.Objects.equals(targetInRange, that.targetInRange);} @Override public int hashCode(){return java.util.Objects.hash(now,tick,freshHurt,commandAt,attackQueued,targetInRange);}
}
    public static final class Decision {
        private final boolean block; private final String reason; private final long releaseTick;
        public Decision(boolean block, String reason, long releaseTick){this.block=block; this.reason=reason; this.releaseTick=releaseTick;}
        public boolean block(){return block;} public String reason(){return reason;} public long releaseTick(){return releaseTick;}
        @Override public boolean equals(Object o){if(!(o instanceof Decision))return false;Decision that=(Decision)o;return java.util.Objects.equals(block, that.block) && java.util.Objects.equals(reason, that.reason) && java.util.Objects.equals(releaseTick, that.releaseTick);} @Override public int hashCode(){return java.util.Objects.hash(block,reason,releaseTick);}
}
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
