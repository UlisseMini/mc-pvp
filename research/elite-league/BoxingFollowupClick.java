import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import lab.Json;

/** Compare one deferred physical press at the next fresh wait after a trade. */
final class BoxingFollowupClick {
    private static final double HORIZON = 1350;
    private static final double LIMIT = 23_000;
    private static final double TICK_MS = 50;
    private static final long NO_PRESS = Long.MIN_VALUE;

    record Timing(double phase, double transport, double frame, double nativeTick) {
        double firstFresh(double hurt, double replica, int rtt) {
            double receiptEstimate = hurt - rtt / 2.0 + 500;
            double phaseEstimate = replica - rtt / 2.0 + phase;
            return phaseEstimate + Math.rint((receiptEstimate - phaseEstimate) / TICK_MS) * TICK_MS;
        }

        double ready(double now, double head, String source) {
            return (source.equals("native_tick") ? now + nativeTick : Math.max(now, head + TICK_MS) + frame)
                    + transport;
        }
    }

    enum Rule { STOCK, FORECAST, GUARDED, EXACT }

    private static final class Fighter extends BoxingTradeFollowup.Scout {
        final Timing timing;
        final Rule rule;
        final long exactPress;
        double head = Double.NEGATIVE_INFINITY;
        double targetStatus = Double.NEGATIVE_INFINITY;
        double replica = Double.NEGATIVE_INFINITY;
        double initialStatus = Double.NaN;
        double heldAt = -1;
        long heldTick;
        boolean heldBraking;
        int protectedCount;

        Fighter(int rtt, Timing timing) {
            this(rtt, timing, Rule.STOCK, NO_PRESS);
        }

        Fighter(Fighter source, Rule rule, long exactPress) {
            this(source.rtt, source.timing, rule, exactPress);
            handled = source.handled;
            releaseUntil = source.releaseUntil;
            firstClick = source.firstClick;
            head = source.head;
            targetStatus = source.targetStatus;
            initialStatus = targetStatus;
            replica = source.replica;
        }

        private Fighter(int rtt, Timing timing, Rule rule, long exactPress) {
            super(rtt);
            this.timing = timing;
            this.rule = rule;
            this.exactPress = exactPress;
        }

        @Override public PeriodicComboClock.Control input(PeriodicComboClock.Observation o) {
            head = o.time();
            return super.input(o);
        }

        @Override public void received(PeriodicComboClock.PacketObservation p) {
            if (p.kind() == PeriodicComboClock.ReceivedKind.TARGET_POSITION) replica = p.arrivedAt();
            if (p.kind() == PeriodicComboClock.ReceivedKind.TARGET_HURT) targetStatus = p.arrivedAt();
            super.received(p);
        }

        @Override public boolean allowInjection(PeriodicComboClock.Observation o, String source,
                                                BooleanSupplier contactReady) {
            if (rule == Rule.STOCK) return true;
            if (heldAt >= 0) return o.tick() > heldTick;
            boolean defer;
            if (rule == Rule.EXACT) {
                defer = Math.round(o.time() * 1e6) == exactPress;
            } else {
                if (targetStatus != initialStatus || o.time() - replica > 250 || !contactReady.getAsBoolean())
                    return true;
                double first = timing.firstFresh(targetStatus, replica, rtt);
                double ready = timing.ready(o.time(), head, source);
                double margin = rule == Rule.GUARDED ? 6 : 0;
                defer = first - 100 + margin < ready && ready <= first - 50 - margin;
            }
            if (!defer) return true;
            double dx = o.targetX() - o.own().x(), dz = o.targetZ() - o.own().z();
            double radial = (dx * o.own().vx() + dz * o.own().vz()) / Math.max(.001, Math.hypot(dx, dz));
            boolean brakesRecoil = o.sprint() && radial < -.05;
            if (rule == Rule.GUARDED && brakesRecoil) {
                protectedCount++;
                return true;
            }
            heldAt = o.time();
            heldTick = o.tick();
            heldBraking = brakesRecoil;
            return false;
        }
    }

