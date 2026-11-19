import java.nio.file.*;
import java.util.*;
import lab.DuelNetwork;
import lab.PairedExperts;
import lab.Json;

/** Causal switch/no-switch comparisons at the last server boundary before the opponent changes tactics. */
final class AdaptationBranches {
    static final class Pilot extends DuelEnv.Player {
        DuelNetwork brain;
        DuelNetwork[] parts=new DuelNetwork[2];
        DuelEnv.Player wt;
        int forced=-1,alternateHeads,totalHeads;
        double firstChange=Double.NaN;

        Pilot(String encoded,int rtt){
            super("pair-branch",0,rtt,0);
            brain=DuelNetwork.decode(encoded);float[] w=brain.weights();
            if(brain.family()!=DuelNetwork.Family.PAIR)throw new IllegalArgumentException("Expected a paired controller");
            wt=new DuelEnv.Player("wtap",0,rtt,0);
            for(int i=0;i<2;i++){
                var type=PairedExperts.EXPERTS[(int)w[i+1]];
                int start=PairedExperts.HEADER+PairedExperts.GATE+i*PairedExperts.BLOCK;
                parts[i]=new DuelNetwork(type,Arrays.copyOfRange(w,start,start+DuelNetwork.size(type)));
            }
        }
        Pilot(Pilot p){
            super("pair-branch",0,p.rtt,0);brain=p.brain.copy();wt=p.wt.copy();
            for(int i=0;i<2;i++)parts[i]=p.parts[i].copy();
            firstClick=p.firstClick;forced=p.forced;alternateHeads=p.alternateHeads;totalHeads=p.totalHeads;firstChange=p.firstChange;
        }
        @Override Pilot copy(){return new Pilot(this);}
        int step(DuelNetwork n,PeriodicComboClock.Observation o){
            var q=o.own();
            return n.step(q.x(),q.y(),q.z(),o.targetX(),o.targetY(),o.targetZ(),q.vx(),q.vy(),q.vz(),q.onGround(),
                          o.sprint(),o.time()-o.lastOwnHurtTime(),o.time()-o.lastTargetHurtTime(),rtt);
        }
        @Override public PeriodicComboClock.Control input(PeriodicComboClock.Observation o){
            int active=step(brain,o),left=step(parts[0],o),right=step(parts[1],o);var plain=wt.input(o);
            int baseline=DuelNetwork.pack(plain.key(),(int)Math.signum(plain.strafe()),plain.click(),plain.jump());
            if(active!=(brain.pair().selected()==0?left:right))throw new AssertionError("Expert not primed on realized controls");
            int chosen=forced==0?left:forced==1?right:forced==2?baseline:active;
            brain.applied(chosen);for(var n:parts)n.applied(chosen);
            if(brain.pair().switches()>0 && Double.isNaN(firstChange))firstChange=o.time();
            totalHeads++;alternateHeads+=brain.pair().selected();
            return new PeriodicComboClock.Control(DuelNetwork.key(chosen),.98f*DuelNetwork.side(chosen),DuelNetwork.click(chosen),DuelNetwork.jump(chosen));
        }
    }
    static Pilot rival(DuelEnv.Game g){return (Pilot)(g.first?g.b:g.a);}
    static Map<String,Object> one(DuelEnv e,boolean seat,String arm,Path source)throws Exception{
        var us=new Pilot(arm,e.rtt);var other=new Pilot(Files.readString(source).trim(),e.rtt);
        DuelEnv.Game g=e.new Game(seat,us,other),prefix=null;
        while(g.winner()<0 && g.clock.time()<e.limit){
            var previous=e.new Game(g,g.a.copy(),g.b.copy());
            if(!g.advance())break;
            if(other.brain.pair().switches()>0){prefix=previous;break;}
        }
        g.finish();
        var row=new LinkedHashMap<String,Object>();
        row.put("clock",e.clockId);row.put("side",seat?"a":"b");row.put("rtt",e.rtt);row.put("opponent",source.getFileName().toString());
        row.put("uninterrupted",g.reward());row.put("switch_seen",prefix!=null);
        if(prefix==null)return row;
        row.put("fork_ms",prefix.clock.time());row.put("switch_ms",other.firstChange);row.put("prefix_hash",FullClockHash.digest(prefix.clock));
        var outcomes=new LinkedHashMap<String,Object>();
        for(int theirs:new int[]{-1,0})for(int ours:new int[]{-1,0,1,2}){
            var b=e.new Game(prefix,prefix.a.copy(),prefix.b.copy());
            var mine=(Pilot)b.our();mine.forced=ours;rival(b).forced=theirs;
            int before=mine.alternateHeads,heads=mine.totalHeads,switches=mine.brain.pair().switches();
            b.finish();
            if(ours<0&&theirs<0 && (!FullClockHash.digest(b.clock).equals(FullClockHash.digest(g.clock))||b.reward()!=g.reward()))
                throw new AssertionError("Unchanged full-state fork diverged");
            var key=(theirs<0?"switch/":"stay/")+(ours<0?"adaptive":ours==2?"wtap":"component-"+ours);
            outcomes.put(key,List.of(b.reward(),b.clock.time(),mine.alternateHeads-before,mine.totalHeads-heads,mine.brain.pair().switches()-switches));
        }
        row.put("outcomes",outcomes);return row;
    }
    public static void main(String[] args)throws Exception{
        String arm=Files.readString(Path.of(args[0])).trim();var foes=Files.readAllLines(Path.of(args[1])).stream().map(Path::of).toList();
        for(Path tape:Arrays.stream(args,2,args.length).map(Path::of).toList())for(int rtt:new int[]{0,80}){
            var e=new DuelEnv(DuelEnv.Tape.read(tape,DuelEnv.LIMIT),"mmc",rtt,true);e.initial.timing.mouseInput=true;
            for(var opponent:foes)for(boolean seat:new boolean[]{true,false})System.out.println(Json.encode(one(e,seat,arm,opponent)));
        }
    }
}
