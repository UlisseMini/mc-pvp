import java.nio.file.*;
import java.util.*;
import lab.DuelNetwork;
import lab.Json;

/** Acquire once with real W-tap. Fork the complete queue/clock and the same actual applied-input history. */
final class BoxingDrill {
    record Head(PeriodicComboClock.Observation view,PeriodicComboClock.Control applied){}
    static final class Recorder extends DuelEnv.Player{
        final List<Head> history=new ArrayList<>();
        Recorder(int ping){super("wtap",0,ping,0);}
        @Override public PeriodicComboClock.Control input(PeriodicComboClock.Observation o){
            var action=super.input(o);history.add(new Head(o,action));return action;
        }
    }
    record Result(int cycles,double ms,String terminal){}
    static final class Checkpoint {
        final DuelEnv env;final DuelEnv.Game state;final List<Head> history;
        final double landmark;final List<DuelEnv.Hit> committed;
        final int k; final Result baseline;
        Checkpoint(DuelEnv env,DuelEnv.Game game,List<Head> history,double landmark,List<DuelEnv.Hit> committed,int k){
            this.env=env;this.state=env.new Game(game,game.a.copy(),game.b.copy());
            this.history=List.copyOf(history);this.landmark=landmark;this.committed=List.copyOf(committed);this.k=k;
            baseline=run(null,null);
        }
        DuelEnv.Game suffix(Arena.Model model){
            var a=state.a.copy();var b=state.b.copy();
            if(model!=null){
                var n=new Arena.Neural(model,env.rtt);
                for(var head:history){
                    n.input(head.view());
                    var c=head.applied();
                    n.net.applied(DuelNetwork.pack(c.key(),(int)Math.signum(c.strafe()),c.click(),c.jump()));
                }
                n.jumps=0;n.firstClick=state.our().firstClick;
                if(state.first)a=n;else b=n;
            }
            var g=env.new Game(state,a,b);g.trace();g.hits.list.addAll(committed);
            return g;
        }
        Result run(Arena.Model model,List<Object> trace){
            var g=suffix(model);
            int seen=0,cycles=0;double last=landmark;
            do{
                var plant=g.clock.state;
                while(seen<g.hits.list.size()){
                    var h=g.hits.list.get(seen++);
                    if(trace!=null){
                        var impulse=h.sideA()?plant.lastFreshB:plant.lastFreshA;
                        trace.add(Arrays.asList(h.at(),h.sideA()==g.first,h.upgrade(),
                            Math.hypot(plant.serverAx-plant.serverBx,plant.serverAz-plant.serverBz),
                            h.upgrade()||impulse==null||impulse.time()!=h.at()?null:List.of(impulse.x(),impulse.y(),impulse.z())));
                    }
                    if(h.at()-last>1000)return new Result(cycles,last+1000-landmark,"inactive");
                    if(h.sideA()!=g.first)return new Result(cycles,h.at()-landmark,"return");
                    if(!h.upgrade()){cycles++;last=h.at();}
                }
                if(g.clock.time()-last>1000)return new Result(cycles,last+1000-landmark,"inactive");
            }while(g.advance());
            return new Result(cycles,g.clock.time()-landmark,"censored");
        }
    }
    static List<Checkpoint> acquire(DuelEnv e,boolean first,boolean sensitivities){
        var p=new Recorder(e.rtt);var g=e.new Game(first,p,new DuelEnv.Player("wtap",0,e.rtt,2000));
        g.trace();int seen=0,chain=0;Set<Integer> used=new HashSet<>();var entries=new ArrayList<Checkpoint>();
        while(g.advance()){
            var hs=g.hits.list;double wake=Math.max(g.a.firstClick,g.b.firstClick);
            for(;seen<hs.size();seen++){
                var h=hs.get(seen);
                if(!Double.isFinite(wake)||h.at()<wake)continue;
                if(h.sideA()!=first){chain=0;continue;}
                if(h.upgrade())continue;
                if(++chain!=3&&(!sensitivities||(chain!=5&&chain!=10)))continue;
                if(used.add(chain)){
                    entries.add(new Checkpoint(e,g,p.history,h.at(),hs.subList(seen+1,hs.size()),chain));
                    if(!sensitivities)return entries;
                }
            }
        }
        return entries;
    }
    /** Shift each repeated client schedule as a unit. These are development phases of the same donor. */
    static DuelEnv.Tape shift(DuelEnv.Tape t,int tickAms,int tickBms){
        if(tickAms==0&&tickBms==0)return t;
        var events=new ArrayList<PeriodicComboClock.Scheduled>();
        for(var v:t.events()){
            long delta=v.kind().name().startsWith("SERVER_")?0L:(v.fromA()?tickAms:tickBms)*1_000_000L;
            events.add(new PeriodicComboClock.Scheduled(v.nanos()+delta,v.sequence(),v.kind(),v.fromA()));
        }
        return new DuelEnv.Tape(t.id()+"-"+tickAms+"-"+tickBms,events,t.serverEnds(),t.trackerAFirst());
    }
    public static void main(String[] args)throws Exception{
        var base=Arena.Model.decode(Files.readString(Path.of(args[0])).trim());
        for(var file:Arrays.stream(args,1,args.length).map(Path::of).toList()){
            var tape=DuelEnv.Tape.read(file,20000);
            for(int rtt:new int[]{0,80})for(boolean first:new boolean[]{true,false}){
                var e=new DuelEnv(tape,"mmc",rtt,false);
                for(var c:acquire(e,first,false)){
                    var wt=new ArrayList<Object>();var net=new ArrayList<Object>();
                    var w=c.run(null,wt);var n=c.run(base,net);
                    System.out.println(Json.encode(Map.of("clock",e.clockId,"rtt",rtt,"side",first?"a":"b","landmark",c.landmark,
                        "wtap",List.of(w.cycles(),w.ms(),w.terminal(),wt),"hybrid",List.of(n.cycles(),n.ms(),n.terminal(),net))));
                }
            }
        }
    }
}
