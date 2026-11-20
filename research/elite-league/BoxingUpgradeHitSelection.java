import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lab.Json;

/** Compare one bounded click hold against matched controls on exact prior-hit states. */
final class BoxingUpgradeHitSelection {
    private static final double HORIZON = 1400;
    private static final int[] HOLDS = {10, 20, 30, 40, 50, 65, 80, 100, 125, 150, 200};

    private static final class Hold extends BoxingUpgradeDeadline.Primed {
        final double openedAt;

        Hold(BoxingUpgradeDeadline.Primed prefix, double openedAt) {
            super(prefix.actual.copy(), prefix.wtap.copy());
            this.openedAt = openedAt;
        }

        @Override public boolean allowInjection(PeriodicComboClock.Observation own, String source) {
            return own.time() >= openedAt;
        }
    }

    static final class Score {
        int own, other, ownFresh, otherFresh, earlyReturns;
        int firstDamage;
        double firstOwn = Double.NaN, upgrade = Double.NaN, firstAdmission = Double.NaN;
        double nextFresh = Double.NaN, firstOpponent = Double.NaN;
        long firstAdmissionId = -1, firstPacketId = -1;
        String firstPress = "unconsumed";
        Double firstPollY, firstPollVy, firstPollNearest;
        Double firstPollOwnHurtAge, firstPollTargetHurtAge;
        Boolean firstPollGround;

        int margin() { return own - other; }

        private static Double relative(double time, double cut) {
            return Double.isFinite(time) ? time - cut : null;
        }

        Map<String, Object> values(double cut) {
            var values = new LinkedHashMap<String, Object>();
            values.put("score", List.of(own, other));
            values.put("fresh", List.of(ownFresh, otherFresh));
            values.put("firstDamage", firstDamage);
            values.put("firstOwnMs", relative(firstOwn, cut));
            values.put("firstOpponentMs", relative(firstOpponent, cut));
            values.put("upgradeMs", relative(upgrade, cut));
            values.put("nextFreshMs", relative(nextFresh, cut));
            values.put("admissionMs", relative(firstAdmission, cut));
            values.put("firstPressOutcome", firstPress);
            values.put("opponentBeforeFirstOwn", earlyReturns);
            values.put("pollY", firstPollY);
            values.put("pollVy", firstPollVy);
            values.put("pollNearest", firstPollNearest);
            values.put("pollGround", firstPollGround);
            values.put("ownHurtAge", firstPollOwnHurtAge);
            values.put("targetHurtAge", firstPollTargetHurtAge);
            return values;
        }
    }

    static DuelEnv.Game hold(DuelEnv env, DuelEnv.Game prefix, double releaseAt) {
        var chosen = new Hold((BoxingUpgradeDeadline.Primed) prefix.our(), releaseAt);
        var game = env.new Game(prefix, prefix.first ? chosen : prefix.a.copy(),
                prefix.first ? prefix.b.copy() : chosen);
        game.trace();
        return game;
    }

    static Score play(DuelEnv.Game game, double end) {
        var score = new Score();
        game.clock.setClickAudit(e -> {
            if (e.fromA() != game.first) return;
            if (e.action().equals("CONSUME") && e.admissionId() == score.firstAdmissionId) {
                if (e.contact()) {
                    int kind = game.first ? 3 : 7;
                    score.firstPacketId = game.clock.state.packets.stream().filter(p -> p.kind == kind)
                            .mapToLong(p -> p.sequence).max().orElseThrow();
                    score.firstPress = "contact-unscored";
                } else score.firstPress = e.eligible() ? "miss" : "miss-cooldown";
                return;
            }
            if (!e.action().equals("ADMIT") || Double.isFinite(score.firstAdmission)) return;
            score.firstAdmissionId = e.admissionId();
            score.firstAdmission = e.admittedNanos() / 1e6;
            var state = game.clock.state;
            var local = game.first ? state.a : state.b;
            var replica = game.first ? state.ab : state.ba;
            score.firstPollY = local.q.y();
            score.firstPollVy = local.q.vy();
            score.firstPollNearest = PeriodicComboFightModel.nearest(local.q, replica);
            score.firstPollGround = local.q.onGround();
            score.firstPollOwnHurtAge = Double.isFinite(local.lastHurtTime) ? e.time() - local.lastHurtTime : null;
            score.firstPollTargetHurtAge = Double.isFinite(replica.lastHurtTime) ? e.time() - replica.lastHurtTime : null;
        });
        game.clock.setAudit((time, stage, state) -> {
            int i = game.hits.list.size();
            game.hits.event(time, stage, state);
            int lastDamage = game.first ? state.lastDamageB : state.lastDamageA;
            for (; i < game.hits.list.size(); i++) {
                var hit = game.hits.list.get(i);
                if (hit.sideA() != game.first) {
                    if (!Double.isFinite(score.firstOpponent)) score.firstOpponent = hit.at();
                    score.other++;
                    if (!hit.upgrade()) score.otherFresh++;
                    if (score.own == 0) score.earlyReturns++;
                    continue;
                }
                long linked = hit.upgrade() ? (game.first ? state.lastAcceptedPacketA : state.lastAcceptedPacketB)
                        : (game.first ? state.lastFreshPacketA : state.lastFreshPacketB);
                if (score.firstPacketId >= 0 && linked == score.firstPacketId)
                    score.firstPress = hit.upgrade() ? "upgrade" : "fresh";
                if (score.own == 0) {
                    score.firstOwn = hit.at();
                    boolean laterUpgrade = game.hits.list.subList(i + 1, game.hits.list.size()).stream()
                            .anyMatch(h -> h.sideA() == game.first && h.upgrade());
                    score.firstDamage = hit.upgrade() ? -1 : laterUpgrade ? 8 : lastDamage;
                } else if (!Double.isFinite(score.nextFresh)) {
                    if (!hit.upgrade()) score.nextFresh = hit.at();
                    else if (score.firstDamage == 8 && !Double.isFinite(score.upgrade)) score.upgrade = hit.at();
                }
                score.own++;
                if (!hit.upgrade()) score.ownFresh++;
            }
        });
        game.clock.runUntil(end);
        return score;
    }

