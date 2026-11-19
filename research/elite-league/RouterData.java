import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import lab.DuelNetwork;
import lab.ExpertRouter;
import lab.Json;

/** Produce counterfactual outcome labels at an exact pre-decision boundary on a single saved fight. */
final class RouterData {
    static final class Prefix extends DuelEnv.Player {
        Arena.Neural router;
        DuelEnv.Player wt,hold;
        int baseline=-1;
        Prefix(Arena.Model model,int rtt){
            super("router-prefix",0,rtt,0);router=new Arena.Neural(model,rtt);
            wt=new DuelEnv.Player("wtap",0,rtt,0);hold=new DuelEnv.Player("hold",0,rtt,0);
        }
        private Prefix(Prefix p){
            super(p.style,0,p.rtt,0);router=p.router.copy();wt=p.wt.copy();hold=p.hold.copy();
            baseline=p.baseline;firstClick=p.firstClick;
        }
        @Override Prefix copy(){return new Prefix(this);}
        public PeriodicComboClock.Control input(PeriodicComboClock.Observation o){
            if(baseline==0)return wt.input(o);
            if(baseline==1)return hold.input(o);
            wt.input(o);hold.input(o);
            return router.input(o);
        }
    }
    static Arena.Model skeleton()throws Exception{
        var base=Path.of("research/elite-league");
        String[] paths={"robust-frozen/champion.net","frozen/hybrid.net","challenge-frozen/anti-hybrid.net","challenge-frozen/anti-hold.net"};
        float[] w=new float[ExpertRouter.SIZE];int index=ExpertRouter.PARAMETERS+1;
        for(int i=0;i<paths.length;i++){
            var expert=RobustField.load("expert",base.resolve(paths[i])).model();
            if(expert.family()!=ExpertRouter.FAMILIES[i])throw new IllegalArgumentException("Architecture changed: "+paths[i]);
            System.arraycopy(expert.weights(),0,w,index,expert.weights().length);index+=expert.weights().length;
        }
        return new Arena.Model(DuelNetwork.Family.ROUTER,w);
    }
    static String example(Arena.Scenario sc,Arena.Model model,int variation){
        var e=sc.env();boolean first=sc.first();int ping=first?e.rtt:e.otherRtt,otherPing=first?e.otherRtt:e.rtt;
        var p=new Prefix(model,ping);
        var g=e.new Game(first,p,sc.opponent().create(otherPing,0,true,e.initial.config.sumoDiameter));
        var router=p.router.net.router();
        while(g.step<e.serverEnds.length && g.winner()<0 && !router.triggered()){
            double end=e.serverEnds[g.step];
            if(!g.clock.runHeadInterval(first,end))g.step++;
        }
        var result=new LinkedHashMap<String,Object>();
        result.put("clock",e.clockId);result.put("variation",variation);result.put("opponent",sc.opponent().name());
        result.put("side",first?"a":"b");result.put("rtt",ping);result.put("other_rtt",otherPing);
        double[] scores=new double[6];
        var ref=new Arena.Fighter("default",model);
        double full=sc.game(ref);
        result.put("wtap_full",sc.game(Evolve.WT));result.put("hold_full",sc.game(Evolve.HOLD));result.put("default_full",full);
        if(router.triggered()){
            result.put("x",router.cueFeatures());result.put("at_ms",g.clock.time());
            for(int i=0;i<6;i++){
                var branch=e.new Game(g,g.a.copy(),g.b.copy());var prefix=(Prefix)branch.our();
                if(i<4)prefix.router.net.router().forceContinuation(i);else prefix.baseline=i-4;
                branch.finish();scores[i]=branch.reward();
            }
            if(scores[0]!=full)throw new AssertionError("Unchanged continuation differs from uninterrupted reference");
            result.put("scores",scores);
        }else{g.finish();if(g.reward()!=full)throw new AssertionError("No-contact prefix changed fight");}
        return Json.encode(result);
    }

    public static void main(String[] args)throws Exception{
        boolean fitting=args[0].equals("training");
        var opponents=Matches.roster(Path.of(args[1]));
        var model=skeleton();
        var pool=Executors.newFixedThreadPool(3);
        try{
            int variationCount=fitting?12:1;
            for(Path path:Arrays.stream(args,2,args.length).map(Path::of).toList()){
                var tape=DuelEnv.Tape.read(path,DuelEnv.LIMIT);
                for(int v=0;v<variationCount;v++){
                    int[] diam={10,8,12,14},pings={0,80,40,100};
                    var shifted=v==0?tape:BoxingDrill.shift(tape,(11*v)%49,(23*v)%49);
                    var work=new ArrayList<Callable<String>>();final int variant=v;
                    for(int sidePing=0;sidePing<2;sidePing++){
                        int a=v==0||v%3==0?pings[sidePing]:pings[(v+sidePing)%4];
                        int b=v==0||v%3==0?a:pings[(v*2+sidePing)%4];
                        int size=v==0?10:diam[v%4];
                        double gap=v==0?6:new double[]{5,6,7,4.5}[v%4],offset=v==0?0:new double[]{-.7,0,.85}[v%3];
                        var env=new DuelEnv(shifted,"mmc",a,b,true,v==0?29:24+v,true,size,gap,offset);
                        for(var opp:opponents)for(boolean first:new boolean[]{true,false}){
                            var sc=new Arena.Scenario(env,opp,first);work.add(()->example(sc,model,variant));
                        }
                    }
                    for(var task:pool.invokeAll(work))System.out.println(task.get());
                }
            }
        }finally{pool.shutdown();}
    }
}
