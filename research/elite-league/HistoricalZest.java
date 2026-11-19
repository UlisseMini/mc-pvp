import java.util.Properties;

/** Archived Zest settings, mirrored from the Client's own delayed hurt and spacing observations. */
final class HistoricalZest implements Cloneable {
    record Decision(boolean forward,int side,boolean click){}
    final Properties config;
    boolean selecting,opened;
    long entryAt,release,resume,incomingCue,landedCue;
    int phase,unanswered;
    HistoricalZest(Properties p){config=p;selecting=p.getProperty("entry","select").equals("select");}
    HistoricalZest copy(){
        try{return (HistoricalZest)super.clone();}catch(CloneNotSupportedException e){throw new AssertionError(e);}
    }
    double v(String key,double fallback){String s=config.getProperty(key);return s==null?fallback:Double.parseDouble(s);}
    Decision decide(HistoricalMovement.Head h,int rtt,int otherRtt,boolean forward){
        long now=h.now();boolean got=h.incoming()>incomingCue,landed=h.landed()>landedCue;
        if(got){
            boolean lost=unanswered>=2 && v("recover",1)>0;incomingCue=h.incoming();unanswered=0;
            if(selecting||lost){selecting=true;entryAt=now+(long)v("select_delay_ms",0);phase=0;release=resume=0;}
        }
        if(landed){
            landedCue=h.landed();unanswered++;int chain=Math.max(2,Math.min(3,(int)v("cycle_hits",3)));phase=(phase+1)%chain;
            if(phase==chain-1){
                release=Math.max(now,landedCue+(long)(v("hit_window_ms",500)-rtt-v("release_lead_ms",100)));
                resume=release+(long)v("release_ms",200);
            }
        }
        if(selecting && ((entryAt>0&&now>=entryAt)||(entryAt==0&&now-h.epoch()>v("entry_timeout_ms",2000))))selecting=false;
        boolean click=!selecting,go=!(now>=release && now<resume);int side=0;
        if(v("strafe",0)>0 && (v("strafe_during_release_only",0)<=0||!go))
            side=((now-h.epoch())/(long)v("strafe_period_ms",1500))%2==0?1:-1;
        double dx=h.tx()-h.x(),dz=h.tz()-h.z(),dist=Math.hypot(dx,dz),age=(now-h.observed())/1000.;
        if(v("spacing",0)>0&&!selecting&&now-h.incoming()>v("spacing_after_ms",750)){
            double predicted=dist-Math.max(-8,Math.min(8,h.closing()))*(age+v("spacing_horizon_ms",50)/1000.);
            if(predicted<v("zest_gap",2.7))go=false;
        }
        if(v("lock_control",0)>0){
            double fx=dx/Math.max(.001,dist),fz=dz/Math.max(.001,dist);
            double own=h.ux()*fx+h.uz()*fz,target=h.tvx()*fx+h.tvz()*fz;
            double viewAge=(rtt+otherRtt)/2000.+v("interpolation_ms",150)/1000.;
            double gap=dist+(target-own)*(age+v("control_horizon_ms",50)/1000.);
            double reach=reach(h.y(),h.ty()),lower=reach(h.ty(),h.y())-(target+own)*viewAge+v("deny_margin",.1);
            double upper=reach-v("lock_margin",.2),desired=lower+Math.max(0,upper-lower)*v("window_bias",.9);
            double friction=h.ground()?.546:.91,ticks=Math.max(1,Math.min(10,v("release_ms",200)/50));
            double coast=Math.max(0,h.vx()*fx+h.vz()*fz)*(1-Math.pow(friction,ticks))/(1-friction);
            if(target>v("chase_speed",.3)&&own>0&&lower<upper&&landedCue>0&&!selecting)
                go &= gap-coast>desired+(forward?-v("lock_hysteresis",.06):v("lock_hysteresis",.06));
            if(v("adaptive_reset",0)>0&&landed&&lower>=upper&&!selecting){
                release=now;resume=now+(long)v("emergency_tap_ms",50);phase=0;go=false;
            }
            if(v("opening_range",0)>0&&!opened&&!selecting){opened=gap<=reach-v("opening_margin",.05);click=opened;}
        }
        return new Decision(go,side,click);
    }
    static double reach(double y,double ty){
        double vertical=Math.max(0,Math.max(ty-.1-(y+1.62),(y+1.62)-(ty+1.9)));
        return Math.sqrt(Math.max(0,9-vertical*vertical))+.4;
    }
}
