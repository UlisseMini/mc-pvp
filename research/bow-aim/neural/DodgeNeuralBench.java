import practice.core.bow.BowBallistics;
import practice.core.bow.BowBallistics.Shot;
import practice.core.bow.BowMotion;
import practice.core.bow.BowMotion.Mode;
import practice.core.bow.BowMotion.Track;
import practice.core.bow.BowNeuralMotion;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.GZIPInputStream;

/**
 * Side-by-side benchmark of BowMotion (linear, adaptive+cycle) vs BowNeuralMotion (K=6 + 9x7x9 voxels)
 * on the exact held-out DodgeArena BedWars maps (maps 10-11) across rhythm, feints, and reactive bots.
 */
public final class DodgeNeuralBench {
    private static final int FLIGHT = BowBallistics.MAX_TICKS;
    private static final double WIDTH = .30, EYE = 1.62;
    private static final String[] DIFFICULTIES = {"rhythm", "feints", "reactive"};

    private static final class Arena {
        final int id, x0, y0, z0, nx, ny, nz;
        final boolean[] blocks;
        final int[][] starts;

        Arena(DataInputStream in) throws IOException {
            id = in.readInt();
            x0 = in.readInt(); y0 = in.readInt(); z0 = in.readInt();
            nx = in.readInt() - x0 + 1; ny = in.readInt() - y0 + 1; nz = in.readInt() - z0 + 1;
            blocks = new boolean[nx * ny * nz];
            int n = in.readInt();
            for (int i = 0; i < n; i++) {
                int x = in.readShort() - x0, y = in.readShort() - y0, z = in.readShort() - z0;
                in.readUnsignedShort();
                blocks[(x * ny + y) * nz + z] = true;
            }
            starts = new int[in.readInt()][6];
            for (int[] s : starts) for (int i = 0; i < 6; i++) s[i] = in.readShort();
        }

        boolean block(int x, int y, int z) {
            x -= x0; y -= y0; z -= z0;
            return x >= 0 && x < nx && y >= 0 && y < ny && z >= 0 && z < nz && blocks[(x * ny + y) * nz + z];
        }

        byte[][] sampleMultiVox(double wx, double wy, double wz) {
            byte[] fine = new byte[BowNeuralMotion.FINE_DIM];
            byte[] coarse = new byte[BowNeuralMotion.COARSE_DIM];
            int bx = floor(wx), by = floor(wy), bz = floor(wz);
            for (int cy = 0; cy < 10; cy++) {
                int fy = cy - 1, coarseYOff = (cy >> 1) * 121;
                for (int cz = 0; cz < 22; cz++) {
                    int fz = cz - 6, coarseZOff = coarseYOff + (cz >> 1) * 11;
                    for (int cx = 0; cx < 22; cx++) {
                        if (block(bx - 10 + cx, by - 4 + cy, bz - 10 + cz)) {
                            coarse[coarseZOff + (cx >> 1)] = 1;
                            int fx = cx - 6;
                            if (fy >= 0 && fy < 7 && fz >= 0 && fz < 9 && fx >= 0 && fx < 9)
                                fine[fy * 81 + fz * 9 + fx] = 1;
                        }
                    }
                }
            }
            return new byte[][]{fine, coarse};
        }

        boolean body(double x, double y, double z, double height) {
            for (int px = floor(x - WIDTH); px <= floor(x + WIDTH); px++)
                for (int py = floor(y + 1e-5); py <= floor(y + height); py++)
                    for (int pz = floor(z - WIDTH); pz <= floor(z + WIDTH); pz++)
                        if (block(px, py, pz)) return true;
            return false;
        }

        boolean support(double x, double y, double z) {
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++)
                if (!block(floor(x + dx * WIDTH), floor(y - .06), floor(z + dz * WIDTH))) return false;
            return true;
        }

