import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Observer for first return geometry, late enough to score the actual native fresh window. */
final class BoxingReplyEvidence {
    private record Attempt(double at, double ray, boolean contact) {}

    private final DuelEnv.Game game;
    private final double cut;
    private final BoxingTradeSkills.Landings landings;
    private final List<Attempt> replies = new ArrayList<>();
    private final ArrayList<Double> ownHits = new ArrayList<>(), otherHits = new ArrayList<>();
    private final double[] window = {Double.NaN, Double.NaN};
    private int seen;

    BoxingReplyEvidence(DuelEnv.Game game, double cut) {
        this.game = game;
        this.cut = cut;
        landings = new BoxingTradeSkills.Landings(game.clock.state);
    }

    void click(PeriodicComboClock.ClickEvent event) {
        if (!event.action().equals("CONSUME") || event.fromA() == game.first || !event.eligible()) return;
        var state = game.clock.state;
        var ray = event.fromA() ? PeriodicComboFightModel.ray(state.a, state.ab)
                                : PeriodicComboFightModel.ray(state.b, state.ba);
        replies.add(new Attempt(event.time(), ray, event.contact()));
    }

    void event(double time, String stage, PeriodicComboFightModel.State state) {
        game.hits.event(time, stage, state);
        landings.event(time, stage, state);
        while (seen < game.hits.list.size()) {
            var hit = game.hits.list.get(seen++);
            if (!hit.upgrade()) (hit.sideA() == game.first ? ownHits : otherHits).add(hit.at());
        }
        if (!stage.equals("SERVER_ENTITY")) return;
        int ownTimer = game.first ? state.ownResistant : state.resistant;
        int otherTimer = game.first ? state.resistant : state.ownResistant;
        if (Double.isNaN(window[0]) && !otherHits.isEmpty() && ownTimer <= state.freshThreshold) window[0] = time;
        if (Double.isNaN(window[1]) && !ownHits.isEmpty() && otherTimer <= state.freshThreshold) window[1] = time;
    }

    Map<String, Object> values() {
        var output = new LinkedHashMap<String, Object>(landings.values(game.first));
        output.put("freshWindow", Arrays.asList(finite(window[0]), finite(window[1])));
        output.put("firstOwn", ownHits.stream().limit(3).toList());
        output.put("firstOther", otherHits.stream().limit(3).toList());
        output.put("firstOtherAccepted", game.hits.list.stream().filter(h -> h.sideA() != game.first)
                .limit(3).map(h -> List.of(h.at(), h.upgrade())).toList());
        if (!ownHits.isEmpty() && Double.isFinite(window[0])) {
            double start = Math.max(window[0], ownHits.get(0));
            double end = ownHits.size() >= 2 ? ownHits.get(1) : Math.min(cut + 2500, start + 850);
            var examined = replies.stream().filter(a -> a.at >= start && a.at < end).toList();
            var nearest = examined.stream().mapToDouble(Attempt::ray).min();
            output.put("defenderAttemptsBetweenWindows", examined.size());
            output.put("defenderContactsBetweenWindows", examined.stream().filter(Attempt::contact).count());
            output.put("defenderMinimumRay", nearest.isPresent() ? finite(nearest.getAsDouble()) : null);
        }
        return output;
    }

    private static Double finite(double x) { return Double.isFinite(x) ? x : null; }
}
