import java.nio.file.*;
import lab.SumoJumpPolicy;

public final class SimChecks {
    public static void main(String[] args)throws Exception{
        for(int mask:new int[]{0,32,8288,32816,65535})for(int i=0;i<16;i++){
            if(SumoJumpPolicy.jump(mask,i)!=((mask&(1<<i))!=0))throw new AssertionError("State mask lookup");
            var gap=new double[]{2,3,4,5}[i%4];
            int state=SumoJumpPolicy.state(true,true,gap,0,i>=8?.3:0,(i&4)!=0?-.12:.1,0);
            if(state!=i || SumoJumpPolicy.state(false,true,gap,0,0,0,0)!=-1 ||
                    SumoJumpPolicy.state(true,false,gap,0,0,0,0)!=-1)throw new AssertionError("Own-view gate");
        }
        int checked=0;
        for(var path:Files.list(Path.of(args[0])).sorted().toList()){
            var tape=DuelEnv.Tape.read(path,2800);
            for(int rtt:new int[]{0,80}){
                var env=new DuelEnv(tape,"mmc",rtt,true);
                var base=env.start(true,"wtap",8288);
                base.clock.runUntil(820);
                var clone=env.new Game(base,8288);base.trace();clone.trace();
                for(double t:new double[]{820,1097,1532,2100,2750}){
                    base.clock.runUntil(t);clone.clock.runUntil(t);
                    if(!FullClockHash.digest(base.clock).equals(FullClockHash.digest(clone.clock)) ||
                       !FullClockHash.digest(base.a).equals(FullClockHash.digest(clone.a)) ||
                       !FullClockHash.digest(base.b).equals(FullClockHash.digest(clone.b)) ||
                       !base.hits.list.equals(clone.hits.list))throw new AssertionError("No-op resumption diverged at "+t);
                    checked++;
                }
            }
        }
        System.out.println(checked+" full-state no-op comparisons passed");
    }
}
