import java.util.Properties;
import lab.CombatProfile;
import lab.PracticePresets;

/** Component checks only: no fight rollout or claim about Hypixel's hidden implementation. */
public final class CombatSprintVerticalCheck {
    static int checks;
    static void check(boolean b,String why){checks++;if(!b)throw new AssertionError(why);}
    static void bits(double a,double b,String why){check(Double.doubleToLongBits(a)==Double.doubleToLongBits(b),why+" "+a+" / "+b);}
    static PeriodicComboFightModel.State seed(boolean sprint,int resistant,boolean velocityChanged){
        var s=new PeriodicComboFightModel.State(0,0,2.5,0);s.serverSprint=sprint;s.resistant=resistant;s.lastDamageB=4;s.velocityChangedB=velocityChanged;
        s.serverB=PeriodicComboFightModel.motion(s.serverB,.123,-.0784000015258789,.045);return s;
    }
    static PeriodicComboFightModel.Packet velocity(PeriodicComboFightModel.State s){return s.packets.stream().filter(p->p.kind==4).findFirst().orElse(null);}
    public static void main(String[] args){
        var nativeConfig=new PeriodicComboFightModel.Config();var explicit=new PeriodicComboFightModel.Config();Properties p=new Properties();p.setProperty("combat_sprint_vertical",".1");explicit.applyCombat(p);
        bits(nativeConfig.sprintVertical,.1,"default native lift");
        for(boolean sprint:new boolean[]{false,true}){
            var a=seed(sprint,0,false);var b=a.deepCopy();PeriodicComboFightModel.hit(a,nativeConfig,true,0);PeriodicComboFightModel.hit(b,explicit,true,0);
            var av=velocity(a);var bv=velocity(b);bits(av.vx,bv.vx,"default X parity");bits(av.vy,bv.vy,"default Y parity");bits(av.vz,bv.vz,"default Z parity");check(a.serverB.equals(b.serverB),"native server motion exact");
        }
        var mmc=new PeriodicComboFightModel.Config();
        mmc.applyCombat(PracticePresets.defaults("mmc-v2"));
        var repeated=seed(true,0,false);
        PeriodicComboFightModel.hit(repeated,mmc,true,0);
        bits(repeated.lastFreshB.x(),PeriodicComboFightModel.vel(.5274+.327),"opening MMC sprint hit");
        check(!repeated.serverSprint,"accepted sprint hit consumes the server flag");
        repeated.resistant=mmc.hitTicks;
        PeriodicComboFightModel.hit(repeated,mmc,true,450);
        bits(repeated.lastFreshB.x(),PeriodicComboFightModel.vel(.5274),"unreset second MMC hit");
        repeated.add(2,850,0,0,0,0,true);
        PeriodicComboFightModel.serverDrain(repeated,mmc,850);
        repeated.resistant=mmc.hitTicks;
        PeriodicComboFightModel.hit(repeated,mmc,true,900);
        bits(repeated.lastFreshB.x(),PeriodicComboFightModel.vel(.5274+.327),"sprint command re-arms the bonus");
        var measured=new PeriodicComboFightModel.Config();measured.applyCombat(PracticePresets.defaults("mmc-v3"));
        check(measured.hitTicks==10 && measured.deepCopy().recordedMmc,"recorded profile and fresh window retained");
        for(boolean running:new boolean[]{false,true}){
            var air=seed(running,0,false);air.serverBy=air.mmcB.floor()+1.1;air.mmcB=air.mmcB.cap(1.1);
            PeriodicComboFightModel.hit(air,measured,true,0);
            bits(air.lastFreshB.y(),PeriodicComboFightModel.vel(.361375),"full recorded airborne lift");
            bits(air.lastFreshB.x(),PeriodicComboFightModel.vel(.5275+(running?.422:0)),"recorded horizontal impulse");
            var old=seed(running,0,false);old.serverBy=old.mmcB.floor()+1.1;old.mmcB=old.mmcB.cap(1.1);
            PeriodicComboFightModel.hit(old,mmc,true,0);bits(old.lastFreshB.y(),0,"legacy high hit stays capped");
        }
        var ranged=new PeriodicComboFightModel.Config();ranged.applyCombat(PracticePresets.defaults("mmc"));
        for(double gap:new double[]{1,3,3.5,4,4.5})for(boolean running:new boolean[]{true,false}){
            var state=seed(running,0,false);state.serverBx=gap;
            PeriodicComboFightModel.hit(state,ranged,true,0);
            double strength=running?.9495*Math.max(.6,1-.35*Math.max(0,gap-3)):.5275;
            bits(state.lastFreshB.x(),PeriodicComboFightModel.vel(strength),"distance attenuation");
            var cross=seed(running,0,false);cross.serverBx=gap;cross.serverYawA=0;
            PeriodicComboFightModel.hit(cross,ranged,true,0);
            bits(cross.lastFreshB.x(),PeriodicComboFightModel.vel(strength*.5),"right-angle radial impulse");
            bits(cross.lastFreshB.z(),PeriodicComboFightModel.vel(strength*.5),"right-angle facing impulse");
            var reverse=seed(running,0,false);reverse.serverBx=gap;reverse.serverYawA=90;
            PeriodicComboFightModel.hit(reverse,ranged,true,0);
            bits(reverse.lastFreshB.x(),0,"crossed players cancel the two X components");
        }
        var h=new PeriodicComboFightModel.Config();h.applyCombat(PracticePresets.defaults("hypixel"));check(h.hitTicks==9&&h.kbScale==1&&h.verticalScale==1&&h.sprintVertical==.07,"full profile import");check(h.deepCopy().sprintVertical==.07,"copied boost");
        var standard=new PeriodicComboFightModel.Config();standard.hitTicks=9;
        for(boolean sprint:new boolean[]{false,true}){
            var a=seed(sprint,0,false);var b=a.deepCopy();PeriodicComboFightModel.hit(a,standard,true,0);PeriodicComboFightModel.hit(b,h,true,0);
            var av=velocity(a);var bv=velocity(b);bits(av.vx,bv.vx,"native horizontal X preserved");bits(av.vz,bv.vz,"native horizontal Z preserved");
            double base=Math.min((double).4f,-.0784000015258789*.5+(double).4f);double nativeY=sprint?base+.1:base;
            double expected=new CombatProfile(1,1,9,.07).freshVertical(nativeY,sprint);
            bits(bv.vy,PeriodicComboFightModel.vel(expected),"same native-adjustment arithmetic then quantization");check(a.serverB.equals(b.serverB),"restored server state unaffected by packet-only change");
            check(sprint?av.vy>bv.vy:av.vy==bv.vy,"only fresh sprint lift changes");
        }
        // Stronger damage during immunity is an upgrade, not a fresh lift intervention.
        for(boolean pending:new boolean[]{false,true}){
            var a=seed(true,18,pending);var b=a.deepCopy();PeriodicComboFightModel.hit(a,standard,true,0);PeriodicComboFightModel.hit(b,h,true,0);
            check(a.hits==0&&b.hits==0,"upgrade not fresh");check(a.serverB.equals(b.serverB),"upgrade server motion unchanged");
            var av=velocity(a);var bv=velocity(b);check((av==null)==(bv==null),"same upgrade packet presence");if(av!=null)bits(av.vy,bv.vy,"upgrade velocity keeps native sprint Y");
        }
        System.out.println("PASS CombatSprintVerticalCheck "+checks+" checks");
    }
}
