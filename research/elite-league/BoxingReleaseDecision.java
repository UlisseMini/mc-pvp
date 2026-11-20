import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lab.Json;

/** Diagnose one prospective stock release after a real regulation trade. */
final class BoxingReleaseDecision {
    private static final int CUE_SEARCH_MS = 350;
    private static final int HORIZON_MS = 2_200;
    private static final int[][] FUTURE_PHASES = {{0,0}, {0,-5}, {0,5}, {5,0}, {5,5}};
    private static final boolean TEST_DRAG = Boolean.getBoolean("lab.boxing.releaseDragAblation");
    private static final boolean TRACE_HEADS = Boolean.getBoolean("lab.boxing.releaseTraceHeads");
    private static final boolean TRACE_ATTEMPTS = Boolean.getBoolean("lab.boxing.releaseTraceAttempts");
    private static final int FUTURE_RTT = Integer.getInteger("lab.boxing.releaseFutureRtt", -1);

    enum Choice {
        WTAP(0), WALK(1), SPRINT(2);

        final int key;
        Choice(int key) { this.key = key; }
    }

    interface HeadAdjustment {
        PeriodicComboClock.Control adjust(int index, PeriodicComboClock.Observation own, PeriodicComboClock.Control stock);
    }

    static final class Trial extends DuelEnv.Player {
        final DuelEnv.Player tap;
        final Choice choice;
        final HeadAdjustment adjustment;
        Map<String,Object> applied;
        int headIndex;

        Trial(DuelEnv.Player warmed, Choice choice, HeadAdjustment adjustment) {
            super("wtap", 0, warmed.rtt, warmed.wake);
            tap = warmed.copy();
            this.choice = choice;
            this.adjustment = adjustment;
            firstClick = warmed.firstClick;
        }

        @Override public PeriodicComboClock.Control input(PeriodicComboClock.Observation own) {
            var stock = tap.input(own);
            int index = headIndex++;
            if (applied != null) return adjustment == null ? stock : adjustment.adjust(index, own, stock);
            int key = stock.key() == 0 ? choice.key : stock.key();
            applied = Map.of("previousChoice", stock.key(), "key", key, "observation", observations(own));
            return new PeriodicComboClock.Control(key, stock.strafe(), stock.click(), stock.jump());
        }
    }

    private static double age(double now, double at) { return Double.isFinite(at) ? now - at : -1; }

    private static Map<String,Object> observations(PeriodicComboClock.Observation own) {
        var p = own.own();
        double dx = own.targetX() - p.x(), dz = own.targetZ() - p.z(), horizontal = Math.hypot(dx, dz);
        double toward = horizontal == 0 ? 0 : (p.vx() * dx + p.vz() * dz) / horizontal;
        return Map.of("at", own.time(), "nearest", own.nearest(), "targetY", own.targetY() - p.y(),
                "grounded", p.onGround(), "sprinting", own.sprint(), "toward", toward, "vy", p.vy(),
                "incomingAge", age(own.time(), own.lastOwnHurtTime()),
                "targetAge", age(own.time(), own.lastTargetHurtTime()),
                "lastContactAge", age(own.time(), own.lastContactTime()));
    }

    record Evidence(List<DuelEnv.Hit> hits, List<List<Long>> presses,
                    Map<String,Object> decision, List<Map<String,Object>> contacts, Map<String,Object> result) {}

    record Drag(long admission, double factor) {}

    static Evidence run(DuelEnv env, DuelEnv.Game prefix, DuelEnv.Player tap, Choice choice, int[] phase) {
        return run(env, prefix, tap, choice, phase, null, null);
    }

    private static Evidence run(DuelEnv env, DuelEnv.Game prefix, DuelEnv.Player tap, Choice choice, int[] phase, Drag drag) {
        return run(env, prefix, tap, choice, phase, drag, null);
    }

    static Evidence adjusted(DuelEnv env, DuelEnv.Game prefix, DuelEnv.Player tap, int[] phase, HeadAdjustment adjustment) {
        return run(env, prefix, tap, Choice.SPRINT, phase, null, adjustment);
    }

