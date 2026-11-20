package sword;

/** Java Edition 26.3 flat stone court. All coordinates are world coordinates.
 * The single-precision operations here also occur at single precision in Java Edition. */
final class FlatPhysics {
    static final double FLOOR = 101;
    static final double LIMIT = 12.0 - .6f / 2;
    static final double SCALE = 10430.378350470453;
    private static final float[] sine = new float[65536];
    static {
        for (int i = 0; i < sine.length; i++) sine[i] = (float)Math.sin(i / SCALE);
    }
    static double angle(float degrees) { return degrees * .017453292f; }
    static float sin(double radians) { return sine[(int)((long)(radians * SCALE) & 65535)]; }
    static float cos(double radians) { return sine[(int)((long)(radians * SCALE + 16384) & 65535)]; }

    static class Motion {
        double x, y = FLOOR, z, vx, vy = -.08 * .98f, vz;
        float yaw;
        boolean ground = true, blocksSprint;
        void set(double x, double y, double z) { this.x = x; this.y = y; this.z = z; }
        void jump(boolean sprint) {
            vy = Math.max(vy, .42f);
            if (sprint) {
                vx -= sin(angle(yaw)) * .2;
                vz += cos(angle(yaw)) * .2;
            }
        }
        void snapSmallVelocity() {
            if (vx*vx + vz*vz < 9e-6) { vx = 0; vz = 0; }
            if (Math.abs(vy) < .003) vy = 0;
        }
        void travel(int forward, int side, boolean sprint) {
            float speed = ground ? (sprint ? (float)(.1f*(1.+.3f)) : .1f) * (.21600002f / (.6f*.6f*.6f))
                                 : (sprint ? .025999999f : .02f);
            // KeyboardInput normalizes the stick. LocalPlayer scales it onto a square,
            // then Entity caps its length. Digital diagonal movement reaches unit length.
            double component = forward != 0 && side != 0 ? DIAGONAL : .98f;
            double sx = side * component * speed, sz = forward * component * speed;
            double sin = sin(angle(yaw)), cos = cos(angle(yaw));
            vx += sx * cos - sz * sin;
            vz += sz * cos + sx * sin;
            double drag = ground ? .6f * .91f : .91f;
            double mx = Math.clamp(vx, -LIMIT-x, LIMIT-x);
            double mz = Math.clamp(vz, -LIMIT-z, LIMIT-z);
            double my = Math.max(vy, FLOOR-y);
            boolean wall = Math.abs(vx-mx) >= 1e-5 || Math.abs(vz-mz) >= 1e-5;
            blocksSprint = wall && !minorCollision(side, forward, sin, cos, mx, mz);
            ground = vy < 0 && vy != my;
            if (mx*mx+my*my+mz*mz > 1e-7 || vx*vx+vy*vy+vz*vz - (mx*mx+my*my+mz*mz) < 1e-7) {
                x += mx; y += my; z += mz;
            }
            if (mx != vx) vx = 0;
            if (mz != vz) vz = 0;
            if (my != vy) vy = 0;
            vx *= drag; vz *= drag; vy = (vy - .08) * .98f;
        }
        // LocalPlayer preserves sprint when the clipped displacement is within
        // eight degrees of the requested keyboard direction along a wall.
        static boolean minorCollision(int side,int forward,double sin,double cos,double mx,double mz){
            double ix=side*cos-forward*sin,iz=forward*cos+side*sin;
            double inputSq=ix*ix+iz*iz,moveSq=mx*mx+mz*mz;
            if(inputSq<1e-5f||moveSq<1e-5f)return false;
            return Math.acos((ix*mx+iz*mz)/Math.sqrt(inputSq*moveSq))<.13962633907794952;
        }
    }
    // Derive the exact binary diagonal that LocalPlayer produces for a digital keyboard.
    private static final double DIAGONAL;
    static {
        float v = (1f/(float)Math.sqrt(2f)) * .98f;
        float length = (float)Math.sqrt(v*v+v*v);
        float n = v * (1f/length) * Math.min(1f, length*(float)Math.sqrt(2f));
        double lengthSquared = (double)n*n + (double)n*n;
        DIAGONAL = lengthSquared > 1 ? n/Math.sqrt(lengthSquared) : n;
    }
    static final class Impulse {
        double x, y, z;
        void set(double vx,double vy,double vz) { x=vx; y=vy; z=vz; }
        void push(double strength, double dx, double dz, boolean ground) {
            double length = Math.sqrt(dx*dx+dz*dz);
            x = x/2 - (dx/length)*strength;
            z = z/2 - (dz/length)*strength;
            if (ground) y = Math.min(.4, y/2+strength);
        }
        // The direction of a completely overlapping hit is sampled by the caller.
        void hit(double vx,double vy,double vz,boolean ground,double dx,double dz,float yaw,boolean fresh,boolean boosted) {
            set(vx,vy,vz);
            if (fresh) push(.4f, -dx, -dz, ground);
            if (boosted) push(.5, sin(angle(yaw)), -cos(angle(yaw)), ground);
        }
        void wire() {
            double max = Math.max(Math.abs(x), Math.max(Math.abs(y), Math.abs(z)));
            if (max < 3.051944088384301e-5) { set(0,0,0); return; }
            double scale = Math.ceil(max);
            set(component(x,scale),component(y,scale),component(z,scale));
        }
        private static double component(double value,double scale) {
            long code = Math.round((value/scale*.5+.5)*32766);
            return (code*2./32766.-1)*scale;
        }
    }
    static float rawDamage(float charge,boolean critical) {
        float damage = 7f * (.2f + charge*charge*.8f);
        return critical ? damage*1.5f : damage;
    }
    static float armored(float raw) {
        float reduction = Math.clamp(20f - raw/4f, 4f, 20f)/25f;
        return raw*(1f-reduction);
    }
}
