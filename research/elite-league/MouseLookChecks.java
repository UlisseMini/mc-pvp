import java.util.Random;
import lab.MouseLook;

/** Check the float/double order, wrap, inversion, pitch clamp and zero-time frames. */
final class MouseLookChecks {
    public static void main(String[] args){
        var random=new Random(910917);
        for(int i=0;i<10000;i++){
            float s=random.nextFloat(),yaw=random.nextFloat()*1440-720,pitch=random.nextFloat()*180-90;
            float ty=random.nextFloat()*720-360,tp=random.nextFloat()*270-135;
            boolean inv=random.nextBoolean();
            double dt=i%31==0?0:random.nextDouble()*90;
            var m=MouseLook.plan(yaw,pitch,ty,tp,s,inv,dt);
            var recorded=MouseLook.fromCounts(m.dx,m.dy,s,inv);
            if(recorded.yawAfter(yaw)!=m.yawAfter(yaw) || recorded.pitchAfter(pitch)!=m.pitchAfter(pitch))
                throw new AssertionError("Recorded integer input did not reproduce the planned movement");
            float f=s*.6f+.2f,scale=f*f*f*8;
            if(dt==0&&(m.dx!=0||m.dy!=0))throw new AssertionError("Mouse moved in zero time");
            float ax=(float)(yaw+(double)(m.dx*scale)*.15);
            float ay=Math.max(-90f,Math.min(90f,(float)(pitch-(double)(m.dy*scale*(inv?-1:1))*.15)));
            if(ax!=m.yawAfter(yaw)||ay!=m.pitchAfter(pitch))throw new AssertionError("Float order differs");
            double deg=Math.hypot(ax-yaw,ay-pitch);
            if(deg>720*Math.min(dt,50)/1000+Math.sqrt(.5)*scale*.15+.0001)
                throw new AssertionError("Mouse exceeded the elapsed turn budget");
            var flipped=MouseLook.plan(yaw,pitch,ty,tp,s,!inv,dt);
            if(Math.abs(m.pitchAfter(pitch)-flipped.pitchAfter(pitch))>scale*.15+.00001)throw new AssertionError("Inversion");
        }
        if(MouseLook.plan(179,0,-179,0,.5f,false,50).dx!=13)throw new AssertionError("Long turn across wrap");
        System.out.println("10000 bounded count-to-angle cases and yaw-wrap check passed");
    }
}
