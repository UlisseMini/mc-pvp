package lab;

/** Per-attacker observer. A completed trick needs two damaging sword contacts,
 * not merely a rod contact or a critical upgrading an earlier sword hit. */
public final class RodTricks {
    // A prompt second hit: at most 250 ms at 20 TPS. Use victim ticks, not ping or wall-clock time.
    static final int FOLLOWUP_TICKS=5;
    int completed;
    private int rodHit,upgradeHit;
    private long upgradeTick;

    void clear(){rodHit=upgradeHit=0;}
    void rod(int hitIndex){clear();rodHit=hitIndex;}

    /** Returns the rod/upgrade/fresh hit IDs exactly once, on the completing hit. */
    int[] melee(long victimTick,int hitIndex,boolean immune,float previousDamage,boolean sword,double healthLoss){
        if(!sword||healthLoss<=0){clear();return null;}
        if(immune){
            if(upgradeHit!=0){
                if(victimTick-upgradeTick>FOLLOWUP_TICKS)clear();
            }else if(rodHit!=0&&previousDamage==0){
                upgradeHit=hitIndex;upgradeTick=victimTick;
            }else clear();
            return null;
        }
        int[] result=null;
        if(rodHit!=0&&upgradeHit!=0&&victimTick>=upgradeTick&&victimTick-upgradeTick<=FOLLOWUP_TICKS){
            result=new int[]{rodHit,upgradeHit,hitIndex};completed++;
        }
        clear();return result;
    }
}
