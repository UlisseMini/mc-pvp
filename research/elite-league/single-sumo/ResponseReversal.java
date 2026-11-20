import java.nio.file.*;
import java.util.*;
import lab.*;

/** Prime candidate responses on one realized history, then swap only the buttons controlling the continuation. */
final class ResponseReversal {
    static class Primed extends DuelEnv.Player {
        static final int HEAD_LIMIT=Integer.getInteger("league.commitHeads",0);
        final ArrayList<DuelEnv.Player> options=new ArrayList<>();int choice,cues,heads; int[] observed; boolean measure;
        double tOwn=Double.NEGATIVE_INFINITY,tFoe=Double.NEGATIVE_INFINITY,cut=Double.NaN;
        Primed(int own){super("response-probe",0,own,0);}
        @Override Primed copy(){var c=new Primed(rtt);c.choice=choice;c.cues=cues;c.heads=heads;c.tOwn=tOwn;c.tFoe=tFoe;c.cut=cut;
            for(var p:options)c.options.add(p.copy());return c;}
        public void received(PeriodicComboClock.PacketObservation o){for(var p:options)p.received(o);}
        public boolean injected(PeriodicComboClock.Observation o,double t){
            for(var p:options)p.injected(o,t);return true;
        }
        public PeriodicComboClock.Control input(PeriodicComboClock.Observation o){
            if(o.lastOwnHurtTime()>tOwn){tOwn=o.lastOwnHurtTime();cues++;}
            if(o.lastTargetHurtTime()>tFoe){tFoe=o.lastTargetHurtTime();cues++;}
            heads++;
            if((HEAD_LIMIT>0?heads>=HEAD_LIMIT:cues>=3)&&Double.isNaN(cut))cut=o.time();
            PeriodicComboClock.Control chosen=null;
            int[] current=measure&&observed==null?new int[options.size()]:null;
            for(int i=0;i<options.size();i++){var c=options.get(i).input(o);if(i==choice)chosen=c;
                if(current!=null)current[i]=DuelNetwork.pack(c.key(),(int)Math.signum(c.strafe()),c.click(),c.jump());}
            if(current!=null)observed=current;
            int a=DuelNetwork.pack(chosen.key(),(int)Math.signum(chosen.strafe()),chosen.click(),chosen.jump());
            for(var p:options)if(p instanceof Arena.Neural n)n.net.applied(a);
            return chosen;
        }
    }
    static DuelEnv.Game create(LeagueWorker.Trial t,DuelEnv e,String[] responders)throws Exception{
        var p=new Primed(t.own());p.options.add(LeagueWorker.player(t.actor(),t.own(),t.other(),e,t.aSeed()));
        for(String r:responders)p.options.add(LeagueWorker.player(MemoryAssay.resolve(r),t.own(),t.other(),e,t.aSeed()));
        return e.new Game(t.seat(),p,LeagueWorker.player(t.foe(),t.other(),t.own(),e,t.bSeed()));
    }
    public static void main(String[] args)throws Exception{
        LeagueWorker.load();String[] responses=args[2].split(",");
        try(var out=Files.newBufferedWriter(Path.of(args[1]));var in=Files.newBufferedReader(Path.of(args[0]))){
            String row;while((row=in.readLine())!=null){
                var t=MemoryAssay.trial(row.split("\t"));
                var e=new DuelEnv(t.base(),t.seat()?t.own():t.other(),t.seat()?t.other():t.own());var orig=create(t,e,responses);
                orig.finish();double cut=((Primed)orig.our()).cut;
                var v=new LinkedHashMap<String,Object>();v.put("full",orig.reward());v.put("cut",cut);
                if(Double.isFinite(cut)){
                    var base=create(t,e,responses);
                    while(base.step<e.serverEnds.length && e.serverEnds[base.step]<=cut && base.advance()){}
                    base.clock.runUntil(cut);
                    if(args.length>3 && args[3].equals("target")){
                        Primed a=(Primed)base.our();a.measure=true;
                        while(a.observed==null && base.advance()){}
                        v.put("response_buttons",a.observed);
                    }else for(int i=0;i<=responses.length;i++){
                        var b=e.new Game(base,base.a.copy(),base.b.copy());((Primed)b.our()).choice=i;b.finish();
                        if(i==0&&b.reward()!=orig.reward())throw new AssertionError("Ordinary continuation diverged");
                        v.put(i==0?"intact":responses[i-1],b.reward());
                    }
                }
                out.write(Json.encode(v));out.newLine();
            }
        }
    }
}
