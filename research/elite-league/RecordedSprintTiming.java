import java.nio.file.Path;
import java.util.*;
import lab.DuelNetwork;
import lab.Json;

/** Factor the causal effect of always pressing Ctrl into observable parts of a sumo fight. */
final class RecordedSprintTiming {
    enum Period { NATURAL, ALL, BEFORE_HURT, AFTER_HURT, APPROACH, RECOIL, GROUND, AIR }
    static final class Control extends DuelEnv.Player {
        final Arena.Neural policy;
        final Period period;
        int changes;
        double firstChange=-1;
        Control(Arena.Model model,int rtt,Period period){
            super("network",0,rtt,0);policy=new Arena.Neural(model,rtt);this.period=period;
        }
        public PeriodicComboClock.Control input(PeriodicComboClock.Observation o){
            var natural=policy.input(o);
            if(natural.key()!=PeriodicComboFightModel.W)return natural;
            var q=o.own();double approach=(o.targetX()-q.x())*q.vx()+(o.targetZ()-q.z())*q.vz();
            boolean received=Math.max(o.lastOwnHurtTime(),o.lastTargetHurtTime())>=0;
            boolean force=switch(period){
                case NATURAL->false; case ALL->true; case BEFORE_HURT->!received; case AFTER_HURT->received;
                case APPROACH->approach>=0; case RECOIL->approach<0; case GROUND->q.onGround(); case AIR->!q.onGround();
            };
            if(!force)return natural;
            changes++;if(firstChange<0)firstChange=o.time();
            int key=PeriodicComboFightModel.S;
            policy.net.applied(DuelNetwork.pack(key,(int)Math.signum(natural.strafe()),natural.click(),natural.jump()));
            return new PeriodicComboClock.Control(key,natural.strafe(),natural.click(),natural.jump());
        }
        public boolean injected(PeriodicComboClock.Observation o,double t){return policy.injected(o,t);}
        public void received(PeriodicComboClock.PacketObservation p){policy.received(p);}
    }
    public static void main(String[] args)throws Exception{
        var arms=Matches.roster(Path.of(args[0]));var foes=Matches.roster(Path.of(args[1]));
        var files=Arrays.stream(args,2,args.length).map(Path::of).toList();
        for(var env:Arena.envs(files,"mmc-range",true)){
            env.initial.timing.mouseInput=true;
            for(var foe:foes)for(boolean first:List.of(true,false)){
                var control=new Arena.Scenario(env,foe,first).round(Evolve.WT);
                for(var arm:arms)for(var period:Period.values()){
                    var policy=new Control(arm.model(),env.rtt,period);
                    var rival=foe.create(env.rtt,env.rtt,0,true,13,env.initial.config.hitTicks);
                    var game=env.new Game(first,policy,rival);game.trace();game.finish();
                    var result=new LinkedHashMap<String,Object>();
                    result.put("clock",env.clockId);result.put("side",first?"a":"b");result.put("rtt",env.rtt);
                    result.put("opponent",foe.name());result.put("arm",arm.name());result.put("period",period.name());
                    result.put("score",game.reward());result.put("wtap",control.reward());result.put("end_ms",game.clock.time());
                    result.put("changes",policy.changes);result.put("first_change_ms",policy.firstChange);
                    result.put("own_fresh",game.hits.list.stream().filter(h->!h.upgrade() && h.sideA()==first).count());
                    result.put("incoming_fresh",game.hits.list.stream().filter(h->!h.upgrade() && h.sideA()!=first).count());
                    System.out.println(Json.encode(result));
                }
            }
        }
    }
}
