import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lab.Json;

/** Exact paired regulation and established-combo tests of one frozen sprint pulse. */
final class BoxingTradeFollowupRegulation {
    private static final boolean ISOLATE = Boolean.getBoolean("lab.tradeFollowup.isolateRepeat");
    private static final class Sprint extends BoxingTradeFollowup.Scout {
        private final boolean active;
        private final int limit;
        private int left;
        final List<Double> triggers = new ArrayList<>();

        Sprint(int rtt, boolean active) {
            this(rtt,active,Integer.MAX_VALUE);
        }

        Sprint(int rtt, boolean active, int limit) {
            super(rtt);
            this.active=active && rtt>=80;
            this.limit=limit;
        }

        Sprint(BoxingDrill.Checkpoint c, boolean active) {
            this(c.env.rtt,active);
            var prior=c.state.our();
            handled=prior.handled;
            releaseUntil=prior.releaseUntil;
            firstClick=prior.firstClick;
            for (var head:c.history) observe(head.view());
        }

        @Override public PeriodicComboClock.Control input(PeriodicComboClock.Observation o) {
            int before=episode;
            observe(o);
            if (active && episode>before && triggers.size()<limit) {
                left=2;
                triggers.add(o.time());
            }
            return super.input(o);
        }

        @Override public PeriodicComboClock.Control movement(PeriodicComboClock.Control control) {
            if (left==0) return control;
            left--;
            return new PeriodicComboClock.Control(PeriodicComboFightModel.S,0,control.click(),control.jump());
        }
    }

    private record Run(BoxingDoubleSearch.Score score, double end, List<List<Double>> clicks, List<Double> triggers,
                       List<DuelEnv.Hit> hits) {
        Map<String,Object> summary(double common) {
            return Map.of("score",score.values(),"end",end,"triggers",triggers,
                    "common_clicks",List.of(clicks.get(0).stream().filter(t->t<=common).count(),
                                             clicks.get(1).stream().filter(t->t<=common).count()));
        }

        int marginAt(boolean first, double at) {
            int score=0;
            for (var hit:hits) {
                if (hit.at()>at) break;
                score+=hit.sideA()==first?1:-1;
            }
            return score;
        }
    }

    private static void phase(PeriodicComboClock clock, boolean first, int own, int other) {
        if (own!=0) clock.shiftFutureClientOpportunities(first,own);
        if (other!=0) clock.shiftFutureClientOpportunities(!first,other);
    }

    private static Run play(DuelEnv env, boolean first, int own, int other, boolean active, int limit) {
        var fighter=new Sprint(env.rtt,active,limit);
        var g=env.new Game(first,fighter,Evolve.WT.create(env.otherRtt,0));
        phase(g.clock,first,own,other);
        g.trace();
        var clicks=List.of(new ArrayList<Double>(),new ArrayList<Double>());
        g.clock.setClickAudit(e -> {
            if (e.action().equals("ADMIT")) clicks.get(e.fromA()==first?0:1).add(e.admittedNanos()/1e6);
        });
        var score=BoxingDoubleSearch.play(g,100);
        return new Run(score,BoxingEdgeHitSelect.decidingHit(g),List.of(clicks.get(0),clicks.get(1)),fighter.triggers,g.hits.list);
    }

    private static Map<String,Object> regulation(DuelEnv env, boolean first, int own, int other) {
        var w=play(env,first,own,other,false,0);
        var s=play(env,first,own,other,true,Integer.MAX_VALUE);
        double cut=Math.min(w.end,s.end);
        requireMatchedClicks(w,s,cut);
        if (!ISOLATE) return Map.of("common",cut,"wtap",w.summary(cut),"sprint",s.summary(cut));
        var once=play(env,first,own,other,true,1);
        requireMatchedClicks(w,once,Math.min(w.end,once.end));
        double from=s.triggers.isEmpty()?0:s.triggers.get(0);
        double to=from+1350;
        int initial=w.marginAt(first,from);
        if (once.marginAt(first,from)!=initial || s.marginAt(first,from)!=initial)
            throw new IllegalStateException("Scoring diverged before the pulse");
        return Map.of("common",cut,"wtap",w.summary(cut),"sprint",s.summary(cut),
                "once",once.summary(Math.min(w.end,once.end)),"first_window",List.of(
                w.marginAt(first,to)-initial,s.marginAt(first,to)-initial,once.marginAt(first,to)-initial));
    }

    private static void requireMatchedClicks(Run w, Run s, double cut) {
        for (int i=0;i<2;i++) if (!w.clicks.get(i).stream().filter(t->t<=cut).toList()
                .equals(s.clicks.get(i).stream().filter(t->t<=cut).toList()))
            throw new IllegalStateException("Changing combat movement changed the physical press calendar");
    }

    private static Map<String,Object> maintained(BoxingDrill.Checkpoint checkpoint) {
        var env=checkpoint.env;
        var w=checkpoint.suffix(null);
        var sprint=new Sprint(checkpoint,true);
        var chosen=env.new Game(w,w.first?sprint:w.a.copy(),w.first?w.b.copy():sprint);
        chosen.trace();
        chosen.hits.list.addAll(w.hits.list);
        w.clock.runUntil(env.limit);
        chosen.clock.runUntil(env.limit);
        var a=BoxingLateReturns.score(w,checkpoint.landmark,1100);
        var b=BoxingLateReturns.score(chosen,checkpoint.landmark,1100);
        if (sprint.triggers.isEmpty() && !w.hits.list.equals(chosen.hits.list))
            throw new IllegalStateException("Probe modified a maintenance run without a trigger");
        return Map.of("k",checkpoint.k,"wtap",a.values(),"sprint",b.values(),"triggers",sprint.triggers);
    }

    public static void main(String[] args) throws Exception {
        int rtt=Integer.parseInt(args[0]), own=Integer.parseInt(args[1]), other=Integer.parseInt(args[2]);
        var wire=Path.of(args[3]);
        var seeds=Path.of(args[4]);
        for (int i=5;i<args.length;i++) {
            var file=Path.of(args[i]);
            var env=BoxingEdgeHitSelect.environment(file,rtt,180_000,wire,seeds);
            for (boolean first:new boolean[]{true,false}) {
                var pairs=regulation(env,first,own,other);
                var maintained=new ArrayList<Map<String,Object>>();
                if (rtt>=80 && !ISOLATE) {
                    var check=BoxingDoubleSearch.environment(file,env.profile,rtt,20,20_000);
                    // Keep the empirical route knots from the full timing source. A shorter drill
                    // horizon limits simulated events and leaves transport through that boundary intact.
                    check.initial.state.packetTiming=env.initial.state.packetTiming.deepCopy();
                    check.initial.state.extraLatency=env.initial.state.extraLatency.clone();
                    BoxingJumpTimingAudit.seedTracker(check,seeds.resolve(env.clockId+".txt"));
                    phase(check.initial,first,own,other);
                    for (var anchor:BoxingDrill.acquire(check,first,true)) maintained.add(maintained(anchor));
                }
                System.out.println(Json.encode(Map.of("clock",env.clockId,"rtt",rtt,"seat",first?"a":"b",
                        "phase",List.of(own,other),"regulation",pairs,"maintenance",maintained)));
            }
        }
    }
}
