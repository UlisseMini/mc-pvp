package practice.core.bow;

/**
 * Exact 1.8.9 Void-Knockback & Hit-Equivalent Tactical Value Scorer.
 * Simulates 14 ticks of 1.8.9 knockBack + Punch enchantment + gravity + solid wall/floor collisions
 * + conservative air counter-strafe to determine whether a hit knocks the target into the void
 * or off high ground.
 *
 * Hit-Equivalent Values:
 * - Bow: 1.0x normal hit, 8.0x void knock-off (+ up to 0.8x high-drop fall bonus, min 5.0x lethal HP finish)
 * - Snowball / Egg: 0.4x normal KB/interrupt, 10.0x void knock-off (+ up to 0.32x high-drop bonus)
 */
public final class VoidKbValue {
    public static final double BOW_BASE = 1.0;
    public static final double BOW_VOID = 8.0;
    public static final double THROW_BASE = 0.4;
    public static final double THROW_VOID = 10.0;

    private static final int SIM_TICKS = 14;
    private static final int VOID_SCAN_DEPTH = 18;
    private static final double HALF_W = 0.29;
    private static final double AIR_COUNTER_ACCEL = 0.015;
    private static final double GROUND_COUNTER_ACCEL = 0.05;

    private VoidKbValue() {}

    /** Caller MUST return true for unloaded chunks so we never hallucinate void outside loaded world! */
    public interface SolidQuery {
        boolean isSolid(int x, int y, int z);
    }

    public static final class Outcome {
        public final boolean voidKill;
        public final double dropBlocks;

        public Outcome(boolean voidKill, double dropBlocks) {
            this.voidKill = voidKill;
            this.dropBlocks = dropBlocks;
        }
    }

    public static final class Score {
        public final double voidProb;
        public final double fallBonus;
        public final double value;

        public Score(double voidProb, double fallBonus, double value) {
            this.voidProb = voidProb;
            this.fallBonus = fallBonus;
            this.value = value;
        }
    }

    /**
     * Exact single-point 1.8.9 knockback trajectory rollout (mirrored 1-to-1 in Python void_and_wait.py).
     */
    public static Outcome simulate(double px, double py, double pz,
                                   double tvx, double tvy, double tvz,
                                   double ivx, double ivz,
                                   int punchLevel, SolidQuery solid) {
        if (solid == null) return new Outcome(false, 0.0);
        // Clamp predicted target velocity to physical Minecraft player speeds
        double vx = clamp(tvx, -0.75, 0.75);
        double vy = clamp(tvy, -1.50, 0.55);
        double vz = clamp(tvz, -0.75, 0.75);

        double h = Math.hypot(ivx, ivz);
        double dx = h > 1e-6 ? ivx / h : 0.0;
        double dz = h > 1e-6 ? ivz / h : 0.0;

        // Exact 1.8.9 EntityLivingBase.knockBack: halve current motion, add 0.4 horizontal + min(0.4, +0.4) vertical
        vx = vx * 0.5 + dx * 0.4;
        vz = vz * 0.5 + dz * 0.4;
        vy = Math.min(0.4, vy * 0.5 + 0.4);

        // Exact 1.8.9 EntityArrow Punch enchantment knockback (addVelocity)
        if (punchLevel > 0) {
            vx += dx * (0.6 * punchLevel);
            vz += dz * (0.6 * punchLevel);
            vy += 0.1;
        }

        double x = px, y = py, z = pz;
        boolean onGround = hasFloor(x, y, z, solid);

        for (int t = 0; t < SIM_TICKS; t++) {
            // Apply conservative player counter-strafe opposite KB direction
            double counter = onGround ? GROUND_COUNTER_ACCEL : AIR_COUNTER_ACCEL;
            vx -= dx * counter;
            vz -= dz * counter;

            // Horizontal movement with solid wall check (X then Z, matching Minecraft axis-separated AABB move)
            double nx = x + vx;
            if (!hitsWall(nx, y, z, solid)) {
                x = nx;
            } else {
                vx = 0.0;
            }
            double nz = z + vz;
            if (!hitsWall(x, y, nz, solid)) {
                z = nz;
            } else {
                vz = 0.0;
            }

            // Vertical movement with floor & ceiling check
            double ny = y + vy;
            if (vy <= 0.0) {
                if (hasFloor(x, ny, z, solid)) {
                    y = Math.floor(ny - 0.05) + 1.0;
                    vy = 0.0;
                    onGround = true;
                } else {
                    y = ny;
                    onGround = false;
                }
            } else {
                if (hitsCeiling(x, ny, z, solid)) {
                    vy = 0.0;
                } else {
                    y = ny;
                }
                onGround = false;
            }

            // Exact 1.8.9 gravity & drag
            vy = (vy - 0.08) * 0.98;
            double drag = onGround ? 0.546 : 0.91;
            vx *= drag;
            vz *= drag;

            if (y < -4.0) return new Outcome(true, py - y);
        }

        double drop = Math.max(0.0, py - y);
        if (onGround) {
            return new Outcome(false, drop);
        }

        // Still airborne after 14 ticks: scan down from current footprint + 2 extra drift ticks
        double fx = x + vx * 1.5;
        double fz = z + vz * 1.5;
        if (!hitsWall(fx, y, fz, solid)) {
            x = fx;
            z = fz;
        }
        int baseY = (int)Math.floor(y);
        int floorDist = scanFloorDistance(x, baseY, z, solid);
        if (floorDist < 0) {
            return new Outcome(true, Math.max(drop, VOID_SCAN_DEPTH));
        }
        double totalDrop = Math.max(0.0, py - (baseY - floorDist + 1.0));
        return new Outcome(false, totalDrop);
    }

