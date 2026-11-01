import lab.ExactTravel;
import java.util.*;

/** Independent clocks around the SAME fight kernel. Periodic clocks are hypothetical;
 * a supplied event tape is exogenous, never a prediction of future OS stalls. */
public final class PeriodicComboClock {
    public enum InputMode { DIRECT, NATIVE_QUEUED }
    public static final class Timing implements Cloneable {
        public Timing deepCopy(){try{return (Timing)super.clone();}catch(CloneNotSupportedException impossible){throw new AssertionError(impossible);}}
        public double aTickPhase=0,bTickPhase=10,serverPhase=25;
        public double tickPeriod=50,serverPeriod=50,framePeriod=2.5,aFramePhase=0,bFramePhase=1;
        public double moveAfterInput=.3,flushAfterInput=.6,entityAfterDrain=.4,trackAfterDrain=.8,endAfterDrain=1;
        public double uplinkA=0,uplinkB=0,downlinkA=0,downlinkB=0;
        /** Historical direct-input mode keeps its original event ordering and semantics. */
        public InputMode inputMode=InputMode.DIRECT;
        /** Physical injection ceiling, not a processing cap. Native queued mode uses nanoseconds. */
        public long clickIntervalNanos=50_000_000L;
        public boolean aRemoteBeforeMove=true,bRemoteBeforeMove=false,aimBeforeFrameDrain=true;
    }
    public record Observation(double time,long tick,ExactTravel.State own,double targetX,double targetY,double targetZ,
                              boolean sprint,float headYaw,double lastContactTime,long localContacts,double lastTargetHurtTime,double lastOwnHurtTime) {
        public double nearest(){PeriodicComboFightModel.View v=new PeriodicComboFightModel.View(targetX);v.y=targetY;v.z=targetZ;return PeriodicComboFightModel.nearest(own,v);}
    }
    /** Native A/D are +/-.98f. In NATIVE_QUEUED, click holds an admission gate until updated. */
    public record Control(int key,float strafe,boolean click) {}
    public enum ReceivedKind { TARGET_POSITION, TARGET_LOOK, TARGET_HURT, OWN_HURT, OWN_VELOCITY, OWN_SPRINT }
    /** Immutable data available from this client's own incoming packets, delivered
     * only after local handling. arrivedAt is a local network receipt timestamp,
     * NOT a server send time. Position goals are this client's decoded/interpolated
     * endpoint; absent numeric payloads are NaN. No global packet sequence, server
     * tick, remote velocity, or remote-client state is exposed. */
    public record PacketObservation(double arrivedAt,double handledAt,int packetId,ReceivedKind kind,
                                    double goalX,double goalY,double goalZ,boolean groundFlag,
                                    double vx,double vy,double vz,boolean sprintFlag) {}
    public interface Policy {
        Control input(Observation observation);
        default void received(PacketObservation packet){}
        /** Own-observation admission permission, consulted only after gate/cap eligibility.
         * A deferral leaves the physical cap timestamp, gate and admitted FIFO unchanged. */
        default boolean allowInjection(Observation own,String source){return true;}
        /** Successful physical injection only; return the gate to retain until the next input decision. */
        default boolean injected(Observation own,double completedAt){return true;}
        /** Native clickMouse invocation, including a call suppressed by the native miss counter. */
        default void consumed(Observation own,long admissionId){}
    }
    public interface Audit {void event(double time,String event,PeriodicComboFightModel.State state);}
    /** Admission timestamps are instantaneous adapter opportunities, not measured native call starts. */
    public record ClickEvent(double time,boolean fromA,String action,String source,long admissionId,
                             long admittedNanos,long tick,int queued,int missCounter,boolean eligible,boolean contact) {}
    public interface ClickAudit {void event(ClickEvent event);}
    /** A tape has explicit drains/interpolation and never automatically adds periodic events.
     * TICK_HEAD selects keys/gate and polls; CONSUME drains the predicted queue, never a recorded count. */
    public enum Kind { FRAME_ENTRY, CLIENT_DRAIN, TICK_HEAD, CONSUME, MOVE, INTERP, FLUSH,
                       SERVER_DRAIN, SERVER_ENTITY, SERVER_TRACK, SERVER_END }
    public record Scheduled(long nanos,long sequence,Kind kind,boolean fromA) {}
    private record Event(double time,int kind,long order) {}
    private record Press(long id,long admittedNanos) {}
    private static final class ClickQueue {
        final LinkedList<Press> presses=new LinkedList<>();
        long nextId,lastCompletedNanos=Long.MIN_VALUE;boolean gate;int missCounter;
        ClickQueue copy(){ClickQueue q=new ClickQueue();q.presses.addAll(presses);q.nextId=nextId;q.lastCompletedNanos=lastCompletedNanos;q.gate=gate;q.missCounter=missCounter;return q;}
    }
    public final PeriodicComboFightModel.State state;
    public final PeriodicComboFightModel.Config config;
    public final Timing timing;
    private final Policy[] policies;
    private final boolean recordedSchedule;
    private final double scheduleEnd;
    private final PriorityQueue<Event> events=new PriorityQueue<>(Comparator.comparingDouble(Event::time).thenComparingLong(Event::order));
    private final long[] ticks=new long[2];private final Control[] controls=new Control[2];private final double[] lastClick={-1e100,-1e100};
    private final ClickQueue[] clickQueues={new ClickQueue(),new ClickQueue()};
    private final boolean[] selectionReady=new boolean[2];
    private long order;private double now;private Audit audit;private ClickAudit clickAudit;
    public PeriodicComboClock(PeriodicComboFightModel.State state,PeriodicComboFightModel.Config config,Timing timing,Policy a,Policy b){this(state,config,timing,a,b,null);}
    /** Own observations/policies generate inputs; the tape supplies event opportunities/order only.
     * Queued mode models zero-delay, short press/release, no GUI/block/use, flat-arena clicks. */
    public PeriodicComboClock(PeriodicComboFightModel.State state,PeriodicComboFightModel.Config config,Timing timing,Policy a,Policy b,List<Scheduled> schedule){
        this.state=state;this.config=config;this.timing=timing;policies=new Policy[]{a,b};recordedSchedule=schedule!=null;scheduleEnd=schedule==null?Double.POSITIVE_INFINITY:schedule.stream().mapToLong(Scheduled::nanos).max().orElseThrow(()->new IllegalArgumentException("Empty event tape"))/1_000_000.0;config.deferMovementSend=true;state.transport=null;
        state.extraLatency=new double[]{timing.uplinkA,timing.uplinkB,timing.downlinkA,timing.downlinkB};
        if(timing.inputMode==InputMode.NATIVE_QUEUED&&timing.clickIntervalNanos<50_000_000L)throw new IllegalArgumentException("Physical injection ceiling exceeds20CPS");
        if(schedule==null){add(timing.aFramePhase,0);add(timing.bFramePhase,1);add(timing.aTickPhase,2);add(timing.bTickPhase,3);add(timing.serverPhase,8);}
        else{
            if(timing.inputMode!=InputMode.NATIVE_QUEUED)throw new IllegalArgumentException("Explicit event tapes require NATIVE_QUEUED mode");
            Set<Long> sequences=new HashSet<>();
            for(Scheduled e:List.copyOf(schedule)){
                if(e.nanos<0||!sequences.add(e.sequence))throw new IllegalArgumentException("Tape requires nonnegative times and unique sequence values");
                events.add(new Event(e.nanos/1_000_000.0,kind(e),e.sequence));
            }
        }
    }
    private static int kind(Scheduled e){int side=e.fromA?0:1;return switch(e.kind){case FRAME_ENTRY->side;case TICK_HEAD->2+side;case MOVE->4+side;case FLUSH->6+side;case SERVER_DRAIN->8;case SERVER_ENTITY->9;case SERVER_TRACK->10;case SERVER_END->11;case INTERP->12+side;case CONSUME->14+side;case CLIENT_DRAIN->16+side;};}
    private PeriodicComboClock(PeriodicComboClock source,Policy a,Policy b){
        state=source.state.deepCopy();config=source.config.deepCopy();timing=source.timing.deepCopy();policies=new Policy[]{Objects.requireNonNull(a),Objects.requireNonNull(b)};recordedSchedule=source.recordedSchedule;scheduleEnd=source.scheduleEnd;
        events.addAll(source.events);System.arraycopy(source.ticks,0,ticks,0,2);System.arraycopy(source.controls,0,controls,0,2);System.arraycopy(source.lastClick,0,lastClick,0,2);
        System.arraycopy(source.selectionReady,0,selectionReady,0,2);clickQueues[0]=source.clickQueues[0].copy();clickQueues[1]=source.clickQueues[1].copy();order=source.order;now=source.now;
    }
    /** Policies must be fresh or immutable. All packet/input queues and scheduler state are copied;
     * audits are detached. An already admitted input survives a later gate/key change. */
    public PeriodicComboClock fork(Policy a,Policy b){return new PeriodicComboClock(this,a,b);}
    public void setAudit(Audit audit){this.audit=audit;}
    public void setClickAudit(ClickAudit audit){clickAudit=audit;}
    public double time(){return now;}
    public int queuedClicks(boolean fromA){return clickQueues[fromA?0:1].presses.size();}
    public int missCounter(boolean fromA){return clickQueues[fromA?0:1].missCounter;}
    private void add(double time,int kind){events.add(new Event(time,kind,order++));}
    private Observation observation(boolean a){PeriodicComboFightModel.Body b=a?state.a:state.b;PeriodicComboFightModel.View v=a?state.ab:state.ba;return new Observation(now,ticks[a?0:1],b.q,v.x,v.y,v.z,b.sprint,b.headYaw,b.lastContactTime,b.localContacts,v.lastHurtTime,b.lastHurtTime);}
    private void receive(boolean a){
        PeriodicComboFightModel.receive(state,now,a,p->{
            ReceivedKind kind;double x=Double.NaN,y=Double.NaN,z=Double.NaN,vx=Double.NaN,vy=Double.NaN,vz=Double.NaN;boolean ground=false,sprint=false;
            if(p.kind==5||p.kind==6){
                kind=p.packetId==0x16?ReceivedKind.TARGET_LOOK:ReceivedKind.TARGET_POSITION;
                PeriodicComboFightModel.View v=a?state.ab:state.ba;x=v.gx;y=v.gy;z=v.gz;ground=p.ground;
            }else if(p.kind==4||p.kind==8){kind=ReceivedKind.OWN_VELOCITY;vx=p.vx;vy=p.vy;vz=p.vz;}
            else if(p.kind==10||p.kind==11){kind=ReceivedKind.OWN_SPRINT;sprint=p.flag;}
            else if(p.kind==12||p.kind==13)kind=ReceivedKind.TARGET_HURT;
            else if(p.kind==14||p.kind==15)kind=ReceivedKind.OWN_HURT;
            else throw new AssertionError("Unexpected own incoming packet kind "+p.kind);
            policies[a?0:1].received(new PacketObservation(p.due,now,p.packetId,kind,x,y,z,ground,vx,vy,vz,sprint));
        });
    }
    private void aim(boolean a){PeriodicComboFightModel.Body b=a?state.a:state.b;PeriodicComboFightModel.View v=a?state.ab:state.ba;double dx=v.x-b.q.x(),dz=v.z-b.q.z(),eye=b.q.y()+PeriodicComboFightModel.EYE;double targetY=Math.max(v.y+.15,Math.min(v.y+1.65,eye));b.yaw=(float)Math.toDegrees(Math.atan2(-dx,dz));b.pitch=(float)-Math.toDegrees(Math.atan2(targetY-eye,Math.hypot(dx,dz)));selectionReady[a?0:1]=true;}
    private void poll(boolean a,String source){
        int side=a?0:1;ClickQueue q=clickQueues[side];long ns=Math.round(now*1_000_000.0);
        boolean ready=q.lastCompletedNanos==Long.MIN_VALUE||ns-q.lastCompletedNanos>=timing.clickIntervalNanos;
        if(!q.gate||!ready){clickEvent(a,"POLL",source,null,ready,false);return;}
        if(!policies[side].allowInjection(observation(a),source)){clickEvent(a,"DEFER",source,null,true,false);return;}
        Press press=new Press(++q.nextId,ns);q.presses.addLast(press);q.lastCompletedNanos=ns;
        q.gate=policies[side].injected(observation(a),now);clickEvent(a,"ADMIT",source,press,true,false);
    }
    private void clickEvent(boolean a,String action,String source,Press press,boolean eligible,boolean contact){
        if(clickAudit==null)return;int side=a?0:1;ClickQueue q=clickQueues[side];
        clickAudit.event(new ClickEvent(now,a,action,source,press==null?-1:press.id,press==null?-1:press.admittedNanos,ticks[side],q.presses.size(),q.missCounter,eligible,contact));
    }
    private void consume(boolean a){
        int side=a?0:1;ClickQueue q=clickQueues[side];Control control=Objects.requireNonNull(controls[side],"CONSUME before TICK_HEAD");
        PeriodicComboFightModel.Body body=a?state.a:state.b;
        if(!q.presses.isEmpty()&&!selectionReady[side])throw new IllegalStateException("UNSUPPORTED_CACHED_SELECTION: drain/movement/interpolation between ray refresh and click consumption");
        while(!q.presses.isEmpty()){
            Press press=q.presses.removeFirst();boolean eligible=q.missCounter<=0;long contacts=body.localContacts;
            if(eligible){
                PeriodicComboFightModel.replayInput(state,config,a,control.key,body.yaw,body.pitch,true,now);
                if(body.localContacts==contacts)q.missCounter=10; // air MISS; no block interactions in this adapter
            }
            policies[side].consumed(observation(a),press.id);clickEvent(a,"CONSUME","native_tick",press,eligible,body.localContacts>contacts);
        }
        // Pinned ave.s(): entire pressTime loop precedes b(false), whose first action clears ag.
        // Synthetic onTick presses do not hold keyBindAttack down. A second same-tick MISS is suppressed.
        q.missCounter=0;
    }
    public void runUntil(double end){
        if(end<now)throw new IllegalArgumentException("Cannot run a clock backwards");
        if(!Double.isFinite(end)||end>scheduleEnd)throw new IllegalArgumentException("Requested time exceeds supplied event-tape coverage");
        while(!events.isEmpty()&&events.peek().time<=end){Event e=events.remove();now=e.time;int k=e.kind;boolean a=(k&1)==0;int side=a?0:1;String name;
            if(k<2){
                if(timing.inputMode==InputMode.NATIVE_QUEUED){aim(a);poll(a,"frame");if(!recordedSchedule){receive(a);selectionReady[side]=false;}}
                else{if(timing.aimBeforeFrameDrain)aim(a);receive(a);if(!timing.aimBeforeFrameDrain)aim(a);}
                if(!recordedSchedule)add(now+timing.framePeriod,k);name="FRAME"+(a?"A":"B");
            }
            else if(k<4){
                Control control=policies[side].input(observation(a));controls[side]=control;
                if(timing.inputMode==InputMode.NATIVE_QUEUED){
                    ClickQueue q=clickQueues[side];if(q.missCounter>0)q.missCounter--;q.gate=control.click;
                    // Reaction0 Client.tick calls Client.frame before native input consumption, even when cap-blocked.
                    aim(a);poll(a,"native_tick");if(!recordedSchedule)consume(a);
                }else{
                    if(control.click){if(now-lastClick[side]<50-1e-9)throw new IllegalArgumentException("20 CPS native input cap violated");lastClick[side]=now;}
                    PeriodicComboFightModel.Body b=a?state.a:state.b;PeriodicComboFightModel.replayInput(state,config,a,control.key,b.yaw,b.pitch,control.click,now);
                }
                if(!recordedSchedule){add(now+timing.moveAfterInput,k+2);add(now+timing.flushAfterInput,k+4);add(now+timing.tickPeriod,k);}
                name=(recordedSchedule?"POLICY":"INPUT")+(a?"A":"B");
            }
            else if(k<6){
                Control control=Objects.requireNonNull(controls[side],"MOVE before TICK_HEAD");boolean first=a?timing.aRemoteBeforeMove:timing.bRemoteBeforeMove;
                PeriodicComboFightModel.View v=a?state.ab:state.ba;
                if(!recordedSchedule&&first){v.step();checkpoint("INTERP"+(a?"A":"B"));}
                PeriodicComboFightModel.replayMove(state,config,a,control.key,control.strafe,(a?state.a:state.b).yaw,now);checkpoint("MOVE"+(a?"A":"B"));
                if(!recordedSchedule&&!first)v.step();ticks[side]++;selectionReady[side]=false;name=recordedSchedule||first?"AFTER_MOVE":"INTERP"+(a?"A":"B");
            }
            else if(k<8){PeriodicComboFightModel.replayFlush(state,config,a,now);name="FLUSH"+(a?"A":"B");}
            else if(k==8){PeriodicComboFightModel.serverDrain(state,config,now);if(!recordedSchedule){add(now+timing.entityAfterDrain,9);add(now+timing.trackAfterDrain,10);add(now+timing.endAfterDrain,11);add(now+timing.serverPeriod,8);}name="SERVER_DRAIN";}
            else if(k==9){PeriodicComboFightModel.serverEntity(state);name="SERVER_ENTITY";}
            else if(k==10){PeriodicComboFightModel.serverTrack(state,config,now);name="SERVER_TRACK";}
            else if(k==11){PeriodicComboFightModel.serverEnd(state);name="SERVER_END";}
            else if(k<14){(a?state.ab:state.ba).step();selectionReady[side]=false;name="INTERP"+(a?"A":"B");}
            else if(k<16){consume(a);name="INPUT"+(a?"A":"B");}
            else{receive(a);selectionReady[side]=false;name="CLIENT_DRAIN"+(a?"A":"B");}
            checkpoint(name);
        }
        now=end;
    }
    private void checkpoint(String name){PeriodicComboFightModel.audit(state);if(audit!=null)audit.event(now,name,state);}
}