    private static Evidence run(DuelEnv env, DuelEnv.Game prefix, DuelEnv.Player tap, Choice choice, int[] phase,
                                Drag drag, HeadAdjustment adjustment) {
        var trial = new Trial(tap, choice, adjustment);
        var game = env.new Game(prefix, prefix.first ? trial : prefix.a.copy(), prefix.first ? prefix.b.copy() : trial);
        game.trace();
        BoxingAcquisitionControl.shift(game, phase[0], phase[1]);
        if (FUTURE_RTT >= 0) {
            game.clock.config.ownRtt = FUTURE_RTT;
            game.clock.config.otherRtt = FUTURE_RTT;
            Arrays.fill(game.clock.state.nominalHalfRtt, FUTURE_RTT / 2.0);
        }
        var evidence = new Ledger(game, drag);
        double started = prefix.clock.time();
        game.clock.runUntil(started + HORIZON_MS);
        if (trial.applied == null) throw new AssertionError("No post-cut head");
        if (drag != null && !evidence.replaced) throw new AssertionError("Target physical contact was absent");
        var result = new LinkedHashMap<String,Object>(Map.of("decision", trial.applied, "nextHead", evidence.nextHead,
                "contacts", evidence.contacts, "hits", evidence.hits));
        if (TRACE_HEADS && choice == Choice.SPRINT) {
            result.put("ownHeads", evidence.ownHeads);
            result.put("deliveryStages", evidence.deliveryStages);
        }
        if (TRACE_ATTEMPTS) result.put("attempts", evidence.attempts);
        return new Evidence(game.hits.list, List.of(evidence.admitted.get(0), evidence.admitted.get(1)),
                trial.applied, evidence.contacts, result);
    }

    private static final class Ledger {
        final DuelEnv.Game game;
        final Drag drag;
        final List<List<Long>> admitted = List.of(new ArrayList<>(), new ArrayList<>());
        final List<Map<String,Object>> contacts = new ArrayList<>();
        final List<Object> hits = new ArrayList<>();
        final boolean[] sprint = new boolean[2];
        final double[] x = new double[2], z = new double[2];
        final Map<String,Object> nextHead = new LinkedHashMap<>();
        final List<Object> ownHeads = new ArrayList<>();
        final List<Object> deliveryStages = new ArrayList<>();
        final List<Object> attempts = new ArrayList<>();
        int heads;
        boolean replaced;

        Ledger(DuelEnv.Game game, Drag drag) {
            this.game = game;
            this.drag = drag;
            rememberMotion();
            game.clock.setClickAudit(this::click);
            game.clock.setAudit(this::event);
        }

        private void rememberMotion() {
            var s = game.clock.state;
            sprint[0] = s.a.sprint; sprint[1] = s.b.sprint;
            x[0] = s.a.q.vx(); x[1] = s.b.q.vx();
            z[0] = s.a.q.vz(); z[1] = s.b.q.vz();
        }

        private void click(PeriodicComboClock.ClickEvent event) {
            int side = event.fromA() ? 0 : 1;
            if (event.action().equals("ADMIT")) {
                var times = admitted.get(side);
                if (!times.isEmpty() && event.admittedNanos() - times.get(times.size() - 1) < 50_000_000L)
                    throw new AssertionError("Too many clicks");
                times.add(event.admittedNanos());
            }
            if (TRACE_ATTEMPTS && event.action().equals("CONSUME")) {
                var s = game.clock.state;
                var body = event.fromA() ? s.a : s.b;
                var view = event.fromA() ? s.ab : s.ba;
                double ray = PeriodicComboFightModel.ray(body,view);
                attempts.add(Map.of("at",event.time(),"ours",event.fromA() == game.first,"admission",event.admissionId(),
                        "eligible",event.eligible(),"contact",event.contact(),"ray",Double.isFinite(ray) ? ray : -1,
                        "body",List.of(body.q.x(),body.q.y(),body.q.z(),body.q.onGround(),body.q.vx(),body.q.vy(),body.q.vz()),
                        "target",List.of(view.x,view.y,view.z)));
            }
            if (!event.action().equals("CONSUME") || !event.contact()) return;
            var state = game.clock.state;
            var last = state.packets.get(state.packets.size() - 1);
            if (last.sequence != state.seq - 1 || last.kind != (event.fromA() ? 3 : 7))
                throw new AssertionError("Unknown local contact source");
            var body = event.fromA() ? state.a : state.b;
            var view = event.fromA() ? state.ab : state.ba;
            double factor = sprint[side] ? .6 : 1;
            if (body.q.vx() != x[side] * factor || body.q.vz() != z[side] * factor)
                throw new AssertionError("Physical contact changed velocity unexpectedly");
            boolean ours = event.fromA() == game.first;
            if (drag != null && ours && event.admissionId() == drag.admission) {
                if (replaced || factor == drag.factor) throw new AssertionError("Unexpected drag on target contact");
                body.velocity(x[side] * drag.factor, body.q.vy(), z[side] * drag.factor);
                replaced = true;
            }
            contacts.add(Map.of("at", event.time(), "ours", ours, "admission", event.admissionId(),
                    "ray", PeriodicComboFightModel.ray(body, view), "sprintBefore", sprint[side],
                    "before", List.of(x[side], z[side]), "after", List.of(body.q.vx(), body.q.vz()),
                    "packet", last.sequence, "ready", last.ready));
            rememberMotion();
        }

