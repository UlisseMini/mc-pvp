package practice.core.forecast;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** The replay feature and label schedule, driven by completed client snapshots. */
public final class BedWarsForecastHistory {
    private static final int[] HORIZONS = {5, 10, 30, 60, 120};
    private static final int LABEL_STRIDE = 3, SUPPORT_EPOCH = 30, SUPPORT_LIMIT = 32;
    private static final int SUPPORT_BUFFER = 220, TRAIL_LIMIT = 1200;
    private static final int LOOKBACK = HORIZONS[HORIZONS.length - 1] + 2;
    private final Map<Integer, Map<UUID, Input>> frames = new LinkedHashMap<>();
    private final Map<UUID, List<Deque<Support>>> supports = new HashMap<>();
    private final Map<UUID, List<float[]>> trails = new HashMap<>();
    private float openingY = Float.NaN;
    private int lastSecond = -1;

    public static final class PersonalContext {
        public final int second;
        /** Rows of [second, x, y, z] ordered by second <= current second. */
        public final float[][] trail;
        /** Rows of 28 floats: 27 standard support fields + issueSecond at index 27. */
        public final float[][] routes;
        public PersonalContext(int second, float[][] trail, float[][] routes) {
            this.second = second;
            this.trail = trail;
            this.routes = routes;
        }
    }

    public static int[] horizons() { return HORIZONS.clone(); }

    public static final class Observation {
        public final float[] xyz;
        public final float yaw;
        public final boolean sneak, sword;
        public Observation(float x, float y, float z, float yaw, boolean sneak, boolean sword) {
            xyz = new float[]{x,y,z};
            this.yaw = yaw;
            this.sneak = sneak;
            this.sword = sword;
        }
    }

    public static final class Input {
        public final Observation observation;
        public final float[] features, velocity;
        Input(Observation observation, float[] features, float[] velocity) {
            this.observation = observation;
            this.features = features;
            this.velocity = velocity;
        }
    }

    private static final class Support {
        final int issued, due;
        final float[] values, routeRow;
        Support(int issued, int due, Input origin, Observation destination) {
            this.issued = issued;
            this.due = due;
            values = new float[27];
            System.arraycopy(origin.observation.xyz, 0, values, 0, 3);
            System.arraycopy(origin.velocity, 0, values, 3, 3);
            System.arraycopy(origin.features, 0, values, 6, 17);
            System.arraycopy(destination.xyz, 0, values, 23, 3);
            values[26] = origin.observation.yaw;
            routeRow = new float[28];
            System.arraycopy(values, 0, routeRow, 0, 27);
            routeRow[27] = issued;
        }
    }

    public void clear() {
        frames.clear();
        supports.clear();
        trails.clear();
        openingY = Float.NaN;
        lastSecond = -1;
    }

    public static boolean withinGrid(float[] xyz) {
        return Float.isFinite(xyz[0]) && Float.isFinite(xyz[1]) && Float.isFinite(xyz[2])
                && xyz[0] >= -128 && xyz[0] < 128 && xyz[2] >= -128 && xyz[2] < 128;
    }

    /** Add one frame and return issue-time inputs. Gaps remain gaps. */
    public Map<UUID, Input> add(int second, UUID local, Map<UUID, Observation> observations) {
        if (second < 2 || second <= lastSecond) throw new IllegalArgumentException("Expected a new BedWars second");
        Observation own = observations.get(local);
        if (Float.isNaN(openingY)) openingY = own == null ? 75 : own.xyz[1];
        Map<UUID, Input> inputs = new LinkedHashMap<>();
        for (Map.Entry<UUID, Observation> item : observations.entrySet()) {
            UUID id = item.getKey();
            Observation pose = item.getValue();
            inputs.put(id, features(id, pose, own, local.equals(id), observations.size(), second));
            if (withinGrid(pose.xyz)) {
                List<float[]> trail = trails.computeIfAbsent(id, ignored -> new ArrayList<>());
                trail.add(new float[]{second, pose.xyz[0], pose.xyz[1], pose.xyz[2]});
                if (trail.size() > TRAIL_LIMIT) trail.remove(0);
            }
        }
        complete(second, inputs);
        frames.put(second, inputs);
        frames.keySet().removeIf(t -> t < second - LOOKBACK);
        lastSecond = second;
        return inputs;
    }

    private Observation previous(int t, UUID id) {
        Map<UUID, Input> previous = frames.get(t);
        if (previous == null) return null;
        Input input = previous.get(id);
        return input == null ? null : input.observation;
    }

