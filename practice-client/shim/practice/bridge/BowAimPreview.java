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
import practice.core.bow.BowBallistics;
import practice.core.bow.BowBallistics.Shot;
import practice.core.bow.BowNeuralMotion;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** A depth-tested preview of the current launch direction, moving target intercept, and multi-mode landing ghosts. */
final class BowAimPreview {
    private static final int PATH_COLOR = 0x83DAFA, HIT_COLOR = 0xFFE28C;
    private static final int GHOST_AIMED_COLOR = 0xFFE28C;
    private static final int GHOST_AIMED_VOID_COLOR = 0xFFAD42;
    private static final int GHOST_VOID_COLOR = 0xFF6B4A;
    private static final int GHOST_SAFE_COLOR = 0x76D8FF;
    private static final double MIN_GHOST_WEIGHT = 0.06;

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
            // Match exact 1.8.9 EntityArrow/EntityThrowable rayTraceBlocks(from, to, stopOnLiquid=false, ignoreNonSolid=true, false)
            MovingObjectPosition wall = world.func_147447_a(from, to, false, true, false);
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
        draw(mc, guide, path, marker, false, null, partial);
    }

    static void draw(Minecraft mc, Guide guide, boolean path, boolean marker,
                     boolean showGhosts, BowAim.Selection selection, float partial) {
        Entity camera = mc.func_175606_aa();
        boolean hasGhosts = showGhosts && selection != null && selection.prediction != null && selection.ideal != null;
        if (camera == null || (!path && (!marker || guide.feet == null) && !hasGhosts)) return;
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
            if (hasGhosts) {
                drawModeGhosts(mc.func_175598_ae(), selection, x, y, z);
            }
            if (marker && guide.feet != null)
                intercept(mc.func_175598_ae(), guide, guide.feet.func_72438_d(new Vec3(x, y, z)));
        } finally {
            GL11.glPopMatrix();
            GL11.glPopAttrib();
        }
    }

    /** Render translucent player silhouettes at ALL top predicted landing modes (pi_k >= 6%) at projectile arrival time. */
    private static void drawModeGhosts(RenderManager camera, BowAim.Selection sel,
                                       double camX, double camY, double camZ) {
        BowNeuralMotion.Prediction pred = sel.prediction;
        int maxBase = BowBallistics.MAX_TICKS;
        int maxAllowed = sel.throwable ? BowBallistics.MAX_THROW_TICKS : maxBase;
        double tEval = Math.max(0.0, Math.min(maxAllowed, sel.ideal.time + sel.leadShift));
        int K = pred.weights.length;

        for (int k = 0; k < K; k++) {
            double w = pred.weights[k];
            if (w < MIN_GHOST_WEIGHT && k != sel.aimedMode) continue;
            double gx, gy, gz;
            if (tEval <= maxBase) {
                int lo = (int)tEval;
                int hi = Math.min(maxBase, lo + 1);
                double frac = tEval - lo;
                double[] pLo = pred.paths[k][lo], pHi = pred.paths[k][hi];
                gx = pLo[0] + frac * (pHi[0] - pLo[0]);
                gy = pLo[1] + frac * (pHi[1] - pLo[1]);
                gz = pLo[2] + frac * (pHi[2] - pLo[2]);
            } else {
                double extra = tEval - maxBase;
                double[] pLast = pred.paths[k][maxBase], pRef = pred.paths[k][maxBase - 6];
                gx = pLast[0] + (pLast[0] - pRef[0]) / 6.0 * extra;
                gy = pLast[1] + (pLast[1] - pRef[1]) / 6.0 * extra;
                gz = pLast[2] + (pLast[2] - pRef[2]) / 6.0 * extra;
            }

            double dx = gx - camX, dy = gy - camY, dz = gz - camZ;
            double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (dist <= 1.5 || dist > 96.0) continue;

            boolean isAimed = (k == sel.aimedMode);
            boolean isVoid = sel.modeVoids != null && k < sel.modeVoids.length && sel.modeVoids[k] >= 0.45;
            double uncovered = (sel.modeUncovered != null && k < sel.modeUncovered.length) ? sel.modeUncovered[k] : 1.0;

            int tint = isAimed ? (isVoid ? GHOST_AIMED_VOID_COLOR : GHOST_AIMED_COLOR)
                    : (isVoid ? GHOST_VOID_COLOR : GHOST_SAFE_COLOR);
            float probScale = (float)Math.max(0.15, Math.min(1.0, w));
            float coverDim = (float)(0.35 + 0.65 * Math.max(0.0, Math.min(1.0, uncovered)));
            float fade = (float)Math.min(1.0, (dist - 1.5) / 3.5) * coverDim;
            float bodyAlpha = fade * (isAimed ? (0.28f + 0.46f * probScale) : (0.14f + 0.42f * probScale));
            float eyeAlpha = fade * (isAimed ? (0.48f + 0.44f * probScale) : (0.26f + 0.42f * probScale));

            GL11.glPushMatrix();
            try {
                GL11.glTranslated(gx, gy, gz);
                // Aimed mode gets a clean floor/feet ring so it stands out immediately
                if (isAimed) {
                    GL11.glLineWidth(2.2f);
                    color(tint, fade * 0.85f);
                    GL11.glBegin(GL11.GL_LINE_LOOP);
                    GL11.glVertex3d(-.32, .03, -.32); GL11.glVertex3d(.32, .03, -.32);
                    GL11.glVertex3d(.32, .03, .32); GL11.glVertex3d(-.32, .03, .32);
                    GL11.glEnd();
                }

                GL11.glRotatef(-camera.field_78735_i, 0, 1, 0);
                GL11.glBegin(GL11.GL_QUADS);
                color(tint, bodyAlpha);
                quad(-.24, 1.40, .24, 1.88);
                quad(-.25, .74, .25, 1.37);
                quad(-.46, .78, -.28, 1.37);
                quad(.28, .78, .46, 1.37);
                quad(-.23, .04, -.022, .71);
                quad(.022, .04, .23, .71);
                color(tint, eyeAlpha);
                quad(-.16, 1.60, -.07, 1.65);
                quad(.07, 1.60, .16, 1.65);
                GL11.glEnd();

                GL11.glTranslated(0, 2.02, 0);
                GL11.glRotatef(camera.field_78732_j, 1, 0, 0);
                double unit = Math.min(.125, .024 + dist * .0013);
                GL11.glScaled(-unit, unit, unit);
                String tag = Math.round(w * 100.0) + "%" + (isVoid ? " V" : "");
                BedWarsGhostDrawing.label(tag, 0, tint, Math.max(0.5f, fade));
            } finally {
                GL11.glPopMatrix();
            }
        }
    }

    private static void quad(double left, double bottom, double right, double top) {
        GL11.glVertex3d(left, bottom, 0);
        GL11.glVertex3d(right, bottom, 0);
        GL11.glVertex3d(right, top, 0);
        GL11.glVertex3d(left, top, 0);
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
