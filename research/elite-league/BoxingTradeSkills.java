import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lab.Json;

/** Isolate opening click admission and knockback-triggered jump in the same W-tap plant. */
final class BoxingTradeSkills {
    private static final int DURATION = 12_000;
    private static final int[] OPENING_TIMES = {450, 500, 550, 600, 650, 700, 750, 800, 900};
    private static final int[] CUE_DELAYS = {0, 25, 50, 75, 100, 150};
    private static final int[][] PHASES = {{0, 0}, {0, 17}, {23, 0}, {34, 11}};

    record Footwork(int key, int ticks) {}

    record Setting(String name, int opensAt, int afterCue, boolean jump, double coastRange,
                   int coastTicks, boolean resumeOnHurt, Footwork footwork) {
        Setting(String name, int opensAt, int afterCue, boolean jump, double coastRange,
                int coastTicks, boolean resumeOnHurt) {
            this(name, opensAt, afterCue, jump, coastRange, coastTicks, resumeOnHurt, null);
        }
        Setting(String name, int opensAt, int afterCue, boolean jump) {
            this(name, opensAt, afterCue, jump, 0, 0, false);
        }
    }

    private static final Setting WTAP = new Setting("wtap", 0, -1, false);
    private static final Setting JUMP = new Setting("jump-on-impact", 0, -1, true);

    static class Fighter extends DuelEnv.Player {
        final Setting setting;
        double hurtAt = Double.POSITIVE_INFINITY;
        int jumpRequests;
        int coastRemaining;
        boolean coastStarted;
        int retreatRemaining, retreatSteps;

        Fighter(Setting setting, int ping) {
            super(setting.jump ? "reset" : "wtap", 0, ping, 0);
            this.setting = setting;
        }

        Fighter(Fighter source) {
            this(source.setting, source.rtt);
            hurtAt = source.hurtAt;
            jumpRequests = source.jumpRequests;
            impact = source.impact;
            firstClick = source.firstClick;
            handled = source.handled;
            releaseUntil = source.releaseUntil;
            coastStarted = source.coastStarted;
            coastRemaining = source.coastRemaining;
            retreatRemaining = source.retreatRemaining;
            retreatSteps = source.retreatSteps;
        }

        @Override Fighter copy() { return new Fighter(this); }

        @Override public PeriodicComboClock.Control input(PeriodicComboClock.Observation o) {
            var base = super.input(o);
            if (base.jump()) jumpRequests++;
            if (Double.isFinite(o.lastOwnHurtTime()) && !Double.isFinite(hurtAt)) {
                hurtAt = o.lastOwnHurtTime();
                if (setting.footwork != null) retreatRemaining = setting.footwork.ticks;
            }
            boolean click = o.time() >= setting.opensAt &&
                    (setting.afterCue < 0 || o.time() >= hurtAt + setting.afterCue || o.time() >= 1_100);
            if (setting.coastTicks > 0 && !coastStarted && !Double.isFinite(hurtAt)
                    && o.own().onGround() && o.nearest() <= setting.coastRange) {
                coastRemaining = setting.coastTicks;
                coastStarted = true;
            }
            int key = base.key();
            if (setting.resumeOnHurt && Double.isFinite(hurtAt)) coastRemaining = 0;
            if (coastRemaining > 0) {
                key = 0;
                coastRemaining--;
            }
            if (retreatRemaining > 0 && o.time() < hurtAt + setting.afterCue - 50) {
                key = setting.footwork.key;
                retreatRemaining--;
                retreatSteps++;
            }
            return new PeriodicComboClock.Control(key, base.strafe(), click, base.jump());
        }
    }

    static final class Landings {
        // Client order is intentional. The server's on-ground flag can lag local movement.
        final double[] accepted = {Double.NaN, Double.NaN};
        final double[] launched = {Double.NaN, Double.NaN};
        final double[] landed = {Double.NaN, Double.NaN};
        final double[] appliedHorizontal = {Double.NaN, Double.NaN};
        final double[] appliedVertical = {Double.NaN, Double.NaN};
        final double[] velocityAtLanding = {Double.NaN, Double.NaN};
        final int[] beforeFirst = {0, 0};
        final boolean[] contaminated = {false, false};

        Landings(PeriodicComboFightModel.State state) {
            beforeFirst[0] = state.incomingHits;
            beforeFirst[1] = state.hits;
        }