    private static Map<String, Object> witness(DuelEnv env, boolean first, Arena.Model model,
                                                 DuelEnv.Game source, BoxingUpgradeDeadline.Candidate candidate) {
        double originalPress = candidate.normalShot().admitted();
        double cut = Math.nextDown(originalPress), end = candidate.deadline() + HORIZON;
        var anchor = BoxingUpgradeDeadline.start(env, first, model);
        anchor.clock.runUntil(cut);
        var variants = new ArrayList<Map<String, Object>>();
        for (int own : new int[]{0, 5, -5}) for (int other : new int[]{0, 5, -5}) {
            var shifted = BoxingUpgradeDeadline.branch(env, anchor, false);
            if (own != 0) shifted.clock.shiftFutureClientOpportunities(first, own);
            if (other != 0) shifted.clock.shiftFutureClientOpportunities(!first, other);
            var unmodified = BoxingUpgradeDeadline.branch(env, shifted, false);
            var stock = play(unmodified, end);
            var wtap = play(BoxingUpgradeDeadline.branch(env, shifted, true), end);
            if (own == 0 && other == 0) {
                if (!unmodified.hits.list.equals(BoxingUpgradeDeadline.suffix(source, originalPress, end)))
                    throw new AssertionError("Pre-press replay changed its original hit sequence");
                if (stock.firstAdmission != originalPress || stock.firstDamage != 8
                        || stock.firstOwn != candidate.normalAt() || Double.isFinite(stock.upgrade))
                    throw new AssertionError("Original admission, normal hit, or missed upgrade was misidentified");
            }
            var arms = new LinkedHashMap<String, Object>();
            for (int delay : HOLDS) {
                var result = play(hold(env, shifted, originalPress + delay), end);
                if (result.firstAdmission < originalPress + delay)
                    throw new AssertionError("A gated press was admitted during its hold");
                boolean preserves = stock.firstDamage == 8 && result.firstDamage == 8 && result.firstOwn > stock.firstOwn;
                arms.put(Integer.toString(delay), Map.of("result", result.values(originalPress),
                        "normalPreservedAndDelayed", preserves, "deltaStock", result.margin() - stock.margin(),
                        "deltaWtap", result.margin() - wtap.margin()));
            }
            variants.add(Map.of("phase", List.of(own, other), "stock", stock.values(originalPress),
                    "wtap", wtap.values(originalPress), "holds", arms));
        }
        return Map.of("pressMs", originalPress, "normalHitMs", candidate.normalAt(),
                "lateCriticalMs", candidate.freshAt(), "variants", variants);
    }

