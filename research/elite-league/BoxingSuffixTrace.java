import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import lab.Json;

/** Observer-only ledger for W-tap and a frozen specialist from the same complete established state. */
final class BoxingSuffixTrace {
    static final class Ledger {
        final DuelEnv.Game g;
        final List<Object> events=new ArrayList<>();
        final List<Object> accepted=new ArrayList<>();
        final double[] vx=new double[2],vz=new double[2];
        final boolean[] sprint=new boolean[2];
        final double start;
        int seen;
        Ledger(BoxingDrill.Checkpoint cp,Arena.Model model){
            this(cp.suffix(model),cp.landmark);
        }
        Ledger(DuelEnv.Game game,double landmark){
            g=game;start=landmark;
            for(var hit:g.hits.list)accepted.add(List.of(hit.at(),hit.sideA(),hit.upgrade()));
            seen=g.hits.list.size();save();
            g.clock.setClickAudit(e->{
                if(!e.action().equals("CONSUME")||!e.contact())return;
                int i=e.fromA()?0:1;var body=e.fromA()?g.clock.state.a:g.clock.state.b;
                double factor=sprint[i]?.6:1.;
                if(body.q.vx()!=vx[i]*factor||body.q.vz()!=vz[i]*factor)
                    throw new AssertionError("Unexpected local contact drag");
                events.add(Map.of("t",e.time()-start,"event","contact","subject",e.fromA()==g.first,
                    "sprint_before",sprint[i],"before",List.of(vx[i],vz[i]),"after",List.of(body.q.vx(),body.q.vz())));
                save();
            });
            g.clock.setAudit((t,event,s)->{
                g.hits.event(t,event,s);
                while(seen<g.hits.list.size()){
                    var h=g.hits.list.get(seen++);accepted.add(List.of(h.at(),h.sideA(),h.upgrade()));
                    var impulse=h.sideA()?s.lastFreshB:s.lastFreshA;
                    var entry=new LinkedHashMap<String,Object>();entry.put("t",t-start);entry.put("event","hit");
                    entry.put("subject",h.sideA()==g.first);entry.put("upgrade",h.upgrade());
                    entry.put("gap",Math.hypot(s.serverAx-s.serverBx,s.serverAz-s.serverBz));
                    if(!h.upgrade()&&impulse!=null&&impulse.time()==h.at())
                        entry.put("velocity",List.of(impulse.x(),impulse.y(),impulse.z()));
                    events.add(entry);
                }
                boolean a=event.endsWith("A");boolean head=event.startsWith("POLICY");
                if(head||event.equals("MOVEA")||event.equals("MOVEB")){
                    var b=a?s.a:s.b;var v=a?s.ab:s.ba;var c=g.clock.appliedControl(a);
                    double gap=Math.hypot(v.x-b.q.x(),v.z-b.q.z());
                    events.add(Map.of("t",t-start,"event",head?"head":"move","subject",a==g.first,
                        "key",c.key(),"lateral",c.strafe(),"jump",c.jump(),"click",c.click(),"sprint",b.sprint,
                        "gap",gap,"pose",List.of(b.q.x(),b.q.y(),b.q.z(),b.q.vx(),b.q.vy(),b.q.vz(),b.q.onGround())));
                }
                save();
            });
        }
        void save(){
            for(int i=0;i<2;i++){
                var b=i==0?g.clock.state.a:g.clock.state.b;vx[i]=b.q.vx();vz[i]=b.q.vz();sprint[i]=b.sprint;
            }
        }
        Map<String,Object> run(){
            int i=0;boolean done=false;
            do{
                while(i<g.hits.list.size())if(g.hits.list.get(i++).sideA()!=g.first)done=true;
            }while(!done&&g.advance());
            return Map.of("accepted",accepted,"events",events);
        }
    }
    public static void main(String[] args)throws Exception{
        var model=Arena.Model.decode(Files.readString(Path.of(args[0])).trim());
        if(args.length>1 && args[1].equals("--wire")){
            int rtt=Integer.parseInt(args[2]);
            for(int i=5;i<args.length;i++){
                var env=BoxingEdgeHitSelect.environment(Path.of(args[i]),rtt,20_000,Path.of(args[3]),Path.of(args[4]));
                for(boolean seat:new boolean[]{true,false}){
                    for(var cp:BoxingDrill.acquire(env,seat,false))emit(cp,model,null);
                }
            }
            return;
        }
        boolean breadth=args.length>2 && args[1].equals("--mouse-defenders");
        var foes=breadth?Matches.roster(Path.of(args[2])):List.of(Evolve.WT);
        for(var file:Arrays.stream(args,breadth?3:1,args.length).map(Path::of).toList()){
            var tape=DuelEnv.Tape.read(file,20000);
            for(var foe:foes)for(int ping:new int[]{0,80})for(boolean seat:new boolean[]{true,false}){
                var env=new DuelEnv(tape,"mmc",ping,false);
                env.initial.timing.mouseInput=breadth;
                var starts=breadth?BoxingDrill.acquire(env,seat,false,new BoxingBreadth.Defender(foe,ping)):BoxingDrill.acquire(env,seat,false);
                for(var cp:starts)emit(cp,model,breadth?foe.name():null);
            }
        }
    }

    private static void emit(BoxingDrill.Checkpoint cp,Arena.Model model,String opponent){
        var row=new LinkedHashMap<String,Object>();
        row.put("clock",cp.env.clockId);row.put("side",cp.state.first?"a":"b");row.put("rtt",cp.env.rtt);row.put("k",cp.k);
        row.put("landmark",cp.landmark);row.put("wtap",new Ledger(cp,null).run());row.put("specialist",new Ledger(cp,model).run());
        if(opponent!=null)row.put("opponent",opponent);
        System.out.println(Json.encode(row));
    }
}
