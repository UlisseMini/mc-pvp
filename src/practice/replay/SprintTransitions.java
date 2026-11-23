package practice.replay;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.TreeSet;

/** Confirmed server sprint changes on a recording's playhead. */
public final class SprintTransitions {
    public static final int DISPLAY_MS = 250;
    public static final class Sample {
        public static final Sample EMPTY = new Sample(Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY);
        public final double gain, loss;

        private Sample(double gain, double loss) { this.gain = gain; this.loss = loss; }

        public static boolean visible(double at, double time) {
            return at <= time && time - at < DISPLAY_MS;
        }
    }

    private final NavigableSet<Double> gains = new TreeSet<>(), losses = new TreeSet<>();

    public void add(double time, boolean sprint) {
        if (Double.isFinite(time) && time >= 0) (sprint ? gains : losses).add(time);
    }

    public boolean isEmpty() { return gains.isEmpty() && losses.isEmpty(); }

    public Sample at(double time) {
        Double gain = gains.floor(time), loss = losses.floor(time);
        return new Sample(gain == null ? Double.NEGATIVE_INFINITY : gain,
                loss == null ? Double.NEGATIVE_INFINITY : loss);
    }

    public List<Map<String,Object>> between(double from, double to) {
        List<Map<String,Object>> rows = new ArrayList<>();
        for (boolean sprint : Arrays.asList(true, false)) {
            for (double at : (sprint ? gains : losses).subSet(from, true, to, true)) {
                Map<String,Object> row = new LinkedHashMap<>();
                row.put("at", at);
                row.put("sprint", sprint);
                rows.add(row);
            }
        }
        return rows;
    }

    public static Sample read(List<?> rows, double time) {
        double gain = Double.NEGATIVE_INFINITY, loss = Double.NEGATIVE_INFINITY;
        for (Object row : rows) {
            Map<String,Object> event = PoseTrack.object(row);
            double at = PoseTrack.number(event, "at", Double.NaN);
            if (!Double.isFinite(at) || at < 0 || at > time) continue;
            if (Boolean.TRUE.equals(event.get("sprint"))) gain = Math.max(gain, at);
            if (Boolean.FALSE.equals(event.get("sprint"))) loss = Math.max(loss, at);
        }
        return new Sample(gain, loss);
    }
}