        private void event(double time, String stage, PeriodicComboFightModel.State state) {
            int before = game.hits.list.size();
            game.hits.event(time, stage, state);
            for (; before < game.hits.list.size(); before++) {
                var hit = game.hits.list.get(before);
                var launch = hit.sideA() ? state.lastFreshB : state.lastFreshA;
                long packet = hit.upgrade() ? (hit.sideA() ? state.lastAcceptedPacketA : state.lastAcceptedPacketB)
                        : (hit.sideA() ? state.lastFreshPacketA : state.lastFreshPacketB);
                var row = new LinkedHashMap<String,Object>(Map.of("at", time, "ours", hit.sideA() == game.first,
                        "upgrade", hit.upgrade(), "packet", packet));
                if (!hit.upgrade()) {
                    if (launch == null || launch.time() != time) throw new AssertionError("Unknown hit impulse");
                    row.put("impulse", List.of(launch.x(), launch.y(), launch.z()));
                    row.put("defenderGround", launch.victimBefore().onGround());
                }
                hits.add(row);
            }
            if (stage.equals(game.first ? "POLICYA" : "POLICYB")) {
                var own = game.clock.observation(game.first);
                if (++heads == 2) nextHead.putAll(observations(own));
                if (TRACE_HEADS) ownHeads.add(Map.of("at", time, "targetHurt", age(time, own.lastTargetHurtTime()),
                        "targetHurtTime", Double.isFinite(own.lastTargetHurtTime()) ? own.lastTargetHurtTime() : -1,
                        "key", game.clock.appliedControl(game.first).key(), "nearest", own.nearest()));
            }
            if (TRACE_HEADS && (stage.equals(game.first ? "MOVEA" : "MOVEB")
                    || stage.equals(game.first ? "FLUSHA" : "FLUSHB")
                    || stage.equals("SERVER_DRAIN") || stage.equals("SERVER_TRACK"))) {
                deliveryStages.add(List.of(time, stage));
            }
            rememberMotion();
        }
    }

    private static Map<String,Object> dragTest(DuelEnv env, DuelEnv.Game prefix, DuelEnv.Player tap,
                                               int[] phase, Evidence base, Evidence sprint) {
        var baseContacts = new LinkedHashMap<Long,Map<String,Object>>();
        double limit = prefix.clock.time() + 250;
        for (var contact : base.contacts) {
            if ((double) contact.get("at") > limit) break;
            if ((boolean) contact.get("ours") && !(boolean) contact.get("sprintBefore"))
                baseContacts.put((long) contact.get("admission"), contact);
        }
        for (var contact : sprint.contacts) {
            if ((double) contact.get("at") > limit) break;
            if (!(boolean) contact.get("ours") || !(boolean) contact.get("sprintBefore")) continue;
            long id = (long) contact.get("admission");
            var stock = baseContacts.get(id);
            if (stock == null || !stock.get("at").equals(contact.get("at"))) continue;
            var restore = run(env, prefix, tap, Choice.SPRINT, phase, new Drag(id, 1));
            var damp = run(env, prefix, tap, Choice.WTAP, phase, new Drag(id, .6));
            if (!base.presses.equals(restore.presses) || !base.presses.equals(damp.presses))
                throw new AssertionError("Drag changed physical press schedules");
            return Map.of("admission", id, "at", contact.get("at"),
                    "SPRINT_NO_DRAG", restore.result, "WTAP_WITH_DRAG", damp.result);
        }
        return Map.of();
    }

    private static boolean findRelease(DuelEnv.Game prefix, DuelEnv.Player tap, double until) {
        if (until < prefix.clock.time()) return false;
        if (!prefix.clock.advanceToBeforeNextHead(prefix.first, until)) return false;
        do {
            if (tap.copy().input(prefix.clock.observation(prefix.first)).key() == 0) return true;
        } while (prefix.clock.runHeadInterval(prefix.first, until));
        return false;
    }

