package sword;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

import static sword.Fighter.*;

/** Read-only two-POV diagnostics on the same established model state, including queues. */
public final class ForkAudit {
    record Input(View view, Keys keys) {}
    static final class Recorder implements Model.Controller {
        final Model.Controller controller;
        final List<Input> live = new ArrayList<>();

        Recorder(Model.Controller controller) { this.controller = controller; }
        public void prime(View view, Keys keys) { controller.prime(view, keys); }
        public Keys act(View view) {
            Keys keys = controller.act(view);
            live.add(new Input(view, keys));
            return keys;
        }
    }

    static double reachDistance(View view) {
        double dx=Math.max(0,Math.abs(view.tx()-view.x())-.3f);
        double dz=Math.max(0,Math.abs(view.tz()-view.z())-.3f);
        double eye=view.y()+1.62f;
        double dy=Math.max(0,Math.max(view.ty()-eye,eye-view.ty()-1.8f));
        return Math.sqrt(dx*dx+dy*dy+dz*dz);
    }

    static String suffix(Model.Start start,int seat,int tick,String policy,String rival) throws Exception {
        var own=new Recorder(Controls.get(policy));
        var other=new Recorder(Controls.get(rival));
        var tape=new Maintenance.Tape();
        Model.duel(seat==0?own:other,seat==0?other:own,start,tape);
        if(own.live.isEmpty())return ",,,,,,,,,,,,"+tape.suffix(seat,tick,false);
        var first=own.live.getFirst();
        var defender=other.live.getFirst().view();
        int nextWindow=9; // live[0] is the poll one tick after the third fresh hit.
        String window=",,";
        if(own.live.size()>nextWindow){
            var v=own.live.get(nextWindow).view();
            window=String.format(Locale.ROOT,"%.6f,%.6f,%.6f",reachDistance(v),
                reachDistance(other.live.get(nextWindow).view()),v.charge());
        }
        return String.format(Locale.ROOT,"%.6f,%.6f,%.6f,%.6f,%b,%d,%d,%b,%b,%s,%s",
            reachDistance(first.view()),reachDistance(defender),first.view().charge(),defender.charge(),
            first.view().sprint(),first.keys().forward(),first.keys().side(),first.keys().jump(),first.keys().click(),
            window,tape.suffix(seat,tick,false));
    }

    public static void main(String[] args) throws Exception {
        if(!"lockstep".equals(System.getProperty("sword.transport")))throw new IllegalArgumentException("Use the native-validated flat kernel");
        var rng=new Random(Long.parseLong(args[1]));
        String rival=args[3];
        var header="own_distance,opponent_distance,charge,opponent_charge,sprinting,forward,side,jump,click,window_own_distance,window_opponent_distance,window_charge,cycles,stop,exposure".split(",");
        var columns=new ArrayList<>(List.of("opening","delay","seat","landmark_tick"));
        for(String arm:List.of("learned","wtap"))for(String field:header)columns.add(arm+"_"+field);
        System.out.println(String.join(",",columns));
        for(int lag=0;lag<=1;lag++)for(int opening=0;opening<Integer.parseInt(args[2]);opening++){
            var scene=new Model.Scene(rng.nextLong(),lag,lag);
            for(int seat=0;seat<2;seat++){
                var reference=new Maintenance.Tape();var baseline=Controls.get("wtap");var peer=Controls.get(rival);
                Model.duel(seat==0?baseline:peer,seat==0?peer:baseline,new Model.Start(scene,List.of()),reference);
                int tick=reference.landmark(seat,3);
                if(tick<0)continue;
                var start=new Model.Start(scene,reference.frames.subList(0,tick+1));
                System.out.println(opening+","+lag+","+seat+","+tick+","+suffix(start,seat,tick,args[0],rival)+","+suffix(start,seat,tick,"wtap",rival));
            }
        }
    }
}
