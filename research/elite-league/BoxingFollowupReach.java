import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lab.Json;

/** Capture legal observations and exact reach labels at each side's next fresh window. */
final class BoxingFollowupReach {
    private static final double LIMIT = 23_000;
    private static final double HORIZON = 1350;

    private static final class Observer extends BoxingTradeFollowup.Scout {
        final ArrayDeque<PeriodicComboClock.PacketObservation> goals = new ArrayDeque<>();
        Observer(int ping) { super(ping); }

        @Override public void received(PeriodicComboClock.PacketObservation p) {
            if (p.kind()==PeriodicComboClock.ReceivedKind.TARGET_POSITION) {
                if (goals.size()==5) goals.removeFirst();
                goals.addLast(p);
            }
            super.received(p);
        }
    }

    private static final class Openings {
        int a=-1,b=-1;
        long seenA,seenB;

        void serverDrain(PeriodicComboFightModel.State s) {
            if (s.lastFreshOrderA!=seenA) {
                a=s.serverTicks;
                seenA=s.lastFreshOrderA;
            }
            if (s.lastFreshOrderB!=seenB) {
                b=s.serverTicks;
                seenB=s.lastFreshOrderB;
            }
        }
    }

    private static Map<String,Object> visible(Observer owner, PeriodicComboClock.Observation o) {
        var q=o.own();
        double t=o.time();
        boolean tap=o.lastTargetHurtTime()>owner.handled || (long)t<owner.releaseUntil;
        return Map.of("own",List.of(q.x(),q.y(),q.z(),q.vx(),q.vy(),q.vz(),q.onGround(),o.sprint()),
                "replica",List.of(o.targetX(),o.targetY(),o.targetZ()),"range",o.nearest(),"stock_release",tap,
                "cues",List.of(t-o.lastOwnHurtTime(),t-o.lastTargetHurtTime()),
                "S12",List.of(owner.velocityX,owner.velocityY,owner.velocityZ,Math.min(2000,t-owner.lastVelocity)),
                "reports",owner.goals.stream().map(p->List.of(t-p.arrivedAt(),t-p.handledAt(),
                        p.goalX(),p.goalY(),p.goalZ(),p.groundFlag())).toList());
    }

    private static List<Object> ranges(PeriodicComboFightModel.State s, boolean first, double at) {
        var a=first?s.a:s.b;
        var b=first?s.b:s.a;
        var visible=first?s.ab:s.ba;
        var other=first?s.ba:s.ab;
        double own=PeriodicComboFightModel.ray(a,visible), defender=PeriodicComboFightModel.ray(b,other);
        return List.of(at,PeriodicComboFightModel.nearest(a.q,visible),PeriodicComboFightModel.nearest(b.q,other),
                Double.isFinite(own)?own:-1.,Double.isFinite(defender)?defender:-1.,
                a.q.y(),b.q.y(),a.q.onGround(),b.q.onGround());
    }

