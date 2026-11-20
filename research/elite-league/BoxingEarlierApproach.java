import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lab.BoxingApproachPoll;
import lab.Json;

/** Test one short movement change from a grounded, fast, quiet approach. */
final class BoxingEarlierApproach {
    private static final double HORIZON = 1800;
    private static final double SEARCH_LIMIT = 30_000;
    private static final String SELECTED_ARM = System.getProperty("lab.earlier.arm", "");
    private static final String SELECTED_SEAT = System.getProperty("lab.earlier.seat", "");
    private static final int SELECTED_BAND = Integer.getInteger("lab.earlier.band", -1);
    private static final boolean CONTACT_TIMING = Boolean.getBoolean("lab.earlier.contactTiming");
    private static final boolean RECOIL_STRESS = Boolean.getBoolean("lab.earlier.recoilStress");
    private static final boolean TRACE_RETURN = Boolean.getBoolean("lab.earlier.traceReturn");
    private static final double[] BANDS = {3.70, 4.10, 4.65, 5.20};
    private static final int[] WAITS = {0, 35, 55, 75};
    // r releases forward, b backs up, w walks forward, s sprints forward,
    // l/d are diagonal sprint, and a/e are lateral movement without forward.
    private static final List<String> WORDS = List.of("", "r", "b", "w", "l", "d", "a", "e",
            "rr", "bb", "ww", "ll", "dd", "aa", "ee", "rs", "bs", "as", "es", "ls", "ds",
            "rrs", "bbs", "aas", "ees", "lll", "ddd", "rrrs", "bbbs", "llll", "dddd");

    private static final class DelayContact extends BoxingApproachPollCompare.Fighter {
        private final double until;

        DelayContact(BoxingApproachPollCompare.Fighter source, double until) {
            super(source,null);
            this.until = until;
        }

        @Override public boolean allowInjection(PeriodicComboClock.Observation own, String source,
                                                java.util.function.BooleanSupplier contactReady) {
            if (own.time()<until && contactReady.getAsBoolean()) return false;
            return super.allowInjection(own,source,contactReady);
        }
    }

    private static double toward(PeriodicComboClock.Observation o) {
        double dx=o.targetX()-o.own().x(), dz=o.targetZ()-o.own().z();
        return (o.own().vx()*dx+o.own().vz()*dz)/Math.max(.001,Math.hypot(dx,dz));
    }

    static int band(PeriodicComboClock.Observation o) {
        if (!o.own().onGround() || Math.abs(o.targetY()-o.own().y())>.05 || toward(o)<.13 ||
                o.time()-Math.max(o.lastTargetHurtTime(),o.lastOwnHurtTime())<1100) return -1;
        double nearest=o.nearest();
        for (int i=0;i+1<BANDS.length;i++) if (nearest>=BANDS[i] && nearest<BANDS[i+1]) return i;
        return -1;
    }

    private static Map<String,Object> recoilStress(DuelEnv env, DuelEnv.Game prefix, int wait, String word,
                                                   boolean stock, Map<String,Object> reference) {
        var results=new LinkedHashMap<String,Object>();
        var subject=(BoxingApproachPollCompare.Fighter)prefix.our();
        var defender=prefix.first?prefix.b:prefix.a;
        double at=prefix.clock.time();
        for (double scale : new double[]{0,.6,1}) {
            var probe=new BoxingApproachFootwork.FirstRecoil(defender,scale);
            var actor=stock?subject.copy():new BoxingApproachFootwork.Fighter(subject,wait,word,true);
            var branch=BoxingApproachFootwork.replay(env,prefix,actor,at,HORIZON,probe);
            var result=branch.summary(prefix.first);
            if (scale==0 && !result.equals(reference))
                throw new IllegalStateException("Observing the first step changed the reference");
            results.put(Double.toString(scale),Map.of("score",result,"step",probe.result(at)));
        }
        if (TRACE_RETURN && (stock || SELECTED_ARM.equals(wait+":"+(word.isEmpty()?"k":word)))) {
            var returns=new LinkedHashMap<String,Object>();
            for (int advance : new int[]{0,2,5,10,20}) {
                var probe=new BoxingApproachFootwork.FirstRecoil(defender,.6,advance,true);
                var actor=stock?subject.copy():new BoxingApproachFootwork.Fighter(subject,wait,word,true);
                var branch=BoxingApproachFootwork.replay(env,prefix,actor,at,HORIZON,probe);
                var score=branch.summary(prefix.first);
                if (advance==0 && !results.get("0.6").equals(Map.of("score",score,"step",probe.result(at))))
                    throw new IllegalStateException("Return audit changed the unmodified trajectory");
                returns.put(Integer.toString(advance),Map.of("score",score,"events",probe.returns));
            }
            results.put("return_traces",returns);
        }
        return results;
    }

