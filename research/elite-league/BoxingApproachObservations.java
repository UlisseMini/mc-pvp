import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lab.BoxingApproachPoll;
import lab.Json;

/** Paired outcomes for short movement choices, with observations frozen before input. */
final class BoxingApproachObservations {
    private static final double HORIZON = 1800;
    private static final double LIMIT = 28_200;
    private static final double[] DISTANCES = {3.70, 4.10, 4.65, 5.20};
    private static final List<List<String>> WORDS = List.of(List.of("bbbs", "r"), List.of("rr"), List.of("rrrs"));

    private static final class Scout extends BoxingApproachPollCompare.Fighter {
        final ArrayDeque<PeriodicComboClock.Observation> heads = new ArrayDeque<>();
        final ArrayDeque<PeriodicComboClock.PacketObservation> positions = new ArrayDeque<>();
        double lastPress = Double.NEGATIVE_INFINITY;

        Scout(int rtt) { super(rtt, BoxingApproachPoll.Mode.OBSERVE, null); }

        @Override public PeriodicComboClock.Control input(PeriodicComboClock.Observation o) {
            if (heads.size()==4) heads.removeFirst();
            heads.addLast(o);
            return super.input(o);
        }

        @Override public void received(PeriodicComboClock.PacketObservation packet) {
            if (packet.kind()==PeriodicComboClock.ReceivedKind.TARGET_POSITION) {
                if (positions.size()==4) positions.removeFirst();
                positions.addLast(packet);
            }
            super.received(packet);
        }

        @Override public boolean injected(PeriodicComboClock.Observation own, double completedAt) {
            lastPress=completedAt;
            return super.injected(own,completedAt);
        }
    }

    private static double forward(PeriodicComboClock.Observation o) {
        double x=o.targetX()-o.own().x(), z=o.targetZ()-o.own().z();
        return (x*o.own().vx()+z*o.own().vz()) / Math.max(.001,Math.hypot(x,z));
    }

    private static int band(PeriodicComboClock.Observation o) {
        if (!o.own().onGround() || Math.abs(o.targetY()-o.own().y())>.05 || forward(o)<.13 ||
                o.time()-Math.max(o.lastTargetHurtTime(),o.lastOwnHurtTime())<1100) return -1;
        double nearest=o.nearest();
        for (int i=0;i<DISTANCES.length-1;i++) if (nearest>=DISTANCES[i] && nearest<DISTANCES[i+1]) return i;
        return -1;
    }

    private static Map<String,Double> local(Scout s, PeriodicComboClock.Observation o) {
        var own=o.own();
        double dx=o.targetX()-own.x(), dz=o.targetZ()-own.z();
        double center=Math.hypot(dx,dz), ux=dx/center, uz=dz/center;
        var vars=new LinkedHashMap<String,Double>();
        vars.put("range",o.nearest());
        vars.put("forward",forward(o));
        vars.put("lateral",Math.abs(own.vx()*uz-own.vz()*ux));
        vars.put("rtt",(double)s.rtt);
        vars.put("sprint",o.sprint()?1.:0.);
        vars.put("height",Math.abs(o.targetY()-own.y()));
        vars.put("contact_age",Math.min(2000,o.time()-o.lastContactTime()));
        vars.put("press_age",Math.min(150,o.time()-s.lastPress));
        var last=s.heads.peekLast();
        var oldest=s.heads.peekFirst();
        double headSpan=oldest==null?0:o.time()-oldest.time();
        double dt=last==null?0:o.time()-last.time();
        vars.put("previous_head",dt);
        vars.put("closing_head",dt<=0?0:(last.nearest()-o.nearest())*50/dt);
        vars.put("closing_recent",headSpan<=0?0:(oldest.nearest()-o.nearest())*50/headSpan);
        vars.put("target_motion",headSpan<=0?0:((oldest.targetX()-o.targetX())*ux+(oldest.targetZ()-o.targetZ())*uz)*50/headSpan);
        vars.put("own_accel",last==null?0:forward(o)-forward(last));
        var goal=s.positions.peekLast();
        var oldGoal=s.positions.peekFirst();
        double span=goal==null?0:goal.arrivedAt()-oldGoal.arrivedAt();
        vars.put("goal_offset",goal==null?0:(goal.goalX()-o.targetX())*ux+(goal.goalZ()-o.targetZ())*uz);
        vars.put("goal_motion",span<=0?0:((oldGoal.goalX()-goal.goalX())*ux+(oldGoal.goalZ()-goal.goalZ())*uz)*50/span);
        vars.put("replica_age",goal==null?150:Math.min(150,o.time()-goal.arrivedAt()));
        vars.put("replica_queue",goal==null?0:goal.handledAt()-goal.arrivedAt());
        vars.put("replica_samples",(double)s.positions.size());
        return vars;
    }

