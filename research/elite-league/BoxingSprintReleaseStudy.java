import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lab.Json;

/** Test a one-tick Ctrl release using only a received hurt cue and a real local contact. */
final class BoxingSprintReleaseStudy {
    private static final double HORIZON = 2_200;
    private static final double HURT_WINDOW = 450;
    private static final int[] RTT = {0, 20, 80};

    record Rule(int lead, boolean geometry) {
        String label() { return lead + (geometry ? "-ground-range" : "-time"); }
    }

    static final class Release extends DuelEnv.Player {
        final Rule rule;
        double lastReleaseCue = Double.NEGATIVE_INFINITY;
        final List<List<Double>> actions = new ArrayList<>();

        Release(DuelEnv.Player warmed, Rule rule) {
            super("wtap", 0, warmed.rtt, warmed.wake);
            this.rule = rule;
            handled = warmed.handled;
            releaseUntil = warmed.releaseUntil;
            firstClick = warmed.firstClick;
        }

        @Override public boolean releaseSprintForMove(PeriodicComboClock.Observation obs) {
            double cue = obs.lastTargetHurtTime();
            if (!Double.isFinite(cue) || cue <= lastReleaseCue || obs.lastOwnHurtTime() > cue) return false;
            double age = obs.time() - cue;
            if (Math.abs(age - (HURT_WINDOW - rtt - rule.lead)) > 28) return false;
            double nearest = obs.nearest(), height = obs.targetY() - obs.own().y();
            if (rule.geometry && (!obs.own().onGround() || nearest < 2.35 || nearest > 2.95 || height < .1 || height > .65))
                return false;
            lastReleaseCue = cue;
            actions.add(List.of(obs.time(), age, nearest, height));
            return true;
        }
    }

    private record Result(int activeFresh, int continuedFresh, int margin, Double returned,
                          double returnExposure, double activeExposure, String activeEnd, List<List<Double>> release) {
        Map<String,Object> values() {
            var values = new java.util.LinkedHashMap<String,Object>();
            values.put("activeFresh", activeFresh);
            values.put("continuedFresh", continuedFresh);
            values.put("margin", margin);
            values.put("returnedMs", returned);
            values.put("returnExposureMs", returnExposure);
            values.put("activeExposureMs", activeExposure);
            values.put("activeEnd", activeEnd);
            values.put("releases", release);
            return values;
        }
    }

    private static final class LaunchTrace {
        List<Double> firstOwn;

        LaunchTrace(DuelEnv.Game game) {
            game.clock.setAudit((time, stage, state) -> {
                int before = game.hits.list.size();
                game.hits.event(time, stage, state);
                if (firstOwn != null) return;
                for (int i = before; i < game.hits.list.size(); i++) {
                    var hit = game.hits.list.get(i);
                    if (hit.sideA() == game.first && !hit.upgrade()) {
                        var impulse = game.first ? state.lastFreshB : state.lastFreshA;
                        firstOwn = List.of(time, Math.hypot(impulse.x(), impulse.z()), impulse.y());
                        break;
                    }
                }
            });
        }
    }

    private static Result measure(List<DuelEnv.Hit> hits, boolean first, double anchor, double finish,
                                  List<List<Double>> actions) {
        int streak = 0, active = 0, margin = 0;
        double lastActive = anchor;
        Double returned = null;
        for (var hit : hits) {
            margin += hit.sideA() == first ? 1 : -1;
            if (returned != null) continue;
            if (hit.sideA() != first) returned = hit.at() - anchor;
            else if (!hit.upgrade()) {
                if (active == streak && hit.at() - lastActive <= 1000) {
                    active++;
                    lastActive = hit.at();
                }
                streak++;
            }
        }
        double exposure = returned == null ? finish - anchor : returned;
        double activeExposure = Math.min(exposure, lastActive + 1000 - anchor);
        String end = activeExposure < exposure ? "inactive" : returned == null ? "censored" : "return";
        return new Result(active, streak, margin, returned, exposure, activeExposure, end, actions);
    }

    private static DuelEnv.Game start(DuelEnv env, boolean first) {
        var game = env.new Game(first, new DuelEnv.Player("wtap", 0, env.rtt, 0),
                new DuelEnv.Player("wtap", 0, env.rtt, 2_000));
        game.trace();
        return game;
    }

    private static List<Integer> anchors(DuelEnv.Game sample) {
        var anchors = new ArrayList<Integer>();
        double awake = Math.max(sample.a.firstClick, sample.b.firstClick);
        boolean afterReturn = true;
        for (int i = 0; i < sample.hits.list.size(); i++) {
            var hit = sample.hits.list.get(i);
            if (hit.at() < awake) continue;
            if (hit.at() + HORIZON > sample.clock.time()) break;
            if (hit.sideA() != sample.first) afterReturn = true;
            else if (afterReturn && !hit.upgrade()) {
                anchors.add(i);
                afterReturn = false;
            }
        }
        return anchors;
    }

    private static List<DuelEnv.Hit> acceptedAfter(List<DuelEnv.Hit> committed, DuelEnv.Game fork) {
        var hits = new ArrayList<>(committed);
        hits.addAll(fork.hits.list);
        return hits;
    }

