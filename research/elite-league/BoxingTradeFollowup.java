import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lab.BoxingApproachPoll;
import lab.Json;

/** Compare short movement pulses after both local hurt cues in an ordinary trade. */
final class BoxingTradeFollowup {
    private static final double QUIET_MS = 1100;
    private static final double MAX_CUE_INTERVAL_MS = 300;
    private static final double HORIZON = 1350;
    private static final double LIMIT = 23_000;
    private static final int MAX_EXCHANGES = 4;
    static final List<Integer> STAGES = List.of(0, 200, 325);
    private static final String WHICH = System.getProperty("lab.tradeFollowup.arm", "");
    static final List<String> WORDS = List.of("r", "b", "w", "s", "l", "d", "a", "e", "rr", "bb", "ww", "ss",
            "ll", "dd", "aa", "ee", "rs", "bs", "ws", "sr", "lr", "dr", "rrs", "bbs", "rss", "bss");

    static class Scout extends BoxingApproachPollCompare.Fighter {
        double lastOwn = Double.NEGATIVE_INFINITY, lastTarget = Double.NEGATIVE_INFINITY;
        double velocityX, velocityY, velocityZ;
        double press = Double.NEGATIVE_INFINITY;
        double firstCue, pairedAt;
        int episode, phase;
        boolean incomingFirst;
        final boolean[] tested = new boolean[STAGES.size()];
        PeriodicComboClock.Observation previousHead;

        Scout(int rtt) { super(rtt,BoxingApproachPoll.Mode.OBSERVE,null); }

        @Override public void received(PeriodicComboClock.PacketObservation p) {
            if (p.kind()==PeriodicComboClock.ReceivedKind.OWN_VELOCITY) {
                velocityX=p.vx(); velocityY=p.vy(); velocityZ=p.vz();
            }
            super.received(p);
        }

        @Override public boolean injected(PeriodicComboClock.Observation own, double completedAt) {
            press=completedAt;
            return super.injected(own,completedAt);
        }

        @Override public PeriodicComboClock.Control input(PeriodicComboClock.Observation own) {
            previousHead=own;
            return super.input(own);
        }

        // Run before input is called, using only this endpoint's already delivered observations.
        void observe(PeriodicComboClock.Observation o) {
            double own=o.lastOwnHurtTime(), target=o.lastTargetHurtTime();
            boolean incoming=own>lastOwn, outgoing=target>lastTarget;
            if (!incoming && !outgoing) return;
            double now=Math.max(own,target), previous=Math.max(lastOwn,lastTarget);
            if (now-previous>=QUIET_MS) {
                firstCue=incoming&&outgoing?Math.min(own,target):now;
                incomingFirst=incoming&&(!outgoing||own<=target);
                phase=1;
            } else if (phase==2) phase=0;
            if (phase==1 && now-firstCue<=MAX_CUE_INTERVAL_MS &&
                    (incomingFirst? target>=firstCue : own>=firstCue)) {
                phase=2;
                pairedAt=now;
                episode++;
                java.util.Arrays.fill(tested,false);
            }
            lastOwn=own; lastTarget=target;
        }
    }

    private static double radial(PeriodicComboClock.Observation o) {
        double dx=o.targetX()-o.own().x(), dz=o.targetZ()-o.own().z();
        return (o.own().vx()*dx+o.own().vz()*dz)/Math.max(.001,Math.hypot(dx,dz));
    }

    private static double age(double now, double t) {
        return Math.min(2000,now-t);
    }

    private static Map<String,Object> observation(Scout scout, PeriodicComboClock.Observation o) {
        var values=new LinkedHashMap<String,Object>();
        values.put("nearest",o.nearest());
        values.put("height",o.targetY()-o.own().y());
        values.put("ground",o.own().onGround());
        values.put("own_vy",o.own().vy());
        values.put("forward",radial(o));
        values.put("nearest_change",scout.previousHead==null ? 0 : o.nearest()-scout.previousHead.nearest());
        values.put("own_hurt_age",age(o.time(),scout.lastOwn));
        values.put("target_hurt_age",age(o.time(),scout.lastTarget));
        values.put("last_contact_age",age(o.time(),o.lastContactTime()));
        values.put("click_age",age(o.time(),scout.press));
        values.put("velocity_age",age(o.time(),scout.lastVelocity));
        values.put("incoming_velocity",List.of(scout.velocityX,scout.velocityY,scout.velocityZ));
        values.put("sprint",o.sprint());
        return values;
    }

