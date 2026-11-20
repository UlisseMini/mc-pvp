import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import lab.Json;

/** One click release from an unchanged W-tap approach. No policy is fitted. */
final class BoxingReentryRelease {
    private static final int WINDOW = 2_500;
    private static final int FALLBACK = 600;
    private static final int[] PHASES = Arrays.stream(System.getProperty("lab.boxing.releasePhaseMs", "0,5,15")
            .split(",")).mapToInt(Integer::parseInt).toArray();
    private static final int ENROLL_UNTIL = Integer.getInteger("lab.boxing.releaseEnrollMs", Integer.MAX_VALUE);
    private static final int FRESH_OVERRIDE = Integer.getInteger("lab.boxing.releaseFreshTicks", 0);
    private static final String RECOIL_OVERRIDE = System.getProperty("lab.boxing.releaseRecoil");
    private static final boolean COMMON_FRESH = Boolean.getBoolean("lab.boxing.releaseCommonFresh");
    private static final boolean CURRENT_METRICS = Boolean.getBoolean("lab.boxing.releaseCurrentMetrics");
    private static final double OWN_ATTACK_SHIFT = Double.parseDouble(System.getProperty("lab.boxing.releaseOwnAttackMs", "0"));
    private static final double OTHER_ATTACK_SHIFT = Double.parseDouble(System.getProperty("lab.boxing.releaseOtherAttackMs", "0"));
    private static final double ATTACK_SHIFT_START = Double.parseDouble(System.getProperty(
            "lab.boxing.releaseAttackStartMs", "-Infinity"));
    private static final double ATTACK_SHIFT_END = Double.parseDouble(System.getProperty(
            "lab.boxing.releaseAttackEndMs", "Infinity"));
    private static final boolean RECORD_APPROACH_VIEW = Boolean.getBoolean("lab.boxing.releaseApproachView");
    private static final boolean RECORD_REPLY_AUDIT = Boolean.getBoolean("lab.boxing.releaseReplyAudit");
    private static final String FOOTWORK_DELAY = System.getProperty("lab.boxing.reentryFootwork");
    private static final boolean INCLUDE_WTAP_FOOTWORK =
            "close-factorial".equals(System.getProperty("lab.boxing.reentryFootworkCue"));
    private static final String REPLY_SPACING = System.getProperty("lab.boxing.reentryReplySpacing");
    private static final String REPLY_SPACING_TRACE = System.getProperty("lab.boxing.reentryReplySpacingTrace");
    private static final boolean REPLY_ADMISSION = Boolean.getBoolean("lab.boxing.replyAdmission");

    record Delay(String label, int ms, boolean hurt, double lift) {
        Delay(String label, int ms, boolean hurt) { this(label, ms, hurt, 0); }

        boolean permits(double cut, double poll, double lastHurt, double feetY) {
            if (lift > 0) return poll >= cut + FALLBACK || lastHurt >= cut && feetY - 4 >= lift;
            return hurt ? poll >= cut + FALLBACK || lastHurt >= cut && poll >= lastHurt + ms : poll >= cut + ms;
        }
    }

    private static List<Delay> delays() {
        var choices = new ArrayList<Delay>();
        for (int ms = 0; ms <= 350; ms += 5) {
            choices.add(new Delay(ms == 0 ? "wtap" : "wait-" + ms, ms, false));
        }
        for (int ms = 0; ms <= 275; ms += 5) choices.add(new Delay("hurt-" + ms, ms, true));
        return choices;
    }

    private static List<Delay> selectDelays(String arms) {
        if (arms == null) return delays();
        var requested = Arrays.asList(arms.split(","));
        var choices = new ArrayList<>(delays());
        for (int height : new int[]{300, 600, 800}) {
            choices.add(new Delay("lift-" + height, 0, false, height / 1000.0));
        }
        var selected = choices.stream().filter(d -> d.label.equals("wtap") || requested.contains(d.label)).toList();
        if (selected.size() != requested.size() + 1) throw new IllegalArgumentException("Unknown or repeated delay " + arms);
        return selected;
    }

