import java.nio.file.*;
import java.util.*;

/** Keep the registered event clocks, primed policies and queued packets while changing a short midtrade input. */
final class RecordedTradeBranches {
    public static void main(String[] args)throws Exception{
        var arm=Matches.roster(Path.of(args[0])).get(0);var foes=Matches.roster(Path.of(args[1]));
        TradeWords.exchange=Integer.parseInt(args[2]);TradeWords.auditForks=true;
        for(var env:Arena.envs(Arrays.stream(args,3,args.length).map(Path::of).toList(),"mmc-recorded",true)){
            env.initial.timing.mouseInput=true;
            for(var foe:foes)for(boolean seat:List.of(true,false))TradeWords.run(env,seat,arm,foe);
        }
    }
}
