import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lab.ExactTravel;
import lab.Json;

/** Compare fixed-age and own-landing timing of one release in a natural trade. */
final class BoxingLandingReentry {
    static final double HORIZON = Double.parseDouble(System.getProperty("lab.landingReentry.horizon","2700"));
    private static final double ATTACK_SHIFT_HORIZON=Double.parseDouble(
            System.getProperty("lab.landingReentry.attackShiftHorizon",Double.toString(HORIZON)));
    static final double LIMIT = 23_000;
    private static final double AGE = 450;
    private static final boolean TRACE = Boolean.getBoolean("lab.landingReentry.contacts");
    private static final boolean CONTACT_POSE = Boolean.getBoolean("lab.landingReentry.contactPose");
    private static final boolean TRACK_REPLICA = Boolean.getBoolean("lab.landingReentry.trackReplica");
    private static final double REPLICA_SEND_AGE=Double.parseDouble(
            System.getProperty("lab.landingReentry.replicaSendAge","NaN"));
    private static final double REPLICA_SHIFT=Double.parseDouble(
            System.getProperty("lab.landingReentry.replicaShift","0"));
    private static final boolean PROTECTION_CHECK = Boolean.getBoolean("lab.landingReentry.protectionCheck");
    private static final int WIDEN_EPISODE = Integer.getInteger("lab.landingReentry.widenEpisode", -1);
    static final Adjustment BACK=new Adjustment(2,4,0);
    static final Adjustment LATERAL=new Adjustment(2,8,6);
    record Timing(int ownPhase, int defenderPhase, int ownUpload, int defenderUpload) {}
    private record ReplicaDispatch(PeriodicComboFightModel.PacketTiming source,int defenderRoute,double send,double shift)
            implements PeriodicComboFightModel.PacketTiming {
        @Override public double arrival(int route,double time,double leg) {
            return source.arrival(route,time,leg);
        }
        @Override public PeriodicComboFightModel.PacketTimingPlan planForPacket(int route,double time,double leg,
                PeriodicComboFightModel.TimingSemantic semantic,int id) {
            var normal=source.planForPacket(route,time,leg,semantic,id);
            if (route!=defenderRoute || id!=0x15 || Math.abs(time-send)>.000001) return normal;
            double arrival=Math.max(time,normal.rawReceiptMs()+shift);
            Double ready=normal.readyMs()==null?null:Math.max(arrival,normal.readyMs()+shift);
            return new PeriodicComboFightModel.PacketTimingPlan(arrival,ready,normal.cueDelayNanos(),normal.service());
        }
        @Override public ReplicaDispatch deepCopy() {
            return new ReplicaDispatch(source.deepCopy(),defenderRoute,send,shift);
        }
    }
    record Adjustment(int head, int first, int second) {
        static Adjustment fromProperty() {
            String spec=System.getProperty("lab.reentryProtection.word");
            if (spec==null) return null;
            String[] fields=spec.split(",",-1);
            if (fields.length!=3) throw new IllegalArgumentException("Expected head,first,second");
            int head=Integer.parseInt(fields[0]),first=Integer.parseInt(fields[1]),second=Integer.parseInt(fields[2]);
            if (head<2 || head>14 || first<1 || first>=BoxingLateReturns.MOVES || second<0 || second>=BoxingLateReturns.MOVES)
                throw new IllegalArgumentException("Movement sequence is outside the diagnostic menu");
            return new Adjustment(head,first,second);
        }

        PeriodicComboClock.Control input(PeriodicComboClock.Control control, int groundedHead) {
            return switch (groundedHead-head) {
                case 0 -> BoxingLateReturns.movement(control,first);
                case 1 -> BoxingLateReturns.movement(control,second);
                default -> control;
            };
        }
    }

    enum Start { WTAP, AGE450, LANDING, LANDING_PLUS_ONE, LANDING_PLUS_TWO }

    private static final class Landing {
        double hurt = Double.NEGATIVE_INFINITY;
        boolean airborne;
        int headsSinceLanding = -1;

        Landing() {}
        Landing(Landing source) {
            hurt=source.hurt;
            airborne=source.airborne;
            headsSinceLanding=source.headsSinceLanding;
        }

        void input(PeriodicComboClock.Observation o) {
            if (o.lastOwnHurtTime()>hurt) {
                hurt=o.lastOwnHurtTime();
                airborne=false;
                headsSinceLanding=-1;
            }
            if (!o.own().onGround()) airborne=true;
            else if (airborne) headsSinceLanding++;
        }
    }