    static class Gate extends DuelEnv.Player {
        final Predicate<PeriodicComboClock.Observation> permits;
        boolean opened;

        Gate(DuelEnv.Player source, Predicate<PeriodicComboClock.Observation> permits) {
            super(source.style, source.mask, source.rtt, source.wake);
            handled = source.handled;
            releaseUntil = source.releaseUntil;
            firstClick = source.firstClick;
            this.permits = permits;
        }

        Gate(Gate source) {
            this(source, source.permits);
            opened = source.opened;
        }

        @Override public boolean allowInjection(PeriodicComboClock.Observation own, String source) {
            opened |= permits.test(own);
            return opened;
        }
    }

    private record Poll(double time, double lastHurt, boolean grounded, double height, double nearest) {}
    private record Opportunity(List<Poll> polls, Map<String, Object> cueView) {}

    private record LocalView(double time, List<Double> feet, List<Double> motion, List<Double> target, double nearest,
                             boolean grounded, boolean sprint, Double ownHurt, Double targetHurt, Double lastContact) {
        static LocalView capture(double time, boolean first, PeriodicComboFightModel.State state) {
            var body = first ? state.a : state.b;
            var target = first ? state.ab : state.ba;
            var q = body.q;
            return new LocalView(time, List.of(q.x(), q.y(), q.z()), List.of(q.vx(), q.vy(), q.vz()),
                    List.of(target.x, target.y, target.z), PeriodicComboFightModel.nearest(q, target),
                    q.onGround(), body.sprint, optional(body.lastHurtTime), optional(target.lastHurtTime),
                    optional(body.lastContactTime));
        }

        private static Double optional(double time) { return Double.isFinite(time) ? time : null; }

        Map<String, Object> values() {
            var row = new LinkedHashMap<String, Object>();
            row.put("time", time);
            row.put("feet", feet);
            row.put("motion", motion);
            row.put("target", target);
            row.put("nearest", nearest);
            row.put("grounded", grounded);
            row.put("sprint", sprint);
            row.put("ownHurt", ownHurt);
            row.put("targetHurt", targetHurt);
            row.put("lastContact", lastContact);
            return row;
        }
    }

    static final class Exchange {
        private static final boolean RECORD_DISPATCH = Boolean.getBoolean("lab.boxing.reentryDispatchTrace");
        private static final boolean RECORD_BOUNDARY = Boolean.getBoolean("lab.boxing.reentryBoundaryTrace");
        final List<List<Object>> events = new ArrayList<>();
        final List<List<Object>> ownHeads = new ArrayList<>();
        final List<List<Object>> dispatch = new ArrayList<>();
        final List<Double> drains = new ArrayList<>();
        final BoxingTradeSkills.Landings flight;
        final ReleaseBoundary boundary;

        Exchange(DuelEnv.Game game, double cut) {
            this(game, cut, null);
        }

        Exchange(DuelEnv.Game game, double cut, PeriodicComboClock.Audit observer) {
            flight = new BoxingTradeSkills.Landings(game.clock.state);
            boundary = RECORD_BOUNDARY ? new ReleaseBoundary(game, cut) : null;
            game.clock.setAudit((t, stage, s) -> {
                int before = game.hits.list.size();
                game.hits.event(t, stage, s);
                flight.event(t, stage, s);
                if (observer != null) observer.event(t, stage, s);
                if (boundary != null) boundary.stage(t, stage, before);
                if (RECORD_DISPATCH && stage.equals("SERVER_DRAIN")) drains.add(t - cut);
                for (int i = before; i < game.hits.list.size(); i++) {
                    var hit = game.hits.list.get(i);
                    events.add(List.of(t - cut, "accepted", hit.sideA() == game.first, hit.upgrade()));
                }
                if (stage.equals("SERVER_ENTITY")) {
                    if (s.ownResistant == s.freshThreshold) events.add(List.of(t - cut, "ready", !game.first));
                    if (s.resistant == s.freshThreshold) events.add(List.of(t - cut, "ready", game.first));
                }
                if (stage.equals(game.first ? "POLICYA" : "POLICYB")) {
                    var body = game.first ? s.a : s.b;
                    var view = game.first ? s.ab : s.ba;
                    double dx = view.x - body.q.x(), dz = view.z - body.q.z();
                    ownHeads.add(Arrays.asList(t - cut, Math.hypot(dx, dz), view.y - 4, body.q.y() - 4,
                            body.q.onGround(), body.sprint, body.q.vx(), body.q.vy(), body.q.vz(),
                            relative(body.lastHurtTime, cut), relative(view.lastHurtTime, cut),
                            relative(body.lastContactTime, cut)));
                }
            });
        }