        double obstruction(double x, double y, double z, double dx, double dy, double dz) {
            int samples = Math.max(1, (int)Math.ceil(12 * Math.sqrt(dx * dx + dy * dy + dz * dz)));
            for (int i = 1; i <= samples; i++) {
                double u = i / (double)samples;
                if (block(floor(x + u * dx), floor(y + u * dy), floor(z + u * dz))) return u;
            }
            return Double.POSITIVE_INFINITY;
        }

        boolean visible(Actor a, Actor b) {
            return !Double.isFinite(obstruction(a.x, a.y + EYE, a.z, b.x - a.x, b.y - a.y + .90 - EYE, b.z - a.z));
        }
    }

    private static final class Actor {
        final double homeX, homeY, homeZ;
        double x, y, z, vx, vy, vz;
        boolean grounded = true;

        Actor(int x, int y, int z) { homeX = this.x = x + .5; homeY = this.y = y; homeZ = this.z = z + .5; }
        Actor(Actor c) {
            homeX = c.homeX; homeY = c.homeY; homeZ = c.homeZ;
            x = c.x; y = c.y; z = c.z; vx = c.vx; vy = c.vy; vz = c.vz; grounded = c.grounded;
        }

        void tick(Arena map, double dx, double dz, double speed, boolean jump) {
            dx += .33 * (homeX - x); dz += .33 * (homeZ - z);
            double scale = speed / Math.max(1, Math.hypot(dx, dz));
            dx *= scale; dz *= scale;
            if (!map.support(x + dx * 2.7, y, z + dz * 2.7)) {
                dx = (homeX - x) * .12; dz = (homeZ - z) * .12;
            }
            double acc = grounded ? .49 : .075;
            vx += (dx - vx) * acc; vz += (dz - vz) * acc;
            if (!map.body(x + vx, y, z, 1.79)) x += vx; else vx = 0;
            if (!map.body(x, y, z + vz, 1.79)) z += vz; else vz = 0;
            if (jump && grounded) vy = .42;
            if (map.body(x, y + vy, z, 1.79)) {
                if (vy < 0) {
                    int foot = floor(y + vy);
                    while (foot < floor(y) + 2 && map.body(x, foot, z, 1.79)) foot++;
                    y = foot; grounded = true;
                }
                vy = 0;
            } else { y += vy; grounded = false; }
            vy = (vy - .08) * .98;
        }
    }

    private static final class Scene {
        final int difficulty, when, charge, period, phase, jumpPeriod;
        final double ux, uz, speed, jumpChance;
        final long spreadSeed;
        int dodgeDir, dodgeLeft;
        final Random rng;

        Scene(Arena map, int difficulty, int[] init, long seed) {
            this.difficulty = difficulty;
            rng = new Random(seed);
            when = 75 + rng.nextInt(55);
            charge = 20;
            period = 18 + rng.nextInt(26);
            phase = rng.nextInt(period);
            jumpPeriod = 26 + rng.nextInt(24);
            double dx = init[3] - init[0], dz = init[5] - init[2];
            double norm = Math.max(1e-6, Math.hypot(dx, dz));
            ux = -dz / norm; uz = dx / norm; // perpendicular strafe axis
            speed = .20 + rng.nextDouble() * .07;
            jumpChance = difficulty == 0 ? 0.0 : 0.035;
            spreadSeed = seed ^ 0x5A17D09EL;
        }

        void defender(Actor a, int t, Projectile arrow, Arena map) {
            double dir;
            boolean jump = false;
            if (difficulty == 0) {
                dir = ((t + phase) / (period / 2)) % 2 == 0 ? 1 : -1;
            } else if (difficulty == 1) {
                int half = Math.max(6, period / 2);
                int seg = (t + phase) / half;
                dir = ((seg ^ (seg / 3)) % 2 == 0) ? 1 : -1;
                if ((t + phase) % jumpPeriod == 0) jump = true;
            } else {
                if (arrow != null && dodgeLeft == 0 && arrow.incoming(map, a)) {
                    dodgeDir = rng.nextBoolean() ? 1 : -1;
                    dodgeLeft = 10;
                }
                if (dodgeLeft > 0) {
                    dir = dodgeDir;
                    dodgeLeft--;
                    jump = dodgeLeft == 9;
                } else {
                    dir = ((t + phase) / Math.max(8, period / 2)) % 2 == 0 ? 1 : -1;
                    jump = rng.nextDouble() < jumpChance;
                }
            }
            a.tick(map, ux * dir, uz * dir, speed, jump);
        }
    }

    private static final class Projectile {
        double x, y, z, vx, vy, vz;
        Projectile(Shot s, double speed, long seed) {
            Random r = new Random(seed);
            x = s.x; y = s.y; z = s.z;
            vx = s.vx + spread(r) * speed; vy = s.vy + spread(r) * speed; vz = s.vz + spread(r) * speed;
        }
        private static double spread(Random r) { return .0075 * r.nextGaussian() * (r.nextBoolean() ? -1 : 1); }
        boolean incoming(Arena map, Actor d) {
            double dx = d.x - x, dy = d.y + .85 - y, dz = d.z - z;
            double d2 = dx * dx + dy * dy + dz * dz, along = dx * vx + dy * vy + dz * vz;
            double v2 = vx * vx + vy * vy + vz * vz;
            return d2 <= 24 * 24 && along > 0 && d2 - along * along / v2 <= 3.5 * 3.5
                    && !Double.isFinite(map.obstruction(x, y, z, dx, dy, dz));
        }
        String tick(Arena map, Actor target) {
            double terrain = map.obstruction(x, y, z, vx, vy, vz);
            double contact = crossing(this, target);
            if (contact <= 1 && contact < terrain) return "hit";
            if (terrain <= 1) return "terrain";
            x += vx; y += vy; z += vz;
            vx *= BowBallistics.DRAG; vy = vy * BowBallistics.DRAG - BowBallistics.GRAVITY; vz *= BowBallistics.DRAG;
            return null;
        }
    }

    private static double crossing(Projectile p, Actor a) {
        double[] q = {p.x, p.y, p.z}, v = {p.vx, p.vy, p.vz};
        double[] low = {a.x - .6, a.y - .3, a.z - .6}, high = {a.x + .6, a.y + 2.1, a.z + .6};
        double enter = 0, exit = 1;
        for (int k = 0; k < 3; k++) {
            if (Math.abs(v[k]) < 1e-9) {
                if (q[k] < low[k] || q[k] > high[k]) return 2;
            } else {
                double u = (low[k] - q[k]) / v[k], w = (high[k] - q[k]) / v[k];
                enter = Math.max(enter, Math.min(u, w)); exit = Math.min(exit, Math.max(u, w));
                if (enter > exit) return 2;
            }
        }
        return enter;
    }

    private static int floor(double x) { return (int)Math.floor(x); }
    private static double quantized(double x) { return Math.rint(x * 32) / 32; }

    public static void main(String[] args) throws Exception {
        BowMotion legacy = BowMotion.read(Files.newInputStream(Paths.get(args[1])));
        BowNeuralMotion neural = BowNeuralMotion.read(Files.newInputStream(Paths.get(args[2])));
        int repeat = Integer.parseInt(args[3]);
        String[] methods = {"linear", "adaptive+cycle", "neural+voxels"};
        int[][] hits = new int[3][3], eligible = new int[3][3];
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(
                new GZIPInputStream(Files.newInputStream(Paths.get(args[0])))))) {
            if (in.readInt() != 0x42574131) throw new IOException("Bad arena file");
            int maps = in.readInt();
            for (int m = 0; m < maps; m++) {
                Arena arena = new Arena(in);
                if (arena.id < 10) continue; // held-out maps 10 & 11 only!
                for (int diff = 0; diff < 3; diff++) {
                    for (int p = 0; p < arena.starts.length; p++) {
                        for (int rep = 0; rep < repeat; rep++) {
                            long seed = 71033629L + arena.id * 9337L + p * 17947L + diff * 7499L + rep * 6721L;
                            Scene sc = new Scene(arena, diff, arena.starts[p], seed);
                            Actor shooter = new Actor(arena.starts[p][0], arena.starts[p][1], arena.starts[p][2]);
                            Actor target = new Actor(arena.starts[p][3], arena.starts[p][4], arena.starts[p][5]);
                            Track track = new Track();
                            for (int t = 0; t < sc.when; t++) {
                                if (arena.visible(shooter, target)) {
                                    float yaw = (float)Math.toDegrees(Math.atan2(-target.vx, target.vz));
                                    track.add(t, quantized(target.x), quantized(target.y), quantized(target.z), 0, yaw, 0f, 0);
                                }
                                sc.defender(target, t, null, arena);
                            }
                            if (target.y <= arena.y0 - 25 || track.length() < 8 || track.tick() != sc.when - 1
                                    || !arena.visible(shooter, target)) continue;
                            double speed = BowBallistics.speed(sc.charge);
                            Shot[] shots = new Shot[3];
                            shots[0] = BowBallistics.intercept(shooter.x, shooter.y + EYE, shooter.z, speed,
                                    legacy.path(track, Mode.LINEAR, FLIGHT), .90);
                            shots[1] = BowBallistics.intercept(shooter.x, shooter.y + EYE, shooter.z, speed,
                                    legacy.path(track, Mode.ADAPTIVE, FLIGHT), .90);
                            // Multi-mode neural v2 + live multi-scale voxels + continuous refinement!
                            byte[][] vox = arena.sampleMultiVox(target.x, target.y, target.z);
                            BowNeuralMotion.Prediction pred = neural.predict(track, vox[0], vox[1], true);
                            Shot best = null; double bestP = -1;
                            for (int c = 0; c <= pred.paths.length; c++) {
                                double[][] path = c < pred.paths.length ? pred.paths[c] : pred.meanPath;
                                Shot cand = BowBallistics.intercept(shooter.x, shooter.y + EYE, shooter.z, speed, path, .90);
                                if (cand != null) {
                                    double pr = neural.rawHitProb(cand, pred, 0.0);
                                    if (pr > bestP) { bestP = pr; best = cand; }
                                }
                            }
                            if (best != null) {
                                best = neural.refineShot(shooter.x, shooter.y + EYE, shooter.z, speed, best, pred, 0.0, null);
                            }
                            shots[2] = best;
                            for (int k = 0; k < 3; k++) {
                                eligible[diff][k]++;
                                if (shots[k] == null) continue;
                                Projectile arr = new Projectile(shots[k], speed, sc.spreadSeed);
                                Actor copyT = new Actor(target);
                                Scene copyS = new Scene(arena, diff, arena.starts[p], seed);
                                for (int t = 0; t < FLIGHT; t++) {
                                    copyS.defender(copyT, sc.when + t, arr, arena);
                                    String stop = arr.tick(arena, copyT);
                                    if (stop != null) { if ("hit".equals(stop)) hits[diff][k]++; break; }
                                }
                            }
                        }
                    }
                }
            }
        }
        System.out.println("=== HELD-OUT DODGE ARENA (maps 10-11) ===");
        System.out.printf("%-10s | %-15s | %-15s | %-15s%n", "Difficulty", methods[0], methods[1], methods[2]);
        for (int d = 0; d < 3; d++) {
            System.out.printf(Locale.ROOT, "%-10s | %5.1f%% (%3d/%3d) | %5.1f%% (%3d/%3d) | %5.1f%% (%3d/%3d)%n",
                    DIFFICULTIES[d],
                    100.0 * hits[d][0] / Math.max(1, eligible[d][0]), hits[d][0], eligible[d][0],
                    100.0 * hits[d][1] / Math.max(1, eligible[d][1]), hits[d][1], eligible[d][1],
                    100.0 * hits[d][2] / Math.max(1, eligible[d][2]), hits[d][2], eligible[d][2]);
        }
    }
}
