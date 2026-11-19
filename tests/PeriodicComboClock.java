import lab.ExactTravel;
import java.util.*;

/** Independent clocks around the SAME fight kernel. Periodic clocks are hypothetical;
 * a supplied event tape is exogenous, never a prediction of future OS stalls. */
public final class PeriodicComboClock {
    public enum InputMode { DIRECT, NATIVE_QUEUED }
    public enum ServiceMode { NONE, LOWER, MIDPOINT, UPPER }
    /** Payload-blind exogenous overhead. Never selects recorded admissions.
     * Called only for a newly eligible generated injection, with its actual poll time. */
    public interface InjectionTiming {
        long completionDelayNanos(boolean fromA,long pollNanos);
        /** Stateful measured-cost models must isolate their generated-call history. */
        default InjectionTiming deepCopy(){return this;}
    }
    /** Optional exogenous S19 publication time, queried only after a generated
     * packet is selected by an actual client drain. Routes2/3 mean receiving A/B.
     * All arguments/results are absolute clock-relative nanoseconds; no native
     * packet identity, target/own label, count or historical ordinal is exposed. */
    public interface CuePublicationTiming {
        long publicationNanos(int clientRoute,long rawReceiptNanos,long taskReadyNanos,long actualDrainStartNanos);
        default CuePublicationTiming deepCopy(){return this;}
    }
    /** Predictive-only whole-drain timing. The already selected FIFO is the only
     * workload input; recorded per-packet envelopes/default modes stay unchanged. */
    public record DrainPacket(ReceivedKind kind,int packetId) {
        public DrainPacket {Objects.requireNonNull(kind);if(packetId<0)throw new IllegalArgumentException("Negative packet ID");}
    }
    public record PacketService(long effectOffsetNanos,long callbackOffsetNanos) {
        public PacketService {if(effectOffsetNanos<0||callbackOffsetNanos<effectOffsetNanos)throw new IllegalArgumentException("Invalid packet service offsets");}
    }
    public record DrainServicePlan(String templateId,List<PacketService> packets,long completionOffsetNanos) {
        public DrainServicePlan {
            if(templateId==null||templateId.isBlank()||completionOffsetNanos<0)throw new IllegalArgumentException("Invalid drain service plan");
            packets=List.copyOf(packets);long previous=0;
            for(var p:packets){if(p.effectOffsetNanos()<previous||p.callbackOffsetNanos()>completionOffsetNanos)throw new IllegalArgumentException("Drain service violates serial FIFO/completion");previous=p.callbackOffsetNanos();}
        }
    }
    public interface DrainServiceTiming {
        DrainServicePlan plan(int clientRoute,long actualDrainStartNanos,List<DrainPacket> selectedFifo);
        default DrainServiceTiming deepCopy(){return this;}
    }
    public static final class Timing implements Cloneable {
        public Timing deepCopy(){try{Timing copy=(Timing)super.clone();if(injectionTiming!=null)copy.injectionTiming=injectionTiming.deepCopy();if(cuePublicationTiming!=null)copy.cuePublicationTiming=cuePublicationTiming.deepCopy();if(drainServiceTiming!=null)copy.drainServiceTiming=drainServiceTiming.deepCopy();return copy;}catch(CloneNotSupportedException impossible){throw new AssertionError(impossible);}}
        public double aTickPhase=0,bTickPhase=10,serverPhase=25;
        public double tickPeriod=50,serverPeriod=50,framePeriod=2.5,aFramePhase=0,bFramePhase=1;
        public double moveAfterInput=.3,flushAfterInput=.6,entityAfterDrain=.4,trackAfterDrain=.8,endAfterDrain=1;
        public double uplinkA=0,uplinkB=0,downlinkA=0,downlinkB=0;
        /** Historical direct-input mode keeps its original event ordering and semantics. */
        public InputMode inputMode=InputMode.DIRECT;
        /** Physical injection ceiling, not a processing cap. Native queued mode uses nanoseconds. */
        public long clickIntervalNanos=50_000_000L;
        /** Tape-only: HEAD/FRAME refresh decisions/aim; separate POLL events admit. */
        public boolean explicitPolls=false;
        /** Null preserves historical instantaneous injection. Non-null requires a tape. */
        public InjectionTiming injectionTiming;
        /** Null preserves exact immediate legacy cue/callback behavior. Tape-only.
         * This is publication timing, not a general packet-handler service model. */
        public CuePublicationTiming cuePublicationTiming;
        /** Tape-only opt-in for immutable per-packet drain-relative cue delays. */
        public boolean packetCueDelays=false;
        /** Conditional reference-opportunity sensitivity only, NOT a calibrated
         * clock engine. Busy client stages wait; delays of a full tick reject. */
        public boolean causalBackpressure=false;
        /** Separate cue publication from native main-thread/queue-monitor release.
         * Explicit bound sensitivity, not an identified host-service duration. */
        public ServiceMode serviceMode=ServiceMode.NONE;
        /** Null preserves strict historical-envelope behavior, including rejection
         * of unsupported coalescence. Non-null replaces service, not transport. */
        public DrainServiceTiming drainServiceTiming;
        public boolean aRemoteBeforeMove=true,bRemoteBeforeMove=false,aimBeforeFrameDrain=true;
    }
    public record Observation(double time,long tick,ExactTravel.State own,double targetX,double targetY,double targetZ,
                              boolean sprint,float headYaw,double lastContactTime,long localContacts,double lastTargetHurtTime,double lastOwnHurtTime,
                              float reportedYaw) {
        public Observation(double time,long tick,ExactTravel.State own,double targetX,double targetY,double targetZ,
                           boolean sprint,float headYaw,double lastContactTime,long localContacts,double lastTargetHurtTime,double lastOwnHurtTime) {
            this(time,tick,own,targetX,targetY,targetZ,sprint,headYaw,lastContactTime,localContacts,lastTargetHurtTime,lastOwnHurtTime,headYaw);
        }
        public double nearest(){PeriodicComboFightModel.View v=new PeriodicComboFightModel.View(targetX);v.y=targetY;v.z=targetZ;return PeriodicComboFightModel.nearest(own,v);}
    }
    /** Native A/D are +/-.98f. In NATIVE_QUEUED, click holds an admission gate until updated. */
    public record Control(int key,float strafe,boolean click,boolean jump) {
        /** Historical policies retain exactly the no-jump action space. */
        public Control(int key,float strafe,boolean click){this(key,strafe,click,false);}
    }
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
        default float aimYaw(float trackingYaw){return trackingYaw;}
        default boolean contactOnly(){return false;}
        /** Own-observation admission permission, consulted only after gate/cap eligibility.
         * A deferral leaves the physical cap timestamp, gate and admitted FIFO unchanged. */
        default boolean allowInjection(Observation own,String source){return true;}
        /** The current native crosshair and miss cooldown, both local to the clicking client. */
        default boolean allowInjection(Observation own,String source,java.util.function.BooleanSupplier contactReady){return allowInjection(own,source);}

