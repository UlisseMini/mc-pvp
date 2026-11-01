package lab;

import java.util.ArrayDeque;
import java.util.Properties;

/** Short-horizon constant-velocity geometry, using only observations from this client. */
public final class LinearHitPredictor {
    public static final class Sample {
        private final long time; private final double x; private final double y; private final double z; private final double tx; private final double ty; private final double tz;
        public Sample(long time, double x, double y, double z, double tx, double ty, double tz){this.time=time; this.x=x; this.y=y; this.z=z; this.tx=tx; this.ty=ty; this.tz=tz;}
        public long time(){return time;} public double x(){return x;} public double y(){return y;} public double z(){return z;} public double tx(){return tx;} public double ty(){return ty;} public double tz(){return tz;}
        @Override public boolean equals(Object o){if(!(o instanceof Sample))return false;Sample that=(Sample)o;return java.util.Objects.equals(time, that.time) && java.util.Objects.equals(x, that.x) && java.util.Objects.equals(y, that.y) && java.util.Objects.equals(z, that.z) && java.util.Objects.equals(tx, that.tx) && java.util.Objects.equals(ty, that.ty) && java.util.Objects.equals(tz, that.tz);} @Override public int hashCode(){return java.util.Objects.hash(time,x,y,z,tx,ty,tz);}
}
    public static final class Settings {
        private final double viewLag; private final double bias; private final long horizon; private final int samples; private final double delivery;
        public Settings(double viewLag, double bias, long horizon, int samples, double delivery){this.viewLag=viewLag; this.bias=bias; this.horizon=horizon; this.samples=samples; this.delivery=delivery;}
        public double viewLag(){return viewLag;} public double bias(){return bias;} public long horizon(){return horizon;} public int samples(){return samples;} public double delivery(){return delivery;}
        @Override public boolean equals(Object o){if(!(o instanceof Settings))return false;Settings that=(Settings)o;return java.util.Objects.equals(viewLag, that.viewLag) && java.util.Objects.equals(bias, that.bias) && java.util.Objects.equals(horizon, that.horizon) && java.util.Objects.equals(samples, that.samples) && java.util.Objects.equals(delivery, that.delivery);} @Override public int hashCode(){return java.util.Objects.hash(viewLag,bias,horizon,samples,delivery);}

