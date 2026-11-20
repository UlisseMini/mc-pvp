import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import lab.Json;

/** Trace an established combo's first return, then search bounded input words from its exact second hit. */
final class BoxingBeforeThirdHit {
    private static final int HEADS = 4;
    private static final int WORDS = 625;
    private static final int[] PHASES = {0, -10, 10};
    private static final double HORIZON = 2_200;

    private record Velocity(long packet, double vx, double vy, double vz) {}

    private record Swing(long packet, double time, double ready, double ray, double nearest,
                         double playerY, double replicaY, double serverGap) {
        Map<String,Object> values(double landmark) {
            return Map.of("packet", packet, "consumedFromK3", time - landmark, "readyFromK3", ready - landmark,
                    "ray", ray, "nearest", nearest, "playerY", playerY, "replicaY", replicaY, "serverGap", serverGap);
        }
    }

    private static final class Trace {
        final Map<Long,Swing> swings = new HashMap<>();
        final List<Swing> accepted = new ArrayList<>();
        final List<Long> recoil = new ArrayList<>();
        final List<Double> ownHeads = new ArrayList<>();
        final Map<Double,List<Object>> headViews = new LinkedHashMap<>();
        long nextSequence;

        Trace(DuelEnv.Game game) {
            String ownHead = game.first ? "POLICYA" : "POLICYB";
            nextSequence = game.clock.state.seq;
            game.clock.setClickAudit(event -> {
                if (!event.action().equals("CONSUME") || !event.contact()) return;
                var state = game.clock.state;
                var body = event.fromA() ? state.a : state.b;
                var target = event.fromA() ? state.ab : state.ba;
                int kind = event.fromA() ? 3 : 7;
                var packet = state.packets.stream().filter(p -> p.kind == kind)
                        .max(java.util.Comparator.comparingLong(p -> p.sequence)).orElseThrow();
                if (swings.containsKey(packet.sequence)) throw new AssertionError("Reused attack packet");
                swings.put(packet.sequence, new Swing(packet.sequence, event.time(), packet.ready,
                        PeriodicComboFightModel.ray(body, target), PeriodicComboFightModel.nearest(body.q, target),
                        body.q.y(), target.y, gap(state)));
            });
            game.clock.setAudit((time, stage, state) -> {
                if (stage.equals(ownHead)) {
                    ownHeads.add(time);
                    var body = game.first ? state.a : state.b;
                    var view = game.first ? state.ab : state.ba;
                    headViews.put(time, List.of(view.lastHurtTime, body.lastHurtTime, body.sprint, body.q.onGround(),
                            PeriodicComboFightModel.nearest(body.q, view), body.q.y(), view.y,
                            game.clock.appliedControl(game.first).key()));
                }
                int before = game.hits.list.size();
                game.hits.event(time, stage, state);
                for (int i = before; i < game.hits.list.size(); i++) {
                    var hit = game.hits.list.get(i);
                    long packet = hit.upgrade()
                            ? (hit.sideA() ? state.lastAcceptedPacketA : state.lastAcceptedPacketB)
                            : (hit.sideA() ? state.lastFreshPacketA : state.lastFreshPacketB);
                    accepted.add(swings.get(packet));
                    int velocityKind = hit.sideA() ? 4 : 8;
                    var emitted = state.packets.stream().filter(p -> p.kind == velocityKind && p.sequence >= nextSequence).toList();
                    recoil.add(!hit.upgrade() && emitted.size() == 1 ? emitted.get(0).sequence : -1L);
                }
                nextSequence = state.seq;
            });
        }
    }

    private static final class Word extends DuelEnv.Player {
        int code, wait, steps = HEADS;

        Word(DuelEnv.Player warmed, int wait, int code) {
            super("wtap", 0, warmed.rtt, 0);
            this.code = code;
            this.wait = wait;
            handled = warmed.handled;
            releaseUntil = warmed.releaseUntil;
            firstClick = warmed.firstClick;
        }

