import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import lab.RecordedCollisions;

/** Construct a legal match opening on a recorded platform without revealing its identity to either controller. */
final class RecordedMapClock {
    private static final Map<String,RecordedCollisions> terrains=new HashMap<>();
    static DuelEnv open(Path clock,String name)throws IOException{
        if(!terrains.containsKey(name))terrains.put(name,new RecordedCollisions(name,4096,11));
        var terrain=terrains.get(name);var m=terrain.map;
        var env=new DuelEnv(DuelEnv.Tape.read(clock,DuelEnv.LIMIT),"mmc-range",0,0,true,29,true,
                Math.max(m.width,m.depth)-2,m.gap,0,DuelEnv.LIMIT,new double[]{m.ax+.5,m.az+.5,m.bx+.5,m.bz+.5,m.yaw(true),m.yaw(false)},terrain);
        env.initial.timing.mouseInput=true;return env;
    }
}
