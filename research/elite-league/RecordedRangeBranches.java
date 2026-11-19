import java.nio.file.Path;
import java.util.*;
/** Test three physical input heads at the second exchange on the measured floor and revised impulse law. */
final class RecordedRangeBranches {
    public static void main(String[] args) throws Exception {
        var arm=Matches.roster(Path.of(args[0])).get(0);
        var foes=Matches.roster(Path.of(args[1]));
        TradeWords.exchange=2;
        TradeWords.auditForks=true;
        for(int i=2;i<args.length;i++){
            var file=Path.of(args[i]);
            var donor=DuelEnv.Tape.read(file,DuelEnv.LIMIT);
            for(int phase=0;phase<3;phase++)for(int rtt:new int[]{0,80}){
                var tape=BoxingDrill.shift(donor,phase*9,phase*13);
                var env=new DuelEnv(tape,"mmc-range",rtt,rtt,true,29,true,DuelEnv.regulationDiameter(),DuelEnv.regulationGap(),0);
                env.initial.timing.mouseInput=true;
                for(var foe:foes)for(boolean seat:List.of(true,false))TradeWords.run(env,seat,arm,foe);
            }
        }
    }
}