    private static float[] velocity(Observation pose, Observation old, int seconds) {
        if (old == null) return new float[3];
        float[] result = new float[3];
        for (int i = 0; i < 3; i++) result[i] = (pose.xyz[i] - old.xyz[i]) / seconds;
        if (result[0] * result[0] + result[1] * result[1] + result[2] * result[2] > 81) return new float[3];
        return result;
    }

    private Input features(UUID id, Observation pose, Observation own, boolean local, int visible, int second) {
        Observation p2 = previous(second - 2, id), p8 = previous(second - 8, id);
        float[] v2 = velocity(pose, p2, 2), v8 = velocity(pose, p8, 8), xyz = pose.xyz;
        double c = Math.cos(pose.yaw), s = Math.sin(pose.yaw);
        double distance = own == null ? 160 : Math.sqrt(Math.pow(xyz[0] - own.xyz[0], 2)
                + Math.pow(xyz[1] - own.xyz[1], 2) + Math.pow(xyz[2] - own.xyz[2], 2));
        float[] features = {
                (float)(c * v2[0] + s * v2[2]), v2[1], (float)(-s * v2[0] + c * v2[2]),
                (float)(c * v8[0] + s * v8[2]), v8[1], (float)(-s * v8[0] + c * v8[2]),
                p2 == null ? 0 : 1, p8 == null ? 0 : 1, pose.sneak ? 1 : 0,
                (float)(Math.hypot(xyz[0], xyz[2]) / 35), (xyz[1] - openingY) / 10,
                (float)((-s * xyz[0] + c * xyz[2]) / 55), (float)Math.log1p(second / 30.0),
                visible / 6f, (float)(Math.min(distance, 200) / 50), local ? 1 : 0, pose.sword ? 1 : 0
        };
        return new Input(pose, features, new float[]{v2[0], 0, v2[2]});
    }

    private static List<Deque<Support>> supportBuckets() {
        List<Deque<Support>> buckets = new ArrayList<>(HORIZONS.length);
        for (int ignored : HORIZONS) buckets.add(new ArrayDeque<>());
        return buckets;
    }

    private void complete(int second, Map<UUID, Input> current) {
        for (int i = 0; i < HORIZONS.length; i++) {
            int issued = second - HORIZONS[i];
            if (issued % LABEL_STRIDE != 0) continue;
            Map<UUID, Input> issueFrame = frames.get(issued);
            if (issueFrame == null) continue;
            for (Map.Entry<UUID, Input> item : current.entrySet()) {
                UUID id = item.getKey();
                Input origin = issueFrame.get(id);
                Observation destination = item.getValue().observation;
                if (origin == null || !withinGrid(destination.xyz)) continue;
                Deque<Support> bucket = supports.computeIfAbsent(id, ignored -> supportBuckets()).get(i);
                bucket.addLast(new Support(issued, second, origin, destination));
                if (bucket.size() > SUPPORT_BUFFER) bucket.removeFirst();
            }
        }
    }

    public PersonalContext personalContext(UUID id, int horizon, int second) {
        int index = Arrays.binarySearch(HORIZONS, horizon);
        if (index < 0) throw new IllegalArgumentException("Unknown forecast horizon " + horizon);
        List<float[]> rawTrail = trails.get(id);
        float[][] trailArr = rawTrail == null ? new float[0][] : rawTrail.toArray(new float[0][]);
        List<Deque<Support>> buckets = supports.get(id);
        float[][] routeArr;
        if (buckets == null) {
            routeArr = new float[0][];
        } else {
            Deque<Support> bucket = buckets.get(index);
            List<float[]> rows = new ArrayList<>(bucket.size());
            for (Support support : bucket) if (support.due <= second) rows.add(support.routeRow);
            routeArr = rows.toArray(new float[0][]);
        }
        return new PersonalContext(second, trailArr, routeArr);
    }

    public float[][] examples(UUID id, int horizon, int second) {
        int index = Arrays.binarySearch(HORIZONS, horizon);
        if (index < 0) throw new IllegalArgumentException("Unknown forecast horizon " + horizon);
        List<Deque<Support>> buckets = supports.get(id);
        if (buckets == null) return new float[0][];
        Deque<Support> bucket = buckets.get(index);
        int cutoff = (second / SUPPORT_EPOCH) * SUPPORT_EPOCH;
        List<float[]> rows = new ArrayList<>(bucket.size());
        for (Support support : bucket) if (support.due <= cutoff) rows.add(support.values);
        return rows.subList(Math.max(0, rows.size() - SUPPORT_LIMIT), rows.size()).toArray(new float[0][]);
    }
}
