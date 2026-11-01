package lab;

/** Frozen own-observation controller, compatible with Java8.
 * Validated model scenario: horizontalKB1, verticalKB0, hitTicks2,
 * own/opponent RTT0/120, reach3/3. See strong-relaxation.md for scope.
 * Call step ONCE per native input tick. Keep the returned click gate open
 * between ticks and use the ordinary persistent frame-polled20CPS limiter.
 */
public final class StrongRelaxationPolicy {
    public static final class Control {
        public final int key; // 1 = W held WITHOUT Ctrl; 0 release,2 W+Ctrl.
        public final float strafe; // ordinary native damped A/D input.
        public final boolean click;
        private Control(int key,float strafe,boolean click){this.key=key;this.strafe=strafe;this.click=click;}
    }
    private final double nominal,gain,target;
    private final int key;
    private double accumulator;
    private int sign=1;
    public StrongRelaxationPolicy(){this(.283,2,2.55,1);}
    public StrongRelaxationPolicy(double nominal,double gain,double target,int key){this.nominal=nominal;this.gain=gain;this.target=target;this.key=key;}
    public Control step(double ownNearestRenderedTarget){
        double duty=Math.max(0,Math.min(1,nominal+gain*(target-ownNearestRenderedTarget)));
        accumulator+=duty;
        float strafe=0;
        if(accumulator>=1){accumulator-=1;strafe=sign*.98f;sign=-sign;}
        return new Control(key,strafe,true);
    }
}
