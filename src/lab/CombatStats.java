package lab;

import java.util.*;

/** Read-only observers for actual healing, health loss and accepted criticals. */
public final class CombatStats {
    int crits,rodHits,upgrades;
    double regenerated,damageDealt,damageTaken,otherDamage;
    Map<String,Object> state(){return Map.of("crits",crits,"rod_hits",rodHits,"damage_upgrades",upgrades,
        "regenerated",regenerated,"damage_dealt",damageDealt,"damage_taken",damageTaken,"other_damage",otherDamage);}
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
            Referee.append(m.replay,Map.of("type","critical","time",System.currentTimeMillis(),"attacker",who,"target",victim));
        }catch(Throwable e){if(Referee.errors++<10)e.printStackTrace();}
    }
    void summary(Object recipient,String name,int hits,int blocks,boolean rods,Number health){
        String hp=health==null?"§7HP: unavailable":String.format(Locale.ROOT,"§cHP: %.2f/10",health.doubleValue()/2);
        Referee.message(recipient,name+" §8| "+hp+" §8| §eHits: "+hits+(rods?" §7("+rodHits+" rod)":""));
        Referee.message(recipient,String.format(Locale.ROOT,"  §dCrits: %d §8| §bBlocks: %d §8| §aRegen: +%.2f",crits,blocks,regenerated/2));
        Referee.message(recipient,String.format(Locale.ROOT,"  §6Damage dealt: %.2f §8| §cTaken: %.2f §8| §7Immunity upgrades: %d%s",
            damageDealt/2,damageTaken/2,upgrades,otherDamage>0?String.format(Locale.ROOT," §8| §7Other damage: %.2f",otherDamage/2):""));
    }
}
