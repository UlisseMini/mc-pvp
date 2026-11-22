package practice.core.bow;

import practice.core.bow.BowMotion.Track;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;

/** A motion specialist for visible block-in-hand, downward-looking bridge movement. */
public final class BridgeMotion {
    public static final int SNEAK = 1, LOOKING_DOWN_WITH_BLOCK = 2;
    public static final int FEATURES = 10, HISTORY = 20;
    private final double[][][] weights;
    private final double strength;

    private BridgeMotion(double[][][] weights, double strength) {
        this.weights = weights;
        this.strength = strength;
    }

    public static BridgeMotion read(InputStream stream) throws IOException {
        try (DataInputStream in = new DataInputStream(stream)) {
            if (in.readInt() != 0x42524d31) throw new IOException("Invalid bridge motion model");
            double strength = in.readDouble();
            if (!(strength >= 0 && strength <= 1)) throw new IOException("Invalid bridge model strength");
            double[][][] weights = new double[BowMotion.HORIZONS.length][2][FEATURES];
            for (double[][] atHorizon : weights) for (double[] axis : atHorizon)
                for (int k = 0; k < axis.length; k++) {
                    axis[k] = in.readDouble();
                    if (!Double.isFinite(axis[k])) throw new IOException("Invalid bridge model weight");
                }
            return new BridgeMotion(weights, strength);
        }
    }

    public static int cue(boolean sneak, boolean holdingBlock, float pitch) {
        if (pitch >= 180) pitch -= 360;
        if (pitch < -180) pitch += 360;
        return (sneak ? SNEAK : 0) | (holdingBlock && pitch >= 34 ? LOOKING_DOWN_WITH_BLOCK : 0);
    }

    public static boolean active(Track track) {
        if (track.length() <= HISTORY || (track.cue(0) & LOOKING_DOWN_WITH_BLOCK) == 0) return false;
        int buildingPose = 0;
        for (int age = 0; age < 10; age++) if ((track.cue(age) & LOOKING_DOWN_WITH_BLOCK) != 0) buildingPose++;
        if (buildingPose < 6) return false;
        double horizontal = Math.hypot(track.get(0, 0) - track.get(12, 0), track.get(0, 2) - track.get(12, 2));
        return horizontal >= .16 || Math.abs(track.get(0, 1) - track.get(12, 1)) >= .30;
    }

    public static double[] features(Track track, int axis) {
        double a = velocity(track, 0, 3, axis), b = velocity(track, 3, 7, axis);
        double c = velocity(track, 7, 12, axis), d = velocity(track, 12, HISTORY, axis);
        double fast = velocity(track, 0, 5, axis), steady = velocity(track, 0, HISTORY, axis);
        double sneak = (track.cue(0) & SNEAK) != 0 ? 1 : 0;
        int sneaking = 0;
        for (int i = 0; i < HISTORY; i++) if ((track.cue(i) & SNEAK) != 0) sneaking++;
        if (axis != 1) {
            double vx = velocity(track, 0, 8, 0), vz = velocity(track, 0, 8, 2);
            double ox = velocity(track, 8, HISTORY, 0), oz = velocity(track, 8, HISTORY, 2);
            double speed = Math.hypot(vx, vz);
            double similarity = (vx*ox + vz*oz) / Math.max(.001, speed*Math.hypot(ox,oz));
            return new double[]{a,b,c,d,fast*sneak,steady*sneak,steady*sneaking/HISTORY,
                    steady*similarity,fast*Math.min(1,speed/.20),steady*Math.min(1,speed/.20)};
        }
        double floorNow = minHeight(track, 0, 6), floorBefore = minHeight(track, 8, HISTORY);
        return new double[]{a,b,c,d,fast*sneak,steady*sneaking/HISTORY,(floorNow-floorBefore)/14,
                track.get(0,1)-floorNow, Math.max(0,fast), Math.min(0,fast)};
    }

    public double[][] apply(Track track, double[][] base) {
        return apply(track, base, strength);
    }

    public double[][] applyProgressGated(Track track, double[][] base) {
        if (!active(track)) return base;
        double floorChange = minHeight(track, 0, 6) - minHeight(track, 8, HISTORY);
        int sneak = 0;
        for (int age = 0; age < HISTORY; age++) if ((track.cue(age) & SNEAK) != 0) sneak++;
        double trust = floorChange >= .27 ? 1 : sneak >= 4 ? .55 : .28;
        return apply(track, base, trust);
    }

    public double[][] apply(Track track, double[][] base, double amount) {
        if (amount == 0 || !active(track)) return base;
        double[][] input = {features(track,0),features(track,1),features(track,2)};
        double[][] forecast = new double[base.length][3];
        double[][] anchors = new double[BowMotion.HORIZONS.length+1][3];
        for (int i = 0; i < weights.length; i++)
            for (int a = 0; a < 3; a++) {
                double[] coefficient = weights[i][a == 1 ? 1 : 0];
                double outcome = 0;
                for (int k = 0; k < FEATURES; k++) outcome += input[a][k]*coefficient[k];
                anchors[i+1][a] = outcome;
            }
        int high = 0, low = 0;
        for (int t = 0; t < base.length; t++) {
            while (t > BowMotion.HORIZONS[high] && high + 1 < weights.length) low = BowMotion.HORIZONS[high++];
            double f = Math.min(1, (t-low)/(double)(BowMotion.HORIZONS[high]-low));
            for (int a = 0; a < 3; a++) {
                double world = track.get(0,a) + (1-f)*anchors[high][a] + f*anchors[high+1][a];
                forecast[t][a] = (1-amount)*base[t][a] + amount*world;
            }
        }
        return forecast;
    }

    private static double velocity(Track track, int newer, int older, int a) {
        return (track.get(newer,a)-track.get(older,a))/(older-newer);
    }

    private static double minHeight(Track track, int first, int last) {
        double y = track.get(first,1);
        for (int k = first+1; k <= last; k++) y = Math.min(y,track.get(k,1));
        return y;
    }
}