    private static Map<String,Double> privileged(DuelEnv.Game game) {
        var state=game.clock.state;
        var other=game.clock.observation(!game.first);
        var own=game.clock.observation(game.first);
        double dx=own.own().x()-other.own().x(), dz=own.own().z()-other.own().z();
        return Map.of("other_range",other.nearest(),"other_forward",forward(other),
                "other_ground",other.own().onGround()?1.:0.,"other_sprint",other.sprint()?1.:0.,
                "live_center",Math.hypot(dx,dz),"server_center",Math.hypot(state.serverAx-state.serverBx,state.serverAz-state.serverBz));
    }

    private static Map<String,Object> cut(DuelEnv env, DuelEnv.Game game, Scout scout, PeriodicComboClock.Observation o,
                                          int band, int index) {
        double at=game.clock.time();
        var local=local(scout,o);
        var privileged=privileged(game);
        var scores=new LinkedHashMap<String,Object>();
        var plain=BoxingApproachFootwork.replay(env,game,scout.copy(),at,HORIZON).summary(game.first);
        scores.put("wtap",plain);
        for (String word:WORDS.get(band)) {
            var next=new BoxingApproachFootwork.Fighter(scout,0,word,true);
            var result=BoxingApproachFootwork.replay(env,game,next,at,HORIZON);
            if (result.movement().size()!=word.length()) throw new IllegalStateException("Short movement playback");
            scores.put(word,result.summary(game.first));
        }
        if (!plain.equals(BoxingApproachFootwork.replay(env,game,scout.copy(),at,HORIZON).summary(game.first)))
            throw new IllegalStateException("Changing movement altered the shared control");
        return Map.of("at",at,"band",band,"occurrence",index,"local",local,"privileged",privileged,"arms",scores);
    }

    private static Map<String,Object> play(DuelEnv env, boolean first, int ownPhase, int otherPhase) {
        var scout=new Scout(env.rtt);
        var game=env.new Game(first,scout,Evolve.WT.create(env.rtt,0));
        if (ownPhase!=0) game.clock.shiftFutureClientOpportunities(first,ownPhase);
        if (otherPhase!=0) game.clock.shiftFutureClientOpportunities(!first,otherPhase);
        var cuts=new ArrayList<Map<String,Object>>();
        var count=new int[3];
        double[] prior={-2000,-2000,-2000};
        if (game.clock.advanceToBeforeNextHead(first,LIMIT)) do {
            var o=game.clock.observation(first);
            int at=band(o);
            if (at<0 || count[at]>=4 || o.time()-prior[at]<1800) continue;
            cuts.add(cut(env,game,scout,o,at,count[at]++));
            prior[at]=o.time();
        } while (game.clock.runHeadInterval(first,LIMIT));
        return Map.of("clock",env.clockId,"rtt",env.rtt,"seat",first?"a":"b",
                "phase",List.of(ownPhase,otherPhase),"cuts",cuts);
    }

    public static void main(String[] args) throws Exception {
        int rtt=Integer.parseInt(args[0]);
        int ours=Integer.parseInt(args[1]), other=Integer.parseInt(args[2]);
        var wire=Path.of(args[3]);
        var seeds=Path.of(args[4]);
        for (int i=5;i<args.length;i++) {
            var env=BoxingEdgeHitSelect.environment(Path.of(args[i]),rtt,180_000,wire,seeds);
            for (boolean first:new boolean[]{true,false}) System.out.println(Json.encode(play(env,first,ours,other)));
        }
    }
}