        void event(double time, String kind, PeriodicComboFightModel.State state) {
            for (int side = 0; side < 2; side++) {
                int received = side == 0 ? state.incomingHits : state.hits;
                if (received <= beforeFirst[side]) continue;
                if (!Double.isFinite(accepted[side])) {
                    var impulse = side == 0 ? state.lastFreshA : state.lastFreshB;
                    accepted[side] = impulse.time();
                    appliedHorizontal[side] = Math.hypot(impulse.x(), impulse.z());
                    appliedVertical[side] = impulse.y();
                }
                if (Double.isFinite(landed[side])) continue;
                if (received > beforeFirst[side] + 1) contaminated[side] = true;
                if (!kind.equals(side == 0 ? "MOVEA" : "MOVEB")) continue;
                var own = side == 0 ? state.a.q : state.b.q;
                var other = side == 0 ? state.b.q : state.a.q;
                if (!Double.isFinite(launched[side]) && !own.onGround() && own.y() > 4.00001) {
                    launched[side] = time;
                } else if (Double.isFinite(launched[side]) && own.onGround() && !contaminated[side]) {
                    landed[side] = time;
                    double dx = other.x() - own.x(), dz = other.z() - own.z();
                    velocityAtLanding[side] = (own.vx() * dx + own.vz() * dz) / Math.max(.00001, Math.hypot(dx, dz));
                }
            }
        }

        Map<String,Object> values(boolean first) {
            int a = first ? 0 : 1, b = 1-a;
            var result = new LinkedHashMap<String,Object>();
            result.put("accepted", optional(accepted[a], accepted[b]));
            result.put("airborne", optional(launched[a], launched[b]));
            result.put("ground", optional(landed[a], landed[b]));
            result.put("launchHorizontal", optional(appliedHorizontal[a], appliedHorizontal[b]));
            result.put("launchVertical", optional(appliedVertical[a], appliedVertical[b]));
            result.put("groundForwardSpeed", optional(velocityAtLanding[a], velocityAtLanding[b]));
            return result;
        }
    }

    private static List<Double> optional(double a, double b) {
        return java.util.Arrays.asList(Double.isFinite(a) ? a : null, Double.isFinite(b) ? b : null);
    }

    static DuelEnv env(DuelEnv.Tape tape, int ping, int cps, double gap) {
        var env = new DuelEnv(tape, "mmc", ping, ping, false, 29, true, 10, gap, 0, DURATION);
        env.initial.timing.clickIntervalNanos = (1_000_000_000L + cps - 1) / cps;
        env.initial.timing.mouseInput = true;
        return env;
    }

    static Map<String,Object> play(DuelEnv env, boolean first, Setting setting) {
        return play(env, first, new Fighter(setting, env.rtt));
    }

    static Map<String,Object> play(DuelEnv env, boolean first, Fighter ours) {
        var opponent = new DuelEnv.Player("wtap", 0, env.rtt, 0);
        var game = env.new Game(first, ours, opponent);
        game.trace();
        var landings = new Landings(game.clock.state);
        game.clock.setAudit((t, event, state) -> {
            game.hits.event(t, event, state);
            if (t <= 2_700) landings.event(t, event, state);
        });
        game.finish();
        var hits = game.hits.list;
        int own = first ? game.hits.acceptedA : game.hits.acceptedB;
        int other = first ? game.hits.acceptedB : game.hits.acceptedA;
        int freshOwn = first ? game.hits.freshA : game.hits.freshB;
        int freshOther = first ? game.hits.freshB : game.hits.freshA;
        int firstOur = -1, firstTheir = -1;
        for (int i = 0; i < hits.size(); i++) {
            var h = hits.get(i);
            if (h.upgrade()) continue;
            if (firstOur < 0 && h.sideA() == first) firstOur = i;
            if (firstTheir < 0 && h.sideA() != first) firstTheir = i;
            if (firstOur >= 0 && firstTheir >= 0) break;
        }
        int beforeResponse = 0;
        int ourStreak = 0;
        int theirStreak = 0;
        double last = Double.NEGATIVE_INFINITY;
        int ownDouble = 0, theirDouble = 0;
        int side = 0;
        for (int i = 0; i < hits.size();) {
            var h = hits.get(i);
            int sides = 0, fresh = 0, j = i;
            for (; j < hits.size() && hits.get(j).at() == h.at(); j++) {
                var e = hits.get(j);
                sides |= e.sideA() == first ? 1 : 2;
                if (!e.upgrade()) fresh++;
            }
            if (sides == 3 || sides != side || h.at() - last > 1000) {
                side = sides == 3 ? 0 : sides;
                ourStreak = 0;
                theirStreak = 0;
            }
            if (side == 1 && fresh > 0) {
                if (ourStreak == 1) ownDouble++;
                ourStreak += fresh;
                last = h.at();
            }
            if (side == 2 && fresh > 0) {
                if (theirStreak == 1) theirDouble++;
                theirStreak += fresh;
                last = h.at();
            }
            i = j;
        }
        double atOwn = firstOur < 0 ? Double.NaN : hits.get(firstOur).at();
        double atOther = firstTheir < 0 ? Double.NaN : hits.get(firstTheir).at();
        double nextAfterPair = Double.NaN;
        boolean nextAfterPairOurs = false;
        if (firstOur >= 0 && firstTheir >= 0 && atOwn > atOther) {
            double lastOwn = atOwn;
            for (int i = firstOur + 1; i < hits.size();) {
                double now = hits.get(i).at();
                int j = i, fresh = 0;
                boolean opposing = false;
                for (; j < hits.size() && hits.get(j).at() == now; j++) {
                    var h = hits.get(j);
                    opposing |= h.sideA() != first;
                    if (!h.upgrade()) fresh++;
                }
                if (Double.isNaN(nextAfterPair) && fresh > 0) {
                    nextAfterPair = now;
                    nextAfterPairOurs = !opposing;
                }
                if (now - lastOwn > 1000 || opposing || now == atOwn) break;
                beforeResponse += fresh;
                if (fresh > 0) lastOwn = now;
                i = j;
            }
        }
        return Map.ofEntries(
                Map.entry("score", List.of(own, other)),
                Map.entry("fresh", List.of(freshOwn, freshOther)),
                Map.entry("doubles", List.of(ownDouble, theirDouble)),
                Map.entry("firstFresh", optional(atOwn, atOther)),
                Map.entry("firstClick", optional(ours.firstClick, opponent.firstClick)),
                Map.entry("nextFresh", optional(nextAfterPair, nextAfterPairOurs ? 1 : 0)),
                Map.entry("followUpsAfterCounter", beforeResponse),
                Map.entry("retreatSteps", ours.retreatSteps),
                Map.entry("jumpRequests", ours.jumpRequests),
                Map.entry("flight", landings.values(first)));
    }

