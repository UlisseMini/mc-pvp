package sword;

import java.util.*;
public final class Grade {
    public static void main(String[] args)throws Exception{
        var rng=new Random(Long.parseLong(args[1]));int pairs=Integer.parseInt(args[2]);
        System.out.println("scene,oneWayTicks,seat,candidateHP,opponentHP,ticks,hitsFor,hitsAgainst,presses,jumps,criticalHits,wtapHP,wtapOpponentHP,wtapHits,wtapHitsAgainst,wtapPresses");
        for(int lag=0;lag<=1;lag++)for(int i=0;i<pairs;i++){
            var scene=new Model.Scene(rng.nextLong(),lag,lag);
            for(int seat=0;seat<2;seat++){
                var policy=Controls.get(args[0]);var w=Controls.get("wtap");
                var result=seat==0?Model.duel(policy,w,scene):Model.duel(w,policy,scene);
                var base=Model.duel(Controls.get("wtap"),Controls.get("wtap"),scene);
                boolean a=seat==0;
                System.out.printf(Locale.ROOT,"%d,%d,%d,%.4f,%.4f,%d,%d,%d,%d,%d,%d,%.4f,%.4f,%d,%d,%d%n",i,lag,seat,
                    a?result.a():result.b(),a?result.b():result.a(),result.ticks(),a?result.hitsA():result.hitsB(),a?result.hitsB():result.hitsA(),
                    a?result.clicksA():result.clicksB(),a?result.jumpsA():result.jumpsB(),a?result.airHitsA():result.airHitsB(),
                    a?base.a():base.b(),a?base.b():base.a(),a?base.hitsA():base.hitsB(),a?base.hitsB():base.hitsA(),a?base.clicksA():base.clicksB());
            }
        }
    }
}
