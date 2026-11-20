import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lab.BoxingApproachPoll;
import lab.Json;

/** Compare ordinary W-tap click admission clocks from the same regulation opening. */
final class BoxingApproachPollCompare {
    private static final long WALL_ORIGIN_MS = 1_000_000;
    record Wait(int millis, boolean firstOnly, double firstImpulseLimit) {
        Wait(int millis, boolean firstOnly) { this(millis,firstOnly,Double.POSITIVE_INFINITY); }
    }
    private record Window(double from, double until) {
        boolean includes(double at) { return from <= at && at <= until; }
    }

    static class Fighter extends DuelEnv.Player {
        final BoxingApproachPoll poll;
        final List<Double> awaits = new ArrayList<>();
        final List<Double> issued = new ArrayList<>();
        final List<Double> held = new ArrayList<>();
        final List<Double> protectedContacts = new ArrayList<>();
        final Wait wait;
        double lastVelocity = Double.NEGATIVE_INFINITY;
        double holdUntil = Double.NEGATIVE_INFINITY;

        Fighter(int rtt, BoxingApproachPoll.Mode mode, Wait wait) {
            super("wtap", 0, rtt, 0);
            poll = new BoxingApproachPoll(mode);
            this.wait = wait;
        }

        Fighter(Fighter source, Wait wait) {
            super("wtap",0,source.rtt,source.wake);
            poll = new BoxingApproachPoll(source.poll);
            this.wait = wait;
            lastVelocity = source.lastVelocity;
            holdUntil = source.holdUntil;
            handled = source.handled;
            releaseUntil = source.releaseUntil;
            firstClick = source.firstClick;
            held.addAll(source.held);
        }

        private static long wall(double time) {
            return Double.isFinite(time) ? WALL_ORIGIN_MS + (long)time : 0;
        }

        private static double toward(PeriodicComboClock.Observation o) {
            double dx = o.targetX() - o.own().x(), dz = o.targetZ() - o.own().z();
            return (o.own().vx()*dx + o.own().vz()*dz) / Math.max(.001,Math.hypot(dx,dz));
        }

        @Override public PeriodicComboClock.Control input(PeriodicComboClock.Observation o) {
            poll.arm(Math.round(o.time()*1e6),wall(o.time()),wall(o.lastOwnHurtTime()),wall(o.lastTargetHurtTime()),
                    o.own().onGround(),o.nearest(),o.targetY()-o.own().y(),toward(o));
            return super.input(o);
        }

        @Override public void received(PeriodicComboClock.PacketObservation packet) {
            if (packet.kind() == PeriodicComboClock.ReceivedKind.OWN_VELOCITY) lastVelocity = packet.handledAt();
            super.received(packet);
        }

        private boolean recoil(PeriodicComboClock.Observation own) {
            double armTime = poll.armedAt()/1e6;
            return lastVelocity >= armTime || own.lastOwnHurtTime() >= armTime || toward(own) < -.05;
        }

        @Override public boolean allowInjection(PeriodicComboClock.Observation own, String source,
                                                java.util.function.BooleanSupplier contactReady) {
            boolean contact = contactReady.getAsBoolean();
            if (wait != null && own.time() < holdUntil && contact) {
                if (!recoil(own)) return false;
                holdUntil = Double.NEGATIVE_INFINITY;
            }
            var decision = poll.poll(Math.round(own.time()*1e6),source.equals("native_tick"),contact);
            if (decision == BoxingApproachPoll.Decision.FIRST_DEFER) awaits.add(own.time());
            if (decision == BoxingApproachPoll.Decision.SELECT) {
                if (wait != null && (!wait.firstOnly || held.isEmpty())) {
                    if (!recoil(own)) {
                        held.add(own.time());
                        holdUntil = own.time()+wait.millis;
                        return false;
                    }
                    protectedContacts.add(own.time());
                }
                issued.add(own.time());
            }
            return decision != BoxingApproachPoll.Decision.FIRST_DEFER && decision != BoxingApproachPoll.Decision.DEFER;
        }
    }

