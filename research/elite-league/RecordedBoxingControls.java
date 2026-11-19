import java.nio.file.*;
import java.util.*;
import lab.DuelNetwork;
import lab.Json;

/** Replace one channel of the sumo actor with W-tap after the exact same established boxing prefix. */
final class RecordedBoxingControls {
    static final class Mixed extends DuelEnv.Player {
        final DuelEnv.Player wt;
        final Arena.Neural actor;
        final int mask;
        final int[] different=new int[4];
        Mixed(DuelEnv.Player wt,Arena.Neural actor,int mask){
            super("control-diagnostic",0,actor.rtt,0);this.wt=wt;this.actor=actor;this.mask=mask;firstClick=wt.firstClick;
        }
        @Override public PeriodicComboClock.Control input(PeriodicComboClock.Observation o){
            var w=wt.input(o);var n=actor.input(o);
            if(w.key()!=n.key())different[0]++;
            if(w.strafe()!=n.strafe())different[1]++;
            if(w.click()!=n.click())different[2]++;
            if(w.jump()!=n.jump())different[3]++;
            var c=new PeriodicComboClock.Control((mask&1)!=0?w.key():n.key(),(mask&2)!=0?w.strafe():n.strafe(),
                    (mask&4)!=0?w.click():n.click(),(mask&8)!=0?w.jump():n.jump());
            actor.net.applied(DuelNetwork.pack(c.key(),(int)Math.signum(c.strafe()),c.click(),c.jump()));return c;
        }
    }
    static void play(BoxingDrill.Checkpoint cp,Arena.Model model)throws Exception{
        var results=new LinkedHashMap<String,Object>();String prefix=FullClockHash.digest(cp.state.clock);
        for(int mask:new int[]{0,1,2,4,8,15}){
            var shadow=cp.suffix(model);
            var mixed=new Mixed(cp.state.our().copy(),(Arena.Neural)shadow.our(),mask);
            var g=cp.env.new Game(cp.state,cp.state.first?mixed:cp.state.a.copy(),cp.state.first?cp.state.b.copy():mixed);
            if(!FullClockHash.digest(g.clock).equals(prefix))throw new AssertionError("Changed physical state before intervention");
            g.trace();g.hits.list.addAll(cp.committed);g.finish();
            if(mask==0||mask==15){
                var control=cp.suffix(mask==15?null:model);control.finish();
                if(!g.hits.list.equals(control.hits.list)||!FullClockHash.digest(g.clock).equals(FullClockHash.digest(control.clock)))
                    throw new AssertionError("Unchanged action stream diverged, mask "+mask);
            }
            String label=switch(mask){case 0->"actor";case 1->"wtap_forward_sprint";case 2->"wtap_strafe";case 4->"wtap_click";case 8->"wtap_jump";default->"wtap";};
            var hits=g.hits.list.stream().map(h->List.of(h.at(),h.sideA()!=g.first,h.upgrade())).toList();
            results.put(label,Map.of("hits",hits,"end",g.clock.time(),"different_heads",mixed.different));
        }
        if(!prefix.equals(FullClockHash.digest(cp.state.clock)))throw new AssertionError("Mutated source");
        System.out.println(Json.encode(Map.of("clock",cp.env.clockId,"rtt",cp.env.rtt,"side",cp.state.first?"a":"b",
            "landmark",cp.landmark,"prefix",prefix,"outcomes",results)));
    }
    public static void main(String[] args)throws Exception{
        var actor=Arena.Model.decode(Files.readString(Path.of(args[0])).trim());
        for(var env:Arena.envs(Arrays.stream(args,1,args.length).map(Path::of).toList(),"mmc-recorded",false)){
            env.initial.timing.mouseInput=true;
            for(boolean seat:List.of(true,false))for(var cp:BoxingDrill.acquire(env,seat,false))play(cp,actor);
        }
    }
}
