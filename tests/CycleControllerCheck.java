import lab.CycleController;
import java.util.Properties;

public class CycleControllerCheck {
    public static void main(String[]args){
        CycleController c=new CycleController();CycleController.Settings s=CycleController.Settings.from(new Properties());
        check(c.decide(99,4,s).forward&&c.decide(99,4,s).click);
        c.queued(100);c.contact(100);check(!c.decide(100,2.8,s).click);
        for(int i=1;i<20;i++){
            CycleController.Decision d=c.decide(100+i,2.8,s);
            check(d.forward==(i<6||i>=13));check(d.click==(i==10));
        }
        check(c.decide(120,2.6,s).coastTicks==8);
        check(!c.decide(133,3,s).forward&&c.decide(134,3,s).forward);
        check(c.decide(140,2.9,s).coastTicks==7);
        c.reset();check(!c.decide(200,3,s).anchored);
        System.out.println("PASS CycleController: input words, sparse click slots, own-view feedback, anchor/reset");
    }
    static void check(boolean b){if(!b)throw new AssertionError();}
}