        private static Double relative(double time, double cut) {
            return Double.isFinite(time) ? time - cut : null;
        }

        Map<String, Object> boundaryValues() { return boundary == null ? Map.of() : boundary.values(); }

        void click(DuelEnv.Game game, double cut, PeriodicComboClock.ClickEvent e) {
            if (!e.action().equals("CONSUME")) return;
            var s = game.clock.state;
            var body = e.fromA() ? s.a : s.b;
            var view = e.fromA() ? s.ab : s.ba;
            var target = e.fromA() ? s.b : s.a;
            double ray = PeriodicComboFightModel.ray(body, view);
            events.add(Arrays.asList(e.time() - cut, "click", e.fromA() == game.first, e.eligible(), e.contact(),
                    Double.isFinite(ray) ? ray : null, body.q.onGround(), body.q.y() - 4, target.q.y() - 4));
            if (boundary != null) boundary.click(e, ray);
            if (RECORD_DISPATCH && e.fromA() == game.first) {
                Double received = null;
                Double ready = null;
                if (e.contact()) {
                    var attack = s.packets.get(s.packets.size() - 1);
                    if (attack.kind != (e.fromA() ? 3 : 7) || attack.sequence != s.seq - 1)
                        throw new AssertionError("Expected the local attack at the queue tail");
                    received = attack.due - cut;
                    ready = attack.ready - cut;
                }
                dispatch.add(Arrays.asList(e.admittedNanos() / 1e6 - cut, e.time() - cut, e.eligible(), e.contact(),
                        Double.isFinite(ray) ? ray : null, received, ready, relative(body.lastHurtTime, cut)));
            }
        }
    }

    /** Link input, packet readiness, server acceptance and S12 application in a detailed replay. */
    private static final class ReleaseBoundary {
        private final DuelEnv.Game game;
        private final double cut;
        private final List<Map<String, Object>> swings = new ArrayList<>();
        private final List<Map<String, Object>> hits = new ArrayList<>();
        private final List<List<Object>> moves = new ArrayList<>();
        private final List<List<Object>> reports = new ArrayList<>();
        private final Map<Long, PeriodicComboFightModel.Packet> pendingReports = new LinkedHashMap<>();
        private final Map<Long, Map<String, Object>> pending = new LinkedHashMap<>();
        private final Map<Long, Map<String, Object>> recoil = new LinkedHashMap<>();
        private long sequence;

        ReleaseBoundary(DuelEnv.Game game, double cut) {
            this.game = game;
            this.cut = cut;
            sequence = game.clock.state.seq;
        }

        void click(PeriodicComboClock.ClickEvent event, double ray) {
            var state = game.clock.state;
            boolean a = event.fromA();
            var body = a ? state.a : state.b;
            var view = a ? state.ab : state.ba;
            var row = new LinkedHashMap<String, Object>();
            row.put("own", a == game.first);
            row.put("poll", event.admittedNanos() / 1e6 - cut);
            row.put("consume", event.time() - cut);
            row.put("eligible", event.eligible());
            row.put("ray", Double.isFinite(ray) ? ray : null);
            row.put("nearest", PeriodicComboFightModel.nearest(body.q, view));
            row.put("feet", List.of(body.q.x(), body.q.y(), body.q.z()));
            row.put("target", List.of(view.x, view.y, view.z));
            row.put("motionAfter", List.of(body.q.vx(), body.q.vy(), body.q.vz()));
            if (event.contact()) {
                var packet = state.packets.get(state.packets.size() - 1);
                if (packet.kind != (a ? 3 : 7) || packet.sequence != state.seq - 1) {
                    throw new AssertionError("Contact did not enqueue an attack at the tail");
                }
                row.put("packet", packet.sequence);
                row.put("wire", packet.due - cut);
                row.put("ready", packet.ready - cut);
                pending.put(packet.sequence, row);
            }
            swings.add(row);
        }

