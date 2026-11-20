import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lab.Json;

/** Isolate a newly available return and test ordinary movement that could deny it. */
final class BoxingRecoilReturn {
    private static final int HORIZON = 2200;
    private static final int SPRINT = PeriodicComboFightModel.S;
    private static final int RELEASE = PeriodicComboFightModel.R;
    private static final int BACK = PeriodicComboFightModel.BACK;
    private static final boolean LONG = Boolean.getBoolean("lab.boxing.recoilReturnFull");

    private record Cut(boolean first, double head, double launch, String harms) {}
    private record Anchor(int tick, int index) {}
    private record Phase(int own, int other, int positionDirection) {
        void apply(DuelEnv.Game game) {
            if (own == 0 && other == 0) return;
            var state = game.clock.state;
            state.packetTiming = new BoxingReleaseWindow.ShiftedReceipt(state.packetTiming,
                    game.first ? own : other, game.first ? other : own, positionDirection);
        }
        List<Integer> values() { return List.of(own, other, positionDirection); }
    }

    private static List<Phase> phases() {
        var list = new ArrayList<Phase>();
        for (int own : new int[]{0, -2, 2}) for (int other : new int[]{0, -2, 2}) {
            list.add(new Phase(own, other, 1));
            if (own != 0 || other != 0) list.add(new Phase(own, other, -1));
        }
        return list;
    }

    private record Inputs(boolean sprintFirst, int at, int key) {
        static final Inputs WTAP = new Inputs(false, -1, 0);
        static final Inputs SPRINT_ONLY = new Inputs(true, -1, 0);
        static final Inputs SPRINT_RELEASE = new Inputs(true, 1, RELEASE);
    }

    private static final class Patched extends DuelEnv.Player {
        final DuelEnv.Player stock;
        final Inputs override;
        int head;

        Patched(DuelEnv.Player stock, Inputs override) {
            super(stock.style, stock.mask, stock.rtt, stock.wake);
            this.stock = stock.copy();
            this.override = override;
            firstClick = stock.firstClick;
        }

        @Override public PeriodicComboClock.Control input(PeriodicComboClock.Observation view) {
            var ordinary = stock.input(view);
            int index = head++;
            int key = ordinary.key();
            if (index == 0 && override.sprintFirst) key = SPRINT;
            if (index == override.at) key = override.key;
            return new PeriodicComboClock.Control(key, ordinary.strafe(), ordinary.click(), ordinary.jump());
        }
    }

    private record Reply(double time, long packet, boolean upgrade) {}
    private record Click(double time, long admission, long packet, double nearest, double ray) {
        List<Object> values(double anchor) { return List.of(time - anchor, admission, packet, nearest, ray); }
    }

    private static final class Reading {
        double next = Double.POSITIVE_INFINITY;
        int nextTick = -1;
        Reply firstReturn;
        final List<Click> defenderClicks = new ArrayList<>();
        final List<List<Long>> presses = List.of(new ArrayList<>(), new ArrayList<>());
    }

    private record Evidence(BoxingLateReturns.Score score, double fresh, Reply reply, boolean timely,
                            boolean protectedHit, List<List<Long>> presses, List<Click> defenderClicks) {
        double returned() { return reply == null ? Double.POSITIVE_INFINITY : reply.time; }
        Map<String, Object> values(double anchor, boolean includePress) {
            var out = new LinkedHashMap<>(score.values());
            out.put("freshMs", Double.isFinite(fresh) ? fresh - anchor : null);
            out.put("returnMs", reply == null ? null : reply.time - anchor);
            out.put("timely", timely);
            out.put("protected", protectedHit);
            if (includePress && reply != null && reply.packet >= 0) {
                var click = defenderClicks.stream().filter(c -> c.packet == reply.packet).findFirst();
                out.put("returnClick", click.map(c -> c.values(anchor)).orElse(null));
            }
            return out;
        }
    }

