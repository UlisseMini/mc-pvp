import java.util.List;
import java.util.Locale;

// Run with JDK 17+: java docs/MovementGuide.java
// Readable examples for ordinary unmounted players on loaded, dry, solid blocks.
public class MovementGuide {
    record Vec(double x, double y, double z) {
        Vec plus(Vec v) { return new Vec(x + v.x, y + v.y, z + v.z); }
        Vec brakeXZ() { return new Vec(x * 0.6, y, z * 0.6); }
    }

    static final Vec ZERO = new Vec(0, 0, 0);
    record Input(float strafe, float forward) {}
    record Keys(boolean w, boolean s, boolean a, boolean d,
                boolean sneak, boolean sprint, boolean jump) {}
    static final Keys W_AND_SPRINT = new Keys(true, false, false, false, false, true, false);
    enum Ray { ENTITY, BLOCK, MISS }
    enum Hit { REJECTED, UPGRADE, FRESH }
    record Damage(Hit kind, float beforeMitigation) {}
    record Move(Vec displacement, Vec savedMotion, boolean grounded) {}

    static class Player {
        Vec motion = ZERO;
        Box box;
        float yaw, airAcceleration = 0.02f, previousForward;
        boolean grounded, hitWall, sprinting, previousSneak, usingItem, blind;
        int food = 20, sprintTicks, tapTicks, jumpTicks;
        int swingCount, attackPackets, leftClickCounter, knockbackLevel;
        int hurtResistantTime, maxHurtResistantTime = 20, hurtTime;
        float lastDamage, fallDistance;
        boolean velocityChanged, heightCapped;
        double floorY;

        Player(double x, double y, double z, boolean grounded) {
            double half = 0.6f / 2;
            box = new Box(x-half, y, z-half, x+half, y+1.8f, z+half);
            this.grounded = grounded;
        }

        void sprint(boolean on) {
            sprinting = on;
            sprintTicks = on ? 600 : 0;
        }
    }

    // Replay the order of the incoming packet, local contact, and first movement in m2151.
    static Move firstMove(Player self, Player replica, short[] packet, boolean entityContact) {
        self.motion = decodeVelocity(packet);
        if (entityContact) consumeClick(self, Ray.ENTITY, replica);

        Keys keys = W_AND_SPRINT;
        Input input = sampleInput(self, keys);
        updateSprint(self, keys, input);
        Move move = travel(self, input, keys.jump(), 0.6f, List.of(FLOOR));
        self.airAcceleration = self.sprinting ? SPRINT_AIR : 0.02f; // After travel
        return move;
    }

    static Input sampleInput(Player p, Keys keys) {
        float forward = (keys.w() ? 1 : 0) - (keys.s() ? 1 : 0);
        float strafe  = (keys.a() ? 1 : 0) - (keys.d() ? 1 : 0);
        if (keys.sneak()) { forward *= 0.3; strafe *= 0.3; }
        if (p.usingItem)  { forward *= 0.2f; strafe *= 0.2f; }
        return new Input(strafe, forward);  // Sprint reads these before the 0.98 damping.
    }

    static void updateSprint(Player p, Keys keys, Input input) {
        if (p.sprintTicks > 0 && --p.sprintTicks == 0) p.sprint(false);
        if (p.tapTicks > 0) p.tapTicks--;
        if (p.usingItem) p.tapTicks = 0;

        boolean forward = input.forward() >= 0.8f;
        boolean canStart = forward && p.food > 6 && !p.usingItem && !p.blind;
        boolean risingTap = p.grounded && !p.previousSneak && p.previousForward < 0.8f;
        if (!p.sprinting && canStart) {
            if (keys.sprint() || (risingTap && p.tapTicks > 0)) p.sprint(true);
            else if (risingTap) p.tapTicks = 7;
        }
        if (p.sprinting && (!forward || p.hitWall || p.food <= 6)) p.sprint(false);
        p.previousForward = input.forward();
        p.previousSneak = keys.sneak();
    }

