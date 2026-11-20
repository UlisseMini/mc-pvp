import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lab.Json;

/** Isolate one change to movement on an ordinary W-tap cue from a complete copied state. */
final class BoxingReleaseWindow {
    private static final int REGULATION_ANCHORS = 8;
    private static final double HORIZON = 2200;
    private static final int[][] PHASES = {{0, 0}, {-2, 0}, {2, 0}, {0, -2}, {0, 2}};
    private static final boolean POSITION_TIMING = Boolean.getBoolean("lab.boxing.releaseWindowPositionTiming");
    private static final boolean RESET_ORDER = Boolean.getBoolean("lab.boxing.releaseWindowResetOrder");
    private static final String WITNESS = System.getProperty("lab.boxing.releaseWindowWitness");
    private static final boolean LONG_REGULATION = Boolean.getBoolean("lab.boxing.releaseWindowLongRegulation");
    private static final List<List<String>> RESET_WORDS = List.of(
            List.of("keep", "keep", "keep"),
            List.of("walk", "keep", "keep"),
            List.of("sprint", "keep", "keep"),
            List.of("walk", "release", "keep"),
            List.of("sprint", "release", "keep"),
            List.of("release", "walk", "keep"),
            List.of("release", "release", "keep"));

    private record Cut(DuelEnv.Game game, int stock, int change, Map<String, Object> local) {}

    record ShiftedReceipt(PeriodicComboFightModel.PacketTiming wire, int a, int b,
                          int positionDirection)
            implements PeriodicComboFightModel.PacketTiming {
        @Override public double arrival(int route, double send, double leg) {
            return wire.arrival(route, send, leg);
        }

        @Override public PeriodicComboFightModel.PacketTimingPlan planForPacket(
                int route, double send, double leg, PeriodicComboFightModel.TimingSemantic semantic, int id) {
            var plan = wire.planForPacket(route, send, leg, semantic, id);
            boolean attack = route < 2 && id == 2;
            boolean position = route >= 2 && id >= 0x14 && id <= 0x18;
            int multiplier = position ? positionDirection : 0;
            if (attack && !POSITION_TIMING) multiplier = 1;
            int shift = (route % 2 == 0 ? a : b) * multiplier;
            double arrival = Math.max(send, plan.rawReceiptMs() + shift);
            double ready = plan.readyMs() == null ? arrival : Math.max(arrival, plan.readyMs() + shift);
            return new PeriodicComboFightModel.PacketTimingPlan(arrival, ready, plan.cueDelayNanos(), plan.service());
        }

        @Override public ShiftedReceipt deepCopy() { return new ShiftedReceipt(wire.deepCopy(), a, b, positionDirection); }
    }

    private static final class Reading {
        Double readyAt, ownGap, defenderGap, first, returned;
        Integer readyTick, firstTick, returnTick;
        final int[] admitted = new int[2];
        final List<List<Long>> times = List.of(new ArrayList<>(), new ArrayList<>());
        final List<List<Object>> early = new ArrayList<>();
        final List<List<Object>> attempts = new ArrayList<>();
        final List<List<Object>> launches = new ArrayList<>();
        boolean readyOnNextDrain;
    }

    private static Map<String, Object> assess(BoxingDrill.Checkpoint checkpoint, Cut cut, boolean change,
                                              double end, int[] phase) {
        int word = change ? BoxingChaseEnvelope.movementWord(List.of(
                cut.change == 2 ? "sprint" : "release", "keep", "keep")) : 0;
        return assess(checkpoint, cut, word, end, phase, POSITION_TIMING ? 1 : 0, false);
    }