    private static Evidence play(DuelEnv env, DuelEnv.Game prefix, Cut cut, Anchor launch,
                                 Phase phase, Inputs inputs, Reply omit, boolean detail) {
        var game = BoxingChaseEnvelope.branch(env, prefix, new Patched(prefix.our(), inputs));
        var clock = game.clock;
        phase.apply(game);
        var read = new Reading();
        for (var hit : game.hits.list) {
            if (hit.sideA() != game.first) {
                read.firstReturn = new Reply(hit.at(), -1, hit.upgrade());
                break;
            }
        }
        clock.setClickAudit(e -> {
            boolean own = e.fromA() == game.first;
            if (e.action().equals("ADMIT")) read.presses.get(own ? 0 : 1).add(e.admittedNanos());
            if (detail && !own && e.action().equals("CONSUME")) {
                var s = clock.state;
                var body = e.fromA() ? s.a : s.b;
                var target = e.fromA() ? s.ab : s.ba;
                double ray = PeriodicComboFightModel.ray(body, target);
                read.defenderClicks.add(new Click(e.time(), e.admissionId(), e.contact() ? s.seq - 1 : -1,
                        PeriodicComboFightModel.nearest(body.q, target), Double.isFinite(ray) ? ray : -1));
            }
        });
        clock.setAudit((time, stage, state) -> {
            int before = game.hits.list.size();
            game.hits.event(time, stage, state);
            for (int i = before; i < game.hits.list.size(); i++) {
                var hit = game.hits.list.get(i);
                if (hit.sideA() == game.first) {
                    if (!hit.upgrade() && !Double.isFinite(read.next)) {
                        read.next = hit.at();
                        read.nextTick = state.serverTicks;
                    }
                } else if (read.firstReturn == null) {
                    long packet = hit.upgrade()
                            ? (hit.sideA() ? state.lastAcceptedPacketA : state.lastAcceptedPacketB)
                            : (hit.sideA() ? state.lastFreshPacketA : state.lastFreshPacketB);
                    read.firstReturn = new Reply(hit.at(), packet, hit.upgrade());
                }
            }
        });
        if (omit != null) {
            clock.runUntil(Math.nextDown(omit.time));
            int attack = game.first ? 7 : 3;
            int before = clock.state.packets.size();
            clock.state.packets.removeIf(p -> p.kind == attack && p.sequence == omit.packet && p.ready <= omit.time);
            if (clock.state.packets.size() != before - 1) throw new IllegalStateException("Missing linked return at its drain");
        }
        clock.runUntil(cut.launch + HORIZON);
        int ready = launch.tick + clock.config.hitTicks;
        boolean timely = read.nextTick >= ready && read.nextTick <= ready + 1;
        boolean noReturn = read.firstReturn == null || read.firstReturn.time > read.next + 500;
        return new Evidence(BoxingLateReturns.score(game, cut.launch, 100.0 * clock.config.hitTicks + 100),
                read.next, read.firstReturn, timely, timely && noReturn, read.presses, read.defenderClicks);
    }

    private static void score(DuelEnv env, DuelEnv.Game prefix, Cut cut, Anchor anchor) {
        var phaseList = phases();
        if (phaseList.size() != cut.harms.length()) throw new IllegalStateException("Stress panel changed");
        var cases = new ArrayList<Map<String, Object>>();
        for (int index = 0; index < phaseList.size(); index++) {
            var phase = phaseList.get(index);
            boolean harm = cut.harms.charAt(index) == '1';
            var base = play(env, prefix, cut, anchor, phase, Inputs.WTAP, null, harm);
            var sprint = play(env, prefix, cut, anchor, phase, Inputs.SPRINT_ONLY, null, harm);
            boolean expectedHarm = sprint.fresh < base.fresh && sprint.returned() < Math.min(sprint.fresh, base.returned())
                    && sprint.returned() > cut.head;
            if (harm != expectedHarm || !base.presses.equals(sprint.presses))
                throw new IllegalStateException("Frozen cut comparison changed");
            var arms = new LinkedHashMap<String, Object>(Map.of(
                    "phase", phase.values(), "wtap", base.values(cut.launch, true),
                    "sprint", sprint.values(cut.launch, true), "harm", harm));
            if (harm) {
                if (sprint.reply.packet < 0) throw new IllegalStateException("Return preceded input opportunity");
                var omitted = play(env, prefix, cut, anchor, phase, Inputs.SPRINT_ONLY, sprint.reply, true);
                if (!sprint.presses.equals(omitted.presses)) throw new IllegalStateException("Click schedule changed");
                arms.put("removed", omitted.values(cut.launch, true));
                arms.put("removedPacket", sprint.reply.packet);
                var crossing = sprint.defenderClicks.stream().filter(c -> c.packet == sprint.reply.packet).findFirst().orElseThrow();
                var stockClick = base.defenderClicks.stream().filter(c -> c.admission == crossing.admission).findFirst();
                arms.put("wtapSamePress", stockClick.map(c -> c.values(cut.launch)).orElse(null));
            }
            var movement = new ArrayList<List<Object>>();
            if (cut.harms.indexOf('1') >= 0) {
                for (int at = 1; at <= 8; at++) for (int key : new int[]{RELEASE, BACK}) {
                    var response = play(env, prefix, cut, anchor, phase, new Inputs(true, at, key), null, false);
                    if (!base.presses.equals(response.presses)) throw new IllegalStateException("Click schedule changed");
                    movement.add(List.of(at, key, response.values(cut.launch, false)));
                }
            }
            arms.put("movement", movement);
            cases.add(arms);
        }
        System.out.println(Json.encode(Map.of("clock", env.clockId, "seat", cut.first ? "a" : "b",
                "head", cut.head, "anchor", cut.launch, "phases", cases)));
    }

    private record Regulation(int own, int defender, int freshStreak, double end, boolean finished,
                              List<List<Long>> presses) {
        Map<String, Object> values(double cut) {
            return Map.of("own", own, "defender", defender, "longestFreshRun", freshStreak,
                    "end", end, "finished", finished, "suffixCps", presses.stream().map(times ->
                    1000 * times.stream().takeWhile(t -> t / 1e6 <= end).count() / (end - cut)).toList());
        }
    }

