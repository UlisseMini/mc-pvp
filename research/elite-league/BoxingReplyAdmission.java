import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

/** Diagnostic first-click gates built only from the selector's delivered observations. */
final class BoxingReplyAdmission {
    private static final double PERIOD_MS = 50;
    // Frozen on four older native 80 ms mirrors. See native-admission-summary.json.
    private static final double REPLICA_PHASE_MS = -43.5;
    private static final double ATTACK_LEG_MS = 40.890459;
    private static final double NATIVE_CONSUME_MS = .716;
    private static final double FRAME_CONSUME_MS = -.010708;

    private record Condition(String name, double guard, double maxWait, boolean contact) {}
    private static final List<Condition> CONDITIONS = List.of(
            new Condition("immediate", 0, 0, false),
            new Condition("guard-3-20", 3, 20, false),
            new Condition("guard-6-20", 6, 20, false),
            new Condition("guard-6-50", 6, 50, false),
            new Condition("contact-20", 0, 20, true));

    static final class History extends DuelEnv.Player {
        double lastHead = Double.NEGATIVE_INFINITY;
        double lastReplica = Double.NEGATIVE_INFINITY;
        double previousReplica = Double.NEGATIVE_INFINITY;

        History(int ping) { super("wtap", 0, ping, 0); }

        @Override History copy() {
            var clone = new History(rtt);
            clone.handled = handled;
            clone.releaseUntil = releaseUntil;
            clone.firstClick = firstClick;
            clone.lastHead = lastHead;
            clone.lastReplica = lastReplica;
            clone.previousReplica = previousReplica;
            return clone;
        }

        @Override public PeriodicComboClock.Control input(PeriodicComboClock.Observation own) {
            lastHead = own.time();
            return super.input(own);
        }

        @Override public void received(PeriodicComboClock.PacketObservation packet) {
            if (isReplica(packet)) {
                previousReplica = lastReplica;
                lastReplica = packet.arrivedAt();
            }
        }

        Map<String, Object> approachPhase(double headTime) {
            var view = new LinkedHashMap<String, Object>();
            Double replicaAge = Double.isFinite(lastReplica) ? headTime - lastReplica : null;
            view.put("replicaAge", replicaAge);
            view.put("priorReplicaAge", Double.isFinite(previousReplica) ? headTime - previousReplica : null);
            view.put("priorHeadAge", Double.isFinite(lastHead) ? headTime - lastHead : null);
            view.put("arrivalPhase", replicaAge != null && replicaAge <= 250
                    ? arrivalPhaseMs(headTime + NATIVE_CONSUME_MS, lastReplica) : null);
            return view;
        }
    }

    private static double arrivalPhaseMs(double localConsume, double lastReplica) {
        double elapsed = localConsume + ATTACK_LEG_MS - (lastReplica + REPLICA_PHASE_MS);
        return elapsed - PERIOD_MS * Math.floor(elapsed / PERIOD_MS);
    }

    private static boolean isReplica(PeriodicComboClock.PacketObservation packet) {
        return packet.kind() == PeriodicComboClock.ReceivedKind.TARGET_POSITION
                && (packet.packetId() == 0x15 || packet.packetId() == 0x17 || packet.packetId() == 0x18);
    }

    private static final class Gate extends BoxingReentryFootwork.Movement {
        final Condition condition;
        double lastHead, lastReplica;
        double earliest = Double.NaN, admitted = Double.NaN, beginHurt = Double.NaN;
        int deferred, noPhase;
        Double firstMargin;

        Gate(History source, double cut, int word, Condition condition) {
            super(new BoxingReentryRelease.Gate(source,
                    own -> own.lastOwnHurtTime() >= cut || own.time() >= cut + 600), word);
            this.condition = condition;
            this.lastHead = source.lastHead;
            this.lastReplica = source.lastReplica;
        }

        @Override public PeriodicComboClock.Control input(PeriodicComboClock.Observation own) {
            lastHead = own.time();
            return super.input(own);
        }

