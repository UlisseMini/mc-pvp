import practice.core.bow.BowBallistics;
import practice.core.bow.BowBallistics.Shot;
import practice.core.bow.BowMotion;
import practice.core.bow.BowMotion.Mode;
import practice.core.bow.BowMotion.Track;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.GZIPInputStream;

/** Paired moving-bot bow scenarios on voxel geometry extracted from BedWars. */
public final class DodgeArena {
    private static final int FLIGHT = BowBallistics.MAX_TICKS;
    private static final double WIDTH = .30, EYE = 1.62;
    private static final String[] DIFFICULTIES = {"rhythm", "feints", "reactive"};
    private static final String[] LABELS = {"linear", "adaptive", "cycle_strict", "cycle_medium", "cycle_loose"};
    private static final double[] GATES = {.10, .22, .40};
    private static final int BASELINE_MODES = 2;

    private static final class Arena {
        final int id, x0, y0, z0, nx, ny, nz;
        final boolean[] blocks;
        final int[][] starts;

        Arena(DataInputStream in) throws IOException {
            id = in.readInt();
            x0 = in.readInt();
            y0 = in.readInt();
            z0 = in.readInt();
            nx = in.readInt() - x0 + 1;
            ny = in.readInt() - y0 + 1;
            nz = in.readInt() - z0 + 1;
            blocks = new boolean[nx * ny * nz];
            int n = in.readInt();
            for (int i = 0; i < n; i++) {
                int x = in.readShort() - x0, y = in.readShort() - y0, z = in.readShort() - z0;
                in.readUnsignedShort();
                blocks[index(x, y, z)] = true;
            }
            starts = new int[in.readInt()][6];
            for (int[] start : starts) for (int i = 0; i < 6; i++) start[i] = in.readShort();
        }

        private int index(int x, int y, int z) { return (x * ny + y) * nz + z; }

        private boolean block(int x, int y, int z) {
            x -= x0;
            y -= y0;
            z -= z0;
            return x >= 0 && x < nx && y >= 0 && y < ny && z >= 0 && z < nz && blocks[index(x, y, z)];
        }

        boolean body(double x, double y, double z, double height) {
            for (int px = floor(x - WIDTH); px <= floor(x + WIDTH); px++)
                for (int py = floor(y + 1e-5); py <= floor(y + height); py++)
                    for (int pz = floor(z - WIDTH); pz <= floor(z + WIDTH); pz++)
                        if (block(px,py,pz)) return true;
            return false;
        }

        boolean support(double x, double y, double z) {
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++)
                if (!block(floor(x + dx * WIDTH), floor(y - .06), floor(z + dz * WIDTH))) return false;
            return true;
        }

        // Ray segments in this harness are sampled conservatively at intervals of at most 1/12 block.
        double obstruction(double x, double y, double z, double dx, double dy, double dz) {
            int samples = Math.max(1, (int)Math.ceil(12 * Math.sqrt(dx * dx + dy * dy + dz * dz)));
            for (int i = 1; i <= samples; i++) {
                double u = i / (double)samples;
                if (block(floor(x + u * dx),floor(y + u * dy),floor(z + u * dz))) return u;
            }
            return Double.POSITIVE_INFINITY;
        }

