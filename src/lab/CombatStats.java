package lab;

import java.util.*;

/** Read-only observers for actual healing, health loss and accepted criticals. */
public final class CombatStats {
    int crits,rodHits,upgrades;
    double regenerated,damageDealt,damageTaken,otherDamage;
    final DamageBreakdown breakdown=new DamageBreakdown();
    final BlockAccuracy blockAccuracy=new BlockAccuracy();
    final BlockTiming blockTiming=new BlockTiming();
    Map<String,Object> state(int meleeHitsTaken){
        return state(meleeHitsTaken,System.currentTimeMillis(),0);
    }
    Map<String,Object> state(int meleeHitsTaken,long now,long duration){
        Map<String,Object> state=new LinkedHashMap<>(Map.of("crits",crits,"rod_hits",rodHits,"damage_upgrades",upgrades,
            "regenerated",regenerated,"damage_dealt",damageDealt,"damage_taken",damageTaken,"other_damage",otherDamage));
        state.put("melee_hits_taken",meleeHitsTaken);state.put("damage_taken_per_hit",damageTakenPerHit(meleeHitsTaken));
        state.put("block_percent",blockPercent(meleeHitsTaken));
        state.put("damage_blocked_percent",damageBlockedPercent());
        long blockedMs=blockAccuracy.totalMillis(now);
        state.put("blocking_time_ms",blockedMs);state.put("blocking_uptime_percent",duration>0?100.0*blockedMs/duration:null);
        state.put("blocking_saved_per_second",blockedMs>0?breakdown.blockingSaved.damage*1000/blockedMs:null);
        state.put("damage_breakdown",breakdown.state());state.put("block_accuracy",blockAccuracy.state());return state;
    }
    /** Actual opponent health loss, including native crit/block/armor effects and partial immunity upgrades.
     * Rod contacts have zero damage and are excluded by the caller's incoming melee count. */
    Double damageTakenPerHit(int meleeHitsTaken){return meleeHitsTaken==0?null:Math.max(0,damageTaken-otherDamage)/meleeHitsTaken;}
    Double blockPercent(int meleeHitsTaken){return meleeHitsTaken==0?null:100.0*breakdown.blockedTaken.hits/meleeHitsTaken;}
    Double damageBlockedPercent(){
        double saved=breakdown.blockingSaved.damage,total=breakdown.blockedTaken.damage+breakdown.unblockedTaken.damage+saved;
        return total>0?100.0*saved/total:null;
    }
    String damageBlockedText(){Double pct=damageBlockedPercent();return pct==null?"N/A":String.format(Locale.ROOT,"%.1f%%",pct);}
    String blockRatioText(int meleeHitsTaken){
        Double percent=blockPercent(meleeHitsTaken);
        return percent==null?"0/0 (N/A)":String.format(Locale.ROOT,"%d/%d (%.1f%%)",breakdown.blockedTaken.hits,meleeHitsTaken,percent);
    }
    String blockPercentText(int meleeHitsTaken){
        Double percent=blockPercent(meleeHitsTaken);
        return "  §bBlock %: "+(percent==null?"§7N/A (no incoming melee hits)":
            String.format(Locale.ROOT,"§f%.1f%% §7(%d/%d incoming hits)",percent,breakdown.blockedTaken.hits,meleeHitsTaken))+
            " §8| §bDamage blocked: §f"+damageBlockedText();
    }
    String blockBarText(int meleeHitsTaken){return "§bBlocks: §f"+blockRatioText(meleeHitsTaken)+" §8| §bDmg blocked: §f"+damageBlockedText();}
    String damageTakenPerHitText(int meleeHitsTaken){
        Double average=damageTakenPerHit(meleeHitsTaken);
        return "  §cDamage taken per hit: "+(average==null?"§7N/A (no melee hits)":
            String.format(Locale.ROOT,"§f%.2f hearts §7(%d melee hits)",average/2,meleeHitsTaken));
    }
    static Referee.Match match(Object p){
        if(Referee.server==null||!"server".equals(System.getProperty("lab.role")))return null;
        Referee.Match m=Referee.find(R.name(p));return m!=null&&System.currentTimeMillis()>=m.start&&m.finish==0?m:null;
    }
    /** Observe actual item-use transitions, including press+release within a single server tick. */
    public static void usingItemChanged(Object p){
        try{
            Referee.Match m=match(p);if(m==null||!m.winner.isEmpty())return;
            boolean blocking=(Boolean)R.call(p,"isBlocking");BlockAccuracy accuracy=m.stats(R.name(p)).blockAccuracy;
            long now=System.currentTimeMillis();
            if(accuracy.update(blocking,now)){
                Referee.append(m.replay,Map.of("type","block_attempt","time",now,
                    "player",R.name(p),"blocking",blocking,"attempt",accuracy.attempts,"successful_attempts",accuracy.successful));
                if(m.stats(R.name(p)).blockTiming.transition(blocking,now))Referee.timingFeedback(m,R.name(p),now);
            }
        }catch(Throwable e){if(Referee.errors++<10)e.printStackTrace();}
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
    void summary(Object recipient,String name,int hits,int blocks,boolean rods,Number health,int meleeHitsTaken,long now,long duration){
        String hp=health==null?"§7HP: unavailable":String.format(Locale.ROOT,"§cHP: %.2f/10",health.doubleValue()/2);
        Referee.message(recipient,name+" §8| "+hp+" §8| §eHits: "+breakdown.damageHitsLanded+(rods?" §8| §7Rod contacts: "+rodHits:""));
        Referee.message(recipient,String.format(Locale.ROOT,"  §dCrits: %d §8| §bBlocks: %d §8| §aRegen: +%.2f",crits,blocks,regenerated/2));
        Referee.message(recipient,blockPercentText(meleeHitsTaken));
        long blockedMs=blockAccuracy.totalMillis(now);
        Referee.message(recipient,String.format(Locale.ROOT,"  §bBlocking time: §f%.2fs §7(%s of fight) §8| §bSaved/sec blocking: §f%s hearts",
            blockedMs/1000.0,duration>0?String.format(Locale.ROOT,"%.1f%%",100.0*blockedMs/duration):"N/A",
            blockedMs>0?String.format(Locale.ROOT,"%.2f",breakdown.blockingSaved.damage*500/blockedMs):"N/A"));
        Referee.message(recipient,String.format(Locale.ROOT,"  §6Damage dealt: %.2f §8| §cTaken: %.2f §8| §7Immunity upgrades: %d%s",
            damageDealt/2,damageTaken/2,upgrades,otherDamage>0?String.format(Locale.ROOT," §8| §7Other damage: %.2f",otherDamage/2):""));
        Referee.message(recipient,damageTakenPerHitText(meleeHitsTaken));
        for(String line:breakdown.lines())Referee.message(recipient,line);
    }
}
