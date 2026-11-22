package practice.core.bow;

import practice.core.bow.BowBallistics.Shot;
import practice.core.bow.BowMotion.Track;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Random;

/** Replay-fitted movement errors and native arrow dispersion for a displayed launch. */
public final class BowHitChance {
    public static final int GROUPS = 8, SAMPLES = 80, CALIBRATION_STEPS = 11;
    private static final int[] TIMES = BowMotion.HORIZONS;
    private static final double[][] SPREAD = new double[SAMPLES][3];

    static {
        Random random = new Random(29031176L);
        for (double[] draw : SPREAD)
            for (int a = 0; a < 3; a++) draw[a] = .007499999832361937 * random.nextGaussian()
                    * (random.nextBoolean() ? -1 : 1);
    }

    private final float[][][][] errors;
    private final double[] calibration;

    private BowHitChance(float[][][][] errors, double[] calibration) {
        this.errors = errors;
        this.calibration = calibration;
    }

    public static BowHitChance read(InputStream stream) throws IOException {
        try (DataInputStream in = new DataInputStream(stream)) {
            if (in.readInt() != 0x42484331) throw new IOException("Invalid bow hit chance model");
            float[][][][] values = new float[GROUPS][TIMES.length][SAMPLES][3];
            for (float[][][] group : values) for (float[][] horizon : group) for (float[] sample : horizon)
                for (int a = 0; a < 3; a++) sample[a] = in.readFloat();
            double[] curve = new double[CALIBRATION_STEPS];
            for (int k = 0; k < curve.length; k++) curve[k] = in.readDouble();
            return new BowHitChance(values, curve);
        }
    }

    public static int group(Track track, boolean building) {
        int past = Math.min(8, track.length()-1);
        double horizontal = Math.hypot(track.get(0,0)-track.get(past,0),track.get(0,2)-track.get(past,2));
        double vertical = Math.abs(track.get(0,1)-track.get(past,1));
        return (building ? 4 : 0) | (horizontal >= .08*past ? 2 : 0) | (vertical >= .38 ? 1 : 0);
    }

    public static double heading(Track track) {
        int past = Math.min(8,track.length()-1);
        double x = track.get(0,0)-track.get(past,0), z = track.get(0,2)-track.get(past,2);
        return Math.hypot(x,z) > .10 ? Math.atan2(z,x) : 0;
    }

    public double estimate(Track track, boolean building, Shot aimed, double idealTicks,
                           double clearUntil, double[][] predicted) {
        double raw = rawEstimate(track,building,aimed,idealTicks,clearUntil,predicted);
        if (raw == 0) return 0;
        double index = raw * (calibration.length-1);
        int lower = (int)index;
        if (lower >= calibration.length-1) return calibration[calibration.length-1];
        return calibration[lower] + (index-lower) * (calibration[lower+1]-calibration[lower]);
    }

    public double rawEstimate(Track track, boolean building, Shot aim, double ideal, double clearUntil, double[][] goal) {
        if (goal.length < 2 || clearUntil <= 0 || ideal <= 0) return 0;
        int upper = Math.min(goal.length-1,(int)Math.ceil(clearUntil));
        int closest = 0;
        for (int h = 1; h < TIMES.length; h++)
            if (Math.abs(TIMES[h]-ideal) < Math.abs(TIMES[closest]-ideal)) closest = h;
        double direction = heading(track), c = Math.cos(direction), s = Math.sin(direction);
        double speed = Math.sqrt(aim.vx*aim.vx+aim.vy*aim.vy+aim.vz*aim.vz);
        int first = Math.max(1,(int)(ideal - Math.max(4,ideal*.30)));
        int last = Math.min(upper,(int)Math.ceil(ideal + Math.max(4,ideal*.30)));
        if (first > last || speed <= 0) return 0;
        int hits = 0;
        float[][] samples = errors[group(track,building)][closest];
        for (int k = 0; k < SAMPLES; k++) {
            double dx = c*samples[k][0]-s*samples[k][2];
            double dz = s*samples[k][0]+c*samples[k][2];
            double vx = aim.vx+speed*SPREAD[k][0], vy = aim.vy+speed*SPREAD[k][1], vz = aim.vz+speed*SPREAD[k][2];
            for (int t = first; t <= last; t++) {
                double age = Math.min(clearUntil,t);
                double begin = BowBallistics.travel(t-1), end = BowBallistics.travel(age);
                double startX = aim.x+vx*begin, startY = aim.y+vy*begin-BowBallistics.fall(t-1);
                double startZ = aim.z+vz*begin;
                double rate = (double)t / TIMES[closest];
                double[] next = goal[t];
                if (intersects(startX,startY,startZ,aim.x+vx*end,aim.y+vy*end-BowBallistics.fall(age),
                        aim.z+vz*end,next[0]+dx*rate,next[1]+samples[k][1]*rate,next[2]+dz*rate)) {
                    hits++;
                    break;
                }
            }
        }
        return hits / (double)SAMPLES;
    }

    public static boolean intersects(double x, double y, double z, double a, double b, double c,
                                     double targetX, double targetY, double targetZ) {
        double from = 0, to = 1;
        for (int axis = 0; axis < 3; axis++) {
            double point = axis == 0 ? x-targetX : axis == 1 ? y-targetY : z-targetZ;
            double delta = axis == 0 ? a-x : axis == 1 ? b-y : c-z;
            double low = axis == 1 ? -.3 : -.6, high = axis == 1 ? 2.1 : .6;
            if (Math.abs(delta) < 1e-10) {
                if (point < low || point > high) return false;
                continue;
            }
            double p = (low-point)/delta, q = (high-point)/delta;
            from = Math.max(from,Math.min(p,q));
            to = Math.min(to,Math.max(p,q));
            if (from > to) return false;
        }
        return true;
    }
}
