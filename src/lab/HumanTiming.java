package lab;

/** Conditional action distribution learned from ordinary first-person inputs.
 * The final two parameters select a repeatable draw stream and the high-CPS sparring variant. */
public final class HumanTiming {
    public static final int INPUTS = 26;
    public static final int HIDDEN = 16;
    public static final int OUTPUTS = 8;
    public static final int PARAMETERS = HIDDEN * (INPUTS + 1) + OUTPUTS * (HIDDEN + 1);
    public static final int SIZE = PARAMETERS + 2;
    private final float[] weights;
    private final double[] x = new double[INPUTS];
    private final double[] h = new double[HIDDEN];
    private final double[] logits = new double[OUTPUTS];
    private long random;
    private int clickAge = 20, walkAge = 20, sideAge = 20, jumpAge = 20;
    private int lastWalk, lastSide;
    private boolean lastSampledClick;

    HumanTiming(float[] weights) {
        this.weights = weights;
        random = 0x5deece66dL ^ (long)weights[PARAMETERS];
    }

    private HumanTiming(HumanTiming source) {
        this(source.weights);
        random = source.random;
        clickAge = source.clickAge;
        walkAge = source.walkAge;
        sideAge = source.sideAge;
        jumpAge = source.jumpAge;
        lastWalk = source.lastWalk;
        lastSide = source.lastSide;
        lastSampledClick = source.lastSampledClick;
        System.arraycopy(source.x, 0, x, 0, INPUTS);
        System.arraycopy(source.h, 0, h, 0, HIDDEN);
        System.arraycopy(source.logits, 0, logits, 0, OUTPUTS);
    }

    HumanTiming copy() { return new HumanTiming(this); }
    public double[] inputs() { return x.clone(); }

    public static void forward(float[] weights, double[] inputs, double[] hidden, double[] scores) {
        int index = 0;
        for (int i = 0; i < HIDDEN; i++) {
            double v = weights[index++];
            for (double input : inputs) v += weights[index++] * input;
            hidden[i] = v / (1 + Math.abs(v));
        }
        for (int i = 0; i < OUTPUTS; i++) {
            double v = weights[index++];
            for (double input : hidden) v += weights[index++] * input;
            scores[i] = v;
        }
    }

    private double uniform() {
        random = (random * 0x5deece66dL + 0xbL) & ((1L << 48) - 1);
        return (random >>> 16) * 0x1.0p-32;
    }

    public static double sigmoid(double z) {
        return z >= 0 ? 1/(1+Math.exp(-z)) : Math.exp(z)/(1+Math.exp(z));
    }

    private int categorical(int offset) {
        double m = Math.max(logits[offset], Math.max(logits[offset+1], logits[offset+2]));
        double a = Math.exp(logits[offset]-m), b = Math.exp(logits[offset+1]-m);
        double draw = uniform() * (a+b+Math.exp(logits[offset+2]-m));
        return draw < a ? -1 : draw < a+b ? 0 : 1;
    }

    int step(double[] base, boolean grounded) {
        System.arraycopy(base, 0, x, 0, base.length);
        if (weights[PARAMETERS+1] != 0) x[18] = lastSampledClick ? 1 : -1;
        x[22] = Math.min(1,clickAge/5.0);
        x[23] = Math.min(1,walkAge/10.0);
        x[24] = Math.min(1,sideAge/10.0);
        x[25] = Math.min(1,jumpAge/15.0);
        forward(weights, x, h, logits);
        int walk = categorical(0), side = categorical(3);
        lastSampledClick = uniform() < sigmoid(logits[6]);
        boolean jump = uniform() < sigmoid(logits[7]) && grounded;
        boolean click = weights[PARAMETERS+1] != 0 || lastSampledClick;
        return DuelNetwork.pack(walk < 0 ? 3 : walk > 0 ? 2 : 0, side, click, jump);
    }

    void applied(int action) {
        // For high-CPS stress, retain the sampled private click rhythm while the physical gate stays open.
        boolean click = weights[PARAMETERS+1] != 0 ? lastSampledClick : DuelNetwork.click(action);
        int walk = DuelNetwork.key(action), side = DuelNetwork.side(action);
        clickAge = click ? 0 : Math.min(20,clickAge + 1);
        jumpAge = DuelNetwork.jump(action) ? 0 : Math.min(20,jumpAge + 1);
        walkAge = walk != lastWalk ? 0 : Math.min(20,walkAge + 1);
        sideAge = side != lastSide ? 0 : Math.min(20,sideAge + 1);
        lastWalk = walk;
        lastSide = side;
        lastSampledClick = click;
    }
}
