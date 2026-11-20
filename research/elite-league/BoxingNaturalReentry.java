import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lab.Json;

/** Test a two-tick release after a naturally occurring trade and a quiet re-entry. */
final class BoxingNaturalReentry {
    private static final double AGE = 450;
    private static final double HORIZON = 2200;
    private static final double LIMIT = 23_000;
    private static final boolean SINGLE_RELEASE = Boolean.getBoolean("lab.reentry.singleRelease");
    private static final boolean WIDEN = Boolean.getBoolean("lab.reentry.widen");
    private record Timing(int ownPhase, int defenderPhase, int ownUpload, int defenderUpload) {}
    private static final Timing ORIGINAL = new Timing(0,0,0,0);

    static class Scout extends BoxingTradeFollowup.Scout {
        PeriodicComboClock.PacketObservation report, previousReport;

        Scout(int rtt) { super(rtt); }

        @Override public void received(PeriodicComboClock.PacketObservation p) {
            if (p.kind()==PeriodicComboClock.ReceivedKind.TARGET_POSITION) {
                previousReport=report;
                report=p;
            }
            super.received(p);
        }
    }

    private static final class Release extends DuelEnv.Player {
        int remaining;
        int delay;
        int changed;
        Release(DuelEnv.Player source, int remaining, int delay) {
            super("wtap",0,source.rtt,source.wake);
            handled=source.handled;
            releaseUntil=source.releaseUntil;
            firstClick=source.firstClick;
            this.remaining=remaining;
            this.delay=delay;
        }

        @Override public PeriodicComboClock.Control input(PeriodicComboClock.Observation o) {
            var stock=super.input(o);
            if (delay>0) {
                delay--;
                return stock;
            }
            if (remaining==0) return stock;
            remaining--;
            if (stock.key()!=0) changed++;
            return new PeriodicComboClock.Control(0,stock.strafe(),stock.click(),stock.jump());
        }
    }

    /** Observe actual defender recoil from inside the offline model, before travel. */
    private static final class Defender extends DuelEnv.Player {
        PeriodicComboClock clock;
        boolean subjectFirst;
        PeriodicComboClock.PacketObservation impulse;
        Map<String,Object> step = Map.of();

        Defender(int rtt) { super("wtap",0,rtt,0); }

        @Override public void received(PeriodicComboClock.PacketObservation p) {
            if (p.kind()==PeriodicComboClock.ReceivedKind.OWN_VELOCITY) impulse=p;
            super.received(p);
        }

        @Override public PeriodicComboClock.Control movement(PeriodicComboClock.Control issued) {
            if (impulse==null) return issued;
            var s=clock.state;
            var fresh=subjectFirst?s.lastFreshB:s.lastFreshA;
            var q=(subjectFirst?s.b:s.a).q;
            if (fresh!=null && impulse.handledAt()>=fresh.time() && fresh.x()==impulse.vx() &&
                    fresh.y()==impulse.vy() && fresh.z()==impulse.vz() && q.vy()==impulse.vy()) {
                double factor=q.vx()==impulse.vx() && q.vz()==impulse.vz()?1. :
                        q.vx()==impulse.vx()*.6 && q.vz()==impulse.vz()*.6?.6 : -1;
                step=Map.of("factor",factor,"hit",fresh.time(),"step",clock.time(),
                        "horizontal",Math.hypot(impulse.vx(),impulse.vz()),"vertical",impulse.vy(),
                        "separation",fresh.serverSeparation(),"sprint_hit",fresh.attackerSprint());
            } else step=Map.of();
            impulse=null;
            return issued;
        }
    }

    static Map<String,Object> legal(Scout scout, PeriodicComboClock.Observation o) {
        var q=o.own();
        double angleX=o.targetX()-q.x(), angleZ=o.targetZ()-q.z();
        double span=Math.max(.001,Math.hypot(angleX,angleZ));
        double radial=(angleX*q.vx()+angleZ*q.vz())/span;
        var result=new LinkedHashMap<String,Object>();
        result.put("nearest",o.nearest());
        result.put("ground",q.onGround());
        result.put("sprint",o.sprint());
        result.put("height_offset",o.targetY()-q.y());
        result.put("speed_toward",radial);
        result.put("own_vy",q.vy());
        result.put("incoming_first",scout.incomingFirst);
        result.put("own_hurt_age",o.time()-o.lastOwnHurtTime());
        result.put("target_hurt_age",o.time()-o.lastTargetHurtTime());
        result.put("click_age",o.time()-scout.press);
        result.put("velocity",List.of(scout.velocityX,scout.velocityY,scout.velocityZ));
        if (scout.previousHead!=null)
            result.put("gap_velocity",(o.nearest()-scout.previousHead.nearest())*50/
                    Math.max(1,o.time()-scout.previousHead.time()));
        var p=scout.report;
        if (p!=null) {
            result.put("report_age",o.time()-p.arrivedAt());
            result.put("reported_target_ground",p.groundFlag());
            var old=scout.previousReport;
            if (old!=null && p.arrivedAt()>old.arrivedAt()) {
                double radialDelta=(angleX*(p.goalX()-old.goalX())+angleZ*(p.goalZ()-old.goalZ()))/span;
                result.put("reported_target_radial",radialDelta*50/(p.arrivedAt()-old.arrivedAt()));
            }
        }
        return result;
    }