    static Move travel(Player p, Input input, boolean jump, float slip, List<Box> solids) {
        Vec v = p.motion;
        v = new Vec(cutoff(v.x), cutoff(v.y), cutoff(v.z));
        if (p.jumpTicks > 0) p.jumpTicks--;
        if (!jump) p.jumpTicks = 0;
        if (jump && p.grounded && p.jumpTicks == 0) {
            v = new Vec(v.x, 0.42f, v.z); // No Jump Boost in this example.
            if (p.sprinting) v = v.plus(jumpImpulse(p.yaw));
            p.jumpTicks = 10;
        }
        float friction = p.grounded ? 0.91f * slip : 0.91f;
        float groundSpeed = p.sprinting ? SPRINT_GROUND : 0.1f;
        float acceleration = p.grounded
                ? groundSpeed * (0.16277136f / (friction * friction * friction))
                : p.airAcceleration;

        Vec request = v.plus(accelerate(input, p.yaw, acceleration));
        Path path = moveBox(p.box, request, p.grounded, solids);
        Vec d = path.displacement();
        p.box = path.box();
        p.grounded = request.y < 0 && request.y != path.collisionY();
        p.hitWall = request.x != d.x || request.z != d.z;
        double vy = request.y == path.collisionY() ? request.y : 0; // Ordinary landing
        p.motion = new Vec(request.x == d.x ? request.x * friction : 0,
                           (vy - 0.08) * 0.98f,
                           request.z == d.z ? request.z * friction : 0);
        return new Move(d, p.motion, p.grounded);
    }

    static Vec accelerate(Input input, float yaw, float acceleration) {
        float s = input.strafe() * 0.98f, w = input.forward() * 0.98f;
        float squared = s*s + w*w;
        if (squared < 0.0001f) return ZERO;
        float scale = acceleration / Math.max(1f, (float)Math.sqrt(squared));
        s *= scale;
        w *= scale;
        float radians = yaw * (float)Math.PI / 180f;
        float sin = sinTable(radians), cos = cosTable(radians);
        return new Vec(s*cos - w*sin, 0, w*cos + s*sin);
    }

    static final float SPRINT_GROUND = (float)((double)0.1f * (1 + (double)0.3f));
    static final float SPRINT_AIR = (float)(0.02f + (double)0.02f * 0.3);
    static final float[] SIN = new float[65536];
    static {
        for (int i = 0; i < SIN.length; i++)
            SIN[i] = (float)Math.sin(i * Math.PI * 2.0 / 65536.0);
    }
    static float sinTable(float a) { return SIN[(int)(a * 10430.378f) & 65535]; }
    static float cosTable(float a) { return SIN[(int)(a * 10430.378f + 16384f) & 65535]; }
    static double cutoff(double v) { return Math.abs(v) < 0.005 ? 0 : v; }
    static Vec jumpImpulse(float yaw) {
        float radians = yaw * 0.017453292f;
        return new Vec(-sinTable(radians) * 0.2f, 0, cosTable(radians) * 0.2f);
    }

    record Box(double x0, double y0, double z0, double x1, double y1, double z1) {
        Box offset(double x, double y, double z) {
            return new Box(x0+x, y0+y, z0+z, x1+x, y1+y, z1+z);
        }
        Box sweptXZ(double x, double z) {
            return new Box(x0+Math.min(0,x), y0, z0+Math.min(0,z),
                           x1+Math.max(0,x), y1, z1+Math.max(0,z));
        }
    }
    static final Box FLOOR = new Box(-100, -1, -100, 100, 0, 100);
    enum Axis { X, Y, Z }
    // On a step the native Y collision comparison uses the final downward leg.
    record Path(Box box, Vec displacement, double collisionY) {
        double horizontalSquared() {
            return displacement.x * displacement.x + displacement.z * displacement.z;
        }
    }

    static Path slide(Box box, Vec request, List<Box> solids) {
        double y = clip(Axis.Y, box, request.y, solids);
        box = box.offset(0, y, 0);
        double x = clip(Axis.X, box, request.x, solids);
        box = box.offset(x, 0, 0);
        double z = clip(Axis.Z, box, request.z, solids);
        return new Path(box.offset(0, 0, z), new Vec(x, y, z), y);
    }

    static double clip(Axis axis, Box b, double amount, List<Box> solids) {
        for (Box s : solids) {
            boolean overlapX = b.x1 > s.x0 && b.x0 < s.x1;
            boolean overlapY = b.y1 > s.y0 && b.y0 < s.y1;
            boolean overlapZ = b.z1 > s.z0 && b.z0 < s.z1;
            double low, high, obstacleLow, obstacleHigh;
            switch (axis) {
                case X -> {
                    if (!overlapY || !overlapZ) continue;
                    low = b.x0; high = b.x1; obstacleLow = s.x0; obstacleHigh = s.x1;
                }
                case Y -> {
                    if (!overlapX || !overlapZ) continue;
                    low = b.y0; high = b.y1; obstacleLow = s.y0; obstacleHigh = s.y1;
                }
                default -> {
                    if (!overlapX || !overlapY) continue;
                    low = b.z0; high = b.z1; obstacleLow = s.z0; obstacleHigh = s.z1;
                }
            }
            if (amount > 0 && high <= obstacleLow) amount = Math.min(amount, obstacleLow-high);
            if (amount < 0 && low >= obstacleHigh) amount = Math.max(amount, obstacleHigh-low);
        }
        return amount;
    }

