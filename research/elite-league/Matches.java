import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import lab.Json;

/** Round-robin matches, including the two seat orders on every independent host calendar. */
final class Matches {
    static List<Arena.Fighter> roster(Path path)throws Exception{
        var list=new ArrayList<Arena.Fighter>();
        for(String line:Files.readAllLines(path)){
            var v=line.split("\\s+");
            list.add(v.length==1?Arena.Fighter.named(v[0]):new Arena.Fighter(v[0],Arena.Model.decode(Files.readString(Path.of(v[1])).trim())));
        }
        return list;
    }
    static Map<String,Object> boxing(DuelEnv env,boolean first,Arena.Fighter arm){
        Arena.Neural shadow=(Arena.Neural)arm.create(env.rtt,0);
        shadow.shadow=true;
        var game=env.new Game(first,shadow,new DuelEnv.Player("wtap",0,env.rtt,2000));
        game.trace();
        var plain=env.start(first,"wtap",0);plain.trace();plain.finish();
        var forks=new TreeMap<Integer,Object>();int seen=0,chain=0;
        while(game.advance()){
            var hs=game.hits.list;
            for(;seen<hs.size();seen++){
                var h=hs.get(seen);double alive=Math.max(game.a.firstClick,game.b.firstClick);
                if(!Double.isFinite(alive)||h.at()<alive)continue;
                if(h.sideA()!=first){chain=0;continue;}
                if(h.upgrade())continue;
                chain++;
                if((chain!=3&&chain!=5&&chain!=10)||forks.containsKey(chain))continue;
                var base=env.new Game(game,game.a.copy(),game.b.copy());
                var candidate=env.new Game(game,game.a.copy(),game.b.copy());
                ((Arena.Neural)candidate.our()).shadow=false;
                base.trace();candidate.trace();base.finish();candidate.finish();
                var suffix=plain.hits.list.stream().filter(x->x.at()>game.clock.time()).toList();
                if(!base.hits.list.equals(suffix) || base.clock.time()!=plain.clock.time())throw new AssertionError("W-tap fork diverged");
                forks.put(chain,Map.of("landmark",h.at(),"fork_at",game.clock.time(),
                    "committed",hs.subList(seen+1,hs.size()).stream().map(x->List.of(x.at(),x.sideA(),x.upgrade())).toList(),
                    "wtap",base.result(),"rl",candidate.result()));
            }
        }
        if(!plain.hits.list.equals(game.hits.list))throw new AssertionError("Shadow changed opening inputs");
        return Map.of("mode","boxing","profile",env.profile,"side",first?"a":"b","opponent","wtap","clock",env.clockId,
            "rtt",env.rtt,"arm",arm.name(),"forks",forks);
    }
    public static void main(String[] args)throws Exception{
        String mode=args[0],profile=args[1];boolean sumo=mode.equals("sumo");
        var arms=roster(Path.of(args[2]));var opponents=roster(Path.of(args[3]));
        var files=Arrays.stream(args,4,args.length).map(Path::of).toList();
        var envs=Arena.envs(files,profile,sumo);
        var pool=Executors.newFixedThreadPool(3);
        try{
            for(var arm:arms){
                var jobs=new ArrayList<Callable<String>>();
                for(var env:envs)for(boolean first:new boolean[]{true,false}){
                    if(!sumo){jobs.add(()->Json.encode(boxing(env,first,arm)));continue;}
                    for(var opp:opponents)jobs.add(()->{
                        var s=new Arena.Scenario(env,opp,first);
                        Map<String,Object> row=new LinkedHashMap<>(Map.of("arm",arm.name(),"opponent",opp.name(),"profile",profile,
                                "clock",env.clockId,"rtt",env.rtt,"side",first?"a":"b"));
                        try{row.put("score",s.game(arm));}
                        catch(IllegalStateException error){
                            if(!BoxingModel.UNSUPPORTED_SEPARATION.equals(error.getMessage()))throw error;
                            row.put("unknown",error.getMessage());
                        }
                        return Json.encode(row);
                    });
                }
                for(var f:pool.invokeAll(jobs))System.out.println(f.get());
            }
        }finally{pool.shutdown();}
    }
}