    private record Run(List<Integer> score, double end, int armings, List<Double> waits, List<Double> selected,
                       List<List<Long>> inputs, List<List<Object>> consumes, List<DuelEnv.Hit> hits,
                       List<Double> held, List<Double> protectedContacts, List<List<Object>> timeline) {
        Map<String,Object> summarize(double commonStart, double commonEnd, double firstWait) {
            var sameWindowCps = inputs.stream().map(p -> commonEnd > commonStart
                    ? 1000.0 * p.stream().filter(t -> t/1e6 >= commonStart && t/1e6 <= commonEnd).count()
                    / (commonEnd-commonStart) : 0).toList();
            var firstPhysical = inputs.get(0).stream().filter(t -> t/1e6 >= firstWait).findFirst().orElse(-1L);
            var firstEffective = consumes.stream().filter(r -> (double)r.get(0) >= firstWait && (boolean)r.get(2))
                    .findFirst().orElse(List.of());
            return Map.of("score",score,"cps",sameWindowCps,"armings",armings,"waits",waits,"selected",selected,
                    "first_input_after_wait_ns",firstPhysical,"first_contact_after_wait",firstEffective,"end",end);
        }
    }

    private static Run regulation(DuelEnv env, boolean first, BoxingApproachPoll.Mode mode,
                                  int ownPhase, int opponentPhase, boolean guardedHold) {
        return regulation(env,first,mode,ownPhase,opponentPhase,guardedHold ? new Wait(35,false) : null,null);
    }

    static List<Object> pose(double at, String event, boolean a, boolean first,
                             PeriodicComboFightModel.State state) {
        var owner = a ? state.a : state.b;
        var target = a ? state.ab : state.ba;
        var q = owner.q;
        double ray = PeriodicComboFightModel.ray(owner,target);
        double dx = target.x - q.x(), dz = target.z - q.z();
        return List.of(at,event,a==first,q.y(),q.onGround(),owner.sprint,
                (q.vx()*dx+q.vz()*dz)/Math.max(.001,Math.hypot(dx,dz)),q.vy(),target.y,
                PeriodicComboFightModel.nearest(q,target),Double.isFinite(ray)?ray:-1.,owner.localContacts);
    }

    private static void limitFirstReply(PeriodicComboFightModel.State state, boolean first, double limit) {
        int velocityKind = first ? 4 : 8;
        var pending = state.packets.stream().filter(p -> p.kind==velocityKind)
                .max(java.util.Comparator.comparingLong(p -> p.sequence)).orElseThrow();
        var recorded = first ? state.lastFreshB : state.lastFreshA;
        if (pending.vx!=recorded.x() || pending.vy!=recorded.y() || pending.vz!=recorded.z())
            throw new IllegalStateException("The replacement must be the new reply's applied velocity");
        double magnitude = Math.hypot(pending.vx,pending.vz);
        if (magnitude <= limit) return;
        pending.vx=PeriodicComboFightModel.vel(pending.vx/magnitude*limit);
        pending.vz=PeriodicComboFightModel.vel(pending.vz/magnitude*limit);
        var modified = new PeriodicComboFightModel.FreshImpulse(recorded.time(),recorded.victimBefore(),
                pending.vx,pending.vy,pending.vz);
        if (first) state.lastFreshB=modified; else state.lastFreshA=modified;
    }