    private static void study(DuelEnv env, Arena.Model model, boolean first, BoxingLaterTrades.Use source, int own, int other) {
        var original = BoxingLaterTrades.start(env, model, first, source, own, other);
        var scored = BoxingDoubleSearch.play(original, 100);
        if (Math.max(scored.own(), scored.other()) != 100) throw new AssertionError("Unfinished first-to-100");
        var starts = BoxingLaterTrades.anchors(original.hits.list);
        var prefix = BoxingLaterTrades.start(env, model, first, source, own, other);
        var tap = ((BoxingLaterTrades.Trial) prefix.our()).player.tap;
        var cuts = new ArrayList<Object>();
        double lastCut = -1;
        for (var anchor : starts) {
            prefix.clock.runUntil(Math.max(prefix.clock.time(), anchor.hit().at()));
            if (!findRelease(prefix, tap, anchor.hit().at() + CUE_SEARCH_MS) || lastCut == prefix.clock.time()) continue;
            lastCut = prefix.clock.time();
            var committed = prefix.hits.list;
            if (!committed.equals(original.hits.list.subList(0, committed.size())))
                throw new AssertionError("Source prefix failed to replay");
            if (lastCut + HORIZON_MS >= original.clock.time()) continue;
            var known = committed.get(committed.size() - 1);
            var tested = new ArrayList<Object>();
            var admission = prefix.clock.observation(first);
            for (int[] phase : FUTURE_PHASES) {
                var base = run(env, prefix, tap, Choice.WTAP, phase);
                var results = new LinkedHashMap<String,Object>();
                Map<String,Object> drag = Map.of();
                for (Choice choice : Choice.values()) {
                    var arm = choice == Choice.WTAP ? base : run(env, prefix, tap, choice, phase);
                    if (!arm.presses.equals(base.presses)) throw new AssertionError("Unmatched press timing");
                    if (!arm.decision.get("observation").equals(base.decision.get("observation")))
                        throw new AssertionError("Arms observed different pre-action inputs");
                    if (choice == Choice.WTAP && phase[0] == 0 && phase[1] == 0 && FUTURE_RTT < 0
                            && source == BoxingLaterTrades.Use.WTAP) {
                        double end = lastCut + HORIZON_MS;
                        var expected = original.hits.list.subList(committed.size(), original.hits.list.size()).stream()
                                .filter(hit -> hit.at() <= end).toList();
                        if (!arm.hits.equals(expected)) throw new AssertionError("Stock replay diverged");
                    }
                    results.put(choice.toString(), arm.result);
                    if (TEST_DRAG && choice == Choice.SPRINT) drag = dragTest(env,prefix,tap,phase,base,arm);
                }
                tested.add(TEST_DRAG ? Map.of("timing", phase, "arms", results, "drag", drag)
                        : Map.of("timing", phase, "arms", results));
            }
            cuts.add(Map.of("anchor", anchor.hit().at(), "at", lastCut, "local", observations(admission),
                    "lastAccepted", List.of(known.at(), known.sideA() == first, known.upgrade()), "timings", tested));
        }
        var result = new LinkedHashMap<String,Object>(Map.of("clock", env.clockId, "seat", first ? "a" : "b",
                "phase", List.of(own, other), "source", source.toString(), "regulation", scored.values(),
                "eligibleTrades", starts.size(), "cuts", cuts));
        if (FUTURE_RTT >= 0) result.put("futureRtt", FUTURE_RTT);
        System.out.println(Json.encode(result));
    }

    public static void main(String[] args) throws Exception {
        var model = Arena.Model.decode(Files.readString(Path.of(args[0])).strip());
        int rtt = Integer.parseInt(args[1]);
        var wire = Path.of(args[2]);
        var seeds = Path.of(args[3]);
        for (int i = 4; i < args.length; i++) {
            var env = BoxingEdgeHitSelect.environment(Path.of(args[i]), rtt, 180_000, wire, seeds);
            for (boolean first : new boolean[]{true, false}) {
                for (int own : new int[]{0,-5,5}) for (int other : new int[]{0,-5,5}) {
                    for (var seed : List.of(BoxingLaterTrades.Use.WTAP, BoxingLaterTrades.Use.FULL)) {
                        study(env, model, first, seed, own, other);
                    }
                }
            }
        }
    }
}