        void stage(double time, String stage, int before) {
            var state = game.clock.state;
            if (stage.equals("MOVEA") || stage.equals("MOVEB") || stage.equals("FLUSHA") || stage.equals("FLUSHB")) {
                for (var packet : state.packets) {
                    if (packet.kind == 0 || packet.kind == 1) pendingReports.putIfAbsent(packet.sequence, packet);
                }
            }
            if (stage.equals("SERVER_DRAIN")) {
                pendingReports.values().removeIf(packet -> {
                    if (packet.ready > time) return false;
                    reports.add(List.of((packet.kind == 0) == game.first, packet.due - cut, packet.ready - cut,
                            time - cut, packet.x, packet.y, packet.z, packet.flag));
                    return true;
                });
                pending.values().removeIf(row -> {
                    if ((double) row.get("ready") > time - cut) return false;
                    row.put("server", time - cut);
                    return true;
                });
                for (int i = before; i < game.hits.list.size(); i++) {
                    var hit = game.hits.list.get(i);
                    boolean a = hit.sideA();
                    var row = new LinkedHashMap<String, Object>();
                    row.put("server", time - cut);
                    row.put("own", a == game.first);
                    row.put("upgrade", hit.upgrade());
                    row.put("packet", hit.upgrade() ? (a ? state.lastAcceptedPacketA : state.lastAcceptedPacketB)
                            : (a ? state.lastFreshPacketA : state.lastFreshPacketB));
                    if (!hit.upgrade()) {
                        var emitted = state.packets.stream()
                                .filter(p -> p.sequence >= sequence && p.kind == (a ? 4 : 8)).toList();
                        if (emitted.size() == 1) {
                            var packet = emitted.get(0);
                            row.put("velocityPacket", packet.sequence);
                            row.put("velocity", List.of(packet.vx, packet.vy, packet.vz));
                            row.put("velocityReady", packet.ready - cut);
                            recoil.put(packet.sequence, row);
                        }
                    }
                    hits.add(row);
                }
            }
            if (stage.equals("CLIENT_DRAINA") || stage.equals("CLIENT_DRAINB")) {
                boolean subject = stage.endsWith("A") == game.first;
                recoil.entrySet().removeIf(row -> {
                    if (subject == (boolean) row.getValue().get("own")
                            || state.packets.stream().anyMatch(p -> p.sequence == row.getKey())) return false;
                    row.getValue().put("velocityApplied", time - cut);
                    return true;
                });
            }
            if (stage.equals("MOVEA") || stage.equals("MOVEB")) {
                boolean a = stage.endsWith("A");
                var body = a ? state.a : state.b;
                var q = body.q;
                var control = game.clock.appliedControl(a);
                moves.add(List.of(time - cut, a == game.first, q.x(), q.y(), q.z(),
                        q.vx(), q.vy(), q.vz(), q.onGround(), body.sprint, control.key(), control.strafe()));
            }
            sequence = state.seq;
        }

        Map<String, Object> values() {
            return Map.of("swings", swings, "hits", hits, "moves", moves, "reports", reports);
        }
    }

    private static DuelEnv.Game fork(DuelEnv env, DuelEnv.Game prefix, DuelEnv.Player selector) {
        boolean first = prefix.first;
        var game = env.new Game(prefix, first ? selector : prefix.a.copy(), first ? prefix.b.copy() : selector);
        game.trace();
        return game;
    }