    static class Scout extends BoxingNaturalReentry.Scout {
        final Landing landing = new Landing();
        Scout(int rtt){super(rtt);}
        @Override public PeriodicComboClock.Control input(PeriodicComboClock.Observation o) {
            landing.input(o);
            return super.input(o);
        }
    }

    private static final class Pulse extends DuelEnv.Player {
        final Start start;
        final Adjustment adjustment;
        final double initialOwnHurt, initialTargetHurt;
        final Landing landing;
        PeriodicComboClock.Observation previousHead;
        Map<String,Object> activation = Map.of();

        Pulse(Scout source, PeriodicComboClock.Observation cut, Start start, Adjustment adjustment) {
            super("wtap",0,source.rtt,source.wake);
            this.start=start;
            this.adjustment=adjustment;
            initialOwnHurt=cut.lastOwnHurtTime();
            initialTargetHurt=cut.lastTargetHurtTime();
            landing=new Landing(source.landing);
            previousHead=source.previousHead;
            handled=source.handled;
            releaseUntil=source.releaseUntil;
            firstClick=source.firstClick;
        }

        @Override public PeriodicComboClock.Control input(PeriodicComboClock.Observation o) {
            var stock=super.input(o);
            var prior=previousHead;
            previousHead=o;
            landing.input(o);
            if (o.lastOwnHurtTime()!=initialOwnHurt || o.lastTargetHurtTime()!=initialTargetHurt) return stock;
            if (start==Start.WTAP && adjustment==null) return stock;
            if (start==Start.WTAP || !activation.isEmpty()) {
                return adjustment!=null && o.own().onGround()?adjustment.input(stock,landing.headsSinceLanding):stock;
            }
            boolean ready=switch (start) {
                case AGE450 -> o.time()-initialTargetHurt>=AGE;
                case LANDING -> o.own().onGround() && landing.headsSinceLanding==0;
                case LANDING_PLUS_ONE -> o.own().onGround() && landing.headsSinceLanding==1;
                case LANDING_PLUS_TWO -> o.own().onGround() && landing.headsSinceLanding==2;
                default -> false;
            };
            if (!ready) return stock;
            var q=o.own();
            double dx=o.targetX()-q.x(),dz=o.targetZ()-q.z();
            double radial=(dx*q.vx()+dz*q.vz())/Math.max(.001,Math.hypot(dx,dz));
            double closing=prior==null?0:(o.nearest()-prior.nearest())*50/Math.max(1,o.time()-prior.time());
            activation=Map.of("time",o.time(),"age",o.time()-initialTargetHurt,"heads_since_landing",landing.headsSinceLanding,
                    "ground",q.onGround(),"nearest",o.nearest(),"target_height",o.targetY()-q.y(),
                    "radial_speed",radial,"gap_velocity",closing,"keys_changed",stock.key()!=0);
            return new PeriodicComboClock.Control(0,stock.strafe(),stock.click(),stock.jump());
        }
    }

    record Run(List<DuelEnv.Hit> hits, List<List<Long>> presses, Map<String,Object> activation,
               List<List<Object>> contacts, List<List<Object>> credits, List<List<Object>> defenderFlight,
               List<List<Object>> defenderReplica) {
        Map<String,Object> score(boolean first, double cut, double previous, boolean open) {
            int own=0,other=0,fresh=0;
            double nextOwn=-1,returnHit=-1;
            int consecutive=0;
            double last=open?previous:cut;
            for (var x : hits) {
                if (x.sideA()==first) {
                    own++;
                    if (x.upgrade()) continue;
                    fresh++;
                    if (nextOwn<0) nextOwn=x.at();
                    if (returnHit<0 && x.at()-last<=(consecutive==0?850:1100)) {
                        consecutive++;
                        last=x.at();
                    }
                } else {
                    other++;
                    if (!x.upgrade()) fresh--;
                    if (returnHit<0) returnHit=x.at();
                }
            }
            if (consecutive>0 && last==returnHit) consecutive--;
            var output=new LinkedHashMap<String,Object>(Map.of("points",List.of(own,other),"fresh_margin",fresh,"clean",consecutive>=1,
                    "three",consecutive+(open?1:0)>=3,"consecutive",consecutive,
                    "own",nextOwn<0?-1:nextOwn-cut,"return",returnHit<0?-1:returnHit-cut,"activation",activation,
                    "press_counts",presses.stream().map(List::size).toList(),
                    "ledger",hits.stream().map(h->List.of(h.at()-cut,h.sideA()==first,h.upgrade())).toList()));
            if (!contacts.isEmpty()) {
                output.put("contacts",contacts);
                output.put("credits",credits);
            }
            if (!defenderFlight.isEmpty()) output.put("defender_flight",defenderFlight);
            if (!defenderReplica.isEmpty()) output.put("defender_replica",defenderReplica);
            return output;
        }
    }