        @Override public PeriodicComboClock.Control input(PeriodicComboClock.Observation obs) {
            var normal = super.input(obs);
            if (wait > 0) {
                wait--;
                return normal;
            }
            if (steps == 0) return normal;
            steps--;
            int key = switch (code % 5) {
                case 1 -> PeriodicComboFightModel.R;
                case 2 -> PeriodicComboFightModel.BACK;
                case 3 -> PeriodicComboFightModel.W;
                case 4 -> PeriodicComboFightModel.S;
                default -> normal.key();
            };
            code /= 5;
            return new PeriodicComboClock.Control(key, normal.strafe(), normal.click(), normal.jump());
        }
    }

    private record Outcome(int freshBeforeReturn, int activeFresh, String end, double exposure) {
        boolean succeeds() { return activeFresh >= 2; }
        List<Object> values() { return List.of(freshBeforeReturn, activeFresh, end, exposure); }
    }

    private static double gap(PeriodicComboFightModel.State state) {
        return Math.hypot(state.serverAx - state.serverBx, state.serverAz - state.serverBz);
    }

    private static DuelEnv.Game start(DuelEnv env, boolean first) {
        var game = env.new Game(first, new DuelEnv.Player("wtap", 0, env.rtt, 0),
                new DuelEnv.Player("wtap", 0, env.rtt, 2_000));
        game.trace();
        return game;
    }

    private static Outcome outcome(List<DuelEnv.Hit> hits, boolean first, double start) {
        double last = start;
        int cycle = 0, active = 0;
        for (var hit : hits) {
            if (hit.sideA() != first) return new Outcome(cycle, active, "return", hit.at() - start);
            if (!hit.upgrade()) {
                if (active == cycle && hit.at() - last <= 1000) active++;
                cycle++;
                last = hit.at();
            }
        }
        return new Outcome(cycle, active, "censored", HORIZON);
    }

    private static List<DuelEnv.Hit> replay(DuelEnv env, DuelEnv.Game prefix, double from, int phase, int delay, int word) {
        return replay(env, prefix, from, phase, delay, word, null);
    }

    private static List<DuelEnv.Hit> replay(DuelEnv env, DuelEnv.Game prefix, double from, int phase, int delay,
                                           int word, Velocity secondImpulse) {
        var fork = branch(env, prefix, phase, delay, word);
        if (secondImpulse != null) {
            var packet = secondVelocity(fork, secondImpulse.packet);
            packet.vx = secondImpulse.vx;
            packet.vy = secondImpulse.vy;
            packet.vz = secondImpulse.vz;
        }
        fork.clock.runUntil(from + HORIZON);
        return fork.hits.list;
    }

    private static DuelEnv.Game branch(DuelEnv env, DuelEnv.Game prefix, int phase, int delay, int word) {
        var p = new Word(prefix.our(), delay, word);
        var fork = env.new Game(prefix, prefix.first ? p : prefix.a.copy(), prefix.first ? prefix.b.copy() : p);
        if (phase != 0) fork.clock.shiftFutureClientOpportunities(!prefix.first, phase);
        fork.trace();
        return fork;
    }

    private static PeriodicComboFightModel.Packet secondVelocity(DuelEnv.Game game, long sequence) {
        int kind = game.first ? 4 : 8;
        return game.clock.state.packets.stream().filter(p -> p.kind == kind && p.sequence == sequence).findFirst()
                .orElseThrow(() -> new IllegalStateException("Second-hit velocity packet is absent from fork: " + sequence));
    }

    private static Map<String,Object> impulseProbe(DuelEnv env, DuelEnv.Game prefix, double from, long sequence) {
        if (sequence < 0) return Map.of("status", "unlinked second-hit velocity");
        var packet = secondVelocity(prefix, sequence);
        double norm = Math.hypot(packet.vx, packet.vz);
        if (!(norm > 0)) throw new IllegalStateException("Missing second-hit horizontal impulse");
        var results = new LinkedHashMap<String,Object>();
        results.put("packet", sequence);
        results.put("recordedImpulse", List.of(packet.vx, packet.vy, packet.vz));
        for (double horizontal : new double[]{norm, .527375, .854375}) for (double vertical : new double[]{packet.vy, 0, .361375}) {
            String name = horizontal + "/" + vertical;
            if (results.containsKey(name)) continue;
            var forced = new Velocity(sequence, packet.vx * (horizontal / norm), vertical, packet.vz * (horizontal / norm));
            var comparison = Arrays.stream(PHASES).mapToObj(phase -> outcome(
                    replay(env, prefix, from, phase, 0, 0, forced), prefix.first, from).values()).toList();
            results.put(name, comparison);
        }
        return results;
    }