    private static List<Setting> settings(double gap) {
        var result = new ArrayList<Setting>();
        result.add(JUMP);
        // Absolute delays are scenario controls. Cue-gated entry can transfer across starting gaps.
        if (gap == 6) for (int t : OPENING_TIMES) result.add(new Setting("start-" + t, t, -1, false));
        for (int t : CUE_DELAYS) result.add(new Setting("hurt+" + t, 0, t, false));
        return result;
    }

    private static List<Setting> coastSettings() {
        var options = new ArrayList<Setting>();
        for (int delay : new int[]{0, 50, 100, 150}) {
            options.add(new Setting("hurt+" + delay, 0, delay, false));
            for (double range : new double[]{3.1, 3.4, 3.7, 4.0, 4.3}) {
                for (int ticks : new int[]{1, 2, 3, 4}) {
                    options.add(new Setting("hurt+" + delay + "/coast-" + range + "-" + ticks,
                            0, delay, false, range, ticks, false));
                }
            }
        }
        return options;
    }

    private static List<Setting> baitSettings() {
        var options = new ArrayList<Setting>();
        for (int delay : CUE_DELAYS) {
            options.add(new Setting("hurt+" + delay, 0, delay, false));
            for (double range : new double[]{3.7, 4.3, 4.9, 5.7}) {
                for (int ticks : new int[]{6, 10, 14}) {
                    options.add(new Setting("hurt+" + delay + "/bait-" + range + "-" + ticks,
                            0, delay, false, range, ticks, true));
                }
            }
        }
        return options;
    }

