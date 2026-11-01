package lab;

import java.util.List;

public final class ReplayTimelineCheck {
    static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
    public static void main(String[] args){
        ReplayTimeline t=new ReplayTimeline(2000,100);
        check(t.advance(200)==100,"real recorded time");t.pause(true,250);
        check(t.advance(1000)==150,"pause freezes position");t.speed(.5,1100);t.pause(false,1200);
        check(t.advance(1400)==250,"half speed");t.seek(1500,1400);
        check(t.advance(1500)==1550,"seek retains speed");t.speed(2,1500);
        check(t.advance(1800)==2000&&t.paused(),"ends paused exactly at duration");
        t.seek(-100,2000);check(t.position()==0,"negative seek clamped");t.seek(99999,2100);check(t.position()==2000,"oversized seek clamped");
        for(double invalid:new double[]{0,-1,3,Double.NaN,Double.POSITIVE_INFINITY}){
            boolean rejected=false;try{t.speed(invalid,2200);}catch(IllegalArgumentException e){rejected=true;}check(rejected,"invalid speed");
        }
        List<Long> times=List.of(0L,50L,50L,100L);
        check(ReplayTimeline.floor(times,-10)==0,"before first sample");check(ReplayTimeline.floor(times,50)==2,"last same-time sample");
        check(ReplayTimeline.floor(times,99)==2,"recorded step, no invented trajectory");check(ReplayTimeline.floor(times,999)==3,"last sample");
        System.out.println("PASS replay clock: pause, seek, speed, end, invalid inputs, duplicate samples and no interpolation");
    }
}