    private static Map<String,Object> probe(DuelEnv env, DuelEnv.Game prefix, double from, List<DuelEnv.Hit> expected,
                                            Trace trace, Swing returned) {
        boolean first = prefix.first;
        var unchanged = replay(env, prefix, from, 0, 0, 0);
        if (!unchanged.equals(expected)) throw new AssertionError("W-tap prefix or queues diverged");
        var controls = Arrays.stream(PHASES).mapToObj(phase -> outcome(
                replay(env, prefix, from, phase, 0, 0), first, from)).toList();
        var delays = new LinkedHashSet<Integer>(List.of(0, 4));
        if (returned != null) {
            // These are privileged cuts based on the unchanged defender's
            // actual return. Allow time for both wire legs and replica motion.
            for (int lead : new int[]{240, 360}) {
                double desired = returned.time - lead;
                delays.add((int)trace.ownHeads.stream().filter(t -> t > from && t < desired).count());
            }
        }
        int matches = 0, robust = 0, winDelay = -1, winWord = -1;
        var winner = List.<Outcome>of();
        for (int delay : delays) for (int word = 0; word < WORDS; word++) {
            var natural = outcome(replay(env, prefix, from, 0, delay, word), first, from);
            if (!natural.succeeds()) continue;
            matches++;
            var early = outcome(replay(env, prefix, from, PHASES[1], delay, word), first, from);
            var late = outcome(replay(env, prefix, from, PHASES[2], delay, word), first, from);
            if (early.succeeds() && late.succeeds()) {
                robust++;
                if (winWord < 0) {
                    winWord = word;
                    winDelay = delay;
                    winner = List.of(natural, early, late);
                }
            }
        }
        return Map.of("wtap", controls.stream().map(Outcome::values).toList(), "schedules", PHASES, "delays", delays,
                "actualSolutions", matches, "threePhaseSolutions", robust, "solutionDelay", winDelay,
                "solutionWord", winWord, "solution", winner.stream().map(Outcome::values).toList());
    }

    private record SecondHit(Outcome outcome, double horizontal, double vertical, List<Double> freshTimes) {
        boolean succeeds() { return outcome.activeFresh >= 3 && horizontal < .6 && vertical == 0; }
        List<Object> values() { return List.of(outcome.values(), horizontal, vertical, freshTimes); }
    }

    private static SecondHit strengthTrial(DuelEnv env, DuelEnv.Game prefix, double from, int phase, int delay, int word) {
        var fork = branch(env, prefix, phase, delay, word);
        var impulse = new double[]{Double.NaN, Double.NaN};
        fork.clock.setAudit((time, stage, state) -> {
            int before = fork.hits.list.size();
            fork.hits.event(time, stage, state);
            if (Double.isFinite(impulse[0])) return;
            for (int i = before; i < fork.hits.list.size(); i++) {
                var hit = fork.hits.list.get(i);
                if (hit.sideA() == fork.first && !hit.upgrade()) {
                    var launch = fork.first ? state.lastFreshB : state.lastFreshA;
                    impulse[0] = Math.hypot(launch.x(), launch.z());
                    impulse[1] = launch.y();
                    break;
                }
            }
        });
        fork.clock.runUntil(from + HORIZON);
        var hits = fork.hits.list;
        var times = hits.stream().takeWhile(h -> h.sideA() == fork.first).filter(h -> !h.upgrade())
                .map(h -> h.at() - from).toList();
        return new SecondHit(outcome(hits, fork.first, from), impulse[0], impulse[1], times);
    }

