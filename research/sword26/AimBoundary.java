package sword;

import java.util.Locale;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Offline audit using the stock 26.3 view vector and clipping code. */
public final class AimBoundary {
    static final double REACH=3, CAST=4.5;

    static double horizontalEntry(AABB box, Vec3 eye, Vec3 direction, double padding) {
        double length=Math.hypot(direction.x,direction.z);
        double near=0, far=CAST;
        for(int axis=0;axis<2;axis++){
            double lo=(axis==0?box.minX-eye.x:box.minZ-eye.z)-padding;
            double hi=(axis==0?box.maxX-eye.x:box.maxZ-eye.z)+padding;
            double d=(axis==0?direction.x:direction.z)/length;
            if(Math.abs(d)<1e-12){
                if(lo>0||hi<0)return Double.POSITIVE_INFINITY;
            }else{
                near=Math.max(near,Math.min(lo/d,hi/d));
                far=Math.min(far,Math.max(lo/d,hi/d));
            }
        }
        return near<=far?near:Double.POSITIVE_INFINITY;
    }

    static double clippedDistance(AABB box,Vec3 eye,float yaw,float pitch){
        if(box.contains(eye))return 0;
        var end=eye.add(Entity.calculateViewVector(pitch,yaw).scale(CAST));
        return box.clip(eye,end).map(eye::distanceTo).orElse(Double.POSITIVE_INFINITY);
    }

    public static void main(String[] args){
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        double[] n=java.util.Arrays.stream(args).mapToDouble(Double::parseDouble).toArray();
        var eye=new Vec3(n[0],n[1],n[2]);double half=.6f/2;
        var box=new AABB(n[3]-half,n[4],n[5]-half,n[3]+half,n[4]+1.8f,n[5]+half);
        double dy=Math.clamp(eye.y,box.minY,box.maxY)-eye.y;
        double lower=Double.POSITIVE_INFINITY, best=Double.POSITIVE_INFINITY;
        float bestYaw=0,bestPitch=0;
        // Positive and negative revolutions cover the two ways that truncation
        // of sine and cosine lookup indices can treat equivalent bearings.
        for(int i=-65536;i<65536;i++){
            float yaw=(float)((i+.5)*360/65536.);
            var direction=Entity.calculateViewVector(0,yaw);
            // Native X/Z products are floats. Widen both horizontal faces by a
            // millionth of a block, beyond their rounding at this reach.
            double relaxed=horizontalEntry(box,eye,direction,1e-6);
            lower=Math.min(lower,Math.hypot(relaxed,dy));
            double horizontal=horizontalEntry(box,eye,direction,0);
            if(horizontal>3.01)continue;
            double pitch=-Math.toDegrees(Math.atan2(dy,horizontal));
            for(int j=-24;j<=24;j++){
                float p=(float)Math.clamp(pitch+j*(360./65536)/4,-90,90);
                double d=clippedDistance(box,eye,yaw,p);
                if(d<best){best=d;bestYaw=yaw;bestPitch=p;}
            }
        }
        double recorded=clippedDistance(box,eye,(float)n[6],(float)n[7]);
        System.out.printf(Locale.ROOT,"AIM_DISTANCE,%.12f,%.12f,%.12f,%.12f,%.8f,%.8f%n",
            Math.sqrt(box.distanceToSqr(eye)),recorded,lower,best,bestYaw,bestPitch);
    }
}
