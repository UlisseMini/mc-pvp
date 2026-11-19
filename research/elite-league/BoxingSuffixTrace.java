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
            g=cp.suffix(model);start=cp.landmark;
            for(var hit:cp.committed)accepted.add(List.of(hit.at(),hit.sideA(),hit.upgrade()));
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
        for(var file:Arrays.stream(args,1,args.length).map(Path::of).toList()){
            var tape=DuelEnv.Tape.read(file,20000);
            for(int ping:new int[]{0,80})for(boolean seat:new boolean[]{true,false}){
                var env=new DuelEnv(tape,"mmc",ping,false);
                for(var cp:BoxingDrill.acquire(env,seat,false)){
                    System.out.println(Json.encode(Map.of("clock",env.clockId,"side",seat?"a":"b","rtt",ping,"k",cp.k,
                        "landmark",cp.landmark,"wtap",new Ledger(cp,null).run(),"specialist",new Ledger(cp,model).run())));
                }
            }
        }
    }
}