    private static Map<String,Object> strengthSearch(DuelEnv env, boolean first, double from, Trace trace, Swing second,
                                                     List<DuelEnv.Hit> hits) {
        var prefix = start(env, first);
        prefix.clock.runUntil(from);
        var expected = hits.stream().filter(h -> h.at() > from && h.at() <= from + HORIZON).toList();
        if (!replay(env, prefix, from, 0, 0, 0).equals(expected)) throw new AssertionError("Pre-K2 W-tap replay diverged");
        var delays = new LinkedHashSet<Integer>();
        for (int lead : new int[]{50, 100, 150, 200}) {
            double desired = second.time - lead;
            delays.add((int)trace.ownHeads.stream().filter(t -> t > from && t < desired).count());
        }
        var controls = Arrays.stream(PHASES)
                .mapToObj(phase -> strengthTrial(env, prefix, from, phase, 0, 0).values()).toList();
        int count = 0, stable = 0, walkAt = -1;
        var solutions = new ArrayList<Object>();
        for (int delay : delays) for (int word = 0; word < WORDS; word++) {
            var actual = strengthTrial(env, prefix, from, 0, delay, word);
            if (!actual.succeeds()) continue;
            count++;
            var early = strengthTrial(env, prefix, from, PHASES[1], delay, word);
            var late = strengthTrial(env, prefix, from, PHASES[2], delay, word);
            if (early.succeeds() && late.succeeds()) {
                stable++;
                solutions.add(Map.of("delay", delay, "word", word, "result", List.of(actual.values(), early.values(), late.values())));
                if (walkAt < 0 && word == 3) walkAt = delay;
            }
        }
        var width = new ArrayList<Object>();
        if (walkAt >= 0) for (int phase = -25; phase <= 25; phase += 5) {
            var wtap = strengthTrial(env, prefix, from, phase, 0, 0);
            var walk = strengthTrial(env, prefix, from, phase, walkAt, 3);
            width.add(Map.of("phase", phase, "wtap", wtap.values(), "walk", walk.values(), "success", walk.succeeds()));
        }
        var views = trace.ownHeads.stream().filter(t -> t > from && t <= second.time).map(t ->
                List.of(t - from, trace.headViews.get(t))).toList();
        return Map.of("wtap", controls, "delays", delays, "solutions", count, "stable", stable, "witnesses", solutions,
                "walkDelay", walkAt, "walkPhaseSweep", width, "anchorMs", from, "localHeads", views);
    }

    private static void run(Path file, int ping, boolean first, boolean beforeSecond) throws Exception {
        var env = BoxingDoubleSearch.environment(file, ping, 20, 20_000);
        run(env, first, beforeSecond, false);
    }

