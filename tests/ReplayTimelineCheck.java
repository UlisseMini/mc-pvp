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
        for(double invalid:new double[]{0,-1,Double.NaN,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY}){
            boolean rejected=false;try{t.speed(invalid,2200);}catch(IllegalArgumentException e){rejected=true;}check(rejected,"invalid speed");
        }
        ReplayTimeline slow=new ReplayTimeline(30000,0);
        slow.speed(.1,0);check(slow.advance(1000)==100,"one tenth speed");
        slow.speed(.75,1000);check(slow.advance(2000)==850,"custom fractional speed");
        slow.speed(3,2000);check(slow.advance(3000)==3850,"speed above old ceiling");
        slow.speed(.001,3000);check(slow.advance(4000)==3851,"speed below old floor");
        check(slow.speedLabel().equals("0.001x"),"small speed does not round to zero");
        slow.speed(1,4000);check(slow.speedLabel().equals("1x"),"compact ordinary speed");
        slow.seekSeconds(-1,true,5000);
        check(slow.position()==3851&&!slow.paused(),"rewind from up-to-date moving playhead, remain playing");
        slow.pause(true,5000);slow.seekSeconds(12.5,false,6000);slow.seekSeconds(-5,true,7000);
        check(slow.position()==7500&&slow.paused(),"absolute seek then rewind while paused");
        slow.seekSeconds(.05,true,8000);check(slow.position()==7550&&slow.paused(),"fifty millisecond forward step");
        slow.seekSeconds(Double.MAX_VALUE,true,9000);check(slow.position()==30000,"huge forward seek clamps without wraparound");
        slow.seekSeconds(-Double.MAX_VALUE,true,10000);check(slow.position()==0,"huge rewind clamps without wraparound");
        for(double invalid:new double[]{Double.NaN,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY}){
            boolean rejected=false;try{slow.seekSeconds(invalid,true,11000);}catch(IllegalArgumentException e){rejected=true;}
            check(rejected&&slow.position()==0&&slow.paused(),"invalid seek cannot change playback");
        }
        slow.speed(Double.MIN_VALUE,11000);check(!slow.speedLabel().equals("0x"),"tiny positive speed retained");
        slow.speed(Double.MAX_VALUE,11000);slow.pause(false,11000);
        check(slow.advance(12000)==30000&&slow.paused(),"extreme finite speed reaches end safely");
        List<Long> times=List.of(0L,50L,50L,100L);
        check(ReplayTimeline.floor(times,-10)==0,"before first sample");check(ReplayTimeline.floor(times,50)==2,"last same-time sample");
        check(ReplayTimeline.floor(times,99)==2,"recorded step, no invented trajectory");check(ReplayTimeline.floor(times,999)==3,"last sample");
        System.out.println("PASS replay clock: fractional/arbitrary speeds, absolute/relative seek, pause preservation, boundary clamps, invalid inputs and recorded samples");
    }
}
