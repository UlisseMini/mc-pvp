import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.GZIPInputStream;
import lab.SumoJumpPolicy;

/** Forkable head-on 1.8.9 environment. No recorded combat outcomes enter the plant. */
final class DuelEnv {
    static final int LIMIT = 120_000;
    record Tape(String id, List<PeriodicComboClock.Scheduled> events, double[] serverEnds, boolean trackerAFirst) {
        static Tape read(Path path, int limit) throws IOException {
            var events = new ArrayList<PeriodicComboClock.Scheduled>();
            var ends = new ArrayList<Double>();
            boolean trackerAFirst=true;
            try (var in = new BufferedReader(new InputStreamReader(new GZIPInputStream(Files.newInputStream(path))))) {
                String side = null, line;
                int drains=0, entities=0, trackers=0;
                long offset = 0, period = 0, until = (limit + 100L) * 1_000_000;
                while ((line = in.readLine()) != null) {
                    String[] v = line.split(" ");
                    if(v[0].equals("#")){
                        if(v.length!=3 || !v[1].equals("tracker_first") || !(v[2].equals("a") || v[2].equals("b")))throw new IOException("Unknown calendar header");
                        trackerAFirst=v[2].equals("a");continue;
                    }
                    if (v.length == 3) {
                        side = v[0]; offset = Long.parseLong(v[1]); period = Long.parseLong(v[2]);
                        if (period<=0) throw new IOException("Invalid calendar period");
                        continue;
                    }
                    var kind = PeriodicComboClock.Kind.valueOf(v[1]);
                    if(kind==PeriodicComboClock.Kind.SERVER_DRAIN)drains++;
                    if(kind==PeriodicComboClock.Kind.SERVER_ENTITY)entities++;
                    if(kind==PeriodicComboClock.Kind.SERVER_TRACK)trackers++;
                    for (long t = offset + Long.parseLong(v[0]); t <= until; t += period) {
                        events.add(new PeriodicComboClock.Scheduled(t,events.size(),kind,!"b".equals(side)));
                        if (kind==PeriodicComboClock.Kind.SERVER_END && t<=limit*1_000_000L) ends.add(t/1e6);
                    }
                }
                if(drains==0 || drains!=entities || drains!=trackers)
                    throw new IOException("Expected one overworld entity and tracker stage per server drain in "+path);
            }
            return new Tape(path.getFileName().toString().split("\\.")[0], events, ends.stream().mapToDouble(x->x).sorted().toArray(),trackerAFirst);
        }
    }
    static class Player implements PeriodicComboClock.Policy {
        String style;
        int waitMs=150, tradeWindow=200;
        boolean impact;
        final int rtt, wake;
        int mask, jumps;
        double handled=Double.NEGATIVE_INFINITY, releaseUntil=Double.NEGATIVE_INFINITY;
        double firstClick=Double.NaN;
        Player(String style,int mask,int rtt,int wake){this.style=style;this.mask=mask;this.rtt=rtt;this.wake=wake;}
        // Bits 0..15 select grounded jump states. The high word selects an
        // existing native movement preset; zero is the ordinary W-tapper.
        void select(int code){
            int kind=code>>>16;mask=code&65535;
            if(kind>16)throw new IllegalArgumentException("Unknown movement preset");
            style=kind==0?"wtap":kind==1?"hold":"trade";
            if(kind>=2){
                waitMs=new int[]{50,100,150,250,400}[(kind-2)/3];
                tradeWindow=new int[]{0,100,200}[(kind-2)%3];
            }
        }
        public void received(PeriodicComboClock.PacketObservation p){
            if(style.equals("reset") && p.kind()==PeriodicComboClock.ReceivedKind.OWN_VELOCITY)impact=p.vx()!=0||p.vz()!=0;
        }
        Player copy(){Player b=new Player(style,mask,rtt,wake);b.jumps=jumps;b.waitMs=waitMs;b.tradeWindow=tradeWindow;b.impact=impact;b.handled=handled;b.releaseUntil=releaseUntil;b.firstClick=firstClick;return b;}
        public PeriodicComboClock.Control input(PeriodicComboClock.Observation o){
            long now=(long)o.time();
            double cue=o.lastTargetHurtTime();
            if (!style.equals("hold") && cue>handled &&
                (!style.equals("trade") || o.lastOwnHurtTime()>=cue-tradeWindow || now>=cue+waitMs+rtt/2.0)) {
                handled=cue;releaseUntil=now+50;
            }
            boolean go=now>=releaseUntil;
            int state=SumoJumpPolicy.state(o.own().onGround(),go,o.targetX()-o.own().x(),o.targetZ()-o.own().z(),
                                          o.targetY()-o.own().y(),o.own().vx(),o.own().vz());
            boolean jump=SumoJumpPolicy.jump(mask,state)||(impact&&o.own().onGround());impact=false;
            if(jump)jumps++;
            return new PeriodicComboClock.Control(go?2:0,0,now>=wake,jump);
        }
        public boolean injected(PeriodicComboClock.Observation o,double t){if(Double.isNaN(firstClick))firstClick=t;return true;}
    }
    record Hit(double at, boolean sideA, boolean upgrade) {}
    /** Read-only score of delays from the victim becoming fresh-eligible to the next own fresh hit. */
    static final class FreshWindows {
        boolean pending;
        double ready = Double.NaN;
        final List<Double> delays = new ArrayList<>();
        void entity(double time, int immunity, int threshold) {
            if (pending && Double.isNaN(ready) && immunity <= threshold) ready = time;
        }
        void resume(FreshWindows previous) {
            pending = previous.pending;
            ready = previous.ready;
        }
        void hit(double time) {
            if (Double.isFinite(ready)) delays.add(time - ready);
            pending = true;
            ready = Double.NaN;
        }
        Map<String,Object> result(double end) {
            return Map.of("delays_ms",delays,"waiting_for_fresh",pending && Double.isNaN(ready),
                          "terminal_open_ms",Double.isFinite(ready) ? end-ready : -1.0);
        }
    }
    static final class Hits implements PeriodicComboClock.Audit {
        int freshA,freshB,acceptedA,acceptedB;
        final List<Hit> list=new ArrayList<>();
        final FreshWindows a=new FreshWindows(), b=new FreshWindows();
        Hits(){}
        Hits(PeriodicComboFightModel.State s){update(s);}
        void update(PeriodicComboFightModel.State s){freshA=s.hits;freshB=s.incomingHits;acceptedA=s.acceptedA;acceptedB=s.acceptedB;}
        public void event(double t,String event,PeriodicComboFightModel.State s){
            if(event.equals("SERVER_ENTITY")){
                a.entity(t,s.resistant,s.freshThreshold);
                b.entity(t,s.ownResistant,s.freshThreshold);
            }
            if(!event.equals("SERVER_DRAIN") || (acceptedA==s.acceptedA && acceptedB==s.acceptedB))return;
            var batch=new TreeMap<Long,Hit>();
            if(s.hits>freshA)batch.put(s.lastFreshOrderA,new Hit(t,true,false));
            if(s.incomingHits>freshB)batch.put(s.lastFreshOrderB,new Hit(t,false,false));
            if(s.acceptedA-acceptedA>s.hits-freshA)batch.put(s.lastAcceptedOrderA,new Hit(t,true,true));
            if(s.acceptedB-acceptedB>s.incomingHits-freshB)batch.put(s.lastAcceptedOrderB,new Hit(t,false,true));
            if(batch.size()!=s.acceptedA+s.acceptedB-acceptedA-acceptedB)throw new IllegalStateException("Unresolved within-drain accepted hit order");
            for(Hit hit:batch.values())if(!hit.upgrade())(hit.sideA()?a:b).hit(t);
            list.addAll(batch.values());update(s);
        }
    }
    final String clockId,profile;
    final boolean sumo;
    final int rtt,otherRtt,limit;
    final double[] serverEnds;
    final PeriodicComboClock initial;
    DuelEnv(Tape tape,String profile,int rtt,boolean sumo){this(tape,profile,rtt,sumo,29,true);}
    DuelEnv(Tape tape,String profile,int rtt,boolean sumo,int trackerStart,boolean solidRim){
        this(tape,profile,rtt,rtt,sumo,trackerStart,solidRim,10,6,0);
    }
    DuelEnv(Tape tape,String profile,int rtt,int otherRtt,boolean sumo,int trackerStart,boolean solidRim,int diameter,double gap,double lateralOffset){
        this.clockId=tape.id;this.profile=profile;this.rtt=rtt;this.otherRtt=otherRtt;this.sumo=sumo;limit=sumo?LIMIT:20000;serverEnds=tape.serverEnds;
        var config=BoxingModel.config(profile,rtt);config.otherRtt=otherRtt;config.sumo=sumo;config.sumoDiameter=diameter;
        if(sumo){
            config.sumoArena(4096,12);
            if(solidRim){config.solidEdge();config.trackerAFirst=tape.trackerAFirst();}
        }
        var t=new PeriodicComboClock.Timing();
        t.inputMode=PeriodicComboClock.InputMode.NATIVE_QUEUED;
        t.uplinkA=t.uplinkB=t.downlinkA=t.downlinkB=.35;
        var state=new PeriodicComboFightModel.State(4096-gap/2,lateralOffset,4096+gap/2,lateralOffset,sumo?12:4);
        state.serverTicks=trackerStart;
        Player idle=new Player("wtap",0,rtt,0);
        initial=new PeriodicComboClock(state,config,t,idle,idle,tape.events);
        initial.enableSharedCalendar();
    }
    final class Game {
        final Player a,b; final boolean first; final PeriodicComboClock clock;
        Hits hits; int step;
        Game(boolean first,String opponent,int mask){
            this(first, legacy(mask), opponent.equals("zest-strafe") ? new StrafeOpponent(rtt) : new Player(opponent,0,rtt,sumo?0:2000));
        }
        Game(boolean first,Player our,Player other){
            this.first=first;
            a=first?our:other;b=first?other:our;
            clock=initial.fork(a,b);
        }
        Game(Game prefix,int mask){
            this(prefix,prefix.a.copy(),prefix.b.copy());our().select(mask);
        }
        Game(Game prefix,Player sideA,Player sideB){
            first=prefix.first;a=sideA;b=sideB;
            clock=prefix.clock.fork(a,b);step=prefix.step;
            if(prefix.hits!=null){
                hits=new Hits(clock.state);
                hits.a.resume(prefix.hits.a);
                hits.b.resume(prefix.hits.b);
            }
        }
        Player our(){return first?a:b;}
        void trace(){if(hits==null)hits=new Hits(clock.state);clock.setAudit(hits); }
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
                "active",Math.max(a.firstClick,b.firstClick),"fresh_windows",(first?hits.a:hits.b).result(clock.time()),"hits",hits.list.stream().map(h->List.of(h.at,h.sideA,h.upgrade)).toList());
        }
    }
    Player legacy(int code){Player p=new Player("wtap",0,rtt,0);p.select(code);return p;}
    Game start(boolean first,String opponent,int mask){return new Game(first,opponent,mask);}
}
