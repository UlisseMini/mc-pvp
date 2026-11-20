package sword;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import static sword.Fighter.*;

/** Post-training diagnostic. Fork the first ready, aimed opening swing that a fighter passes up. */
public final class OpeningPressAudit {
    static boolean reachable(View v, Keys keys) {
        double yaw=FlatPhysics.angle((float)steer(v,keys));
        double pitch=FlatPhysics.angle(FastDuel.pitch(v));
        return Model.rayBox(v.tx()-v.x(), v.ty()-v.y()-1.62f, v.tz()-v.z(),
            FlatPhysics.sin(-yaw)*FlatPhysics.cos(pitch), -FlatPhysics.sin(pitch),
            FlatPhysics.cos(-yaw)*FlatPhysics.cos(pitch));
    }
    static final class Opening implements Model.Controller {
        final Model.Controller base;
        int tick, skipped=-1;
        boolean pressed;
        Opening(Model.Controller base){this.base=base;}
        public Keys act(View v){
            Keys k=base.act(v);
            if(skipped<0 && !pressed && v.health()==20 && v.charge()>=.9 && !k.click() && reachable(v,k))skipped=tick;
            pressed|=k.click();tick++;
            return k;
        }
    }
    static final class EarlierPress implements Model.Controller {
        final Fighter policy;
        boolean fork=true;
        EarlierPress(double[] weights){policy=new Fighter(weights);}
        public void prime(View v, Keys actual){policy.prime(v,actual);}
        public Keys act(View v){
            Keys k=policy.act(v);
            if(!fork)return k;
            fork=false;
            if(k.click() || !reachable(v,k) || v.charge()<.9)throw new IllegalStateException("Unmatched opening fork");
            return policy.previous=new Keys(k.forward(),k.side(),k.sprint(),k.jump(),true,k.offset());
        }
    }
    static final class Trace implements Model.Observer {
        final List<Model.Frame> frames=new ArrayList<>();
        int first=-1,firstSeat=-1;
        public void step(int t,Model.Body a,Model.Body b,Keys ka,Keys kb,boolean aFirst){
            frames.add(new Model.Frame(ka,kb));
            if(first<0 && a.hits+b.hits>0){
                first=t;firstSeat=a.hits>0&&(b.hits==0||aFirst)?0:1;
            }
        }
    }
    static Model.Score bout(Model.Controller actor, String rival,int seat, Model.Start start,Trace trace)throws Exception {
        Model.Controller other=Controls.get(rival);
        return seat==0?Model.duel(actor,other,start,trace):Model.duel(other,actor,start,trace);
    }
    static double points(Model.Score score,int seat) {
        double a=score.b()==0?1:score.a()==0?0:.5;
        return seat==0?a:1-a;
    }
    public static void main(String[] args)throws Exception{
        double[] weights=read(Path.of(args[0]));String rival=args[1];
        var rng=new Random(Long.parseLong(args[2]));int count=Integer.parseInt(args[3]);
        System.out.println("scene,rtt,seat,bot_fork_tick,wtap_skipped_tick,bot_first,wtap_first,bot_score,wtap_score,earlier_score,wtap_same_state_score");
        for(int lag=0;lag<=1;lag++)for(int i=0;i<count;i++){
            var scene=new Model.Scene(rng.nextLong(),lag,lag);
            var initial=new Model.Start(scene,List.of());
            for(int seat=0;seat<2;seat++){
                var learner=new Opening(new Fighter(weights));var baseline=new Opening(Controls.get("wtap"));
                var t=new Trace();var w=new Trace();
                var factual=bout(learner,rival,seat,initial,t);var control=bout(baseline,rival,seat,initial,w);
                int fork=learner.skipped;
                if(t.first>=0 && fork>t.first)fork=-1;
                int wtapFork=baseline.skipped;
                if(w.first>=0 && wtapFork>w.first)wtapFork=-1;
                String suffix=",,";
                if(fork>=0){
                    var start=new Model.Start(scene,t.frames.subList(0,fork));
                    if(!bout(new Fighter(weights),rival,seat,start,null).equals(factual))throw new IllegalStateException("Factual replay diverged");
                    var earlier=bout(new EarlierPress(weights),rival,seat,start,null);
                    var switched=bout(Controls.get("wtap"),rival,seat,start,null);
                    suffix=","+points(earlier,seat)+","+points(switched,seat);
                }
                System.out.println(scene.seed()+","+lag*100+","+seat+","+fork+","+wtapFork+","+(t.firstSeat==seat)+","+
                    (w.firstSeat==seat)+","+points(factual,seat)+","+points(control,seat)+suffix);
            }
        }
    }
}
