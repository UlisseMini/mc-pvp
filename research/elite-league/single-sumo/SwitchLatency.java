import java.nio.file.*;
import java.util.*;
import lab.*;

/** Compare switch/stay duels from identical spawns and randomness through the first observable change. */
final class SwitchLatency {
    record Frame(double time,double[] features,int buttons){}
    static final class Track extends Arena.Neural {
        final ArrayList<Frame> heads=new ArrayList<>(); final boolean actor;
        Track(Arena.Model m,int own,int other,long seed,boolean actor){
            super(m,own,other);if(net.league()!=null)net.league().seed(seed);this.actor=actor;
        }
        public PeriodicComboClock.Control input(PeriodicComboClock.Observation o){
            var c=super.input(o);
            heads.add(new Frame(o.time(),actor?net.features():null,DuelNetwork.pack(c.key(),(int)Math.signum(c.strafe()),c.click(),c.jump())));return c;
        }
    }
    record Run(DuelEnv.Game game,Track actor,Track opponent){}
    static Run run(LeagueWorker.Trial t){
        var env=new DuelEnv(t.base(),t.seat()?t.own():t.other(),t.seat()?t.other():t.own());
        var a=new Track(t.actor().model(),t.own(),t.other(),t.aSeed(),true);
        var b=new Track(t.foe().model(),t.other(),t.own(),t.bSeed(),false);
        var game=env.new Game(t.seat(),a,b);game.trace();game.finish();return new Run(game,a,b);
    }
    static double first(List<Frame> left,List<Frame> right,boolean buttons){
        for(int i=0;i<Math.min(left.size(),right.size());i++){
            Frame a=left.get(i),b=right.get(i);if(a.time!=b.time)throw new AssertionError("Input clock changed");
            if(buttons?a.buttons!=b.buttons:!Arrays.equals(a.features,b.features))return a.time;
        }
        return Double.NaN;
    }
    public static void main(String[] args)throws Exception{
        LeagueWorker.load();
        try(var in=Files.newBufferedReader(Path.of(args[0]));var out=Files.newBufferedWriter(Path.of(args[1]))){
            String row;while((row=in.readLine())!=null){
                var stay=run(MemoryAssay.trial(row.split("\t")));var change=run(MemoryAssay.trial(in.readLine().split("\t")));
                double theirs=first(stay.opponent.heads,change.opponent.heads,true);
                double cue=first(stay.actor.heads,change.actor.heads,false),ours=first(stay.actor.heads,change.actor.heads,true);
                if(Double.isFinite(ours)&&(!Double.isFinite(cue)||ours<cue))throw new AssertionError("Action changed before any observable cue");
                if(Double.isFinite(cue)&&(!Double.isFinite(theirs)||cue<theirs))throw new AssertionError("Future style identifier leaked");
                var result=new LinkedHashMap<String,Object>();result.put("opponent_changed_ms",theirs);result.put("locally_visible_ms",cue);
                result.put("own_buttons_changed_ms",ours);result.put("response_after_visible_ms",ours-cue);
                for(var pair:List.of(Map.entry("stay",stay),Map.entry("switch",change))){
                    var g=pair.getValue().game;result.put(pair.getKey()+"_score",g.reward());
                    result.put(pair.getKey()+"_next_hit",g.hits.list.stream().filter(h->!h.upgrade()&&h.at()>=cue)
                                    .findFirst().map(h->h.sideA()==g.first?1:-1).orElse(0));
                }
                out.write(Json.encode(result));out.newLine();
            }
        }
    }
}
