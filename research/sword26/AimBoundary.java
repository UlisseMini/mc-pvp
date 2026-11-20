package sword;

import java.util.Locale;
import java.util.HashMap;
import java.util.Map;
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

    static long lookup(float angle,boolean yaw){
        double scaled=((yaw?-angle:angle)*.017453292f)*FlatPhysics.SCALE;
        return ((long)scaled<<32)|((long)(scaled+16384)&0xffffffffL);
    }
    static int tableKey(long pair){
        return ((int)(pair>>>32)&65535)<<16|((int)pair&65535);
    }
    static void directions(Map<Integer,Float> out,boolean yaw,float low,float high,long a,long b){
        // The two unwrapped lookup indices are monotone in the input angle.
        // Equal endpoint pairs certify the entire intervening float interval.
        if(a==b){out.putIfAbsent(tableKey(a),low);return;}
        float mid=(float)((low+(double)high)/2);
        if(mid==low||mid==high){
            out.putIfAbsent(tableKey(a),low);out.putIfAbsent(tableKey(b),high);return;
        }
        long m=lookup(mid,yaw);
        directions(out,yaw,low,mid,a,m);directions(out,yaw,mid,high,m,b);
    }
    static float[] directions(boolean yaw,float limit){
        var samples=new HashMap<Integer,Float>();
        directions(samples,yaw,-limit,limit,lookup(-limit,yaw),lookup(limit,yaw));
        float[] values=new float[samples.size()];int i=0;
        for(float angle:samples.values())values[i++]=angle;
        return values;
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
        float[] pitches=directions(false,90);
        int feasibleHeadings=0;
        for(float yaw:directions(true,360)){
            var direction=Entity.calculateViewVector(0,yaw);
            // Native X/Z products are floats. Widen both horizontal faces by a
            // millionth of a block, beyond their rounding at this reach.
            double relaxed=horizontalEntry(box,eye,direction,1e-6);
            double bound=Math.hypot(relaxed,dy);
            lower=Math.min(lower,bound);
            double horizontal=horizontalEntry(box,eye,direction,0);
            if(bound<REACH){
                feasibleHeadings++;
                for(float p:pitches){
                    double d=clippedDistance(box,eye,yaw,p);
                    if(d<best){best=d;bestYaw=yaw;bestPitch=p;}
                }
            }else if(horizontal<=3.01){
                double pitch=-Math.toDegrees(Math.atan2(dy,horizontal));
                for(int j=-24;j<=24;j++){
                    float p=(float)Math.clamp(pitch+j*(360./65536)/4,-90,90);
                    double d=clippedDistance(box,eye,yaw,p);
                    if(d<best){best=d;bestYaw=yaw;bestPitch=p;}
                }
            }
        }
        double recorded=clippedDistance(box,eye,(float)n[6],(float)n[7]);
        System.out.printf(Locale.ROOT,"AIM_DISTANCE,%.12f,%.12f,%.12f,%.12f,%.8f,%.8f,%d,%d%n",
            Math.sqrt(box.distanceToSqr(eye)),recorded,lower,best,bestYaw,bestPitch,feasibleHeadings,pitches.length);
    }
}