    record Branch(List<DuelEnv.Hit> hits, List<List<Long>> presses, int changed, List<List<Object>> clicks,
                  List<List<Object>> credits) {
        Map<String,Object> result(boolean first, double cut, double previousOwn, boolean unbroken) {
            List<Integer> shortPoints=points(first,cut+1350), total=points(first,cut+HORIZON);
            int count=0;
            double own=-1, other=-1;
            double last=unbroken?previousOwn:cut;
            for (var hit : hits) {
                if (hit.sideA()==first) {
                    if (hit.upgrade()) continue;
                    if (own<0) own=hit.at();
                    if (other<0 && (count==0?hit.at()-last<=850:hit.at()-last<=1100)) {
                        count++;
                        last=hit.at();
                    }
                } else if (other<0) other=hit.at();
            }
            if (count>0 && other==last) count--;
            boolean clean=count>0 && (other<0 || own<other);
            var result=new LinkedHashMap<String,Object>();
            result.put("points",total);
            result.put("short",shortPoints);
            result.put("presses",presses.stream().map(List::size).toList());
            result.put("changed",changed);
            result.put("own",own<0?-1:own-cut);
            result.put("return",other<0?-1:other-cut);
            result.put("clean",clean);
            result.put("three",clean && unbroken && count>=2);
            result.put("fresh_margin",hits.stream().filter(h->!h.upgrade()).mapToInt(h->h.sideA()==first?1:-1).sum());
            result.put("clicks",clicks);
            result.put("credits",credits);
            return result;
        }

        private List<Integer> points(boolean first, double end) {
            int a=0,b=0;
            for (var hit : hits) {
                if (hit.at()>end) break;
                if (hit.sideA()==first) a++; else b++;
            }
            return List.of(a,b);
        }
    }

    private static Branch fork(DuelEnv env, DuelEnv.Game prefix, int ticks, int delay, Timing timing, boolean detailed) {
        boolean first=prefix.first;
        var actor=new Release(prefix.our(),ticks,delay);
        var game=env.new Game(prefix,first?actor:prefix.a.copy(),first?prefix.b.copy():actor);
        game.trace();
        var presses=List.of(new ArrayList<Long>(),new ArrayList<Long>());
        var clicks=new ArrayList<List<Object>>();
        var credits=new ArrayList<List<Object>>();
        double cut=prefix.clock.time();
        if (timing.ownPhase!=0) game.clock.shiftFutureClientOpportunities(first,timing.ownPhase);
        if (timing.defenderPhase!=0) game.clock.shiftFutureClientOpportunities(!first,timing.defenderPhase);
        BoxingJumpTimingAudit.shiftAttackTiming(game.clock.state,first?timing.ownUpload:timing.defenderUpload,
                first?timing.defenderUpload:timing.ownUpload,cut,cut+HORIZON);
        game.clock.setClickAudit(e -> {
            if (e.action().equals("ADMIT")) {
                var list=presses.get(e.fromA()==first?0:1);
                if (!list.isEmpty() && e.admittedNanos()-list.get(list.size()-1)<50_000_000L)
                    throw new IllegalStateException("20 CPS cap exceeded");
                list.add(e.admittedNanos());
            } else if (detailed && e.action().equals("CONSUME")) {
                var s=game.clock.state;
                var body=e.fromA()?s.a:s.b;
                var view=e.fromA()?s.ab:s.ba;
                var packet=e.contact()?s.packets.get(s.packets.size()-1):null;
                if (packet!=null && packet.kind!=(e.fromA()?3:7))
                    throw new IllegalStateException("Missing C02 at the contact");
                double ray=PeriodicComboFightModel.ray(body,view);
                clicks.add(List.of(e.time()-cut,e.fromA()==first,e.eligible(),e.contact(),
                        PeriodicComboFightModel.nearest(body.q,view),Double.isFinite(ray)?ray:-1.,
                        packet!=null?packet.ready-cut:-1.,packet!=null?packet.sequence:-1L));
            }
        });
        if (detailed) game.clock.setAudit((time,event,s)->{
            int start=game.hits.list.size();
            game.hits.event(time,event,s);
            for (int i=start;i<game.hits.list.size();i++) {
                var h=game.hits.list.get(i);
                long packet=h.upgrade()?(h.sideA()?s.lastAcceptedPacketA:s.lastAcceptedPacketB):
                        (h.sideA()?s.lastFreshPacketA:s.lastFreshPacketB);
                credits.add(List.of(time-cut,h.sideA()==first,h.upgrade(),s.serverTicks,packet));
            }
        });
        game.clock.runUntil(Math.nextDown(cut+HORIZON));
        return new Branch(List.copyOf(game.hits.list),presses.stream().map(List::copyOf).toList(),actor.changed,clicks,credits);
    }

