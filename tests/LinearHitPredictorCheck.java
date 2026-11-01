import lab.LinearHitPredictor;
import lab.TimedBlockController;

public final class LinearHitPredictorCheck {
    static void check(boolean value,String why){if(!value)throw new AssertionError(why);}
    public static void main(String[] args){
        var p=new LinearHitPredictor();var settings=new LinearHitPredictor.Settings(150,0,300,3,0);
        p.predict(new LinearHitPredictor.Sample(1000,0,4,0,5,4,0),0,0,0,settings);
        p.predict(new LinearHitPredictor.Sample(1050,.25,4,0,4.75,4,0),0,0,0,settings);
        var r=p.predict(new LinearHitPredictor.Sample(1100,.5,4,0,4.5,4,0),0,0,0,settings);
        check(Math.abs(r.ownVx()-5)<1e-8&&Math.abs(r.targetVx()+5)<1e-8,"fit each player's observed motion");
        check(r.receiptAt()>=1160&&r.receiptAt()<=1165,"linear approach reaches expanded box in 60 ms");
        var immune=p.predict(new LinearHitPredictor.Sample(1100,.5,4,0,4.5,4,0),1000,0,0,settings);
        check(!Double.isFinite(immune.receiptAt()),"no prediction outside short horizon while immune");
        var network=p.predict(new LinearHitPredictor.Sample(1100,.5,4,0,4.5,4,0),0,100,100,settings);
        check(Math.abs(network.receiptAt()-r.receiptAt()-100)<6,"symmetric RTT shifts cue arrival");
        check(Math.abs(network.commandAt()-r.commandAt())<6,"same outbound delay cancels for guard command");
        var delivered=p.predict(new LinearHitPredictor.Sample(1100,.5,4,0,4.5,4,0),0,100,100,new LinearHitPredictor.Settings(150,0,300,3,45));
        check(delivered.receiptAt()==network.receiptAt()&&delivered.commandAt()==network.commandAt()-45,"pipeline compensation changes command time, not prediction");
        var calibration=p.hurt(1175,0);
        check(calibration!=null&&calibration.forecastTime()==1100,"calibrate from a stored earlier forecast");
        check(calibration.correction()>0&&calibration.correction()<10,"late observed hit shifts future forecasts later");
        p.reset();check(p.hurt(1200,0)==null,"new duel has no inherited timing samples");
        var c=new TimedBlockController();var guard=new TimedBlockController.Settings(1,25,50,100,true,false,false);
        check(!c.decide(new TimedBlockController.Input(1000,20,0,1000,false,true),guard).block(),"do not sacrifice scheduled attack");
        check(c.decide(new TimedBlockController.Input(1000,20,0,1000,true,true),guard).block(),"swing then block");
        check(!c.decide(new TimedBlockController.Input(1047,21,0,1047,false,true),guard).block(),"release next client tick even if less than 50 ms elapsed");
        check(!c.decide(new TimedBlockController.Input(1050,22,1040,1050,true,true),guard).block(),"release after fresh hurt");
        check(!c.decide(new TimedBlockController.Input(1300,26,1040,1300,true,false),guard).block(),"no out-of-reach attack-aligned blocks");
        var latched=new TimedBlockController();var latch=new TimedBlockController.Settings(1,50,50,100,true,true,false);
        check(!latched.decide(new TimedBlockController.Input(1000,20,0,1070,true,true),latch).block(),"not yet within guard window");
        check(latched.decide(new TimedBlockController.Input(1050,21,0,1120,true,true),latch).block(),"rolling click-wait estimate cannot postpone a near-term guard forever");
        System.out.println("PASS linear motion, range crossing, immunity, RTT and one-tick swing-aligned guard");
    }
}