        /** Successful physical injection only; return the gate to retain until the next input decision. */
        default boolean injected(Observation own,double completedAt){return true;}
        /** Native clickMouse invocation, including a call suppressed by the native miss counter. */
        default void consumed(Observation own,long admissionId){}
    }
    public interface Audit {void event(double time,String event,PeriodicComboFightModel.State state);}
    /** ADMIT time is completion; admittedNanos records the originating poll opportunity. */
    public record ClickEvent(double time,boolean fromA,String action,String source,long admissionId,
                             long admittedNanos,long tick,int queued,int missCounter,boolean eligible,boolean contact) {}
    public interface ClickAudit {void event(ClickEvent event);}
    /** A tape has explicit drains/interpolation and never automatically adds periodic events.
     * TICK_HEAD selects keys/gate and normally polls; explicitPolls separates those opportunities.
     * CONSUME drains the predicted queue, never a recorded count. */
    public enum Kind { FRAME_ENTRY, CLIENT_DRAIN, TICK_HEAD, CONSUME, MOVE, INTERP, FLUSH,
                       SERVER_DRAIN, SERVER_ENTITY, SERVER_TRACK, SERVER_END, POLL_FRAME, POLL_NATIVE }
    public record Scheduled(long nanos,long sequence,Kind kind,boolean fromA) {}
    private record Event(double time,int kind,long order,double originalTime) {}
    public record HeadTiming(long tick,double originalTime,double actualTime,long sequence) {}
    public record BackpressureStats(long deferredStages,long deferralAttempts,double maxDelayMillis,Map<Kind,Long> stageCounts) {}
    public record ServiceStats(long drains,long deferredStages,long enqueueDeferrals,double maxDelayMillis) {}
    private record ServiceLock(long start,long end) {}
    private record Press(long id,long admittedNanos) {}
    private record Injection(Press press,String source,long completedNanos) {}
    /** Exactly one of cue/callback is non-null. Cue packets have not applied yet;
     * other packets already applied, with an immutable FIFO-delayed callback. */
    private record Publication(long nanos,PeriodicComboFightModel.Packet cue,PacketObservation callback,boolean apply,boolean deliver) {
        Publication(long nanos,PeriodicComboFightModel.Packet cue,PacketObservation callback){this(nanos,cue,callback,cue!=null,true);}
        Publication deepCopy(){return new Publication(nanos,cue==null?null:cue.deepCopy(),callback,apply,deliver);}
    }
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
    private double scheduleEnd;
    private final PriorityQueue<Event> events;
    // Opt-in immutable future opportunities; only dynamically generated events remain in the heap.
    private Event[] sharedCalendar;
    private int calendarCursor;
    private Event[][] phaseCalendar;
    private int[] phaseCursors;
    private double[] phaseOffsets;
    private int nextSource=-1;
    private boolean shareableCalendar=true;
    private final long[] ticks=new long[2];private final Control[] controls=new Control[2];private final double[] lastClick={-1e100,-1e100};
    private final ClickQueue[] clickQueues={new ClickQueue(),new ClickQueue()};
    private final Injection[] injections=new Injection[2];
    private final List<LinkedList<Publication>> publications=List.of(new LinkedList<>(),new LinkedList<>());
    private final boolean[] selectionReady=new boolean[2];
    private final HeadTiming[] headTimings=new HeadTiming[2];
    private final long[][] deferredCounts=new long[2][Kind.values().length];
    private final long[] deferredStages=new long[2],deferralAttempts=new long[2];
    private final double[] maxDeferral=new double[2];
    private final long[] serviceUntil={Long.MIN_VALUE,Long.MIN_VALUE},serviceDrains=new long[2],serviceDeferred=new long[2],enqueueDeferred=new long[2];
    private final double[] serviceMaxDelay=new double[2];
    private final List<ArrayList<ServiceLock>> serviceLocks=List.of(new ArrayList<>(),new ArrayList<>());
    private long order;private double now;private boolean executionStarted;private Audit audit;private ClickAudit clickAudit;
    public PeriodicComboClock(PeriodicComboFightModel.State state,PeriodicComboFightModel.Config config,Timing timing,Policy a,Policy b){this(state,config,timing,a,b,null);}
    /** Own observations/policies generate inputs; the tape supplies event opportunities/order only.
     * Queued mode models zero-delay, short press/release, no GUI/block/use, flat-arena clicks. */
    public PeriodicComboClock(PeriodicComboFightModel.State state,PeriodicComboFightModel.Config config,Timing timing,Policy a,Policy b,List<Scheduled> schedule){
        this.state=state;this.config=config;this.timing=timing;policies=new Policy[]{a,b};recordedSchedule=schedule!=null;scheduleEnd=schedule==null?Double.POSITIVE_INFINITY:schedule.stream().mapToLong(Scheduled::nanos).max().orElseThrow(()->new IllegalArgumentException("Empty event tape"))/1_000_000.0;config.deferMovementSend=true;state.transport=null;
        Comparator<Event> comparator=Comparator.comparingDouble(Event::time);
        events=new PriorityQueue<>(timing.causalBackpressure||timing.cuePublicationTiming!=null||timing.packetCueDelays?comparator.thenComparingInt(e->e.kind>=22?0:1)
            .thenComparingDouble(Event::originalTime).thenComparingLong(Event::order):comparator.thenComparingLong(Event::order));
        state.extraLatency=new double[]{timing.uplinkA,timing.uplinkB,timing.downlinkA,timing.downlinkB};
        state.nominalHalfRtt=new double[]{config.ownRtt/2,config.otherRtt/2,config.ownRtt/2,config.otherRtt/2};
        if(timing.inputMode==InputMode.NATIVE_QUEUED&&timing.clickIntervalNanos<50_000_000L)throw new IllegalArgumentException("Physical injection ceiling exceeds20CPS");
        if((timing.explicitPolls||timing.injectionTiming!=null)&&schedule==null)throw new IllegalArgumentException("Explicit polls/injection timing require an event tape");
        if(timing.causalBackpressure&&schedule==null)throw new IllegalArgumentException("Causal backpressure requires an event tape");
        if(timing.serviceMode!=ServiceMode.NONE&&(schedule==null||!timing.causalBackpressure))throw new IllegalArgumentException("Service backpressure requires a tape and causalBackpressure");
        if(timing.drainServiceTiming!=null&&(schedule==null||!timing.causalBackpressure||timing.serviceMode==ServiceMode.NONE))throw new IllegalArgumentException("Drain templates require tape, causalBackpressure and service mode");
        if((timing.cuePublicationTiming!=null||timing.packetCueDelays)&&schedule==null)throw new IllegalArgumentException("Cue publication timing requires an event tape");
        if(schedule==null){add(timing.aFramePhase,0);add(timing.bFramePhase,1);add(timing.aTickPhase,2);add(timing.bTickPhase,3);add(timing.serverPhase,8);}
        else{
            if(timing.inputMode!=InputMode.NATIVE_QUEUED)throw new IllegalArgumentException("Explicit event tapes require NATIVE_QUEUED mode");
            Set<Long> sequences=new HashSet<>();
            for(Scheduled e:List.copyOf(schedule)){
                if(e.sequence<0)shareableCalendar=false;
                if(e.nanos<0||!sequences.add(e.sequence))throw new IllegalArgumentException("Tape requires nonnegative times and unique sequence values");
                if((e.kind==Kind.POLL_FRAME||e.kind==Kind.POLL_NATIVE)&&!timing.explicitPolls)throw new IllegalArgumentException("POLL tape events require explicitPolls");
                events.add(new Event(e.nanos/1_000_000.0,kind(e),e.sequence,e.nanos/1_000_000.0));
            }
        }
    }
    private static int kind(Scheduled e){int side=e.fromA?0:1;return switch(e.kind){case FRAME_ENTRY->side;case TICK_HEAD->2+side;case MOVE->4+side;case FLUSH->6+side;case SERVER_DRAIN->8;case SERVER_ENTITY->9;case SERVER_TRACK->10;case SERVER_END->11;case INTERP->12+side;case CONSUME->14+side;case CLIENT_DRAIN->16+side;case POLL_FRAME->18+side;case POLL_NATIVE->20+side;};}
    private PeriodicComboClock(PeriodicComboClock source,Policy a,Policy b){
        state=source.state.deepCopy();config=source.config.deepCopy();timing=source.timing.deepCopy();policies=new Policy[]{Objects.requireNonNull(a),Objects.requireNonNull(b)};recordedSchedule=source.recordedSchedule;scheduleEnd=source.scheduleEnd;
        events=new PriorityQueue<>(source.events.comparator());
        events.addAll(source.events);sharedCalendar=source.sharedCalendar;calendarCursor=source.calendarCursor;shareableCalendar=source.shareableCalendar;
        phaseCalendar=source.phaseCalendar;phaseCursors=source.phaseCursors==null?null:source.phaseCursors.clone();phaseOffsets=source.phaseOffsets==null?null:source.phaseOffsets.clone();nextSource=source.nextSource;
        System.arraycopy(source.ticks,0,ticks,0,2);System.arraycopy(source.controls,0,controls,0,2);System.arraycopy(source.lastClick,0,lastClick,0,2);executionStarted=source.executionStarted;
        System.arraycopy(source.selectionReady,0,selectionReady,0,2);clickQueues[0]=source.clickQueues[0].copy();clickQueues[1]=source.clickQueues[1].copy();order=source.order;now=source.now;
        System.arraycopy(source.injections,0,injections,0,2);
        for(int side=0;side<2;side++)for(Publication p:source.publications.get(side))publications.get(side).addLast(p.deepCopy());
        System.arraycopy(source.headTimings,0,headTimings,0,2);
        for(int side=0;side<2;side++)System.arraycopy(source.deferredCounts[side],0,deferredCounts[side],0,deferredCounts[side].length);
        System.arraycopy(source.deferredStages,0,deferredStages,0,2);System.arraycopy(source.deferralAttempts,0,deferralAttempts,0,2);System.arraycopy(source.maxDeferral,0,maxDeferral,0,2);
        for(int side=0;side<2;side++){serviceUntil[side]=source.serviceUntil[side];serviceDrains[side]=source.serviceDrains[side];serviceDeferred[side]=source.serviceDeferred[side];enqueueDeferred[side]=source.enqueueDeferred[side];serviceMaxDelay[side]=source.serviceMaxDelay[side];serviceLocks.get(side).addAll(source.serviceLocks.get(side));}
    }
    /** Policies must be fresh or immutable. All packet/input queues and scheduler state are copied;
     * audits are detached. An already admitted input survives a later gate/key change. */
    public PeriodicComboClock fork(Policy a,Policy b){return new PeriodicComboClock(this,a,b);}
    /** Observer-only scheduler boundary for prospective-clock rollouts. This
     * replaces only not-yet-attempted host opportunities strictly after now.
     * Current/equal-time events, deferred stages and generated completions stay
     * committed, as do every packet/input/publication queue and policy state.
     * This is NOT by itself a causal timing adapter: packet-timing/readiness
     * hooks and policy wall-clock mappings must also describe the new calendar.
     * Mutation is atomic after validation and is intended for an isolated fork. */
    public void replaceFutureHostOpportunities(List<Scheduled> replacement,double coverageEnd){
        if(!recordedSchedule||!Double.isFinite(coverageEnd)||coverageEnd<now)
            throw new IllegalArgumentException("Finite tape coverage at/after the current cut required");
        List<Event> all=pendingEvents();List<Event> retained=new ArrayList<>();Set<Long> sequences=new HashSet<>();
        for(Event e:all)if(!replaceableHost(e)){
            if(e.time>coverageEnd)throw new IllegalArgumentException("Replacement would truncate committed work");
            retained.add(e);sequences.add(e.order);
        }
        List<Event> proposed=new ArrayList<>();
        for(Scheduled e:List.copyOf(replacement)){
            double time=e.nanos/1_000_000.0;
            if(e.nanos<0||e.sequence<0||time<=now||time>coverageEnd||!sequences.add(e.sequence))
                throw new IllegalArgumentException("Replacement needs unique nonnegative sequences and strictly future covered times");
            if((e.kind==Kind.POLL_FRAME||e.kind==Kind.POLL_NATIVE)&&!timing.explicitPolls)
                throw new IllegalArgumentException("Replacement polls require explicitPolls");
            proposed.add(new Event(time,kind(e),e.sequence,time));
        }
        proposed.sort(events.comparator());
        events.clear();events.addAll(retained);sharedCalendar=proposed.toArray(Event[]::new);calendarCursor=0;
        phaseCalendar=null;phaseCursors=null;phaseOffsets=null;nextSource=-1;shareableCalendar=true;scheduleEnd=coverageEnd;
    }
    /** Diagnostic export of the replaceable suffix. Deferred and equal-time
     * opportunities are intentionally absent. Never expose this eventual tape
     * to an allegedly causal policy. Nanosecond event precision is required. */
    public List<Scheduled> futureHostOpportunities(){
        if(!recordedSchedule)throw new IllegalStateException("Recorded opportunity tape required");
        List<Scheduled> result=new ArrayList<>();
        for(Event e:pendingEvents())if(replaceableHost(e)){
            long nanos=Math.round(e.time*1_000_000.0);
            if(nanos/1_000_000.0!=e.time)throw new IllegalStateException("Sub-nanosecond shifted opportunity cannot be exported exactly");
            boolean client=e.kind<8||e.kind>=12;
            Kind kind=client?clientKind(e.kind):switch(e.kind){case 8->Kind.SERVER_DRAIN;case 9->Kind.SERVER_ENTITY;case 10->Kind.SERVER_TRACK;case 11->Kind.SERVER_END;default->throw new AssertionError();};
            result.add(new Scheduled(nanos,e.order,kind,!client||(e.kind&1)==0));
        }
        return List.copyOf(result);
    }
    private boolean replaceableHost(Event e){return e.kind<22&&e.originalTime==e.time&&e.time>now;}
    private List<Event> pendingEvents(){
        List<Event> all=new ArrayList<>(events);
        if(sharedCalendar!=null)for(int i=calendarCursor;i<sharedCalendar.length;i++)all.add(sharedCalendar[i]);
        if(phaseCalendar!=null)for(int side=0;side<3;side++)for(int i=phaseCursors[side];i<phaseCalendar[side].length;i++){
            Event e=phaseCalendar[side][i];double delta=phaseOffsets[side];
            all.add(delta==0?e:new Event(e.time+delta,e.kind,e.order,e.originalTime+delta));
        }
        all.sort(events.comparator());return all;
    }
    /** Observer-only timing proposal on a fork. Retain all executed state,
     * packets, publications and in-progress injection/service completions.
     * Only unattempted future client opportunities move; an already deferred
     * opportunity retains its original time and pending execution. Normal backpressure
     * still applies. This is an explicit schedule hypothesis, never a policy
     * control or a change to default/replay timing. */
    public void shiftFutureClientOpportunities(boolean fromA,double deltaMillis){
        if(!recordedSchedule||!Double.isFinite(deltaMillis)||Math.abs(deltaMillis)>25)
            throw new IllegalArgumentException("Bounded tape-only client phase proposal required");
        if(phaseCalendar==null){
            List<List<Event>> groups=List.of(new ArrayList<>(),new ArrayList<>(),new ArrayList<>());
            List<Event> future=new ArrayList<>(events);events.clear();
            if(sharedCalendar!=null)for(int i=calendarCursor;i<sharedCalendar.length;i++)future.add(sharedCalendar[i]);
            for(Event e:future){
                if(e.kind>=22||e.originalTime<e.time){events.add(e);continue;}
                boolean client=e.kind<8||e.kind>=12;
                groups.get(client?(e.kind&1):2).add(e);
            }
            phaseCalendar=new Event[3][];phaseCursors=new int[3];phaseOffsets=new double[3];
            for(int i=0;i<3;i++){groups.get(i).sort(events.comparator());phaseCalendar[i]=groups.get(i).toArray(Event[]::new);}
            sharedCalendar=null;calendarCursor=0;
        }
        int side=fromA?0:1;
        if(phaseCursors[side]<phaseCalendar[side].length)
            deltaMillis=Math.max(deltaMillis,now+.000001-phaseCalendar[side][phaseCursors[side]].time-phaseOffsets[side]);
        for(Event e:events)if(e.kind<22&&e.originalTime==e.time&&(e.kind<8||e.kind>=12)&&(e.kind&1)==side)
            deltaMillis=Math.max(deltaMillis,now+.000001-e.time);
        phaseOffsets[side]+=deltaMillis;
        ArrayList<Event> pending=new ArrayList<>(events);events.clear();
        for(Event e:pending){
            if(e.kind<22&&e.originalTime==e.time&&(e.kind<8||e.kind>=12)&&(e.kind&1)==side)
                events.add(new Event(e.time+deltaMillis,e.kind,e.order,e.originalTime+deltaMillis));
            else events.add(e);
        }
        shareableCalendar=false;
    }
    /** Freeze remaining recorded opportunities once; forks share this immutable array.
     * Negative source sequences are unsupported here because they can alias generated
     * completion/publication priorities. Legacy/default queues remain unrestricted. */
    public void enableSharedCalendar(){
        if(sharedCalendar!=null)return;
        if(!recordedSchedule||!shareableCalendar)
            throw new IllegalStateException("Shared calendar requires a tape with nonnegative source sequences");
        Event[] tape=events.toArray(Event[]::new);
        Arrays.sort(tape,events.comparator());
        for(int i=1;i<tape.length;i++)if(events.comparator().compare(tape[i-1],tape[i])==0)
            throw new IllegalStateException("Shared calendar requires unambiguous event priorities");
        sharedCalendar=tape;calendarCursor=0;events.clear();
    }
    private Event nextEvent(){
        Event best=events.peek();nextSource=-1;
        if(sharedCalendar!=null&&calendarCursor<sharedCalendar.length){
            Event tape=sharedCalendar[calendarCursor];
            if(best==null||events.comparator().compare(tape,best)<0){best=tape;nextSource=-2;}
        }
        if(phaseCalendar!=null)for(int i=0;i<3;i++)if(phaseCursors[i]<phaseCalendar[i].length){
            Event e=phaseCalendar[i][phaseCursors[i]];double d=phaseOffsets[i];
            Event shifted=d==0?e:new Event(e.time+d,e.kind,e.order,e.originalTime+d);
            if(best==null||events.comparator().compare(shifted,best)<0){best=shifted;nextSource=i;}
        }
        return best;
    }
    private Event removeEvent(){
        Event next=nextEvent();
        if(nextSource==-2)calendarCursor++;
        else if(nextSource>=0)phaseCursors[nextSource]++;
        else events.remove();
        return next;
    }
    private void validateEnd(double end){
        if(end<now)throw new IllegalArgumentException("Cannot run a clock backwards");
        if(!Double.isFinite(end)||end>scheduleEnd)throw new IllegalArgumentException("Requested time exceeds supplied event-tape coverage");
        executionStarted=true;
    }
    /** Resolve a busy opportunity by the same pop/reinsert rule used by runUntil.
     * A ready HEAD is inspected but never popped by the boundary API. */
    private boolean prepareNext(){
        Event e=nextEvent();now=e.time;int k=e.kind,side=(k&1)==0?0:1;
        boolean clientStage=k<8||k>=12&&k<22;
        if(clientStage&&timing.serviceMode!=ServiceMode.NONE&&Math.round(now*1e6)<serviceUntil[side]){
            removeEvent();serviceDeferred[side]++;serviceMaxDelay[side]=Math.max(serviceMaxDelay[side],serviceUntil[side]/1e6-e.originalTime);
            deferUntil(e,side,serviceUntil[side]);return false;
        }
        if((k<8||k>=12&&k<24)&&!publications.get(side).isEmpty()){
            removeEvent(); // Match legacy rejection state: the unsupported opportunity was popped.
            String message="UNSUPPORTED_CUE_SERVICE_OVERLAP: same-client stage before FIFO publication completes";
            if(timing.serviceMode!=ServiceMode.NONE)message+="; side="+side+" kind="+k+" actual="+now+" original="+e.originalTime+" publication="+publications.get(side).getLast().nanos/1e6+" busy_until="+serviceUntil[side]/1e6;
            throw new IllegalStateException(message);
        }
        if((k<8||k>=12&&k<22)&&injections[side]!=null){
            if(!timing.causalBackpressure){
                removeEvent();
                throw new IllegalStateException("UNSUPPORTED_INJECTION_OVERLAP: same-client event before synchronous injection completion");
            }
            removeEvent();now=e.time;defer(e,side);return false;
        }
        return true;
    }
    /** Process all preceding events, including equal-time predecessors, then pause
     * immediately BEFORE a backpressure-ready requested HEAD. No policy is called.
     * False means coverage/limit reached; the clock has executed through limit. */
    public boolean advanceToBeforeNextHead(boolean a,double limit){
        validateEnd(limit);
        while(nextEvent()!=null&&nextEvent().time<=limit){
            if(!prepareNext())continue;
            if(nextEvent().kind==(a?2:3)){now=nextEvent().time;return true;}
            executeNext();
        }
        now=limit;return false;
    }
    public boolean advanceToBeforeNextHead(boolean a){return advanceToBeforeNextHead(a,scheduleEnd);}
    /** Execute one requested HEAD, then stop before the next ready requested HEAD.
     * False retains all work/credit through limit, without executing beyond it. */
    public boolean runHeadInterval(boolean a,double limit){
        if(!advanceToBeforeNextHead(a,limit))return false;
        executeNext();
        return advanceToBeforeNextHead(a,limit);
    }
    public boolean runHeadInterval(boolean a){return runHeadInterval(a,scheduleEnd);}
    /** Explicit source-derived inactive input for a tick straddling activation.
     * This is not a policy HEAD: no callback, aiming, cap update, queue reset or
     * tick increment occurs. Retain the real residual INTERP/MOVE/FLUSH events.
     * The caller must establish inactive/released input and empty startup queue
     * from the capture; a missing mid-run HEAD cannot use this initializer. */
    public void initializeReleasedStartup(boolean fromA){
        int side=fromA?0:1;
        if(executionStarted||ticks[side]!=0||controls[side]!=null)
            throw new IllegalStateException("Released startup must precede execution with tick0 and no held control");
        controls[side]=new Control(0,0,false);
    }
    public void setAudit(Audit audit){this.audit=audit;}
    public void setClickAudit(ClickAudit audit){clickAudit=audit;}
    public double time(){return now;}
    /** Last control sampled at this client's head, including the primed control of a fork. */
    public Control appliedControl(boolean fromA){return controls[fromA?0:1];}
    public int queuedClicks(boolean fromA){return clickQueues[fromA?0:1].presses.size();}
    public int missCounter(boolean fromA){return clickQueues[fromA?0:1].missCounter;}
    public long serviceBusyUntil(boolean fromA){return serviceUntil[fromA?0:1];}
    public ServiceStats serviceStats(boolean fromA){int s=fromA?0:1;return new ServiceStats(serviceDrains[s],serviceDeferred[s],enqueueDeferred[s],serviceMaxDelay[s]);}
    /** Set before this side's Policy.input callback; original time retains the
     * donor calendar's head/wall-label mapping when actual execution is delayed. */
    public HeadTiming headTiming(boolean fromA){return headTimings[fromA?0:1];}
    public BackpressureStats backpressureStats(boolean fromA){
        int side=fromA?0:1;Map<Kind,Long> counts=new EnumMap<>(Kind.class);
        for(Kind kind:Kind.values())if(deferredCounts[side][kind.ordinal()]!=0)counts.put(kind,deferredCounts[side][kind.ordinal()]);
        return new BackpressureStats(deferredStages[side],deferralAttempts[side],maxDeferral[side],Map.copyOf(counts));
    }
    private void add(double time,int kind){events.add(new Event(time,kind,order++,time));}
    private static Kind clientKind(int k){return switch(k/2){
        case 0->Kind.FRAME_ENTRY;case 1->Kind.TICK_HEAD;case 2->Kind.MOVE;case 3->Kind.FLUSH;
        case 6->Kind.INTERP;case 7->Kind.CONSUME;case 8->Kind.CLIENT_DRAIN;case 9->Kind.POLL_FRAME;case 10->Kind.POLL_NATIVE;
        default->throw new AssertionError("Not a client stage: "+k);
    };}
    private void defer(Event e,int side){
        deferUntil(e,side,injections[side].completedNanos);
    }
    private void deferUntil(Event e,int side,long completion){
        long reference=Math.round(e.originalTime*1_000_000.0);
        long delay=Math.subtractExact(completion,reference);
        if(delay>=50_000_000L)throw new IllegalStateException("UNSUPPORTED_BACKPRESSURE_DELAY: >=50ms from original client opportunity; timer/catch-up counts unknown");
        if(delay<0||completion/1_000_000.0<e.time)throw new AssertionError("Backpressure cannot advance a stage");
        if(e.time==e.originalTime){deferredStages[side]++;deferredCounts[side][clientKind(e.kind).ordinal()]++;}
        deferralAttempts[side]++;maxDeferral[side]=Math.max(maxDeferral[side],delay/1_000_000.0);
        events.add(new Event(completion/1_000_000.0,e.kind,e.order,e.originalTime));
    }
    private Observation observation(boolean a){PeriodicComboFightModel.Body b=a?state.a:state.b;PeriodicComboFightModel.View v=a?state.ab:state.ba;return new Observation(now,ticks[a?0:1],b.q,v.x,v.y,v.z,b.sprint,b.headYaw,b.lastContactTime,b.localContacts,v.lastHurtTime,b.lastHurtTime,b.reportYaw);}
    private void receive(boolean a){
        int side=a?0:1;LinkedList<Publication> pending=publications.get(side);
        long drainNanos=Math.round(now*1_000_000.0);
        if(timing.serviceMode!=ServiceMode.NONE)deferBlockedEnqueues(side);
        var batch=PeriodicComboFightModel.due(state,now,a?1:2);
        if(timing.drainServiceTiming!=null){receiveTemplate(a,drainNanos,batch);return;}
        if(timing.serviceMode!=ServiceMode.NONE)startService(side,drainNanos,batch);
        for(PeriodicComboFightModel.Packet p:batch){
            if(p.cueDelayNanos!=null&&!timing.packetCueDelays)
                throw new IllegalStateException("UNSUPPORTED_PACKET_CUE_MODE: stored delay requires explicit opt-in");
            if(p.kind>=12){
                long publication=drainNanos;
                if(p.cueDelayNanos!=null){
                    try{publication=Math.addExact(drainNanos,p.cueDelayNanos);}
                    catch(ArithmeticException e){throw new IllegalStateException("UNSUPPORTED_CUE_TIME: publication overflows",e);}
                }else if(timing.cuePublicationTiming!=null)
                    publication=timing.cuePublicationTiming.publicationNanos(a?2:3,Math.round(p.due*1_000_000.0),Math.round(p.ready*1_000_000.0),drainNanos);
                if(publication<drainNanos)throw new IllegalStateException("UNSUPPORTED_CUE_TIME: publication precedes selected drain");
                if(timing.serviceMode!=ServiceMode.NONE&&publication>Math.max(drainNanos,serviceUntil[side]))throw new IllegalStateException("UNSUPPORTED_SERVICE_ENVELOPE: publication exceeds declared completion");
                if(publication/1_000_000.0>scheduleEnd)throw new IllegalStateException("UNSUPPORTED_CUE_TIME: publication exceeds tape coverage");
                if(!pending.isEmpty()&&publication<pending.getLast().nanos)
                    throw new IllegalStateException("UNSUPPORTED_CUE_ORDER: publication would invert client FIFO");
                if(publication==drainNanos&&pending.isEmpty()){
                    PeriodicComboFightModel.applyReceived(state,now,a,p);
                    policies[side].received(packetObservation(a,p,now));
                }else enqueuePublication(a,new Publication(publication,p.deepCopy(),null));
            }else{
                PeriodicComboFightModel.applyReceived(state,now,a,p);
                if(pending.isEmpty())policies[side].received(packetObservation(a,p,now));
                else{
                    long publication=pending.getLast().nanos;
                    enqueuePublication(a,new Publication(publication,null,packetObservation(a,p,publication/1_000_000.0)));
                }
            }
        }
    }
    /** Native scheduledTasks monitor freezes membership at acquisition. Future
     * enqueues wait for unlock; they are never dropped or admitted into this batch. */
    private void receiveTemplate(boolean a,long start,List<PeriodicComboFightModel.Packet> batch){
        if(batch.isEmpty())return; // Empty work retains the supplied host calendar.
        int side=a?0:1;var word=new ArrayList<DrainPacket>();
        for(var p:batch)word.add(new DrainPacket(receivedKind(p),p.packetId));
        DrainServicePlan plan=Objects.requireNonNull(timing.drainServiceTiming.plan(side+2,start,List.copyOf(word)),"Missing drain service plan");
        if(plan.packets().size()!=batch.size())throw new IllegalStateException("UNKNOWN_DRAIN_SERVICE_WORD: template packet count differs from selected FIFO");
        long completion=Math.addExact(start,plan.completionOffsetNanos());
        if(completion/1e6>scheduleEnd)throw new IllegalStateException("UNSUPPORTED_SERVICE_TIME: template completion exceeds supplied tape");
        if(!publications.get(side).isEmpty())throw new IllegalStateException("UNSUPPORTED_SERVICE_OVERLAP: previous template still publishing");
        serviceUntil[side]=completion;serviceDrains[side]++;serviceLocks.get(side).add(new ServiceLock(start,completion));
        for(int i=0;i<batch.size();i++){
            var p=batch.get(i);var service=plan.packets().get(i);
            long effect=Math.addExact(start,service.effectOffsetNanos()),callback=Math.addExact(start,service.callbackOffsetNanos());
            enqueuePublication(a,new Publication(effect,p.deepCopy(),null,true,false));
            enqueuePublication(a,new Publication(callback,p.deepCopy(),null,false,true));
        }
    }
    /** Native enqueues use the same monitor as the drain. Retained raw arrivals
     * are unchanged; attempted readiness inside an extended lock waits until
     * release. Equal-unlock enqueues precede the next drain in this explicit
     * ordering convention and never join the already selected batch. */
    private void deferBlockedEnqueues(int side){
        int route=side+2;var packets=new ArrayList<PeriodicComboFightModel.Packet>();
        for(var p:state.packets)if(PeriodicComboTransport.route(p.kind)==route)packets.add(p);
        packets.sort(Comparator.comparingLong(p->p.sequence));double watermark=Double.NEGATIVE_INFINITY;
        for(var p:packets){
            double ready=Math.max(p.ready,watermark);long ns=Math.round(ready*1e6);
            for(var lock:serviceLocks.get(side))if(ns>=lock.start&&ns<lock.end)ns=lock.end;
            ready=Math.max(ready,ns/1e6);
            if(ready>p.ready){enqueueDeferred[side]++;p.ready=ready;}
            watermark=p.ready;
        }
        state.queueReadyWatermarks[route]=Math.max(state.queueReadyWatermarks[route],watermark);
    }
    private void startService(int side,long start,List<PeriodicComboFightModel.Packet> batch){
        String id=null;long offset=0;boolean declared=false;
        for(var p:batch){
            var s=p.service;if(s==null)continue;
            if(id!=null&&!id.equals(s.batchId()))throw new IllegalStateException("UNSUPPORTED_SERVICE_COMPOSITION: distinct native drain envelopes coalesced; first="+id+" next="+s.batchId());
            id=s.batchId();declared=true;
            long chosen=switch(timing.serviceMode){case LOWER->s.completionLowerNanos();case MIDPOINT->s.completionLowerNanos()+(s.completionUpperNanos()-s.completionLowerNanos())/2;case UPPER->s.completionUpperNanos();default->throw new AssertionError();};
            offset=Math.max(offset,chosen);
        }
        if(!declared)return;
        long completion=Math.addExact(start,offset);
        if(completion/1e6>scheduleEnd)throw new IllegalStateException("UNSUPPORTED_SERVICE_TIME: completion exceeds supplied tape");
        serviceUntil[side]=completion;serviceDrains[side]++;serviceLocks.get(side).add(new ServiceLock(start,completion));
    }
    private void enqueuePublication(boolean a,Publication publication){
        publications.get(a?0:1).addLast(publication);
        double time=publication.nanos/1_000_000.0;
        events.add(new Event(time,a?24:25,Long.MIN_VALUE+order++,time));
    }
    private String publish(boolean a){
        int side=a?0:1;Publication p=publications.get(side).removeFirst();
        if(p.nanos!=Math.round(now*1_000_000.0))throw new AssertionError("Cue publication queue/event mismatch");
        if(p.cue!=null){
            if(p.apply)PeriodicComboFightModel.applyReceived(state,now,a,p.cue);
            if(p.deliver)policies[side].received(packetObservation(a,p.cue,now));
            return (p.apply?(p.deliver?"CUE_PUBLICATION":"PACKET_APPLICATION"):"RECEIVE_CALLBACK")+(a?"A":"B");
        }
        policies[side].received(p.callback);
        return "RECEIVE_CALLBACK"+(a?"A":"B");
    }
    private static ReceivedKind receivedKind(PeriodicComboFightModel.Packet p){
        if(p.kind==5||p.kind==6)return p.packetId==0x16?ReceivedKind.TARGET_LOOK:ReceivedKind.TARGET_POSITION;
        if(p.kind==4||p.kind==8)return ReceivedKind.OWN_VELOCITY;
        if(p.kind==10||p.kind==11)return ReceivedKind.OWN_SPRINT;
        if(p.kind==12||p.kind==13)return ReceivedKind.TARGET_HURT;
        if(p.kind==14||p.kind==15)return ReceivedKind.OWN_HURT;
        throw new IllegalArgumentException("Unsupported client drain packet kind "+p.kind);
    }
    private PacketObservation packetObservation(boolean a,PeriodicComboFightModel.Packet p,double handledAt){
            ReceivedKind kind;double x=Double.NaN,y=Double.NaN,z=Double.NaN,vx=Double.NaN,vy=Double.NaN,vz=Double.NaN;boolean ground=false,sprint=false;
            if(p.kind==5||p.kind==6){
                kind=p.packetId==0x16?ReceivedKind.TARGET_LOOK:ReceivedKind.TARGET_POSITION;
                PeriodicComboFightModel.View v=a?state.ab:state.ba;x=v.gx;y=v.gy;z=v.gz;ground=p.ground;
            }else if(p.kind==4||p.kind==8){kind=ReceivedKind.OWN_VELOCITY;vx=p.vx;vy=p.vy;vz=p.vz;}
            else if(p.kind==10||p.kind==11){kind=ReceivedKind.OWN_SPRINT;sprint=p.flag;}
            else if(p.kind==12||p.kind==13)kind=ReceivedKind.TARGET_HURT;
            else if(p.kind==14||p.kind==15)kind=ReceivedKind.OWN_HURT;
            else throw new AssertionError("Unexpected own incoming packet kind "+p.kind);
            return new PacketObservation(p.due,handledAt,p.packetId,kind,x,y,z,ground,vx,vy,vz,sprint);
    }
    private void aim(boolean a){int side=a?0:1;PeriodicComboFightModel.Body b=a?state.a:state.b;PeriodicComboFightModel.View v=a?state.ab:state.ba;double dx=v.x-b.q.x(),dz=v.z-b.q.z(),eye=b.q.y()+PeriodicComboFightModel.EYE;double targetY=Math.max(v.y+.15,Math.min(v.y+1.65,eye));b.yaw=policies[side].aimYaw((float)Math.toDegrees(Math.atan2(-dx,dz)));b.pitch=(float)-Math.toDegrees(Math.atan2(targetY-eye,Math.hypot(dx,dz)));selectionReady[side]=true;}
    private boolean selectsOpponent(boolean a){
        return PeriodicComboFightModel.ray(a?state.a:state.b,a?state.ab:state.ba)<=(a?3:config.defenderReach);
    }
    private void poll(boolean a,String source){
        int side=a?0:1;ClickQueue q=clickQueues[side];long ns=Math.round(now*1_000_000.0);
        boolean ready=q.lastCompletedNanos==Long.MIN_VALUE||ns-q.lastCompletedNanos>=timing.clickIntervalNanos;
        if(!q.gate||!ready){clickEvent(a,"POLL",source,null,ready,false);return;}
        if(!policies[side].allowInjection(observation(a),source,()->q.missCounter<=0&&selectsOpponent(a))
                ||policies[side].contactOnly()&&!selectsOpponent(a)){clickEvent(a,"DEFER",source,null,true,false);return;}
        long delay=timing.injectionTiming==null?0:timing.injectionTiming.completionDelayNanos(a,ns);
        if(delay<0)throw new IllegalArgumentException("Negative injection completion overhead");
        long completed=Math.addExact(ns,delay);
        if(completed/1_000_000.0>scheduleEnd)throw new IllegalStateException("Injection completion exceeds tape coverage");
        Press press=new Press(++q.nextId,ns);injections[side]=new Injection(press,source,completed);
        if(delay==0)completeInjection(a);
        else{
            // A synchronous native call blocks this client. Other actor/server events may run.
            // Completion is effective before a new same-nanosecond opportunity.
            events.add(new Event(completed/1_000_000.0,22+side,Long.MIN_VALUE+order++,completed/1_000_000.0));
            clickEvent(a,"INJECTION_START",source,press,true,false);
        }
    }
    private void completeInjection(boolean a){
        int side=a?0:1;Injection injection=Objects.requireNonNull(injections[side],"No pending injection");ClickQueue q=clickQueues[side];
        q.presses.addLast(injection.press);q.lastCompletedNanos=injection.completedNanos;injections[side]=null;
        q.gate=policies[side].injected(observation(a),now);clickEvent(a,"ADMIT",injection.source,injection.press,true,false);
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
        validateEnd(end);
        while(nextEvent()!=null&&nextEvent().time<=end){
            if(prepareNext())executeNext();
        }
        now=end;
    }
    private void executeNext(){
        Event e=removeEvent();now=e.time;int k=e.kind;boolean a=(k&1)==0;int side=a?0:1;String name;
            if(k<2){
                if(timing.inputMode==InputMode.NATIVE_QUEUED){aim(a);if(!timing.explicitPolls)poll(a,"frame");if(!recordedSchedule){receive(a);selectionReady[side]=false;}}
                else{if(timing.aimBeforeFrameDrain)aim(a);receive(a);if(!timing.aimBeforeFrameDrain)aim(a);}
                if(!recordedSchedule)add(now+timing.framePeriod,k);name="FRAME"+(a?"A":"B");
            }
            else if(k<4){
                headTimings[side]=new HeadTiming(ticks[side],e.originalTime,now,e.order);
                Control control=policies[side].input(observation(a));controls[side]=control;
                if(timing.inputMode==InputMode.NATIVE_QUEUED){
                    ClickQueue q=clickQueues[side];if(q.missCounter>0)q.missCounter--;q.gate=control.click;
                    // Reaction0 Client.tick calls Client.frame before native input consumption, even when cap-blocked.
                    aim(a);if(!timing.explicitPolls)poll(a,"native_tick");if(!recordedSchedule)consume(a);
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
                PeriodicComboFightModel.replayMove(state,config,a,control.key,control.strafe,(a?state.a:state.b).yaw,control.jump,now);checkpoint("MOVE"+(a?"A":"B"));
                if(!recordedSchedule&&!first)v.step();ticks[side]++;selectionReady[side]=false;name=recordedSchedule||first?"AFTER_MOVE":"INTERP"+(a?"A":"B");
            }
            else if(k<8){PeriodicComboFightModel.replayFlush(state,config,a,now);name="FLUSH"+(a?"A":"B");}
            else if(k==8){PeriodicComboFightModel.serverDrain(state,config,now);if(!recordedSchedule){add(now+timing.entityAfterDrain,9);add(now+timing.trackAfterDrain,10);add(now+timing.endAfterDrain,11);add(now+timing.serverPeriod,8);}name="SERVER_DRAIN";}
            else if(k==9){PeriodicComboFightModel.serverEntity(state);name="SERVER_ENTITY";}
            else if(k==10){PeriodicComboFightModel.serverTrack(state,config,now);name="SERVER_TRACK";}
            else if(k==11){PeriodicComboFightModel.serverEnd(state);name="SERVER_END";}
            else if(k<14){(a?state.ab:state.ba).step();selectionReady[side]=false;name="INTERP"+(a?"A":"B");}
            else if(k<16){consume(a);name="INPUT"+(a?"A":"B");}
            // Supported packets change velocity/sprint/cues or interpolation goals,
            // never current own/replica geometry. A cached selection survives this
            // drain (including S12 before consume); MOVE/INTERP still invalidate it.
            else if(k<18){receive(a);name="CLIENT_DRAIN"+(a?"A":"B");}
            else if(k<22){poll(a,k<20?"frame":"native_tick");name="POLL"+(a?"A":"B");}
            else if(k<24){completeInjection(a);name="INJECTION_COMPLETE"+(a?"A":"B");}
            else{name=publish(a);}
            checkpoint(name);
    }
    private void checkpoint(String name){PeriodicComboFightModel.audit(state);if(audit!=null)audit.event(now,name,state);}
}