    private static void witness(String[] args) throws Exception {
        int cps = Integer.parseInt(args[1]), rtt = Integer.parseInt(args[2]);
        double gap = Double.parseDouble(args[3]);
        boolean first = args[4].equals("a");
        int phaseA = Integer.parseInt(args[5]), phaseB = Integer.parseInt(args[6]);
        var tape = DuelEnv.Tape.read(Path.of(args[8]), DURATION);
        var env = env(BoxingDrill.shift(tape, phaseA, phaseB), rtt, cps, gap);
        var cases = new ArrayList<>(settings(gap));
        cases.addAll(coastSettings());
        cases.addAll(baitSettings());
        cases.add(WTAP);
        var setting = cases.stream().filter(candidate -> candidate.name.equals(args[7])).findFirst().orElseThrow();
        if (args.length > 9) {
            int retreatSteps = Integer.parseInt(args[9]);
            setting = new Setting(setting.name + "/back-" + retreatSteps, 0, setting.afterCue, false, 0, 0, false,
                    new Footwork(PeriodicComboFightModel.BACK, retreatSteps));
        }
        var game = env.new Game(first, new Fighter(setting, rtt), new DuelEnv.Player("wtap", 0, rtt, 0));
        game.trace();
        var landings = new Landings(game.clock.state);
        var log = new ArrayList<Object>();
        game.clock.setAudit((time, stage, state) -> {
            int before = game.hits.list.size();
            game.hits.event(time, stage, state);
            landings.event(time, stage, state);
            if (game.hits.list.size() > before) {
                for (var hit : game.hits.list.subList(before, game.hits.list.size())) {
                    log.add(List.of(hit.at(), "accept", hit.sideA() == first ? "own" : "opponent", hit.upgrade()));
                }
            }
            if (stage.equals("SERVER_ENTITY")) {
                log.add(List.of(time, "eligibility", state.ownResistant, state.resistant));
            }
        });
        game.clock.setClickAudit(event -> {
            if (!event.action().equals("CONSUME")) return;
            var state = game.clock.state;
            boolean a = event.fromA();
            var body = a ? state.a : state.b;
            var target = a ? state.ab : state.ba;
            var other = a ? state.b : state.a;
            var velocity = body.q;
            double dx = target.x - velocity.x(), dz = target.z - velocity.z();
            double toward = (velocity.vx() * dx + velocity.vz() * dz) / Math.max(.00001, Math.hypot(dx, dz));
            double ready = Double.NaN;
            if (event.contact()) {
                for (var packet : state.packets) {
                    if (packet.kind == (a ? 3 : 7)) ready = packet.ready;
                }
            }
            log.add(List.of(event.time(), "click", a == first ? "own" : "opponent",
                    event.eligible(), event.contact(), PeriodicComboFightModel.nearest(velocity, target),
                    velocity.y(), other.q.y(), velocity.onGround(), body.sprint, toward,
                    Double.isFinite(ready) ? ready : -1, PeriodicComboFightModel.ray(body, target)));
        });
        game.clock.runUntil(2_100);
        System.out.println(Json.encode(Map.of("arm", setting.name, "rtt", rtt, "phase", List.of(phaseA, phaseB),
                "side", args[4], "flight", landings.values(first), "timeline", log)));
    }

    private static void phaseGrid(String[] paths) throws Exception {
        var delay = new Setting("hurt+150", 0, 150, false);
        for (int index = 1; index < paths.length; index++) {
            var tape = DuelEnv.Tape.read(Path.of(paths[index]), DURATION);
            for (int a = 0; a < 50; a += 5) for (int b = 0; b < 50; b += 5) {
                var env = env(BoxingDrill.shift(tape, a, b), 0, 10, 6);
                for (boolean first : new boolean[]{true, false}) {
                    System.out.println(Json.encode(Map.of(
                            "donor", tape.id(), "phase", List.of(a, b), "side", first ? "a" : "b",
                            "wtap", play(env, first, WTAP), "candidate", play(env, first, delay))));
                }
            }
        }
    }

    public static void main(String[] args) throws Exception {
        if (args[0].equals("witness")) {
            witness(args);
            return;
        }
        if (args[0].equals("phase-grid")) {
            phaseGrid(args);
            return;
        }
        boolean coast = args[0].equals("coast"), rttSweep = args[0].equals("rtt"), bait = args[0].equals("bait");
        boolean frozenCue = args[0].equals("cue150");
        boolean fixedGap = coast || rttSweep || bait || frozenCue;
        int cps = Integer.parseInt(args[fixedGap ? 1 : 0]);
        for (int i = fixedGap ? 2 : 1; i < args.length; i++) {
            var tape = DuelEnv.Tape.read(Path.of(args[i]), DURATION);
            for (int[] phase : PHASES) {
                var shifted = BoxingDrill.shift(tape, phase[0], phase[1]);
                int[] latencies = frozenCue ? new int[]{0, 20, 80} : rttSweep ? new int[]{0, 20, 40, 60, 80} : new int[]{0, 80};
                for (int ping : latencies) {
                    for (double gap : fixedGap ? new double[]{6} : new double[]{4, 5, 6, 7}) {
                        var env = env(shifted, ping, cps, gap);
                        for (boolean first : new boolean[]{true, false}) {
                            var control = play(env, first, WTAP);
                            var candidates = frozenCue ? List.of(new Setting("hurt+150", 0, 150, false))
                                    : coast ? coastSettings() : bait ? baitSettings() : settings(gap);
                            for (var candidate : candidates) {
                                System.out.println(Json.encode(Map.of("donor", tape.id(), "phase", phase,
                                        "seat", first ? "a" : "b", "rtt", ping, "gap", gap, "cps", cps,
                                        "arm", candidate.name, "wtap", control, "candidate", play(env, first, candidate))));
                            }
                        }
                    }
                }
            }
        }
    }
}
