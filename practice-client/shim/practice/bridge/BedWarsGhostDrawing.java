package practice.bridge;

import net.minecraft.client.renderer.entity.RenderManager;
import org.lwjgl.opengl.GL11;
import practice.core.forecast.BedWarsForecastGhosts.Marker;

import java.util.Map;
import java.util.function.IntUnaryOperator;

final class BedWarsGhostDrawing {
    private static final String CHARACTERS = "0123456789s%.";
    private static final String[] PIXELS = {
        "111101101101111", "010110010010111", "111001111100111",
        "111001111001111", "101101111001001", "111100111001111",
        "111100111101111", "111001010010010", "111101111101111",
        "111101111001111", "000111110001110", "101001010100101", "000000000000010"
    };

    private BedWarsGhostDrawing() {}

    /** The caller supplies the depth-tested, untextured world render state. */
    static void draw(RenderManager camera, Marker marker, IntUnaryOperator colorFor,
                     double cameraX, double cameraY, double cameraZ) {
        double dx = marker.x - cameraX, dy = marker.y - cameraY, dz = marker.z - cameraZ;
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (distance <= 1.5 || dx * dx + dz * dz > 148 * 148) return;
        double scale = 1 + Math.min(.75, distance * .010);
        float fade = (float)Math.min(1, (distance - 1.5) / 4);
        int tint = colorFor.applyAsInt(marker.horizons.keySet().iterator().next());

        GL11.glPushMatrix();
        try {
            GL11.glTranslated(marker.x, marker.y, marker.z);
            GL11.glRotatef(-camera.field_78735_i, 0, 1, 0);
            GL11.glScaled(scale, scale, scale);
            GL11.glBegin(GL11.GL_QUADS);
            color(tint, fade * (.18f + .49f * marker.probability));
            quad(-.24, 1.40, .24, 1.88, 0);
            quad(-.25, .74, .25, 1.37, 0);
            quad(-.46, .78, -.28, 1.37, 0);
            quad(.28, .78, .46, 1.37, 0);
            quad(-.23, .04, -.022, .71, 0);
            quad(.022, .04, .23, .71, 0);
            color(tint, fade * (.33f + .50f * marker.probability));
            quad(-.16, 1.60, -.07, 1.65, 0);
            quad(.07, 1.60, .16, 1.65, 0);
            GL11.glEnd();

            GL11.glTranslated(0, 2.03, 0);
            GL11.glRotatef(camera.field_78732_j, 1, 0, 0);
            double unit = Math.min(.135, .026 + distance * .00135) / scale;
            GL11.glScaled(-unit, unit, unit);
            labels(marker, colorFor, fade);
        } finally {
            GL11.glPopMatrix();
        }
    }

    private static void labels(Marker marker, IntUnaryOperator colorFor, float fade) {
        int bottom = 0;
        for (Map.Entry<Integer, Float> entry : marker.horizons.entrySet()) {
            int horizon = entry.getKey();
            String text = horizon + "s " + Math.round(entry.getValue() * 100) + "%";
            label(text, bottom, colorFor.applyAsInt(horizon), fade);
            bottom += 8;
        }
    }

    static void label(String text, int bottom, int tint, float fade) {
        double left = -text.length() * 2.0;
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glColor4f(.035f, .055f, .075f, fade * .76f);
        quad(left - 1, bottom - 1, -left + 1, bottom + 6, 0);
        color(tint, fade * .96f);
        for (int i = 0; i < text.length(); i++) {
            int glyph = CHARACTERS.indexOf(text.charAt(i));
            if (glyph < 0) continue;
            double x = left + i * 4;
            for (int p = 0; p < 15; p++) {
                if (PIXELS[glyph].charAt(p) == '0') continue;
                double y = bottom + 4 - p / 3;
                quad(x + p % 3, y, x + p % 3 + 1, y + 1, 0);
            }
        }
        GL11.glEnd();
    }

    private static void color(int rgb, float alpha) {
        GL11.glColor4f((rgb >> 16 & 255) / 255f, (rgb >> 8 & 255) / 255f, (rgb & 255) / 255f, alpha);
    }

    private static void quad(double left, double bottom, double right, double top, double z) {
        GL11.glVertex3d(left, bottom, z);
        GL11.glVertex3d(right, bottom, z);
        GL11.glVertex3d(right, top, z);
        GL11.glVertex3d(left, top, z);
    }
}