    private static Run fork(DuelEnv env, DuelEnv.Game prefix, Scout scout,
                            PeriodicComboClock.Observation cut, Start mode, Timing timing, boolean traceAll) {
        return fork(env,prefix,scout,cut,mode,timing,traceAll,null);
    }

    static Run fork(DuelEnv env, DuelEnv.Game prefix, Scout scout, PeriodicComboClock.Observation cut,
                    Start mode, Timing timing, boolean traceAll, Adjustment adjustment) {
        boolean first=prefix.first;
        var actor=new Pulse(scout,cut,mode,adjustment);
        var g=env.new Game(prefix,first?actor:prefix.a.copy(),first?prefix.b.copy():actor);
        if (timing.ownPhase!=0) g.clock.shiftFutureClientOpportunities(first,timing.ownPhase);
        if (timing.defenderPhase!=0) g.clock.shiftFutureClientOpportunities(!first,timing.defenderPhase);
        BoxingJumpTimingAudit.shiftAttackTiming(g.clock.state,first?timing.ownUpload:timing.defenderUpload,
                first?timing.defenderUpload:timing.ownUpload,cut.time(),cut.time()+ATTACK_SHIFT_HORIZON);
        if (Double.isFinite(REPLICA_SEND_AGE) && REPLICA_SHIFT!=0) {
            g.clock.state.packetTiming=new ReplicaDispatch(g.clock.state.packetTiming,first?3:2,
                    cut.time()+REPLICA_SEND_AGE,REPLICA_SHIFT);
        }
        g.trace();
        boolean traced=TRACE && (traceAll || timing.ownUpload==0 && timing.defenderUpload==0);
        var presses=List.of(new ArrayList<Long>(),new ArrayList<Long>());
        var contacts=new ArrayList<List<Object>>();
        var credits=new ArrayList<List<Object>>();
        var defenderFlight=new ArrayList<List<Object>>();
        var defenderReplica=new ArrayList<List<Object>>();
        String defenderSide=first?"B":"A";
        var previousDefender=new ExactTravel.State[]{(first?g.clock.state.b:g.clock.state.a).q};
        g.clock.setClickAudit(e->{
            if (traced && e.action().equals("CONSUME")) {
                var s=g.clock.state;
                var p=e.contact()?s.packets.get(s.packets.size()-1):null;
                if (p!=null && p.kind!=(e.fromA()?3:7)) throw new IllegalStateException("Expected a C02");
                var body=e.fromA()?s.a:s.b;
                var view=e.fromA()?s.ab:s.ba;
                double ray=PeriodicComboFightModel.ray(body,view);
                var contact=List.<Object>of(e.time()-cut.time(),e.fromA()==first,e.eligible(),e.contact(),
                        PeriodicComboFightModel.nearest(body.q,view),Double.isFinite(ray)?ray:-1.,
                        p!=null?p.ready-cut.time():-1.,p!=null?p.sequence:-1L);
                if (CONTACT_POSE) {
                    var pose=new ArrayList<>(contact);
                    var other=(e.fromA()?s.b:s.a).q;
                    pose.add(List.of(body.q.x(),body.q.y(),body.q.z()));
                    pose.add(List.of(view.x,view.y,view.z));
                    pose.add(List.of(other.x(),other.y(),other.z()));
                    contacts.add(pose);
                } else contacts.add(contact);
            }
            if (!e.action().equals("ADMIT")) return;
            var list=presses.get(e.fromA()==first?0:1);
            if (!list.isEmpty() && e.admittedNanos()-list.get(list.size()-1)<50_000_000L)
                throw new IllegalStateException("Physical cap exceeded");
            list.add(e.admittedNanos());
        });
        if (traced) g.clock.setAudit((at,event,s)->{
            int prior=g.hits.list.size();
            g.hits.event(at,event,s);
            if (TRACK_REPLICA && traceAll) traceReplica(defenderReplica,s,first,defenderSide,at-cut.time(),event,cut.time());
            if (traceAll) {
                var defender=(first?s.b:s.a).q;
                // Of these receive events only velocity application replaces the local body state.
                boolean applied=defender!=previousDefender[0] &&
                        (event.equals("CLIENT_DRAIN"+defenderSide) || event.equals("PACKET_APPLICATION"+defenderSide) ||
                         event.equals("CUE_PUBLICATION"+defenderSide));
                if (applied || event.equals("MOVE"+defenderSide))
                    defenderFlight.add(List.of(at-cut.time(),applied?"velocity":"move",
                            defender.x(),defender.y(),defender.z(),defender.vx(),defender.vy(),defender.vz()));
                previousDefender[0]=defender;
            }
            for (int i=prior;i<g.hits.list.size();i++) {
                var h=g.hits.list.get(i);
                long id=h.upgrade()?(h.sideA()?s.lastAcceptedPacketA:s.lastAcceptedPacketB):
                        (h.sideA()?s.lastFreshPacketA:s.lastFreshPacketB);
                credits.add(List.of(h.at()-cut.time(),h.sideA()==first,h.upgrade(),s.serverTicks,id));
            }
        });
        g.clock.runUntil(Math.nextDown(cut.time()+HORIZON));
        return new Run(List.copyOf(g.hits.list),presses.stream().map(List::copyOf).toList(),actor.activation,
                contacts,credits,defenderFlight,defenderReplica);
    }

