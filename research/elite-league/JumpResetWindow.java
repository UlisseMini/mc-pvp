import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import lab.DuelNetwork;
import lab.ExactTravel;
import lab.Json;

/** Change only Space at the first locally visible incoming fresh-hurt cue in a regulation bout. */
final class JumpResetWindow {
    static final String[] MODES={"original","wtap","pulse-now","first-ground","second-ground","suppress"};
    static final int HEADS=10;
    static final double WINDOW=850;

    static final class Pilot extends DuelEnv.Player {
        final DuelEnv.Player base,wt;
        int heads,entry,grounds,mode;
        double at;
        boolean space,changed;
        List<Object> view;
        Pilot(Arena.Fighter arm,int rtt){
            super("jump-window",0,rtt,0);base=arm.create(rtt,0);wt=new DuelEnv.Player("wtap",0,rtt,0);
        }
        Pilot(Pilot p){
            super(p.style,0,p.rtt,0);base=p.base.copy();wt=p.wt.copy();heads=p.heads;entry=p.entry;grounds=p.grounds;
            mode=p.mode;at=p.at;space=p.space;changed=p.changed;view=p.view;firstClick=p.firstClick;
        }
        @Override Pilot copy(){return new Pilot(this);}
        @Override public void received(PeriodicComboClock.PacketObservation p){base.received(p);wt.received(p);}
        @Override public PeriodicComboClock.Control input(PeriodicComboClock.Observation o){
            heads++;
            var c=base.input(o);var w=wt.input(o);var q=o.own();
            double age=o.time()-o.lastOwnHurtTime();
            if(entry==0 && age>=0 && age<100){
                entry=heads;at=o.time();
                view=List.of(age,q.onGround(),q.x(),q.y(),q.z(),q.vx(),q.vy(),q.vz(),
                        o.targetX(),o.targetY(),o.targetZ(),o.sprint(),c.key(),c.strafe(),c.click(),c.jump());
            }
            if(entry!=0 && mode==1)c=w;
            space=c.jump();
            if(entry!=0 && heads-entry<HEADS){
                if(q.onGround())grounds++;
                if(mode==2 && heads==entry || mode==3 && q.onGround() && grounds==1 ||
                        mode==4 && q.onGround() && grounds==2)space=true;
                if(mode==5)space=false;
            }
            changed|=space!=c.jump();
            if(base instanceof Arena.Neural nn)
                nn.net.applied(DuelNetwork.pack(c.key(),(int)Math.signum(c.strafe()),c.click(),space));
            return new PeriodicComboClock.Control(c.key(),c.strafe(),c.click(),space);
        }
    }

    static final class Travel {
        ExactTravel.State before;
        final List<List<Object>> steps=new ArrayList<>();
        Travel(DuelEnv.Game g){
            boolean first=g.first;var p=(Pilot)g.our();
            var s=g.clock.state;var body=first?s.a:s.b;
            before=body.q;
            g.trace();var hits=g.hits;
            g.clock.setAudit((t,event,state)->{
                hits.event(t,event,state);
                if(t>p.at+WINDOW)return;
                var b=first?state.a:state.b;
                if(event.equals(first?"MOVEA":"MOVEB")){
                    // A successful jump sets the ten-tick cooldown. All later travel decrements it.
                    boolean tookOff=b.jumpTicks==10;
                    if(tookOff && (!p.space || !before.onGround() || Math.abs(b.q.y()-before.y()-(double).42f)>1e-9))
                        throw new AssertionError("Unexpected ground jump at "+t);
                    steps.add(List.of(t-p.at,p.space,before.onGround(),tookOff,b.q.onGround(),
                        b.q.x(),b.q.y(),b.q.z(),b.q.vx(),b.q.vy(),b.q.vz(),b.sprint));
                }
                before=b.q;
            });
        }
    }

    static void play(DuelEnv env,Arena.Fighter arm,Arena.Fighter foe,boolean first)throws Exception{
        var original=env.new Game(first,new Pilot(arm,env.rtt),foe.create(env.rtt,0));
        original.finish();var p=(Pilot)original.our();
        var r=new LinkedHashMap<String,Object>();r.put("arm",arm.name());r.put("opponent",foe.name());
        r.put("side",first?"a":"b");r.put("rtt",env.rtt);r.put("clock",env.clockId);
        var s=original.clock.state;r.put("base",original.reward());r.put("end",original.clock.time());
        r.put("fresh",first?List.of(s.hits,s.incomingHits):List.of(s.incomingHits,s.hits));
        if(p.entry>0){
            var g=env.new Game(first,new Pilot(arm,env.rtt),foe.create(env.rtt,0));
            var prefix=(Pilot)g.our();
            while(g.step<=original.step && g.clock.time()<=p.at){
                double end=g.step<env.serverEnds.length?Math.min(env.limit,env.serverEnds[g.step]):env.limit;
                if(!g.clock.advanceToBeforeNextHead(first,end)){g.step++;continue;}
                if(prefix.heads+1==p.entry)break;
                if(!g.clock.runHeadInterval(first,end))g.step++;
            }
            if(prefix.entry!=0 || prefix.heads+1!=p.entry || g.clock.time()!=p.at)throw new AssertionError("Wrong input boundary");
            r.put("at",p.at);r.put("view",p.view);
            if(g.winner()<0){
                String fork=TradeWords.stateHash(g);r.put("fork",fork);
                var branches=new LinkedHashMap<String,Object>();
                for(int i=0;i<MODES.length;i++){
                    var b=env.new Game(g,g.a.copy(),g.b.copy());
                    if(!fork.equals(TradeWords.stateHash(b)))throw new AssertionError("Different initial state");
                    var pilot=(Pilot)b.our();pilot.mode=i;var travel=new Travel(b);b.finish();
                    if(!p.view.equals(pilot.view))throw new AssertionError("Different local information");
                    if(i==0 && !TradeWords.stateHash(b).equals(TradeWords.stateHash(original)))throw new AssertionError("No-op changed terminal state");
                    var row=new LinkedHashMap<String,Object>();row.put("score",b.reward());row.put("end",b.clock.time());
                    row.put("changed_space",pilot.changed);row.put("steps",travel.steps);
                    row.put("accepted",b.hits.list.stream().filter(h->h.at()>=p.at && h.at()<=p.at+WINDOW)
                            .map(h->List.of(h.at()-p.at,h.sideA()==first,h.upgrade())).toList());
                    branches.put(MODES[i],row);
                }
                if(!fork.equals(TradeWords.stateHash(g)))throw new AssertionError("Changed initial state");
                r.put("branches",branches);
            }else r.put("ineligible","already below the fall plane");
        }
        System.out.println(Json.encode(r));
    }
    public static void main(String[] args)throws Exception{
        var arms=Matches.roster(Path.of(args[0]));var foes=Matches.roster(Path.of(args[1]));
        for(var env:Arena.envs(Arrays.stream(args,2,args.length).map(Path::of).toList(),"mmc",true)){
            env.initial.timing.mouseInput=true;
            for(var a:arms)for(var o:foes)for(boolean first:new boolean[]{true,false})play(env,a,o,first);
        }
    }
}