    private static Map<String,Object> alternatives(DuelEnv env, DuelEnv.Game prefix, double last, boolean unbroken,
                                                   Timing timing, boolean detailed) {
        var baseline=fork(env,prefix,0,0,timing,detailed);
        var choices=new LinkedHashMap<String,Branch>();
        choices.put("wtap",baseline);
        choices.put("release",fork(env,prefix,2,0,timing,detailed));
        if (SINGLE_RELEASE) {
            choices.put("first_tick",fork(env,prefix,1,0,timing,detailed));
            choices.put("second_tick",fork(env,prefix,1,1,timing,detailed));
        }
        if (detailed && !baseline.equals(fork(env,prefix,0,0,timing,true)))
            throw new IllegalStateException("Release changed the shared prefix");
        var scores=new LinkedHashMap<String,Object>();
        for (var item : choices.entrySet())
            scores.put(item.getKey(),item.getValue().result(prefix.first,prefix.clock.time(),last,unbroken));
        return Map.of("arms",scores,"identical_presses",choices.values().stream().allMatch(b->b.presses.equals(baseline.presses)));
    }

    private static List<Map<String,Object>> widen(DuelEnv env, DuelEnv.Game g, double previous, boolean unbroken) {
        var results=new ArrayList<Map<String,Object>>();
        int[][] phase={{0,0},{2,0},{5,0},{10,0},{0,-10},{0,-5},{0,-2},{0,2},{0,5},{0,10},{5,5}};
        int[][] upload={{0,0},{0,5},{0,10},{5,0},{10,0}};
        for (var p : phase) for (var u : upload) {
            var timing=new Timing(p[0],p[1],u[0],u[1]);
            var sample=new LinkedHashMap<>(alternatives(env,g,previous,unbroken,timing,true));
            sample.put("client_phases",List.of(p[0],p[1]));
            sample.put("attack_upload",List.of(u[0],u[1]));
            results.add(sample);
        }
        return results;
    }

    private static Map<String,Object> play(DuelEnv env, boolean first, int ownShift, int otherShift) {
        var scout=new Scout(env.rtt);
        var defender=new Defender(env.otherRtt);
        var g=env.new Game(first,scout,defender);
        defender.clock=g.clock;
        defender.subjectFirst=first;
        if (ownShift!=0) g.clock.shiftFutureClientOpportunities(first,ownShift);
        if (otherShift!=0) g.clock.shiftFutureClientOpportunities(!first,otherShift);
        g.trace();
        var cuts=new ArrayList<Map<String,Object>>();
        int lastEpisode=0;
        if (g.clock.advanceToBeforeNextHead(first,LIMIT)) do {
            var o=g.clock.observation(first);
            scout.observe(o);
            if (scout.episode>4) break;
            if (scout.phase!=2 || scout.episode==lastEpisode || o.time()-o.lastTargetHurtTime()<AGE) continue;
            lastEpisode=scout.episode;
            var s=g.clock.state;
            var prior=first?s.lastFreshB:s.lastFreshA;
            var incoming=first?s.lastFreshA:s.lastFreshB;
            boolean unbroken=prior!=null && (first?s.lastFreshOrderA>s.lastAcceptedOrderB:s.lastFreshOrderB>s.lastAcceptedOrderA);
            double previous=prior==null?o.time():prior.time();
            var row=new LinkedHashMap<String,Object>(alternatives(env,g,previous,unbroken,ORIGINAL,true));
            row.put("at",o.time());
            row.put("episode",lastEpisode);
            row.put("visible",legal(scout,o));
            row.put("defender_recoil",defender.step);
            row.put("prior_hit_gap",incoming==null?-1.:previous-incoming.time());
            row.put("unbroken",unbroken);
            if (SINGLE_RELEASE)
                row.put("stress",Map.of("own_late",alternatives(env,g,previous,unbroken,new Timing(0,0,5,0),false),
                        "defender_late",alternatives(env,g,previous,unbroken,new Timing(0,0,0,5),false)));
            if (WIDEN && first && lastEpisode==3)
                row.put("wider",widen(env,g,previous,unbroken));
            cuts.add(row);
        } while (g.clock.runHeadInterval(first,LIMIT));
        return Map.of("clock",env.clockId,"rtt",env.rtt,"seat",first?"a":"b",
                "phase",List.of(ownShift,otherShift),"cuts",cuts);
    }

    public static void main(String[] args) throws Exception {
        int rtt=Integer.parseInt(args[0]), own=Integer.parseInt(args[1]), other=Integer.parseInt(args[2]);
        var wire=Path.of(args[3]);
        var seeds=Path.of(args[4]);
        for (int i=5;i<args.length;i++) {
            var env=BoxingEdgeHitSelect.environment(Path.of(args[i]),rtt,180_000,wire,seeds);
            for (boolean first : new boolean[]{true,false}) System.out.println(Json.encode(play(env,first,own,other)));
        }
    }
}
