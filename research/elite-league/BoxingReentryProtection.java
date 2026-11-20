import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lab.Json;

/** Offline search for ordinary footwork that denies the replacement return after a re-entry. */
final class BoxingReentryProtection {
    private static final int LAST_START_HEAD=14;

    record Score(int margin, double firstOwn, double firstReturn, int chain) {
        List<Number> values() { return List.of(margin,firstOwn,firstReturn,chain); }
    }

    static Score score(BoxingLandingReentry.Run run, boolean first, double cut) {
        return score(run.hits(),first,cut);
    }

    static Score score(List<DuelEnv.Hit> hits, boolean first, double cut) {
        int margin=0,chain=0;
        double own=-1,ret=-1,last=-1;
        for (var hit:hits) {
            if (hit.sideA()!=first) {
                margin--;
                if (ret<0) ret=hit.at();
            } else {
                margin++;
                if (hit.upgrade()) continue;
                if (own<0) own=hit.at();
                if (ret<0 && (chain==0 || hit.at()-last<=1100)) {
                    last=hit.at();
                    chain++;
                }
            }
        }
        if (chain>0 && last==ret) chain--;
        return new Score(margin,own<0?-1:own-cut,ret<0?-1:ret-cut,chain);
    }

    private static Map<String,Object> study(DuelEnv env,DuelEnv.Game game, BoxingLandingReentry.Scout scout,
                                             PeriodicComboClock.Observation cut, int ownUpload, int defenderUpload) {
        return study(env,game,scout,cut,new BoxingLandingReentry.Timing(0,0,ownUpload,defenderUpload));
    }

    private static Map<String,Object> study(DuelEnv env,DuelEnv.Game game, BoxingLandingReentry.Scout scout,
                                             PeriodicComboClock.Observation cut, BoxingLandingReentry.Timing timing) {
        var stock=BoxingLandingReentry.fork(env,game,scout,cut,BoxingLandingReentry.Start.WTAP,timing,false,null);
        var pulse=BoxingLandingReentry.fork(env,game,scout,cut,BoxingLandingReentry.Start.LANDING_PLUS_ONE,timing,false,null);
        if (!stock.presses().equals(pulse.presses()))
            throw new IllegalStateException("The landing release changed physical click times");
        var rows=new ArrayList<List<Object>>();
        for (int head=2;head<=LAST_START_HEAD;head++) {
            for (int first=1;first<BoxingLateReturns.MOVES;first++) {
                for (int second=0;second<BoxingLateReturns.MOVES;second++) {
                    var adjustment=new BoxingLandingReentry.Adjustment(head,first,second);
                    var branch=BoxingLandingReentry.fork(env,game,scout,cut,BoxingLandingReentry.Start.LANDING_PLUS_ONE,
                            timing,false,adjustment);
                    if (!stock.presses().equals(branch.presses()))
                        throw new IllegalStateException("The footwork changed physical click times");
                    rows.add(List.of(head,first,second,score(branch,game.first,cut.time()).values()));
                }
            }
        }
        return Map.of("upload",List.of(timing.ownUpload(),timing.defenderUpload()),
                "wtap",score(stock,game.first,cut.time()).values(),
                "pulse",score(pulse,game.first,cut.time()).values(),"words",rows);
    }

    static List<BoxingLandingReentry.Timing> timingGrid() {
        int[][] phases={{0,0},{2,0},{5,0},{10,0},{0,-10},{0,-5},{0,-2},{0,2},{0,5},{0,10},{5,5}};
        int[][] uploads={{0,0},{5,0},{0,5},{10,0},{0,10}};
        var rows=new ArrayList<BoxingLandingReentry.Timing>();
        for (int[] phase:phases) for (int[] upload:uploads) {
            rows.add(new BoxingLandingReentry.Timing(phase[0],phase[1],upload[0],upload[1]));
        }
        return rows;
    }

    private static List<Map<String,Object>> robustSurvey(DuelEnv env,DuelEnv.Game game,BoxingLandingReentry.Scout scout,
                                                         PeriodicComboClock.Observation cut) {
        var rows=new ArrayList<Map<String,Object>>();
        for (var timing:timingGrid()) {
            var row=new java.util.LinkedHashMap<>(study(env,game,scout,cut,timing));
            row.put("phase",List.of(timing.ownPhase(),timing.defenderPhase()));
            rows.add(row);
        }
        return rows;
    }

    static List<Map<String,Object>> widen(DuelEnv env,DuelEnv.Game game,BoxingLandingReentry.Scout scout,
                                                 PeriodicComboClock.Observation cut) {
        return widen(env,game,scout,cut,timingGrid());
    }