    private static Opportunity availablePolls(DuelEnv env, DuelEnv.Game phase, double cut) {
        var polls = new ArrayList<Poll>();
        var cue = new LinkedHashMap<String, Object>();
        var masked = new Gate(phase.our(), own -> {
            if (polls.isEmpty() || polls.get(polls.size() - 1).time != own.time()) {
                polls.add(new Poll(own.time(), own.lastOwnHurtTime(), own.own().onGround(),
                        own.own().y(), own.nearest()));
            }
            if (RECORD_REPLY_AUDIT && own.lastOwnHurtTime() >= cut && cue.isEmpty()) {
                var q = own.own();
                cue.put("time", own.time());
                cue.put("hurt", own.lastOwnHurtTime());
                cue.put("targetHurt", Double.isFinite(own.lastTargetHurtTime()) ? own.lastTargetHurtTime() : null);
                cue.put("nearest", own.nearest());
                cue.put("feet", List.of(q.x(), q.y(), q.z()));
                cue.put("target", List.of(own.targetX(), own.targetY(), own.targetZ()));
                cue.put("motion", List.of(q.vx(), q.vy(), q.vz()));
                cue.put("grounded", q.onGround());
                cue.put("sprint", own.sprint());
            }
            return false;
        });
        var game = fork(env, phase, masked);
        game.clock.runUntil(cut + 750);
        if (polls.isEmpty() || polls.get(polls.size() - 1).time < cut + FALLBACK) game.clock.runUntil(cut + WINDOW);
        if (polls.isEmpty() || polls.get(polls.size() - 1).time < cut + FALLBACK) {
            throw new IllegalStateException("No capped click opportunity after the release deadline");
        }
        return new Opportunity(polls, cue);
    }

    private static void verifyWtap(DuelEnv.Game unchanged, List<DuelEnv.Hit> expected) {
        if (expected != null && !unchanged.hits.list.equals(expected)) {
            throw new AssertionError("Unmodified cut failed to reproduce the W-tap parent");
        }
    }

    private static Map<String, Object> score(DuelEnv.Game game, double cut, Poll poll) {
        boolean ourSide = game.first;
        var hits = game.hits.list;
        var counter = BoxingOpeningRecoil.score(hits, ourSide);
        int own = 0, opponent = 0, ownFresh = 0, opponentFresh = 0;
        for (var hit : hits) {
            if (hit.sideA() == ourSide) {
                own++;
                if (!hit.upgrade()) ownFresh++;
            } else {
                opponent++;
                if (!hit.upgrade()) opponentFresh++;
            }
        }
        var result = new LinkedHashMap<String, Object>(Map.of("score", List.of(own, opponent), "fresh", List.of(ownFresh, opponentFresh),
                "counter", counter.values(), "chains", List.of(BoxingReentryTiming.openingChain(hits, ourSide),
                        BoxingReentryTiming.openingChain(hits, !ourSide)),
                "release", List.of(poll.time - cut, poll.grounded, poll.height, poll.nearest)));
        if (CURRENT_METRICS) {
            double activeGap = 100.0 * game.clock.config.hitTicks + 100;
            result.put("strictChains850", List.of(BoxingReentryTiming.openingChain(hits, ourSide, 850),
                    BoxingReentryTiming.openingChain(hits, !ourSide, 850)));
            result.put("activeChains", List.of(BoxingReentryTiming.openingChain(hits, ourSide, activeGap),
                    BoxingReentryTiming.openingChain(hits, !ourSide, activeGap)));
            var firstFresh = hits.stream().filter(h -> !h.upgrade()).findFirst();
            result.put("firstFresh", firstFresh.map(h -> List.of(h.at() - cut, h.sideA() == ourSide)).orElse(List.of()));
        }
        return result;
    }

