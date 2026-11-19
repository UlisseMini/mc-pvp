package lab;

/** Frozen, head-on jump policy. Inputs are sampled by the controlling client. */
public final class SumoJumpPolicy {
    private SumoJumpPolicy() {}
    public static int state(boolean grounded, boolean forward, double dx, double dz,
                            double targetHeight, double vx, double vz) {
        if (!grounded || !forward) return -1;
        double gap = Math.hypot(dx, dz);
        int band = gap < 2.35 ? 0 : gap < 3.4 ? 1 : gap < 4.75 ? 2 : 3;
        double approach = gap > 0 ? (vx * dx + vz * dz) / gap : 0;
        return band + (approach < -.015 ? 4 : 0) + (targetHeight > .25 ? 8 : 0);
    }
    public static boolean jump(int mask, int state) {
        return state >= 0 && state < 16 && (mask & (1 << state)) != 0;
    }
}