    private static Run regulation(DuelEnv env, boolean first, BoxingApproachPoll.Mode mode,
                                  int ownPhase, int opponentPhase, Wait wait, Window trace) {
        var subject = new Fighter(env.rtt,mode,wait);
        var game = env.new Game(first,subject,Evolve.WT.create(env.otherRtt,0));
        if (ownPhase != 0) game.clock.shiftFutureClientOpportunities(first,ownPhase);
        if (opponentPhase != 0) game.clock.shiftFutureClientOpportunities(!first,opponentPhase);
        game.trace();
        var timeline = new ArrayList<List<Object>>();
        boolean[] replySeen = {false};
        if (trace != null) game.clock.setAudit((at,event,state) -> {
            int before = game.hits.list.size();
            game.hits.event(at,event,state);
            if (!trace.includes(at)) return;
            if (event.startsWith("MOVE") || event.startsWith("CLIENT_DRAIN"))
                timeline.add(pose(at,event,event.endsWith("A"),first,state));
            for (int i=before;i<game.hits.list.size();i++) {
                var hit=game.hits.list.get(i);
                if (wait != null && Double.isFinite(wait.firstImpulseLimit) && !replySeen[0] &&
                        !subject.held.isEmpty() && at>=subject.held.get(0) && hit.sideA()==first && !hit.upgrade()) {
                    limitFirstReply(state,first,wait.firstImpulseLimit);
                    replySeen[0] = true;
                }
                var launch=hit.sideA()?state.lastFreshB:state.lastFreshA;
                timeline.add(List.of(at,"HIT",hit.sideA()==first,hit.upgrade(),state.serverTicks,
                        hit.upgrade()?0.:Math.hypot(launch.x(),launch.z()),hit.upgrade()?0.:launch.y(),
                        Math.hypot(state.serverBx-state.serverAx,state.serverBz-state.serverAz)));
            }
        });
        var inputs = List.of(new ArrayList<Long>(),new ArrayList<Long>());
        var consumes = new ArrayList<List<Object>>();
        game.clock.setClickAudit(e -> {
            if (e.action().equals("ADMIT")) {
                var side = inputs.get(e.fromA() == first ? 0 : 1);
                if (!side.isEmpty() && e.admittedNanos()-side.get(side.size()-1) < 50_000_000L)
                    throw new IllegalStateException("20 CPS cap mismatch");
                side.add(e.admittedNanos());
            } else if (e.action().equals("CONSUME") && e.fromA() == first) {
                consumes.add(List.of(e.time(),e.tick(),e.contact(),e.admittedNanos()));
            }
            if (trace != null && trace.includes(e.time()) && e.action().equals("CONSUME")) {
                var sample=new ArrayList<>(pose(e.time(),"PRESS",e.fromA(),first,game.clock.state));
                sample.addAll(List.of(e.tick(),e.eligible(),e.contact(),e.admittedNanos()/1e6));
                timeline.add(sample);
            }
        });
        var score = BoxingDoubleSearch.play(game,100);
        return new Run(score.values(),BoxingEdgeHitSelect.decidingHit(game),subject.poll.arms(),
                subject.awaits,subject.issued,inputs.stream().map(List::copyOf).toList(),consumes,
                List.copyOf(game.hits.list),subject.held,subject.protectedContacts,timeline);
    }

    private static <T> List<T> firstDifference(List<T> control, List<T> changed) {
        int i = 0;
        while (i < control.size() && i < changed.size() && control.get(i).equals(changed.get(i))) i++;
        if (i == control.size() || i == changed.size()) return List.of();
        return List.of(control.get(i),changed.get(i));
    }

    private static List<Double> trackedContacts(Run run, double end) {
        return run.consumes.stream().filter(p -> (boolean)p.get(2)).map(p -> (double)p.get(0))
                .takeWhile(t -> t <= end).toList();
    }

    private static Map<String,Object> admissionDiagnosis(Run normal, Run late, double firstWait, double end) {
        var stock = normal.inputs.get(0).stream().filter(t -> t/1e6 >= firstWait).limit(3).toList();
        var replacement = late.inputs.get(0).stream().filter(t -> t/1e6 >= firstWait).findFirst().orElse(-1L);
        boolean measurable = stock.size() >= 2 && replacement >= 0;
        long nextGap = measurable ? stock.get(1) - replacement : 0;
        boolean capConflict = measurable && nextGap < 50_000_000L;
        boolean nextIsContact = measurable && normal.consumes.stream().anyMatch(p ->
                (long)p.get(3) == stock.get(1) && (boolean)p.get(2));
        var contactChange = firstDifference(trackedContacts(normal,end),trackedContacts(late,end));
        var pointChange = firstDifference(normal.hits.stream().takeWhile(h -> h.at() <= end).toList(),
                late.hits.stream().takeWhile(h -> h.at() <= end).toList());
        return Map.of("stock_next_presses_ns",stock,"retimed_press_ns",replacement,"next_gap_ns",nextGap,
                "cap_conflict",capConflict,"next_is_contact",nextIsContact,"contact_change",contactChange,
                "point_change",pointChange.stream().map(h -> List.of(h.at(),h.sideA(),h.upgrade())).toList());
    }