    private static Map<String, Object> assess(BoxingDrill.Checkpoint checkpoint, Cut cut, int word,
                                              double end, int[] phase, int direction, boolean longScore) {
        var game = BoxingChaseEnvelope.branch(checkpoint.env, cut.game,
                new BoxingChaseEnvelope.Movement(cut.game.our(), word));
        var clock = game.clock;
        if (phase[0] != 0 || phase[1] != 0)
            clock.state.packetTiming = new ShiftedReceipt(clock.state.packetTiming,
                    game.first ? phase[0] : phase[1], game.first ? phase[1] : phase[0], direction);
        var reading = new Reading();
        double anchor = checkpoint.landmark;
        double from = cut.game.clock.time();
        var ownMove = game.first ? "MOVEA" : "MOVEB";
        clock.setClickAudit(e -> {
            if (e.action().equals("ADMIT")) {
                int side = e.fromA() == game.first ? 0 : 1;
                reading.admitted[side]++;
                if (RESET_ORDER) reading.times.get(side).add(e.admittedNanos());
            }
            if (WITNESS != null && e.time() - from < 220 && e.fromA() == game.first && e.action().equals("CONSUME")) {
                var o = clock.observation(game.first);
                reading.early.add(List.of("click", e.time()-from, e.contact(), o.own().x(), o.own().vx(), o.sprint(), o.nearest()));
            }
            if (RESET_ORDER && WITNESS != null && e.time() <= end && e.action().equals("CONSUME")) {
                var state = clock.state;
                var body = e.fromA() ? state.a : state.b;
                var view = e.fromA() ? state.ab : state.ba;
                double ray = PeriodicComboFightModel.ray(body, view);
                long packet = e.contact() ? state.seq - 1 : -1;
                reading.attempts.add(List.of(e.time() - anchor, e.fromA() == game.first,
                        e.admissionId(), e.eligible(), e.contact(), PeriodicComboFightModel.nearest(body.q, view),
                        Double.isFinite(ray) ? ray : -1, body.q.vx(), packet));
            }
        });
        clock.setAudit((t, stage, state) -> {
            int count = game.hits.list.size();
            game.hits.event(t, stage, state);
            if (stage.equals("SERVER_DRAIN") && reading.readyOnNextDrain && reading.readyAt == null) {
                reading.readyAt = t - anchor;
                reading.readyTick = state.serverTicks;
                var own = game.first ? state.a : state.b;
                var target = game.first ? state.b : state.a;
                reading.ownGap = PeriodicComboFightModel.nearest(own.q, game.first ? state.ab : state.ba);
                reading.defenderGap = PeriodicComboFightModel.nearest(target.q, game.first ? state.ba : state.ab);
            }
            for (; count < game.hits.list.size(); count++) {
                var hit = game.hits.list.get(count);
                if (RESET_ORDER && WITNESS != null && t <= end && !hit.upgrade()) {
                    var launch = hit.sideA() ? state.lastFreshB : state.lastFreshA;
                    long packet = hit.sideA() ? state.lastFreshPacketA : state.lastFreshPacketB;
                    reading.launches.add(List.of(hit.at() - anchor, hit.sideA() == game.first,
                            Math.hypot(launch.x(), launch.z()), launch.y(), packet));
                }
                if (hit.sideA() == game.first && !hit.upgrade() && reading.first == null) {
                    reading.first = hit.at() - anchor;
                    reading.firstTick = state.serverTicks;
                }
                if (hit.sideA() != game.first && reading.returned == null) {
                    reading.returned = hit.at() - anchor;
                    reading.returnTick = state.serverTicks;
                }
            }
            if (stage.equals("SERVER_ENTITY") && reading.first == null
                    && (game.first ? state.resistant : state.ownResistant) <= clock.config.hitTicks)
                reading.readyOnNextDrain = true;
            if (WITNESS != null && t - from < 220 && stage.equals(ownMove)) {
                var o = clock.observation(game.first);
                reading.early.add(List.of("move", t-from, clock.appliedControl(game.first).key(),
                        o.own().x(), o.own().vx(), o.sprint(), o.nearest()));
            }
        });
        clock.runUntil(end);
        var result = new LinkedHashMap<String, Object>();
        result.put("score", BoxingLateReturns.score(game, anchor, 100.0 * clock.config.hitTicks + 100).values());
        result.put("readyAt", reading.readyAt);
        result.put("ownGap", reading.ownGap);
        result.put("defenderGap", reading.defenderGap);
        result.put("nextFreshMs", reading.first);
        result.put("returnMs", reading.returned);
        result.put("readyTick", reading.readyTick);
        result.put("nextFreshTick", reading.firstTick);
        result.put("returnTick", reading.returnTick);
        result.put("physicalPresses", List.of(reading.admitted[0], reading.admitted[1]));
        if (RESET_ORDER) result.put("pressTimes", List.of(List.copyOf(reading.times.get(0)), List.copyOf(reading.times.get(1))));
        if (WITNESS != null) result.put("early", reading.early);
        if (RESET_ORDER && WITNESS != null) {
            result.put("attempts", reading.attempts);
            result.put("freshLaunches", reading.launches);
        }
        result.put("hits", game.hits.list.stream().map(h ->
                List.of(h.at() - anchor, h.sideA() == game.first, h.upgrade())).toList());
        if (longScore) {
            clock.runUntil(checkpoint.env.limit);
            result.put("fullScore", BoxingLateReturns.score(game, anchor, 100.0 * clock.config.hitTicks + 100).values());
            result.put("fullPresses", List.of(reading.admitted[0], reading.admitted[1]));
        }
        if (LONG_REGULATION) {
            var serverEnds = checkpoint.env.serverEnds;
            double horizon = Math.min(checkpoint.env.limit, serverEnds[serverEnds.length - 1]);
            clock.runUntil(horizon);
            int a = cut.game.clock.state.acceptedA, b = cut.game.clock.state.acceptedB;
            int ownFresh = 0, longestFresh = 0, streak = 0;
            Double deciding = null;
            for (var hit : game.hits.list) {
                if (hit.sideA()) a++; else b++;
                if (hit.sideA() != game.first) {
                    streak = 0;
                } else if (!hit.upgrade()) {
                    ownFresh++;
                    longestFresh = Math.max(longestFresh, ++streak);
                }
                if (a == 100 || b == 100) {
                    deciding = hit.at();
                    break;
                }
            }
            double finish = deciding == null ? horizon : deciding;
            double elapsed = finish - from;
            result.put("regulation", Map.of("own", game.first ? a : b, "opponent", game.first ? b : a,
                    "throughMs", finish, "censored", deciding == null,
                    "ownFreshAfterCut", ownFresh, "longestFreshRunAfterCut", longestFresh,
                    "suffixCps", reading.times.stream().map(ts ->
                            1000 * ts.stream().filter(t -> t / 1e6 <= finish).count() / elapsed).toList()));
            result.put("regulationPressTimes", reading.times);
        }
        return result;
    }

