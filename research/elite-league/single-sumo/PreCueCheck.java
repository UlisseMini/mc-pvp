import java.nio.file.*;
import java.util.*;
import lab.*;

/** Paired future styles must be unknowable until the opponent's first possible change. */
final class PreCueCheck {
    public static void main(String[] args)throws Exception{
        LeagueWorker.load();int games=0,heads=0;
        try(var in=Files.newBufferedReader(Path.of(args[0]));var out=Files.newBufferedWriter(Path.of(args[1]))){
            String first;
            while((first=in.readLine())!=null){
                var x=MemoryAssay.start(MemoryAssay.trial(first.split("\t")));
                var y=MemoryAssay.start(MemoryAssay.trial(in.readLine().split("\t")));
                int checked=0;
                while(true){
                    var rival=(Arena.Neural)(x.g().first?x.g().b:x.g().a);
                    if(rival.net.pair().switches()>0)break;
                    var a=x.actor().net;var b=y.actor().net;
                    if(!Arrays.equals(a.league().memory(),b.league().memory()) || a.league().randomState()!=b.league().randomState()
                      ||!Arrays.equals(a.features(),b.features()) || !Arrays.equals(a.scores(),b.scores()))
                        throw new AssertionError("Pre-cue assigned style leaked into an input or hidden state");
                    if(checked==2 && games<2 && !FullClockHash.digest(x.g().clock).equals(FullClockHash.digest(y.g().clock)))
                        throw new AssertionError("Unmatched pre-cue physical state");
                    checked++;
                    boolean more=x.g().advance();if(more!=y.g().advance())throw new AssertionError("Different pre-cue clock");
                    if(!more)break;
                }
                if(checked<2)throw new AssertionError("Empty pre-cue prefix");heads+=x.actor().heads;games++;
            }
            out.write(Json.encode(Map.of("paired_games",games,"pre_cue_input_heads_including_trigger",heads,
                "differing_pre_cue_hidden_states",0,"differing_pre_cue_actions",0)));out.newLine();
        }
    }
}
