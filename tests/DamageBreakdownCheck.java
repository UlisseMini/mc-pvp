package lab;

/** Exercise overlapping crit/block groups and their independent denominators. */
public final class DamageBreakdownCheck {
    static void check(boolean value,String why){if(!value)throw new AssertionError(why);}
    static void group(DamageBreakdown.Group g,int hits,double damage,double average){
        check(g.hits==hits,"group count");check(Math.abs(g.damage-damage)<1e-10,"group damage");
        check(Math.abs(g.average()-average)<1e-10,"group average");
    }
    public static void main(String[] args){
        DamageBreakdown attacker=new DamageBreakdown(),defender=new DamageBreakdown();
        // Blocked crit, unblocked normal, blocked partial crit, zero-loss normal.
        DamageBreakdown.record(attacker,defender,true,true,.6);
        DamageBreakdown.record(attacker,defender,false,false,1.2);
        DamageBreakdown.record(attacker,defender,true,true,.2);
        DamageBreakdown.record(attacker,defender,false,true,0);
        group(attacker.criticalDealt,2,.8,.4);group(attacker.normalDealt,2,1.2,.6);
        group(defender.blockedTaken,3,.8,.8/3);group(defender.unblockedTaken,1,1.2,1.2);
        check(attacker.criticalDealt.text("Crits","§d").contains("0.40 total"),"totals convert HP to hearts");
        check(attacker.criticalDealt.text("Crits","§d").contains("0.20 / hit"),"average uses crit count, converted once");
        check(attacker.blockedTaken.average()==null,"empty receiving group is undefined");
        check(attacker.blockedTaken.text("Blocks","§b").contains("N/A"),"empty average displayed explicitly");
        check(Json.encode(attacker.state()).contains("\"damage_per_hit\":null"),"empty average serializes as null");
        System.out.println("PASS crit/block overlap, group denominators, partial and zero damage, hearts and empty groups");
    }
}