    private static Map<String,Object> score(BoxingApproachFootwork.Branch branch, boolean first, double at) {
        int own=0, other=0;
        double firstReturn=-1, follow=-1;
        for (var hit:branch.hits()) {
            if (hit.sideA()==first) {
                own++;
                if (!hit.upgrade() && follow<0) follow=hit.at();
            } else {
                other++;
                if (firstReturn<0) firstReturn=hit.at();
            }
        }
        return Map.of("points",List.of(own,other),"presses",branch.presses(),"follow",follow,"return",firstReturn,
                "clean",follow>=0 && follow-at<=850 && (firstReturn<0 || follow<firstReturn));
    }

    private static Map<String,Object> cuts(DuelEnv env, DuelEnv.Game game, Scout scout,
                                           PeriodicComboClock.Observation own, int index) {
        double at=own.time();
        var baseline=BoxingApproachFootwork.replay(env,game,scout.copy(),at,HORIZON);
        var arms=new LinkedHashMap<String,Object>();
        arms.put("wtap",score(baseline,game.first,at));
        for (String word:WORDS) {
            if (!WHICH.isEmpty() && !word.equals(WHICH)) continue;
            var movement=new BoxingApproachFootwork.Fighter(scout,0,word,true);
            var branch=BoxingApproachFootwork.replay(env,game,movement,at,HORIZON);
            if (branch.movement().size()!=word.length() || !branch.presses().equals(baseline.presses()))
                throw new IllegalStateException("Movement arm changed the physical click count");
            arms.put(word,score(branch,game.first,at));
        }
        if (!BoxingApproachFootwork.replay(env,game,scout.copy(),at,HORIZON).hits().equals(baseline.hits()))
            throw new IllegalStateException("Changing inputs contaminated the baseline state");
        return Map.of("stage",STAGES.get(index),"episode",scout.episode,"cut",at,"incoming_first",scout.incomingFirst,
                "cue_gap",Math.abs(scout.lastOwn-scout.lastTarget),"paired_time",scout.pairedAt,
                "visible",observation(scout,own),"arms",arms);
    }

    private static Map<String,Object> play(DuelEnv env, boolean first, int ownPhase, int otherPhase) {
        var scout=new Scout(env.rtt);
        var game=env.new Game(first,scout,Evolve.WT.create(env.otherRtt,0));
        if (ownPhase!=0) game.clock.shiftFutureClientOpportunities(first,ownPhase);
        if (otherPhase!=0) game.clock.shiftFutureClientOpportunities(!first,otherPhase);
        var rows=new ArrayList<Map<String,Object>>();
        if (game.clock.advanceToBeforeNextHead(first,LIMIT)) do {
            var own=game.clock.observation(first);
            scout.observe(own);
            if (scout.episode>MAX_EXCHANGES) break;
            if (scout.phase!=2) continue;
            for (int i=0;i<STAGES.size();i++) {
                if (scout.tested[i] || own.time()-scout.pairedAt<STAGES.get(i)) continue;
                scout.tested[i]=true;
                rows.add(cuts(env,game,scout,own,i));
            }
        } while (game.clock.runHeadInterval(first,LIMIT));
        return Map.of("clock",env.clockId,"rtt",env.rtt,"seat",first?"a":"b",
                "phase",List.of(ownPhase,otherPhase),"cuts",rows);
    }

    public static void main(String[] args) throws Exception {
        int rtt=Integer.parseInt(args[0]), ours=Integer.parseInt(args[1]), other=Integer.parseInt(args[2]);
        var wire=Path.of(args[3]);
        var seeds=Path.of(args[4]);
        for (int i=5;i<args.length;i++) {
            var env=BoxingEdgeHitSelect.environment(Path.of(args[i]),rtt,180_000,wire,seeds);
            for (boolean first:new boolean[]{true,false}) System.out.println(Json.encode(play(env,first,ours,other)));
        }
    }
}
