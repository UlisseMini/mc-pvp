package sword;

import java.util.*;
import static sword.Fighter.*;

/** Offline control substitutions for a frozen candidate on development starts. */
public final class Diagnose {
    public static void main(String[] args)throws Exception{
        var weights=read(java.nio.file.Path.of(args[0]));
        System.out.println("variant,win,loss,draw,mean_margin,mean_wtap_mirror_margin,presses,damage_events");
        for(String variant:List.of("learned","baseline_aim","baseline_click","baseline_movement","baseline_aim_and_click","wtap")){
            var rng=new Random(1297714);int win=0,loss=0,draw=0,press=0,hits=0;double margin=0,base=0;
            for(int lag=0;lag<=1;lag++)for(int i=0;i<16;i++){
                var scene=new Model.Scene(rng.nextLong(),lag,lag);
                for(int side=0;side<2;side++){
                    var candidate=new Fighter(weights);var ctrl=Controls.get("wtap");var rival=Controls.get("wtap");
                    Model.Controller mixed=v->{
                        var k=candidate.act(v);var b=ctrl.act(v);
                        var output=switch(variant){
                            case "baseline_aim" -> new Keys(k.forward(),k.side(),k.sprint(),k.jump(),k.click(),0);
                            case "baseline_click" -> new Keys(k.forward(),k.side(),k.sprint(),k.jump(),b.click(),k.offset());
                            case "baseline_aim_and_click" -> new Keys(k.forward(),k.side(),k.sprint(),k.jump(),b.click(),0);
                            case "baseline_movement" -> new Keys(b.forward(),b.side(),b.sprint(),b.jump(),k.click(),k.offset());
                            case "wtap" -> b;
                            default -> k;
                        };candidate.previous=output;return output;
                    };
                    var s=side==0?Model.duel(mixed,rival,scene):Model.duel(rival,mixed,scene);
                    var mirror=Model.duel(Controls.get("wtap"),Controls.get("wtap"),scene);
                    double hp=side==0?s.a():s.b(),other=side==0?s.b():s.a();
                    margin+=hp-other;base+=(side==0?1:-1)*(mirror.a()-mirror.b());
                    if(hp==0)loss++;else if(other==0)win++;else draw++;
                    press+=side==0?s.clicksA():s.clicksB();hits+=side==0?s.hitsA():s.hitsB();
                }
            }
            System.out.printf(Locale.ROOT,"%s,%d,%d,%d,%.2f,%.2f,%.2f,%.2f%n",variant,win,loss,draw,margin/64,base/64,press/64.,hits/64.);
        }
    }
}