    private static Map<String, Object> observation(DuelEnv.Game game, PeriodicComboClock.Observation current,
                                                    PeriodicComboClock.Observation previous) {
        var state = game.clock.state;
        double dx = current.targetX() - current.own().x();
        double dz = current.targetZ() - current.own().z();
        double gap = Math.hypot(dx, dz);
        double along = gap == 0 ? 0 : (current.own().vx() * dx + current.own().vz() * dz) / gap;
        var output = new LinkedHashMap<String, Object>();
        output.put("nearest", current.nearest());
        output.put("ownAlong", along);
        output.put("ownVertical", current.own().vy());
        output.put("grounded", current.own().onGround());
        output.put("sprint", current.sprint());
        output.put("targetHeight", current.targetY() - current.own().y());
        output.put("cueAge", current.time() - current.lastTargetHurtTime());
        output.put("ownHurtAge", Double.isFinite(current.lastOwnHurtTime()) ? current.time() - current.lastOwnHurtTime() : null);
        output.put("queued", game.clock.queuedClicks(game.first));
        output.put("missCounter", game.clock.missCounter(game.first));
        output.put("closure", previous == null || current.time() == previous.time() ? null
                : (previous.nearest() - current.nearest()) * 50 / (current.time() - previous.time()));
        return output;
    }

    private static List<Cut> cuts(BoxingDrill.Checkpoint checkpoint) {
        var prefix = checkpoint.suffix(null);
        double anchor = checkpoint.landmark;
        double end = Math.min(checkpoint.env.limit, anchor + 325);
        if (end < prefix.clock.time() || !prefix.clock.advanceToBeforeNextHead(prefix.first, end))
            return List.of();
        var result = new ArrayList<Cut>();
        var history = checkpoint.history;
        PeriodicComboClock.Observation previous = history.isEmpty() ? null : history.get(history.size()-1).view();
        double seenRelease = Double.NaN;
        do {
            if (!prefix.hits.list.isEmpty()) break;
            var o = prefix.clock.observation(prefix.first);
            int key = prefix.our().copy().input(o).key();
            double cue = o.lastTargetHurtTime();
            if (cue >= anchor && o.time() - cue <= 160) {
                if (key == 0 && !Double.isFinite(seenRelease)) {
                    result.add(save(checkpoint.env, prefix, o, previous, 0, 2));
                    seenRelease = cue;
                }
                if (key == 2 && cue == seenRelease) {
                    result.add(save(checkpoint.env, prefix, o, previous, 2, 0));
                    break;
                }
            }
            previous = o;
        } while (prefix.clock.runHeadInterval(prefix.first, end));
        return result;
    }

    private static Cut save(DuelEnv env, DuelEnv.Game game, PeriodicComboClock.Observation current,
                            PeriodicComboClock.Observation previous, int key, int replacement) {
        return new Cut(BoxingChaseEnvelope.branch(env, game, game.our().copy()),
                key, replacement, observation(game, current, previous));
    }

