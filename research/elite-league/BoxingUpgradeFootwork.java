import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lab.DuelNetwork;
import lab.Json;

/** Search ordinary movement from the exact prior normal hit of a late critical. */
final class BoxingUpgradeFootwork {
    private static final double HORIZON = 1400;

    private record Phase(int own, int other) {
        List<Integer> values() { return List.of(own, other); }
    }

    private record Cut(DuelEnv.Game game, Result stock, Result wtap) {}
    private record HitTotals(int score, int fresh, int upgrades) {}
    private record Result(HitTotals own, HitTotals other, Double earlyUpgrade, Double nextFresh) {
        int margin() { return own.score - other.score; }
        Map<String, Object> values() {
            var row = new LinkedHashMap<String, Object>();
            row.put("score", List.of(own.score, other.score));
            row.put("fresh", List.of(own.fresh, other.fresh));
            row.put("upgrades", List.of(own.upgrades, other.upgrades));
            row.put("margin", margin());
            row.put("earlyUpgrade", earlyUpgrade);
            row.put("nextFresh", nextFresh);
            return row;
        }
    }

    private record Win(int head, int code, Result result, int scoreGain) {}
    private record Coverage(Win win, int worstStock, int worstWtap, List<Map<String, Object>> phases) {}

    private static final class Movement extends BoxingUpgradeDeadline.Primed {
        int word, remaining = BoxingLateReturns.HEADS;
        PeriodicComboClock.Observation first;

        Movement(BoxingUpgradeDeadline.Primed prefix, int word) {
            super(prefix.actual.copy(), prefix.wtap.copy());
            this.word = word;
        }

        @Override public PeriodicComboClock.Control input(PeriodicComboClock.Observation own) {
            var control = super.input(own);
            if (remaining == 0) return control;
            if (remaining == BoxingLateReturns.HEADS) first = own;
            remaining--;
            var next = BoxingLateReturns.movement(control, word % BoxingLateReturns.MOVES);
            word /= BoxingLateReturns.MOVES;
            if (actual instanceof Arena.Neural neural)
                neural.net.applied(DuelNetwork.pack(next.key(), (int)Math.signum(next.strafe()), next.click(), next.jump()));
            return next;
        }

        Map<String, Object> initial(double normalAt) {
            var row = new LinkedHashMap<String, Object>(Map.of("headMs", first.time() - normalAt,
                    "knowsTargetHurt", first.lastTargetHurtTime() >= normalAt, "nearest", first.nearest(),
                    "ground", first.own().onGround(), "sprint", first.sprint(), "ownY", first.own().y(),
                    "targetY", first.targetY()));
            row.put("targetHurtAgeMs", Double.isFinite(first.lastTargetHurtTime())
                    ? first.time() - first.lastTargetHurtTime() : null);
            return row;
        }
    }

    private static DuelEnv.Game move(DuelEnv env, DuelEnv.Game prefix, Movement movement) {
        var trial = env.new Game(prefix, prefix.first ? movement : prefix.a.copy(),
                prefix.first ? prefix.b.copy() : movement);
        trial.trace();
        return trial;
    }

    private static DuelEnv.Game continueFrom(DuelEnv env, DuelEnv.Game prefix, boolean wtap) {
        var game = BoxingUpgradeDeadline.branch(env, prefix, wtap);
        game.hits.list.addAll(prefix.hits.list);
        return game;
    }

    private static Result read(DuelEnv.Game game, double windowStart, double cutoff) {
        int ownFresh = 0, otherFresh = 0, ownUpgrade = 0, otherUpgrade = 0;
        Double early = null, next = null;
        for (var hit : game.hits.list) {
            if (hit.sideA() == game.first) {
                if (hit.upgrade()) {
                    ownUpgrade++;
                    if (early == null && next == null && hit.at() <= cutoff) early = hit.at() - windowStart;
                } else {
                    ownFresh++;
                    if (next == null) next = hit.at() - windowStart;
                }
            } else if (hit.upgrade()) otherUpgrade++;
            else otherFresh++;
        }
        return new Result(new HitTotals(ownFresh + ownUpgrade, ownFresh, ownUpgrade),
                new HitTotals(otherFresh + otherUpgrade, otherFresh, otherUpgrade), early, next);
    }

    private static Result eval(DuelEnv.Game game, double windowStart, double cutoff, double end) {
        game.clock.runUntil(end);
        return read(game, windowStart, cutoff);
    }

    private static List<Cut> cuts(DuelEnv env, DuelEnv.Game anchor, Phase phase, double start,
                                  double cutoff, double past, int limit) {
        var prefix = continueFrom(env, anchor, false);
        if (phase.own != 0) prefix.clock.shiftFutureClientOpportunities(prefix.first, phase.own);
        if (phase.other != 0) prefix.clock.shiftFutureClientOpportunities(!prefix.first, phase.other);
        var cuts = new ArrayList<Cut>();
        if (!prefix.clock.advanceToBeforeNextHead(prefix.first, past)) return cuts;
        do {
            var snapshot = continueFrom(env, prefix, false);
            var stock = eval(continueFrom(env, snapshot, false), start, cutoff, cutoff + HORIZON);
            var wtap = eval(continueFrom(env, snapshot, true), start, cutoff, cutoff + HORIZON);
            cuts.add(new Cut(snapshot, stock, wtap));
        } while (cuts.size() < limit && prefix.clock.runHeadInterval(prefix.first, past));
        return cuts;
    }

