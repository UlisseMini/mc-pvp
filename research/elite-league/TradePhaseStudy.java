import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import lab.Json;

/** Hold the observed trade fixed. Vary only future host opportunities after the decision. */
final class TradePhaseStudy {
    static DuelEnv.Game copy(DuelEnv env,DuelEnv.Game g){
        return env.new Game(g,g.a.copy(),g.b.copy());
    }

    static Map<String,Object> phase(DuelEnv env,DuelEnv.Game prefix,int delta) throws Exception {
        var start=copy(env,prefix);
        if(delta!=0)start.clock.shiftFutureClientOpportunities(delta<0?start.first:!start.first,Math.abs(delta));
        String forkHash=TradeWords.stateHash(start),endHash=null;
        var scores=new LinkedHashMap<String,Double>();
        for(int choice=0;choice<TradeWords.WORDS.length;choice++){
            var branch=copy(env,start);
            if(choice==0 && !TradeWords.stateHash(branch).equals(forkHash))
                throw new AssertionError("Changed initial fork");
            if(choice!=0){
                var probe=(TradeWords.Probe)branch.our();
                probe.choice=choice;probe.remaining=3;
            }
            branch.finish();
            scores.put(TradeWords.WORDS[choice],branch.reward());
            if(choice==0)endHash=TradeWords.stateHash(branch);
        }
        start.finish();
        if(!TradeWords.stateHash(start).equals(endHash))throw new AssertionError("No-op fork changed terminal state");
        return Map.of("scores",scores,"fork_sha256",forkHash,"unchanged_terminal_sha256",endHash);
    }

    static void play(DuelEnv env,boolean first,Arena.Fighter f,Arena.Fighter opponent)throws Exception{
        var p=new TradeWords.Probe(f.model(),env.rtt);
        var g=env.new Game(first,p,opponent.create(env.rtt,0));
        while(g.step<env.serverEnds.length && g.winner()<0 && !p.marked)
            if(!g.clock.runHeadInterval(first,env.serverEnds[g.step]))g.step++;
        Map<String,Object> row=new LinkedHashMap<>();
        row.put("prefix",f.name());row.put("opponent",opponent.name());row.put("clock",env.clockId);
        row.put("side",first?"a":"b");row.put("rtt",env.rtt);
        if(p.marked){
            row.put("at",g.clock.time());row.put("x",p.x);
            String prefixHash=TradeWords.stateHash(g);row.put("fork_sha256",prefixHash);
            var phases=new LinkedHashMap<Integer,Object>();
            for(int delta:new int[]{0,5,-5,12,-12})phases.put(delta,phase(env,g,delta));
            row.put("phases",phases);
            if(!TradeWords.stateHash(g).equals(prefixHash))throw new AssertionError("Branch mutated observed prefix");
        }
        g.finish();row.put("base",g.reward());
        System.out.println(Json.encode(row));
    }

    public static void main(String[] args)throws Exception{
        var arm=Matches.roster(Path.of(args[0]));
        var foes=Matches.roster(Path.of(args[1]));
        TradeWords.exchange=Integer.parseInt(args[2]);
        for(var env:Arena.envs(Arrays.stream(args,3,args.length).map(Path::of).toList(),"mmc",true)){
            env.initial.timing.mouseInput=true;
            for(var f:arm)for(var opp:foes)for(boolean seat:new boolean[]{true,false})play(env,seat,f,opp);
        }
    }
}
