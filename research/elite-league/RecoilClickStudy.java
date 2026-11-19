import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import lab.DuelNetwork;
import lab.Json;

/** Change only click permission for four input heads during locally visible recoil. */
final class RecoilClickStudy {
    static boolean observeOnly, sprintStudy;
    static final String[] MODES={"original","wtap","open","closed","open-even","open-odd","delay-one"};
    static final String[] RESETS={"original","wtap","release-1","release-2","walk-1","release-then-sprint","sprint-2"};
    static final class Probe extends DuelEnv.Player {
        final DuelEnv.Player base,wt;
        int heads,entry,mode;
        double at;
        boolean used;
        List<Object> local;
        Probe(Arena.Fighter f,int rtt) {
            super("recoil-click",0,rtt,0);base=f.create(rtt,0);wt=new DuelEnv.Player("wtap",0,rtt,0);
        }
        Probe(Probe p){
            super(p.style,0,p.rtt,0);base=p.base.copy();wt=p.wt.copy();heads=p.heads;
            entry=p.entry;mode=p.mode;at=p.at;used=p.used;local=p.local;firstClick=p.firstClick;
        }
        @Override Probe copy(){return new Probe(this);}
        @Override public PeriodicComboClock.Control input(PeriodicComboClock.Observation o){
            heads++;
            var c=base.input(o);var w=wt.input(o);var q=o.own();
            double dx=o.targetX()-q.x(),dz=o.targetZ()-q.z(),gap=Math.hypot(dx,dz);
            double away=gap==0?0:-(q.vx()*dx+q.vz()*dz)/gap;
            double age=o.time()-o.lastOwnHurtTime();
            double targetAge=o.time()-o.lastTargetHurtTime();
            boolean landmark=sprintStudy?targetAge>=0&&targetAge<100:age>=0&&age<=175&&away>=.12&&gap>0&&gap<=3.6;
            if(entry==0 && landmark){
                entry=heads;at=o.time();local=List.of(age,gap,away,q.x(),q.y(),q.z(),q.vx(),q.vy(),q.vz(),
                        o.targetX(),o.targetZ(),o.sprint(),q.onGround(),c.key(),c.click());
                if(sprintStudy)local=List.of(targetAge,gap,away,q.x(),q.y(),q.z(),q.vx(),q.vy(),q.vz(),
                        o.targetX(),o.targetZ(),o.sprint(),q.onGround(),c.key(),c.click());
            }
            if(mode>0 && entry>0){
                if(mode==1)c=w;
                else if(sprintStudy){
                    int key=c.key(),step=heads-entry;
                    if(step==0)key=mode==4?1:mode==6?2:0;
                    if(step==1 && (mode==3||mode>=5))key=mode==3?0:2;
                    used|=key!=c.key();
                    c=new PeriodicComboClock.Control(key,c.strafe(),c.click(),c.jump());
                }else if(heads-entry<4){
                    int t=heads-entry;
                    boolean click=mode==2 || mode==4&&t%2==0 || mode==5&&t%2==1 || mode==6&&t>0;
                    used|=click!=c.click();
                    c=new PeriodicComboClock.Control(c.key(),c.strafe(),click,c.jump());
                }
            }
            if(base instanceof Arena.Neural nn)nn.net.applied(DuelNetwork.pack(c.key(),(int)Math.signum(c.strafe()),c.click(),c.jump()));
            return c;
        }
    }

    static final class Ledger {
        final DuelEnv.Game g;
        final boolean a;
        final double at;
        boolean sprint;
        double vx,vz;
        final List<List<Object>> contacts=new ArrayList<>(),moves=new ArrayList<>();
        final SprintPacketLedger sprintEvents;
        int consumes,admissions;
        Ledger(DuelEnv.Game game,double start){
            g=game;a=g.first;at=start;remember();
            sprintEvents=sprintStudy?new SprintPacketLedger(g.clock.state,start,a):null;
            g.trace();
            var hits=g.hits;
            g.clock.setAudit((time,event,state)->{
                int before=hits.list.size();
                hits.event(time,event,state);
                if(sprintEvents!=null && time<=at+1500)sprintEvents.observe(time,event,state,hits.list,before);
                if(time>at+350)return;
                var b=body();
                if(event.equals(a?"MOVEA":"MOVEB"))moves.add(List.of(time-at,b.q.x(),b.q.y(),b.q.z(),b.q.vx(),b.q.vz(),b.sprint));
                remember();
            });
            g.clock.setClickAudit(e->{
                if(e.fromA()!=a||e.time()>at+350)return;
                if(e.action().equals("ADMIT"))admissions++;
                if(!e.action().equals("CONSUME"))return;
                consumes++;
                var b=body();double scale=e.contact()&&sprint?.6:1.;
                if(vx*scale!=b.q.vx() || vz*scale!=b.q.vz() || b.sprint!=(sprint&&!(e.contact()&&sprint)))
                    throw new AssertionError("Wrong impulse during click consumption");
                if(e.contact())contacts.add(List.of(e.time()-at,e.tick(),sprint,vx,vz,b.q.vx(),b.q.vz(),b.q.x(),b.q.z(),e.admissionId()));
                remember();
            });
        }
        PeriodicComboFightModel.Body body(){return a?g.clock.state.a:g.clock.state.b;}
        void remember(){var b=body();sprint=b.sprint;vx=b.q.vx();vz=b.q.vz();}
    }