    private static void run(DuelEnv env, boolean first, boolean beforeSecond, boolean traceOnly) {
        var game = start(env, first);
        var trace = new Trace(game);
        game.finish();
        double awake = Math.max(game.a.firstClick, game.b.firstClick);
        var hits = game.hits.list;
        int chain = 0, initial = -1, second = -1, third = -1;
        for (int i = 0; i < hits.size(); i++) {
            var hit = hits.get(i);
            if (hit.at() < awake) continue;
            if (hit.sideA() != first) chain = 0;
            else if (!hit.upgrade()) {
                if (++chain == 1) initial = i;
                if (chain == 2) second = i;
                if (chain == 3) {
                    third = i;
                    break;
                }
            }
        }
        if (third < 0) return;
        double time = hits.get(third).at(), from = hits.get(second).at();
        var row = new LinkedHashMap<String,Object>();
        row.put("clock", env.clockId);
        row.put("rtt", env.rtt);
        row.put("seat", first ? "a" : "b");
        row.put("k2", from);
        row.put("k3", time);
        var k3 = trace.accepted.get(third);
        row.put("thirdSwing", k3 == null ? null : k3.values(time));
        for (int j = third + 1; j < hits.size(); j++) {
            var hit = hits.get(j);
            if (hit.sideA() == first) continue;
            var linked = trace.accepted.get(j);
            row.put("returnFromK3", hit.at() - time);
            row.put("returnUpgrade", hit.upgrade());
            row.put("returnSwing", linked == null ? null : linked.values(time));
            if (traceOnly) {
                row.put("firstOwnHeadAfterK3", trace.ownHeads.stream().filter(t -> t > time)
                        .findFirst().map(t -> t - time).orElse(null));
                row.put("ownFreshBeforeReturn", hits.subList(third + 1, j).stream().filter(h -> !h.upgrade()).count());
            }
            if (!traceOnly && env.rtt == 80 && hit.at() - time <= 55) {
                var prefix = start(env, first);
                prefix.clock.runUntil(from);
                row.put("k2ServerGap", gap(prefix.clock.state));
                row.put("k2ImpulseProbe", impulseProbe(env, prefix, from, trace.recoil.get(second)));
                if (beforeSecond && trace.accepted.get(second) != null) {
                    row.put("strengthSearch", strengthSearch(env, first, hits.get(initial).at(), trace, trace.accepted.get(second), hits));
                } else {
                    row.put("exercise", probe(env, prefix, from, hits.stream()
                            .filter(h -> h.at() > from && h.at() <= from + HORIZON).toList(), trace, linked));
                }
            }
            break;
        }
        System.out.println(Json.encode(row));
    }

    private static Map<String,Object> envelopeScore(List<DuelEnv.Hit> committed, List<DuelEnv.Hit> played,
                                                    boolean first, double from) {
        var hits = new ArrayList<>(committed);
        hits.addAll(played);
        var result = outcome(hits, first, from);
        double last = from;
        int active = 0;
        for (var h : hits) {
            if (h.at() - last > 1000 || h.sideA() != first) break;
            if (!h.upgrade()) {
                active++;
                last = h.at();
            }
        }
        if (active != result.activeFresh) throw new AssertionError("Activity counts differ");
        double activityMs = Math.min(result.exposure, last + 1000 - from);
        int margin = hits.stream().mapToInt(h -> h.sideA() == first ? 1 : -1).sum();
        var ownTimes = hits.stream().takeWhile(h -> h.sideA() == first).filter(h -> !h.upgrade())
                .map(h -> h.at() - from).toList();
        return Map.of("activeCycles", active, "returnOnlyCycles", result.freshBeforeReturn, "margin", margin,
                "activityMs", activityMs, "returnOnlyMs", result.exposure, "returnOnlyEnd", result.end,
                "activityEnd", activityMs < result.exposure ? "inactive" : result.end, "unansweredFreshMs", ownTimes);
    }

    private static Map<String,Object> ordinaryWalks(DuelEnv env, boolean first, double k1, Swing k2, Trace trace) {
        var prefix = start(env, first);
        prefix.clock.runUntil(k1);
        var delays = new LinkedHashSet<Integer>();
        for (int lead = 0; lead <= 250; lead += 25) {
            double desired = k2.time - lead;
            delays.add((int)trace.ownHeads.stream().filter(t -> t > k1 && t < desired).count());
        }
        var baselines = Arrays.stream(PHASES).mapToObj(p -> strengthTrial(env, prefix, k1, p, 0, 0)).toList();
        int sameTiming = 0, stable = 0;
        var examples = new ArrayList<Object>();
        for (int delay : delays) {
            int word = 0;
            for (int ticks = 1; ticks <= HEADS; ticks++) {
                word = word * 5 + 3;
                var baseline = baselines.get(0);
                var natural = strengthTrial(env, prefix, k1, 0, delay, word);
                if (!improves(natural, baseline)) continue;
                sameTiming++;
                var early = strengthTrial(env, prefix, k1, PHASES[1], delay, word);
                var late = strengthTrial(env, prefix, k1, PHASES[2], delay, word);
                if (!improves(early, baselines.get(1)) || !improves(late, baselines.get(2))) continue;
                stable++;
                examples.add(Map.of("waitHeads", delay, "wOnlyHeads", ticks,
                        "outcomes", List.of(natural.values(), early.values(), late.values())));
            }
        }
        return Map.of("k1", k1, "schedules", PHASES, "wtap", baselines.stream().map(SecondHit::values).toList(),
                "tried", delays.size() * HEADS, "nominalSolutions", sameTiming, "threePhaseSolutions", stable, "examples", examples);
    }

