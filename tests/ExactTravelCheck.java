import lab.ExactTravel;
import java.io.BufferedReader;
import java.io.InputStreamReader;

/** Standalone regression tests; optional --predict-tsv differential-test bridge. */
public final class ExactTravelCheck {
    private static final ExactTravel.Environment FLOOR =
            new ExactTravel.Environment(4.0, .6f, true, true, false, false, false);
    private static final ExactTravel.Input IDLE = new ExactTravel.Input(0, 0, 0, .1f, .02f);

    private static ExactTravel.State state(double x, double y, double z,
                                           double vx, double vy, double vz, boolean ground) {
        return new ExactTravel.State(x, y, z, vx, vy, vz,
                ExactTravel.boxAt(x, y, z, .6f, 1.8f), ground,
                false, ground, ground, false, false, false);
    }
    private static void equal(double expected, double actual, String label) {
        if (Double.doubleToRawLongBits(expected) != Double.doubleToRawLongBits(actual))
            throw new AssertionError(label + ": " + Double.toHexString(expected)
                    + " != " + Double.toHexString(actual));
    }
    private static void check(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
    }
    private static void rejected(Runnable action, String label) {
        try { action.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("Accepted unsupported " + label);
    }
    private static void velocity(ExactTravel.State result, double x, double y, double z) {
        equal(x, result.vx(), "motionX"); equal(y, result.vy(), "motionY");
        equal(z, result.vz(), "motionZ");
    }

    private static void nativeGoldenVelocities() {
        // Archived native g-entry/g-return fixtures. Boxes were not logged in
        // these older traces, so they establish velocity parity only.
        // m0360-a, time1792811564633: first sprint tick, negative noncardinal yaw.
        ExactTravel.State start = state(4093, 4, .4095760777171238,
                0, -.0784000015258789, 0, true);
        ExactTravel.State result = ExactTravel.predict(start,
                new ExactTravel.Input(0, .98f, -93.90510559082031f, .13000001f, .02f), FLOOR);
        velocity(result, .06939930422542417, -.0784000015258789, -.004731347899962157);
        check(result.onGround() && result.isCollidedVertically(), "grounded collision");
        equal(4, result.y(), "standing feet");

        // m0362-b, time1792811654773: ground-origin S12 launch uses ground friction.
        result = ExactTravel.predict(state(4096.536925450151, 4, .12796703728772962,
                        .895125, .46075, -.091875, true),
                new ExactTravel.Input(0, .98f, 84.51667022705078f, .13000001f, .025999998673796654f), FLOOR);
        velocity(result, .41949671958045315, .3731350072622299, -.04351158668878973);
        check(!result.onGround() && !result.isCollided(), "takeoff is not a landing");

        // m0362-b, time1792811654821: following airborne tick.
        result = ExactTravel.predict(state(4097.305234371131, 4.46075, .04827549583204352,
                        .41949671958045315, .3731350072622299, -.04351158668878973, false),
                new ExactTravel.Input(0, .98f, 84.29623413085938f, .13000001f, .025999998673796654f), FLOOR);
        velocity(result, .35867017009054325, .28727231270809184, -.037289659228840616);

        // m0362-b, time1792811656364: landing still uses pre-move air friction.
        result = ExactTravel.predict(state(4108.848378502657, 4.1762311931132405, -1.2658537768838403,
                        .14623758911518675, -.4429242165297362, -.017460958329836483, false),
                new ExactTravel.Input(0, .98f, 83.71770477294922f, .13000001f, .025999998673796654f), FLOOR);
        velocity(result, .11002873518326975, -.0784000015258789, -.013351448943745282);
        equal(4, result.y(), "landed feet");
        check(result.onGround() && result.isCollidedVertically(), "landing flags");
    }

    private static void exactBoxHistoryAndBoundary() {
        // Two retained boxes can have equal position but differ after identical
        // offsets. Reconstructing either box from its center loses information.
        double half = (double) (.6f / 2.0f);
        ExactTravel.Box first = new ExactTravel.Box(-half, 5, -half, half, 6.8, half);
        ExactTravel.Box second = new ExactTravel.Box(Math.nextUp(-half), 5, -half,
                Math.nextDown(half), 6.8, half);
        ExactTravel.State a = new ExactTravel.State(0, 5, 0, .217209373088948, 0, 0,
                first, false, false, false, false, false, false, false);
        ExactTravel.State b = new ExactTravel.State(0, 5, 0, .217209373088948, 0, 0,
                second, false, false, false, false, false, false, false);
        ExactTravel.State pa = ExactTravel.predict(a, IDLE, FLOOR);
        ExactTravel.State pb = ExactTravel.predict(b, IDLE, FLOOR);
        equal(.21720937308894797, pa.x(), "retained first box center");
        equal(.217209373088948, pb.x(), "retained second box center");
        equal(pa.vx(), pb.vx(), "box history changes position, not velocity");
        check(a.box().equals(first) && b.box().equals(second), "input snapshots unchanged");
        check(ExactTravel.predict(a, IDLE, FLOOR).equals(pa), "repeatability");

        // Exactly touching the floor at the requested displacement is not a
        // clipped motion: native onGround is false until a later downward clip.
        ExactTravel.State touching = ExactTravel.predict(state(0, 4.125, 0, 0, -.125, 0, false), IDLE, FLOOR);
        equal(4, touching.y(), "exact-contact height");
        check(!touching.onGround() && !touching.isCollidedVertically(), "strict Y clipping");
        ExactTravel.State landed = ExactTravel.predict(touching, IDLE, FLOOR);
        check(landed.onGround() && landed.isCollidedVertically(), "next downward tick clips");

        // The .005 cutoff occurs before this conditional entry point. Applying
        // it again would erase this deliberately supplied nonzero g-entry value.
        ExactTravel.State small = ExactTravel.predict(state(0, 4, 0, .004, -.0784000015258789, 0, true), IDLE, FLOOR);
        equal(.0021840002536773682, small.vx(), "no duplicate motion cutoff");
    }

    private static void nativeGoldenBoxAndLanding() {
        // Fresh m0385-b time1792814453136: complete native retained box is
        // available. In particular maxY is not reconstructible as feet+height
        // with the same bits after this flight/landing history.
        ExactTravel.Box box = new ExactTravel.Box(4107.939248895042, 4.221225771052778,
                -1.238566397863624, 4108.5392489188835, 6.021225723369061, -.6385663740217664);
        ExactTravel.State before = new ExactTravel.State(4108.2392489069625, 4.221225771052778,
                -.9385663859426953, .2945134251718686, -.5169479491049732, -.02610739472862964,
                box, false, false, false, false, false, false, false);
        ExactTravel.State after = ExactTravel.predict(before,
                new ExactTravel.Input(0, .98f, 85.17096710205078f, .13000001f, .025999998673796654f), FLOOR);
        double[] actual = {after.x(), after.y(), after.z(), after.vx(), after.vy(), after.vz(),
                after.box().minX(), after.box().minY(), after.box().minZ(),
                after.box().maxX(), after.box().maxY(), after.box().maxZ()};
        double[] nativeValues = {4108.50837296442, 4, -.9625266094906296,
                .24490289934424267, -.0784000015258789, -.02180380405700212,
                4108.208372952499, 4, -1.2625266214115585,
                4108.808372976341, 5.799999952316283, -.6625265975697008};
        for (int i = 0; i < actual.length; i++) equal(nativeValues[i], actual[i], "native box/landing field" + i);
        check(after.onGround() && !after.isCollidedHorizontally()
                && after.isCollidedVertically() && after.isCollided(), "four native landing flags");
    }

    private static void sumoLipRecovery() {
        // m2197, 6.472 s. The box starts fully beyond the last block, still flagged
        // grounded from the previous move. Its inward travel steps back onto the rim.
        var floor = new ExactTravel.Environment(12, .6f, true, true, false, false, false,
                new ExactTravel.Disk(4096, 10));
        var box = new ExactTravel.Box(4101.048270047797, 12, -.30000001192092896,
                4101.648270071639, 13.799999952316284, .30000001192092896);
        var initial = new ExactTravel.State(4101.348270059718, 12, 0,
                .06591015984082216, -.0784000015258789, 0, box,
                true, false, true, true, false, false, false);
        var recovered = ExactTravel.predict(initial, new ExactTravel.Input(0, .98f, 90, .13000001f, .026f), floor);
        equal(4101.286780238537, recovered.x(), "native lip recovery x");
        equal(12, recovered.y(), "native lip recovery y");
        equal(-.033573446264502184, recovered.vx(), "native lip recovery velocity");
        check(recovered.onGround() && !recovered.isCollidedHorizontally(), "step onto disk");
        var fall = ExactTravel.predict(initial, IDLE, floor);
        check(fall.y() < 12 && !fall.onGround(), "outward momentum falls off disk");
    }

    private static void unsupportedInputs() {
        ExactTravel.State s = state(0, 4, 0, 0, -.0784000015258789, 0, true);
        for (int flag = 0; flag < 3; flag++) {
            ExactTravel.State bad = new ExactTravel.State(s.x(), s.y(), s.z(), s.vx(), s.vy(), s.vz(),
                    s.box(), true, false, true, true, flag == 0, flag == 1, flag == 2);
            rejected(() -> ExactTravel.predict(bad, IDLE, FLOOR), "movement flag" + flag);
        }
        for (int flag = 0; flag < 5; flag++) {
            ExactTravel.Environment bad = new ExactTravel.Environment(4, .6f,
                    flag != 0, flag != 1, flag == 2, flag == 3, flag == 4);
            rejected(() -> ExactTravel.predict(s, IDLE, bad), "environment flag" + flag);
        }
        rejected(() -> ExactTravel.predict(state(0, 3.9, 0, 0, 0, 0, true), IDLE, FLOOR), "penetration");
        rejected(() -> new ExactTravel.Input(0, Float.NaN, 0, .1f, .02f), "nonfinite input");
        rejected(() -> new ExactTravel.Box(1, 0, 0, -1, 1, 1), "unordered box");
    }

    /**
     * TSV input, exactly28 fields per row, without a header:
     * x y z vx vy vz ground minX minY minZ maxX maxY maxZ noClip inWeb sneaking
     * strafe forward yaw groundAccel airAccel floorTopY slipperiness
     * loadedBefore loadedAfter water lava ladder
     * Booleans are true/false. Float fields parse directly as floats. The output
     * has a header; UNSUPPORTED rows retain row alignment and carry a reason.
     */
    private static void predictTsv() throws Exception {
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
        System.out.println("status\tx\ty\tz\tvx\tvy\tvz\tminX\tminY\tminZ\tmaxX\tmaxY\tmaxZ\tonGround\tisCollidedHorizontally\tisCollidedVertically\tisCollided");
        String line;
        while ((line = reader.readLine()) != null) {
            try {
                String[] f = line.split("\t", -1);
                if (f.length != 28) throw new IllegalArgumentException("Expected28 fields");
                ExactTravel.Box box = new ExactTravel.Box(d(f, 7), d(f, 8), d(f, 9), d(f, 10), d(f, 11), d(f, 12));
                ExactTravel.State state = new ExactTravel.State(d(f, 0), d(f, 1), d(f, 2), d(f, 3), d(f, 4), d(f, 5),
                        box, b(f, 6), false, false, false, b(f, 13), b(f, 14), b(f, 15));
                ExactTravel.Input input = new ExactTravel.Input(p(f, 16), p(f, 17), p(f, 18), p(f, 19), p(f, 20));
                ExactTravel.Environment env = new ExactTravel.Environment(d(f, 21), p(f, 22),
                        b(f, 23), b(f, 24), b(f, 25), b(f, 26), b(f, 27));
                ExactTravel.State out = ExactTravel.predict(state, input, env);
                ExactTravel.Box q = out.box();
                System.out.println("OK\t" + out.x() + "\t" + out.y() + "\t" + out.z() + "\t"
                        + out.vx() + "\t" + out.vy() + "\t" + out.vz() + "\t"
                        + q.minX() + "\t" + q.minY() + "\t" + q.minZ() + "\t"
                        + q.maxX() + "\t" + q.maxY() + "\t" + q.maxZ() + "\t"
                        + out.onGround() + "\t" + out.isCollidedHorizontally() + "\t"
                        + out.isCollidedVertically() + "\t" + out.isCollided());
            } catch (IllegalArgumentException unsupported) {
                System.out.println("UNSUPPORTED\t" + unsupported.getMessage());
            }
        }
    }
    private static double d(String[] f, int i) { return Double.parseDouble(f[i]); }
    private static float p(String[] f, int i) { return Float.parseFloat(f[i]); }
    private static boolean b(String[] f, int i) {
        if (!f[i].equals("true") && !f[i].equals("false"))
            throw new IllegalArgumentException("Boolean must be true or false");
        return Boolean.parseBoolean(f[i]);
    }
    public static void main(String[] args) throws Exception {
        if (args.length == 1 && args[0].equals("--predict-tsv")) { predictTsv(); return; }
        if (args.length != 0) throw new IllegalArgumentException("Use no arguments or --predict-tsv");
        nativeGoldenVelocities(); exactBoxHistoryAndBoundary(); nativeGoldenBoxAndLanding(); sumoLipRecovery(); unsupportedInputs();
        System.out.println("ExactTravelCheck PASS: native golden velocities, box history, clipping, scope and purity");
    }
}
