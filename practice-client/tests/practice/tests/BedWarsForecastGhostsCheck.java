package practice.tests;

import practice.core.forecast.BedWarsForecastGhosts;
import practice.core.forecast.BedWarsForecastGhosts.Marker;
import practice.core.forecast.BedWarsPredictor;
import practice.core.forecast.BedWarsPredictor.Volume;

import java.util.Arrays;
import java.util.List;

public final class BedWarsForecastGhostsCheck {
    private static final int SIDE = BedWarsPredictor.SIDE, LEVELS = BedWarsPredictor.LEVELS;
    private static int checks;

    private static void check(boolean valid, String message) {
        checks++;
        if (!valid) throw new AssertionError(message);
    }

    private static void close(float actual, float target, String message) {
        check(Math.abs(actual - target) < 1e-6, message + " expected " + target + " got " + actual);
    }

    private static void set(Volume map, int x, int z, int level, float chance) {
        map.mass[(x * SIDE + z) * LEVELS + level] = chance;
    }

    private static List<Marker> select(int minimum, int limit, Volume... fields) {
        return BedWarsForecastGhosts.select(fields, new int[]{10, 60}, minimum, limit);
    }

    public static void main(String[] args) {
        Volume soon = new Volume(), later = new Volume();
        set(soon, 5, 5, 35, .31f);
        set(soon, 5, 6, 36, .10f);
        set(soon, 6, 5, 35, .09f);
        set(soon, 12, 12, 35, .25f);
        set(soon, 0, 0, 35, .14f);
        set(later, 5, 6, 36, .33f);
        set(later, 5, 5, 35, .05f);
        set(later, 1, 13, 35, .27f);
        List<Marker> markers = select(25, 3, soon, later);
        check(markers.size() == 3, "nearby horizons share one marker and distant sites remain distinct");
        Marker first = markers.get(0);
        close(first.horizons.get(10), .50f, "adjacent volumes are added to one hotspot");
        close(first.horizons.get(60), .38f, "later hotspot keeps its own probability");
        close(first.probability, .50f, "body uses the strongest horizon");
        close(first.y, 78f, "body uses its predicted altitude");
        check(markers.get(2).horizons.containsKey(10), "a hotspot at the threshold qualifies");
        check(select(26, 3, soon, later).size() == 2, "raising the threshold removes weak sites");
        check(select(25, 1, soon, later).get(0).horizons.size() == 2, "cap keeps the strongest body and labels");

        Volume flat = new Volume(), weak = new Volume();
        Arrays.fill(flat.mass, 1f / flat.mass.length);
        check(select(25, 3, flat, flat).isEmpty(), "broad density produces no ghosts");
        set(weak, 6, 5, 35, .14f);
        check(select(25, 3, weak, weak).isEmpty(), "weak horizons cannot add up across threshold");
        Volume separated = new Volume(), stacked = new Volume();
        set(separated, 5, 5, 35, .40f);
        set(separated, 7, 7, 35, .35f);
        check(select(25, 3, separated, weak).size() == 2, "distinct horizontal peaks stay separate");
        set(stacked, 5, 5, 39, .42f);
        List<Marker> above = select(25, 3, separated, stacked);
        check(above.size() == 3 && above.get(0).y == 94f && above.get(1).y == 78f,
                "vertically separated forecasts at the same X/Z have independent bodies");
        check(select(0, 3, new Volume(), new Volume()).isEmpty(), "empty fields don't have a marker");
        check(select(100, 3, soon, later).isEmpty(), "100 percent hides partial probabilities");

        // Multi-player union preserves per-player peak probability instead of 1/N dilution.
        Volume p1 = new Volume(), p2 = new Volume(), combined = new Volume();
        set(p1, 3, 3, 35, .60f);
        set(p2, 11, 11, 35, .55f);
        combined.unionWith(p1);
        combined.unionWith(p2);
        List<Marker> unionMarkers = BedWarsForecastGhosts.select(new Volume[]{combined}, new int[]{10}, 50, 4);
        check(unionMarkers.size() == 2, "union retains both players' 50%+ hotspots without 1/N dilution");

        // Terrain masking reweights void voxels down and snaps feet onto walkable terrain.
        Volume masked = new Volume();
        set(masked, 2, 2, 35, .70f); // void voxel
        set(masked, 8, 8, 35, .30f); // island voxel
        float[] tMask = new float[BedWarsPredictor.VOXELS];
        int[] tFeet = new int[BedWarsPredictor.VOXELS];
        int islandBin = (8 * SIDE + 8) * LEVELS + 35;
        tMask[islandBin] = 1f;
        tFeet[islandBin] = BedWarsPredictor.packFeet(4, 77, 5);
        masked.applyTerrain(tMask, tFeet, 0.02f);
        check(masked.mass[islandBin] > 0.90f, "walkable island receives >90% probability after terrain masking");
        check(masked.x(islandBin) == 4 && masked.y(islandBin) == 77 && masked.z(islandBin) == 5, "ghost snaps to walkable block");

        System.out.println("BedWarsForecastGhostsCheck passed: " + checks + " checks");
    }
}
