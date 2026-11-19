import java.nio.file.*;
import java.util.*;

final class ForkChecks{
    public static void main(String[] args)throws Exception{
        int n=0;
        var tape=DuelEnv.Tape.read(Path.of(args[0]),3500);
        for(int rtt:new int[]{0,80})for(String name:List.of("zest-strafe","kb-displace-pull","kb-displace-edge","wtap-tick-window","phase-spacing","select","counterstrafe","hold")){
            var e=new DuelEnv(tape,"mmc",rtt,true);
            var p=RobustField.load("net",Path.of(args[1])).create(rtt,0);
            var g=e.new Game(true,p,Arena.Fighter.named(name).create(rtt,0));g.clock.runUntil(1270);
            var c=e.new Game(g,g.a.copy(),g.b.copy());g.trace();c.trace();
            for(double t:new double[]{1270,1523,1999,2490,3450}){
                g.clock.runUntil(t);c.clock.runUntil(t);
                if(!FullClockHash.digest(g.clock).equals(FullClockHash.digest(c.clock))||!g.hits.list.equals(c.hits.list))
                    throw new AssertionError(name+" diverged at "+t);
                var a=(Arena.Neural)g.a;var b=(Arena.Neural)c.a;
                if(!Arrays.equals(a.net.features(),b.net.features())||!Arrays.equals(a.net.scores(),b.net.scores()))
                    throw new AssertionError("Policy state divergence");
                n++;
            }
        }
        System.out.println(n+" unchanged-fork comparisons with a neural policy and eight opponent styles passed");
    }
}
