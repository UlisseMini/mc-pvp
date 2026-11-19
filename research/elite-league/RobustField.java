import java.nio.file.*;
import java.util.*;

/** Training-only domain variation. Every member of a generation plays the same drawn conditions. */
final class RobustField{
    record Variant(DuelEnv env,boolean regulation){}
    final List<Variant> standard=new ArrayList<>(),shifted=new ArrayList<>();
    final List<List<Arena.Fighter>> groups=new ArrayList<>();
    RobustField(List<Path> tapes,Path frozen,Path responses)throws Exception{
        this(tapes,frozen,responses,"mmc");
    }
    RobustField(List<Path> tapes,Path frozen,Path responses,String profile)throws Exception{
        for(int i=0;i<tapes.size();i++){
            var tape=DuelEnv.Tape.read(tapes.get(i),DuelEnv.LIMIT);
            for(int ping:new int[]{0,80})standard.add(new Variant(new DuelEnv(tape,profile,ping,true),true));
            int[][] pings={{0,65},{75,15},{40,110},{105,30}};
            var p=pings[i%pings.length];
            int[] diameters={8,10,12,14};double[] gaps={4.5,5.5,6.5,7.0};
            shifted.add(new Variant(new DuelEnv(tape,profile,p[0],p[1],true,24+i%11,true,
                diameters[i%4],gaps[(i/4)%4],new double[]{-.75,0,.85,.4}[(i+i/4)%4]),false));
        }
        groups.add(List.of(Evolve.WT,Evolve.HOLD,Arena.Fighter.named("pressure"),Arena.Fighter.named("trade")));
        groups.add(List.of(Arena.Fighter.named("reset"),Arena.Fighter.named("select"),Arena.Fighter.named("counterstrafe")));
        var family=new ArrayList<Arena.Fighter>();
        for(String n:List.of("linear","mlp","recurrent","hybrid"))family.add(load(n,frozen.resolve(n+".net")));
        groups.add(family);
        groups.add(List.of(load("anti-hold",responses.resolve("anti-hold.net")),load("anti-hybrid",responses.resolve("anti-hybrid.net"))));
    }
    static Arena.Fighter load(String name,Path p)throws Exception{
        return new Arena.Fighter(name,Arena.Model.decode(Files.readString(p).trim()));
    }
    List<Arena.Scenario> draw(Random rng,int pairs,List<Arena.Fighter> history){
        var sets=new ArrayList<>(groups);
        if(!history.isEmpty())sets.add(history);
        var scenarios=new ArrayList<Arena.Scenario>();
        for(int i=0;i<pairs;i++){
            var choices=sets.get(i%sets.size());
            var opp=choices.get(rng.nextInt(choices.size()));
            var domains=rng.nextDouble()<.28?shifted:standard;var env=domains.get(rng.nextInt(domains.size())).env;
            for(boolean first:new boolean[]{true,false})scenarios.add(new Arena.Scenario(env,opp,first));
        }
        return scenarios;
    }
}