    private static Result trial(DuelEnv env, Cut cut, int word, double start, double cutoff) {
        var movement = new Movement((BoxingUpgradeDeadline.Primed) cut.game.our(), word);
        var game = move(env, cut.game, movement);
        game.hits.list.addAll(cut.game.hits.list);
        return eval(game, start, cutoff, cutoff + HORIZON);
    }

    private static Map<String, Object> search(DuelEnv env, boolean first, Arena.Model model,
                                               DuelEnv.Game source, BoxingUpgradeDeadline.Candidate event) {
        double start = event.normalAt(), cutoff = event.deadline(), end = cutoff + HORIZON;
        var anchor = BoxingUpgradeDeadline.start(env, first, model);
        anchor.clock.runUntil(start);
        anchor.hits.list.clear();
        var replay = continueFrom(env, anchor, false);
        var stock = eval(replay, start, cutoff, end);
        if (!replay.hits.list.equals(BoxingUpgradeDeadline.suffix(source, Math.nextUp(start), end)))
            throw new AssertionError("Anchored match replay changed its hit sequence");
        if (stock.earlyUpgrade != null) throw new AssertionError("Stock window already upgraded");
        var natural = new Phase(0, 0);
        var starts = cuts(env, anchor, natural, start, cutoff, Math.nextDown(cutoff), Integer.MAX_VALUE);
        var wins = new ArrayList<Win>();
        int conversions = 0;
        for (int i = 0; i < starts.size(); i++) {
            var cut = starts.get(i);
            if (!stock.equals(cut.stock) || !stock.equals(trial(env, cut, 0, start, cutoff)))
                throw new AssertionError("No-op probe changed the stock suffix");
            for (int word = 1; word < BoxingLateReturns.WORDS; word++) {
                var result = trial(env, cut, word, start, cutoff);
                if (result.earlyUpgrade == null) continue;
                conversions++;
                int gain = result.margin() - stock.margin();
                if (gain > 0) wins.add(new Win(i, word, result, gain));
            }
        }

        var variants = new ArrayList<Phase>();
        var shiftedCuts = new ArrayList<List<Cut>>();
        for (int own : new int[]{0, 5, -5}) for (int other : new int[]{0, 5, -5}) {
            Phase phase = new Phase(own, other);
            variants.add(phase);
            shiftedCuts.add(phase.equals(natural) || wins.isEmpty() ? starts
                    : cuts(env, anchor, phase, start, cutoff, end, starts.size()));
        }
        var robust = new ArrayList<Coverage>();
        for (var win : wins) {
            int worstStock = Integer.MAX_VALUE, worstWtap = Integer.MAX_VALUE;
            var scores = new ArrayList<Map<String, Object>>();
            for (int i = 0; i < variants.size(); i++) {
                var cut = shiftedCuts.get(i).get(win.head);
                var outcome = i == 0 ? win.result : trial(env, cut, win.code, start, cutoff);
                int gain = outcome.margin() - cut.stock.margin();
                if (outcome.earlyUpgrade == null || gain <= 0) break;
                worstStock = Math.min(worstStock, gain);
                worstWtap = Math.min(worstWtap, outcome.margin() - cut.wtap.margin());
                scores.add(Map.of("phase", variants.get(i).values(), "result", outcome.values(),
                        "stock", cut.stock.values(), "wtap", cut.wtap.values()));
            }
            if (scores.size() == variants.size()) robust.add(new Coverage(win, worstStock, worstWtap, scores));
        }
        var row = new LinkedHashMap<String, Object>();
        row.put("normalAtMs", start);
        row.put("cutoffFromNormalMs", cutoff - start);
        row.put("stockLateFromCutoffMs", event.shot().consume() - cutoff);
        row.put("stock", stock.values());
        row.put("wtap", eval(continueFrom(env, anchor, true), start, cutoff, end).values());
        row.put("starts", starts.size());
        row.put("tested", starts.size() * (BoxingLateReturns.WORDS - 1));
        row.put("converted", conversions);
        row.put("nominalJoint", wins.size());
        row.put("robustVsStock", robust.size());
        row.put("robustVsBoth", robust.stream().filter(r -> r.worstWtap > 0).count());
        var best = robust.stream().max(Comparator.comparingInt(Coverage::worstWtap)
                .thenComparingInt(Coverage::worstStock).thenComparingInt(v -> v.win.scoreGain));
        if (best.isPresent()) {
            var witness = best.get();
            var win = witness.win;
            var prefix = starts.get(win.head).game;
            var movement = new Movement((BoxingUpgradeDeadline.Primed) prefix.our(), win.code);
            var repeat = move(env, prefix, movement);
            repeat.hits.list.addAll(prefix.hits.list);
            if (!eval(repeat, start, cutoff, end).equals(win.result)) throw new AssertionError("Witness mismatch");
            row.put("witness", Map.of("head", win.head, "word", BoxingLateReturns.names(win.code),
                    "initial", movement.initial(start), "variants", witness.phases));
        }
        return row;
    }

    private static Map<String, Object> run(DuelEnv env, boolean first, Arena.Model model) {
        var source = BoxingUpgradeDeadline.start(env, first, model);
        var ledger = new BoxingUpgradeDeadline.Ledger(source);
        while (source.clock.state.acceptedA < 100 && source.clock.state.acceptedB < 100 && source.advance()) {
            // The replay opportunities are shared by each paired branch.
        }
        double finish = source.hits.list.get(source.hits.list.size() - 1).at();
        var rows = new ArrayList<Map<String, Object>>();
        for (var window : ledger.late) {
            if (window.deadline() + HORIZON < finish) rows.add(search(env, first, model, source, window));
        }
        return Map.of("cases", rows);
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
