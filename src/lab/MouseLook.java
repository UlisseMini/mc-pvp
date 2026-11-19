package lab;

/** Integer mouse input at the vanilla 1.8.9 look boundary, with normal camera mode. */
public final class MouseLook {
    public static final double DEGREES_PER_SECOND = 720.0;
    public static final double MAX_FRAME_MS = 50.0;

    public final int dx, dy;
    public final float yawInput, pitchInput;

    private MouseLook(int dx,int dy,float scale,boolean inverted) {
        this.dx=dx;
        this.dy=dy;
        yawInput=dx*scale;
        pitchInput=(dy*scale)*(inverted?-1:1);
    }

    public static MouseLook plan(float yaw,float pitch,float targetYaw,float targetPitch,
                                 float sensitivity,boolean inverted,double elapsedMs) {
        if(!(sensitivity>=0 && sensitivity<=1) || !Double.isFinite(elapsedMs))
            throw new IllegalArgumentException("Invalid mouse settings");
        float f=sensitivity*.6f+.2f;
        float scale=f*f*f*8f;
        double perCount=.15*scale;
        double x=((targetYaw-(double)yaw)%360+540)%360-180;
        double y=Math.max(-90,Math.min(90,targetPitch))-(double)pitch;
        double magnitude=Math.hypot(x,y);
        double limit=DEGREES_PER_SECOND*Math.max(0,Math.min(MAX_FRAME_MS,elapsedMs))/1000;
        double fraction=magnitude>limit?limit/magnitude:1;
        int dx=(int)Math.round(x*fraction/perCount);
        int dy=(int)Math.round((inverted?y:-y)*fraction/perCount);
        return new MouseLook(dx,dy,scale,inverted);
    }

    // Bytecode of Entity.setAngles uses double .15 and casts the result back to float.
    public float yawAfter(float previous) {return (float)(previous+yawInput*.15);}
    public float pitchAfter(float previous) {return Math.max(-90f,Math.min(90f,(float)(previous-pitchInput*.15)));}
}
