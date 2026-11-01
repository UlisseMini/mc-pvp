package lab;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/** Java8 runtime port of the frozen own-observation ReferenceFeedbackPolicy.
 * No server/opponent-private state, clock tape, packet queue or native mutation.
 * Caller owns the physical opening, capped persistent gate and every queued input.
 */
public final class ReferenceFeedbackController {
    public static final double HANDOFF_MS=3500, DEFENDER_WAKE_MS=4000;
    public static final String TABLE_SHA256="5915e9e8a33ca93ae19d75ac03a9a0ad37ea960bd4fbc302973eff6aea851895";
    private static final double[] UNITS={.1,.2,50,.03,.03,.08,.08,.5,.5};
    /** All time/cue values share one monotonic-ms origin. nearest is the native
     * eye-to-expanded-target-box distance, not center distance or true B state. */
    public static final class Snapshot {
        public final double timeMs,ownX,ownZ,ownVx,ownVz,targetX,targetY,targetZ,nearest,handledTargetHurtMs;
        public final long tick;
        public final boolean localSprint;
        public Snapshot(double timeMs,long tick,double ownX,double ownZ,double ownVx,double ownVz,
                        double targetX,double targetY,double targetZ,double nearest,boolean localSprint,double handledTargetHurtMs){
            finite(timeMs,ownX,ownZ,ownVx,ownVz,targetX,targetY,targetZ,nearest);
            if(nearest<0||Double.isInfinite(handledTargetHurtMs))throw new IllegalArgumentException("Invalid own observation");
            this.timeMs=timeMs;this.tick=tick;this.ownX=ownX;this.ownZ=ownZ;this.ownVx=ownVx;this.ownVz=ownVz;
            this.targetX=targetX;this.targetY=targetY;this.targetZ=targetZ;this.nearest=nearest;
            this.localSprint=localSprint;this.handledTargetHurtMs=handledTargetHurtMs;
        }
    }
    public static final class Decision {
        public final int key;
        public final float strafe;
        public final boolean click;
        public Decision(int key,float strafe,boolean click){
            if(key<0||key>2||!Float.isFinite(strafe))throw new IllegalArgumentException("Ordinary issued control required");
            this.key=key;this.strafe=strafe;this.click=click;
        }
    }
    private static final class Point {
        final double[] x;final int key;final float side;final boolean click;
        Point(double[] x,int key,float side,boolean click){this.x=x;this.key=key;this.side=side;this.click=click;}
    }
    private static final class Sample {
        final double t,x,y,z;
        Sample(Snapshot o){t=o.timeMs;x=o.targetX;y=o.targetY;z=o.targetZ;}
    }
    private static final class Features {
        final ArrayDeque<Sample> history=new ArrayDeque<Sample>();int previousKey=-1;
        Features(){}
        Features(Features p){history.addAll(p.history);previousKey=p.previousKey;}
        double[] read(Snapshot o){
            double dx=o.targetX-o.ownX,dz=o.targetZ-o.ownZ,r=Math.hypot(dx,dz);
            double radial=r==0?0:(o.ownVx*dx+o.ownVz*dz)/r;
            double lateral=r==0?0:(o.ownVz*dx-o.ownVx*dz)/r;
            Sample older=null;for(Sample h:history)if(h.t<=o.timeMs-100)older=h;
            double dt=older==null?0:o.timeMs-older.t;
            double vx=dt>0?(o.targetX-older.x)*50/dt:0,vz=dt>0?(o.targetZ-older.z)*50/dt:0;
            double stableRadial=r==0?0:(vx*dx+vz*dz)/r;
            double stableVy=dt>0?(o.targetY-older.y)*50/dt:0;
            double hurtAge=Double.isFinite(o.handledTargetHurtMs)?o.timeMs-o.handledTargetHurtMs:1000000;
            return new double[]{o.nearest,o.targetY-4,hurtAge,radial,lateral,stableRadial,stableVy,o.localSprint?1:0,previousKey};
        }
        void commit(Snapshot o,Decision actual){
            previousKey=actual.key;history.add(new Sample(o));
            while(history.size()>1&&history.peekFirst().t<o.timeMs-250)history.removeFirst();
        }
    }
    /** Exact frozen LegalSpacingFamily specialization. Diagnostics which never
     * affect controls are omitted; all state influencing controls is copied. */
    private static final class Family {
        final StrongRelaxationPolicy gap;
        long previousTick=Long.MIN_VALUE,lastWRiseTick;int previousKey=-1,remaining;
        boolean wasEnabled,wasSaturated,pending,maintenanceStarted;
        double lastInput=Double.NaN;
        Family(){gap=new StrongRelaxationPolicy(.283,2,2.55,1);}
        Family(Family p){gap=new StrongRelaxationPolicy(p.gap);previousTick=p.previousTick;lastWRiseTick=p.lastWRiseTick;
            previousKey=p.previousKey;remaining=p.remaining;wasEnabled=p.wasEnabled;wasSaturated=p.wasSaturated;
            pending=p.pending;maintenanceStarted=p.maintenanceStarted;lastInput=p.lastInput;}
        Decision step(Snapshot o,Decision actual){
            if(previousTick!=Long.MIN_VALUE&&o.tick<=previousTick)throw new IllegalArgumentException("One HEAD per increasing own input tick");
            lastInput=o.timeMs;StrongRelaxationPolicy.Control base=gap.step(o.nearest);
            boolean enabled=Double.isFinite(o.handledTargetHurtMs),saturated=.283+2*(2.55-o.nearest)>=1;
            if(previousKey<0)lastWRiseTick=o.tick;
            if(!enabled){pending=false;remaining=0;}
            else if(!saturated)pending=false;
            else if(!wasEnabled||!wasSaturated)pending=true;
            boolean guard=previousKey!=0&&o.tick-lastWRiseTick>=8;
            boolean start=enabled&&remaining==0&&pending&&guard;
            int proposedRemaining=start?2:remaining;
            boolean coast=enabled&&(proposedRemaining>0||(previousKey==0&&o.nearest<2.55));
            Decision proposed=new Decision(coast?0:base.key,coast?0:base.strafe,base.click);
            Decision issued=actual==null?proposed:actual;
            if(issued.key==0){
                if(previousKey!=0){remaining=enabled?1:0;pending=false;}
                else remaining=enabled?Math.max(0,proposedRemaining-1):0;
            }else remaining=0;
            if(issued.key!=0&&previousKey==0)lastWRiseTick=o.tick;
            previousKey=issued.key;previousTick=o.tick;wasEnabled=enabled;wasSaturated=saturated;
            return proposed;
        }
        boolean allow(Snapshot o,String source,int hitTicks){
            if(!Double.isFinite(lastInput)||!Double.isFinite(o.handledTargetHurtMs))return true;
            double consume=source.equals("native_tick")?o.timeMs:Math.max(o.timeMs,lastInput+50);
            double latestDrain=consume+120.0/2+5+50;
            double readyLow=o.handledTargetHurtMs-120.0/2+hitTicks*50-5;
            return !(latestDrain+0<readyLow);
        }
    }
    private final List<Point> reference;
    private final int hitTicks;
    private final Family family;
    private final Features features;
    private boolean explicit;
    private int selections,overrides,selectedIndex=-1;
    private double lastError;
    /** Validates the exact production reference bytes before accepting input. */
    public static ReferenceFeedbackController frozen(String text,int hitTicks){
        if(!TABLE_SHA256.equals(sha256(text)))throw new IllegalArgumentException("Frozen reference table SHA256 mismatch");
        return new ReferenceFeedbackController(text,hitTicks);
    }
    /** Public for component fixtures; production integration should use frozen(). */
    public ReferenceFeedbackController(String text,int hitTicks){
        if(text==null||hitTicks<1)throw new IllegalArgumentException("Reference and positive hitTicks required");
        List<Point> list=new ArrayList<Point>();
        for(String line:text.split("\n")){
            if(line.trim().isEmpty())continue;String[] s=line.split("\t");
            if(s.length!=12)throw new IllegalArgumentException("Expected twelve reference columns");
            double[] x=new double[9];for(int i=0;i<x.length;i++){double value=Double.parseDouble(s[i]);finite(value);x[i]=value/UNITS[i];}
            int key=Integer.parseInt(s[9]);float side=Float.parseFloat(s[10]);
            if(key< -1||key>2||!(side== -1||side==0||side==1)||!(s[11].equals("true")||s[11].equals("false")))throw new IllegalArgumentException("Invalid reference control");
            list.add(new Point(x,key,side*.98f,Boolean.parseBoolean(s[11])));
        }
        if(list.isEmpty())throw new IllegalArgumentException("Empty reference table");
        reference=java.util.Collections.unmodifiableList(list);this.hitTicks=hitTicks;family=new Family();features=new Features();
    }
    public ReferenceFeedbackController(ReferenceFeedbackController p){reference=p.reference;hitTicks=p.hitTicks;family=new Family(p.family);features=new Features(p.features);
        explicit=p.explicit;selections=p.selections;overrides=p.overrides;selectedIndex=p.selectedIndex;lastError=p.lastError;}
    public void observeOpening(Snapshot o,Decision actual){
        if(family.maintenanceStarted)throw new IllegalStateException("Opening history is already closed");
        if(actual==null)throw new IllegalArgumentException("Actual opening control required");
        family.step(o,actual);features.commit(o,actual);
    }
    public Decision input(Snapshot o){
        double[] x=features.read(o);for(int i=0;i<x.length;i++)x[i]/=UNITS[i];x[2]*=9.0/hitTicks;
        Point best=null;double error=Double.POSITIVE_INFINITY;int index=-1;
        for(int j=0;j<reference.size();j++){
            Point p=reference.get(j);double e=0;for(int i=0;i<x.length;i++){double d=x[i]-p.x[i];e+=d*d;}
            if(e<error){error=e;best=p;index=j;}
        }
        if(best==null)throw new IllegalStateException("No finite own-observed reference");
        lastError=error;selectedIndex=index;selections++;explicit=best.key>=0;
        Decision actual=explicit?new Decision(best.key,best.side,best.click):new Family(family).step(o,null);
        if(explicit)overrides++;family.maintenanceStarted=true;family.step(o,actual);features.commit(o,actual);return actual;
    }
    /** True only permits ordinary admission; never bypass the caller's cap. */
    public boolean allowInjection(Snapshot o,String source){return explicit||family.allow(o,source,hitTicks);}
    public boolean injected(Snapshot o,double completedAt){return true;}
    public void consumed(Snapshot o,long admissionId){}
    public int selections(){return selections;}
    public int overrides(){return overrides;}
    public int selectedIndex(){return selectedIndex;}
    public double lastError(){return lastError;}
    /** Defensive copy for golden-stream validation and read-only diagnostics. */
    public double[] selectedFeatures(Snapshot o){return features.read(o);}
    private static void finite(double... values){for(double v:values)if(!Double.isFinite(v))throw new IllegalArgumentException("Nonfinite own input/reference");}
    private static String sha256(String text){
        try{byte[] hash=MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));StringBuilder out=new StringBuilder();
            for(byte b:hash)out.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return out.toString();
        }catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
}
