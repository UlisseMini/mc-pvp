import java.nio.file.*;
import java.util.*;
import lab.DuelNetwork;
import lab.Json;

/** For an exact same-world intervention, every possible expert watches the prefix and records its actual keys. */
final class SwitchStudy {
    static final String[] NAMES={"wtap","hold","hybrid","anti-hold","anti-hybrid"};
    static final class Primed extends DuelEnv.Player{
        final List<DuelEnv.Player> options=new ArrayList<>();
        int selected=2;boolean hurt,edge;
        double centerX=Double.NaN,centerZ;
        Primed(int rtt,List<Arena.Fighter> field){super("switch",0,rtt,0);for(var f:field)options.add(f.create(rtt,0));}
        private Primed(Primed src){
            super(src.style,0,src.rtt,0);selected=src.selected;firstClick=src.firstClick;hurt=src.hurt;edge=src.edge;
            centerX=src.centerX;centerZ=src.centerZ;for(var p:src.options)options.add(p.copy());
        }
        @Override Primed copy(){return new Primed(this);}
        public PeriodicComboClock.Control input(PeriodicComboClock.Observation o){
            PeriodicComboClock.Control actual=null;
            for(int i=0;i<options.size();i++){
                var c=options.get(i).input(o);
                if(i==selected)actual=c;
            }
            int action=DuelNetwork.pack(actual.key(),(int)Math.signum(actual.strafe()),actual.click(),actual.jump());
            for(var p:options)if(p instanceof Arena.Neural n)n.net.applied(action);
            var q=o.own();
            if(Double.isNaN(centerX)){centerX=(o.targetX()+q.x())/2;centerZ=(o.targetZ()+q.z())/2;}
            double x=q.x()-centerX,z=q.z()-centerZ;
            hurt|=Double.isFinite(o.lastOwnHurtTime());
            edge|=Math.hypot(x,z)>3.5 && x*q.vx()+z*q.vz()>.04;
            return actual;
        }
    }
    static void play(DuelEnv env,boolean first,Arena.Fighter opponent,List<Arena.Fighter> experts){
        var p=new Primed(env.rtt,experts);
        var base=env.new Game(first,p,opponent.create(env.rtt,0));Set<String> visited=new HashSet<>();
        var cuts=new LinkedHashMap<String,Object>();
        while(base.advance()){
            for(String signal:List.of("received","outward-edge")){
                if(!(signal.equals("received")?p.hurt:p.edge)||!visited.add(signal))continue;
                var scores=new TreeMap<String,Double>();
                double cutoff=base.clock.time();
                for(int i=0;i<NAMES.length;i++){
                    var g=env.new Game(base,base.a.copy(),base.b.copy());
                    ((Primed)g.our()).selected=i;
                    g.finish();scores.put(NAMES[i],g.reward());
                }
                cuts.put(signal,Map.of("at",cutoff,"scores",scores));
            }
        }
        for(Object raw:cuts.values()){
            var data=(Map<?,?>)raw;var scores=(Map<?,?>)data.get("scores");
            if((double)scores.get("hybrid")!=base.reward())throw new AssertionError("Unchanged fork failed");
        }
        System.out.println(Json.encode(Map.of("clock",env.clockId,"rtt",env.rtt,"side",first?"a":"b","opp",opponent.name(),
            "prefix","hybrid","full_score",base.reward(),"cuts",cuts)));
    }
    public static void main(String[] args)throws Exception{
        var experts=Matches.roster(Path.of(args[0]));var opponents=Matches.roster(Path.of(args[1]));
        if(!experts.stream().map(Arena.Fighter::name).toList().equals(List.of(NAMES)))throw new IllegalArgumentException("Wrong expert order");
        for(var env:Arena.envs(Arrays.stream(args,2,args.length).map(Path::of).toList(),"mmc",true))
            for(var opp:opponents)for(boolean first:new boolean[]{true,false})play(env,first,opp,experts);
    }
}