    private static Map<String, Object> phase(DuelEnv env, DuelEnv.Game prefix, double cut, int ownMs,
                                             int otherMs, List<Delay> delays, List<DuelEnv.Hit> expected,
                                             boolean detailed) {
        var phased = fork(env, prefix, prefix.our().copy());
        if (FRESH_OVERRIDE != 0) {
            phased.clock.config.hitTicks = FRESH_OVERRIDE;
            phased.clock.state.freshThreshold = FRESH_OVERRIDE;
        }
        if (RECOIL_OVERRIDE != null) {
            phased.clock.config.recordedMmc = switch (RECOIL_OVERRIDE) {
                case "recorded" -> true;
                case "provisional" -> false;
                default -> throw new IllegalArgumentException("Unknown recoil law " + RECOIL_OVERRIDE);
            };
        }
        if (ownMs != 0) phased.clock.shiftFutureClientOpportunities(prefix.first, ownMs);
        if (otherMs != 0) phased.clock.shiftFutureClientOpportunities(!prefix.first, otherMs);
        BoxingJumpTimingAudit.shiftAttackTiming(phased.clock.state,
                prefix.first ? OWN_ATTACK_SHIFT : OTHER_ATTACK_SHIFT,
                prefix.first ? OTHER_ATTACK_SHIFT : OWN_ATTACK_SHIFT, cut + ATTACK_SHIFT_START, cut + ATTACK_SHIFT_END);
        var available = availablePolls(env, phased, cut);
        var polls = available.polls();
        var branches = new LinkedHashMap<Integer, Map<String, Object>>();
        var deadlines = new ArrayList<Integer>();
        for (var delay : delays) {
            int i = 0;
            while (!delay.permits(cut, polls.get(i).time, polls.get(i).lastHurt, polls.get(i).height)) i++;
            if (!branches.containsKey(i)) {
                var poll = polls.get(i);
                var selector = delay.label.equals("wtap") ? phased.our().copy()
                        : new Gate(phased.our(), own -> delay.permits(cut, own.time(), own.lastOwnHurtTime(), own.own().y()));
                var branch = fork(env, phased, selector);
                var evidence = RECORD_REPLY_AUDIT ? new BoxingReplyEvidence(branch, cut) : null;
                PeriodicComboClock.Audit observer = evidence == null ? null : evidence::event;
                var exchange = detailed ? new Exchange(branch, cut, observer) : null;
                if (exchange == null && observer != null) branch.clock.setAudit(observer);
                var actual = new double[]{Double.NaN};
                branch.clock.setClickAudit(event -> {
                    if (event.fromA() == prefix.first && event.action().equals("ADMIT")
                            && Double.isNaN(actual[0])) actual[0] = event.time();
                    if (exchange != null) exchange.click(branch, cut, event);
                    if (evidence != null) evidence.click(event);
                });
                branch.clock.runUntil(cut + WINDOW);
                if (Math.abs(actual[0] - poll.time) > .000001 || !Double.isFinite(actual[0])) {
                    throw new AssertionError("Gated replay did not admit at its selected physical poll");
                }
                if (delay.label.equals("wtap")) verifyWtap(branch, expected);
                var result = new LinkedHashMap<>(score(branch, cut, poll));
                if (exchange != null) {
                    result.put("events", exchange.events);
                    result.put("flight", exchange.flight.values(prefix.first));
                    result.put("ownHeads", exchange.ownHeads);
                    if (exchange.boundary != null) result.put("boundary", exchange.boundary.values());
                }
                if (evidence != null) result.put("replyEvidence", evidence.values());
                branches.put(i, result);
            }
            deadlines.add(i);
        }
        var result = new LinkedHashMap<String, Object>(Map.of("phaseMs", List.of(ownMs, otherMs),
                "atPoll", deadlines, "outcomes", branches));
        if (RECORD_REPLY_AUDIT) result.put("firstHurtPoll", available.cueView());
        if ("all".equals(FOOTWORK_DELAY)) {
            var footwork = new LinkedHashMap<String, Object>();
            for (var delay : delays) {
                if (!delay.label.equals("wtap") || INCLUDE_WTAP_FOOTWORK)
                    footwork.put(delay.label, BoxingReentryFootwork.measure(env, phased, cut, delay));
            }
            result.put("footworkByRelease", footwork);
        } else if (FOOTWORK_DELAY != null) {
            var delay = delays.stream().filter(d -> d.label.equals(FOOTWORK_DELAY)).findFirst().orElseThrow();
            result.put("footwork", BoxingReentryFootwork.measure(env, phased, cut, delay));
        }
        if (REPLY_ADMISSION) result.put("replyAdmission", BoxingReplyAdmission.measure(env, phased, cut));
        if (REPLY_SPACING != null) {
            var delay = delays.stream().filter(d -> d.label.equals(REPLY_SPACING)).findFirst().orElseThrow();
            if (REPLY_SPACING_TRACE == null) {
                result.put("replySpacing", BoxingReplySpacing.measure(env, phased, cut, cut + WINDOW, delay));
            } else {
                result.put("replySpacingTrace", BoxingReplySpacing.trace(env, phased, cut, cut + WINDOW, delay, REPLY_SPACING_TRACE));
            }
        }
        return result;
    }

