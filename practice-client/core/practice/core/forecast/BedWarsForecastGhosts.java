package practice.core.forecast;

import practice.core.forecast.BedWarsPredictor.Volume;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Select local 3D high-mass areas around distinct peaks. */
public final class BedWarsForecastGhosts {
    private static final int SIDE = BedWarsPredictor.SIDE;
    private static final int LEVELS = BedWarsPredictor.LEVELS;
    private static final int MERGE_DISTANCE = 2 * BedWarsPredictor.TILE_WIDTH;
    private static final int MERGE_HEIGHT = 2 * BedWarsPredictor.HEIGHT_STEP;

    private BedWarsForecastGhosts() {}

    public static final class Marker {
        public final float x, y, z, probability;
        public final Map<Integer, Float> horizons = new LinkedHashMap<>();

        private Marker(Cluster cluster) {
            x = cluster.x;
            y = cluster.y;
            z = cluster.z;
            probability = cluster.mass;
        }
    }

    private static final class Cluster {
        final int horizon, bin;
        final float mass, x, y, z;

        Cluster(int horizon, int bin, float mass, Volume field) {
            this.horizon = horizon;
            this.bin = bin;
            this.mass = mass;
            x = field.x(bin) + .5f;
            y = field.y(bin);
            z = field.z(bin) + .5f;
        }
    }

    /** Each probability includes up to three adjacent bins per axis, once per horizon. */
    public static List<Marker> select(Volume[] fields, int[] horizons, int minimumPercent, int limit) {
        float minimum = minimumPercent / 100f;
        List<Cluster> candidates = new ArrayList<>();
        for (int h = 0; h < horizons.length; h++) {
            float[] remaining = fields[h].mass.clone();
            for (int count = 0; count < limit; count++) {
                Cluster cluster = best(remaining, fields[h], horizons[h]);
                if (cluster == null || cluster.mass < minimum) break;
                candidates.add(cluster);
                int x = cluster.bin / LEVELS / SIDE, z = cluster.bin / LEVELS % SIDE, y = cluster.bin % LEVELS;
                for (int row = Math.max(0, x - 1); row <= Math.min(SIDE - 1, x + 1); row++)
                    for (int col = Math.max(0, z - 1); col <= Math.min(SIDE - 1, z + 1); col++)
                        for (int floor = Math.max(0, y - 1); floor <= Math.min(LEVELS - 1, y + 1); floor++)
                            remaining[(row * SIDE + col) * LEVELS + floor] = 0;
            }
        }

        candidates.sort(Comparator.comparingDouble((Cluster cluster) -> cluster.mass).reversed()
                .thenComparingInt(cluster -> cluster.horizon));
        List<Marker> markers = new ArrayList<>();
        for (Cluster cluster : candidates) {
            Marker closest = null;
            float closestDistance = MERGE_DISTANCE * MERGE_DISTANCE;
            for (Marker marker : markers) {
                float dx = marker.x - cluster.x, dz = marker.z - cluster.z;
                float distance = dx * dx + dz * dz;
                if (Math.abs(marker.y - cluster.y) <= MERGE_HEIGHT && distance <= closestDistance) {
                    closest = marker;
                    closestDistance = distance;
                }
            }
            if (closest == null && markers.size() < limit) {
                closest = new Marker(cluster);
                markers.add(closest);
            }
            if (closest != null) closest.horizons.putIfAbsent(cluster.horizon, cluster.mass);
        }
        return markers;
    }

    private static Cluster best(float[] field, Volume volume, int horizon) {
        int winner = -1;
        float bestMass = 0;
        for (int bin = 0; bin < field.length; bin++) {
            float center = field[bin];
            if (center <= 0) continue;
            int x = bin / LEVELS / SIDE, z = bin / LEVELS % SIDE, y = bin % LEVELS;
            float mass = 0, peak = center;
            for (int row = Math.max(0,x-1); row <= Math.min(SIDE-1,x+1); row++) {
                for (int col = Math.max(0,z-1); col <= Math.min(SIDE-1,z+1); col++) {
                    int start = (row * SIDE + col) * LEVELS;
                    for (int floor = Math.max(0,y-1); floor <= Math.min(LEVELS-1,y+1); floor++) {
                        float chance = field[start + floor];
                        mass += chance;
                        peak = Math.max(peak, chance);
                    }
                }
            }
            if (center < peak) continue;
            if (mass > bestMass || mass == bestMass && winner >= 0 && center > field[winner]) {
                winner = bin;
                bestMass = mass;
            }
        }
        return winner < 0 ? null : new Cluster(horizon, winner, Math.min(1, bestMass), volume);
    }
}
