import lab.CycleController;
import java.util.Properties;

public class CycleControllerCheck {
    public static void main(String[]args){
        CycleController c=new CycleController();
        check(CycleController.Settings.from(new Properties()).hitTicks==9);
        Properties p=new Properties();p.setProperty("cycle_hit_ticks","10");
        CycleController.Settings s=CycleController.Settings.from(p);
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
        p.setProperty("cycle_word","SSSSSSSSSRRRRWWWWWWW");p.setProperty("cycle_burst","2");
        s=CycleController.Settings.from(p);c.contact(200);
        for(int i=0;i<40;i++){
            CycleController.Decision d=c.decide(200+i,3,s);char key=s.word.charAt(i%20);
            check(d.forward==(key!='R')&&d.sprint==(key=='S')&&d.click==(i%10<2));
        }
        c.queued(230);check(!c.decide(230,3,s).click);
        p.setProperty("cycle_sync_cue","1");p.setProperty("cycle_sync_movement","1");s=CycleController.Settings.from(p);c.reset();
        c.contact(100);c.queued(100);
        check(!c.decide(101,3,s,5050,5050,0).click&&c.cues()==1);
        check(!c.decide(109,3,s,5450,5050,0).click);
        check(c.decide(110,3,s,5500,5050,0).click);
        // Two unsuccessful attempts do not close the window. A delayed fresh
        // cue reanchors the movement word to the most recent local contact.
        c.contact(110);c.queued(110);c.contact(112);c.queued(112);
        check(c.decide(113,3,s,5650,5050,0).click);
        CycleController.Decision sync=c.decide(113,3,s,5650,5650,0);
        check(!sync.click&&sync.phase==11&&c.cues()==2);
        check(c.decide(122,3,s,6100,5650,0).click);
        c.decide(123,3,s,6150,6150,0);check(c.cues()==2); // no new local contact
        c.reset();check(c.cues()==0&&!c.decide(200,3,s).anchored);
        System.out.println("PASS CycleController: input words, sparse click slots, own-view feedback, anchor/reset");
    }
    static void check(boolean b){if(!b)throw new AssertionError();}
}