    private static DuelEnv.Game mirror(DuelEnv env, boolean first) {
        var selector = REPLY_ADMISSION ? new BoxingReplyAdmission.History(env.rtt) : new DuelEnv.Player("wtap", 0, env.rtt, 0);
        var game = env.new Game(first, selector,
                new DuelEnv.Player("wtap", 0, env.otherRtt, 0));
        game.trace();
        return game;
    }

    private static void run(DuelEnv env, boolean first, int cps, double witness, String arm) {
        var parent = mirror(env, first);
        double enrollUntil = Math.min(env.limit - WINDOW, ENROLL_UNTIL);
        parent.clock.runUntil(enrollUntil + WINDOW);
        var prefix = mirror(env, first);
        var history = new ArrayDeque<LocalView>();
        if (RECORD_APPROACH_VIEW) {
            String subjectInput = first ? "POLICYA" : "POLICYB";
            prefix.clock.setAudit((time, event, state) -> {
                prefix.hits.event(time, event, state);
                if (event.equals(subjectInput)) {
                    if (history.size() == 4) history.removeFirst();
                    history.addLast(LocalView.capture(time, first, state));
                }
            });
        }
        double previous = Double.NEGATIVE_INFINITY;
        int previousHits = -1;
        boolean detailed = Double.isFinite(witness);
        var delays = selectDelays(arm);
        boolean trace = detailed && Boolean.parseBoolean(System.getProperty("lab.boxing.releaseTrace", "true"));
        while (prefix.clock.advanceToBeforeNextHead(first, enrollUntil)) {
            double cut = prefix.clock.time();
            int count = prefix.hits.list.size();
            var s = prefix.clock.state;
            var own = first ? s.a : s.b;
            var other = first ? s.b : s.a;
            var view = first ? s.ab : s.ba;
            var reverse = first ? s.ba : s.ab;
            double nearest = PeriodicComboFightModel.nearest(own.q, view);
            double dx = view.x - own.q.x(), dz = view.z - own.q.z();
            double toward = (own.q.vx() * dx + own.q.vz() * dz) / Math.hypot(dx, dz);
            int threshold = COMMON_FRESH ? Math.min(9, s.freshThreshold) : s.freshThreshold;
            boolean approach = count != previousHits && cut - previous >= 750 && own.q.onGround() && other.q.onGround()
                    && s.ownResistant <= threshold && s.resistant <= threshold
                    && nearest > 3.05 && nearest <= 3.85 && toward > 0;
            if (approach) {
                previous = cut;
                previousHits = count;
            }
            if (approach && (!detailed || cut == witness)) {
                var expected = parent.hits.list.subList(count, parent.hits.list.size()).stream()
                        .takeWhile(h -> h.at() <= cut + WINDOW).toList();
                var comparison = new ArrayList<Map<String, Object>>();
                boolean originalPhysics = (FRESH_OVERRIDE == 0 || FRESH_OVERRIDE == prefix.clock.config.hitTicks)
                        && (RECOIL_OVERRIDE == null || prefix.clock.config.recordedMmc == RECOIL_OVERRIDE.equals("recorded"));
                for (int ownMs : PHASES) for (int otherMs : PHASES) {
                    comparison.add(phase(env, prefix, cut, ownMs, otherMs, delays,
                            ownMs == 0 && otherMs == 0 && originalPhysics
                                    && OWN_ATTACK_SHIFT == 0 && OTHER_ATTACK_SHIFT == 0 ? expected : null, trace));
                }
                var row = new LinkedHashMap<>(Map.ofEntries(
                        Map.entry("clock", env.clockId), Map.entry("rtt", env.rtt), Map.entry("cps", cps),
                        Map.entry("seat", first ? "a" : "b"), Map.entry("cutMs", cut), Map.entry("priorAccepted", count),
                        Map.entry("ownNearest", nearest), Map.entry("otherNearest", PeriodicComboFightModel.nearest(other.q, reverse)),
                        Map.entry("ownToward", toward), Map.entry("queued", List.of(prefix.clock.queuedClicks(first),
                                prefix.clock.queuedClicks(!first))), Map.entry("wtapIndex", 0),
                        Map.entry("attackShiftMs", List.of(OWN_ATTACK_SHIFT, OTHER_ATTACK_SHIFT)),
                        Map.entry("arms", delays.stream().map(Delay::label).toList()), Map.entry("comparisons", comparison)));
                if (Double.isFinite(ATTACK_SHIFT_START) || Double.isFinite(ATTACK_SHIFT_END)) {
                    row.put("attackShiftInterval", Arrays.asList(
                            Double.isFinite(ATTACK_SHIFT_START) ? ATTACK_SHIFT_START : null,
                            Double.isFinite(ATTACK_SHIFT_END) ? ATTACK_SHIFT_END : null));
                }
                if (REPLY_SPACING != null) row.put("replySpacingArms", BoxingReplySpacing.labels());
                if (RECORD_APPROACH_VIEW) {
                    row.put("approachView", history.stream().map(LocalView::values).toList());
                    row.put("currentView", LocalView.capture(cut, first, s).values());
                    if (prefix.our() instanceof BoxingReplyAdmission.History phaseHistory) {
                        row.put("approachPhase", phaseHistory.approachPhase(cut));
                    }
                }
                System.out.println(Json.encode(row));
                if (detailed) return;
            }
            if (!prefix.clock.runHeadInterval(first, enrollUntil)) break;
        }
        if (detailed) throw new IllegalArgumentException("Witness is absent from " + env.clockId + " at " + witness);
    }