    record Press(long admission, double consumed, boolean eligible, double ready, double range) {
        List<Object> relative(double cut) {
            return List.of(admission / 1e6 - cut, consumed - cut, eligible, ready < 0 ? -1 : ready - cut, range);
        }
    }

    record Drain(int tick, double time) {}
    record Run(Fighter fighter, List<DuelEnv.Hit> hits, List<List<Double>> presses,
               List<Press> consumed, List<Drain> drains) {
        double drain(int tick) {
            return drains.stream().filter(d -> d.tick() == tick).mapToDouble(Drain::time).findFirst().orElseThrow();
        }

        List<Press> arrivingAfterThrough(int startTick, int endTick) {
            double start = drain(startTick), end = drain(endTick);
            return consumed.stream().filter(c -> c.ready() > start && c.ready() <= end).toList();
        }

        Map<String,Object> score(boolean first, double cut, int window) {
            int own = 0, other = 0;
            double follow = -1, returned = -1;
            for (DuelEnv.Hit h : hits) {
                if (h.sideA() == first) {
                    own++;
                    if (!h.upgrade() && follow < 0) follow = h.at() - cut;
                } else {
                    other++;
                    if (returned < 0) returned = h.at() - cut;
                }
            }
            var result = new LinkedHashMap<String,Object>();
            result.put("points", List.of(own, other));
            result.put("presses", presses.stream().map(List::size).toList());
            result.put("follow", follow);
            result.put("return", returned);
            result.put("clean", follow >= 0 && follow <= 850 && (returned < 0 || follow < returned));
            result.put("held", fighter.heldAt < 0 ? -1 : fighter.heldAt - cut);
            result.put("held_braking", fighter.heldBraking);
            result.put("protected", fighter.protectedCount);
            result.put("rescheduled", presses.get(0).stream().filter(p -> fighter.heldAt >= 0 && p >= fighter.heldAt)
                    .mapToDouble(p -> p - fighter.heldAt).findFirst().orElse(-1));
            result.put("first_eligible_packets", arrivingAfterThrough(window - 1, window + 1).stream()
                    .map(p -> p.relative(cut)).toList());
            result.put("first_replacement", consumed.stream().filter(p -> fighter.heldAt >= 0 &&
                            p.admission() / 1e6 >= fighter.heldAt).map(p -> p.relative(cut)).findFirst().orElse(List.of()));
            result.put("local_attempts", consumed.stream().map(p -> p.relative(cut)).toList());
            result.put("own_fresh", hits.stream().filter(h -> h.sideA() == first && !h.upgrade())
                    .map(h -> h.at() - cut).toList());
            result.put("hit_ledger", hits.stream().map(h -> List.of(h.at() - cut, h.sideA() == first, h.upgrade())).toList());
            return result;
        }
    }

    private static Run fork(DuelEnv env, DuelEnv.Game prefix, Fighter scout, Rule rule, long exactPress,
                            int eligible) {
        boolean first = prefix.first;
        var actor = new Fighter(scout, rule, exactPress);
        var g = env.new Game(prefix, first ? actor : prefix.a.copy(), first ? prefix.b.copy() : actor);
        g.trace();
        var consumed = new ArrayList<Press>();
        var presses = List.of(new ArrayList<Double>(), new ArrayList<Double>());
        var drains = new ArrayList<Drain>();
        double start = prefix.clock.time();
        g.clock.setClickAudit(e -> {
            boolean own = e.fromA() == first;
            if (e.action().equals("ADMIT")) {
                var list = presses.get(own ? 0 : 1);
                if (!list.isEmpty() && e.time() - list.get(list.size() - 1) < 49.999999)
                    throw new IllegalStateException("20 CPS cap exceeded");
                list.add(e.time());
            }
            if (!own || !e.action().equals("CONSUME") || e.admittedNanos() / 1e6 < start)
                return;
            double ready = -1;
            if (e.contact()) {
                var p = g.clock.state.packets.get(g.clock.state.packets.size() - 1);
                if (p.kind != (first ? 3 : 7)) throw new IllegalStateException("Missing C02");
                ready = p.ready;
            }
            consumed.add(new Press(e.admittedNanos(), e.time(), e.eligible(), ready,
                    g.clock.observation(first).nearest()));
        });
        g.clock.setAudit((at, stage, state) -> {
            g.hits.event(at, stage, state);
            if (stage.equals("SERVER_DRAIN") && state.serverTicks >= eligible - 2 && state.serverTicks <= eligible + 1)
                drains.add(new Drain(state.serverTicks, at));
        });
        g.clock.runUntil(Math.nextDown(start + HORIZON));
        return new Run(actor, List.copyOf(g.hits.list), presses.stream().map(List::copyOf).toList(), consumed, drains);
    }

