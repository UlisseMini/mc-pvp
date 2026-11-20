import java.nio.file.Path;
import java.util.Arrays;

/** A continuation must reproduce the full simulation, both seeded policies and their next inputs. */
final class LeagueForkCheck {
    public static void main(String[] args) throws Exception {
        var actor=InputStudy.fighter("fixture",Path.of(args[0]));
        var clock=LeagueWorker.clock(args[1]);
        int checked=0;
        for(boolean seat:new boolean[]{true,false}){
            var env=new DuelEnv(clock,seat?31:109,seat?109:31);
            var game=env.new Game(seat,LeagueWorker.player(actor,31,109,env,33),
                                      LeagueWorker.player(actor,109,31,env,65));
            game.trace();
            while(game.clock.time()<650 && game.advance()){}
            var fork=env.new Game(game,game.a.copy(),game.b.copy());
            fork.trace(); int previous=game.hits.list.size();
            for(;;){
                if(!FullClockHash.digest(game.clock).equals(FullClockHash.digest(fork.clock)))throw new AssertionError("World divergence");
                for(int i=0;i<2;i++){
                    var n=((Arena.Neural)(i==0?game.a:game.b)).net;
                    var m=((Arena.Neural)(i==0?fork.a:fork.b)).net;
                    if(!Arrays.equals(n.features(),m.features())||!Arrays.equals(n.scores(),m.scores())
                       ||!Arrays.equals(n.league().memory(),m.league().memory())
                       ||n.league().randomState()!=m.league().randomState())throw new AssertionError("Input divergence");
                }
                checked++;
                boolean more=game.advance();
                if(fork.advance()!=more)throw new AssertionError("Different duration");
                if(!more)break;
            }
            if(game.reward()!=fork.reward()||!game.hits.list.subList(previous,game.hits.list.size()).equals(fork.hits.list.subList(fork.hits.list.size()-game.hits.list.size()+previous,fork.hits.list.size())))throw new AssertionError("Hit divergence");
        }
        System.out.println("Exact continuation agreed at "+checked+" boundaries");
    }
}
