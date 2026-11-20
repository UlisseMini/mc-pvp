import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lab.Json;

/** Offline transfer comparison for the fixed score-only movement sequences. */
final class BoxingScoreOnlyPanel {
    private record Action(String name, BoxingLandingReentry.Start start, BoxingLandingReentry.Adjustment adjustment) {}

    private static List<Action> actions(String[] specs) {
        var result=new ArrayList<Action>();
        result.add(new Action("wtap",BoxingLandingReentry.Start.WTAP,null));
        result.add(new Action("release",BoxingLandingReentry.Start.LANDING_PLUS_ONE,null));
        for (String spec:specs) {
            String[] fields=spec.split(",");
            String name=fields[0];
            var word=new BoxingLandingReentry.Adjustment(Integer.parseInt(fields[1]),Integer.parseInt(fields[2]),
                    Integer.parseInt(fields[3]));
            result.add(new Action(name,BoxingLandingReentry.Start.LANDING_PLUS_ONE,word));
            result.add(new Action(name+"_alone",BoxingLandingReentry.Start.WTAP,word));
        }
        return result;
    }

    private static Map<String,Object> compare(DuelEnv env,DuelEnv.Game game,BoxingLandingReentry.Scout scout,
            PeriodicComboClock.Observation cut,BoxingLandingReentry.Timing timing,List<Action> actions) {
        var stock=BoxingLandingReentry.fork(env,game,scout,cut,BoxingLandingReentry.Start.WTAP,timing,false,null);
        var result=new LinkedHashMap<String,Object>();
        for (Action action:actions) {
            var arm=action.adjustment()==null && action.start()==BoxingLandingReentry.Start.WTAP?stock:
                    BoxingLandingReentry.fork(env,game,scout,cut,action.start(),timing,false,action.adjustment());
            if (!stock.presses().equals(arm.presses()))
                throw new IllegalStateException("Score-only comparison changed physical click timestamps");
            result.put(action.name(),arm.hits().stream()
                    .map(h->List.of(h.at()-cut.time(),h.sideA()==game.first,h.upgrade())).toList());
        }
        return result;
    }

    private static Map<String,Object> play(DuelEnv env,boolean first,int ownPhase,int defenderPhase,List<Action> actions) {
        var scout=new BoxingLandingReentry.Scout(env.rtt);
        var game=env.new Game(first,scout,Evolve.WT.create(env.otherRtt,0));
        if (ownPhase!=0) game.clock.shiftFutureClientOpportunities(first,ownPhase);
        if (defenderPhase!=0) game.clock.shiftFutureClientOpportunities(!first,defenderPhase);
        var rows=new ArrayList<Map<String,Object>>();
        double limit=BoxingLandingReentry.LIMIT;
        if (game.clock.advanceToBeforeNextHead(first,limit)) do {
            var o=game.clock.observation(first);
            int prior=scout.episode;
            scout.observe(o);
            if (scout.episode>4) break;
            if (scout.episode==prior) continue;
            rows.add(Map.of("at",o.time(),"episode",scout.episode,"visible",BoxingNaturalReentry.legal(scout,o),
                    "nominal",compare(env,game,scout,o,new BoxingLandingReentry.Timing(0,0,0,0),actions),
                    "own_late",compare(env,game,scout,o,new BoxingLandingReentry.Timing(0,0,5,0),actions),
                    "defender_late",compare(env,game,scout,o,new BoxingLandingReentry.Timing(0,0,0,5),actions)));
        } while (game.clock.runHeadInterval(first,limit));
        return Map.of("clock",env.clockId,"rtt",env.rtt,"seat",first?"a":"b","phase",List.of(ownPhase,defenderPhase),
                "cuts",rows);
    }

    public static void main(String[] args) throws Exception {
        int rtt=Integer.parseInt(args[0]),ownPhase=Integer.parseInt(args[1]),defenderPhase=Integer.parseInt(args[2]);
        var wire=Path.of(args[3]);
        var seeds=Path.of(args[4]);
        var arms=actions(args[5].split(";"));
        for (int i=6;i<args.length;i++) {
            var env=BoxingEdgeHitSelect.environment(Path.of(args[i]),rtt,180_000,wire,seeds);
            for (boolean first:new boolean[]{true,false})
                System.out.println(Json.encode(play(env,first,ownPhase,defenderPhase,arms)));
        }
    }
}
