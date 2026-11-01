import lab.DamageBenefits;

/** Analytic cases independent of the live event collector. Health values are HP, not hearts. */
public final class DamageBenefitsCheck {
    static void near(double actual,double expected,String why){if(Math.abs(actual-expected)>2e-6)throw new AssertionError(why+": "+actual+" vs "+expected);}
    static double loss(float d,boolean block){return DamageBenefits.loss(d,block,20,-1,0,20,0);}
    public static void main(String[] args){
        near(loss(8,false),1.6,"ordinary diamond armor damage");
        near(loss(8,true),.9,"blocking precedes armor");
        near(loss(8,false)-loss(8,true),.7,"block savings");
        near(loss(12,false)-loss(8,false),.8,"full critical bonus");
        near(loss(12,true)-loss(8,true),.4,"critical bonus while blocked");
        near(loss(4,false)-loss(DamageBenefits.acceptedDamage(8,8,true),false),.8,"crit upgrade: normal hit would be rejected");
        near(loss(DamageBenefits.acceptedDamage(12,0,true),false)-loss(DamageBenefits.acceptedDamage(8,0,true),false),.8,"rod-to-crit gets only genuine crit bonus");
        near(loss(DamageBenefits.acceptedDamage(8,1,true),false),1.4,"low-damage melee to sword upgrade");
        near(DamageBenefits.loss(8,true,20,-1,20,20,0),.18,"observed Protection roll also reduces block savings");
        near(DamageBenefits.loss(8,false,20,0,20,20,0),.256,"Resistance then Protection");
        near(DamageBenefits.loss(8,true,20,-1,0,20,.9f),0,"absorption can absorb all blocked damage");
        near(DamageBenefits.loss(8,false,20,-1,0,.1f,0),.1,"lethal damage capped at actual HP");
        near(DamageBenefits.loss(12,false,20,-1,0,.1f,0)-DamageBenefits.loss(8,false,20,-1,0,.1f,0),0,"both versions lethal: no extra HP removed");
        near(DamageBenefits.loss(0,true,20,-1,20,20,0),0,"rejected noncrit must not gain block's +1");
        near(DamageBenefits.normalHits(.7,8,20,-1,0),.4375,"diamond normal-hit equivalence");
        near(DamageBenefits.normalHits(.4,5,20,-1,0),.4,"wood normal-hit equivalence");
        near(DamageBenefits.normalHits(1.1,8,20,-1,0),.6875,"blocked crit uses noncrit denominator");
        near(DamageBenefits.normalHits(.3,8,20,-1,0),.1875,"partial crit upgrade uses full normal-hit denominator");
        near(DamageBenefits.normalHitDamage(8,20,-1,20),.32,"Protection applies to normal reference");
        near(DamageBenefits.normalHitDamage(8,0,-1,0),8,"unarmored reference");
        near(DamageBenefits.normalHitDamage(8,10,-1,0),4.8,"partial armor reference");
        near(DamageBenefits.normalHits(0,8,20,4,20),0,"zero saved with invulnerability");
        if(DamageBenefits.normalHits(1,0,20,-1,0)!=null)throw new AssertionError("undefined zero-damage reference");
        System.out.println("PASS block/crit benefits, overlapping effects, immunity/rod cases, Protection, Resistance, absorption and lethal caps");
    }
}
