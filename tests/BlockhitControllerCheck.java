import lab.BlockhitController;

public final class BlockhitControllerCheck {
    static void check(boolean value,String why){if(!value)throw new AssertionError(why);}
    public static void main(String[] args){
        var s=new BlockhitController.Settings(500,75,100,200,150,3.65);
        var c=new BlockhitController();
        check(!c.decide(new BlockhitController.Input(1000,0,0,8,0),s).block(),"no distant blocking");
        check(!c.decide(new BlockhitController.Input(1100,0,0,3,0),s).block(),"do not sacrifice opening trade by pre-blocking");
        check(!c.decide(new BlockhitController.Input(1300,0,0,3,0),s).block(),"opening guard has a bounded duration");
        check(!c.decide(new BlockhitController.Input(1400,1400,0,3,0),s).block(),"release on new incoming hurt cue");
        check(!c.decide(new BlockhitController.Input(1800,1400,0,3,0),s).block(),"move and attack while protected");
        check(c.decide(new BlockhitController.Input(1825,1400,0,3,0),s).block(),"lead the next eligible server hit");
        check(c.decide(new BlockhitController.Input(1725,1400,100,3,0),s).block(),"own RTT advances the command by the full round trip");
        check(!c.decide(new BlockhitController.Input(1900,1900,100,3,0),s).block(),"new hit resynchronizes and releases");
        check(!c.decide(new BlockhitController.Input(2600,1900,0,3,0),s).block(),"late retry has attack gaps");
        c.reset();check(!c.decide(new BlockhitController.Input(5000,0,0,3,0),s).block(),"new match reset");
        System.out.println("PASS block windows, range, bounded guards, RTT compensation, hurt-cue release and reset");
    }
}
