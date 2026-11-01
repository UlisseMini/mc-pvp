package lab;

import java.util.Arrays;

public final class RodTricksCheck {
    static void check(boolean value,String why){if(!value)throw new AssertionError(why);}
    static RodTricks setup(){
        RodTricks s=new RodTricks();s.rod(1);
        check(s.melee(100,2,true,0,true,1)==null,"upgrade alone is not a completed trick");return s;
    }
    public static void main(String[] args){
        RodTricks s=setup();
        check(Arrays.equals(s.melee(102,3,false,5,true,1),new int[]{1,2,3}),"prompt fresh sword completes the rod sequence");
        check(s.completed==1,"count once");
        s.melee(112,4,false,5,true,1);check(s.completed==1,"later hits do not double count");
        s=new RodTricks();s.rod(1);s.melee(10,2,false,0,true,1);s.melee(20,3,false,5,true,1);
        check(s.completed==0,"rod followed by two ordinary fresh hits is not a trick");
        s=new RodTricks();s.melee(0,1,false,0,true,1);s.melee(5,2,true,5,true,.5);s.melee(10,3,false,7.5f,true,1);
        check(s.completed==0,"normal-to-critical upgrade is not a rod trick");
        s=setup();s.melee(105,3,false,5,true,1);check(s.completed==1,"inclusive five-tick boundary");
        s=setup();s.melee(106,3,false,5,true,1);check(s.completed==0,"late follow-up excluded");
        s=setup();s.melee(102,3,true,5,true,.5);s.melee(104,4,false,7.5f,true,1);
        check(s.completed==1,"intervening crit upgrade allowed, counted once");
        s=setup();s.melee(104,3,true,5,true,.5);s.melee(106,4,false,7.5f,true,1);
        check(s.completed==0,"crit upgrade cannot extend original follow-up deadline");
        s=setup();s.rod(3);s.melee(102,4,false,0,true,1);check(s.completed==0,"new rod replaces unfinished setup");
        s=new RodTricks();s.rod(1);s.melee(100,2,true,0,false,.2);s.melee(102,3,true,1,true,.8);s.melee(104,4,false,5,true,1);
        check(s.completed==0,"rod-to-punch-to-sword is not rod-to-sword");
        s=new RodTricks();s.rod(1);s.melee(100,2,true,0,true,0);s.melee(102,3,false,5,true,1);
        check(s.completed==0,"upgrade must actually damage HP");
        s=setup();s.melee(102,3,false,5,true,0);check(s.completed==0,"fresh follow-up must damage HP");
        s=setup();s.clear();s.melee(102,3,false,5,true,1);check(s.completed==0,"other damage interrupts setup");
        s=setup();s.melee(99,3,false,5,true,1);check(s.completed==0,"backward clock rejected");
        s=setup();s.melee(102,3,false,5,true,.01);check(s.completed==1,"lethal small remaining HP still completes trick");
        System.out.println("PASS rod trick sequence, provenance, timing boundaries, damage/weapon guards, crits and reset rules");
    }
}