    private static Map<String,Object> fork(DuelEnv env, DuelEnv.Game prefix, Observer observer, String word,
                                           int ownFreshTick, int defenderFreshTick) {
        boolean first=prefix.first;
        DuelEnv.Player actor=word.isEmpty()?observer.copy():new BoxingApproachFootwork.Fighter(observer,0,word,true);
        var g=env.new Game(prefix,first?actor:prefix.a.copy(),first?prefix.b.copy():actor);
        g.trace();
        var windows=new LinkedHashMap<String,Object>();
        int[] presses={0,0};
        int[] moves={0,0};
        boolean[] sprint={g.clock.state.a.sprint,g.clock.state.b.sprint};
        var brakes=List.of(new ArrayList<Integer>(),new ArrayList<Integer>());
        var contacts=new ArrayList<List<Object>>();
        g.clock.setClickAudit(e -> {
            if (e.action().equals("ADMIT")) presses[e.fromA()==first?0:1]++;
            if (e.action().equals("CONSUME") && e.contact()) {
                var state=g.clock.state;
                var packet=state.packets.get(state.packets.size()-1);
                if (packet.sequence!=state.seq-1 || packet.kind!=(e.fromA()?3:7))
                    throw new IllegalStateException("Missing emitted contact packet");
                contacts.add(List.of(e.time(),e.fromA()==first,packet.ready,packet.sequence));
                int side=e.fromA()?0:1;
                if (sprint[side]) {
                    if ((e.fromA()?state.a:state.b).sprint) throw new IllegalStateException("Contact did not clear sprint");
                    int relative=e.fromA()==first?0:1;
                    brakes.get(relative).add(moves[relative]);
                    sprint[side]=false;
                }
            }
        });
        var drains=new ArrayList<List<Object>>();
        double start=prefix.clock.time();
        g.clock.setAudit((at,stage,state) -> {
            g.hits.event(at,stage,state);
            sprint[0]=state.a.sprint;
            sprint[1]=state.b.sprint;
            if (stage.equals("MOVEA")) moves[first?0:1]++;
            if (stage.equals("MOVEB")) moves[first?1:0]++;
            if (!stage.equals("SERVER_DRAIN")) return;
            int tick=state.serverTicks;
            if (tick==ownFreshTick) windows.put("own",ranges(state,first,at-start));
            if (tick==defenderFreshTick) windows.put("defender",ranges(state,first,at-start));
            if (tick>=Math.min(ownFreshTick,defenderFreshTick)-1 && tick<=Math.max(ownFreshTick,defenderFreshTick)+2)
                drains.add(List.of(tick,at));
        });
        g.clock.runUntil(Math.nextDown(start+HORIZON));
        int a=0,b=0;
        for (var hit:g.hits.list) if (hit.sideA()==first) a++; else b++;
        double reply=g.hits.list.stream().filter(h->h.sideA()==first&&!h.upgrade()).mapToDouble(DuelEnv.Hit::at).findFirst().orElse(-1);
        double returned=g.hits.list.stream().filter(h->h.sideA()!=first).mapToDouble(DuelEnv.Hit::at).findFirst().orElse(-1);
        boolean clean=reply>=0 && reply-start<=850 && (returned<0 || reply<returned);
        var windowContacts=new int[2];
        for (List<Object> contact:contacts) {
            boolean own=(boolean)contact.get(1);
            double ready=(double)contact.get(2);
            int eligible=own?ownFreshTick:defenderFreshTick;
            for (var d:drains) {
                if (ready>(double)d.get(1)) continue;
                int tick=(int)d.get(0);
                if (tick>=eligible && tick<=eligible+1) windowContacts[own?0:1]++;
                break;
            }
        }
        boolean release=prefix.clock.observation(first).lastTargetHurtTime()>observer.handled || (long)start<observer.releaseUntil;
        return Map.of("points",List.of(a,b),"presses",List.of(presses[0],presses[1]),"windows",windows,
                "first_follow",reply<0?-1:reply-start,"first_return",returned<0?-1:returned-start,"clean",clean,
                "window_clock",List.of(ownFreshTick,defenderFreshTick),"window_contacts",List.of(windowContacts[0],windowContacts[1]),
                "offline_projection",BoxingFollowupProjection.actualStart(prefix,env.rtt,word.length(),release,
                                        List.of(brakes.get(0),brakes.get(1))));
    }

    private static Map<String,Object> play(DuelEnv env, boolean first, int ours, int other) {
        var observer=new Observer(env.rtt);
        var g=env.new Game(first,observer,Evolve.WT.create(env.otherRtt,0));
        if (ours!=0) g.clock.shiftFutureClientOpportunities(first,ours);
        if (other!=0) g.clock.shiftFutureClientOpportunities(!first,other);
        var openings=new Openings();
        g.clock.setAudit((at,stage,state)->{ if (stage.equals("SERVER_DRAIN")) openings.serverDrain(state); });
        var cuts=new ArrayList<Map<String,Object>>();
        if (g.clock.advanceToBeforeNextHead(first,LIMIT)) do {
            var o=g.clock.observation(first);
            int prior=observer.episode;
            observer.observe(o);
            if (observer.episode>4) break;
            if (observer.episode==prior) continue;
            int delay=g.clock.config.hitTicks;
            int own=(first?openings.a:openings.b)+delay, defender=(first?openings.b:openings.a)+delay;
            var arms=new LinkedHashMap<String,Object>();
            var forecasts=new LinkedHashMap<String,Object>();
            var input=visible(observer,o);
            for (String word:List.of("","s","ss")) arms.put(word.isEmpty()?"wtap":word,fork(env,g,observer,word,own,defender));
            for (String word:List.of("","s","ss")) forecasts.put(word.isEmpty()?"wtap":word,
                    BoxingFollowupProjection.predict(o,List.copyOf(observer.goals),env.rtt,word.length(),(boolean)input.get("stock_release")));
            cuts.add(Map.of("cut",o.time(),"episode",observer.episode,"input",input,"arms",arms,"forecast",forecasts,
                    "incoming_first",observer.incomingFirst));
        } while (g.clock.runHeadInterval(first,LIMIT));
        return Map.of("clock",env.clockId,"seat",first?"a":"b","phase",List.of(ours,other),"rtt",env.rtt,"cuts",cuts);
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
