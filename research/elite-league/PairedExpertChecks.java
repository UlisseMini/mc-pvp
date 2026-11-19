import java.nio.file.*;
import java.util.*;
import lab.DuelNetwork;

final class PairedExpertChecks{
    public static void main(String[] a)throws Exception{
        Path folder=Path.of(a[0]);int checked=0,changes=0;
        var changed=new HashSet<String>();
        for(Path p:Files.list(folder).filter(q->q.getFileName().toString().startsWith("shift-")).sorted().toList()){
            String definition=Files.readString(p).trim();var original=DuelNetwork.decode(definition);float[] w=original.weights();
            var fallback=w.clone();fallback[0]=0;var opener=new DuelNetwork(DuelNetwork.Family.PAIR,fallback);DuelNetwork fork=null;
            for(int tick=0;tick<120;tick++){
                if(tick==9)fork=original.copy();
                double own=tick<12?Double.POSITIVE_INFINITY:(tick-12)*50,enemy=tick<17?Double.POSITIVE_INFINITY:(tick-17)*50;
                double x=3-tick*.005,tx=6+Math.sin(tick/13.)*.35;
                int real=original.step(x,12,.1,tx,12+.19*Math.sin(tick),0,.03,.01,.02,tick%3==0,tick%2==0,own,enemy,80);
                int stay=opener.step(x,12,.1,tx,12+.19*Math.sin(tick),0,.03,.01,.02,tick%3==0,tick%2==0,own,enemy,80);
                if(tick>=9){
                    int copied=fork.step(x,12,.1,tx,12+.19*Math.sin(tick),0,.03,.01,.02,tick%3==0,tick%2==0,own,enemy,80);
                    if(copied!=real||fork.pair().switches()!=original.pair().switches())throw new AssertionError("Switching state lost on fork");
                    fork.applied(real);
                }
                int at=(w[3]==0?0:w[3]==1?12:17)+Math.round(w[4]),period=Math.round(w[5]);
                int expert=tick<at?0:period==0?1:1-(tick-at)/period%2;
                if(expert!=original.pair().selected()||expert==0&&real!=stay)throw new AssertionError("Wrong switch or stale expert");
                if(real!=stay){changes++;changed.add(p.getFileName().toString());}
                original.applied(real);opener.applied(real);checked++;
            }
        }
        if(changed.size()!=12)throw new AssertionError("Some switching opponents never changed their controls on the test stream: "+changed);
        System.out.println(checked+" scheduled expert heads checked, "+changes+" different actions across "+changed.size()+" switching opponents");
    }
}
