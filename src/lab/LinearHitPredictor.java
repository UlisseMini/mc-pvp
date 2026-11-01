package lab;

import java.util.ArrayDeque;
import java.util.Properties;

/** Short-horizon constant-velocity geometry, using only observations from this client. */
public final class LinearHitPredictor {
    public record Sample(long time,double x,double y,double z,double tx,double ty,double tz){}
    public record Settings(double viewLag,double bias,long horizon,int samples,double delivery){
        public static Settings from(Properties p){return new Settings(
            Double.parseDouble(p.getProperty("block_view_lag_ms","150")),
            Double.parseDouble(p.getProperty("block_prediction_bias_ms","0")),
            Long.parseLong(p.getProperty("block_prediction_horizon_ms","300")),
            Integer.parseInt(p.getProperty("block_fit_samples","3")),
            Double.parseDouble(p.getProperty("block_delivery_ms","0")));}
    }
    public record Prediction(double receiptAt,double commandAt,double eligibleAt,double contactAt,
                             double ownVx,double ownVy,double ownVz,double targetVx,double targetVy,double targetVz,
                             double opponentRangeNow){}
    final ArrayDeque<Sample> history=new ArrayDeque<>();
    record Forecast(long time,double receipt){}
    final ArrayDeque<Forecast> forecasts=new ArrayDeque<>();
    double correction;int calibrationCount;
    public record Calibration(double error,double correction,int count,long forecastTime){}
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
    static double axis(Sample s,int k){return switch(k){case 0->s.x;case 1->s.y;case 2->s.z;case 3->s.tx;case 4->s.ty;default->s.tz;};}
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