        public static Settings from(Properties p){return new Settings(
            Double.parseDouble(p.getProperty("block_view_lag_ms","150")),
            Double.parseDouble(p.getProperty("block_prediction_bias_ms","0")),
            Long.parseLong(p.getProperty("block_prediction_horizon_ms","300")),
            Integer.parseInt(p.getProperty("block_fit_samples","3")),
            Double.parseDouble(p.getProperty("block_delivery_ms","0")));}
    }
    public static final class Prediction {
        private final double receiptAt; private final double commandAt; private final double eligibleAt; private final double contactAt; private final double ownVx; private final double ownVy; private final double ownVz; private final double targetVx; private final double targetVy; private final double targetVz; private final double opponentRangeNow;
        public Prediction(double receiptAt, double commandAt, double eligibleAt, double contactAt, double ownVx, double ownVy, double ownVz, double targetVx, double targetVy, double targetVz, double opponentRangeNow){this.receiptAt=receiptAt; this.commandAt=commandAt; this.eligibleAt=eligibleAt; this.contactAt=contactAt; this.ownVx=ownVx; this.ownVy=ownVy; this.ownVz=ownVz; this.targetVx=targetVx; this.targetVy=targetVy; this.targetVz=targetVz; this.opponentRangeNow=opponentRangeNow;}
        public double receiptAt(){return receiptAt;} public double commandAt(){return commandAt;} public double eligibleAt(){return eligibleAt;} public double contactAt(){return contactAt;} public double ownVx(){return ownVx;} public double ownVy(){return ownVy;} public double ownVz(){return ownVz;} public double targetVx(){return targetVx;} public double targetVy(){return targetVy;} public double targetVz(){return targetVz;} public double opponentRangeNow(){return opponentRangeNow;}
        @Override public boolean equals(Object o){if(!(o instanceof Prediction))return false;Prediction that=(Prediction)o;return java.util.Objects.equals(receiptAt, that.receiptAt) && java.util.Objects.equals(commandAt, that.commandAt) && java.util.Objects.equals(eligibleAt, that.eligibleAt) && java.util.Objects.equals(contactAt, that.contactAt) && java.util.Objects.equals(ownVx, that.ownVx) && java.util.Objects.equals(ownVy, that.ownVy) && java.util.Objects.equals(ownVz, that.ownVz) && java.util.Objects.equals(targetVx, that.targetVx) && java.util.Objects.equals(targetVy, that.targetVy) && java.util.Objects.equals(targetVz, that.targetVz) && java.util.Objects.equals(opponentRangeNow, that.opponentRangeNow);} @Override public int hashCode(){return java.util.Objects.hash(receiptAt,commandAt,eligibleAt,contactAt,ownVx,ownVy,ownVz,targetVx,targetVy,targetVz,opponentRangeNow);}
}
    final ArrayDeque<Sample> history=new ArrayDeque<>();
     static final class Forecast {
        private final long time; private final double receipt;
        public Forecast(long time, double receipt){this.time=time; this.receipt=receipt;}
        public long time(){return time;} public double receipt(){return receipt;}
        @Override public boolean equals(Object o){if(!(o instanceof Forecast))return false;Forecast that=(Forecast)o;return java.util.Objects.equals(time, that.time) && java.util.Objects.equals(receipt, that.receipt);} @Override public int hashCode(){return java.util.Objects.hash(time,receipt);}
}
    final ArrayDeque<Forecast> forecasts=new ArrayDeque<>();
    double correction;int calibrationCount;
    public static final class Calibration {
        private final double error; private final double correction; private final int count; private final long forecastTime;
        public Calibration(double error, double correction, int count, long forecastTime){this.error=error; this.correction=correction; this.count=count; this.forecastTime=forecastTime;}
        public double error(){return error;} public double correction(){return correction;} public int count(){return count;} public long forecastTime(){return forecastTime;}
        @Override public boolean equals(Object o){if(!(o instanceof Calibration))return false;Calibration that=(Calibration)o;return java.util.Objects.equals(error, that.error) && java.util.Objects.equals(correction, that.correction) && java.util.Objects.equals(count, that.count) && java.util.Objects.equals(forecastTime, that.forecastTime);} @Override public int hashCode(){return java.util.Objects.hash(error,correction,count,forecastTime);}
}
    public void reset(){history.clear();forecasts.clear();correction=0;calibrationCount=0;}
    /** Update a single intercept from a forecast genuinely issued before this cue. */
    public Calibration hurt(long cue,double ownRtt){
        long cutoff=cue-(long)Math.min(250,ownRtt+50);Forecast chosen=null;
        for(Forecast f:forecasts)if(f.time<=cutoff)chosen=f;else break;
        if(chosen==null||cutoff-chosen.time>75||!Double.isFinite(chosen.receipt))return null;
        double error=cue-chosen.receipt;
        correction=Math.max(-100,Math.min(100,correction+.2*Math.max(-150,Math.min(150,error))));
        return new Calibration(error,correction,++calibrationCount,chosen.time);
    }
    static double axis(Sample s,int k){switch(k){case 0: return s.x;
case 1: return s.y;
case 2: return s.z;
case 3: return s.tx;
case 4: return s.ty;
default: return s.tz;
}}
    /** Ordinary least squares of position against seconds, no physics simulator or future samples. */
    double velocity(int k){
        if(history.size()<2)return 0;
        double meanT=0,meanX=0;long base=history.getLast().time;
        for(Sample s:history){meanT+=(s.time-base)/1000.0;meanX+=axis(s,k);}
        meanT/=history.size();meanX/=history.size();double numerator=0,denominator=0;
        for(Sample s:history){double dt=(s.time-base)/1000.0-meanT;numerator+=dt*(axis(s,k)-meanX);denominator+=dt*dt;}
        return denominator>0?Math.max(-15,Math.min(15,numerator/denominator)):0;
    }
    /** Opponent eye to our expanded player box, at an opponent-local attack time. */
    public static double range(Sample s,double[] v,double future,double lag){
        double x=s.x+v[0]*(future-lag),y=Math.max(4,s.y+v[1]*(future-lag)),z=s.z+v[2]*(future-lag);
        double tx=s.tx+v[3]*(future+lag),ty=Math.max(4,s.ty+v[4]*(future+lag)),tz=s.tz+v[5]*(future+lag);
        double dx=Math.max(0,Math.abs(tx-x)-.4),dz=Math.max(0,Math.abs(tz-z)-.4);
        double eye=ty+1.62,dy=Math.max(0,Math.max(y-.1-eye,eye-y-1.9));
        return Math.sqrt(dx*dx+dy*dy+dz*dz);
    }
    public Prediction predict(Sample s,long freshHurt,double ownRtt,double opponentRtt,Settings settings){
        if(history.isEmpty()||s.time>history.getLast().time)history.addLast(s);
        while(history.size()>Math.max(2,Math.min(5,settings.samples))||history.size()>1&&s.time-history.getFirst().time>250)history.removeFirst();
        double[] v=new double[6];for(int k=0;k<6;k++)v[k]=velocity(k);
        double transit=(ownRtt+opponentRtt)/2,lag=(settings.viewLag+transit)/1000;
        double eligible=freshHurt>0?freshHurt+500:s.time;
        // Search only a few future client ticks. Longer linear extrapolation is
        // inappropriate across knockback reversals. Transit maps enemy click
        // time into the future time when its fresh-hurt cue reaches this client.
        double contact=Double.NaN,receipt=Double.NaN;
        for(double cue=Math.max(s.time,eligible);cue<=s.time+settings.horizon;cue+=5){
            double future=(cue-s.time-transit)/1000;
            if(range(s,v,future,lag)<=3){contact=cue;receipt=cue+settings.bias+correction;break;}
        }
        if(forecasts.isEmpty()||s.time>forecasts.getLast().time)forecasts.addLast(new Forecast(s.time,receipt));
        while(!forecasts.isEmpty()&&s.time-forecasts.getFirst().time>1000)forecasts.removeFirst();
        // Key polling, server task queues and cue dispatch add delay beyond the
        // configured network RTT. Measure this separately from forecast error.
        return new Prediction(receipt,receipt-ownRtt-settings.delivery,eligible,contact,v[0],v[1],v[2],v[3],v[4],v[5],range(s,v,0,lag));
    }
}
