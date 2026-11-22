import practice.core.bow.BowBallistics;
import practice.core.bow.BowBallistics.Shot;
import practice.core.bow.BowMotion;
import practice.core.bow.BridgeMotion;
import practice.core.bow.BowMotion.Track;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.GZIPInputStream;

public final class Study {
    private static final int HORIZON = BowBallistics.MAX_TICKS, F = BridgeMotion.FEATURES;
    private static final int[] STEPS = BowMotion.HORIZONS, RANGES = {10, 18, 28, 40, 52};
    private static final String[] MODES = {"linear", "installed", "bridge_half", "bridge_full", "bridge_gated"};
    private static final String[] CASES = {"flat_sneak", "flat_release", "rising", "other_bridge", "block_pose_other", "general"};

    interface Tracks { void accept(int game, int id, int[] ticks, float[][] path, int[] flags); }

    static void read(Path path, Tracks action) throws IOException {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new GZIPInputStream(Files.newInputStream(path))))) {
            if (in.readInt() != 0x42524731) throw new IOException("Invalid bridge observation file");
            for (int game = 0, gameCount = in.readInt(); game < gameCount; game++) {
                int id = in.readInt(), players = in.readInt();
                for (int p = 0; p < players; p++) {
                    int n = in.readInt();
                    int[] ticks = new int[n], flags = new int[n];
                    float[][] points = new float[n][3];
                    for (int i = 0; i < n; i++) {
                        ticks[i] = in.readInt();
                        for (int a = 0; a < 3; a++) points[i][a] = in.readFloat();
                        flags[i] = in.readUnsignedByte();
                    }
                    action.accept(id,p,ticks,points,flags);
                }
            }
        }
    }

    static int[] contiguous(int[] ticks, float[][] p) {
        int[] future = new int[ticks.length];
        for (int i = ticks.length-2; i >= 0; i--) {
            if (ticks[i+1] == ticks[i]+1 && Math.hypot(Math.hypot(p[i][0]-p[i+1][0],p[i][2]-p[i+1][2]),
                    p[i][1]-p[i+1][1]) <= 2.5) future[i] = 1 + future[i+1];
        }
        return future;
    }

    private static final class Ridge {
        final double[][] sums = new double[F][F+1];
        int count;

        void put(double[] v, double y) {
            count++;
            for (int i = 0; i < F; i++) {
                for (int j = 0; j < F; j++) sums[i][j] += v[i]*v[j];
                sums[i][F] += v[i]*y;
            }
        }

        double[] finish() {
            for (int i = 0; i < F; i++) sums[i][i] += .00015 * count + .02;
            for (int i = 0; i < F; i++) {
                double diag = sums[i][i];
                for (int j = i; j <= F; j++) sums[i][j] /= diag;
                for (int k = 0; k < F; k++) {
                    if (k == i) continue;
                    double coefficient = sums[k][i];
                    for (int j = i; j <= F; j++) sums[k][j] -= coefficient * sums[i][j];
                }
            }
            double[] weights = new double[F];
            for (int i = 0; i < F; i++) weights[i] = sums[i][F];
            return weights;
        }
    }

    private static void fit(Path observations, Path model, int last, double strength) throws IOException {
        Ridge[][] regressions = new Ridge[STEPS.length][2];
        for (Ridge[] pair : regressions) Arrays.setAll(pair,i->new Ridge());
        read(observations,(game,id,ticks,path,flags)->{
            if (game > last) return;
            int[] future = contiguous(ticks,path);
            Track history = new Track();
            for (int i = 0; i < ticks.length; i++) {
                history.add(ticks[i],path[i][0],path[i][1],path[i][2],flags[i]);
                if ((ticks[i]+id)%3 != 0 || (flags[i]&4)==0 || !BridgeMotion.active(history)) continue;
                for (int a = 0; a < 3; a++) {
                    double[] features = BridgeMotion.features(history,a);
                    for (int h = 0; h < STEPS.length && STEPS[h] <= future[i]; h++)
                        regressions[h][a == 1 ? 1 : 0].put(features,path[i+STEPS[h]][a]-path[i][a]);
                }
            }
        });
        try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(model))) {
            out.writeInt(0x42524d31);
            out.writeDouble(strength);
            for (int h = 0; h < STEPS.length; h++) {
                System.err.printf("Fit %d ticks from %,d build starts%n",STEPS[h],regressions[h][1].count);
                for (Ridge group : regressions[h]) for (double coefficient : group.finish()) out.writeDouble(coefficient);
            }
        }
    }

    private static int category(int[] flags, float[][] pos, int i) {
        if ((flags[i]&4)==0) return (flags[i]&2)!=0 ? 4 : 5;
        if (pos[i+18][1] - pos[i][1] >= .62) return 2;
        if (Math.abs(pos[i+18][1] - pos[i][1]) > .28) return 3;
        if ((flags[i]&1)==0) return 3;
        int release = 0;
        for (int t = 1; t <= 18; t++) if ((flags[i+t]&1)==0) release++;
        return release >= 3 ? 1 : 0;
    }

    private static boolean intersects(double[] at, double[] move, float[] target) {
        double near = 0, far = 1;
        for (int a = 0; a < 3; a++) {
            double lo = target[a] - (a==1 ? .3 : .6), hi = target[a] + (a==1 ? 2.1 : .6);
            if (Math.abs(move[a]) < 1e-9) {
                if (at[a] < lo || at[a] > hi) return false;
                continue;
            }
            double u = (lo-at[a])/move[a], v = (hi-at[a])/move[a];
            near = Math.max(near,Math.min(u,v));
            far = Math.min(far,Math.max(u,v));
            if (near > far) return false;
        }
        return true;
    }

    static boolean hit(Shot s, float[][] p, int i, long seed) {
        if (s==null) return false;
        Random rng = new Random(seed);
        double[] at = {s.x,s.y,s.z}, velocity = {s.vx,s.vy,s.vz};
        for (int a=0; a<3; a++) velocity[a] += .0225*rng.nextGaussian()*(rng.nextBoolean() ? -1 : 1);
        for (int t=1; t<=HORIZON; t++) {
            if (intersects(at,velocity,p[i+t])) return true;
            for (int a=0; a<3; a++) {
                at[a] += velocity[a];
                velocity[a] = velocity[a]*BowBallistics.DRAG - (a==1 ? BowBallistics.GRAVITY : 0);
            }
        }
        return false;
    }

    private static final class Group {
        int ticks, fired, matches;
        double xz, y;
    }

    private static void score(Path observations, Path baseFile, Path model, int first, int last) throws IOException {
        BowMotion base = BowMotion.read(Files.newInputStream(baseFile));
        BridgeMotion bridge = BridgeMotion.read(Files.newInputStream(model));
        Map<String,Group> scores = new TreeMap<>();
        read(observations,(game,id,ticks,path,flags)->{
            if (game < first || game > last) return;
            int[] future = contiguous(ticks,path);
            Track history = new Track();
            for (int i = 0; i < ticks.length; i++) {
                history.add(ticks[i],path[i][0],path[i][1],path[i][2],flags[i]);
                if (future[i] < HORIZON || history.length() <= BridgeMotion.HISTORY || (ticks[i]+id)%7!=0) continue;
                int category = category(flags,path,i);
                if (category==5 && (ticks[i]+id)%5!=0) continue;
                long seed = ticks[i]*7919L + id*4009L + game*541L;
                double a = 2*Math.PI*new Random(seed).nextDouble();
                double[][] installed = base.path(history,BowMotion.Mode.ADAPTIVE,HORIZON);
                double[][][] forecasts = {base.path(history,BowMotion.Mode.LINEAR,HORIZON),installed,
                        bridge.apply(history,installed,.5),bridge.apply(history,installed,1),
                        bridge.applyProgressGated(history,installed)};
                for (int method=0; method<MODES.length; method++) {
                    double[][] forecast = forecasts[method];
                    for (int range : RANGES) {
                        String key = String.format(Locale.ROOT,"%02d,%s,%02d,%s",game,CASES[category],range,MODES[method]);
                        Group record = scores.computeIfAbsent(key,k->new Group());
                        record.ticks++;
                        if (method>=2 && forecast!=installed) record.matches++;
                        record.xz += Math.hypot(forecast[16][0]-path[i+16][0],forecast[16][2]-path[i+16][2]);
                        record.y += Math.abs(forecast[16][1]-path[i+16][1]);
                        Shot shot = BowBallistics.intercept(path[i][0]+range*Math.sin(a),path[i][1]+1.62,
                                path[i][2]+range*Math.cos(a),3,forecast,.90);
                        if (hit(shot,path,i,seed^656541L)) record.fired++;
                    }
                }
            }
        });
        System.out.println("game,case,range,method,n,hits,activated,xz_mae_16,y_mae_16");
        for (Map.Entry<String,Group> row : scores.entrySet()) {
            Group g = row.getValue();
            System.out.printf(Locale.ROOT,"%s,%d,%d,%d,%.5f,%.5f%n",row.getKey(),g.ticks,g.fired,g.matches,g.xz/g.ticks,g.y/g.ticks);
        }
    }

    public static void main(String[] args) throws IOException {
        Path data = Paths.get(args[1]);
        if ("fit".equals(args[0])) fit(data,Paths.get(args[2]),Integer.parseInt(args[3]),Double.parseDouble(args[4]));
        else if ("score".equals(args[0])) score(data,Paths.get(args[2]),Paths.get(args[3]),Integer.parseInt(args[4]),Integer.parseInt(args[5]));
        else throw new IllegalArgumentException("Choose fit or score");
    }
}
