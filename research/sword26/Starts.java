package sword;

import java.util.*;

/** Sample an ordinary time in a training game. Replaying keys regenerates the queues,
 * observations, clock and both bodies. The incoming controllers also see the whole prefix. */
final class Starts {
    static Model.Start sample(Model.Scene scene,double[] a,double[] b,Random rng){
        var history=new ArrayList<Model.Frame>();
        Model.duel(new Fighter(a),new Fighter(b),new Model.Start(scene,List.of()),
            (t,left,right,ka,kb,order)->history.add(new Model.Frame(ka,kb)));
        return new Model.Start(scene,List.copyOf(history.subList(0,rng.nextInt(history.size()))));
    }
    static double play(double[] a,double[] b,Model.Start start,boolean swap){
        var p=new Fighter(a);var q=new Fighter(b);
        return swap?-Train.fitness(Model.duel(q,p,start,null)):Train.fitness(Model.duel(p,q,start,null));
    }
}
