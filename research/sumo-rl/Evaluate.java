import java.nio.file.*;
import java.util.*;
import lab.Json;

/** Score complete rounds and branch the first established W-tap state at each landmark. */
public final class Evaluate {
    static Map<String,Object> episode(DuelEnv env, boolean first,String opponent,int mask) {
        var plain=env.start(first,opponent,0);
        var learned=env.start(first,opponent,mask);
        plain.trace();learned.trace();
        var forks=new TreeMap<Integer,Object>();
        int streak=0,used=0;
        while(plain.advance()){
            var hs=plain.hits.list;
            double wake=Math.max(plain.a.firstClick,plain.b.firstClick);
            for(;used<hs.size();used++){
                var h=hs.get(used);
                if(!Double.isFinite(wake)||h.at()<wake)continue;
                if(h.sideA()!=first){streak=0;continue;}
                if(h.upgrade())continue;
                streak++;
                if((streak!=3&&streak!=5&&streak!=10)||forks.containsKey(streak))continue;
                var a=env.new Game(plain,0);var b=env.new Game(plain,mask);
                a.trace();b.trace();a.finish();b.finish();
                forks.put(streak, Map.of("landmark",h.at(),"fork_at",plain.clock.time(),
                    "committed",hs.subList(used+1,hs.size()).stream().map(x->List.of(x.at(),x.sideA(),x.upgrade())).toList(),
                    "wtap",a.result(),"rl",b.result()));
            }
        }
        learned.finish();
        // Positive control: every unchanged suffix must reproduce the scored fight to termination.
        for(Object obj:forks.values()){
            var f=(Map<?,?>)obj;var base=(Map<?,?>)f.get("wtap");double cut=(double)f.get("fork_at");
            List<?> suffix=plain.hits.list.stream().filter(h->h.at()>cut).map(h->List.of(h.at(),h.sideA(),h.upgrade())).toList();
            if(!base.get("hits").equals(suffix) || !base.get("winner").equals(plain.winner()) ||
                    !base.get("end").equals(plain.clock.time()))throw new AssertionError("Control fork diverged");
        }
        return Map.of("clock",env.clockId,"profile",env.profile,"mode",env.sumo?"sumo":"boxing","rtt",env.rtt,
            "side",first?"a":"b","opponent",opponent,"rl",learned.result(),"wtap",plain.result(),"forks",forks);
    }
    public static void main(String[] args)throws Exception{
        int mask=Integer.parseInt(args[1]);
        for(var path:Files.list(Path.of(args[0])).sorted().toList()){
            var tape=DuelEnv.Tape.read(path,DuelEnv.LIMIT);
            for(String mode:List.of("sumo","boxing"))for(String profile:(mode.equals("sumo")?List.of("mmc","hypixel"):List.of("mmc"))){
                for(int rtt:new int[]{0,80}){
                    var env=new DuelEnv(tape,profile,rtt,mode.equals("sumo"));
                    for(String opp:(mode.equals("sumo")&&profile.equals("mmc")?List.of("wtap","hold","trade"):List.of("wtap")))
                        for(boolean first:new boolean[]{true,false}){
                            try{System.out.println(Json.encode(episode(env,first,opp,mask)));}
                            catch(IllegalStateException e){
                                if(!BoxingModel.UNSUPPORTED_SEPARATION.equals(e.getMessage()))throw e;
                                System.out.println(Json.encode(Map.of("clock",tape.id(),"mode",mode,"profile",profile,"rtt",rtt,
                                        "side",first?"a":"b","opponent",opp,"unknown",e.getMessage())));
                            }
                        }
                }
            }
        }
    }
}
