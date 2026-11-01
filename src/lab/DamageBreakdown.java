package lab;

import java.util.*;

/** Observes a native melee call through acceptance, health loss and its critical callback. */
public final class DamageBreakdown {
    static final class Group {
        int hits;
        double damage;
        void add(double loss){hits++;damage+=loss;}
        Double average(){return hits==0?null:damage/hits;}
        Map<String,Object> state(){
            Map<String,Object> result=new LinkedHashMap<>();result.put("hits",hits);result.put("damage",damage);
            result.put("damage_per_hit",average());return result;
        }
        String text(String label,String color){
            return "  "+color+label+String.format(Locale.ROOT,": §f%.2f total §8| §f",damage/2)+
                (hits==0?"N/A":String.format(Locale.ROOT,"%.2f",damage/hits/2))+" / hit §7("+hits+" hits)";
        }
    }
    final Group blockedTaken=new Group(),unblockedTaken=new Group(),criticalDealt=new Group(),normalDealt=new Group();
    final Group blockingSaved=new Group(),criticalExtra=new Group();
    int damageHitsLanded,damageHitsTaken;
    double blockingSavedNormalHits;
    int undefinedNormalHits;
    Map<String,Object> state(){
        Map<String,Object> result=new LinkedHashMap<>(Map.of("blocked_taken",blockedTaken.state(),"unblocked_taken",unblockedTaken.state(),
            "critical_dealt",criticalDealt.state(),"normal_dealt",normalDealt.state()));
        result.put("blocking_saved",blockingSaved.state());result.put("critical_extra",criticalExtra.state());
        result.put("blocking_saved_normal_hits",undefinedNormalHits==0?blockingSavedNormalHits:null);
        result.put("damage_hits_landed",damageHitsLanded);result.put("damage_hits_taken",damageHitsTaken);
        result.put("blocking_saved_per_received_hit",damageHitsTaken==0?null:blockingSaved.damage/damageHitsTaken);
        result.put("critical_extra_per_landed_hit",damageHitsLanded==0?null:criticalExtra.damage/damageHitsLanded);
        return result;
    }
    static String average(double total,int count){return count==0?"N/A":String.format(Locale.ROOT,"%.3f",total/count/2);}
    List<String> lines(){return List.of(
        String.format(Locale.ROOT,"  §bBlocking saved: §f%.2f hearts §7(%s normal hits)",blockingSaved.damage/2,
            undefinedNormalHits==0?String.format(Locale.ROOT,"%.2f",blockingSavedNormalHits):"N/A"),
        "    §bPer block: §f"+average(blockingSaved.damage,blockingSaved.hits)+" §8| §bPer received hit: §f"+average(blockingSaved.damage,damageHitsTaken),
        String.format(Locale.ROOT,"  §dExtra crit damage: §f%.2f hearts",criticalExtra.damage/2),
        "    §dPer crit: §f"+average(criticalExtra.damage,criticalExtra.hits)+" §8| §dPer landed hit: §f"+average(criticalExtra.damage,damageHitsLanded));}
    static void record(DamageBreakdown attacker,DamageBreakdown defender,boolean critical,boolean blocked,double loss){
        (critical?attacker.criticalDealt:attacker.normalDealt).add(loss);
        (blocked?defender.blockedTaken:defender.unblockedTaken).add(loss);
        if(loss>0){attacker.damageHitsLanded++;defender.damageHitsTaken++;}
    }
    static final class Attack {
        final Object attacker,target;
        final Referee.Match match;
        final String who,victim;
        boolean accepted,blocked,critical;
        double healthLoss;
        float hp,absorption,applied,rawNoncrit,critBase,previousDamage;
        boolean immune,blockApplied,hasCritBase,damageObserved;
        int armor=-1,resistance=-1,protection=-1;
        int hitIndex;
        Attack(Object attacker,Object target,Referee.Match match){
            this.attacker=attacker;this.target=target;this.match=match;who=R.name(attacker);victim=R.name(target);
            previousDamage=(float)R.num(target,"lastDamage");immune=CombatStats.attackImmune(target);
        }
    }
    static final ThreadLocal<Deque<Attack>> attacks=ThreadLocal.withInitial(ArrayDeque::new);
    public static Object begin(Object attacker,Object target){
        if(!"server".equals(System.getProperty("lab.role")))return null;
        try{
            Referee.Match m=CombatStats.match(attacker);
            if(m==null||!m.winner.isEmpty()||attacker==target||m.stats(R.name(target))==null)return null;
            Attack a=new Attack(attacker,target,m);attacks.get().push(a);return a;
        }catch(Throwable e){error(e);return null;}
    }
    static Attack current(Object target,Object source){
        Attack a=attacks.get().peek();
        return a!=null&&a.target==target&&R.call(source,"getEntity")==a.attacker&&
            R.call(source,"getSourceOfDamage")==a.attacker?a:null;
    }
    static void healthLost(Object target,Object source,double loss){
        Attack a=current(target,source);if(a!=null)a.healthLoss+=loss;
    }
    static void blocked(Object target,Object source){Attack a=current(target,source);if(a!=null)a.blocked=true;}
    public static void damageStart(Object target,Object source,float applied){
        if(!"server".equals(System.getProperty("lab.role")))return;
        try{Attack a=current(target,source);if(a!=null){a.damageObserved=true;a.applied=applied;
            a.hp=((Number)R.call(target,"getHealth")).floatValue();a.absorption=((Number)R.call(target,"getAbsorptionAmount")).floatValue();}}
        catch(Throwable e){error(e);}
    }
    static void blockApplied(Object target,Object source){Attack a=current(target,source);if(a!=null)a.blockApplied=true;}
    public static void armor(int value,Object target,Object source){if(!"server".equals(System.getProperty("lab.role")))return;try{Attack a=current(target,source);if(a!=null)a.armor=value;}catch(Throwable e){error(e);}}
    public static void resistance(int value,Object target,Object source){if(!"server".equals(System.getProperty("lab.role")))return;try{Attack a=current(target,source);if(a!=null)a.resistance=value;}catch(Throwable e){error(e);}}
    public static void protection(int value,Object target,Object source){if(!"server".equals(System.getProperty("lab.role")))return;try{Attack a=current(target,source);if(a!=null)a.protection=value;}catch(Throwable e){error(e);}}
    public static void critBase(Object attacker,Object target,float base){
        Attack a=attacks.get().peek();if(a!=null&&a.attacker==attacker&&a.target==target){a.critBase=base;a.hasCritBase=true;}
    }
    public static void planned(Object attacker,Object target,float total,float enchantment){
        Attack a=attacks.get().peek();if(a!=null&&a.attacker==attacker&&a.target==target)a.rawNoncrit=a.hasCritBase?a.critBase+enchantment:total;
    }
    static void accepted(Object target,Object source,int hitIndex){
        Attack a=current(target,source);if(a!=null){a.accepted=true;a.hitIndex=hitIndex;}
    }
    static void critical(Object attacker,Object target){
        Attack a=attacks.get().peek();if(a!=null&&a.attacker==attacker&&a.target==target)a.critical=true;
    }
    public static void end(Object context){
        if(!(context instanceof Attack a))return;
        try{
            if(attacks.get().poll()!=a){attacks.get().clear();throw new IllegalStateException("Melee observation nesting mismatch");}
            if(!a.accepted)return;
            if(!a.damageObserved)throw new IllegalStateException("Accepted melee missing native damage observation");
            double predicted=DamageBenefits.loss(a.applied,a.blockApplied,a.armor,a.resistance,a.protection,a.hp,a.absorption);
            if(Math.abs(predicted-a.healthLoss)>1e-6)throw new IllegalStateException("Native damage calculation mismatch: "+predicted+" vs "+a.healthLoss);
            double withoutBlock=DamageBenefits.loss(a.applied,false,a.armor,a.resistance,a.protection,a.hp,a.absorption);
            float noncritApplied=DamageBenefits.acceptedDamage(a.rawNoncrit,a.previousDamage,a.immune);
            double withoutCrit=DamageBenefits.loss(noncritApplied,a.blockApplied,a.armor,a.resistance,a.protection,a.hp,a.absorption);
            double saved=a.blocked?Math.max(0,withoutBlock-a.healthLoss):0,extra=a.critical?Math.max(0,a.healthLoss-withoutCrit):0;
            double normalHit=DamageBenefits.normalHitDamage(a.rawNoncrit,a.armor,a.resistance,a.protection);
            Double normalHits=DamageBenefits.normalHits(saved,a.rawNoncrit,a.armor,a.resistance,a.protection);
            record(a.match.stats(a.who).breakdown,a.match.stats(a.victim).breakdown,a.critical,a.blocked,a.healthLoss);
            if(a.blocked){
                DamageBreakdown defender=a.match.stats(a.victim).breakdown;defender.blockingSaved.add(saved);
                if(normalHits==null)defender.undefinedNormalHits++;else defender.blockingSavedNormalHits+=normalHits;
            }
            if(a.critical)a.match.stats(a.who).breakdown.criticalExtra.add(extra);
            Map<String,Object> event=new LinkedHashMap<>();event.put("type","melee_damage");event.put("time",System.currentTimeMillis());
            event.put("attacker",a.who);event.put("target",a.victim);event.put("hit_index",a.hitIndex);
            event.put("critical",a.critical);event.put("blocked",a.blocked);event.put("health_loss",a.healthLoss);
            event.put("blocking_saved",saved);event.put("critical_extra",extra);event.put("predicted_health_loss",predicted);
            event.put("normal_hit_damage",normalHit);event.put("blocking_saved_normal_hits",normalHits);
            event.put("without_block_health_loss",withoutBlock);event.put("without_crit_health_loss",withoutCrit);
            event.put("applied_damage",a.applied);event.put("noncrit_raw_damage",a.rawNoncrit);event.put("previous_damage",a.previousDamage);event.put("immune_before",a.immune);
            event.put("block_applied",a.blockApplied);event.put("armor_points",a.armor);event.put("resistance_amplifier",a.resistance);event.put("protection_roll",a.protection);
            event.put("health_before",a.hp);event.put("absorption_before",a.absorption);
            Referee.append(a.match.replay,event);
            long now=System.currentTimeMillis();
            a.match.stats(a.victim).blockTiming.hit(a.blocked,now);
            Referee.timingFeedback(a.match,a.victim,now);
        }catch(Throwable e){error(e);}
    }
    static void error(Throwable e){if(Referee.errors++<10)e.printStackTrace();}
}