    static void play(DuelEnv env,Arena.Fighter arm,Arena.Fighter foe,boolean first)throws Exception{
        var scout=env.new Game(first,new Probe(arm,env.rtt),foe.create(env.rtt,0));scout.finish();var p=(Probe)scout.our();
        var row=new LinkedHashMap<String,Object>();row.put("arm",arm.name());row.put("opponent",foe.name());row.put("clock",env.clockId);
        row.put("side",first?"a":"b");row.put("rtt",env.rtt);row.put("base",scout.reward());row.put("end",scout.clock.time());
        row.put("fresh",first?List.of(scout.clock.state.hits,scout.clock.state.incomingHits):List.of(scout.clock.state.incomingHits,scout.clock.state.hits));
        if(p.entry>0){
            var g=env.new Game(first,new Probe(arm,env.rtt),foe.create(env.rtt,0));var prefix=(Probe)g.our();
            while(g.clock.time()<=p.at && g.step<=scout.step){
                double limit=g.step<env.serverEnds.length?Math.min(env.limit,env.serverEnds[g.step]):env.limit;
                if(!g.clock.advanceToBeforeNextHead(first,limit)){g.step++;continue;}
                if(prefix.heads+1==p.entry)break;
                if(!g.clock.runHeadInterval(first,limit))g.step++;
            }
            if(g.clock.time()!=p.at || prefix.entry!=0 || prefix.heads+1!=p.entry)throw new AssertionError("Different cut");
            row.put("at",p.at);row.put("local",p.local);
            if(g.winner()<0){
                String hash=TradeWords.stateHash(g);row.put("fork",hash);
                var branches=new LinkedHashMap<String,Object>();
                String[] names=sprintStudy?RESETS:MODES;
                for(int mode=0;mode<(observeOnly?1:names.length);mode++){
                    var b=env.new Game(g,g.a.copy(),g.b.copy());
                    if(!hash.equals(TradeWords.stateHash(b)))throw new AssertionError("Different fork");
                    var pilot=(Probe)b.our();pilot.mode=mode;var ledger=new Ledger(b,p.at);
                    b.finish();
                    if(!pilot.local.equals(p.local))throw new AssertionError("Different decision observation");
                    if(mode==0&&!TradeWords.stateHash(b).equals(TradeWords.stateHash(scout)))throw new AssertionError("No-op changed terminal state");
                    var m=new LinkedHashMap<String,Object>();m.put("score",b.reward());m.put("end",b.clock.time());
                    m.put("admissions",ledger.admissions);m.put("consumes",ledger.consumes);m.put("contacts",ledger.contacts);
                    m.put("moves",ledger.moves);m.put(sprintStudy?"changed_movement":"changed_gate",pilot.used);
                    if(sprintStudy)m.put("server_events",ledger.sprintEvents.events);
                    m.put("accepted",b.hits.list.stream().filter(h->h.at()<=p.at+750).map(h->List.of(h.at()-p.at,h.sideA()==first,h.upgrade())).toList());
                    branches.put(names[mode],m);
                }
                if(!hash.equals(TradeWords.stateHash(g)))throw new AssertionError("Changed source state");
                row.put("branches",branches);
            }else row.put("ineligible","server body below fall plane");
        }
        System.out.println(Json.encode(row));
    }
    public static void main(String[] a)throws Exception{
        var arms=Matches.roster(Path.of(a[0]));var foes=Matches.roster(Path.of(a[1]));
        int i=2;
        while(i<a.length && a[i].startsWith("--")){
            if(a[i].equals("--observe"))observeOnly=true;
            else if(a[i].equals("--sprint"))sprintStudy=true;
            else throw new IllegalArgumentException(a[i]);
            i++;
        }
        for(var env:Arena.envs(Arrays.stream(a,i,a.length).map(Path::of).toList(),"mmc",true)){
            env.initial.timing.mouseInput=true;
            for(var arm:arms)for(var foe:foes)for(boolean seat:new boolean[]{true,false})play(env,arm,foe,seat);
        }
    }
}
