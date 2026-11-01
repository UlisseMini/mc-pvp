package lab;

import java.util.ArrayDeque;
import java.util.Properties;

/** Local-view tracking with optional delay, correlated error and bounded turning. */
public final class AimController {
    public static final class Settings {
        public final double delayMs,noiseDegrees,turnRate;
        public final long seed;
        public Settings(double delay,double noise,double rate,long seed){
            if(!Double.isFinite(delay)||delay<0||delay>500||!Double.isFinite(noise)||noise<0||noise>10
                ||!Double.isFinite(rate)||rate!=0&&(rate<30||rate>1440))throw new IllegalArgumentException("Invalid aim settings");
            delayMs=delay;noiseDegrees=noise;turnRate=rate;this.seed=seed;
        }
        public static Settings from(Properties p){return new Settings(Double.parseDouble(p.getProperty("aim_delay_ms","0")),
            Double.parseDouble(p.getProperty("aim_noise_deg","0")),Double.parseDouble(p.getProperty("aim_turn_rate_dps","0")),
            Long.parseLong(p.getProperty("aim_seed","1")));}
    }
    private static final class Target {
        final long time;final double x,y,z;
        Target(long t,double x,double y,double z){time=t;this.x=x;this.y=y;this.z=z;}
    }
    public static final class Result {
        public final float yaw,pitch,idealYaw,idealPitch;
        public final double sampleAge,noiseYaw,noisePitch,errorDegrees;
        Result(float yaw,float pitch,float iy,float ip,double age,double ny,double np){
            this.yaw=yaw;this.pitch=pitch;idealYaw=iy;idealPitch=ip;sampleAge=age;noiseYaw=ny;noisePitch=np;
            errorDegrees=Math.hypot(wrap(yaw-iy),pitch-ip);
        }
    }
    private final ArrayDeque<Target> history=new ArrayDeque<>();
    private long previousTime=Long.MIN_VALUE,origin;
    public void reset(){history.clear();previousTime=Long.MIN_VALUE;origin=0;}
    public Result aim(long now,double x,double eye,double z,double tx,double ty,double tz,float currentYaw,float currentPitch,Settings settings){
        if(previousTime!=Long.MIN_VALUE&&now<previousTime)throw new IllegalArgumentException("Aim clock moved backwards");
        if(history.isEmpty())origin=now;
        if(!history.isEmpty()&&history.peekLast().time==now)history.removeLast();
        history.addLast(new Target(now,tx,ty,tz));
        while(history.size()>1&&history.peekFirst().time<now-1000)history.removeFirst();
        Target sample=history.peekFirst();double desiredTime=now-settings.delayMs;
        for(Target target:history){if(target.time>desiredTime)break;sample=target;}
        float[] ideal=angles(x,eye,z,tx,ty,tz),delayed=angles(x,eye,z,sample.x,sample.y,sample.z);
        double ny=settings.noiseDegrees*noise(now-origin,settings.seed),np=settings.noiseDegrees*noise(now-origin,settings.seed^0x632be59bd9b4e019L);
        double yaw=settings.noiseDegrees==0?delayed[0]:delayed[0]+ny;
        double pitch=settings.noiseDegrees==0?delayed[1]:Math.max(-90,Math.min(90,delayed[1]+np));
        if(settings.turnRate>0){
            double dt=previousTime==Long.MIN_VALUE?0:Math.max(0,now-previousTime)/1000.0;
            double dy=wrap(yaw-currentYaw),dp=pitch-currentPitch,length=Math.hypot(dy,dp),limit=settings.turnRate*dt;
            double scale=length>limit&&length>0?limit/length:1;
            yaw=currentYaw+dy*scale;pitch=currentPitch+dp*scale;
        }
        previousTime=now;
        return new Result((float)yaw,(float)pitch,ideal[0],ideal[1],now-sample.time,ny,np);
    }
    private static float[] angles(double x,double eye,double z,double tx,double ty,double tz){
        double dx=tx-x,dz=tz-z,targetEye=Math.max(ty+.15,Math.min(eye,ty+1.65));
        return new float[]{(float)Math.toDegrees(Math.atan2(-dx,dz)),(float)-Math.toDegrees(Math.atan2(targetEye-eye,Math.hypot(dx,dz)))};
    }
    static double wrap(double degrees){return (degrees%360+540)%360-180;}
    private static double noise(long elapsed,long seed){
        long segment=elapsed/200;double t=(elapsed%200)/200.0,smooth=t*t*(3-2*t);
        return value(seed,segment)*(1-smooth)+value(seed,segment+1)*smooth;
    }
    private static double value(long seed,long segment){
        long z=seed+0x9e3779b97f4a7c15L*(segment+1);z=(z^(z>>>30))*0xbf58476d1ce4e5b9L;z=(z^(z>>>27))*0x94d049bb133111ebL;z^=z>>>31;
        return (z>>>11)*0x1.0p-52-1;
    }
}