    /**
     * Smooth 3-point spatial evaluation along the knockback axis so EV varies smoothly near bridge edges
     * instead of flickering 0/1 when uncertainty straddles an edge.
     */
    public static Score scoreMode(double mx, double my, double mz,
                                  double tvx, double tvy, double tvz,
                                  double sx, double sz,
                                  double ivx, double ivz,
                                  boolean isThrowable, int punchLevel,
                                  double targetHealth, double expectedBowDamage,
                                  SolidQuery solid) {
        if (solid == null) {
            double base = isThrowable ? THROW_BASE : BOW_BASE;
            if (!isThrowable && targetHealth > 0.0 && targetHealth <= expectedBowDamage) {
                base = Math.max(base, 5.0);
            }
            return new Score(0.0, 0.0, base);
        }

        double h = Math.hypot(ivx, ivz);
        double dx = h > 1e-6 ? ivx / h : 0.0;
        double dz = h > 1e-6 ? ivz / h : 0.0;
        double sigmaAlong = Math.min(1.2, Math.hypot(dx * sx, dz * sz));

        double voidProb = 0.0;
        double fallBonus = 0.0;
        if (sigmaAlong < 0.18) {
            Outcome o = simulate(mx, my, mz, tvx, tvy, tvz, ivx, ivz, punchLevel, solid);
            voidProb = o.voidKill ? 1.0 : 0.0;
            fallBonus = dropBonus(o.dropBlocks);
        } else {
            double step = 0.55 * sigmaAlong;
            final double[] offsets = {-step, 0.0, step};
            final double[] weights = {0.25, 0.50, 0.25};
            for (int i = 0; i < 3; i++) {
                double ox = mx + dx * offsets[i];
                double oz = mz + dz * offsets[i];
                Outcome o = simulate(ox, my, oz, tvx, tvy, tvz, ivx, ivz, punchLevel, solid);
                if (o.voidKill) voidProb += weights[i];
                else fallBonus += weights[i] * dropBonus(o.dropBlocks);
            }
        }

        double value;
        if (isThrowable) {
            value = THROW_BASE + (THROW_VOID - THROW_BASE) * voidProb + (1.0 - voidProb) * (0.4 * fallBonus);
        } else {
            value = BOW_BASE + (BOW_VOID - BOW_BASE) * voidProb + (1.0 - voidProb) * fallBonus;
            if (targetHealth > 0.0 && targetHealth <= expectedBowDamage) {
                value = Math.max(value, 5.0);
            }
        }
        return new Score(voidProb, fallBonus, value);
    }

    public static double expectedBowDamage(double speed, int powerLevel) {
        if (speed <= 0.1) return 0.0;
        double base = Math.ceil(speed * 2.0);
        if (speed >= 2.95) base += 2.0; // critical charge bonus
        if (powerLevel > 0) base += powerLevel * 0.5 + 0.5;
        // Conservative armor reduction estimate (~35% for leather/iron mix in BedWars/SkyWars)
        return base * 0.65;
    }

    private static double dropBonus(double dropBlocks) {
        if (dropBlocks >= 9.0) return 0.8;
        if (dropBlocks >= 5.0) return 0.4;
        return 0.0;
    }

    private static boolean hasFloor(double x, double y, double z, SolidQuery solid) {
        int by = (int)Math.floor(y - 0.05);
        if (by < 0) return false;
        return solid.isSolid((int)Math.floor(x - HALF_W), by, (int)Math.floor(z - HALF_W))
                || solid.isSolid((int)Math.floor(x + HALF_W), by, (int)Math.floor(z - HALF_W))
                || solid.isSolid((int)Math.floor(x - HALF_W), by, (int)Math.floor(z + HALF_W))
                || solid.isSolid((int)Math.floor(x + HALF_W), by, (int)Math.floor(z + HALF_W));
    }

    private static boolean hitsWall(double x, double y, double z, SolidQuery solid) {
        int yLo = (int)Math.floor(y + 0.25);
        int yHi = (int)Math.floor(y + 1.45);
        int x0 = (int)Math.floor(x - HALF_W), x1 = (int)Math.floor(x + HALF_W);
        int z0 = (int)Math.floor(z - HALF_W), z1 = (int)Math.floor(z + HALF_W);
        for (int by = yLo; by <= yHi; by++) {
            if (by < 0) continue;
            if (solid.isSolid(x0, by, z0) || solid.isSolid(x1, by, z0)
                    || solid.isSolid(x0, by, z1) || solid.isSolid(x1, by, z1)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hitsCeiling(double x, double y, double z, SolidQuery solid) {
        int by = (int)Math.floor(y + 1.80);
        if (by < 0) return false;
        return solid.isSolid((int)Math.floor(x - HALF_W), by, (int)Math.floor(z - HALF_W))
                || solid.isSolid((int)Math.floor(x + HALF_W), by, (int)Math.floor(z - HALF_W))
                || solid.isSolid((int)Math.floor(x - HALF_W), by, (int)Math.floor(z + HALF_W))
                || solid.isSolid((int)Math.floor(x + HALF_W), by, (int)Math.floor(z + HALF_W));
    }

    private static int scanFloorDistance(double x, int baseY, double z, SolidQuery solid) {
        int x0 = (int)Math.floor(x - HALF_W), x1 = (int)Math.floor(x + HALF_W);
        int z0 = (int)Math.floor(z - HALF_W), z1 = (int)Math.floor(z + HALF_W);
        for (int dy = 0; dy <= VOID_SCAN_DEPTH; dy++) {
            int by = baseY - dy;
            if (by < 0) return -1;
            if (solid.isSolid(x0, by, z0) || solid.isSolid(x1, by, z0)
                    || solid.isSolid(x0, by, z1) || solid.isSolid(x1, by, z1)) {
                return dy;
            }
        }
        return -1;
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
