import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.GZIPInputStream;
import lab.SumoJumpPolicy;

/** Forkable head-on 1.8.9 environment. No recorded combat outcomes enter the plant. */
final class DuelEnv {
    static final int LIMIT = 120_000;
    record Tape(String id, List<PeriodicComboClock.Scheduled> events, double[] serverEnds) {
        static Tape read(Path path, int limit) throws IOException {
            var events = new ArrayList<PeriodicComboClock.Scheduled>();
            var ends = new ArrayList<Double>();
            try (var in = new BufferedReader(new InputStreamReader(new GZIPInputStream(Files.newInputStream(path))))) {
                String side = null, line;
                long offset = 0, period = 0, until = (limit + 100L) * 1_000_000;
                while ((line = in.readLine()) != null) {
                    String[] v = line.split(" ");
                    if (v.length == 3) {
                        side = v[0]; offset = Long.parseLong(v[1]); period = Long.parseLong(v[2]);
                        if (period<=0) throw new IOException("Invalid calendar period");
                        continue;
                    }
                    var kind = PeriodicComboClock.Kind.valueOf(v[1]);
                    for (long t = offset + Long.parseLong(v[0]); t <= until; t += period) {
                        events.add(new PeriodicComboClock.Scheduled(t,events.size(),kind,!"b".equals(side)));
                        if (kind==PeriodicComboClock.Kind.SERVER_END && t<=limit*1_000_000L) ends.add(t/1e6);
                    }
                }
            }
            return new Tape(path.getFileName().toString().split("\\.")[0], events, ends.stream().mapToDouble(x->x).sorted().toArray());
        }
    }
    static final class Player implements PeriodicComboClock.Policy {
        final String style;
        final int rtt, wake;
        int mask, jumps;
        double handled=Double.NEGATIVE_INFINITY, releaseUntil=Double.NEGATIVE_INFINITY;
        double firstClick=Double.NaN;
        Player(String style,int mask,int rtt,int wake){this.style=style;this.mask=mask;this.rtt=rtt;this.wake=wake;}
        Player copy(){Player b=new Player(style,mask,rtt,wake);b.jumps=jumps;b.handled=handled;b.releaseUntil=releaseUntil;b.firstClick=firstClick;return b;}
        public PeriodicComboClock.Control input(PeriodicComboClock.Observation o){
            long now=(long)o.time();
            double cue=o.lastTargetHurtTime();
            if (!style.equals("hold") && cue>handled &&
                (!style.equals("trade") || o.lastOwnHurtTime()>=cue-200 || now>=cue+150+rtt/2.0)) {
                handled=cue;releaseUntil=now+50;
            }
            boolean go=now>=releaseUntil;
            int state=SumoJumpPolicy.state(o.own().onGround(),go,o.targetX()-o.own().x(),o.targetZ()-o.own().z(),
                                          o.targetY()-o.own().y(),o.own().vx(),o.own().vz());
            boolean jump=SumoJumpPolicy.jump(mask,state);
            if(jump)jumps++;
            return new PeriodicComboClock.Control(go?2:0,0,now>=wake,jump);
        }
        public boolean injected(PeriodicComboClock.Observation o,double t){if(Double.isNaN(firstClick))firstClick=t;return true;}
    }
    record Hit(double at, boolean sideA, boolean upgrade) {}
    static final class Hits implements PeriodicComboClock.Audit {
        int freshA,freshB,acceptedA,acceptedB;
        final List<Hit> list=new ArrayList<>();
        Hits(){}
        Hits(PeriodicComboFightModel.State s){update(s);}
        void update(PeriodicComboFightModel.State s){freshA=s.hits;freshB=s.incomingHits;acceptedA=s.acceptedA;acceptedB=s.acceptedB;}
        public void event(double t,String event,PeriodicComboFightModel.State s){
            if(!event.equals("SERVER_DRAIN") || (acceptedA==s.acceptedA && acceptedB==s.acceptedB))return;
            var batch=new TreeMap<Long,Hit>();
            if(s.hits>freshA)batch.put(s.lastFreshOrderA,new Hit(t,true,false));
            if(s.incomingHits>freshB)batch.put(s.lastFreshOrderB,new Hit(t,false,false));
            if(s.acceptedA-acceptedA>s.hits-freshA)batch.put(s.lastAcceptedOrderA,new Hit(t,true,true));
            if(s.acceptedB-acceptedB>s.incomingHits-freshB)batch.put(s.lastAcceptedOrderB,new Hit(t,false,true));
            if(batch.size()!=s.acceptedA+s.acceptedB-acceptedA-acceptedB)throw new IllegalStateException("Unresolved within-drain accepted hit order");
            list.addAll(batch.values());update(s);
        }
    }
    final String clockId,profile;
    final boolean sumo;
    final int rtt,limit;
    final double[] serverEnds;
    final PeriodicComboClock initial;
    DuelEnv(Tape tape,String profile,int rtt,boolean sumo){
        this.clockId=tape.id;this.profile=profile;this.rtt=rtt;this.sumo=sumo;limit=sumo?LIMIT:20000;serverEnds=tape.serverEnds;
        var config=BoxingModel.config(profile,rtt);config.sumo=sumo;
        if(sumo)config.sumoArena(4096,12);
        var t=new PeriodicComboClock.Timing();
        t.inputMode=PeriodicComboClock.InputMode.NATIVE_QUEUED;
        t.uplinkA=t.uplinkB=t.downlinkA=t.downlinkB=.35;
        var state=new PeriodicComboFightModel.State(4093,0,4099,0,sumo?12:4);
        Player idle=new Player("wtap",0,rtt,0);
        initial=new PeriodicComboClock(state,config,t,idle,idle,tape.events);
        initial.enableSharedCalendar();
    }
    final class Game {
        final Player a,b; final boolean first; final PeriodicComboClock clock;
        Hits hits; int step;
        Game(boolean first,String opponent,int mask){
            this.first=first;
            var our=new Player("wtap",mask,rtt,0);
            var other=new Player(opponent,0,rtt,sumo?0:2000);
            a=first?our:other;b=first?other:our;
            clock=initial.fork(a,b);
        }
        Game(Game prefix,int mask){
            first=prefix.first;a=prefix.a.copy();b=prefix.b.copy();our().mask=mask;
            clock=prefix.clock.fork(a,b);step=prefix.step;
        }
        Player our(){return first?a:b;}
        void trace(){hits=new Hits(clock.state);clock.setAudit(hits);}
        int winner(){
            if(!sumo)return -1;
            var s=clock.state;double boundary=clock.config.platform.floorTopY()-.5;
            boolean fa=s.serverAy<boundary,fb=s.serverBy<boundary;
            return fa?(fb?0:2):(fb?1:-1);
        }
        boolean advance(){
            if(clock.time()>=limit || winner()>=0)return false;
            double next=step<serverEnds.length?Math.min(limit,serverEnds[step++]):limit;
            clock.runUntil(next);return true;
        }
        void finish(){while(advance()){}}
        double reward(){int w=winner();return w<=0?.5:(w==(first?1:2)?1:0);}
        Map<String,Object> result(){
            return Map.of("reward",reward(),"winner",winner(),"end",clock.time(),"jumps",our().jumps,
                "active",Math.max(a.firstClick,b.firstClick),"hits",hits.list.stream().map(h->List.of(h.at,h.sideA,h.upgrade)).toList());
        }
    }
    Game start(boolean first,String opponent,int mask){return new Game(first,opponent,mask);}
}
