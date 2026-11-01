package practice.core;

import java.util.Random;

/** Explicit session-only ordinary inputs. No network, position or motion writes. */
public final class AntiAfk {
    public static final int FORWARD=1,BACK=2,LEFT=4,RIGHT=8,SNEAK=16;
    private final Random random;
    private boolean enabled,paused;
    private Object connection;
    private long next,last;
    private int minSeconds=30,maxSeconds=90,index,length,tap,direction;
    private boolean sneak;
    private double yawAmplitude,pitchAmplitude,previousYaw,previousPitch,yawDelta,pitchDelta;
    public AntiAfk(){this(new Random());}
    public AntiAfk(Random random){this.random=random;}
    public void enable(long now,Object connection,int seconds){enable(now,connection,seconds,seconds);}
    public void enable(long now,Object connection,int min,int max){
        if(connection==null)throw new IllegalArgumentException("Join a server first");
        if(min<10||max>3600||min>max)throw new IllegalArgumentException("Interval must be 10–3600 seconds, or MIN-MAX");
        enabled=true;this.connection=connection;minSeconds=min;maxSeconds=max;last=now;paused=false;clearPulse();schedule(now);
    }
    public void enable(long now,Object connection,String interval){
        String[] p=interval.split("-",-1);
        try{if(p.length==1)enable(now,connection,Integer.parseInt(p[0]));else if(p.length==2)enable(now,connection,Integer.parseInt(p[0]),Integer.parseInt(p[1]));else throw new NumberFormatException();}
        catch(NumberFormatException e){throw new IllegalArgumentException("Use seconds or MIN-MAX, e.g. 60 or 30-90");}
    }
    private void schedule(long now){next=now+(minSeconds+random.nextInt(maxSeconds-minSeconds+1))*1_000_000_000L;}
    private void clearPulse(){index=length=0;previousYaw=previousPitch=yawDelta=pitchDelta=0;}
    public void disable(){enabled=false;connection=null;paused=false;clearPulse();}
    /** Same-channel world changes PAUSE; only a real socket change/disconnect disables.
     * Countdown freezes while a GUI/manual input/world transition is present. */
    public int tick(long now,Object actual,boolean playing,boolean gui,boolean humanInput){
        yawDelta=pitchDelta=0;
        if(!enabled)return 0;
        if(actual==null||actual!=connection){disable();return 0;}
        long elapsed=Math.max(0,now-last);last=now;
        if(!playing||gui||humanInput){
            if(length>0){clearPulse();schedule(now);}
            else next+=elapsed;
            paused=true;return 0;
        }
        if(paused){paused=false;next=Math.max(next,now+1_000_000_000L);}
        if(length==0){
            if(now<next)return 0;
            tap=1+random.nextInt(4);direction=random.nextInt(4);sneak=random.nextInt(4)==0;
            length=Math.max(6,2*tap);index=0;previousYaw=previousPitch=0;
            yawAmplitude=(.5+random.nextDouble()*1.5)*(random.nextBoolean()?1:-1);
            pitchAmplitude=(.2+random.nextDouble()*.4)*(random.nextBoolean()?1:-1);
        }
        int[] dirs={FORWARD,BACK,LEFT,RIGHT};
        int keys=index<tap?dirs[direction]:index<2*tap?dirs[direction^1]:0;
        if(sneak&&index<2*tap)keys|=SNEAK;
        double phase=Math.PI*2*(index+1)/length;
        double y=index+1==length?0:yawAmplitude*Math.sin(phase),p=index+1==length?0:pitchAmplitude*Math.sin(phase);
        yawDelta=y-previousYaw;pitchDelta=p-previousPitch;previousYaw=y;previousPitch=p;
        index++;
        if(index==length){length=index=0;schedule(now);}
        return keys;
    }
    public double yawDelta(){return yawDelta;}
    public double pitchDelta(){return pitchDelta;}
    public boolean pulsing(){return length>0||yawDelta!=0||pitchDelta!=0;}
    /** Release only synthetic ownership, preserving newly physically held keys. */
    public static final class Ownership {
        private int owned;
        public int[] applyMask(int desired,int humanHeld){int release=owned&~desired&~humanHeld;owned=desired;return new int[]{desired,release};}
        /** Compatibility for older small fixture callers: 1=W, -1=S. */
        public int[] apply(int direction,boolean humanForward,boolean humanBack){return applyMask(direction==1?FORWARD:direction==-1?BACK:0,(humanForward?FORWARD:0)|(humanBack?BACK:0));}
    }
    public boolean enabled(){return enabled;}
    public String status(){return enabled?"Anti-AFK ON ("+(minSeconds==maxSeconds?String.valueOf(minSeconds):minSeconds+"–"+maxSeconds)+"s random taps; this connection only"+(paused?", paused":"")+")":"Anti-AFK OFF";}
}