    private static void run(BoxingDrill.Checkpoint checkpoint, String mode, int index) {
        var identity = mode + ":" + (checkpoint.state.first ? "a" : "b") + ":" + index;
        if (WITNESS != null && !WITNESS.equals(identity) && !WITNESS.equals(identity + ":" + checkpoint.k))
            return;
        if (LONG_REGULATION && !mode.equals("regulation")) return;
        var cuts = cuts(checkpoint);
        double end = Math.min(checkpoint.env.limit, checkpoint.landmark + HORIZON);
        var rows = new ArrayList<Map<String, Object>>();
        var expected = checkpoint.suffix(null);
        expected.clock.runUntil(end);
        for (var cut : cuts) {
            if (RESET_ORDER) {
                if (cut.stock == 0) rows.add(resetOrder(checkpoint, cut, end, expected, mode.equals("maintenance")));
                continue;
            }
            var phases = new ArrayList<Map<String, Object>>();
            for (int[] phase : PHASES) {
                var wtap = assess(checkpoint, cut, false, end, phase);
                var changed = assess(checkpoint, cut, true, end, phase);
                if (phase[0] == 0 && phase[1] == 0) {
                    var hits = expected.hits.list.stream().map(h -> List.of(
                            h.at() - checkpoint.landmark, h.sideA() == expected.first, h.upgrade())).toList();
                    if (!hits.equals(wtap.get("hits"))) throw new AssertionError("Control changed after copying");
                }
                phases.add(Map.of("phase", List.of(phase[0], phase[1]), "wtap", wtap, "changed", changed));
            }
            rows.add(Map.of("stock", cut.stock, "change", cut.change, "cut", cut.game.clock.time() - checkpoint.landmark,
                    "local", cut.local, "phases", phases));
        }
        var output = new LinkedHashMap<>(Map.of("clock", checkpoint.env.clockId, "rtt", checkpoint.env.rtt,
                "seat", checkpoint.state.first ? "a" : "b", "mode", mode, "index", index,
                "anchor", checkpoint.landmark, "cuts", rows, "remainder", end - checkpoint.landmark));
        if (RESET_ORDER) output.put("k", checkpoint.k);
        System.out.println(Json.encode(output));
    }

    private static Map<String, Object> resetOrder(BoxingDrill.Checkpoint checkpoint, Cut cut, double end,
                                                   DuelEnv.Game expected, boolean longScore) {
        var results = new ArrayList<Map<String, Object>>();
        for (int own : new int[]{0, -2, 2}) for (int other : new int[]{0, -2, 2}) {
            for (int direction : new int[]{1, -1}) {
                if (own == 0 && other == 0 && direction == -1) continue;
                var arms = new ArrayList<Map<String, Object>>();
                for (var keys : RESET_WORDS) {
                    arms.add(assess(checkpoint, cut, BoxingChaseEnvelope.movementWord(keys),
                            end, new int[]{own, other}, direction, longScore));
                }
                if (own == 0 && other == 0) {
                    var hits = expected.hits.list.stream().map(h -> List.of(h.at() - checkpoint.landmark,
                            h.sideA() == expected.first, h.upgrade())).toList();
                    if (!hits.equals(arms.get(0).get("hits"))) throw new AssertionError("Control changed after copying");
                }
                if (LONG_REGULATION) compareRegulationPresses(arms);
                results.add(Map.of("phase", List.of(own, other, direction), "arms", arms));
            }
        }
        return Map.of("cut", cut.game.clock.time() - checkpoint.landmark, "local", cut.local,
                "words", RESET_WORDS, "results", results);
    }

    @SuppressWarnings("unchecked")
    private static void compareRegulationPresses(List<Map<String, Object>> arms) {
        var stock = arms.get(0);
        var baseline = (List<List<Long>>) stock.get("regulationPressTimes");
        double baselineEnd = (double) ((Map<?, ?>) stock.get("regulation")).get("throughMs");
        for (var arm : arms) {
            var times = (List<List<Long>>) arm.remove("regulationPressTimes");
            double commonEnd = Math.min(baselineEnd, (double) ((Map<?, ?>) arm.get("regulation")).get("throughMs"));
            boolean same = true;
            for (int side = 0; side < times.size(); side++) {
                var reference = baseline.get(side).stream().takeWhile(t -> t / 1e6 <= commonEnd).toList();
                var actual = times.get(side).stream().takeWhile(t -> t / 1e6 <= commonEnd).toList();
                same &= reference.equals(actual);
            }
            arm.put("samePressTimesThroughEarlierFinish", same);
        }
    }

    public static void main(String[] args) throws Exception {
        if (LONG_REGULATION && (!RESET_ORDER || WITNESS == null))
            throw new IllegalArgumentException("Long regulation requires a specific reset-order witness");
        int rtt = Integer.parseInt(args[0]);
        var wire = Path.of(args[1]);
        var seeds = Path.of(args[2]);
        for (int i = 3; i < args.length; i++) {
            var env = BoxingEdgeHitSelect.environment(Path.of(args[i]), rtt, LONG_REGULATION ? 180_000 : 20_000, wire, seeds);
            for (boolean first : new boolean[]{true, false}) {
                if (!LONG_REGULATION) {
                    for (var checkpoint : BoxingDrill.acquire(env, first, RESET_ORDER))
                        run(checkpoint, "maintenance", 0);
                }
                var starts = BoxingDrill.regulationFirstHits(env, first).stream()
                        .filter(c -> c.landmark + HORIZON < env.limit).limit(REGULATION_ANCHORS).toList();
                for (int j = 0; j < starts.size(); j++) run(starts.get(j), "regulation", j);
            }
        }
    }
}
