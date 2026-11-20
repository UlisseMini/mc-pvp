package sword;

import java.nio.file.*;
import java.util.*;

public final class Maintenance {
    record Event(int t,int seat,boolean fresh){}
    static final class Tape implements Model.Observer{
        int hitsA,hitsB,firstPressA=99999,firstPressB=99999;
        final List<Event> hits=new ArrayList<>();final List<Model.Frame> frames=new ArrayList<>();
        public void step(int t,Model.Body a,Model.Body b,Fighter.Keys ka,Fighter.Keys kb,boolean af){
            Event ea=a.hits>hitsA?new Event(t,0,a.lastFresh==t):null,eb=b.hits>hitsB?new Event(t,1,b.lastFresh==t):null;
            if(ka.click())firstPressA=Math.min(firstPressA,t);if(kb.click())firstPressB=Math.min(firstPressB,t);
            if(af&&ea!=null)hits.add(ea);if(eb!=null)hits.add(eb);if(!af&&ea!=null)hits.add(ea);
            frames.add(new Model.Frame(ka,kb));hitsA=a.hits;hitsB=b.hits;
        }
        int landmark(int seat,int k){
            int count=0;
            for(var e:hits){
                if(e.t<(seat==0?firstPressB:firstPressA))continue;
                if(e.seat!=seat){count=0;continue;}if(e.fresh&&++count==k){
                    // Fork at the next input opportunity. Any reply on this same tick already happened.
                    boolean returned=hits.stream().anyMatch(x->x.t==e.t&&x.seat!=seat&&hits.indexOf(x)>hits.indexOf(e));
                    if(!returned)return e.t;
                }
            }return -1;
        }
        String suffix(int seat,int start,boolean active){
            int cycles=0,last=start,stop=frames.size();String reason="round_end";
            for(var h:hits)if(h.t>start){
                if(active&&h.t-last>22){stop=last+23;reason="inactive";break;}
                if(h.seat!=seat){stop=h.t;reason="return";break;}
                if(h.fresh){cycles++;last=h.t;}
            }
            if(reason.equals("round_end")&&active&&stop-last>22){reason="inactive";stop=last+23;}
            return cycles+","+reason+","+(stop-start);
        }
    }
    public static void main(String[] args)throws Exception{
        String rival=args.length>3?args[3]:"wtap";
        var weights=Fighter.read(Path.of(args[0]));var rng=new Random(Long.parseLong(args[1]));
        System.out.println("scene,delay,seat,landmark,tick,activity,learned_cycles,learned_end,learned_ticks,wtap_cycles,wtap_end,wtap_ticks");
        for(int lag=0;lag<2;lag++)for(int i=0;i<Integer.parseInt(args[2]);i++){
            var scene=new Model.Scene(rng.nextLong(),lag,lag);
            for(int seat=0;seat<2;seat++){
              var reference=new Tape();var baseline=Controls.get("wtap");var other=Controls.get(rival);
              Model.duel(seat==0?baseline:other,seat==0?other:baseline,new Model.Start(scene,List.of()),reference);
              for(int k:new int[]{3,5,10}){
                int tick=reference.landmark(seat,k);if(tick<0){
                    System.out.printf("%d,%d,%d,%d,-1,both,,no_entry,,,no_entry,%n",i,lag,seat,k);continue;
                }
                var start=new Model.Start(scene,reference.frames.subList(0,tick+1));var learned=new Tape();
                var bot=new Fighter(weights);var w=Controls.get(rival);
                Model.duel(seat==0?bot:w,seat==0?w:bot,start,learned);
                for(boolean active:new boolean[]{false,true})
                    System.out.printf("%d,%d,%d,%d,%d,%b,%s,%s%n",i,lag,seat,k,tick,active,learned.suffix(seat,tick,active),reference.suffix(seat,tick,active));
              }
            }
        }
    }
}
