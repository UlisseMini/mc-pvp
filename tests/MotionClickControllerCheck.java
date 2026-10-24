import lab.MotionClickController;
import lab.MomentumController;

public final class MotionClickControllerCheck {
    static void check(boolean b,String message){if(!b)throw new AssertionError(message);}
    static MotionClickController.Decision at(long now,double motion,boolean sprint,boolean poll,boolean recoil){
        return MotionClickController.decide(new MotionClickController.Input(now,1000,20,motion,sprint,poll,true),
                new MotionClickController.Settings(50,recoil));
    }
    public static void main(String[] args){
        check(at(1100,-.2,true,true,true).recoil(),"current backward sprint permits recoil click");
        check(!at(1100,.2,true,true,true).click(),"do not brake pursuit before attack window");
        check(!at(1100,-.2,false,true,true).click(),"no sprint means no recoil cancellation benefit");
        check(!at(1100,-.2,true,false,true).click(),"recoil input cannot queue from an earlier frame");
        check(!at(1100,-.2,true,true,false).click(),"window-only ablation removes only recoil clicks");
        check(at(1329,-.2,true,true,true).recoil(),"recoil leaves more than100ms before attack window");
        check(at(1330,-.2,true,true,true).reserved()&&!at(1330,-.2,true,true,true).click(),"reserve next input slot conservatively");
        check(at(1430,.2,true,true,true).attackWindow(),"attack window overrides forward-motion preference");
        check(at(2500,.2,false,false,true).click(),"misses keep attack window open pending visible cue");
        var opening=MotionClickController.decide(new MotionClickController.Input(500,0,20,.2,true,false,false),new MotionClickController.Settings(50,true));
        check(opening.click()&&opening.opening(),"normal opening acquisition");
        check(!MotionClickController.decide(new MotionClickController.Input(1100,1000,20,-.2,true,true,false),
                new MotionClickController.Settings(50,true)).click(),"a recoil-only miss cannot help and must not arm miss cooldown");
        var settings=new MomentumController.Settings();settings.reactionMs=0;settings.pauseTicks=1;
        var controller=new MomentumController();
        var instant=new MomentumController.Input(1000,1000,1000,0,990,3,2,0,.1,4,4,true,20,20);
        check(controller.decide(instant,settings).resetStarted(),"explicit zero-delay Wtap uses current cue");
        boolean rejected=false;
        try{new MomentumController().decide(instant,new MomentumController.Settings());}catch(IllegalArgumentException expected){rejected=true;}
        check(rejected,"historical150ms controllers retain their declared limit");
        System.out.println("PASS current-motion bursts, input-slot reservation and explicit zero-delay controls");
    }
}