        @Override public void received(PeriodicComboClock.PacketObservation packet) {
            if (isReplica(packet)) lastReplica = packet.arrivedAt();
        }

        private Double clearance(double now, String source) {
            if (!Double.isFinite(lastReplica) || now - lastReplica > 250) return null;
            boolean nativePoll = source.equals("native_tick");
            if (!nativePoll && !Double.isFinite(lastHead)) return null;
            double localConsume = nativePoll ? now + NATIVE_CONSUME_MS
                    : Math.max(now, Math.max(now, lastHead + PERIOD_MS) + FRAME_CONSUME_MS);
            double after = arrivalPhaseMs(localConsume, lastReplica);
            return Math.min(after, PERIOD_MS - after);
        }

        @Override public boolean allowInjection(PeriodicComboClock.Observation own, String source, BooleanSupplier ready) {
            if (!super.allowInjection(own, source)) return false;
            if (Double.isFinite(admitted)) return true;
            double now = own.time();
            Double margin = clearance(now, source);
            if (Double.isNaN(earliest)) {
                earliest = now;
                firstMargin = margin;
                beginHurt = own.lastOwnHurtTime();
            }
            if (margin == null) noPhase++;
            if (now - earliest >= condition.maxWait) return true;
            boolean allow = condition.contact ? ready.getAsBoolean() : margin == null || margin > condition.guard;
            if (!allow) deferred++;
            return allow;
        }

        @Override public boolean injected(PeriodicComboClock.Observation own, double at) {
            if (Double.isNaN(admitted)) admitted = at;
            return super.injected(own, at);
        }
    }

    static Map<String, Object> measure(DuelEnv env, DuelEnv.Game prefix, double cut) {
        if (env.rtt != 80 || env.otherRtt != 80) throw new IllegalArgumentException("Forecast calibrated for 80 ms");
        var source = (History) prefix.our();
        var output = new LinkedHashMap<String, Object>();
        for (int word : new int[]{0, 179}) {
            var arms = new LinkedHashMap<String, Object>();
            for (Condition condition : CONDITIONS) {
                var candidate = new Gate(source, cut, word, condition);
                var game = env.new Game(prefix, prefix.first ? candidate : prefix.a.copy(),
                        prefix.first ? prefix.b.copy() : candidate);
                game.trace();
                int[] presses = new int[2];
                var dispatch = new ArrayList<List<Object>>();
                game.clock.setClickAudit(event -> {
                    if (event.action().equals("ADMIT")) presses[event.fromA() == game.first ? 0 : 1]++;
                    if (event.action().equals("CONSUME") && event.fromA() == game.first)
                        dispatch.add(List.of(event.time() - cut, event.admittedNanos() / 1e6 - cut,
                                event.contact(), event.eligible()));
                });
                game.clock.runUntil(cut + 2500);
                var reply = BoxingOpeningRecoil.score(game.hits.list, game.first);
                var row = new LinkedHashMap<String, Object>(reply.values());
                row.put("openingChain", BoxingReentryTiming.openingChain(game.hits.list, game.first));
                row.put("opponentChain", BoxingReentryTiming.openingChain(game.hits.list, !game.first));
                row.put("freshMargin", game.hits.list.stream().filter(h -> !h.upgrade())
                        .mapToInt(h -> h.sideA() == game.first ? 1 : -1).sum());
                row.put("firstMargin", candidate.firstMargin);
                row.put("firstHurt", relative(candidate.beginHurt, cut));
                row.put("earliest", relative(candidate.earliest, cut));
                row.put("admitted", relative(candidate.admitted, cut));
                row.put("deferred", candidate.deferred);
                row.put("noPhase", candidate.noPhase);
                row.put("presses", presses);
                row.put("localConsumes", dispatch);
                arms.put(condition.name, row);
            }
            output.put(Integer.toString(word), arms);
        }
        return output;
    }

    private static Double relative(double time, double cut) { return Double.isFinite(time) ? time - cut : null; }
}
