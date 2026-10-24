package lab;

/**
 * Independent, float-faithful conditional predictor for official 1.8.9
 * EntityLivingBase.moveEntityWithHeading (pr.g(FF)), ordinary loaded flat floor.
 *
 * Input is state AT g ENTRY, after the living update's .005 motion cutoff and
 * input damping. Do not apply that cutoff or another .98 input multiplier here.
 * This predicts the six motion/position doubles, AABB and collision flags at
 * g RETURN; it does not predict preceding keys/attacks/packets or later pushing.
 * All positions and floorTopY use absolute native world coordinates.
 *
 * Scope: a stationary full solid horizontal plane, uniform slipperiness, open
 * space above it, ordinary block landing callback (motionY=0), no horizontal
 * collision, mount, flight, liquid, web, ladder, or sneak edge-clamping. Callers
 * must establish that world/ability scope independently. Unsupported recorded
 * flags are rejected rather than silently approximated. This is not a general
 * collision engine and is deliberately separate from ComboModel.
 */
public final class ExactTravel {
    private ExactTravel() {}

    private static final float[] SIN_TABLE = new float[65536];
    static {
        // Exact expression order of MathHelper's pinned native initializer.
        for (int i = 0; i < SIN_TABLE.length; i++)
            SIN_TABLE[i] = (float) Math.sin((double) i * Math.PI * 2.0 / 65536.0);
    }

    public record Box(double minX, double minY, double minZ,
                      double maxX, double maxY, double maxZ) {
        public Box {
            finite(minX, minY, minZ, maxX, maxY, maxZ);
            if (minX > maxX || minY > maxY || minZ > maxZ)
                throw new IllegalArgumentException("Unordered native box");
        }
        private Box offset(double x, double y, double z) {
            return new Box(minX + x, minY + y, minZ + z,
                    maxX + x, maxY + y, maxZ + z);
        }
    }

    /** Immutable snapshot; retained AABB endpoints cannot be recovered from x/z exactly. */
    public record State(double x, double y, double z, double vx, double vy, double vz,
                        Box box, boolean onGround,
                        boolean isCollidedHorizontally, boolean isCollidedVertically,
                        boolean isCollided, boolean noClip, boolean isInWeb, boolean sneaking) {
        public State {
            finite(x, y, z, vx, vy, vz);
            if (box == null) throw new IllegalArgumentException("Native box is required");
        }
    }

    /** These are the raw float g arguments and getters, not ideal key magnitudes. */
    public record Input(float strafe, float forward, float yawDegrees,
                        float groundAcceleration, float airAcceleration) {
        public Input {
            finite(strafe, forward, yawDegrees, groundAcceleration, airAcceleration);
            if (groundAcceleration < 0 || airAcceleration < 0)
                throw new IllegalArgumentException("Negative acceleration");
        }
    }

    /** floorTopY=4 in the lab; the ordinary supporting block's Y is 3. */
    public record Environment(double floorTopY, float floorSlipperiness,
                              boolean loadedColumnBefore, boolean loadedColumnAfter,
                              boolean water, boolean lava, boolean ladder) {
        public Environment {
            finite(floorTopY, floorSlipperiness);
            if (!(floorSlipperiness > 0))
                throw new IllegalArgumentException("Nonpositive slipperiness");
        }
    }

    /**
     * Reproduce setPosition's NEW-box construction. Use for an actual native
     * setPosition or synthetic fixture only, never to replace a measured box
     * before each step: offsetting its two endpoints retains rounding history.
     */
    public static Box boxAt(double x, double y, double z, float width, float height) {
        finite(x, y, z, width, height);
        if (!(width > 0) || !(height > 0))
            throw new IllegalArgumentException("Nonpositive dimensions");
        float halfWidth = width / 2.0f;
        return new Box(x - (double) halfWidth, y, z - (double) halfWidth,
                x + (double) halfWidth, y + (double) height, z + (double) halfWidth);
    }

    public static State predict(State before, Input input, Environment environment) {
        if (before == null || input == null || environment == null)
            throw new IllegalArgumentException("Missing conditional state");
        requireSupported(before, environment);

        // pr.g: all products/divisions below are floats in this exact order.
        float friction = .91f;
        if (before.onGround) friction = environment.floorSlipperiness * .91f;
        float normalization = .16277136f / (friction * friction * friction);
        float acceleration = before.onGround
                ? input.groundAcceleration * normalization : input.airAcceleration;
        float strafe = input.strafe, forward = input.forward;
        double vx = before.vx, vy = before.vy, vz = before.vz;

        // pk.a(FFF), moveFlying. It rounds the rotated increment to float
        // BEFORE widening and adding it to the double motion component.
        float length = strafe * strafe + forward * forward;
        if (length >= 1.0E-4f) {
            length = (float) Math.sqrt((double) length);
            if (length < 1.0f) length = 1.0f;
            length = acceleration / length;
            strafe *= length;
            forward *= length;
            float yawRadians = input.yawDegrees * 3.1415927f / 180.0f;
            float sin = SIN_TABLE[(int) (yawRadians * 10430.378f) & 65535];
            float cos = SIN_TABLE[(int) (yawRadians * 10430.378f + 16384.0f) & 65535];
            vx += (double) (strafe * cos - forward * sin);
            vz += (double) (forward * cos + strafe * sin);
        }

        // Native recomputes this BEFORE movement, so takeoff uses ground
        // friction and landing uses air friction for this complete travel call.
        friction = .91f;
        if (before.onGround) friction = environment.floorSlipperiness * .91f;

        // pk.d(DDD): Y, X, Z clipping and separate AABB offsets. A full plane
        // clips only downward Y; no other box intersects in this scoped world.
        double requestedY = vy, clippedY = requestedY;
        if (requestedY < 0.0) {
            double gap = environment.floorTopY - before.box.minY;
            if (gap > clippedY) clippedY = gap;
        }
        Box box = before.box.offset(0.0, clippedY, 0.0);
        box = box.offset(vx, 0.0, 0.0);
        box = box.offset(0.0, 0.0, vz);

        // resetPositionToBB: do not substitute before.x + vx.
        double x = (box.minX + box.maxX) / 2.0;
        double y = box.minY;
        double z = (box.minZ + box.maxZ) / 2.0;
        boolean verticalCollision = requestedY != clippedY;
        boolean onGround = verticalCollision && requestedY < 0.0;
        if (verticalCollision) vy = 0.0; // ordinary Block.onLanded

        // Loaded-column branch and exact widened float damping constants.
        vy -= .08;
        vy *= .9800000190734863;
        vx *= (double) friction;
        vz *= (double) friction;
        return new State(x, y, z, vx, vy, vz, box, onGround,
                false, verticalCollision, verticalCollision, false, false, false);
    }

    private static void requireSupported(State state, Environment environment) {
        if (state.noClip || state.isInWeb || state.sneaking)
            throw new IllegalArgumentException("Unsupported noClip, web, or sneak state");
        if (environment.water || environment.lava || environment.ladder)
            throw new IllegalArgumentException("Unsupported liquid or ladder branch");
        if (!environment.loadedColumnBefore || !environment.loadedColumnAfter)
            throw new IllegalArgumentException("Unloaded-column gravity branch is unsupported");
        if (state.box.minY < environment.floorTopY)
            throw new IllegalArgumentException("Initial box penetrates declared floor");
    }

    private static void finite(double... values) {
        for (double value : values)
            if (!Double.isFinite(value)) throw new IllegalArgumentException("Nonfinite state");
    }
}