    private static void traceReplica(List<List<Object>> trace,PeriodicComboFightModel.State s,boolean own,
                                     String defenderSide,double age,String event,double cut) {
        var v=own?s.ba:s.ab;
        if (event.equals("SERVER_TRACK")) {
            for (var packet:s.packets) {
                if (packet.kind!=(own?6:5)) continue;
                // A queued tracker update may appear in multiple snapshots. Its sequence identifies it.
                trace.add(List.of(age,"tracker",packet.sequence,packet.packetId,packet.ready-cut,
                        packet.x,packet.y,packet.z));
            }
        } else if (event.equals("CLIENT_DRAIN"+defenderSide) || event.equals("INTERP"+defenderSide) ||
                event.equals("PACKET_APPLICATION"+defenderSide) || event.equals("INPUT"+defenderSide)) {
            trace.add(List.of(age,event,v.left,v.x,v.y,v.z,v.gx,v.gy,v.gz));
        }
    }

    private static Map<String,Object> alternatives(DuelEnv env, DuelEnv.Game g, Scout scout,
            PeriodicComboClock.Observation o, Timing timing, double previous, boolean open, boolean traceAll) {
        var wtap=fork(env,g,scout,o,Start.WTAP,timing,traceAll);
        var scores=new LinkedHashMap<String,Object>();
        for (Start start : Start.values()) {
            var arm=start==Start.WTAP?wtap:fork(env,g,scout,o,start,timing,traceAll);
            if (!arm.presses.equals(wtap.presses))
                throw new IllegalStateException("Pulse moved physical press times");
            if (arm.activation.isEmpty() && !arm.hits.equals(wtap.hits))
                throw new IllegalStateException("A branch with no pulse changed the fight");
            scores.put(start.name().toLowerCase(java.util.Locale.ROOT),arm.score(g.first,o.time(),previous,open));
        }
        return scores;
    }

    private static Map<String,Object> protectionCheck(DuelEnv env, DuelEnv.Game g, Scout scout,
            PeriodicComboClock.Observation o, double previous, boolean open) {
        var timing=new Timing(0,0,0,0);
        var stock=fork(env,g,scout,o,Start.WTAP,timing,false);
        var pulse=fork(env,g,scout,o,Start.LANDING_PLUS_ONE,timing,false);
        var back=fork(env,g,scout,o,Start.LANDING_PLUS_ONE,timing,false,BACK);
        var lateral=fork(env,g,scout,o,Start.LANDING_PLUS_ONE,timing,false,LATERAL);
        if (!back.presses.equals(stock.presses) || !lateral.presses.equals(stock.presses) || !pulse.presses.equals(stock.presses))
            throw new IllegalStateException("Protection check changed physical click timestamps");
        var scores=new LinkedHashMap<String,Object>(Map.of("wtap",stock.score(g.first,o.time(),previous,open),
                "landing_plus_one",pulse.score(g.first,o.time(),previous,open),
                "back",back.score(g.first,o.time(),previous,open),
                "lateral",lateral.score(g.first,o.time(),previous,open)));
        var selected=Adjustment.fromProperty();
        if (selected!=null) {
            var extra=fork(env,g,scout,o,Start.LANDING_PLUS_ONE,timing,false,selected);
            if (!extra.presses.equals(stock.presses))
                throw new IllegalStateException("Protection check changed physical click timestamps");
            scores.put("chosen",extra.score(g.first,o.time(),previous,open));
        }
        return scores;
    }