    public static void main(String[] args) throws Exception {
        int rtt = Integer.parseInt(args[0]);
        int ownPhase = Integer.parseInt(args[1]), opponentPhase = Integer.parseInt(args[2]);
        double impulseLimit = Double.parseDouble(System.getProperty("lab.approach_reply_horizontal_limit",".5275"));
        if (!Double.isFinite(impulseLimit) || impulseLimit<=0) throw new IllegalArgumentException("Positive impulse limit required");
        for (int i=5;i<args.length;i++) {
            var env = BoxingEdgeHitSelect.environment(Path.of(args[i]),rtt,180_000,Path.of(args[3]),Path.of(args[4]));
            for (boolean first : new boolean[]{true,false}) {
                var normal = regulation(env,first,BoxingApproachPoll.Mode.OBSERVE,ownPhase,opponentPhase,false);
                var late = regulation(env,first,BoxingApproachPoll.Mode.INPUT_TICK,ownPhase,opponentPhase,false);
                double end = Math.min(normal.end,late.end);
                double start = List.of(normal,late).stream().flatMap(r -> r.inputs.stream())
                        .mapToDouble(p -> p.isEmpty()?end:p.get(0)/1e6).max().orElseThrow();
                double firstWait = late.waits.isEmpty()?Double.POSITIVE_INFINITY:late.waits.get(0);
                var prefix = normal.hits.stream().takeWhile(h -> h.at()<firstWait).toList();
                if (!prefix.equals(late.hits.stream().takeWhile(h -> h.at()<firstWait).toList()))
                    throw new IllegalStateException("Hit sequence changed before the first deferral");
                var result = new java.util.LinkedHashMap<>(Map.of("clock",env.clockId,"seat",first?"a":"b","rtt",rtt,
                        "phase",List.of(ownPhase,opponentPhase),"wtap",normal.summarize(start,end,firstWait),
                        "input_tick",late.summarize(start,end,firstWait)));
                if (Double.isFinite(firstWait)) result.put("admission_diagnosis",admissionDiagnosis(normal,late,firstWait,end));
                if (Boolean.getBoolean("lab.approach_guarded_hold")) {
                    var guarded = regulation(env,first,BoxingApproachPoll.Mode.INPUT_TICK,ownPhase,opponentPhase,true);
                    double sameEnd = Math.min(guarded.end,late.end);
                    double sameStart = List.of(guarded,late).stream().flatMap(r -> r.inputs.stream())
                            .mapToDouble(p -> p.isEmpty()?sameEnd:p.get(0)/1e6).max().orElseThrow();
                    double firstHold = guarded.held.isEmpty()?Double.POSITIVE_INFINITY:guarded.held.get(0);
                    if (!guarded.hits.stream().takeWhile(h -> h.at()<firstHold).toList()
                            .equals(late.hits.stream().takeWhile(h -> h.at()<firstHold).toList()))
                        throw new IllegalStateException("Hit sequence changed before the first hold");
                    result.put("guarded_hold",guarded.summarize(sameStart,sameEnd,firstHold));
                    result.put("scheduled_wtap",late.summarize(sameStart,sameEnd,firstHold));
                    result.put("guarded_hold_times",guarded.held);
                    result.put("guard_preserved_times",guarded.protectedContacts);
                    if (Boolean.getBoolean("lab.approach_hold_forensics") && Double.isFinite(firstHold)) {
                        Window window = new Window(firstHold-100,firstHold+2000);
                        var holdTrace = regulation(env,first,BoxingApproachPoll.Mode.INPUT_TICK,ownPhase,opponentPhase,
                                new Wait(35,false),window);
                        var stockTrace = regulation(env,first,BoxingApproachPoll.Mode.INPUT_TICK,ownPhase,opponentPhase,null,window);
                        if (!holdTrace.hits.equals(guarded.hits) || !stockTrace.hits.equals(late.hits)
                                || !holdTrace.inputs.equals(guarded.inputs) || !stockTrace.inputs.equals(late.inputs))
                            throw new IllegalStateException("Tracing changed inputs or score sequence");
                        result.put("first_hold_forensics",Map.of("anchor",firstHold,"wtap",stockTrace.timeline,
                                "wait",holdTrace.timeline));
                    }
                    if (Boolean.getBoolean("lab.approach_hold_sweep") && Double.isFinite(firstHold)) {
                        var window = new Window(firstHold-100,firstHold+2000);
                        var stockTrace = regulation(env,first,BoxingApproachPoll.Mode.INPUT_TICK,ownPhase,opponentPhase,null,window);
                        var variants = new java.util.LinkedHashMap<String,Object>();
                        variants.put("0",Map.of("score",stockTrace.score,"timeline",stockTrace.timeline));
                        var stockPrefix = stockTrace.hits.stream().takeWhile(h -> h.at()<firstHold).toList();
                        for (int duration : new int[]{20,35,55,75,95}) {
                            var variant = regulation(env,first,BoxingApproachPoll.Mode.INPUT_TICK,ownPhase,opponentPhase,
                                    new Wait(duration,true),window);
                            if (!variant.held.equals(List.of(firstHold)) || !stockPrefix.equals(
                                    variant.hits.stream().takeWhile(h -> h.at()<firstHold).toList()) ||
                                    !variant.timeline.stream().takeWhile(r -> (double)r.get(0)<firstHold).toList().equals(
                                            stockTrace.timeline.stream().takeWhile(r -> (double)r.get(0)<firstHold).toList()))
                                throw new IllegalStateException("First-hold test moved the matched opening");
                            variants.put(Integer.toString(duration),Map.of("score",variant.score,"timeline",variant.timeline,
                                    "issued",variant.selected));
                        }
                        result.put("single_hold_sweep",Map.of("anchor",firstHold,"arms",variants));
                    }
                    if (Boolean.getBoolean("lab.approach_reply_impulse") && Double.isFinite(firstHold)) {
                        var window = new Window(firstHold-100,firstHold+2000);
                        var stock = regulation(env,first,BoxingApproachPoll.Mode.INPUT_TICK,ownPhase,opponentPhase,null,window);
                        var usual = regulation(env,first,BoxingApproachPoll.Mode.INPUT_TICK,ownPhase,opponentPhase,
                                new Wait(35,true),window);
                        var weak = regulation(env,first,BoxingApproachPoll.Mode.INPUT_TICK,ownPhase,opponentPhase,
                                new Wait(35,true,impulseLimit),window);
                        var shot = usual.hits.stream().filter(h -> h.at()>=firstHold && h.sideA()==first && !h.upgrade())
                                .findFirst().orElse(null);
                        if (shot!=null && !usual.hits.stream().takeWhile(h -> h.at()<=shot.at()).toList().equals(
                                weak.hits.stream().takeWhile(h -> h.at()<=shot.at()).toList()))
                            throw new IllegalStateException("Changing the reply impulse altered its accepted opening");
                        result.put("reply_impulse_probe",Map.of("anchor",firstHold,"shot",shot==null?-1.:shot.at(),
                                "wtap",stock.timeline,"wait",usual.timeline,"weaker",weak.timeline));
                    }
                }
                System.out.println(Json.encode(result));
            }
        }
    }
}
