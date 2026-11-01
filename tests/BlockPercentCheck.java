package lab;

public final class BlockPercentCheck {
    static void check(boolean b,String why){if(!b)throw new AssertionError(why);}
    public static void main(String[] args){
        var stats=new CombatStats();
        check(stats.blockPercent(0)==null,"no hits is undefined");
        check(stats.blockRatioText(0).equals("0/0 (N/A)"),"empty action bar");
        stats.breakdown.blockedTaken.hits=30;
        check(stats.blockPercent(40)==75.0,"blocked / incoming hits");
        check(stats.blockPercentText(40).contains("75.0%")&&stats.blockPercentText(40).contains("30/40 incoming hits"),"chat includes denominator");
        check(stats.blockRatioText(40).equals("30/40 (75.0%)"),"action bar uses the same receiving-side ratio");
        for(int i=0;i<100;i++){stats.blockAccuracy.update(true);stats.blockAccuracy.update(false);}
        check(stats.blockPercent(40)==75.0,"missed attempts do not change hit block percentage");
        check(stats.blockPercent(30)==100.0,"all blocked");
        stats.breakdown.blockedTaken.hits=0;
        check(stats.blockPercent(40)==0.0,"no blocked hits");
        check(stats.damageBlockedPercent()==null,"no damage gives undefined saved percentage");
        stats.breakdown.blockingSaved.damage=6;stats.breakdown.blockedTaken.damage=4;stats.breakdown.unblockedTaken.damage=10;
        check(stats.damageBlockedPercent()==30.0,"saved / (taken + saved), not saved / taken");
        stats.otherDamage=999;stats.regenerated=99;
        check(stats.damageBlockedPercent()==30.0,"environment and regeneration excluded");
        check(stats.blockBarText(40).contains("Dmg blocked: §f30.0%"),"bar includes damage fraction");
        System.out.println("PASS blocked/incoming melee, 75% example, misses ignored, all/none and undefined cases");
    }
}
