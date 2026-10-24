import lab.CriticalClickController;

public final class CriticalClickControllerCheck {
    static void check(boolean b,String message){if(!b)throw new AssertionError(message);}
    public static void main(String[] args){
        var c=new CriticalClickController();
        check(c.eligible(1200,1000,1430,false,false,true,true,true),"falling contact in spare input slot");
        check(c.eligible(1250,1000,1430,false,false,true,true,true),"observing an opportunity does not spend budget");
        check(!c.eligible(1330,1000,1430,false,false,true,true,true),"reserve upcoming attack input");
        check(!c.eligible(1200,1000,1430,false,false,true,true,false),"rising/grounded cannot claim local critical");
        check(!c.eligible(1200,1000,1430,false,false,false,true,true),"only native input poll");
        check(!c.eligible(1200,1000,1430,false,false,true,false,true),"no optional out-of-range attempt");
        c.injected(1000);
        check(!c.eligible(1250,1000,1430,false,false,true,true,true),"only one attempt per cue, even when overlapping recoil");
        check(c.eligible(1700,1500,1930,false,false,true,true,true),"new visible cue replenishes budget");
        c.reset();check(c.eligible(1200,1000,1430,false,false,true,true,true),"match reset clears budget");
        System.out.println("PASS optional critical budget, local eligibility and reserved next attack");
    }
}
