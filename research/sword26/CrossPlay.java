package sword;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Paired whole-fight payoff matrix. All entrants play the same openings in both seats. */
public final class CrossPlay {
    record Player(String name, double[] weights) {
        Model.Controller controller() throws Exception {
            return weights == null ? Controls.get(name) : new Fighter(weights);
        }
    }
    static List<Player> load(Path list) throws Exception {
        var players = new ArrayList<Player>();
        for (String name : Files.readAllLines(list)) {
            players.add(new Player(name, name.equals("wtap") ? null : Fighter.read(Path.of(name))));
        }
        return players;
    }
    static String series(Player a, Player b, List<Model.Scene> scenes, int lag) throws Exception {
        int wins=0, losses=0, draws=0;
        double payoff=0;
        for (var scene : scenes) {
            for (int seat=0; seat<2; seat++) {
                var p=a.controller(); var q=b.controller();
                var game=seat==0 ? Model.duel(p,q,scene) : Model.duel(q,p,scene);
                double mine=seat==0?game.a():game.b(), theirs=seat==0?game.b():game.a();
                if (theirs==0) wins++;
                else if (mine==0) losses++;
                else draws++;
                payoff+=(seat==0?1:-1)*Train.fitness(game);
            }
        }
        return String.format(Locale.ROOT,"%s\t%s\t%d\t%d\t%d\t%d\t%.8f%n",a.name,b.name,lag*100,wins,losses,draws,payoff/(wins+losses+draws));
    }
    public static void main(String[] args) throws Exception {
        var actors=load(Path.of(args[0])); var field=load(Path.of(args[1]));
        var rng=new Random(Long.parseLong(args[2])); int pairs=Integer.parseInt(args[3]);
        try(var pool=Executors.newFixedThreadPool(Integer.parseInt(args[4]))) {
            var jobs=new ArrayList<Callable<String>>();
            for (int lag=0;lag<=1;lag++) {
                int latency=lag;
                var scenes=new ArrayList<Model.Scene>();
                for(int i=0;i<pairs;i++) scenes.add(new Model.Scene(rng.nextLong(),lag,lag));
                for(var a:actors) for(var b:field) jobs.add(()->series(a,b,scenes,latency));
            }
            System.out.println("actor\trival\trtt\twins\tlosses\tdraws\tpayoff");
            for(var task:pool.invokeAll(jobs)) System.out.print(task.get());
        }
    }
}