    static Path moveBox(Box start, Vec request, boolean grounded, List<Box> solids) {
        Path plain = slide(start, request, solids);
        Vec d = plain.displacement();
        boolean landed = request.y < 0 && d.y != request.y;
        if ((!grounded && !landed) || (d.x == request.x && d.z == request.z)) return plain;

        Box swept = start.sweptXZ(request.x, request.z);
        Path wide = stepPath(start, swept, request, solids);
        Path narrow = stepPath(start, start, request, solids);
        Path best = wide.horizontalSquared() > narrow.horizontalSquared() ? wide : narrow;
        return best.horizontalSquared() > plain.horizontalSquared() ? best : plain;
    }

    static Path stepPath(Box start, Box clearance, Vec request, List<Box> solids) {
        double up = clip(Axis.Y, clearance, 0.6f, solids);
        Path across = slide(start.offset(0, up, 0), new Vec(request.x, 0, request.z), solids);
        double down = clip(Axis.Y, across.box(), -up, solids);
        Vec d = across.displacement();
        return new Path(across.box().offset(0, down, 0), new Vec(d.x, up+down, d.z), down);
    }

    static void consumeClick(Player self, Ray ray, Player replica) {
        if (self.leftClickCounter > 0) return;
        self.swingCount++;
        if (ray == Ray.MISS) self.leftClickCounter = 10;
        if (ray != Ray.ENTITY) return; // Block interactions use a different path.

        self.attackPackets++; // C02 ATTACK is queued before the local hit.
        int levels = self.knockbackLevel + (self.sprinting ? 1 : 0);
        // An ordinary remote player accepts the local damage call.
        if (levels > 0) applyAttackBonus(self, replica, levels);
    }

    static void applyAttackBonus(Player attacker, Player target, int levels) {
        float radians = attacker.yaw * (float)Math.PI / 180f;
        Vec bonus = new Vec(-sinTable(radians) * levels * 0.5f, 0.1,
                              cosTable(radians) * levels * 0.5f);
        target.motion = target.motion.plus(bonus);
        attacker.motion = attacker.motion.brakeXZ();
        attacker.sprint(false);
    }

    static Damage acceptDamage(Player victim, float incoming) {
        if (victim.hurtResistantTime > victim.maxHurtResistantTime / 2f) {
            if (incoming <= victim.lastDamage) return new Damage(Hit.REJECTED, 0);
            float excess = incoming - victim.lastDamage;
            victim.lastDamage = incoming;
            return new Damage(Hit.UPGRADE, excess);
        }
        victim.lastDamage = incoming;
        victim.hurtResistantTime = victim.maxHurtResistantTime;
        victim.hurtTime = 10;
        return new Damage(Hit.FRESH, incoming);
    }

    static Vec awayDirection(Vec separation) {
        double x = separation.x, z = separation.z;
        while (x*x + z*z < 0.0001) { // Vanilla fallback for overlapping centers
            x = (Math.random() - Math.random()) * 0.01;
            z = (Math.random() - Math.random()) * 0.01;
        }
        float length = (float)Math.sqrt(x*x + z*z);
        return new Vec(x / length, 0, z / length);
    }

    // Ordinary melee, zero knockback resistance.
    static short[] vanillaHit(Player attacker, Player victim, Vec separation, float damage) {
        Vec saved = victim.motion;
        int levels = attacker.knockbackLevel + (attacker.sprinting ? 1 : 0);
        Hit hit = acceptDamage(victim, damage).kind();
        if (hit == Hit.REJECTED) return null;

        if (hit == Hit.FRESH) {
            Vec away = awayDirection(separation);
            victim.motion = new Vec(saved.x / 2 + away.x * 0.4f,
                                    Math.min(0.4f, saved.y / 2 + 0.4f),
                                    saved.z / 2 + away.z * 0.4f);
            victim.velocityChanged = true;
        }
        if (levels > 0) applyAttackBonus(attacker, victim, levels);
        if (!victim.velocityChanged) return null;
        short[] packet = encodeVelocity(victim.motion);
        victim.velocityChanged = false;
        victim.motion = saved;
        return packet;
    }

    record MmcSettings(double horizontal, double vertical, double sprintVertical) {}
    record Landing(boolean supported, boolean hasDelta, boolean groundedReport,
                   double lastDeltaY, double addedRttMs) {}