    private static boolean improves(SecondHit walk, SecondHit baseline) {
        return walk.succeeds() && walk.outcome.activeFresh > baseline.outcome.activeFresh;
    }

    private static void beforeSecondReturn(DuelEnv env, boolean first) {
        var game = start(env, first);
        var trace = new Trace(game);
        game.finish();
        var hits = game.hits.list;
        double awake = Math.max(game.a.firstClick, game.b.firstClick);
        int chain = 0;
        double firstHit = Double.NaN;
        for (int i = 0; i < hits.size(); i++) {
            var hit = hits.get(i);
            if (!Double.isFinite(awake) || hit.at() < awake) continue;
            if (hit.sideA() != first) {
                chain = 0;
                continue;
            }
            if (hit.upgrade()) continue;
            if (++chain == 1) firstHit = hit.at();
            if (chain != 2 || hit.at() + HORIZON > env.limit) continue;

            for (int j = i + 1; j < hits.size() && hits.get(j).at() == hit.at(); j++) {
                if (hits.get(j).sideA() == first) continue;
                var second = trace.accepted.get(i);
                var returned = trace.accepted.get(j);
                if (second == null || returned == null) throw new AssertionError("Unlinked same-drain contacts");
                double deadline = Math.min(second.time, returned.time);
                var anchor = BoxingDrill.exactHit(env, first, firstHit, 1);
                var row = new LinkedHashMap<>(BoxingLateReturns.examine(anchor, Integer.MAX_VALUE, deadline));
                row.put("k2", hit.at());
                row.put("secondClick", second.values(hit.at()));
                row.put("returnClick", returned.values(hit.at()));
                row.put("returnUpgradeAtK2", hits.get(j).upgrade());
                row.put("lastEligibleStartAfterK1", deadline - firstHit);
                System.out.println(Json.encode(row));
                break;
            }
        }
    }