    private static void forks(Path file, int rtt, boolean first, List<Rule> rules) throws Exception {
        var env = BoxingDoubleSearch.environment(file, rtt, 20, 20_000);
        var sample = start(env, first);
        sample.finish();
        for (int index : anchors(sample)) {
            double anchor = sample.hits.list.get(index).at();
            var prefix = start(env, first);
            prefix.clock.runUntil(anchor);
            if (!prefix.hits.list.equals(sample.hits.list.subList(0, prefix.hits.list.size())))
                throw new AssertionError("W-tap prefix diverged");
            var committed = prefix.hits.list.subList(index + 1, prefix.hits.list.size());
            var wtap = env.new Game(prefix, prefix.a.copy(), prefix.b.copy());
            wtap.trace();
            var plainTrace = new LaunchTrace(wtap);
            wtap.clock.runUntil(anchor + HORIZON);
            var expected = sample.hits.list.subList(index + 1, sample.hits.list.size()).stream()
                    .filter(h -> h.at() <= anchor + HORIZON).toList();
            var unmodified = acceptedAfter(committed, wtap);
            if (!expected.equals(unmodified)) throw new AssertionError("W-tap replay diverged");
            var base = measure(unmodified, first, anchor, anchor + HORIZON, List.of());
            for (var rule : rules) {
                var candidate = new Release(prefix.our(), rule);
                var fork = env.new Game(prefix, first ? candidate : prefix.a.copy(), first ? prefix.b.copy() : candidate);
                fork.trace();
                var candidateTrace = new LaunchTrace(fork);
                fork.clock.runUntil(anchor + HORIZON);
                var row = new java.util.LinkedHashMap<String,Object>();
                row.putAll(Map.of("clock", env.clockId, "rtt", rtt, "side", first ? "a" : "b", "anchor", anchor,
                        "rule", rule.label(), "wtap", base.values(), "candidate",
                        measure(acceptedAfter(committed, fork), first, anchor, anchor + HORIZON, candidate.actions).values()));
                row.put("wtapNextLaunch", plainTrace.firstOwn);
                row.put("candidateNextLaunch", candidateTrace.firstOwn);
                System.out.println(Json.encode(row));
            }
        }
    }

    private static void maintenance(Path file, int rtt, boolean first, Rule rule) throws Exception {
        var env = BoxingDoubleSearch.environment(file, rtt, 20, 20_000);
        for (var checkpoint : BoxingDrill.acquire(env, first, true)) {
            var baseline = checkpoint.suffix(null);
            baseline.finish();
            double anchor = checkpoint.landmark;
            var control = measure(baseline.hits.list, first, anchor, baseline.clock.time(), List.of());
            if (control.activeFresh != checkpoint.baseline.cycles()
                    || !control.activeEnd.equals(checkpoint.baseline.terminal())
                    || Math.abs(control.activeExposure - checkpoint.baseline.ms()) > 1e-8)
                throw new AssertionError("Maintenance scoring diverged");
            var prefix = checkpoint.state;
            var policy = new Release(prefix.our(), rule);
            var fork = env.new Game(prefix, first ? policy : prefix.a.copy(), first ? prefix.b.copy() : policy);
            fork.trace();
            fork.finish();
            var candidate = measure(acceptedAfter(checkpoint.committed, fork), first, anchor,
                    fork.clock.time(), policy.actions);
            System.out.println(Json.encode(Map.of("clock", env.clockId, "rtt", rtt, "side", first ? "a" : "b",
                    "k", checkpoint.k, "anchor", anchor, "rule", rule.label(), "wtap", control.values(),
                    "candidate", candidate.values())));
        }
    }

    private static void regulation(Path file, int rtt, boolean first, Rule rule) throws Exception {
        var env = BoxingDoubleSearch.environment(file, rtt, 20, 180_000);
        var base = regulation(env, first, new DuelEnv.Player("wtap", 0, rtt, 0));
        var candidate = new Release(new DuelEnv.Player("wtap", 0, rtt, 0), rule);
        var score = regulation(env, first, candidate);
        System.out.println(Json.encode(Map.of("clock", env.clockId, "rtt", rtt, "side", first ? "a" : "b",
                "rule", rule.label(), "wtap", base, "candidate", score, "releases", candidate.actions.size())));
    }

    private static List<Integer> regulation(DuelEnv env, boolean first, DuelEnv.Player own) {
        var game = env.new Game(first, own, new DuelEnv.Player("wtap", 0, env.rtt, 0));
        game.trace();
        int at = 0;
        int[] score = new int[2];
        while (game.advance()) {
            for (; at < game.hits.list.size(); at++) {
                var hit = game.hits.list.get(at);
                if (++score[hit.sideA() == first ? 0 : 1] == 100) return List.of(score[0], score[1]);
            }
        }
        return List.of(score[0], score[1]);
    }

    public static void main(String[] args) throws Exception {
        var rules = new ArrayList<Rule>();
        for (String label : args[1].split(",")) {
            int lead = Integer.parseInt(label.substring(0, label.indexOf('-')));
            rules.add(new Rule(lead, label.endsWith("ground-range")));
        }
        for (int i = 2; i < args.length; i++) for (int rtt : RTT) for (boolean first : new boolean[]{true, false}) {
            Path file = Path.of(args[i]);
            switch (args[0]) {
                case "regulation" -> regulation(file, rtt, first, rules.get(0));
                case "maintenance" -> maintenance(file, rtt, first, rules.get(0));
                case "forks" -> forks(file, rtt, first, rules);
                default -> throw new IllegalArgumentException("Unknown study: " + args[0]);
            }
        }
    }
}
