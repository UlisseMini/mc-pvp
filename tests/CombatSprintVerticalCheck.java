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
