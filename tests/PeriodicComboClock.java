import lab.ExactTravel;
import java.util.*;

/** Fixed, independently phased clocks around the SAME fight kernel.
 * This is an explicit hypothetical scheduler, not a prediction of future OS stalls.
 * Input decisions expose only own state, rendered target, and local contact history.
 */
public final class PeriodicComboClock {
    public static final class Timing implements Cloneable {
        public Timing deepCopy(){try{return (Timing)super.clone();}catch(CloneNotSupportedException impossible){throw new AssertionError(impossible);}}
        public double aTickPhase=0,bTickPhase=10,serverPhase=25;
        public double tickPeriod=50,serverPeriod=50,framePeriod=2.5,aFramePhase=0,bFramePhase=1;
        public double moveAfterInput=.3,flushAfterInput=.6,entityAfterDrain=.4,trackAfterDrain=.8,endAfterDrain=1;
        public double uplinkA=0,uplinkB=0,downlinkA=0,downlinkB=0;
        public boolean aRemoteBeforeMove=true,bRemoteBeforeMove=false,aimBeforeFrameDrain=true;
    }
    public record Observation(double time,long tick,ExactTravel.State own,double targetX,double targetY,double targetZ,
                              boolean sprint,float headYaw,double lastContactTime,long localContacts) {
        public double nearest(){PeriodicComboFightModel.View v=new PeriodicComboFightModel.View(targetX);v.y=targetY;v.z=targetZ;return PeriodicComboFightModel.nearest(own,v);}
    }
    /** strafe is native damped input: ordinary A/D are +/-.98f. */
    public record Control(int key,float strafe,boolean click) {}
    public interface Policy {Control input(Observation observation);}
    public interface Audit {void event(double time,String event,PeriodicComboFightModel.State state);}
    private record Event(double time,int kind,long order) {}
    public final PeriodicComboFightModel.State state;
    public final PeriodicComboFightModel.Config config;
    public final Timing timing;
    private final Policy[] policies;
    private final PriorityQueue<Event> events=new PriorityQueue<>(Comparator.comparingDouble(Event::time).thenComparingLong(Event::order));
    private final long[] ticks=new long[2];private final Control[] controls=new Control[2];private final double[] lastClick={-1e100,-1e100};
    private long order;private double now;private Audit audit;
    public PeriodicComboClock(PeriodicComboFightModel.State state,PeriodicComboFightModel.Config config,Timing timing,Policy a,Policy b){
        this.state=state;this.config=config;this.timing=timing;policies=new Policy[]{a,b};config.deferMovementSend=true;state.transport=null;
        state.extraLatency=new double[]{timing.uplinkA,timing.uplinkB,timing.downlinkA,timing.downlinkB};
        add(timing.aFramePhase,0);add(timing.bFramePhase,1);add(timing.aTickPhase,2);add(timing.bTickPhase,3);add(timing.serverPhase,8);
    }
    private PeriodicComboClock(PeriodicComboClock source,Policy a,Policy b){
        state=source.state.deepCopy();config=source.config.deepCopy();timing=source.timing.deepCopy();policies=new Policy[]{Objects.requireNonNull(a),Objects.requireNonNull(b)};
        events.addAll(source.events);System.arraycopy(source.ticks,0,ticks,0,2);System.arraycopy(source.controls,0,controls,0,2);System.arraycopy(source.lastClick,0,lastClick,0,2);
        order=source.order;now=source.now;
    }
    /** Forks between runUntil calls. Policies must be fresh or immutable; audit is deliberately detached.
     * An already consumed control remains in force until the next input event on that side. */
    public PeriodicComboClock fork(Policy a,Policy b){return new PeriodicComboClock(this,a,b);}
    public void setAudit(Audit audit){this.audit=audit;}
    public double time(){return now;}
    private void add(double time,int kind){events.add(new Event(time,kind,order++));}
    private Observation observation(boolean a){PeriodicComboFightModel.Body b=a?state.a:state.b;PeriodicComboFightModel.View v=a?state.ab:state.ba;return new Observation(now,ticks[a?0:1],b.q,v.x,v.y,v.z,b.sprint,b.headYaw,b.lastContactTime,b.localContacts);}
    private void aim(boolean a){PeriodicComboFightModel.Body b=a?state.a:state.b;PeriodicComboFightModel.View v=a?state.ab:state.ba;double dx=v.x-b.q.x(),dz=v.z-b.q.z(),eye=b.q.y()+PeriodicComboFightModel.EYE;double targetY=Math.max(v.y+.15,Math.min(v.y+1.65,eye));b.yaw=(float)Math.toDegrees(Math.atan2(-dx,dz));b.pitch=(float)-Math.toDegrees(Math.atan2(targetY-eye,Math.hypot(dx,dz)));}
    public void runUntil(double end){
        if(end<now)throw new IllegalArgumentException("Cannot run a clock backwards");
        while(!events.isEmpty()&&events.peek().time<=end){Event e=events.remove();now=e.time;int k=e.kind;boolean a=(k&1)==0;String name;
            if(k<2){if(timing.aimBeforeFrameDrain)aim(a);PeriodicComboFightModel.receive(state,now,a);if(!timing.aimBeforeFrameDrain)aim(a);add(now+timing.framePeriod,k);name="FRAME"+(a?"A":"B");}
            else if(k<4){int side=a?0:1;Control control=policies[side].input(observation(a));controls[side]=control;if(control.click){if(now-lastClick[side]<50-1e-9)throw new IllegalArgumentException("20 CPS native input cap violated");lastClick[side]=now;}PeriodicComboFightModel.Body b=a?state.a:state.b;PeriodicComboFightModel.replayInput(state,config,a,control.key,b.yaw,b.pitch,control.click,now);add(now+timing.moveAfterInput,k+2);add(now+timing.flushAfterInput,k+4);add(now+timing.tickPeriod,k);name="INPUT"+(a?"A":"B");}
            else if(k<6){int side=a?0:1;Control control=controls[side];boolean first=a?timing.aRemoteBeforeMove:timing.bRemoteBeforeMove;PeriodicComboFightModel.View v=a?state.ab:state.ba;if(first){v.step();checkpoint("INTERP"+(a?"A":"B"));}PeriodicComboFightModel.replayMove(state,config,a,control.key,control.strafe,(a?state.a:state.b).yaw,now);checkpoint("MOVE"+(a?"A":"B"));if(!first)v.step();ticks[side]++;name=first?"AFTER_MOVE":"INTERP"+(a?"A":"B");}
            else if(k<8){PeriodicComboFightModel.replayFlush(state,config,a,now);name="FLUSH"+(a?"A":"B");}
            else if(k==8){PeriodicComboFightModel.serverDrain(state,config,now);add(now+timing.entityAfterDrain,9);add(now+timing.trackAfterDrain,10);add(now+timing.endAfterDrain,11);add(now+timing.serverPeriod,8);name="SERVER_DRAIN";}
            else if(k==9){PeriodicComboFightModel.serverEntity(state);name="SERVER_ENTITY";}
            else if(k==10){PeriodicComboFightModel.serverTrack(state,config,now);name="SERVER_TRACK";}
            else{PeriodicComboFightModel.serverEnd(state);name="SERVER_END";}
            checkpoint(name);
        }
        now=end;
    }
    private void checkpoint(String name){PeriodicComboFightModel.audit(state);if(audit!=null)audit.event(now,name,state);}
}
