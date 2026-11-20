import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import lab.BoxingApproachPoll;
import lab.Json;

/** Offline check of when ordinary movement can protect the lead from an earlier double. */
final class BoxingAfterDouble {
    private static final double SHORT = 1800;
    private static final double HORIZON = 3000;
    private static final double RECOIL = Double.parseDouble(System.getProperty("lab.afterDouble.recoil", "0.6"));
    private static final String ONLY = System.getProperty("lab.afterDouble.arm", "");
    private static final List<String> WORDS = List.of("r","rr","rrr","b","bb","bbb","s","ss","a","aa","e","ee","l","ll","d","dd");
    private static final List<Integer> AGES = List.of(250,350,450,-1);

    private static final class Continuation extends BoxingApproachFootwork.Fighter {
        final double cut;
        final int cueAge;
        final String pulse;
        final List<List<Object>> heads = new ArrayList<>();
        double firstCue = Double.NaN;
        double secondCueHead = -1;
        double started = -1;
        int pulseIndex;

        Continuation(BoxingApproachPollCompare.Fighter prior, int wait, String opening, double cut, int cueAge,
                     String pulse) {
            super(prior,wait,opening,true);
            this.cut=cut;
            this.cueAge=cueAge;
            this.pulse=pulse;
        }

        @Override public boolean allowInjection(PeriodicComboClock.Observation o, String source,
                                                BooleanSupplier selected) {
            return word.isEmpty() || super.allowInjection(o,source,selected);
        }

        @Override public PeriodicComboClock.Control input(PeriodicComboClock.Observation o) {
            var stock=super.input(o);
            double cue=o.lastTargetHurtTime();
            if (cue<cut) return stock;
            if (Double.isNaN(firstCue)) firstCue=cue;
            boolean second=cue>firstCue;
            if (second && secondCueHead<0) secondCueHead=o.time();
            if (pulse.isEmpty()) heads.add(List.of(o.time(),cue,o.nearest(),o.sprint(),o.own().onGround()));
            if (pulseIndex>=pulse.length() || index<word.length()) return stock;
            if (started<0) {
                if (cueAge<0 ? !second : second || o.time()-firstCue<cueAge) return stock;
                started=o.time();
            }
            char input=pulse.charAt(pulseIndex++);
            int key=switch(input) {
                case 'r','a','e' -> PeriodicComboFightModel.R;
                case 'b' -> PeriodicComboFightModel.BACK;
                case 's','l','d' -> PeriodicComboFightModel.S;
                default -> throw new IllegalArgumentException("Unknown movement key "+input);
            };
            float strafe=switch(input) {
                case 'a','l' -> .98f;
                case 'e','d' -> -.98f;
                default -> 0;
            };
            return new PeriodicComboClock.Control(key,strafe,stock.click(),stock.jump());
        }
    }

    private static List<Integer> score(List<DuelEnv.Hit> hits, boolean first, double end) {
        int own=0,other=0;
        for (var h : hits) {
            if (h.at()>end) break;
            if (h.sideA()==first) own++; else other++;
        }
        return List.of(own,other);
    }

    private static Map<String,Object> arm(BoxingApproachFootwork.Branch replay, boolean first, double cut,
                                         double follow, double returned) {
        var result=new LinkedHashMap<>(replay.summary(first));
        result.put("short_score",score(replay.hits(),first,Math.nextDown(cut+SHORT)));
        result.put("follow_plus_250_score",score(replay.hits(),first,follow+250));
        result.put("follow_plus_500_score",score(replay.hits(),first,follow+500));
        result.put("at_original_return",score(replay.hits(),first,returned));
        return result;
    }

    private static Map<String,Object> screen(DuelEnv env, DuelEnv.Game prefix, BoxingApproachPollCompare.Fighter subject,
                                              int wait, String word) {
        boolean first=prefix.first;
        double cut=prefix.clock.time();
        var defender=first?prefix.b:prefix.a;
        var start=new Continuation(subject,wait,word,cut,-1,"");
        var traced=new BoxingApproachFootwork.FirstRecoil(defender,RECOIL,0,true);
        var normal=BoxingApproachFootwork.replay(env,prefix,start,cut,HORIZON,traced);
        var shortActor=word.isEmpty()?subject.copy():new BoxingApproachFootwork.Fighter(subject,wait,word,true);
        var baseShort=BoxingApproachFootwork.replay(env,prefix,shortActor,
                cut,SHORT,new BoxingApproachFootwork.FirstRecoil(defender,RECOIL));
        var full=normal.summary(first);
        var shortArm=baseShort.summary(first);
        double follow=(double)shortArm.get("follow");
        var stockProbe=new BoxingApproachFootwork.FirstRecoil(defender,RECOIL);
        var stock=BoxingApproachFootwork.replay(env,prefix,subject.copy(),cut,HORIZON,stockProbe);
        double returned=(double)full.get("first_return");
        var arms=new LinkedHashMap<String,Object>();
        arms.put("wtap",arm(stock,first,cut,follow,returned));
        arms.put("opening",arm(normal,first,cut,follow,returned));
        for (int age : AGES) for (String pulse : WORDS) {
            String key=age+":"+pulse;
            if (!ONLY.isEmpty() && !key.equals(ONLY)) continue;
            var fighter=new Continuation(subject,wait,word,cut,age,pulse);
            var probe=new BoxingApproachFootwork.FirstRecoil(defender,RECOIL);
            var branch=BoxingApproachFootwork.replay(env,prefix,fighter,cut,HORIZON,probe);
            if (fighter.started<0 && (!branch.hits().equals(normal.hits()) || !branch.presses().equals(normal.presses())))
                throw new IllegalStateException("Unactivated movement changed the baseline");
            var data=arm(branch,first,cut,follow,returned);
            data.put("movement_at",fighter.started);
            data.put("movement_steps",fighter.pulseIndex);
            arms.put(key,data);
        }
        return Map.of("cut",cut,"short",shortArm,"arms",arms,"recoil",traced.result(cut),"heads",start.heads,
                "second_cue_head",start.secondCueHead,"return_trace",traced.returns);
    }

    public static void main(String[] args) throws Exception {
        int rtt=Integer.parseInt(args[0]);
        int ours=Integer.parseInt(args[1]), other=Integer.parseInt(args[2]);
        int band=Integer.parseInt(args[3]), wait=Integer.parseInt(args[4]);
        String word=args[5];
        boolean first=args[6].equals("a");
        var env=BoxingEdgeHitSelect.environment(Path.of(args[9]),rtt,180_000,Path.of(args[7]),Path.of(args[8]));
        var subject=new BoxingApproachPollCompare.Fighter(rtt,BoxingApproachPoll.Mode.INPUT_TICK,null);
        var prefix=env.new Game(first,subject,Evolve.WT.create(env.otherRtt,0));
        if (ours!=0) prefix.clock.shiftFutureClientOpportunities(first,ours);
        if (other!=0) prefix.clock.shiftFutureClientOpportunities(!first,other);
        double limit=30_000;
        if (prefix.clock.advanceToBeforeNextHead(first,limit)) do {
            if (BoxingEarlierApproach.band(prefix.clock.observation(first))==band) {
                System.out.println(Json.encode(screen(env,prefix,subject,wait,word)));
                return;
            }
        } while (prefix.clock.runHeadInterval(first,limit));
        throw new IllegalStateException("Original approach cut absent");
    }
}
