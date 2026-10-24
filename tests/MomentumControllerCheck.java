import lab.MomentumController;
import lab.ComboModel;
import java.util.List;

public final class MomentumControllerCheck {
    static void require(boolean value,String message) { if(!value)throw new AssertionError(message); }
    static MomentumController.Input input(long now,long cue,long incoming,double gap,double own,double target) {
        return new MomentumController.Input(now,now-150,cue,incoming,now-10,gap,own,target,own/20,4,4,true,0,0);
    }
    public static void main(String[] ignored) {
        var controller=new MomentumController();var settings=new MomentumController.Settings();
        require(!controller.decide(input(650,500,0,3,2,-2),settings).forward(),"default cue starts one50ms release");
        require(!controller.decide(input(699,500,0,3,2,-2),settings).forward(),"planned release persists");
        require(controller.decide(input(700,500,0,3,2,-2),settings).forward(),"release ends on schedule");
        require(!controller.decide(input(750,500,0,3,2,-2),settings).resetStarted(),"same cue cannot retrigger");
        controller.reset();settings.resetDelayMs=100;
        require(controller.decide(input(650,500,0,3,2,-2),settings).forward(),"extra delay retains forward input");
        require(!controller.decide(input(750,500,0,3,2,-2),settings).forward(),"delayed planned reset executes");
        controller.reset();settings=new MomentumController.Settings();settings.resetEvery=2;
        require(controller.decide(input(650,500,0,3,2,-2),settings).resetStarted(),"selective reset establishes opening");
        require(controller.decide(input(1150,1000,0,3,2,-2),settings).resetSkipped(),"second hit keeps ordinary sprint behavior");
        require(controller.decide(input(1650,1500,0,3,2,-2),settings).resetStarted(),"third cue resets again");
        controller.reset();settings=new MomentumController.Settings();settings.adaptiveReset=true;
        require(controller.decide(input(650,500,0,3,2,2),settings).resetSkipped(),"comfortable pursuit avoids reset");
        require(controller.decide(input(1150,1000,0,3,2,-2),settings).resetStarted(),"head-on contact still resets");
        controller.reset();settings=new MomentumController.Settings();settings.spacing=true;settings.pauseMs=0;
        require(!controller.decide(input(650,500,0,2.9,4,2),settings).forward(),"pursuit controller coasts before closing too far");
        controller.reset();
        require(controller.decide(input(650,500,500,2.9,4,2),settings).forward(),"incoming-hit guard preserves counteracceleration");
        controller.reset();
        require(controller.decide(input(650,500,0,2.9,-4,2),settings).forward(),"backward knockback does not trigger spacing brake");
        boolean rejected=false;
        try { controller.decide(new MomentumController.Input(650,550,500,0,640,3,2,2,.1,4,4,true,0,0),settings); }
        catch(IllegalArgumentException expected) { rejected=true; }
        require(rejected,"new observations cannot bypass150ms tactical delay");
        controller.reset();settings=new MomentumController.Settings();settings.freshPhase=true;
        var phased=controller.decide(input(650,500,0,3,2,-2),settings);
        require(phased.forward()&&phased.scheduledResetAt()==850,"phase reset uses known physical click history");
        require(!controller.decide(input(850,500,0,3,2,-2),settings).forward(),"release on last pre-fresh planned click");
        require(controller.decide(input(900,500,0,3,2,-2),settings).forward(),"resume before predicted fresh click");
        controller.reset();settings=new MomentumController.Settings();settings.walkReset=true;
        var walkingReset=controller.decide(input(650,500,0,3,2,-2),settings);
        require(walkingReset.forward()&&!walkingReset.sprintKey(),"walk reset preserves W and releases only Ctrl");
        var resumed=controller.decide(input(700,500,0,3,2,-2),settings);
        require(resumed.forward()&&resumed.sprintKey(),"Ctrl returns after planned walk reset");
        controller.reset();settings=new MomentumController.Settings();settings.strafePeriodMs=350;
        require(controller.decide(input(650,0,0,3,2,-2),settings).lateral()==1,"normal strafe key starts planned cadence");
        require(controller.decide(input(1000,0,0,3,2,-2),settings).lateral()==-1,"strafe alternates on planned clock");

        var config=new ComboModel.Config();config.allowWalk=false;config.horizonTicks=12;config.beamWidth=12;config.macroSeeds=true;
        var state=new ComboModel.State(0,new ComboModel.Body(0,0,0,0,-.0784,0,true,true),
            new ComboModel.Body(3,0,0,0,-.0784,0,true,true),new ComboModel.View(3,0,0),new ComboModel.View(0,0,0),
            new ComboModel.View(0,0,0),new ComboModel.View(3,0,0));
        var decision=ComboModel.choose(state,config,List.of(ComboModel.Action.WALK));
        require(decision.plan.stream().noneMatch(a->a==ComboModel.Action.WALK),"no-WALK applies to beam, macros and warm plans");
        require(ComboModel.advanceRecorded(state,ComboModel.Action.WALK,false,config).nowMs==50,
                "planner restriction must not rewrite or reject recorded real controls");
        System.out.println("PASS momentum controller and restricted planning");
    }
}
