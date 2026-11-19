import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import lab.DuelNetwork;
import lab.Json;

/** Change only a short movement input at an own-observed outward approach to the ring edge. */
final class EdgeRecoveryStudy {
    static final List<String> WORDS=List.of("unchanged","wtap","center-strafe-3","away-strafe-3",
            "no-strafe-3","center-strafe-6","toward-center-3","away-center-3","jump-once");
    static boolean learning;
    // Offline side channel only. No saved example is read by a controller.
    static List<double[]> sampleSink;
    static int firstSample, sampleStride, sampleLimit;
    static void capture(List<double[]> out,int first,int stride,int limit){
        sampleSink=out;firstSample=first;sampleStride=stride;sampleLimit=limit;
    }
    static final class Probe extends DuelEnv.Player {
        final DuelEnv.Player base, tap;
        final boolean network;
        final double radius;
        int heads, entry, choice, start;
        boolean initialized;
        double cx,cz,at;
        List<Double> view;
        Probe(Arena.Fighter f,int ping,double radius){
            super("edge-recovery",0,ping,0);
            this.radius=radius;base=f.create(ping,0);network=base instanceof Arena.Neural;tap=new DuelEnv.Player("wtap",0,ping,0);
        }
        Probe(Probe p){
            super(p.style,0,p.rtt,0);base=p.base.copy();tap=p.tap.copy();network=p.network;radius=p.radius;
            heads=p.heads;entry=p.entry;choice=p.choice;start=p.start;initialized=p.initialized;cx=p.cx;cz=p.cz;at=p.at;view=p.view;firstClick=p.firstClick;
        }
        @Override Probe copy(){return new Probe(this);}
        @Override public PeriodicComboClock.Control input(PeriodicComboClock.Observation o){
            var q=o.own();var a=base.input(o);var w=tap.input(o);heads++;
            if(!initialized){cx=(q.x()+o.targetX())*.5;cz=(q.z()+o.targetZ())*.5;initialized=true;}
            double dx=q.x()-cx,dz=q.z()-cz,rad=Math.hypot(dx,dz);
            double outward=rad>0?(dx*q.vx()+dz*q.vz())/rad:0;
            double tx=o.targetX()-q.x(),tz=o.targetZ()-q.z(),gap=Math.max(1e-9,Math.hypot(tx,tz));
            double toward=(-dx*tx-dz*tz)/gap,lateral=(-dx*tz+dz*tx)/gap;
            boolean combat=Double.isFinite(o.lastOwnHurtTime())||Double.isFinite(o.lastTargetHurtTime());
            if(entry==0 && combat && rad>=radius && outward>=.02){
                entry=heads;at=o.time();
                view=List.of(rad,outward,toward,lateral,o.nearest(),q.y(),q.vy(),(double)(q.onGround()?1:0),
                        (double)a.key(),(double)a.strafe(),(double)(a.click()?1:0),(double)(a.jump()?1:0));
            }
            if(choice>0 && heads>=start){
                int age=heads-start;
                if(choice==1)a=w;
                else if(age<(choice==5?6:choice==8?1:3)){
                    int key=a.key();float strafe=a.strafe();boolean jump=a.jump();
                    if(choice>=2&&choice<=5){
                        int direction=choice==3?-1:1;
                        strafe=choice==4?0:(float)(direction*Math.signum(lateral)*.98);
                    }else if(choice==6||choice==7){
                        double sense=choice==6?1:-1;
                        // Pick the nearest of eight compass directions using ordinary W/S/A/D and Ctrl.
                        double maximum=Math.max(Math.abs(toward),Math.abs(lateral));
                        if(maximum>0){
                            key=Math.abs(toward)<.4142135624*maximum?0:sense*toward>=0?2:3;
                            strafe=Math.abs(lateral)<.4142135624*maximum?0:(float)(sense*Math.signum(lateral)*.98);
                        }
                    }else jump=q.onGround();
                    a=new PeriodicComboClock.Control(key,strafe,a.click(),jump);
                }
            }
            if(sampleSink!=null && network && heads>=firstSample && (heads-firstSample)%sampleStride==0 && sampleSink.size()<sampleLimit){
                double[] inputs=((Arena.Neural)base).net.features();
                double[] example=Arrays.copyOf(inputs,inputs.length+1);
                example[inputs.length]=Math.signum(a.strafe());
                sampleSink.add(example);
            }
            if(network)((Arena.Neural)base).net.applied(DuelNetwork.pack(a.key(),(int)Math.signum(a.strafe()),a.click(),a.jump()));
            return a;
        }
    }
    static DuelEnv.Game start(DuelEnv e,Arena.Fighter arm,Arena.Fighter foe,boolean seat,double rad){
        return e.new Game(seat,new Probe(arm,e.rtt,rad),foe.create(e.rtt,0));
    }
    static double ownRadius(DuelEnv.Game g){
        var p=(Probe)g.our();var s=g.clock.state;var body=g.first?s.serverA:s.serverB;
        return Math.hypot(body.x()-p.cx,body.z()-p.cz);
    }
    static void play(DuelEnv env,Arena.Fighter arm,Arena.Fighter foe,boolean first,double radius)throws Exception{
        var anchors=new java.util.ArrayList<double[]>();
        if(learning)capture(anchors,1,11,64);
        var scout=start(env,arm,foe,first,radius);scout.finish();var probe=(Probe)scout.our();
        sampleSink=null;
        var row=new LinkedHashMap<String,Object>();row.put("clock",env.clockId);row.put("rtt",env.rtt);
        row.put("arm",arm.name());row.put("opponent",foe.name());row.put("side",first?"a":"b");row.put("radius",radius);
        row.put("base",scout.reward());row.put("end",scout.clock.time());row.put("fresh",List.of(scout.clock.state.hits,scout.clock.state.incomingHits));row.put("head",probe.entry);
        if(learning)row.put("anchors",anchors);
        if(probe.entry>0){
            var g=start(env,arm,foe,first,radius);var p=(Probe)g.our();
            // The ordinary referee samples a fall at server-end. A player head
            // can occur after the body crossed the fall plane but before that sample.
            while(g.clock.time()<=probe.at && g.step<=scout.step){
                double limit=g.step<env.serverEnds.length?Math.min(env.limit,env.serverEnds[g.step]):env.limit;
                if(!g.clock.advanceToBeforeNextHead(first,limit)){g.step++;continue;}
                if(p.heads+1==probe.entry)break;
                if(!g.clock.runHeadInterval(first,limit))g.step++;
            }
            if(p.entry!=0||g.clock.time()!=probe.at||p.heads+1!=probe.entry)
                throw new AssertionError("Wrong pre-input boundary "+row+" at "+g.clock.time()+" expected "+probe.at);
            if(g.winner()>=0){
                row.put("ineligible","server body already below fall plane before this input");
                row.put("at",probe.at);row.put("local",probe.view);System.out.println(Json.encode(row));return;
            }
            row.put("at",probe.at);row.put("local",probe.view);
            String initial=TradeWords.stateHash(g);row.put("fork_sha256",initial);
            var results=new LinkedHashMap<String,Object>();
            var examples=new LinkedHashMap<String,List<double[]>>();
            for(int i=0;i<WORDS.size();i++){
                if(learning&&i!=0&&i!=1&&i!=5)continue;
                var b=env.new Game(g,g.a.copy(),g.b.copy());var pilot=(Probe)b.our();
                var shown=new java.util.ArrayList<double[]>();
                if(learning&&i!=1&&pilot.network)capture(shown,probe.entry,1,6);
                if(i==0&&!initial.equals(TradeWords.stateHash(b)))throw new AssertionError("Fork differs");
                if(i!=0){pilot.choice=i;pilot.start=probe.entry;}
                b.trace();
                while(b.clock.time()<probe.at+500&&b.advance()){}
                double radial=ownRadius(b),elapsed=b.clock.time()-probe.at;
                var hits=b.hits.list.stream().filter(h->h.at()<=probe.at+500).map(h->List.of(h.at()-probe.at,h.sideA()==first,h.upgrade())).toList();
                b.finish();sampleSink=null;
                if(learning&&i!=1)examples.put(WORDS.get(i),shown);
                if(!probe.view.equals(pilot.view))throw new AssertionError("Different decision observation");
                if(i==0){
                    String hash=TradeWords.stateHash(b);
                    if(!hash.equals(TradeWords.stateHash(scout)))throw new AssertionError("No-op diverged");
                    row.put("unchanged_terminal_sha256",hash);
                }
                results.put(WORDS.get(i),List.of(b.reward(),b.clock.time(),radial,elapsed,hits));
            }
            if(!initial.equals(TradeWords.stateHash(g)))throw new AssertionError("Mutated common prefix");
            row.put("branches",results);if(learning)row.put("examples",examples);
        }
        System.out.println(Json.encode(row));
    }
    public static void main(String[] args)throws Exception{
        var arms=Matches.roster(Path.of(args[0]));var foes=Matches.roster(Path.of(args[1]));
        double rad=Double.parseDouble(args[2]);
        learning=args[3].equals("--learn");
        for(var e:Arena.envs(Arrays.stream(args,learning?4:3,args.length).map(Path::of).toList(),"mmc",true)){
            e.initial.timing.mouseInput=true;
            for(var arm:arms)for(var foe:foes)for(boolean side:new boolean[]{true,false})play(e,arm,foe,side,rad);
        }
    }
}
