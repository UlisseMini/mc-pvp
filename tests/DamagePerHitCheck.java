package lab;

import java.util.Map;

/** Check receiving-side accounting, rod exclusion, health units and undefined averages. */
public final class DamagePerHitCheck {
    static void check(boolean value,String why){if(!value)throw new AssertionError(why);}
    @SuppressWarnings("unchecked")
    static Map<String,Object> stats(Referee.Match m,String side){return (Map<String,Object>)m.state().get("stats_"+side);}
    static void near(double actual,double expected,String why){check(Math.abs(actual-expected)<1e-10,why);}
    public static void main(String[] args){
        Referee.Match m=new Referee.Match();m.pa="human";m.pb="hold-w";
        // Unequal hit totals catch accidentally dividing by the victim's outgoing hits.
        m.ha=3;m.sa.rodHits=1;m.hb=8;m.sb.rodHits=3;
        m.sa.damageTaken=10.25;m.sa.otherDamage=2.25;m.sa.regenerated=5;
        m.sb.damageTaken=6;m.sb.regenerated=3;
        m.sa.crits=1;m.sb.crits=2;m.ba=2;m.bb=1;
        near((Double)stats(m,"a").get("damage_taken_per_hit"),1.6,"A: opponent damage / incoming melee hits");
        near((Double)stats(m,"b").get("damage_taken_per_hit"),3,"B: its own damage and received hit count");
        check(stats(m,"a").get("melee_hits_taken").equals(5),"exclude three incoming rods");
        check(m.sa.damageTakenPerHitText(5).contains("0.80 hearts"),"convert health points to hearts once");
        // Crits/blocks already affected health loss: counters must not apply a second multiplier.
        m.sb.crits=0;m.ba=0;m.sa.regenerated=50;
        near((Double)stats(m,"a").get("damage_taken_per_hit"),1.6,"do not reweight crits/blocks or subtract regeneration");
        m.hb=3;m.sa.damageTaken=2;m.sa.otherDamage=2;
        check(stats(m,"a").get("damage_taken_per_hit")==null,"rod-only incoming contacts have no melee average");
        check(m.sa.damageTakenPerHitText(0).contains("N/A"),"undefined average is explicit in chat");
        check(Json.encode(stats(m,"a")).contains("\"damage_taken_per_hit\":null"),"undefined average is JSON null");
        near(m.sa.damageTakenPerHit(1),0,"accepted zero-loss melee hit has a defined zero average");
        m.hb=0;m.sb.rodHits=0;
        check(stats(m,"a").get("damage_taken_per_hit")==null,"no incoming hits");
        System.out.println("PASS damage per incoming melee hit, rod/environment exclusion, crit/block accounting, healing, hearts and zero-hit cases");
    }
}