    private static Map<String,Object> play(DuelEnv env, boolean first, int ours, int other, Timing timing) {
        var scout = new Fighter(env.rtt, timing);
        var g = env.new Game(first, scout, Evolve.WT.create(env.otherRtt, 0));
        if (ours != 0) g.clock.shiftFutureClientOpportunities(first, ours);
        if (other != 0) g.clock.shiftFutureClientOpportunities(!first, other);
        long[] freshOrder = {0};
        int[] freshTick = {-1};
        g.clock.setAudit((at, stage, state) -> {
            if (!stage.equals("SERVER_DRAIN")) return;
            long order = first ? state.lastFreshOrderA : state.lastFreshOrderB;
            if (freshOrder[0] != order) {
                freshOrder[0] = order;
                freshTick[0] = state.serverTicks;
            }
        });
        var cuts = new ArrayList<Map<String,Object>>();
        if (g.clock.advanceToBeforeNextHead(first, LIMIT)) do {
            var o = g.clock.observation(first);
            int previous = scout.episode;
            scout.observe(o);
            if (scout.episode > 4) break;
            if (scout.episode == previous) continue;
            int eligible = freshTick[0] + g.clock.config.hitTicks;
            var baseline = fork(env, g, scout, Rule.STOCK, NO_PRESS, eligible);
            var opportunity = baseline.arrivingAfterThrough(eligible - 2, eligible - 1);
            long exactPress = opportunity.isEmpty() ? NO_PRESS : opportunity.get(0).admission();
            var arms = new LinkedHashMap<String,Object>();
            arms.put("wtap", baseline.score(first, o.time(), eligible));
            for (Rule rule : List.of(Rule.FORECAST, Rule.GUARDED, Rule.EXACT)) {
                var branch = fork(env, g, scout, rule, exactPress, eligible);
                if (branch.fighter.heldAt < 0 &&
                        (!branch.hits.equals(baseline.hits) || !branch.presses.equals(baseline.presses)))
                    throw new IllegalStateException("Unchanged press rule failed baseline replay");
                arms.put(rule.name().toLowerCase(java.util.Locale.ROOT), branch.score(first, o.time(), eligible));
            }
            cuts.add(Map.of("cut", o.time(), "episode", scout.episode, "arms", arms,
                    "exact_opportunity", !opportunity.isEmpty(), "incoming_first", scout.incomingFirst,
                    "first_fresh", baseline.drain(eligible) - o.time(),
                    "last_pre_fresh", baseline.drain(eligible - 1) - o.time(),
                    "second_fresh", baseline.drain(eligible + 1) - o.time()));
        } while (g.clock.runHeadInterval(first, LIMIT));
        return Map.of("clock", env.clockId, "seat", first ? "a" : "b", "phase", List.of(ours, other),
                "rtt", env.rtt, "cuts", cuts);
    }

    public static void main(String[] args) throws Exception {
        int rtt = Integer.parseInt(args[0]), ours = Integer.parseInt(args[1]), other = Integer.parseInt(args[2]);
        var wire = Path.of(args[3]);
        var seeds = Path.of(args[4]);
        var timing = new Timing(Double.parseDouble(args[5]), Double.parseDouble(args[6]),
                                Double.parseDouble(args[7]), Double.parseDouble(args[8]));
        for (int i = 9; i < args.length; i++) {
            var env = BoxingEdgeHitSelect.environment(Path.of(args[i]), rtt, 180_000, wire, seeds);
            for (boolean first : new boolean[]{true, false}) System.out.println(Json.encode(play(env, first, ours, other, timing)));
        }
    }
}
