import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lab.Json;

/** Privileged diagnosis of movement after the defender's first recoil step is already committed. */
final class BoxingAfterCounterEnvelope {
    private static final int END = 3_000;
    private static final int HEADS = 4;
    private static final int WORDS = 81;
    private static final int[][] PHASES = {{0, 0}, {0, 17}, {23, 0}, {34, 11}};
    private static final BoxingTradeSkills.Setting OPENING = new BoxingTradeSkills.Setting("hurt+100", 0, 100, false);

    private static final class MovementWord extends DuelEnv.Player {
        private int word, remaining = HEADS;

        MovementWord(DuelEnv.Player warmed, int word) {
            super("wtap", 0, warmed.rtt, 0);
            this.word = word;
            handled = warmed.handled;
            releaseUntil = warmed.releaseUntil;
            firstClick = warmed.firstClick;
        }

        @Override public PeriodicComboClock.Control input(PeriodicComboClock.Observation obs) {
            var normal = super.input(obs);
            if (remaining == 0) return normal;
            int step = word % 3;
            word /= 3;
            remaining--;
            int key = switch (step) {
                case 1 -> PeriodicComboFightModel.R;
                case 2 -> PeriodicComboFightModel.BACK;
                default -> normal.key();
            };
            return new PeriodicComboClock.Control(key, normal.strafe(), normal.click(), normal.jump());
        }
    }

    private static DuelEnv.Game start(DuelEnv env, boolean first, DuelEnv.Player defender, boolean delayed) {
        var own = delayed ? new BoxingTradeSkills.Fighter(OPENING, env.rtt)
                : new DuelEnv.Player("wtap", 0, env.rtt, 0);
        var game = env.new Game(first, own, defender);
        game.trace();
        return game;
    }

    private static List<DuelEnv.Hit> play(DuelEnv env, DuelEnv.Game prefix, int word) {
        var own = new MovementWord(prefix.our(), word);
        var game = env.new Game(prefix, prefix.first ? own : prefix.a.copy(), prefix.first ? prefix.b.copy() : own);
        game.trace();
        game.clock.runUntil(END);
        var hits = new ArrayList<>(prefix.hits.list);
        hits.addAll(game.hits.list);
        return hits;
    }

    private static void measure(DuelEnv env, boolean first, int[] phase) {
        var defender = new BoxingOpeningRecoil.Defender(env.rtt);
        var base = start(env, first, defender, true);
        var moveAt = new double[]{Double.NaN};
        String defenderMove = first ? "MOVEB" : "MOVEA";
        base.clock.setAudit((time, stage, state) -> {
            base.hits.event(time, stage, state);
            if (stage.equals(defenderMove) && defender.recoil != null && !Double.isFinite(moveAt[0])) moveAt[0] = time;
        });
        base.clock.runUntil(END);
        var baseline = BoxingOpeningRecoil.score(base.hits.list, first);
        if (!baseline.timely() || defender.recoil == null || !Double.isFinite(moveAt[0])) return;
        double move = moveAt[0], before = Math.nextDown(move);
        var impulse = defender.recoil;
        if (impulse.at() <= baseline.counter() || impulse.at() > before) return;
        var prefix = start(env, first, new BoxingOpeningRecoil.Defender(env.rtt), true);
        prefix.clock.runUntil(before);
        var q = BoxingOpeningRecoil.defender(prefix).q;
        if (q.vx() != impulse.vx() * .6 || q.vz() != impulse.vz() * .6 || q.vy() != impulse.vy()) return;
        prefix.clock.runUntil(move);
        if (!play(env, prefix, 0).equals(base.hits.list)) throw new AssertionError("W-tap control diverged");

        var plain = start(env, first, new BoxingOpeningRecoil.Defender(env.rtt), false);
        plain.clock.runUntil(END);
        int word = 0, successful = 0;
        var best = baseline;
        for (int choice = 0; choice < WORDS; choice++) {
            var outcome = BoxingOpeningRecoil.score(play(env, prefix, choice), first);
            if (outcome.followups() >= 2) successful++;
            if (outcome.followups() > best.followups()
                    || outcome.followups() == best.followups() && outcome.score() > best.score()) {
                best = outcome;
                word = choice;
            }
        }
        System.out.println(Json.encode(Map.ofEntries(Map.entry("clock", env.clockId), Map.entry("phase", phase),
                Map.entry("side", first ? "a" : "b"), Map.entry("rtt", env.rtt), Map.entry("cutMs", move),
                Map.entry("plainWtap", BoxingOpeningRecoil.score(plain.hits.list, first).values()),
                Map.entry("cueOnly", baseline.values()), Map.entry("oracle", best.values()),
                Map.entry("word", word), Map.entry("successfulWords", successful))));
    }

    public static void main(String[] args) throws Exception {
        for (String file : args) {
            var tape = DuelEnv.Tape.read(Path.of(file), 12_000);
            for (int[] phase : PHASES) {
                var shifted = BoxingDrill.shift(tape, phase[0], phase[1]);
                for (int rtt : new int[]{0, 20, 80}) {
                    var env = BoxingTradeSkills.env(shifted, rtt, 10, 6);
                    for (boolean first : new boolean[]{true, false}) measure(env, first, phase);
                }
            }
        }
    }
}
