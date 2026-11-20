import java.io.*;
import java.nio.file.*;
import java.util.*;
import lab.DuelNetwork;
import lab.Json;

/** Offline interventions on a legal player's actual controls. No target state enters a deployed policy. */
final class FrozenResponseMechanics {
    record Head(int index,double time,double gap,boolean grounded,int key,boolean gate) {}
    record Position(double at,double x,double z,double tx,double tz) {}
    record Launch(int head,double at) {}
    static final class Pilot extends DuelEnv.Player {
        final DuelEnv.Player actor,wt;
        final String opening;
        final int skipHead;
        final List<Head> heads=new ArrayList<>();
        int count;
        Pilot(Arena.Fighter fighter,int own,int other,String opening,int skipHead) {
            super("observer",0,own,0);
            actor=fighter.create(own,other,0,true,DuelEnv.regulationDiameter(),10);
            wt=PingLeague.WT.create(own,other,0,true,DuelEnv.regulationDiameter(),10);
            this.opening=opening;this.skipHead=skipHead;
        }
        public void received(PeriodicComboClock.PacketObservation p){actor.received(p);wt.received(p);}
        public boolean injected(PeriodicComboClock.Observation o,double t){wt.injected(o,t);return actor.injected(o,t);}
        public PeriodicComboClock.Control input(PeriodicComboClock.Observation o){
            var c=actor.input(o);var baseline=wt.input(o);count++;
            boolean early=!Double.isFinite(o.lastOwnHurtTime())&&!Double.isFinite(o.lastTargetHurtTime());
            int key=c.key();float strafe=c.strafe();boolean click=c.click(),jump=c.jump();
            if(early && opening.equals("wt-movement")){key=baseline.key();strafe=baseline.strafe();}
            if(early && opening.equals("open-gate"))click=true;
            if(count==skipHead)jump=false;
            if(early)heads.add(new Head(count,o.time(),Math.hypot(o.targetX()-o.own().x(),o.targetZ()-o.own().z()),o.own().onGround(),key,click));
            if(actor instanceof Arena.Neural n)n.net.applied(DuelNetwork.pack(key,(int)Math.signum(strafe),click,jump));
            return new PeriodicComboClock.Control(key,strafe,click,jump);
        }
    }
    static final class Trace {
        final List<Position> positions=new ArrayList<>();
        final List<Launch> launches=new ArrayList<>();
        final DuelEnv.Game game;
        final Pilot own,other;
        Trace(Arena.Scenario s,Arena.Fighter actor,String move,String gate,int skip){
            var env=s.env();
            int a=s.first()?env.rtt:env.otherRtt,b=s.first()?env.otherRtt:env.rtt;
            own=new Pilot(actor,a,b,move,skip);other=new Pilot(s.opponent(),b,a,gate,0);
            game=env.new Game(s.first(),own,other);game.trace();var hits=game.hits;
            game.clock.setAudit((at,event,state)->{
                hits.event(at,event,state);
                if(event.equals(s.first()?"MOVEA":"MOVEB")&&(s.first()?state.a:state.b).jumpTicks==10)
                    launches.add(new Launch(own.count,at));
            });
            sample();
            while(game.advance())sample();
        }
        void sample(){var s=game.clock.state;
            positions.add(game.first?new Position(game.clock.time(),s.serverAx,s.serverAz,s.serverBx,s.serverBz):
                new Position(game.clock.time(),s.serverBx,s.serverBz,s.serverAx,s.serverAz));
        }
        Position before(double t){var result=positions.get(0);for(var p:positions){if(p.at()>t)break;result=p;}return result;}
        double displacement(Position origin,double end){
            var q=before(end);double dx=origin.x()-origin.tx(),dz=origin.z()-origin.tz(),length=Math.hypot(dx,dz);
            return ((q.x()-origin.x())*dx+(q.z()-origin.z())*dz)/Math.max(.00001,length);
        }
    }
    static String digest(DuelEnv.Game g)throws Exception{
        return FullClockHash.digest(List.of(FullClockHash.digest(g.clock),g.a,g.b,g.step,g.winner()));
    }
    static void analyze(Arena.Scenario s,Arena.Fighter actor,PrintWriter out,boolean hash)throws Exception{
        var original=new Trace(s,actor,"original","original",0);var g=original.game;
        if(s.game(actor)!=g.reward())throw new AssertionError("Recorder changed live controls");
        var movement=new Trace(s,actor,"wt-movement","original",0);
        var forcedGate=new Trace(s,actor,"original","open-gate",0);
        var r=new LinkedHashMap<String,Object>();
        r.put("arm",actor.name());r.put("clock",s.env().clockId);r.put("side",s.first()?"a":"b");
        r.put("own_rtt",s.first()?s.env().rtt:s.env().otherRtt);r.put("score",g.reward());
        r.put("own_wtap_opening_movement",movement.game.reward());r.put("target_open_gate",forcedGate.game.reward());
        r.put("own_opening_heads",original.own.heads.size());r.put("target_opening_heads",original.other.heads.size());
        r.put("target_near_heads",original.other.heads.stream().filter(h->h.gap()<=3.3).count());
        r.put("target_near_gate_closed",original.other.heads.stream().filter(h->h.gap()<=3.3&&!h.gate()).count());
        r.put("own_takeoffs",original.launches.size());
        var fresh=g.hits.list.stream().filter(h->!h.upgrade()).toList();
        if(!fresh.isEmpty()){
            var h=fresh.get(0);r.put("scored_first",h.sideA()==g.first);r.put("first_hit_ms",h.at());
            var q=original.before(h.at());r.put("first_hit_gap_prior_sample",Math.hypot(q.x()-q.tx(),q.z()-q.tz()));
        }
        for(var j:original.launches){
            var hit=fresh.stream().filter(h->h.sideA()!=g.first&&Math.abs(h.at()-j.at())<=150).min(Comparator.comparingDouble(h->Math.abs(h.at()-j.at())));
            if(hit.isEmpty())continue;
            var h=hit.get();var removed=new Trace(s,actor,"original","original",j.head());
            var noop=new Trace(s,actor,"original","original",0);
            if(!original.positions.equals(noop.positions)||!g.hits.list.equals(noop.game.hits.list)||!original.launches.equals(noop.launches)||
                    g.reward()!=noop.game.reward()||!original.own.heads.equals(noop.own.heads)||!original.other.heads.equals(noop.other.heads))
                throw new AssertionError("No-op replay diverged");
            if(hash && !digest(g).equals(digest(noop.game)))throw new AssertionError("Different terminal state");
            // Baseline origin precedes the intervention and the incoming hit in either ordering.
            double begin=Math.min(h.at(),j.at());var pos=original.before(Math.nextDown(begin));
            if(!pos.equals(removed.before(Math.nextDown(begin))))throw new AssertionError("Different pre-intervention trajectory");
            r.put("jump_minus_hit_ms",j.at()-h.at());r.put("jump_score_without",removed.game.reward());
            r.put("same_hit_timestamp_without_jump",removed.game.hits.list.contains(h));
            r.put("jump_displacement_window_complete",Math.min(g.clock.time(),removed.game.clock.time())>=begin+250);
            if(Math.min(g.clock.time(),removed.game.clock.time())>=begin+250){
                r.put("away_with_jump",original.displacement(pos,begin+250));r.put("away_without_jump",removed.displacement(pos,begin+250));
            }break;
        }
        out.println(Json.encode(r));
    }
    public static void main(String[] args)throws Exception{
        try{
            FrozenSumoResponse.load();var actors=new ArrayList<Arena.Fighter>();actors.add(PingLeague.WT);
            for(int i=2;i<args.length;i+=2)actors.add(InputStudy.fighter(args[i],Path.of(args[i+1])));
            var files=Files.readAllLines(Path.of(args[0]));Path dest=Path.of(args[1]),tmp=dest.resolveSibling(dest.getFileName()+".tmp");
            try(var out=new PrintWriter(Files.newBufferedWriter(tmp))){
                int cap=Integer.getInteger("lab.responseResearchCps",20);
                if(cap!=7&&cap!=20)throw new IllegalArgumentException("Unsupported fight cap");
                var settings=new Properties();settings.setProperty("click_cps",Integer.toString(cap));
                for(String file:files){var env=InputStudy.clock(Path.of(file));
                    env.initial.timing.clickIntervalNanos=lab.ClickRate.intervalNanos(settings);
                    for(int p:new int[]{31,109})for(boolean seat:new boolean[]{true,false})for(var actor:actors)
                        analyze(PingLeague.scenario(env,FrozenSumoResponse.target,seat,p,140-p),actor,out,file.equals(files.get(0)));
                    out.flush();
                }
                if(out.checkError())throw new IOException("Incomplete diagnostics");
            }
            Files.move(tmp,dest,StandardCopyOption.ATOMIC_MOVE);
        }finally{PingLeague.pool.shutdown();}
    }
}