    private static Map<String,Object> fork(DuelEnv env, DuelEnv.Game game, BoxingApproachPollCompare.Fighter subject,
                                           int band, PeriodicComboClock.Observation own) {
        double at=game.clock.time();
        var arms = new LinkedHashMap<String,Map<String,Object>>();
        var plain=BoxingApproachFootwork.replay(env,game,subject.copy(),at,HORIZON).summary(game.first);
        arms.put("wtap",plain);
        for (int wait:WAITS) for (String word:WORDS) {
            String key=wait+":"+(word.isEmpty()?"k":word);
            if (!SELECTED_ARM.isEmpty() && !key.equals(SELECTED_ARM) && !key.equals("0:k") &&
                    !key.equals(SELECTED_ARM.split(":")[0]+":k") && !key.equals("0:"+SELECTED_ARM.split(":")[1]))
                continue;
            var fighter=new BoxingApproachFootwork.Fighter(subject,wait,word,true);
            var branch=BoxingApproachFootwork.replay(env,game,fighter,at,HORIZON);
            if (branch.movement().size()!=word.length()) throw new IllegalStateException("Unevaluated movement input");
            arms.put(key,branch.summary(game.first));
        }
        if (!plain.equals(BoxingApproachFootwork.replay(env,game,subject.copy(),at,HORIZON).summary(game.first)))
            throw new IllegalStateException("Candidate changed the shared W-tap prefix");
        if (CONTACT_TIMING) for (int wait=100;wait<=600;wait+=10) {
            var fighter=new DelayContact(subject,at+wait);
            var branch=BoxingApproachFootwork.replay(env,game,fighter,at,HORIZON);
            arms.put("click:"+wait,branch.summary(game.first));
        }
        var result=new LinkedHashMap<String,Object>(Map.of("band",band,"at",at,
                "cut",List.of(own.nearest(),toward(own),own.sprint(),subject.poll.arms()),
                "prior_deferrals",subject.awaits.size(),"arms",arms));
        if (RECOIL_STRESS) {
            var stress=new LinkedHashMap<String,Object>();
            for (var arm : arms.entrySet()) {
                var key=arm.getKey();
                if (key.startsWith("click:")) continue;
                boolean stock=key.equals("wtap");
                var parts=stock?new String[]{"0","k"}:key.split(":");
                int wait=Integer.parseInt(parts[0]);
                String word=parts[1].equals("k")?"":parts[1];
                stress.put(key,recoilStress(env,game,wait,word,stock,arm.getValue()));
            }
            result.put("recoil_stress",stress);
        }
        return result;
    }

    private static Map<String,Object> play(DuelEnv env, boolean first, int ownPhase, int otherPhase) {
        var subject=new BoxingApproachPollCompare.Fighter(env.rtt,BoxingApproachPoll.Mode.INPUT_TICK,null);
        var game=env.new Game(first,subject,Evolve.WT.create(env.otherRtt,0));
        if (ownPhase!=0) game.clock.shiftFutureClientOpportunities(first,ownPhase);
        if (otherPhase!=0) game.clock.shiftFutureClientOpportunities(!first,otherPhase);
        var cuts=new ArrayList<Map<String,Object>>();
        var used=new boolean[BANDS.length-1];
        if (game.clock.advanceToBeforeNextHead(first,SEARCH_LIMIT)) do {
            var own=game.clock.observation(first);
            int band=band(own);
            if (band>=0 && !used[band] && (SELECTED_BAND<0 || SELECTED_BAND==band)) {
                used[band]=true;
                cuts.add(fork(env,game,subject,band,own));
            }
        } while (cuts.size()<(SELECTED_BAND>=0?1:used.length) && game.clock.runHeadInterval(first,SEARCH_LIMIT));
        return Map.of("clock",env.clockId,"rtt",env.rtt,"seat",first?"a":"b",
                "phase",List.of(ownPhase,otherPhase),"cuts",cuts);
    }

    public static void main(String[] args) throws Exception {
        int rtt=Integer.parseInt(args[0]);
        int ownPhase=Integer.parseInt(args[1]), otherPhase=Integer.parseInt(args[2]);
        var wire=Path.of(args[3]);
        var seeds=Path.of(args[4]);
        for (int i=5;i<args.length;i++) {
            var env=BoxingEdgeHitSelect.environment(Path.of(args[i]),rtt,180_000,wire,seeds);
            for (boolean first:new boolean[]{true,false}) if (SELECTED_SEAT.isEmpty() || SELECTED_SEAT.equals(first?"a":"b"))
                System.out.println(Json.encode(play(env,first,ownPhase,otherPhase)));
        }
    }
}
