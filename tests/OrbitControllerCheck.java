import lab.OrbitController;
import lab.MotionClickController;
import java.util.Properties;

public final class OrbitControllerCheck {
    static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
    public static void main(String[] args){
        OrbitController c=new OrbitController();OrbitController.Settings s=new OrbitController.Settings();
        check(c.decide(1000,0,0,s).sprintKey,"acquisition sprint");
        check(c.decide(1200,1200,0,s).forward,"delayed tap pending");
        check(!c.decide(1350,1200,0,s).forward,"one movement update released");
        check(c.decide(1400,1200,0,s).forward,"immediate resume next update");
        OrbitController.Decision d=c.decide(1750,1750,0,s);
        check(d.maintenance&&d.forward&&!d.sprintKey,"second unanswered cue requests normal maintenance");
        check(c.decide(1950,1750,0,s).maintenance,"no pending tap interrupts maintenance");
        check(!c.decide(2000,1750,2000,s).maintenance,"incoming cue recovers acquisition");
        c.reset();c.decide(1000,1000,0,s);c.decide(1550,1550,0,s);
        check(!c.decide(2600,1550,0,s).maintenance,"lost contact recovers acquisition");
        c.reset();s.enabled=false;c.decide(1000,1000,0,s);
        check(!c.decide(1550,1550,0,s).maintenance,"no-switch ablation");
        Properties p=new Properties();p.setProperty("click_cps","20");
        MotionClickController.Input i=new MotionClickController.Input(1325,1000,0,-.2,true,true,true);
        check(MotionClickController.decide(i,MotionClickController.Settings.from(p)).recoil(),"20CPS has a free recoil slot125ms before start");
        i=new MotionClickController.Input(1375,1000,0,-.2,true,true,true);
        check(MotionClickController.decide(i,MotionClickController.Settings.from(p)).recoil(),"20CPS slot75ms before start is free");
        p.setProperty("click_cps","10");
        check(MotionClickController.decide(i,MotionClickController.Settings.from(p)).reserved(),"10CPS slot75ms before start is reserved");
        System.out.println("PASS orbit acquisition, maintenance, recovery, ablation and CPS-aware reservation");
    }
}
