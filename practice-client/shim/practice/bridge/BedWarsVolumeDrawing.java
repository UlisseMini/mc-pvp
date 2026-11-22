package practice.bridge;

import org.lwjgl.opengl.GL11;
import practice.core.forecast.BedWarsPredictor;
import practice.core.forecast.BedWarsPredictor.Volume;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

final class BedWarsVolumeDrawing {
    private static final int SIDE = BedWarsPredictor.SIDE, LEVELS = BedWarsPredictor.LEVELS;
    private static final int TILE = BedWarsPredictor.TILE_WIDTH, STEP = BedWarsPredictor.HEIGHT_STEP;

    private BedWarsVolumeDrawing() {}

    static int[] strongest(Volume map) {
        List<Integer> bins = new ArrayList<>();
        for (int bin = 0; bin < map.mass.length; bin++) if (map.mass[bin] >= .0012f) bins.add(bin);
        bins.sort(Comparator.<Integer>comparingDouble(i -> map.mass[i]).reversed());
        return bins.stream().limit(80).mapToInt(i -> i).toArray();
    }

    /** Draw depth-tested, separated height bands at their forecast world coordinates. */
    static void draw(Volume map, int[] bins, int tint, int layer, int layers, double cameraX, double cameraZ, float opacity) {
        GL11.glBegin(GL11.GL_QUADS);
        for (int bin : bins) {
            int tile = bin / LEVELS;
            double left = BedWarsPredictor.MIN_BLOCK + (tile / SIDE) * TILE;
            double north = BedWarsPredictor.MIN_BLOCK + (tile % SIDE) * TILE;
            if (Math.hypot(left + TILE/2. - cameraX, north + TILE/2. - cameraZ) > 148) continue;
            double bottom = BedWarsPredictor.MIN_Y + (bin % LEVELS) * STEP + .12;
            double top = bottom + STEP - .24;
            double stripe = (TILE - .3)/layers;
            left += .15 + layer * stripe;
            double right = left + stripe - .11;
            north += .15;
            double south = north + TILE - .3;

            float alpha = Math.min(.25f, .45f * (float)Math.sqrt(map.mass[bin])) * opacity;
            GL11.glColor4f((tint >> 16 & 255)/255f, (tint >> 8 & 255)/255f, (tint & 255)/255f, alpha);
            face(left, bottom, north, right, bottom, north, right, top, north, left, top, north);
            face(left, bottom, south, left, top, south, right, top, south, right, bottom, south);
            face(left, bottom, north, left, top, north, left, top, south, left, bottom, south);
            face(right, bottom, north, right, bottom, south, right, top, south, right, top, north);
            face(left, top, north, right, top, north, right, top, south, left, top, south);
            face(left, bottom, north, left, bottom, south, right, bottom, south, right, bottom, north);
        }
        GL11.glEnd();
    }

    private static void face(double... coordinates) {
        for (int i = 0; i < coordinates.length; i += 3)
            GL11.glVertex3d(coordinates[i], coordinates[i + 1], coordinates[i + 2]);
    }
}