    static boolean predictsLanding(double height, double dy, double rttMs) {
        if (height <= 0 || height > 1.3 || rttMs <= 25) return false;
        int ticks = (int)Math.ceil((rttMs - 25) / 50);
        for (int i = 0; i < ticks; i++) {
            dy = (dy - 0.08) * 0.98f;
            height += dy;
            if (height <= 0) return true;
        }
        return false;
    }

    // Outgoing fresh-hit packet on the recorded Minemen sumo fit.
    static Vec mmcVelocity(Player attacker, Vec separation, boolean sprintAtEntry,
                           MmcSettings settings) {
        int levels = attacker.knockbackLevel + (sprintAtEntry ? 1 : 0);
        double angle = Math.toRadians(attacker.yaw);
        double facingX = -Math.sin(angle), facingZ = Math.cos(angle);
        double length = Math.hypot(separation.x, separation.z);
        double awayX = length < 0.01 ? facingX : separation.x/length;
        double awayZ = length < 0.01 ? facingZ : separation.z/length;
        double y = 0.361375 + levels * settings.sprintVertical;
        return new Vec((0.5275 * awayX + 0.422 * levels * facingX) * settings.horizontal,
                       y * settings.vertical,
                       (0.5275 * awayZ + 0.422 * levels * facingZ) * settings.horizontal);
    }

    static short encodeAxis(double speed) {
        double clamped = Math.max(-3.9, Math.min(3.9, speed));
        return (short)(clamped * 8000); // Java truncates toward zero.
    }

    static short[] encodeVelocity(Vec v) {
        return new short[] { encodeAxis(v.x), encodeAxis(v.y), encodeAxis(v.z) };
    }

    static Vec decodeVelocity(short[] wire) {
        return new Vec(wire[0] / 8000.0, wire[1] / 8000.0, wire[2] / 8000.0);
    }

    static Vec interpolateReplica(Vec current, Vec destination, int remainingSteps) {
        if (remainingSteps <= 0) return current;
        return new Vec(current.x + (destination.x - current.x) / remainingSteps,
                       current.y + (destination.y - current.y) / remainingSteps,
                       current.z + (destination.z - current.z) / remainingSteps);
    }

    static double eyeDistance(Vec eye, Box expanded) {
        double dx = Math.max(0, Math.max(expanded.x0 - eye.x, eye.x - expanded.x1));
        double dy = Math.max(0, Math.max(expanded.y0 - eye.y, eye.y - expanded.y1));
        double dz = Math.max(0, Math.max(expanded.z0 - eye.z, eye.z - expanded.z1));
        return Math.sqrt(dx*dx + dy*dy + dz*dz);
    }

    static double reachAdvantage(Vec myEye, Box theirBoxOnMyClient,
                                 Vec theirEye, Box myBoxOnTheirClient) {
        return eyeDistance(theirEye, myBoxOnTheirClient) - eyeDistance(myEye, theirBoxOnMyClient);
    }

    static boolean startsServerJump(boolean wasGrounded, boolean reportedGround, double requestedDY) {
        return wasGrounded && !reportedGround && requestedDY > 0;
    }

    // After the server accepts the position. Ordinary floor, no flight or Jump Boost.
    static int recordFall(Player serverPlayer, boolean reportedGround, double acceptedDY) {
        if (reportedGround) {
            int damage = Math.max(0, (int)Math.ceil(serverPlayer.fallDistance - 3f));
            serverPlayer.fallDistance = 0;
            return damage;
        }
        if (acceptedDY < 0) serverPlayer.fallDistance = (float)(serverPlayer.fallDistance - acceptedDY);
        return 0;
    }

    public static void main(String[] args) {
        Player human = new Player(0, 0, 0, true);
        Player bot = new Player(0, 0.586973267107587, 0, false);
        human.yaw = -89.999916f;
        bot.yaw = 89.95497f;
        for (Player p : List.of(human, bot)) {
            p.sprint(true);
            p.airAcceleration = SPRINT_AIR;
        }
        // S12 signed shorts, in units of 1/8000 block per tick.
        Player botReplica = new Player(2, bot.box.y0, 0, false);
        Player humanReplica = new Player(-2, 0, 0, true);
        Move a = firstMove(human, botReplica, new short[] {-4219, 2891, 6}, true);
        Move b = firstMove(bot, humanReplica, new short[] {4219, 2891, -7}, false);
        System.out.printf(Locale.ROOT, "Nomophobia: move X %.6f, saved X %.6f%n",
                          a.displacement.x, a.savedMotion.x);
        System.out.printf(Locale.ROOT, "Hold-W:     move X %.6f, saved X %.6f%n",
                          b.displacement.x, b.savedMotion.x);
    }
}