    private static List<Map<String,Object>> widen(DuelEnv env, DuelEnv.Game g, Scout scout,
            PeriodicComboClock.Observation o, double previous, boolean open) {
        var rows=new ArrayList<Map<String,Object>>();
        int[][] phase={{0,0},{2,0},{5,0},{10,0},{0,-10},{0,-5},{0,-2},{0,2},{0,5},{0,10},{5,5}};
        int[][] upload={{0,0},{-10,0},{0,-10},{5,0},{0,5}};
        if (env.rtt<20) throw new IllegalArgumentException("This diagnostic needs at least 20 ms RTT for earlier attack delivery");
        for (int[] p : phase) for (int[] u : upload) {
            var arms=alternatives(env,g,scout,o,new Timing(p[0],p[1],u[0],u[1]),previous,open,true);
            rows.add(Map.of("phase",List.of(p[0],p[1]),"upload",List.of(u[0],u[1]),"arms",arms));
        }
        return rows;
    }

    private static Map<String,Object> play(DuelEnv env, boolean first, int ownPhase, int defenderPhase) {
        var scout=new Scout(env.rtt);
        var g=env.new Game(first,scout,Evolve.WT.create(env.otherRtt,0));
        if (ownPhase!=0) g.clock.shiftFutureClientOpportunities(first,ownPhase);
        if (defenderPhase!=0) g.clock.shiftFutureClientOpportunities(!first,defenderPhase);
        var cuts=new ArrayList<Map<String,Object>>();
        if (g.clock.advanceToBeforeNextHead(first,LIMIT)) do {
            var o=g.clock.observation(first);
            int prior=scout.episode;
            scout.observe(o);
            if (scout.episode>4) break;
            if (scout.episode==prior) continue;
            var s=g.clock.state;
            var strike=first?s.lastFreshB:s.lastFreshA;
            double previous=strike==null?o.time():strike.time();
            boolean open=strike!=null && (first?s.lastFreshOrderA>s.lastAcceptedOrderB:s.lastFreshOrderB>s.lastAcceptedOrderA);
            if (PROTECTION_CHECK) {
                cuts.add(Map.of("at",o.time(),"episode",scout.episode,
                        "arms",protectionCheck(env,g,scout,o,previous,open)));
                continue;
            }
            var row=new LinkedHashMap<String,Object>(Map.of("at",o.time(),"episode",scout.episode,
                    "visible",BoxingNaturalReentry.legal(scout,o),
                    "arms",alternatives(env,g,scout,o,new Timing(0,0,0,0),previous,open,false),
                    "own_late",alternatives(env,g,scout,o,new Timing(0,0,5,0),previous,open,false),
                    "defender_late",alternatives(env,g,scout,o,new Timing(0,0,0,5),previous,open,false)));
            if (first && scout.episode==WIDEN_EPISODE) row.put("wider",widen(env,g,scout,o,previous,open));
            cuts.add(row);
        } while (g.clock.runHeadInterval(first,LIMIT));
        return Map.of("clock",env.clockId,"rtt",env.rtt,"seat",first?"a":"b",
                "phase",List.of(ownPhase,defenderPhase),"cuts",cuts);
    }

    public static void main(String[] args) throws Exception {
        int rtt=Integer.parseInt(args[0]), own=Integer.parseInt(args[1]), defender=Integer.parseInt(args[2]);
        var wire=Path.of(args[3]);
        var seeds=Path.of(args[4]);
        for (int i=5;i<args.length;i++) {
            var env=BoxingEdgeHitSelect.environment(Path.of(args[i]),rtt,180_000,wire,seeds);
            for (boolean first : new boolean[]{true,false}) System.out.println(Json.encode(play(env,first,own,defender)));
        }
    }
}
