import java.io.*;
import lab.PracticePresets;
import lab.ExactTravel;

/** Inject real pre-attack states into the simulator's ordinary hit path and compare exact outcomes. */
final class RecordedImpulseParity {
    static double d(String[] f,int i){return Double.parseDouble(f[i]);}
    static boolean flag(String[] f,int i){return Integer.parseInt(f[i])!=0;}
    static ExactTravel.State who(String[] f,int i){
        return PeriodicComboFightModel.state(d(f,i),d(f,i+1),d(f,i+2),d(f,i+3),d(f,i+4),d(f,i+5),flag(f,i+6));
    }
    static void eq(double actual,double expected,String context){
        if(actual!=expected)throw new AssertionError(context+" "+actual+" vs "+expected);
    }
    public static void main(String[] args)throws Exception{
        var conf=new PeriodicComboFightModel.Config();conf.applyCombat(PracticePresets.defaults(args.length==0?"mmc-v3":args[0]));conf.sumo=true;
        int tested=0,charged=0,air=0;
        var input=new BufferedReader(new InputStreamReader(System.in));
        for(String line;(line=input.readLine())!=null;){
            var f=line.split(" ");String label=f[0];boolean first=flag(f,1);
            var own=who(f,2);var target=who(f,9);
            var a=first?own:target;var b=first?target:own;
            var s=new PeriodicComboFightModel.State(a.x(),a.z(),b.x(),b.z());
            s.serverA=a;s.serverB=b;s.serverAx=a.x();s.serverAy=a.y();s.serverAz=a.z();s.serverBx=b.x();s.serverBy=b.y();s.serverBz=b.z();
            boolean sprint=flag(f,17);int immunity=(int)d(f,18);
            if(first){s.serverYawA=d(f,16);s.serverSprint=sprint;s.resistant=immunity;}
            else{s.serverYawB=d(f,16);s.serverSprintB=sprint;s.ownResistant=immunity;}
            PeriodicComboFightModel.hit(s,conf,first,0);
            var pulse=first?s.lastFreshB:s.lastFreshA;
            if(pulse==null)throw new AssertionError("No fresh impulse at "+label);
            eq(pulse.x(),d(f,19),label+" S12 X");eq(pulse.y(),d(f,20),label+" S12 Y");eq(pulse.z(),d(f,21),label+" S12 Z");
            var after=first?s.serverA:s.serverB;
            eq(after.vx(),d(f,22),label+" attacker X");eq(after.vy(),d(f,23),label+" attacker Y");eq(after.vz(),d(f,24),label+" attacker Z");
            if((first?s.serverSprint:s.serverSprintB)!=flag(f,25))throw new AssertionError(label+" sprint flag");
            var restored=first?s.serverB:s.serverA;
            eq(restored.vx(),d(f,26),label+" restored victim X");eq(restored.vy(),d(f,27),label+" restored victim Y");eq(restored.vz(),d(f,28),label+" restored victim Z");
            tested++;if(sprint)charged++;if(!target.onGround())air++;
        }
        System.out.println(tested+" "+charged+" "+air);
    }
}
