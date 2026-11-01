package lab;

/** Same-hit alternatives with the observed native armor/Resistance/Protection roll.
 * Float operations follow the pinned vanilla order; HP loss includes absorption and overkill caps. */
public final class DamageBenefits {
    public static double loss(float applied,boolean block,int armor,int resistance,int protection,float hp,float absorption){
        float damage=mitigated(applied,block,armor,resistance,protection);
        damage=Math.max(0,damage-absorption);
        return (double)hp-Math.max(0,hp-damage);
    }
    static float mitigated(float applied,boolean block,int armor,int resistance,int protection){
        if(applied<=0)return 0;
        float damage=applied;
        if(block)damage=(1.0f+damage)*.5f;
        if(armor>=0)damage=damage*(25-armor)/25.0f;
        if(resistance>=0)damage=damage*(25-(resistance+1)*5)/25.0f;
        if(damage<=0)return 0;
        if(protection>0)damage=damage*(25-Math.min(20,protection))/25.0f;
        return damage;
    }
    /** Full fresh, unblocked, noncritical damage unit. No immunity delta, absorption or remaining-HP cap. */
    public static double normalHitDamage(float noncritRaw,int armor,int resistance,int protection){return mitigated(noncritRaw,false,armor,resistance,protection);}
    public static Double normalHits(double saved,float noncritRaw,int armor,int resistance,int protection){
        double unit=normalHitDamage(noncritRaw,armor,resistance,protection);
        if(unit>0)return saved/unit;
        if(saved==0)return 0.0;
        return null;
    }
    public static float acceptedDamage(float raw,float previous,boolean immune){return immune?Math.max(0,raw-previous):raw;}
}