    public static void main(String[] args) throws Exception {
        int rtt = Integer.parseInt(args[0]);
        int cps = Integer.parseInt(args[1]);
        int duration = Integer.parseInt(args[2]);
        var wires = Path.of(args[3]);
        var seeds = Path.of(args[4]);
        double witness = Double.parseDouble(System.getProperty("lab.boxing.releaseCutMs", "NaN"));
        String arm = System.getProperty("lab.boxing.releaseArm");
        String seat = System.getProperty("lab.boxing.releaseSeat");
        String profile = System.getProperty("lab.boxing.profile", "mmc");
        if (Double.isFinite(witness) && (!"a".equals(seat) && !"b".equals(seat) || args.length != 6 || arm == null)) {
            throw new IllegalArgumentException("A witness needs one clock and an explicit release seat");
        }
        for (int i = 5; i < args.length; i++) {
            var env = BoxingDoubleSearch.environment(Path.of(args[i]), profile, rtt, cps, duration);
            BoxingJumpTimingAudit.wireTiming(env, wires.resolve(env.clockId + ".txt"));
            BoxingJumpTimingAudit.seedTracker(env, seeds.resolve(env.clockId + ".txt"));
            if (!"b".equals(seat)) run(env, true, cps, witness, arm);
            if (!"a".equals(seat)) run(env, false, cps, witness, arm);
        }
    }
}
