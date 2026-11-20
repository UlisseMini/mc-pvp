import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** Replay ordinary movement for a few heads after the first locally observed reply. */
final class BoxingReplySpacing {
    private record Step(String name, int key, float strafe) {}
    private record Pulse(Step step, int skip, int length) {
        List<Object> label() { return List.of(step.name, skip, length); }
    }

    private static final List<Step> STEPS = List.of(
            new Step("release", PeriodicComboFightModel.R, 0),
            new Step("back", PeriodicComboFightModel.BACK, 0),
            new Step("walk", PeriodicComboFightModel.W, 0),
            new Step("sprint", PeriodicComboFightModel.S, 0),
            new Step("left", PeriodicComboFightModel.S, .98f),
            new Step("right", PeriodicComboFightModel.S, -.98f));
    private static final List<Pulse> PULSES = pulses();

    private static List<Pulse> pulses() {
        var result = new ArrayList<Pulse>();
        for (var step : STEPS) {
            for (int skip = 0; skip <= 5; skip++) {
                for (int length = 1; length <= 3; length++) result.add(new Pulse(step, skip, length));
            }
        }
        return List.copyOf(result);
    }

    static List<List<Object>> labels() { return PULSES.stream().map(Pulse::label).toList(); }

    private static final class Movement extends BoxingReentryRelease.Gate {
        final Pulse pulse;
        final double cut;
        double incoming = Double.NaN, replied = Double.NaN, began = Double.NaN;
        int heads, changed;

        Movement(DuelEnv.Player source, double cut, BoxingReentryRelease.Delay delay, Pulse pulse) {
            super(source, own -> delay.permits(cut, own.time(), own.lastOwnHurtTime(), own.own().y()));
            this.cut = cut;
            this.pulse = pulse;
        }

        @Override public PeriodicComboClock.Control input(PeriodicComboClock.Observation own) {
            var normal = super.input(own);
            double hurt = own.lastOwnHurtTime();
            if (Double.isNaN(incoming) && hurt >= cut) incoming = hurt;
            // A second received hurt cue ends this local reply opportunity.
            if (!opened || !(hurt == incoming)) return normal;
            if (Double.isNaN(replied) && own.lastTargetHurtTime() > incoming) replied = own.lastTargetHurtTime();
            if (Double.isNaN(replied)) return normal;
            int index = heads++;
            if (index < pulse.skip || index >= pulse.skip + pulse.length) return normal;
            var step = pulse.step;
            if (normal.key() != step.key || normal.strafe() != step.strafe) {
                if (changed++ == 0) began = own.time();
            }
            return new PeriodicComboClock.Control(step.key, step.strafe, normal.click(), normal.jump());
        }
    }

    static List<List<Object>> measure(DuelEnv env, DuelEnv.Game prefix, double cut, double end,
                                      BoxingReentryRelease.Delay delay) {
        var rows = new ArrayList<List<Object>>(PULSES.size());
        for (var pulse : PULSES) {
            var subject = new Movement(prefix.our(), cut, delay, pulse);
            var game = fork(env, prefix, subject);
            game.clock.runUntil(end);
            rows.add(outcome(game, cut, subject));
        }
        return rows;
    }

    static Map<String, Object> trace(DuelEnv env, DuelEnv.Game prefix, double cut, double end,
                                     BoxingReentryRelease.Delay delay, String name) {
        var pulse = PULSES.stream()
                .filter(p -> name.equals(p.step.name + "," + p.skip + "," + p.length))
                .findFirst().orElseThrow();
        var subject = new Movement(prefix.our(), cut, delay, pulse);
        var game = fork(env, prefix, subject);
        var exchange = new BoxingReentryRelease.Exchange(game, cut);
        game.clock.setClickAudit(event -> exchange.click(game, cut, event));
        game.clock.runUntil(end);
        return Map.of("pulse", pulse.label(), "score", outcome(game, cut, subject), "flight", exchange.flight.values(game.first),
                "events", exchange.events, "boundary", exchange.boundaryValues());
    }

    private static DuelEnv.Game fork(DuelEnv env, DuelEnv.Game prefix, Movement subject) {
        var game = env.new Game(prefix, prefix.first ? subject : prefix.a.copy(),
                prefix.first ? prefix.b.copy() : subject);
        game.trace();
        return game;
    }

    private static List<Object> outcome(DuelEnv.Game game, double cut, Movement subject) {
        int margin = 0, fresh = 0;
        for (var hit : game.hits.list) {
            int change = hit.sideA() == game.first ? 1 : -1;
            margin += change;
            if (!hit.upgrade()) fresh += change;
        }
        var hits = game.hits.list;
        var reply = BoxingOpeningRecoil.score(hits, game.first);
        return Arrays.asList(margin, fresh, BoxingReentryTiming.openingChain(hits, game.first, 850),
                BoxingReentryTiming.openingChain(hits, !game.first, 850),
                BoxingReentryTiming.openingChain(hits, game.first, 100.0 * game.clock.config.hitTicks + 100),
                reply.incoming(), reply.counter(), subject.changed,
                Double.isFinite(subject.began) ? subject.began - cut : null);
    }
}
