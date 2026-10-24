import lab.BurstClickController;

public final class BurstClickControllerCheck {
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    private static BurstClickController.Input at(long now,long landed,long incoming,double rtt){
        return new BurstClickController.Input(now,now-150,landed,incoming,rtt);
    }
    public static void main(String[] ignored){
        var c=new BurstClickController();var s=new BurstClickController.Settings(150,75);
        check(c.decide(at(1000,0,0,20),s).opening(),"opening acquires a first contact");
        var d=c.decide(at(1150,1000,1000,20),s);
        check(d.click()&&d.defensive()&&!d.attackWindow(),"delayed incoming cue starts defensive burst");
        check(d.defensiveUntil()==1300&&d.estimatedReadyAt()==1480&&d.attackStartAt()==1405,"fixed declared windows");
        check(c.decide(at(1299,1000,1000,20),s).defensive(),"burst remains active before its boundary");
        check(!c.decide(at(1300,1000,1000,20),s).click(),"same incoming cue cannot extend burst");
        check(!c.decide(at(1404,1000,1000,20),s).click(),"coast through nonessential clicking interval");
        check(c.decide(at(1405,1000,1000,20),s).attackWindow(),"predicted damage window opens");
        check(c.decide(at(2200,1000,1000,20),s).click(),"a miss cannot restart an invented immunity timer");
        check(!c.decide(at(2350,2200,1000,20),s).click(),"new delayed outgoing cue starts new quiet interval");
        check(c.decide(at(2400,2200,2250,20),s).defensive(),"another received hit can rearm defense");
        c.reset();
        check(c.decide(at(2000,1850,0,500),s).attackWindow(),"high RTT cue can arrive after next useful press time");
        c.reset();
        check(!c.decide(at(1200,1000,1000,20),new BurstClickController.Settings(0,75)).click(),"zero defense is an explicit ablation");
        boolean rejected=false;
        try{c.decide(new BurstClickController.Input(1200,1100,1000,1000,20),s);}catch(IllegalArgumentException expected){rejected=true;}
        check(rejected,"recent observations must never bypass reaction delay");
        rejected=false;
        try{c.decide(at(1200,1100,1000,20),s);}catch(IllegalArgumentException expected){rejected=true;}
        check(rejected,"a future-to-observation hurt cue must be rejected");
        System.out.println("PASS reactive burst timing, cue age, retry and reset");
    }
}
