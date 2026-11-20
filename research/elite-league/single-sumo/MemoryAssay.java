import java.io.*;
import java.nio.file.*;
import java.util.*;
import lab.DuelNetwork;
import lab.Json;

/** Exact memory-only continuations. Private physical coordinates are used only for offline donor matching. */
final class MemoryAssay {
    static final class Witness extends Arena.Neural {
        record Head(double[] before,double[] features){}
        ArrayDeque<Head> tail=new ArrayDeque<>();
        double ownHurt=Double.NEGATIVE_INFINITY,foeHurt=Double.NEGATIVE_INFINITY,cut=Double.NaN;
        int cues,heads;
        Witness(Arena.Model model,int own,int other,long seed){super(model,own,other);net.league().seed(seed);}
        @Override public PeriodicComboClock.Control input(PeriodicComboClock.Observation o){
            double[] prior=net.league().memory();
            var action=super.input(o);heads++;
            if(tail.size()==10)tail.removeFirst();
            tail.addLast(new Head(prior,net.features()));
            if(o.lastOwnHurtTime()>ownHurt){ownHurt=o.lastOwnHurtTime();cues++;}
            if(o.lastTargetHurtTime()>foeHurt){foeHurt=o.lastTargetHurtTime();cues++;}
            if(cues>=3 && Double.isNaN(cut))cut=o.time();
            return action;
        }
    }
    record World(DuelEnv e,DuelEnv.Game g,Witness actor){}
    static World start(LeagueWorker.Trial t){
        var e=new DuelEnv(t.base(),t.seat()?t.own():t.other(),t.seat()?t.other():t.own());
        var a=new Witness(t.actor().model(),t.own(),t.other(),t.aSeed());
        return new World(e,e.new Game(t.seat(),a,LeagueWorker.player(t.foe(),t.other(),t.own(),e,t.bSeed())),a);
    }
    static void to(World w,double time){
        while(w.g.step<w.e.serverEnds.length && w.e.serverEnds[w.g.step]<=time && w.g.advance()){}
        w.g.clock.runUntil(time);
    }
    static double age(double now,double hurt){return Double.isFinite(hurt)?now-hurt:1000000;}
    static double[] context(World w){
        boolean seat=w.g.first;var obs=w.g.clock.observation(seat);var other=w.g.clock.observation(!seat);
        var q=obs.own();double dx=obs.targetX()-q.x(),dz=obs.targetZ()-q.z(),d=Math.hypot(dx,dz);
        double fx=dx/Math.max(d,1e-8),fz=dz/Math.max(d,1e-8);
        return new double[]{w.actor.cut,age(w.actor.cut,w.actor.ownHurt),age(w.actor.cut,w.actor.foeHurt),d,
            q.vx()*fx+q.vz()*fz,other.own().vx()*fx+other.own().vz()*fz,q.onGround()?1:0,other.own().onGround()?1:0};
    }
    static final Map<String,Arena.Fighter> field=new HashMap<>();
    static Arena.Fighter resolve(String k)throws Exception {
        if(!field.containsKey(k))field.put(k,LeagueWorker.fighter(k));return field.get(k);
    }
    static LeagueWorker.Trial trial(String[] p)throws Exception{
        return new LeagueWorker.Trial(resolve(p[0]),resolve(p[1]),LeagueWorker.clock(p[2]),p[3].equals("a"),
                    Integer.parseInt(p[4]),Integer.parseInt(p[5]),Long.parseLong(p[6]),Long.parseLong(p[7]));
    }
    static double[] parse(String s){return Arrays.stream(s.split(",")).mapToDouble(Double::parseDouble).toArray();}
    static void catalog(LeagueWorker.Trial t,Map<String,Object> out){
        var run=start(t);run.g.trace();run.g.finish();
        out.put("outcome",run.g.reward());out.put("time",run.g.clock.time());out.put("heads",run.actor.heads);
        out.put("fresh",run.g.hits.list.stream().filter(h->!h.upgrade()).count());
        if(Double.isNaN(run.actor.cut))return;
        var w=start(t);to(w,run.actor.cut);
        if(w.actor.cut!=run.actor.cut || w.actor.tail.size()!=10)throw new AssertionError("Wrong cue fork");
        out.put("context",context(w));out.put("donor",w.actor.tail.getFirst().before());
    }
    static final class Measured extends Arena.Neural {
        final ArrayList<Integer> actions=new ArrayList<>();
        Measured(Arena.Neural n){super(n);}
        @Override public PeriodicComboClock.Control input(PeriodicComboClock.Observation o){
            var c=super.input(o);
            if(actions.size()<10)actions.add(DuelNetwork.pack(c.key(),(int)Math.signum(c.strafe()),c.click(),c.jump()));
            return c;
        }
    }
    static Map<String,Object> finishBranch(World w,double[] donor,String arm,boolean hash)throws Exception{
        var measured=new Measured(w.actor);
        var b=w.e.new Game(w.g,w.g.first?measured:w.g.a.copy(),w.g.first?w.g.b.copy():measured);var net=measured.net;
        if(donor!=null){
            net.league().memory(donor);
            if(!arm.equals("zero")){
                for(var h:w.actor.tail)net.league().forward(h.features(),new double[5]);
                if(arm.equals("own-replay")&&!Arrays.equals(net.league().memory(),w.actor.net.league().memory()))
                    throw new AssertionError("The ten-head replay changed its own memory");
            }
        }
        b.trace();b.finish();
        var row=new LinkedHashMap<String,Object>();
        row.put("score",b.reward());row.put("first",measured.actions.isEmpty()?-1:measured.actions.get(0));
        row.put("actions",measured.actions);row.put("end",b.clock.time());
        row.put("next_fresh",b.hits.list.stream().filter(h->!h.upgrade()).findFirst().map(h->h.sideA()==b.first?1:-1).orElse(0));
        if(hash)row.put("hash",FullClockHash.digest(b.clock));
        return row;
    }
    static void forks(LeagueWorker.Trial t,String[] p,Map<String,Object> out)throws Exception{
        var w=start(t);to(w,Double.parseDouble(p[8]));
        if(!Double.isFinite(w.actor.cut)||w.actor.cut!=Double.parseDouble(p[8]))throw new AssertionError("Invalid fork");
        boolean verify=p[11].equals("1");
        var real=finishBranch(w,null,"intact",verify);
        out.put("intact",real);
        var replay=finishBranch(w,w.actor.tail.getFirst().before(),"own-replay",verify);
        if(!replay.equals(real))throw new AssertionError("Unchanged continuation diverged");
        out.put("zero",finishBranch(w,new double[32],"zero",false));
        out.put("same",finishBranch(w,parse(p[9]),"same",false));
        out.put("opposite",finishBranch(w,parse(p[10]),"opposite",false));
    }
    public static void main(String[] args)throws Exception{
        LeagueWorker.load();
        try(var reader=Files.newBufferedReader(Path.of(args[1]));var writer=Files.newBufferedWriter(Path.of(args[2]))){
            String s;while((s=reader.readLine())!=null){
                var p=s.split("\t");var t=trial(p);var out=new LinkedHashMap<String,Object>();
                if(args[0].equals("catalog"))catalog(t,out);else forks(t,p,out);
                writer.write(Json.encode(out));writer.newLine();
            }
        }
    }
}
