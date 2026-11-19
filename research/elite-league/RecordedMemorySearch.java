import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import lab.DuelNetwork;
import lab.Json;

/** Add memory to a frozen direct-control MLP, paired with a 64-weight feed-forward search. */
final class RecordedMemorySearch {
    static final int H=DuelNetwork.HIDDEN, INPUTS=DuelNetwork.INPUTS, OUT=DuelNetwork.OUTPUTS;
    static Arena.Model lift(Arena.Model parent) {
        if(parent.family()!=DuelNetwork.Family.MLP)throw new IllegalArgumentException("Expected an MLP");
        float[] w=new float[DuelNetwork.size(DuelNetwork.Family.RECURRENT)];
        for(int h=0;h<H;h++)System.arraycopy(parent.weights(),h*(INPUTS+1),w,h*(INPUTS+1+H),INPUTS+1);
        System.arraycopy(parent.weights(),H*(INPUTS+1),w,H*(INPUTS+1+H),OUT*(H+1));
        return new Arena.Model(DuelNetwork.Family.RECURRENT,w);
    }
    static int index(boolean memory,int i) {
        // Both searches add 64 degrees of freedom in the same hidden layer.
        // The ablation adjusts the first eight direct observation weights of each unit.
        int unit=i/H, channel=i%H;
        return memory ? unit*(INPUTS+1+H)+INPUTS+1+channel : unit*(INPUTS+1)+1+channel;
    }
    static Arena.Model vary(Arena.Model parent,boolean memory,double scale,Random random){
        float[] w=parent.weights().clone();
        for(int i=0;i<H*H;i++)if(random.nextDouble()<.45)w[index(memory,i)]+=scale*random.nextGaussian();
        return new Arena.Model(parent.family(),w);
    }
    public static void main(String[] args)throws Exception{
        Path out=Path.of(args[0]);Files.createDirectories(out);
        Arena.Model parent=Arena.Model.decode(Files.readString(Path.of(args[1])).trim()),lifted=lift(parent);
        var foes=Matches.roster(Path.of(args[2]));var tapes=Arrays.stream(args,3,args.length).map(Path::of).toList();
        var envs=Arena.envs(tapes,"mmc-recorded",true);for(var e:envs)e.initial.timing.mouseInput=true;
        var pool=Executors.newFixedThreadPool(3);
        var rng=new Random(6691829);var variation=new Random[]{new Random(217619),new Random(217619)};
        var lineage=List.of(new ArrayList<>(List.of(parent)),new ArrayList<>(List.of(lifted)));
        try(var log=Files.newBufferedWriter(out.resolve("training.jsonl"),StandardOpenOption.CREATE_NEW)){
            long bouts=0;
            for(int gen=0;gen<48;gen++){
                var cases=new ArrayList<Arena.Scenario>();
                for(var foe:foes)for(int k=0;k<2;k++){
                    var env=envs.get(rng.nextInt(envs.size()));
                    for(boolean seat:List.of(false,true))cases.add(new Arena.Scenario(env,foe,seat));
                }
                if(gen==0)for(var sc:cases){
                    var a=sc.round(new Arena.Fighter("mlp",parent));var b=sc.round(new Arena.Fighter("neutral-memory",lifted));
                    if(a.reward()!=b.reward()||a.clock.time()!=b.clock.time()||a.clock.state.hits!=b.clock.state.hits
                        ||a.clock.state.incomingHits!=b.clock.state.incomingHits)throw new AssertionError("Zero-memory conversion changed the bout");
                }
                var baseline=Evolve.play(Evolve.WT,cases);bouts+=cases.size();
                for(int track=0;track<2;track++){
                    boolean memory=track==1;String name=memory?"memory":"feed";var old=lineage.get(track);
                    var entrants=new ArrayList<Arena.Fighter>();
                    for(var m:old)entrants.add(new Arena.Fighter("parent-"+entrants.size(),m));
                    // Keep the original point reachable even after a lucky panel.
                    entrants.add(new Arena.Fighter("origin",memory?lifted:parent));
                    while(entrants.size()<18){
                        int child=entrants.size();
                        double scale=new double[]{.06,.16,.38,.9}[child%4];
                        entrants.add(new Arena.Fighter("child-"+child,vary(old.get(child%old.size()),memory,scale,variation[track])));
                    }
                    var ranks=Evolve.rank(entrants,cases,pool);bouts+=(long)cases.size()*entrants.size();
                    old.clear();for(var r:ranks){
                        if(old.stream().noneMatch(m->Arrays.equals(m.weights(),r.fighter().model().weights())))old.add(r.fighter().model());
                        if(old.size()==3)break;
                    }
                    var top=ranks.get(0);
                    if(gen%4==3)Files.writeString(out.resolve(String.format("%s-%02d.net",name,gen)),old.get(0).encode()+"\n",StandardOpenOption.CREATE_NEW);
                    log.write(Json.encode(Map.of("generation",gen,"track",name,"mean",top.mean(),"worst",top.worst(),
                        "wtap_mean",baseline.mean(),"wtap_worst",baseline.worst(),"cumulative_bouts",bouts)));log.newLine();
                }
                log.flush();
            }
        }finally{pool.shutdown();}
    }
}