        boolean visible(Actor a, Actor b) {
            return !Double.isFinite(obstruction(a.x,a.y+EYE,a.z,b.x-a.x,b.y-a.y+.90-EYE,b.z-a.z));
        }
    }

    private static final class Actor {
        final double homeX, homeY, homeZ;
        double x, y, z, vx, vy, vz;
        boolean grounded = true;

        Actor(int x, int y, int z) {
            homeX = this.x = x + .5;
            homeY = this.y = y;
            homeZ = this.z = z + .5;
        }

        Actor(Actor copy) {
            homeX = copy.homeX; homeY = copy.homeY; homeZ = copy.homeZ;
            x = copy.x; y = copy.y; z = copy.z; vx = copy.vx; vy = copy.vy; vz = copy.vz;
            grounded = copy.grounded;
        }

        void tick(Arena map, double dx, double dz, double speed, boolean jump) {
            dx += .33 * (homeX - x);
            dz += .33 * (homeZ - z);
            double scale = speed / Math.max(1, Math.hypot(dx,dz));
            dx *= scale;
            dz *= scale;
            if (!map.support(x+dx*2.7, homeY, z+dz*2.7)) {
                dx = (homeX-x) * .12;
                dz = (homeZ-z) * .12;
            }
            double acceleration = grounded ? .49 : .075;
            vx += (dx - vx) * acceleration;
            vz += (dz - vz) * acceleration;
            if (!map.body(x+vx,y,z,1.79)) x += vx;
            else vx = 0;
            if (!map.body(x,y,z+vz,1.79)) z += vz;
            else vz = 0;
            if (jump && grounded) vy = .42;
            if (map.body(x,y+vy,z,1.79)) {
                if (vy < 0) {
                    // Resolve the contact to the exact top of the supporting cube.
                    y = Math.floor(y+vy+1e-5) + 1;
                    grounded = true;
                }
                vy = 0;
            } else {
                y += vy;
                grounded = map.body(x,y-.08,z,1.79);
            }
            vy = grounded ? 0 : (vy-.08)*.98;
        }
    }

    private static final class Scene {
        final Arena map;
        final int difficulty, when, charge, reaction;
        final double sideX, sideZ;
        final int[] strafe, step;
        final boolean[] jump;
        final int shooterPhase;
        final long spreadSeed;

        Scene(Arena map, int difficulty, int[] pair, long seed) {
            this.map = map;
            this.difficulty = difficulty;
            Random rng = new Random(seed);
            when = 145 + rng.nextInt(90);
            charge = 15 + rng.nextInt(6);
            reaction = 3 + rng.nextInt(5);
            shooterPhase = rng.nextInt(96);
            spreadSeed = rng.nextLong();
            double x = pair[3]-pair[0], z = pair[5]-pair[2], n = Math.hypot(x,z);
            sideX = -z/n;
            sideZ = x/n;
            strafe = new int[when + FLIGHT + 1];
            step = new int[strafe.length];
            jump = new boolean[strafe.length];
            int halfCycle = 9 + rng.nextInt(18);
            int offset = rng.nextInt(halfCycle*2);
            int sign = rng.nextBoolean() ? 1 : -1, next = 0;
            for (int t = 0; t < strafe.length; t++) {
                if (difficulty == 0) {
                    strafe[t] = ((t+offset)/halfCycle)%2 == 0 ? 1 : -1;
                } else {
                    if (t >= next) {
                        sign = -sign;
                        next = t+7+rng.nextInt(difficulty == 1 ? 25 : 31);
                        jump[t] = rng.nextDouble() < (difficulty == 1 ? .34 : .24);
                    }
                    strafe[t] = sign;
                    step[t] = (t/23 + offset)%3==0 ? -1 : ((t/19)%3==1 ? 1 : 0);
                }
            }
        }

        void shooter(Actor player, Actor other, int tick) {
            int side = ((tick + shooterPhase) / 27) % 2 == 0 ? 1 : -1;
            double approach = Math.hypot(player.x-other.x,player.z-other.z) > 14 ? .30 : -.15;
            double speed = tick >= when-charge && tick <= when ? .085 : .215;
            player.tick(map,side*.8*sideX + approach*sideZ, side*.8*sideZ - approach*sideX,speed,false);
        }

        boolean defender(Actor player, int tick, Projectile arrow) {
            double side = strafe[tick], fore = step[tick] * .52;
            boolean leap = jump[tick];
            boolean reacted = difficulty == 2 && arrow != null && tick - when >= reaction && arrow.incoming(map, player);
            if (reacted) {
                // React only to a nearby, visible arrow already in flight.
                double lateral = (player.x-arrow.x)*sideX + (player.z-arrow.z)*sideZ;
                side = lateral < 0 ? -1.6 : 1.6;
                fore = -.45;
                leap = tick == when + reaction && (shooterPhase & 1) == 0;
            }
            player.tick(map,side*sideX + fore*sideZ,side*sideZ - fore*sideX,
                    difficulty == 2 ? .278 : .235,leap);
            return reacted;
        }
    }

    private static final class Projectile {
        double x, y, z, vx, vy, vz;

        Projectile(Shot shot, double speed, long seed) {
            Random rng = new Random(seed);
            x = shot.x; y = shot.y; z = shot.z;
            vx = shot.vx + spread(rng)*speed; vy = shot.vy + spread(rng)*speed; vz = shot.vz + spread(rng)*speed;
        }

        private static double spread(Random r) { return .0075 * r.nextGaussian() * (r.nextBoolean() ? -1 : 1); }

        boolean incoming(Arena map, Actor defender) {
            double dx = defender.x - x, dy = defender.y + .85 - y, dz = defender.z - z;
            double d2 = dx*dx + dy*dy + dz*dz, along = dx*vx + dy*vy + dz*vz;
            double v2 = vx*vx + vy*vy + vz*vz;
            return d2 <= 24*24 && along > 0 && d2 - along*along/v2 <= 3.5*3.5
                    && !Double.isFinite(map.obstruction(x,y,z,dx,dy,dz));
        }

        String tick(Arena map, Actor target) {
            double terrain = map.obstruction(x,y,z,vx,vy,vz);
            double contact = crossing(this, target);
            if (contact <= 1 && contact < terrain) return "hit";
            if (terrain <= 1) return "terrain";
            x += vx; y += vy; z += vz;
            vx *= BowBallistics.DRAG;
            vy = vy * BowBallistics.DRAG - BowBallistics.GRAVITY;
            vz *= BowBallistics.DRAG;
            return null;
        }
    }

    private static double crossing(Projectile p, Actor a) {
        double[] q = {p.x,p.y,p.z}, v = {p.vx,p.vy,p.vz};
        double[] low = {a.x-.6,a.y-.3,a.z-.6}, high = {a.x+.6,a.y+2.1,a.z+.6};
        double enter = 0, exit = 1;
        for (int k = 0; k < 3; k++) {
            if (Math.abs(v[k]) < 1e-9) {
                if (q[k] < low[k] || q[k] > high[k]) return 2;
            } else {
                double u = (low[k]-q[k])/v[k], w = (high[k]-q[k])/v[k];
                enter = Math.max(enter,Math.min(u,w));
                exit = Math.min(exit,Math.max(u,w));
                if (enter > exit) return 2;
            }
        }
        return enter;
    }

    private static final class Cycle {
        final int length;
        final double error;

        Cycle(int length, double error) { this.length = length; this.error = error; }

        static Cycle fit(Track track) {
            double variation = 0;
            if (track.length() < 46) return new Cycle(0,Double.POSITIVE_INFINITY);
            for (int age = 0; age < 24; age += 3)
                for (int axis : new int[]{0,2})
                    variation += square((track.get(age,axis) - track.get(age+3,axis))
                            - (track.get(24,axis) - track.get(27,axis)))*.125;
            if (variation < .06) return new Cycle(0,Double.POSITIVE_INFINITY);
            Cycle best = new Cycle(0,Double.POSITIVE_INFINITY);
            for (int period = 15; period <= 58 && 2*period+12 < track.length(); period++) {
                double error = 0;
                for (int age = 0; age < period+8; age += 2) for (int axis : new int[]{0,2}) {
                    double now = track.get(age,axis) - track.get(age+3,axis);
                    double before = track.get(age+period,axis) - track.get(age+period+3,axis);
                    error += square(now-before);
                }
                error /= ((period+9)/2) * variation;
                // Near-tied periods favor the shorter explanation.
                double penalized = error + .0009 * period;
                if (penalized < best.error) best = new Cycle(period,penalized);
            }
            return best;
        }

        double[][] blend(double[][] base, Track track, double gate) {
            if (length == 0 || error >= gate) return base;
            double confidence = .9 * (1 - error / gate);
            double[][] out = new double[base.length][3];
            for (int t = 0; t < base.length; t++) {
                out[t][1] = base[t][1];
                for (int axis : new int[]{0,2}) {
                    double full = track.get(0,axis) - track.get(length,axis);
                    double part = track.get(length - t%length,axis) - track.get(length,axis);
                    double repeat = track.get(0,axis) + full * (t/length) + part;
                    out[t][axis] = base[t][axis] * (1 - confidence) + repeat * confidence;
                }
            }
            return out;
        }
    }

    private static final class Record {
        int attempted, eligible, hit, terrain, active, fell, reactions;
        double shooterMoved, defenderMoved;
    }

    private static void play(Arena map, int difficulty, int[] initial, long seed, BowMotion model,
                             Record[] results, int chosen) {
        Scene scene = new Scene(map,difficulty,initial,seed);
        Actor shooter = new Actor(initial[0],initial[1],initial[2]);
        Actor target = new Actor(initial[3],initial[4],initial[5]);
        Track track = new Track();
        double shooterMoved = 0, defenderMoved = 0;
        for (int tick = 0; tick < scene.when; tick++) {
            if (map.visible(shooter,target))
                track.add(tick,quantized(target.x),quantized(target.y),quantized(target.z));
            double sx = shooter.x, sz = shooter.z, tx = target.x, tz = target.z;
            scene.shooter(shooter,target,tick);
            scene.defender(target,tick,null);
            shooterMoved += Math.hypot(shooter.x-sx,shooter.z-sz);
            defenderMoved += Math.hypot(target.x-tx,target.z-tz);
        }
        boolean alive = shooter.y > map.y0-25 && target.y > map.y0-25;
        for (Record result : results) {
            result.attempted++;
            result.shooterMoved += shooterMoved;
            result.defenderMoved += defenderMoved;
            if (!alive) result.fell++;
        }
        if (!alive || track.length() < 8 || track.tick() != scene.when-1 || !map.visible(shooter,target)) return;
        double[][] linear = model.path(track,Mode.LINEAR,FLIGHT);
        double[][] adaptive = model.path(track,Mode.ADAPTIVE,FLIGHT);
        Cycle cycle = Cycle.fit(track);
        double speed = BowBallistics.speed(scene.charge);
        for (int method = 0; method < results.length; method++) {
            if (chosen >= 0 && method >= BASELINE_MODES && method != chosen + BASELINE_MODES) continue;
            Record result = results[method];
            result.eligible++;
            double[][] predicted = method == 0 ? linear : method == 1 ? adaptive
                    : cycle.blend(adaptive,track,GATES[method-BASELINE_MODES]);
            if (method >= BASELINE_MODES && predicted != adaptive) result.active++;
            // Last observation is one tick old at launch. All arms use the same convention.
            double[][] shifted = new double[FLIGHT+1][];
            for (int t = 0; t <= FLIGHT; t++) shifted[t] = predicted[Math.min(t+1,FLIGHT)];
            Shot shot = BowBallistics.intercept(shooter.x,shooter.y+EYE,shooter.z,speed,shifted,.90);
            if (shot == null) continue;
            Projectile arrow = new Projectile(shot,speed,scene.spreadSeed);
            Actor sameShooter = new Actor(shooter), sameTarget = new Actor(target);
            boolean reacted = false;
            for (int t = 1; t <= FLIGHT; t++) {
                scene.shooter(sameShooter,sameTarget,scene.when+t);
                reacted |= scene.defender(sameTarget,scene.when+t,arrow);
                String stopped = arrow.tick(map,sameTarget);
                if (stopped == null) continue;
                if ("hit".equals(stopped)) result.hit++;
                else result.terrain++;
                break;
            }
            if (reacted) result.reactions++;
        }
    }

    private static int floor(double x) { return (int)Math.floor(x); }
    private static double square(double x) { return x*x; }
    private static double quantized(double x) { return Math.rint(x*32)/32; }

    public static void main(String[] args) throws IOException {
        int repeat = Integer.parseInt(args[3]);
        String split = args[2];
        boolean dev = "dev".equals(split);
        if (!dev && !"heldout".equals(split)) throw new IllegalArgumentException("Choose dev or heldout");
        long baseSeed = dev ? 923477L : 71033629L;
        int chosen = dev ? -1 : Integer.parseInt(args[4]);
        if (!dev && (chosen < 0 || chosen >= GATES.length))
            throw new IllegalArgumentException("Select a frozen cycle gate (0–2)");
        BowMotion model = BowMotion.read(Files.newInputStream(Paths.get(args[1])));
        System.out.println("map,difficulty,method,episodes,eligible,hits,terrain,falls,adapted,reactions,shooter_blocks,target_blocks");
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(
                new GZIPInputStream(Files.newInputStream(Paths.get(args[0])))))) {
            if (in.readInt() != 0x42574131) throw new IOException("Invalid BedWars arena file");
            int maps = in.readInt();
            for (int index = 0; index < maps; index++) {
                Arena arena = new Arena(in);
                if (dev ? arena.id > 9 : arena.id < 10) continue;
                for (int difficulty = 0; difficulty < DIFFICULTIES.length; difficulty++) {
                    Record[] result = new Record[LABELS.length];
                    Arrays.setAll(result,j -> new Record());
                    for (int pair = 0; pair < arena.starts.length; pair++)
                        for (int scene = 0; scene < repeat; scene++) play(arena,difficulty,arena.starts[pair],
                                baseSeed+arena.id*9337L+pair*17947L+difficulty*7499L+scene*6721L,model,result,chosen);
                    for (int m = 0; m < result.length; m++) {
                        if (!dev && m >= BASELINE_MODES && m != chosen + BASELINE_MODES) continue;
                        Record r = result[m];
                        System.out.printf(Locale.ROOT,"%02d,%s,%s,%d,%d,%d,%d,%d,%d,%d,%.2f,%.2f%n",
                                arena.id,DIFFICULTIES[difficulty],LABELS[m],r.attempted,r.eligible,r.hit,r.terrain,
                                r.fell,r.active,r.reactions,r.shooterMoved/r.attempted,r.defenderMoved/r.attempted);
                    }
                }
            }
        }
    }
}
