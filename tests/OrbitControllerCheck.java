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
        Properties ctrl=new Properties();ctrl.setProperty("orbit_entry_hits","1");ctrl.setProperty("orbit_maintenance_ctrl","1");
        s=OrbitController.Settings.from(ctrl);c.reset();
        d=c.decide(1000,1000,0,s);
        check(d.maintenance&&d.forward&&d.sprintKey&&!d.resetStarted,"Ctrl-held maintenance preserves pursuit input without W reset");
        check(c.decide(1150,1000,0,s).sprintKey,"no delayed release after entry");
        d=c.decide(1200,1000,1200,s);
        check(!d.maintenance&&d.forward&&d.sprintKey,"incoming cue returns to acquisition");
        ctrl.setProperty("orbit_opening_range","3.6");ctrl.setProperty("orbit_repair_every","2");
        s=OrbitController.Settings.from(ctrl);c.reset();
        d=c.decide(1000,0,0,3.61,s);
        check(d.openingWait&&!d.forward&&!d.sprintKey,"wait outside declared local opening range");
        d=c.decide(1050,0,0,3.6,s);
        check(!d.openingWait&&d.forward&&d.sprintKey,"sprint before legal contact");
        c.decide(1100,1100,0,2.9,s);
        check(!c.decide(1250,1100,0,3.8,s).resetStarted,"first cue never resets or re-enters opening wait");
        c.decide(1600,1600,0,2.9,s);
        d=c.decide(1750,1600,0,3.2,s);
        check(d.maintenance&&d.resetStarted&&!d.forward,"even cue arms one native reset");
        d=c.decide(1800,1600,0,3.5,s);
        check(d.forward&&d.sprintKey&&!d.resetStarted,"resume once after even cue");
        c.decide(2100,2100,0,2.9,s);
        check(!c.decide(2250,2100,0,3.5,s).resetStarted,"odd cue preserves the following normal hit");
        ctrl.setProperty("orbit_opening_range","0");ctrl.setProperty("orbit_repair_every","0");ctrl.setProperty("orbit_spacing_range","2.9");
        s=OrbitController.Settings.from(ctrl);c.reset();
        d=c.decide(1000,1000,0,3.2,.15,0,true,true,s);
        check(d.forward&&d.projectedRange<3.2,"far maintenance range requests approach");
        d=c.decide(1050,1000,0,2.8,.15,0,true,true,s);
        check(!d.forward&&Math.abs(d.projectedRange-2.71)<1e-9,"close range coasts and retains immune-contact drag");
        d=c.decide(1100,1000,0,3.2,.04,2,true,false,s);
        check(d.forward,"outward moving target resumes approach");
        ctrl.setProperty("orbit_min_contact_ms","700");
        s=OrbitController.Settings.from(ctrl);c.reset();
        check(c.decide(900,0,0,3.2,0,0,true,false,s).click,"opening may click before any cue");
        d=c.decide(1000,1000,0,2.8,.15,0,true,true,s);
        check(!d.click&&!d.forward&&Math.abs(d.projectedRange-2.65)<1e-9,"quiet cycle coasts without invented contact drag");
        check(!c.decide(1699,1000,0,s).click,"guard remains closed before declared cue age");
        check(c.decide(1700,1000,0,s).click,"guard opens exactly at declared cue age");
        check(!c.decide(1750,1750,0,s).click,"next visible fresh cue starts a new interval");
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