    /** Isolate the horizontal velocity packet after each second fresh hit, regardless of the later result. */
    private static void envelope(Path file, int ping, boolean first, boolean useKeys) throws Exception {
        var env = BoxingDoubleSearch.environment(file, ping, 20, 20_000);
        var game = start(env, first);
        var trace = new Trace(game);
        game.finish();
        double awake = Math.max(game.a.firstClick, game.b.firstClick);
        var hits = game.hits.list;
        var prefix = start(env, first);
        int chain = 0;
        double k1 = Double.NaN;
        for (int i = 0; i < hits.size(); i++) {
            var hit = hits.get(i);
            double from = hit.at();
            if (from < awake) continue;
            if (from + HORIZON > env.limit) break;
            if (hit.sideA() != first) {
                chain = 0;
                continue;
            }
            if (hit.upgrade()) continue;
            if (++chain == 1) k1 = from;
            if (chain == 2) {
                long packetId = trace.recoil.get(i);
                if (packetId < 0) throw new AssertionError("Unlinked K2 launch: " + env.clockId + "/" + from);
                prefix.clock.runUntil(from);
                var observed = prefix.hits.list;
                if (!hits.subList(0, observed.size()).equals(observed)) throw new AssertionError("W-tap prefix diverged");
                var committed = observed.subList(i + 1, observed.size());
                var unchanged = replay(env, prefix, from, 0, 0, 0);
                var expected = hits.subList(observed.size(), hits.size()).stream()
                        .takeWhile(h -> h.at() <= from + HORIZON).toList();
                if (!unchanged.equals(expected)) throw new AssertionError("W-tap continuation diverged");

                var packet = secondVelocity(prefix, packetId);
                double natural = Math.hypot(packet.vx, packet.vz);
                double opposite = natural > .65 ? .527375 : .854375;
                var force = new Velocity(packetId, packet.vx * opposite / natural, packet.vy, packet.vz * opposite / natural);
                var plant = prefix.clock.state;
                var victim = first ? plant.b : plant.a;
                var our = first ? plant.a : plant.b;
                double targetY = first ? plant.serverBy : plant.serverAy;
                var source = trace.accepted.get(i);
                if (useKeys && (ping != 80 || packet.vy != 0 || natural <= .65)) continue;
                var rows = new ArrayList<Object>();
                for (int phase : new int[]{0, 10}) {
                    var wtap = phase == 0 ? unchanged : replay(env, prefix, from, phase, 0, 0);
                    var switched = replay(env, prefix, from, phase, 0, 0, force);
                    rows.add(Map.of("phase", phase, "wtap", envelopeScore(committed, wtap, first, from),
                            "differentHorizontal", envelopeScore(committed, switched, first, from)));
                }
                var row = new LinkedHashMap<String,Object>();
                row.put("clock", env.clockId);
                row.put("rtt", ping);
                row.put("side", first ? "a" : "b");
                row.put("anchor", from);
                row.put("wtapHorizontal", natural);
                row.put("otherHorizontal", opposite);
                row.put("vertical", packet.vy);
                row.put("targetServerY", targetY);
                row.put("targetLocalY", victim.q.y());
                row.put("targetLocalVy", victim.q.vy());
                row.put("targetLocalGround", victim.q.onGround());
                row.put("ownGround", our.q.onGround());
                row.put("ownForwardSpeed", Math.hypot(our.q.vx(), our.q.vz()));
                row.put("serverGap", gap(plant));
                row.put("ownSwing", source == null ? null : source.values(from));
                row.put("phasePairs", rows);
                if (useKeys) {
                    if (source == null) throw new AssertionError("Unlinked K2 contact: " + env.clockId + "/" + from);
                    row.put("ordinaryInputs", ordinaryWalks(env, first, k1, source, trace));
                }
                System.out.println(Json.encode(row));
            }
        }
    }

    public static void main(String[] args) throws Exception {
        if (args[0].equals("pre-k2-return")) {
            var wires = Path.of(args[1]);
            var seeds = Path.of(args[2]);
            for (int i = 3; i < args.length; i++) {
                var env = BoxingDoubleSearch.environment(Path.of(args[i]), 80, 20, 20_000);
                BoxingJumpTimingAudit.wireTiming(env, wires.resolve(env.clockId + ".txt"));
                BoxingJumpTimingAudit.seedTracker(env, seeds.resolve(env.clockId + ".txt"));
                for (boolean first : new boolean[]{true, false}) beforeSecondReturn(env, first);
            }
            return;
        }
        if (args[0].equals("wire-trace")) {
            int rtt = Integer.parseInt(args[1]);
            var wires = Path.of(args[2]);
            var seeds = Path.of(args[3]);
            for (int i = 4; i < args.length; i++) {
                var env = BoxingDoubleSearch.environment(Path.of(args[i]), rtt, 20, 20_000);
                BoxingJumpTimingAudit.wireTiming(env, wires.resolve(env.clockId + ".txt"));
                BoxingJumpTimingAudit.seedTracker(env, seeds.resolve(env.clockId + ".txt"));
                for (boolean first : new boolean[]{true, false}) run(env, first, false, true);
            }
            return;
        }
        if (args[0].equals("envelope") || args[0].equals("walk")) {
            boolean useKeys = args[0].equals("walk");
            for (int i = 1; i < args.length; i++) for (int ping : useKeys ? new int[]{80} : new int[]{0, 20, 80}) {
                for (boolean first : new boolean[]{true, false}) envelope(Path.of(args[i]), ping, first, useKeys);
            }
            return;
        }
        boolean beforeSecond = args[0].equals("strength");
        for (int i = beforeSecond ? 1 : 0; i < args.length; i++) for (int ping : new int[]{0, 80}) {
            for (boolean first : new boolean[]{true, false}) run(Path.of(args[i]), ping, first, beforeSecond);
        }
    }
}
