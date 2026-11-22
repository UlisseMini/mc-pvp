package practice.bridge;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.entity.Entity;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import org.lwjgl.opengl.GL11;
import practice.core.bow.BowBallistics.Shot;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** A depth-tested preview of the current launch direction and an estimated moving target intercept. */
final class BowAimPreview {
    private static final int PATH_COLOR = 0x83DAFA, HIT_COLOR = 0xFFE28C;

    private BowAimPreview() {}

    static final class Guide {
        final List<Vec3> points = new ArrayList<>();
        boolean blocked;
        Vec3 feet;
        double hitTicks, travelTicks;
    }

    static Guide trace(WorldClient world, Shot shot, double ticks, BowAim.Selection target) {
        Guide guide = new Guide();
        Vec3 from = new Vec3(shot.x, shot.y, shot.z);
        guide.points.add(from);
        AxisAlignedBB expanded = target == null ? null : target.bounds.func_72314_b(.3, .3, .3);
        for (int tick = 1; tick <= Math.ceil(ticks); tick++) {
            double time = Math.min(ticks, tick);
            Vec3 to = new Vec3(shot.xAt(time), shot.yAt(time), shot.zAt(time));
            if (!world.func_175667_e(new BlockPos(to))) { guide.blocked = true; break; }
            MovingObjectPosition wall = world.func_147447_a(from, to, true, false, false);
            double fraction = wall == null ? 1 : portion(from, to, wall.field_72307_f);
            if (expanded != null && tick < target.path.length) {
                double[] first = target.path[tick - 1], next = target.path[tick];
                Vec3 localFrom = reduced(from, first), localTo = reduced(to, next);
                MovingObjectPosition hit = expanded.func_72327_a(localFrom, localTo);
                double contact = expanded.func_72318_a(localFrom) ? 0 : hit == null ? 2
                        : portion(localFrom, localTo, hit.field_72307_f);
                if (contact <= fraction) {
                    guide.feet = new Vec3(first[0] + contact * (next[0] - first[0]),
                            first[1] + contact * (next[1] - first[1]), first[2] + contact * (next[2] - first[2]));
                    guide.hitTicks = tick - 1 + contact;
                    guide.travelTicks = guide.hitTicks;
                    guide.points.add(lerp(from, to, contact));
                    break;
                }
            }
            if (wall != null) {
                guide.points.add(wall.field_72307_f);
                guide.blocked = true;
                guide.travelTicks = tick - 1 + fraction * (time - tick + 1);
                break;
            }
            guide.points.add(to);
            guide.travelTicks = time;
            from = to;
        }
        return guide;
    }

    private static Vec3 reduced(Vec3 point, double[] offset) {
        return new Vec3(point.field_72450_a - offset[0], point.field_72448_b - offset[1], point.field_72449_c - offset[2]);
    }

    private static double portion(Vec3 from, Vec3 to, Vec3 hit) {
        double length = from.func_72438_d(to);
        return length < 1e-9 ? 0 : Math.min(1, from.func_72438_d(hit) / length);
    }

    private static Vec3 lerp(Vec3 a, Vec3 b, double fraction) {
        return new Vec3(a.field_72450_a + fraction * (b.field_72450_a - a.field_72450_a),
                a.field_72448_b + fraction * (b.field_72448_b - a.field_72448_b),
                a.field_72449_c + fraction * (b.field_72449_c - a.field_72449_c));
    }

    static void draw(Minecraft mc, Guide guide, boolean path, boolean marker, float partial) {
        Entity camera = mc.func_175606_aa();
        if (camera == null || !path && (!marker || guide.feet == null)) return;
        double x = camera.field_70142_S + (camera.field_70165_t - camera.field_70142_S) * partial;
        double y = camera.field_70137_T + (camera.field_70163_u - camera.field_70137_T) * partial;
        double z = camera.field_70136_U + (camera.field_70161_v - camera.field_70136_U) * partial;
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT
                | GL11.GL_CURRENT_BIT | GL11.GL_LINE_BIT);
        GL11.glPushMatrix();
        try {
            GL11.glTranslated(-x, -y, -z);
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            GL11.glDisable(GL11.GL_LIGHTING);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glDisable(GL11.GL_ALPHA_TEST);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glEnable(GL11.GL_DEPTH_TEST);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GL11.glDepthMask(false);
            GL11.glLineWidth(2);
            if (path && guide.points.size() > 1) {
                GL11.glBegin(GL11.GL_LINE_STRIP);
                for (int i = 0; i < guide.points.size(); i++) {
                    Vec3 point = guide.points.get(i);
                    color(PATH_COLOR, (float)(.86 - .41 * i / (guide.points.size() - 1)));
                    GL11.glVertex3d(point.field_72450_a, point.field_72448_b, point.field_72449_c);
                }
                GL11.glEnd();
            }
            if (marker && guide.feet != null)
                intercept(mc.func_175598_ae(), guide, guide.feet.func_72438_d(new Vec3(x, y, z)));
        } finally {
            GL11.glPopMatrix();
            GL11.glPopAttrib();
        }
    }

    private static void intercept(RenderManager camera, Guide guide, double distance) {
        Vec3 feet = guide.feet;
        GL11.glPushMatrix();
        try {
            GL11.glTranslated(feet.field_72450_a, feet.field_72448_b, feet.field_72449_c);
            color(HIT_COLOR, .9f);
            for (double y : new double[]{.025, 1.80}) {
                GL11.glBegin(GL11.GL_LINE_LOOP);
                GL11.glVertex3d(-.30, y, -.30); GL11.glVertex3d(.30, y, -.30);
                GL11.glVertex3d(.30, y, .30); GL11.glVertex3d(-.30, y, .30);
                GL11.glEnd();
            }
            GL11.glBegin(GL11.GL_LINES);
            for (int x = -1; x <= 1; x += 2) for (int z = -1; z <= 1; z += 2) {
                GL11.glVertex3d(.30 * x, .025, .30 * z);
                GL11.glVertex3d(.30 * x, 1.80, .30 * z);
            }
            GL11.glEnd();
            GL11.glTranslated(0, 1.99, 0);
            GL11.glRotatef(-camera.field_78735_i, 0, 1, 0);
            GL11.glRotatef(camera.field_78732_j, 1, 0, 0);
            double size = Math.min(.12, .028 + distance * .0015);
            GL11.glScaled(-size, size, size);
            String eta = String.format(Locale.ROOT, "%.2fs", guide.hitTicks / 20);
            BedWarsGhostDrawing.label(eta, 0, HIT_COLOR, 1);
        } finally {
            GL11.glPopMatrix();
        }
    }

    private static void color(int rgb, float alpha) {
        GL11.glColor4f((rgb >> 16 & 255) / 255f, (rgb >> 8 & 255) / 255f, (rgb & 255) / 255f, alpha);
    }
}