    private static Regulation full(DuelEnv env, DuelEnv.Game prefix, Phase phase, Inputs inputs) {
        var game = BoxingChaseEnvelope.branch(env, prefix, new Patched(prefix.our(), inputs));
        game.hits.list.clear();
        phase.apply(game);
        var presses = List.of(new ArrayList<Long>(), new ArrayList<Long>());
        game.clock.setClickAudit(e -> {
            if (e.action().equals("ADMIT")) presses.get(e.fromA() == game.first ? 0 : 1).add(e.admittedNanos());
        });
        var ends = env.serverEnds;
        double horizon = Math.min(env.limit, ends[ends.length - 1]);
        int a = prefix.clock.state.acceptedA, b = prefix.clock.state.acceptedB;
        game.clock.runUntil(horizon);
        int run = 0, longest = 0;
        double finish = horizon;
        for (var hit : game.hits.list) {
            if (hit.sideA()) a++; else b++;
            if (hit.sideA() != game.first) run = 0;
            else if (!hit.upgrade()) longest = Math.max(longest, ++run);
            if (a == 100 || b == 100) {
                finish = hit.at();
                break;
            }
        }
        return new Regulation(game.first ? a : b, game.first ? b : a, longest,
                finish, a == 100 || b == 100, List.copyOf(presses));
    }

    private static boolean samePresses(Regulation first, Regulation second) {
        double end = Math.min(first.end, second.end);
        for (int seat = 0; seat < first.presses.size(); seat++) {
            var a = first.presses.get(seat).stream().takeWhile(t -> t / 1e6 <= end).toList();
            var b = second.presses.get(seat).stream().takeWhile(t -> t / 1e6 <= end).toList();
            if (!a.equals(b)) return false;
        }
        return true;
    }

    private static void scoreFull(DuelEnv env, DuelEnv.Game prefix, Cut cut) {
        var variants = new ArrayList<Map<String, Object>>();
        for (var phase : phases()) {
            var wtap = full(env, prefix, phase, Inputs.WTAP);
            var sprint = full(env, prefix, phase, Inputs.SPRINT_ONLY);
            var release = full(env, prefix, phase, Inputs.SPRINT_RELEASE);
            if (!samePresses(wtap, sprint) || !samePresses(wtap, release) || !samePresses(sprint, release))
                throw new IllegalStateException("Different click schedules through the earlier finish");
            variants.add(Map.of("phase", phase.values(), "wtap", wtap.values(cut.head),
                    "sprint", sprint.values(cut.head), "sprintRelease", release.values(cut.head)));
        }
        System.out.println(Json.encode(Map.of("clock", env.clockId, "seat", cut.first ? "a" : "b",
                "head", cut.head, "anchor", cut.launch, "phases", variants)));
    }

    public static void main(String[] args) throws Exception {
        int rtt = Integer.parseInt(args[0]);
        var env = BoxingEdgeHitSelect.environment(Path.of(args[4]), rtt, 180_000, Path.of(args[1]), Path.of(args[2]));
        var cuts = Files.readAllLines(Path.of(args[3])).stream().map(line -> {
            var fields = line.split(" ");
            return new Cut(fields[0].equals("a"), Double.parseDouble(fields[1]), Double.parseDouble(fields[2]), fields[3]);
        }).toList();
        for (boolean first : new boolean[]{true, false}) {
            var stock = env.new Game(first, new DuelEnv.Player("wtap", 0, rtt, 0), new DuelEnv.Player("wtap", 0, rtt, 0));
            stock.trace();
            var anchors = new LinkedHashMap<Double, Anchor>();
            stock.clock.setAudit((time, stage, state) -> {
                int before = stock.hits.list.size();
                stock.hits.event(time, stage, state);
                for (int i = before; i < stock.hits.list.size(); i++) {
                    var hit = stock.hits.list.get(i);
                    if (hit.sideA() == first && !hit.upgrade()) anchors.put(hit.at(), new Anchor(state.serverTicks, i));
                }
            });
            for (var cut : cuts.stream().filter(c -> c.first == first).toList()) {
                boolean found = stock.clock.advanceToBeforeNextHead(first, cut.head);
                while (found && stock.clock.time() < cut.head) found = stock.clock.runHeadInterval(first, cut.head);
                if (!found || stock.clock.time() != cut.head)
                    throw new IllegalStateException("Release head no longer present");
                var launch = anchors.get(cut.launch);
                if (launch == null) throw new IllegalStateException("Original launch no longer present");
                var prefix = env.new Game(stock, stock.a.copy(), stock.b.copy());
                prefix.trace();
                prefix.hits.list.addAll(stock.hits.list.subList(launch.index + 1, stock.hits.list.size()));
                if (LONG) scoreFull(env, prefix, cut);
                else score(env, prefix, cut, launch);
            }
        }
    }
}