    private static Map<String, Object> trial(DuelEnv env, DuelEnv.Game anchor, int own, int other,
                                              double press, double end, int delay, DuelEnv.Game source,
                                              BoxingUpgradeDeadline.Normal event) {
        var shifted = BoxingUpgradeDeadline.branch(env, anchor, false);
        if (own != 0) shifted.clock.shiftFutureClientOpportunities(shifted.first, own);
        if (other != 0) shifted.clock.shiftFutureClientOpportunities(!shifted.first, other);
        var original = BoxingUpgradeDeadline.branch(env, shifted, false);
        var stock = play(original, end);
        var wtap = play(BoxingUpgradeDeadline.branch(env, shifted, true), end);
        var result = play(hold(env, shifted, press + delay), end);
        if (own == 0 && other == 0) {
            if (!original.hits.list.equals(BoxingUpgradeDeadline.suffix(source, press, end)))
                throw new AssertionError("All-normal anchor did not replay");
            // During an exact cut, an older press can already be in flight. Keep it in all arms.
            if (original.hits.list.stream().noneMatch(h ->
                    h.sideA() == shifted.first && !h.upgrade() && h.at() == event.accepted()))
                throw new AssertionError("The selected normal hit is absent");
        }
        if (result.firstAdmission < press + delay)
            throw new AssertionError("The selected hold admitted early");
        return Map.of("phase", List.of(own, other), "stock", stock.values(press), "wtap", wtap.values(press),
                "result", result.values(press), "deltaStock", result.margin() - stock.margin(),
                "deltaWtap", result.margin() - wtap.margin(),
                "normalPreservedAndDelayed", stock.firstDamage == 8 && result.firstDamage == 8
                        && result.firstOwn > stock.firstOwn);
    }

    private static List<Map<String, Object>> allNormal(DuelEnv env, boolean first, Arena.Model model,
                                                        DuelEnv.Game source, BoxingUpgradeDeadline.Ledger ledger,
                                                        double finish, int delay) {
        var anchor = BoxingUpgradeDeadline.start(env, first, model);
        var cases = new ArrayList<Map<String, Object>>();
        var sorted = ledger.normals.stream().sorted(Comparator.comparingDouble(e -> e.shot().admitted())).toList();
        for (var event : sorted) {
            var lastImmune = ledger.drains.get(event.tick() + env.initial.config.hitTicks - 1);
            if (lastImmune == null || lastImmune + HORIZON >= finish) continue;
            double press = event.shot().admitted(), end = lastImmune + HORIZON;
            anchor.clock.runUntil(Math.nextDown(press));
            var nominal = trial(env, anchor, 0, 0, press, end, delay, source, event);
            var shifted = new ArrayList<Map<String, Object>>();
            if ((int) nominal.get("deltaStock") > 0 && (int) nominal.get("deltaWtap") > 0) {
                for (int own : new int[]{0, 5, -5}) for (int other : new int[]{0, 5, -5}) {
                    if (own != 0 || other != 0) shifted.add(trial(env, anchor, own, other, press, end, delay, source, event));
                }
            }
            cases.add(Map.of("normalHitMs", event.accepted(), "pressMs", press,
                    "nominal", nominal, "shifted", shifted));
        }
        return cases;
    }

    private static Map<String, Object> run(DuelEnv env, boolean first, Arena.Model model) {
        var source = BoxingUpgradeDeadline.start(env, first, model);
        var ledger = new BoxingUpgradeDeadline.Ledger(source);
        while (source.clock.state.acceptedA < 100 && source.clock.state.acceptedB < 100 && source.advance()) {
            // The comparisons stay inside the observed regulation match.
        }
        double finish = source.hits.list.get(source.hits.list.size() - 1).at();
        int fixedHold = Integer.getInteger("lab.boxing.allNormalHold", 0);
        if (fixedHold > 0) {
            return Map.of("cases", allNormal(env, first, model, source, ledger, finish, fixedHold));
        }
        var cases = new ArrayList<Map<String, Object>>();
        for (var candidate : ledger.late) {
            if (candidate.deadline() + HORIZON < finish) cases.add(witness(env, first, model, source, candidate));
        }
        return Map.of("cases", cases);
    }

    public static void main(String[] args) throws Exception {
        var model = Arena.Model.decode(Files.readString(Path.of(args[0])).strip());
        var wires = Path.of(args[1]);
        var seeds = Path.of(args[2]);
        for (int i = 3; i < args.length; i++) {
            var env = BoxingDoubleSearch.environment(Path.of(args[i]), 20, 20, 180_000);
            BoxingJumpTimingAudit.wireTiming(env, wires.resolve(env.clockId + ".txt"));
            BoxingJumpTimingAudit.seedTracker(env, seeds.resolve(env.clockId + ".txt"));
            for (boolean first : new boolean[]{true, false}) {
                var wtap = run(env, first, null);
                var frozen = run(env, first, model);
                System.out.println(Json.encode(Map.of("clock", env.clockId, "seat", first ? "a" : "b",
                        "wtap", wtap, "frozen", frozen)));
            }
        }
    }
}
