import lab.AimController;
import java.util.Random;

public final class AimControllerCheck {
    private static void check(boolean v,String message){if(!v)throw new AssertionError(message);}
    private static double wrap(double a){return (a%360+540)%360-180;}
    public static void main(String[] args){
        AimController perfect=new AimController();AimController.Settings zero=new AimController.Settings(0,0,0,1);Random random=new Random(517);
        for(int i=0;i<2000;i++){
            double x=random.nextDouble()*20,z=random.nextDouble()*20,eye=4+random.nextDouble()*4,tx=random.nextDouble()*20,tz=random.nextDouble()*20,ty=4+random.nextDouble()*2;
            AimController.Result result=perfect.aim(i,x,eye,z,tx,ty,tz,0,0,zero);
            double dx=tx-x,dz=tz-z,targetEye=Math.max(ty+.15,Math.min(eye,ty+1.65));
            float yaw=(float)Math.toDegrees(Math.atan2(-dx,dz)),pitch=(float)-Math.toDegrees(Math.atan2(targetEye-eye,Math.hypot(dx,dz)));
            check(Float.floatToIntBits(result.yaw)==Float.floatToIntBits(yaw)&&Float.floatToIntBits(result.pitch)==Float.floatToIntBits(pitch),"zero settings preserve exact original aim");
        }
        AimController delayed=new AimController();
        for(int i=0;i<=300;i+=10){AimController.Result r=delayed.aim(i,0,5.62,0,i/100.0,4,5,0,0,new AimController.Settings(120,0,0,1));
            check(r.sampleAge==Math.min(i,120),"explicit local sample delay");
            double target=Math.max(0,i-120)/100.0;check(Math.abs(r.yaw-Math.toDegrees(Math.atan2(-target,5)))<1e-5,"delayed target coordinates only");}
        AimController first=new AimController(),second=new AimController();float yaw=179,pitch=0;
        AimController.Settings noisy=new AimController.Settings(60,4,90,191);
        double lastYaw=0,lastPitch=0;
        for(int t=0;t<10000;t+=5){
            AimController.Result a=first.aim(t,0,5.62,0,Math.sin(t/200.0)*5,4,-5,yaw,pitch,noisy);
            AimController.Result b=second.aim(t,0,5.62,0,Math.sin(t/200.0)*5,4,-5,yaw,pitch,noisy);
            check(a.yaw==b.yaw&&a.pitch==b.pitch,"same seed and observations reproduce tracking");
            check(Math.hypot(wrap(a.yaw-yaw),a.pitch-pitch)<=.45003,"combined turn rate is bounded across wrap");
            check(Math.abs(a.noiseYaw)<=4&&Math.abs(a.noisePitch)<=4,"bounded correlated noise");
            if(t>0)check(Math.abs(a.noiseYaw-lastYaw)<.31&&Math.abs(a.noisePitch-lastPitch)<.31,"noise is smooth across segment boundaries");
            lastYaw=a.noiseYaw;lastPitch=a.noisePitch;yaw=a.yaw;pitch=a.pitch;
        }
        first.reset();check(first.aim(0,0,5.62,0,0,4,5,0,0,zero).sampleAge==0,"reset discards target history");
        for(double[] bad:new double[][]{{-1,0,0},{501,0,0},{0,11,0},{0,0,20},{0,0,1441},{Double.NaN,0,0}}){boolean rejected=false;
            try{new AimController.Settings(bad[0],bad[1],bad[2],1);}catch(IllegalArgumentException expected){rejected=true;}check(rejected,"reject invalid aim parameters");}
        System.out.println("PASS aim: 2000 exact default cases, delayed local samples, deterministic smooth noise, bounded turning, reset and invalid settings");
    }
}
