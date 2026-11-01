package lab;

import java.util.*;

/** Wall-clock playback of recorded server samples; never re-simulates combat. */
public final class ReplayTimeline {
    private final long duration;
    private long previous;
    private double position,speed=1;
    private boolean paused;
    ReplayTimeline(long duration,long now){this.duration=Math.max(0,duration);previous=now;}
    long advance(long now){
        if(!paused)position=Math.min(duration,position+Math.max(0,now-previous)*speed);
        previous=now;if(position>=duration)paused=true;return position();
    }
    long position(){return (long)position;}
    void seek(long ms,long now){position=Math.max(0,Math.min(duration,ms));previous=now;}
    void pause(boolean value,long now){advance(now);paused=value;}
    boolean paused(){return paused;}
    void speed(double value,long now){
        if(!Double.isFinite(value)||value<.25||value>2)throw new IllegalArgumentException("Replay speed must be 0.25–2.");
        advance(now);speed=value;
    }
    double speed(){return speed;}
    static int floor(List<Long> times,long time){
        int lo=0,hi=times.size();while(lo<hi){int mid=(lo+hi)>>>1;if(times.get(mid)<=time)lo=mid+1;else hi=mid;}
        return Math.max(0,lo-1);
    }
}
