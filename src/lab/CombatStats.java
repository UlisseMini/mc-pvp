package lab;

import java.util.*;

/** Read-only observers for actual healing, health loss and accepted criticals. */
public final class CombatStats {
    int crits,rodHits,upgrades;
    double regenerated,damageDealt,damageTaken,otherDamage;
    final DamageBreakdown breakdown=new DamageBreakdown();
    Map<String,Object> state(int meleeHitsTaken){
        Map<String,Object> state=new LinkedHashMap<>(Map.of("crits",crits,"rod_hits",rodHits,"damage_upgrades",upgrades,
            "regenerated",regenerated,"damage_dealt",damageDealt,"damage_taken",damageTaken,"other_damage",otherDamage));
        state.put("melee_hits_taken",meleeHitsTaken);state.put("damage_taken_per_hit",damageTakenPerHit(meleeHitsTaken));
        state.put("damage_breakdown",breakdown.state());return state;
    }
    /** Actual opponent health loss, including native crit/block/armor effects and partial immunity upgrades.
     * Rod contacts have zero damage and are excluded by the caller's incoming melee count. */
    Double damageTakenPerHit(int meleeHitsTaken){return meleeHitsTaken==0?null:Math.max(0,damageTaken-otherDamage)/meleeHitsTaken;}
    String damageTakenPerHitText(int meleeHitsTaken){
        Double average=damageTakenPerHit(meleeHitsTaken);
        return "  §cDamage taken per hit: "+(average==null?"§7N/A (no melee hits)":
            String.format(Locale.ROOT,"§f%.2f hearts §7(%d melee hits)",average/2,meleeHitsTaken));
    }
    static Referee.Match match(Object p){
        if(Referee.server==null||!"server".equals(System.getProperty("lab.role")))return null;
        Referee.Match m=Referee.find(R.name(p));return m!=null&&System.currentTimeMillis()>=m.start&&m.finish==0?m:null;
    }
    public static boolean attackImmune(Object target){
        return "server".equals(System.getProperty("lab.role"))&&R.num(target,"hurtResistantTime")>R.num(target,"maxHurtResistantTime")/2;
    }
    public static void healed(Object target,float before){
        try{
            Referee.Match m=match(target);if(m==null||!m.winner.isEmpty())return;
            double gained=Math.max(0,((Number)R.call(target,"getHealth")).doubleValue()-before);
            if(gained==0)return;m.stats(R.name(target)).regenerated+=gained;
            Referee.append(m.replay,Map.of("type","heal","time",System.currentTimeMillis(),"player",R.name(target),"amount",gained,"health_before",before,"health_after",R.call(target,"getHealth")));
        }catch(Throwable e){if(Referee.errors++<10)e.printStackTrace();}
    }
    public static void healthDamage(Object target,Object source,float before){
        try{
            Referee.Match m=match(target);if(m==null||!m.winner.isEmpty())return;
            double lost=Math.max(0,before-((Number)R.call(target,"getHealth")).doubleValue());if(lost==0)return;
            DamageBreakdown.healthLost(target,source,lost);
            String victim=R.name(target);Object attacker=R.call(source,"getEntity");String who=attacker==null?"":R.name(attacker);
            m.stats(victim).damageTaken+=lost;
            if(!who.equals(victim)&&m.stats(who)!=null)m.stats(who).damageDealt+=lost;else m.stats(victim).otherDamage+=lost;
            Referee.append(m.replay,Map.of("type","health_damage","time",System.currentTimeMillis(),"attacker",who,"target",victim,
                "amount",lost,"health_before",before,"health_after",R.call(target,"getHealth"),"source",R.call(source,"getDamageType")));
        }catch(Throwable e){if(Referee.errors++<10)e.printStackTrace();}
    }
    public static void critical(Object attacker,Object target){
        try{
            Referee.Match m=match(attacker);if(m==null)return;
            String who=R.name(attacker),victim=R.name(target);
            // The accepted-hit observer already sets winner on a lethal hit.
            if(m.stats(victim)==null||who.equals(victim)||!m.winner.isEmpty()&&!m.winner.equals(who))return;
            m.stats(who).crits++;
            DamageBreakdown.critical(attacker,target);
            Referee.append(m.replay,Map.of("type","critical","time",System.currentTimeMillis(),"attacker",who,"target",victim));
        }catch(Throwable e){if(Referee.errors++<10)e.printStackTrace();}
    }
    void summary(Object recipient,String name,int hits,int blocks,boolean rods,Number health,int meleeHitsTaken){
        String hp=health==null?"§7HP: unavailable":String.format(Locale.ROOT,"§cHP: %.2f/10",health.doubleValue()/2);
        Referee.message(recipient,name+" §8| "+hp+" §8| §eHits: "+breakdown.damageHitsLanded+(rods?" §8| §7Rod contacts: "+rodHits:""));
        Referee.message(recipient,String.format(Locale.ROOT,"  §dCrits: %d §8| §bBlocks: %d §8| §aRegen: +%.2f",crits,blocks,regenerated/2));
        Referee.message(recipient,String.format(Locale.ROOT,"  §6Damage dealt: %.2f §8| §cTaken: %.2f §8| §7Immunity upgrades: %d%s",
            damageDealt/2,damageTaken/2,upgrades,otherDamage>0?String.format(Locale.ROOT," §8| §7Other damage: %.2f",otherDamage/2):""));
        Referee.message(recipient,damageTakenPerHitText(meleeHitsTaken));
        for(String line:breakdown.lines())Referee.message(recipient,line);
    }
}