    static List<Map<String,Object>> widen(DuelEnv env, DuelEnv.Game game, BoxingLandingReentry.Scout scout,
                                          PeriodicComboClock.Observation cut,List<BoxingLandingReentry.Timing> timings) {
        var rows=new ArrayList<Map<String,Object>>();
        var selected=BoxingLandingReentry.Adjustment.fromProperty();
        for (var timing:timings) {
            var stock=BoxingLandingReentry.fork(env,game,scout,cut,BoxingLandingReentry.Start.WTAP,timing,true,null);
            var pulse=BoxingLandingReentry.fork(env,game,scout,cut,BoxingLandingReentry.Start.LANDING_PLUS_ONE,timing,true,null);
            var back=BoxingLandingReentry.fork(env,game,scout,cut,BoxingLandingReentry.Start.LANDING_PLUS_ONE,
                    timing,true,BoxingLandingReentry.BACK);
            var lateral=BoxingLandingReentry.fork(env,game,scout,cut,BoxingLandingReentry.Start.LANDING_PLUS_ONE,
                    timing,true,BoxingLandingReentry.LATERAL);
            var backOnly=BoxingLandingReentry.fork(env,game,scout,cut,BoxingLandingReentry.Start.WTAP,
                    timing,true,BoxingLandingReentry.BACK);
            var lateralOnly=BoxingLandingReentry.fork(env,game,scout,cut,BoxingLandingReentry.Start.WTAP,
                    timing,true,BoxingLandingReentry.LATERAL);
            var arms=new java.util.LinkedHashMap<>(Map.of("wtap",stock,"pulse",pulse,"back",back,"lateral",lateral,
                    "back_only",backOnly,"lateral_only",lateralOnly));
            if (selected!=null) {
                arms.put("chosen",BoxingLandingReentry.fork(env,game,scout,cut,BoxingLandingReentry.Start.LANDING_PLUS_ONE,
                        timing,true,selected));
                arms.put("chosen_only",BoxingLandingReentry.fork(env,game,scout,cut,BoxingLandingReentry.Start.WTAP,
                        timing,true,selected));
            }
            var scored=new java.util.LinkedHashMap<String,Object>();
            arms.forEach((key,arm)->{
                if (!stock.presses().equals(arm.presses()))
                    throw new IllegalStateException("Wide protection check changed physical click timestamps");
                scored.put(key,Map.of("summary",score(arm,game.first,cut.time()).values(),
                        "contacts",arm.contacts(),"credits",arm.credits(),"defender_flight",arm.defenderFlight(),
                        "defender_replica",arm.defenderReplica(),
                        "ledger",arm.hits().stream().map(h->List.of(h.at()-cut.time(),h.sideA()==game.first,h.upgrade())).toList(),
                        "activation",arm.activation()));
            });
            rows.add(Map.of("phase",List.of(timing.ownPhase(),timing.defenderPhase()),
                    "upload",List.of(timing.ownUpload(),timing.defenderUpload()),"arms",scored));
        }
        return rows;
    }

    record Checkpoint(DuelEnv env,DuelEnv.Game game,BoxingLandingReentry.Scout scout,
                      PeriodicComboClock.Observation cut) {}

    static Checkpoint checkpoint(String[] args) throws Exception {
        return checkpoint(args,null,game->{});
    }

    static Checkpoint checkpoint(String[] args,DuelEnv.Player opponent,java.util.function.Consumer<DuelEnv.Game> setup)
            throws Exception {
        int rtt=Integer.parseInt(args[0]),ownShift=Integer.parseInt(args[1]),defenderShift=Integer.parseInt(args[2]);
        var env=BoxingEdgeHitSelect.environment(Path.of(args[5]),rtt,180_000,Path.of(args[3]),Path.of(args[4]));
        boolean first=args[6].equals("a");
        int episode=Integer.parseInt(args[7]);
        var scout=new BoxingLandingReentry.Scout(rtt);
        var game=env.new Game(first,scout,opponent==null?Evolve.WT.create(env.otherRtt,0):opponent);
        setup.accept(game);
        if (ownShift!=0) game.clock.shiftFutureClientOpportunities(first,ownShift);
        if (defenderShift!=0) game.clock.shiftFutureClientOpportunities(!first,defenderShift);
        double limit=BoxingLandingReentry.LIMIT;
        if (game.clock.advanceToBeforeNextHead(first,limit)) do {
            var cut=game.clock.observation(first);
            scout.observe(cut);
            if (scout.episode==episode) return new Checkpoint(env,game,scout,cut);
        } while (game.clock.runHeadInterval(first,limit));
        throw new IllegalStateException("Exact bilateral encounter was not reproduced");
    }

    public static void main(String[] args) throws Exception {
        var checkpoint=checkpoint(args);
        var env=checkpoint.env();
        var game=checkpoint.game();
        var scout=checkpoint.scout();
        var cut=checkpoint.cut();
        Object futures;
        if (Boolean.getBoolean("lab.reentryProtection.robustSurvey")) futures=robustSurvey(env,game,scout,cut);
        else if (Boolean.getBoolean("lab.reentryProtection.widen")) futures=widen(env,game,scout,cut);
        else futures=List.of(study(env,game,scout,cut,0,0),study(env,game,scout,cut,5,0),study(env,game,scout,cut,0,5));
        var s=game.clock.state;
        System.out.println(Json.encode(Map.of("at",cut.time(),"clock",env.clockId,"episode",scout.episode,
                "seat",args[6],"phase",List.of(Integer.parseInt(args[1]),Integer.parseInt(args[2])),"rtt",env.rtt,
                "futures",futures,"seed_points",game.first?List.of(s.acceptedA,s.acceptedB):List.of(s.acceptedB,s.acceptedA))));
    }
}
