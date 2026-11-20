package sword;

/** Instant visible-target lock on the flat sword court. All coordinates are relative to the own eye. */
final class PerfectAim {
    static final String PROFILE=System.getProperty("sword.aimProfile",Boolean.getBoolean("sword.smoothAim")?"smooth_mouse_v1":"tournament");
    static final boolean ENABLED=PROFILE.equals("perfect");
    static final double HALF=.6f/2, HEIGHT=1.8f;
    record Look(float yaw,float pitch){}

    static Look lock(double dx,double bottom,double dz,float fallbackYaw){
        // Prefer a stable point on the upper body. At the reach boundary the only
        // pickable region may be a side or corner, so aim inside that region.
        double x=dx,y=bottom+1.25,z=dz;
        double center=x*x+y*y+z*z;
        boolean grazing=false;
        if(center>9){
            double nx=Math.clamp(0.,dx-HALF,dx+HALF);
            double ny=Math.clamp(0.,bottom,bottom+HEIGHT);
            double nz=Math.clamp(0.,dz-HALF,dz+HALF);
            double near=nx*nx+ny*ny+nz*nz;
            if(near<=9){
                grazing=true;
                double distance=Math.sqrt(near);
                double t=Math.clamp((2.9999-distance)/(Math.sqrt(center)-distance),0,1);
                x=nx+t*(x-nx);y=ny+t*(y-ny);z=nz+t*(z-nz);
            }
        }
        double horizontal=Math.sqrt(x*x+z*z);
        float yaw=horizontal<1e-8?fallbackYaw:(float)(Math.toDegrees(Math.atan2(z,x))-90);
        var look=new Look(yaw,(float)-Math.toDegrees(Math.atan2(y,horizontal)));
        if(grazing&&!picks(dx,bottom,dz,look)){
            // Minecraft quantizes each sine lookup. At a barely reachable corner, the
            // adjacent native angle can hit a face that the rounded center ray misses.
            float step=360f/65536;
            for(int radius=1;radius<=3;radius++){
                for(int a=-radius;a<=radius;a++)for(int b=-radius;b<=radius;b++){
                    if(Math.max(Math.abs(a),Math.abs(b))!=radius)continue;
                    var adjacent=new Look(look.yaw()+a*step,Math.clamp(look.pitch()+b*step,-90,90));
                    if(picks(dx,bottom,dz,adjacent))return adjacent;
                }
            }
        }
        return look;
    }
    static boolean picks(double x,double bottom,double z,Look look){
        double yaw=FlatPhysics.angle(look.yaw()),pitch=FlatPhysics.angle(look.pitch());
        return Model.rayBox(x,bottom,z,FlatPhysics.sin(-yaw)*FlatPhysics.cos(pitch),
            -FlatPhysics.sin(pitch),FlatPhysics.cos(-yaw)*FlatPhysics.cos(pitch));
    }
}
