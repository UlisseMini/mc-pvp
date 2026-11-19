import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import lab.Json;

/** The regulation and established-combo scoring protocols share only the measured hit physics. */
final class RecordedBoxing {
    public static void main(String[] args)throws Exception{
        boolean regulation=args[0].equals("regulation");
        var arms=Matches.roster(Path.of(args[1]));
        var opponents=Matches.roster(Path.of(args[2]));
        var workers=Executors.newFixedThreadPool(3);
        try{
            for(int i=3;i<args.length;i++){
                int limit=regulation?180000:20000;
                var t=DuelEnv.Tape.read(Path.of(args[i]),limit);
                for(int rtt:new int[]{0,80}){
                    var e=new DuelEnv(t,"mmc-recorded",rtt,rtt,false,29,true,10,6,0,limit);
                    e.initial.timing.mouseInput=true;
                    var jobs=new ArrayList<Callable<String>>();
                    for(var arm:arms)for(var opponent:opponents)for(boolean seat:new boolean[]{true,false})jobs.add(()->{
                        var result=new LinkedHashMap<>(regulation?BoxingRegulation.play(e,seat,arm,opponent):Matches.boxing(e,seat,arm,opponent));
                        result.put("fresh_hit_ticks",e.initial.config.hitTicks);
                        if(!e.initial.config.recordedMmc||e.initial.config.hitTicks!=10)throw new AssertionError("Wrong calibration");
                        return Json.encode(result);
                    });
                    for(var r:workers.invokeAll(jobs))System.out.println(r.get());
                }
            }
        }finally{workers.shutdown();}
    }
}
