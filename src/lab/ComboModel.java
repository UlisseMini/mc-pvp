package lab;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Provisional, three-coordinate forward model for a W-only attacker and a
 * non-clicking defender that holds W + Ctrl and aims at its local opponent.
 *
 * This is a planning approximation, NOT a replacement for vanilla evaluation.
 * Its inputs must be estimates obtained from observations at least 150 ms old,
 * known policy parameters, and our own input history. In particular b, serverA,
 * serverB, bSeesA and server sprint are latent estimates, never live server or
 * opponent-client reads. No game objects, packets or reflection enter this file.
 *
 * Coordinates: x/z are horizontal world coordinates; y is feet height above
 * the flat floor. Velocities are blocks per native 50 ms tick, not blocks/second.
 * State.nowMs is immediately before an attacker client tick. A state estimated
 * at a delayed observation can be advanced with the already issued actions to
 * obtain a present prediction. choose() returns the next normal W/Ctrl action.
 *
 * Important omissions: A/D, incoming attacks, criticals, exact body collisions,
 * exact packet/tick phases and rendering
 * float rounding. Position replication is modeled with 2-tick server tracking,
 * 1/32-block quantization, network queues and 3-tick client interpolation.
 */
public final class ComboModel {
    private ComboModel() {}
    private static final long TICK_MS = 50;

    public enum Action {
        RELEASE(false, false), WALK(true, false), SPRINT(true, true);
        public final boolean forward, sprintKey;
        Action(boolean forward, boolean sprintKey) {
            this.forward = forward;
            this.sprintKey = sprintKey;
        }
    }

    public static final class Config {
        public double ownRttMs = 20, opponentRttMs = 100;
        /** Phases relative to the attacker tick, each in [0,49] ms. */
        public int serverPhaseMs = 25, opponentPhaseMs = 10, trackerPhase = 0;
        public int horizonTicks = 26, beamWidth = 24, minimumActionTicks = 2;
        public int clickIntervalMs = 100, freshHitIntervalMs = 500;
        /** The native probe waits for its initial delayed observation before W. */
        public long defenderControlAtMs = Long.MIN_VALUE;
        /** Current model uses these calibrated hypotheses, not hidden S12 reads. */
        public double normalImpulseX = .4, normalImpulseY = .3608;
        public double sprintImpulseX = .9, sprintImpulseY = .4608;
        public double hitReward = 1000, opportunityPenalty = 300;
        /** Optional fresh-hit cost for contact inside the 3-block local ray edge. */
        public double contactEdgePenalty = 0;
        public double oneSidedReward = 0, mutualOpportunityPenalty = 0;
        public double penetrationPenalty = 300, safetyMargin = .05;
        public double missPenalty = .4, switchPenalty = .15;
        public double lostPursuitPenalty = 35, terminalGapPenalty = 8;
        public boolean modelDoubleTapSprint = true;
        /** Dynamic native-inspired server motion; fixed impulse values are fallback only. */
        public boolean dynamicServerImpulses = true, modelServerCollisionPush = true;

        public void validate() {
            if (!Double.isFinite(ownRttMs) || !Double.isFinite(opponentRttMs)
                    || ownRttMs < 0 || opponentRttMs < 0)
                throw new IllegalArgumentException("RTT must be finite and nonnegative");
            if (serverPhaseMs < 0 || serverPhaseMs >= TICK_MS
                    || opponentPhaseMs < 0 || opponentPhaseMs >= TICK_MS)
                throw new IllegalArgumentException("Tick phases must be 0..49 ms");
            if (horizonTicks < 1 || horizonTicks > 60 || beamWidth < 1 || beamWidth > 256
                    || minimumActionTicks < 1 || clickIntervalMs < 100
                    || freshHitIntervalMs < 500)
                throw new IllegalArgumentException("Invalid search or click limits");
            if (!Double.isFinite(contactEdgePenalty) || contactEdgePenalty < 0)
                throw new IllegalArgumentException("Contact-edge penalty must be finite and nonnegative");
        }
    }

    public static final class Body {
        public double x, y, z, vx, vy, vz;
        public boolean ground = true, sprint;
        /** Vanilla updates these after travel; retain them across sprint changes. */
        public double groundAcceleration = .1, airAcceleration = .02;
        public Body(double x, double y, double vx, double vy, boolean ground, boolean sprint) {
            this(x, y, 0, vx, vy, 0, ground, sprint);
        }
        public Body(double x, double y, double z, double vx, double vy, double vz,
                    boolean ground, boolean sprint) {
            this.x = x; this.y = y; this.z = z;
            this.vx = vx; this.vy = vy; this.vz = vz;
            this.ground = ground; this.sprint = sprint;
            groundAcceleration = sprint ? .13 : .1;
            airAcceleration = sprint ? .026 : .02;
        }
        public Body copy() {
            Body result = new Body(x, y, z, vx, vy, vz, ground, sprint);
            result.groundAcceleration = groundAcceleration;
            result.airAcceleration = airAcceleration;
            return result;
        }
    }

    public static final class View {
        public double x, y, z, goalX, goalY, goalZ;
        /** Horizontal look direction most recently processed for this server entity. */
        public double lookX = 1, lookZ;
        public int interpolationTicks;
        public View(double x, double y) {
            this(x, y, 0);
        }
        public View(double x, double y, double z) {
            this.x = goalX = x; this.y = goalY = y; this.z = goalZ = z;
        }
        public View copy() {
            View result = new View(x, y, z);
            result.goalX = goalX; result.goalY = goalY; result.goalZ = goalZ;
            result.lookX = lookX; result.lookZ = lookZ;
            result.interpolationTicks = interpolationTicks;
            return result;
        }
        void receive(double x, double y) {
            receive(x, y, 0);
        }
        void receive(double x, double y, double z) {
            goalX = x; goalY = y; goalZ = z; interpolationTicks = 3;
        }
        void step() {
            if (interpolationTicks > 0) {
                x += (goalX - x) / interpolationTicks;
                y += (goalY - y) / interpolationTicks;
                z += (goalZ - z) / interpolationTicks;
                interpolationTicks--;
            }
        }
    }

    private enum PacketKind { A_MOVE, B_MOVE, A_SPRINT, B_SPRINT, ATTACK, CONFIRMED_HIT,
        A_VIEW, B_VIEW, B_VELOCITY, A_HURT_CUE }

    /** Immutable predicted packet; queue ownership is confined to one State. */
    private static final class Packet {
        final PacketKind kind;
        final double dueMs, x, y, z, lookX, lookZ;
        final boolean flag;
        final long sequence, causeMs;
        Packet(PacketKind kind, double dueMs, double x, double y, boolean flag, long sequence) {
            this(kind, dueMs, x, y, 0, flag, sequence, Double.NaN, Double.NaN, Long.MIN_VALUE);
        }
        Packet(PacketKind kind, double dueMs, double x, double y, double z,
               boolean flag, long sequence, double lookX, double lookZ, long causeMs) {
            this.kind = kind; this.dueMs = dueMs; this.x = x; this.y = y;
            this.z = z; this.lookX = lookX; this.lookZ = lookZ; this.causeMs = causeMs;
            this.flag = flag; this.sequence = sequence;
        }
    }

    public static final class State {
        public long nowMs, nextPhysicalClickMs, targetVulnerableAtMs;
        public long lastPredictedHurtCueMs = Long.MIN_VALUE;
        public Body a, b;
        /** Latent server motion, evolved from our predicted C03 stream, never read live. */
        public Body serverMotionA, serverMotionB;
        public View aSeesB, bSeesA;
        /** Last movement positions processed by the modeled server. */
        public View serverA, serverB;
        public boolean aServerSprint, bServerSprint;
        public boolean aLastSentSprint, bLastSentSprint, previousForward;
        public int doubleTapTicks;
        public int serverTicks, actionTicks;
        public Action previousAction = Action.RELEASE;
        public int acceptedHits, defenderOpportunityTicks, physicalClicks, airMisses;
        public int attackerOpportunityTicks, oneSidedOpportunityTicks, mutualOpportunityTicks;
        public double minimumDefenderRay = Double.POSITIVE_INFINITY;
        public double lastOurRay = Double.POSITIVE_INFINITY;
        public double lastDefenderRay = Double.POSITIVE_INFINITY;
        public long firstDefenderOpportunityMs = Long.MIN_VALUE;
        public double accumulatedReward;
        public long lastAssimilatedCueMs = Long.MIN_VALUE;
        public long lastPredictedHitMs = Long.MIN_VALUE, lastServerSprintCommandMs = Long.MIN_VALUE;
        public double lastPredictedImpulseX, lastPredictedImpulseY, lastPredictedImpulseZ;
        public boolean lastPredictedHitSprint;
        private double trackedAX, trackedAY, trackedAZ, trackedBX, trackedBY, trackedBZ;
        private long nextPacketSequence;
        private final ArrayList<Packet> packets = new ArrayList<>();
        // Keep consumed entries as well: later ordinary attacks must not recreate
        // a confirmed event, and repeated historical replay must be idempotent.
        private final ArrayList<Long> confirmedHitTimes = new ArrayList<>();

        /**
         * Exact required initial estimates are deliberately explicit. Views are
         * NOT interchangeable with actual body positions. Pending packets and
         * fresh-hit phase must be reconstructed by advancing known history or
         * supplied as hypotheses using schedule* methods below.
         */
        public State(long nowMs, Body attacker, Body defender, View attackerView,
                     View defenderView, View serverAttacker, View serverDefender) {
            this.nowMs = nowMs; nextPhysicalClickMs = nowMs;
            a = attacker.copy(); b = defender.copy();
            aSeesB = attackerView.copy(); bSeesA = defenderView.copy();
            serverA = serverAttacker.copy(); serverB = serverDefender.copy();
            serverMotionA = new Body(serverA.x, serverA.y, serverA.z, 0, -.0784, 0, a.ground, a.sprint);
            serverMotionB = new Body(serverB.x, serverB.y, serverB.z, 0, -.0784, 0, b.ground, b.sprint);
            setLook(serverA, aSeesB.x - a.x, aSeesB.z - a.z);
            setLook(serverB, bSeesA.x - b.x, bSeesA.z - b.z);
            aServerSprint = aLastSentSprint = a.sprint;
            bServerSprint = bLastSentSprint = b.sprint;
            previousForward = a.sprint;
            previousAction = a.sprint ? Action.SPRINT : Action.RELEASE;
            trackedAX = quantize(serverA.x); trackedAY = quantize(serverA.y);
            trackedBX = quantize(serverB.x); trackedBY = quantize(serverB.y);
            trackedAZ = quantize(serverA.z); trackedBZ = quantize(serverB.z);
        }

        public State copy() {
            State result = new State(nowMs, a, b, aSeesB, bSeesA, serverA, serverB);
            result.nextPhysicalClickMs = nextPhysicalClickMs;
            result.targetVulnerableAtMs = targetVulnerableAtMs;
            result.lastPredictedHurtCueMs = lastPredictedHurtCueMs;
            result.aServerSprint = aServerSprint; result.bServerSprint = bServerSprint;
            result.aLastSentSprint = aLastSentSprint; result.bLastSentSprint = bLastSentSprint;
            result.previousForward = previousForward; result.doubleTapTicks = doubleTapTicks;
            result.serverTicks = serverTicks; result.actionTicks = actionTicks;
            result.previousAction = previousAction;
            result.acceptedHits = acceptedHits;
            result.defenderOpportunityTicks = defenderOpportunityTicks;
            result.attackerOpportunityTicks = attackerOpportunityTicks;
            result.oneSidedOpportunityTicks = oneSidedOpportunityTicks;
            result.mutualOpportunityTicks = mutualOpportunityTicks;
            result.physicalClicks = physicalClicks; result.airMisses = airMisses;
            result.minimumDefenderRay = minimumDefenderRay;
            result.lastOurRay = lastOurRay; result.lastDefenderRay = lastDefenderRay;
            result.firstDefenderOpportunityMs = firstDefenderOpportunityMs;
            result.accumulatedReward = accumulatedReward;
            result.serverMotionA = serverMotionA.copy(); result.serverMotionB = serverMotionB.copy();
            result.serverA.lookX = serverA.lookX; result.serverA.lookZ = serverA.lookZ;
            result.serverB.lookX = serverB.lookX; result.serverB.lookZ = serverB.lookZ;
            result.lastAssimilatedCueMs = lastAssimilatedCueMs;
            result.lastPredictedHitMs = lastPredictedHitMs;
            result.lastServerSprintCommandMs = lastServerSprintCommandMs;
            result.lastPredictedImpulseX = lastPredictedImpulseX;
            result.lastPredictedImpulseY = lastPredictedImpulseY;
            result.lastPredictedImpulseZ = lastPredictedImpulseZ;
            result.lastPredictedHitSprint = lastPredictedHitSprint;
            result.trackedAX = trackedAX; result.trackedAY = trackedAY;
            result.trackedBX = trackedBX; result.trackedBY = trackedBY;
            result.trackedAZ = trackedAZ; result.trackedBZ = trackedBZ;
            result.nextPacketSequence = nextPacketSequence;
            result.packets.addAll(packets); // Packet is immutable.
            result.confirmedHitTimes.addAll(confirmedHitTimes);
            return result;
        }

        /**
         * Schedule one historical hit proven by an eligible delayed local hurt
         * cue. serverTimeMs is a particle's inferred hit time, never a referee
         * timestamp. The event executes on the first modeled server tick at or
         * after that time and computes knockback from THAT reconstructed server
         * state and sprint flag. It bypasses modeled reach/immunity for this one
         * proven event; ordinary physical clicks still occur normally.
         *
         * Rewind before serverTimeMs-100 before adding a new confirmation so no
         * conflicting predicted impulse has already been integrated. Predicted
         * attacks processed within +/-100 ms are suppressed on both sides of the
         * confirmation, including after it has been consumed. This bounded
         * suppression resolves duplicate predictions, not all possible bad hit
         * phases. Callers must not also apply assimilateFreshHurt to the same cue.
         *
         * Returns false if this exact inferred time was already scheduled or
         * consumed in this State. The confirmation ledger is copied with State.
         * Historical events have no known exact local contact ray and therefore
         * receive no contactEdgePenalty; that cost applies to predicted attacks.
         */
        public boolean scheduleConfirmedHit(long serverTimeMs, Config config) {
            config.validate();
            if (confirmedHitTimes.contains(serverTimeMs)) return false;
            if (serverTimeMs < nowMs)
                throw new IllegalArgumentException("Rewind before scheduling a past confirmed hit");
            if (lastPredictedHitMs != Long.MIN_VALUE
                    && Math.abs((double) lastPredictedHitMs - serverTimeMs) <= 100)
                throw new IllegalStateException("Rewind before confirmed hit minus 100 ms to undo a conflicting prediction");
            confirmedHitTimes.add(serverTimeMs);
            enqueue(this, PacketKind.CONFIRMED_HIT, serverTimeMs, 0, 0, false);
            return true;
        }

        /** Add only a model hypothesis inferred from delayed legal observations. */
        public void scheduleDefenderImpulse(double arrivalMs, double vx, double vy) {
            scheduleDefenderImpulse(arrivalMs, vx, vy, 0);
        }
        public void scheduleDefenderImpulse(double arrivalMs, double vx, double vy, double vz) {
            enqueue3(this, PacketKind.B_VELOCITY, arrivalMs, vx, vy, vz, false);
        }
        public void scheduleAttackerView(double arrivalMs, double targetX, double targetY) {
            scheduleAttackerView(arrivalMs, targetX, targetY, 0);
        }
        public void scheduleAttackerView(double arrivalMs, double targetX, double targetY, double targetZ) {
            enqueue3(this, PacketKind.A_VIEW, arrivalMs, targetX, targetY, targetZ, false);
        }
        public void scheduleDefenderView(double arrivalMs, double targetX, double targetY) {
            scheduleDefenderView(arrivalMs, targetX, targetY, 0);
        }
        public void scheduleDefenderView(double arrivalMs, double targetX, double targetY, double targetZ) {
            enqueue3(this, PacketKind.B_VIEW, arrivalMs, targetX, targetY, targetZ, false);
        }
        public int pendingPacketCount() { return packets.size(); }

        /**
         * Apply an eligible delayed observation to a snapshot at that SAME time,
         * then replay already issued inputs with advanceRecorded. The observed
         * local sprint flag does not reveal server sprint or pending C0B packets.
         * Acceleration from the previous living update is approximated from the
         * observed flag; a caller with an eligible recorded value may override it.
         */
        public void correctOwn(double observedX, double observedY,
                               double observedVx, double observedVy,
                               boolean observedGround, boolean observedSprint) {
            correctOwn(observedX, observedY, a.z, observedVx, observedVy, a.vz,
                    observedGround, observedSprint);
        }
        public void correctOwn(double observedX, double observedY, double observedZ,
                               double observedVx, double observedVy, double observedVz,
                               boolean observedGround, boolean observedSprint) {
            requireFinite(observedX, observedY, observedZ, observedVx, observedVy, observedVz);
            if (observedY < 0) throw new IllegalArgumentException("Feet height is relative to floor");
            translateOwnTrace(observedX - a.x, observedY - a.y, observedZ - a.z);
            a.vx = observedVx; a.vy = observedVy; a.vz = observedVz;
            a.ground = observedGround; a.sprint = observedSprint;
            a.groundAcceleration = observedSprint ? .13 : .1;
            a.airAcceleration = observedSprint ? .026 : .02;
        }

        /**
         * Correct the predicted local rendering of the defender, then translate
         * that defender's latent trajectory by the same residual. This assumes a
         * constant position bias: it is not an exact velocity/phase estimator.
         * It must never be given an opponent-client or referee position.
         */
        public void correctObservedDefender(double observedRemoteX, double observedRemoteY) {
            correctObservedDefender(observedRemoteX, observedRemoteY, aSeesB.z);
        }
        public void correctObservedDefender(double observedRemoteX, double observedRemoteY,
                                            double observedRemoteZ) {
            requireFinite(observedRemoteX, observedRemoteY, observedRemoteZ);
            if (observedRemoteY < 0) throw new IllegalArgumentException("Feet height is relative to floor");
            translateDefenderTrace(observedRemoteX - aSeesB.x, observedRemoteY - aSeesB.y,
                    observedRemoteZ - aSeesB.z);
        }

        /**
         * Translate all estimated position history belonging to us. Our view of
         * the OTHER entity is unchanged. Vertical correction is approximate:
         * positions are clamped at the fixed floor, which breaks invariance.
         * Velocity/sprint/attack/impulse packets retain their original meaning.
         */
        public void translateOwnTrace(double dx, double dy) {
            translateOwnTrace(dx, dy, 0);
        }
        public void translateOwnTrace(double dx, double dy, double dz) {
            requireFinite(dx, dy, dz);
            translateBody(a, dx, dy, dz); translateBody(serverMotionA, dx, dy, dz);
            translateView(serverA, dx, dy, dz); translateView(bSeesA, dx, dy, dz);
            trackedAX += dx; trackedAY = Math.max(0, trackedAY + dy);
            trackedAZ += dz;
            translatePositionPackets(true, dx, dy, dz);
        }

        /** Same constant-bias approximation for the defender and its position history. */
        public void translateDefenderTrace(double dx, double dy) {
            translateDefenderTrace(dx, dy, 0);
        }
        public void translateDefenderTrace(double dx, double dy, double dz) {
            requireFinite(dx, dy, dz);
            translateBody(b, dx, dy, dz); translateBody(serverMotionB, dx, dy, dz);
            translateView(serverB, dx, dy, dz); translateView(aSeesB, dx, dy, dz);
            trackedBX += dx; trackedBY = Math.max(0, trackedBY + dy);
            trackedBZ += dz;
            translatePositionPackets(false, dx, dy, dz);
        }

        private void translatePositionPackets(boolean ours, double dx, double dy, double dz) {
            for (int i = 0; i < packets.size(); i++) {
                Packet p = packets.get(i);
                boolean positionOfEntity = ours
                        ? p.kind == PacketKind.A_MOVE || p.kind == PacketKind.B_VIEW
                        : p.kind == PacketKind.B_MOVE || p.kind == PacketKind.A_VIEW;
                if (positionOfEntity) packets.set(i, new Packet(p.kind, p.dueMs,
                        p.x + dx, Math.max(0, p.y + dy), p.z + dz, p.flag, p.sequence,
                        p.lookX, p.lookZ, p.causeMs));
            }
        }

        /** Align future predicted clicks with our own known physical input clock. */
        public void alignPhysicalClickClock(long lastActualInjectionMs, Config config) {
            nextPhysicalClickMs = lastActualInjectionMs + config.clickIntervalMs;
        }

        /**
         * Assimilate a NEW eligible local fresh-hurt animation cue at a delayed
         * snapshot. Never pass a referee hit time. A cue is observed after the
         * server-to-us half RTT plus client tick phase, so timing remains ±1 tick.
         * Removes contradictory queued predicted impulses/cues. If an erroneous
         * impulse was already integrated, earlier replay is required: position
         * translation cannot undo the wrong velocity history.
         */
        public CueCorrection assimilateFreshHurt(long localCueMs, Config config) {
            boolean matched = lastPredictedHitMs != Long.MIN_VALUE
                    && Math.abs(lastPredictedHitMs - (localCueMs - config.ownRttMs / 2)) <= 100;
            return assimilateFreshHurt(localCueMs,
                    matched ? lastPredictedHitSprint : aServerSprint, config);
        }
        public CueCorrection assimilateFreshHurt(long localCueMs, boolean estimatedSprintAtHit,
                                                 Config config) {
            if (localCueMs > nowMs) throw new IllegalArgumentException("Cue is newer than snapshot");
            long hitMs = Math.round(localCueMs - config.ownRttMs / 2);
            double arrivalMs = hitMs + config.opponentRttMs / 2;
            if (localCueMs <= lastAssimilatedCueMs)
                return new CueCorrection(hitMs, arrivalMs, true, false, false);
            lastAssimilatedCueMs = localCueMs;
            lastPredictedHurtCueMs = localCueMs;
            targetVulnerableAtMs = hitMs + config.freshHitIntervalMs;
            boolean matched = lastPredictedHitMs != Long.MIN_VALUE
                    && Math.abs(lastPredictedHitMs - hitMs) <= 100;
            double[] impulse = matched
                    ? new double[]{lastPredictedImpulseX, lastPredictedImpulseY, lastPredictedImpulseZ}
                    : knockback(this, estimatedSprintAtHit, config);
            boolean pendingMatched = false, removedContradiction = false;
            for (int i = packets.size() - 1; i >= 0; i--) {
                Packet p = packets.get(i);
                if ((p.kind == PacketKind.B_VELOCITY || p.kind == PacketKind.A_HURT_CUE)
                        && p.causeMs != Long.MIN_VALUE && p.causeMs >= hitMs - 150) {
                    if (p.kind == PacketKind.B_VELOCITY && Math.abs(p.causeMs - hitMs) <= 100)
                        pendingMatched = true;
                    if (Math.abs(p.causeMs - hitMs) > 100) removedContradiction = true;
                    packets.remove(i);
                }
            }
            // A future victim delivery can be reconciled without injecting any
            // private victim data. An already integrated impulse needs rewind.
            if (arrivalMs > nowMs) enqueueHit(this, PacketKind.B_VELOCITY, arrivalMs,
                    impulse[0], impulse[1], impulse[2], hitMs);
            if (estimatedSprintAtHit && lastServerSprintCommandMs <= hitMs) aServerSprint = false;
            boolean rewind = !matched || removedContradiction
                    || (arrivalMs > nowMs && !pendingMatched)
                    || (!pendingMatched && matched && Math.abs(lastPredictedHitMs - hitMs) >= 50);
            lastPredictedHitMs = hitMs;
            lastPredictedHitSprint = estimatedSprintAtHit;
            lastPredictedImpulseX = impulse[0]; lastPredictedImpulseY = impulse[1];
            lastPredictedImpulseZ = impulse[2];
            return new CueCorrection(hitMs, arrivalMs, matched, rewind, true);
        }
    }

    public static final class CueCorrection {
        public final long estimatedServerHitMs;
        public final double estimatedVictimArrivalMs;
        public final boolean matchedPrediction, needsEarlierReplay, applied;
        CueCorrection(long hitMs, double arrivalMs, boolean matched, boolean rewind, boolean applied) {
            estimatedServerHitMs = hitMs; estimatedVictimArrivalMs = arrivalMs;
            matchedPrediction = matched; needsEarlierReplay = rewind; this.applied = applied;
        }
    }

    public static final class Decision {
        public final Action action;
        public final List<Action> plan;
        public final double score, minimumDefenderRay;
        public final int predictedHits, predictedDefenderOpportunityTicks;
        public final long firstDefenderOpportunityMs;
        public final State predictedEnd;
        private Decision(Node best, State initial, Config config) {
            action = best.plan[0];
            plan = List.of(best.plan.clone());
            score = rank(best, config) - initial.accumulatedReward;
            predictedHits = best.state.acceptedHits - initial.acceptedHits;
            predictedDefenderOpportunityTicks = best.state.defenderOpportunityTicks
                    - initial.defenderOpportunityTicks;
            minimumDefenderRay = best.state.minimumDefenderRay;
            firstDefenderOpportunityMs = best.state.firstDefenderOpportunityMs;
            predictedEnd = best.state.copy();
        }
    }

    private static final class Node {
        final State state;
        final Action[] plan;
        Node(State state, Action[] plan) { this.state = state; this.plan = plan; }
    }

    /** Beam-search approximation, not an optimal-policy claim. Does not mutate input. */
    public static Decision choose(State initial, Config config) {
        config.validate();
        State seed = initial.copy();
        seed.minimumDefenderRay = Double.POSITIVE_INFINITY;
        seed.firstDefenderOpportunityMs = Long.MIN_VALUE;
        ArrayList<Node> beam = new ArrayList<>();
        beam.add(new Node(seed, new Action[0]));
        Comparator<Node> order = Comparator.comparingDouble((Node n) -> rank(n, config)).reversed();
        for (int depth = 0; depth < config.horizonTicks; depth++) {
            ArrayList<Node> next = new ArrayList<>(beam.size() * 3);
            for (Node node : beam) {
                for (Action action : Action.values()) {
                    if (node.state.actionTicks > 0
                            && node.state.actionTicks < config.minimumActionTicks
                            && action != node.state.previousAction) continue;
                    State state = node.state.copy();
                    step(state, action, config);
                    Action[] plan = new Action[depth + 1];
                    System.arraycopy(node.plan, 0, plan, 0, depth);
                    plan[depth] = action;
                    next.add(new Node(state, plan));
                }
            }
            next.sort(order);
            beam = new ArrayList<>(next.subList(0, Math.min(config.beamWidth, next.size())));
        }
        beam.sort(order);
        return new Decision(beam.get(0), initial, config);
    }

    /**
     * Advance one attacker tick with a known W/Ctrl action. Use this to replay
     * already committed input between an old observation and the present.
     * The returned state is independent of the supplied state.
     */
    public static State advance(State state, Action action, Config config) {
        config.validate();
        State result = state.copy();
        step(result, action, config);
        return result;
    }

    public static State advance(State state, List<Action> knownActions, Config config) {
        config.validate();
        State result = state.copy();
        for (Action action : knownActions) step(result, action, config);
        return result;
    }

    /**
     * Replay one known client tick with its actual physical click consumption.
     * false means no click was consumed; true means one short press/release was
     * consumed. The caller maps its own injected clicks to native client ticks.
     * This is input history, not an engine-hit/result oracle. After replay, align
     * future prediction with alignPhysicalClickClock(lastActualInjectionMs,...).
     */
    public static State advanceRecorded(State state, Action action,
                                        boolean physicalClickConsumed, Config config) {
        config.validate();
        State result = state.copy();
        step(result, action, config, physicalClickConsumed);
        return result;
    }

    private static void step(State s, Action action, Config c) {
        step(s, action, c, null);
    }

    private static void step(State s, Action action, Config c, Boolean recordedClick) {
        if (action != s.previousAction) {
            s.accumulatedReward -= c.switchPenalty;
            s.actionTicks = 0;
        }
        s.previousAction = action; s.actionTicks++;
        attackerTick(s, action, c, s.nowMs, recordedClick);
        long serverTime = s.nowMs + c.serverPhaseMs;
        long defenderTime = s.nowMs + c.opponentPhaseMs;
        if (serverTime <= defenderTime) {
            serverTick(s, c, serverTime);
            defenderTick(s, c, defenderTime);
        } else {
            defenderTick(s, c, defenderTime);
            serverTick(s, c, serverTime);
        }
        s.nowMs += TICK_MS;
    }

    private static void attackerTick(State s, Action action, Config c, long time,
                                     Boolean recordedClick) {
        receiveClient(s, true, time);
        s.lastOurRay = rayDistance(s.a.x, s.a.y, s.a.z, s.aSeesB.x, s.aSeesB.y, s.aSeesB.z);
        double[] look = horizontalUnit(s.aSeesB.x - s.a.x, s.aSeesB.z - s.a.z);
        // Independent short press/releases: native end-of-tick key release clears
        // leftClickCounter. Do not invent a persistent 500 ms air-miss cooldown.
        if (recordedClick != null ? recordedClick : time >= s.nextPhysicalClickMs) {
            s.physicalClicks++;
            // Keep the independent physical clock between native ticks. For
            // example 101 ms clicks must not become a permanent 150 ms cadence.
            long injectionTime = recordedClick != null ? time
                    : Math.max(s.nextPhysicalClickMs, time - TICK_MS + 1);
            s.nextPhysicalClickMs = injectionTime + c.clickIntervalMs;
            if (s.lastOurRay <= 3) {
                // Preserve the local contact distance through the network queue;
                // the server position at processing time is a different quantity.
                enqueue(s, PacketKind.ATTACK, time + c.ownRttMs / 2, s.lastOurRay, 0, false);
                if (s.a.sprint) {
                    s.a.vx *= .6;
                    s.a.vz *= .6;
                    s.a.sprint = false;
                }
            } else {
                s.airMisses++;
                s.accumulatedReward -= c.missPenalty;
            }
        }
        // Attack processing precedes the living update and sprint key handling.
        if (s.doubleTapTicks > 0) s.doubleTapTicks--;
        if (c.modelDoubleTapSprint && s.a.ground && !s.previousForward
                && action.forward && !s.a.sprint) {
            if (s.doubleTapTicks > 0 || action.sprintKey) s.a.sprint = true;
            else s.doubleTapTicks = 7;
        }
        if (action.forward && action.sprintKey) s.a.sprint = true;
        if (!action.forward) s.a.sprint = false;
        s.previousForward = action.forward;
        move(s.a, action.forward ? look[0] : 0, action.forward ? look[1] : 0);
        s.aSeesB.step();
        if (s.a.sprint != s.aLastSentSprint) {
            enqueue(s, PacketKind.A_SPRINT, time + c.ownRttMs / 2, 0, 0, s.a.sprint);
            s.aLastSentSprint = s.a.sprint;
        }
        enqueueMove(s, PacketKind.A_MOVE, time + c.ownRttMs / 2, s.a, look);
    }

    private static void defenderTick(State s, Config c, long time) {
        receiveClient(s, false, time);
        s.lastDefenderRay = rayDistance(s.b.x, s.b.y, s.b.z, s.bSeesA.x, s.bSeesA.y, s.bSeesA.z);
        // Paired model opportunities at the same modeled instant. The final
        // metric is always measured from the two real clients' native rays.
        double ourRayNow = rayDistance(s.a.x,s.a.y,s.a.z,s.aSeesB.x,s.aSeesB.y,s.aSeesB.z);
        if (ourRayNow <= 3) {
            s.attackerOpportunityTicks++;
            if (s.lastDefenderRay > 3) {
                s.oneSidedOpportunityTicks++;s.accumulatedReward += c.oneSidedReward;
            } else {
                s.mutualOpportunityTicks++;s.accumulatedReward -= c.mutualOpportunityPenalty;
            }
        }
        double[] look = horizontalUnit(s.bSeesA.x - s.b.x, s.bSeesA.z - s.b.z);
        s.minimumDefenderRay = Math.min(s.minimumDefenderRay, s.lastDefenderRay);
        if (s.lastDefenderRay <= 3) {
            s.defenderOpportunityTicks++;
            if (s.firstDefenderOpportunityMs == Long.MIN_VALUE)
                s.firstDefenderOpportunityMs = time;
            s.accumulatedReward -= c.opportunityPenalty;
        }
        double penetration = Math.max(0, 3 + c.safetyMargin - s.lastDefenderRay);
        s.accumulatedReward -= c.penetrationPenalty * penetration * penetration;
        // This opponent never attacks. It therefore never incurs local attack drag.
        boolean active = time >= c.defenderControlAtMs;
        s.b.sprint = active;
        move(s.b, active ? look[0] : 0, active ? look[1] : 0);
        s.bSeesA.step();
        if (s.b.sprint != s.bLastSentSprint) {
            enqueue(s, PacketKind.B_SPRINT, time + c.opponentRttMs / 2, 0, 0, s.b.sprint);
            s.bLastSentSprint = s.b.sprint;
        }
        enqueueMove(s, PacketKind.B_MOVE, time + c.opponentRttMs / 2, s.b, look);
    }

    /** Native-inspired movement order, including ground friction on takeoff tick. */
    private static void move(Body body, double inputX, double inputZ) {
        if (Math.abs(body.vx) < .005) body.vx = 0;
        if (Math.abs(body.vy) < .005) body.vy = 0;
        if (Math.abs(body.vz) < .005) body.vz = 0;
        double friction = body.ground ? .54600006 : .91;
        double acceleration = body.ground ? body.groundAcceleration : body.airAcceleration;
        body.vx += .98 * acceleration * inputX;
        body.vz += .98 * acceleration * inputZ;
        body.x += body.vx;
        body.z += body.vz;
        double nextY = body.y + body.vy;
        if (nextY <= 0) {
            body.y = 0;
            body.vy = 0;
            body.ground = true;
        } else {
            body.y = nextY;
            body.ground = false;
        }
        body.vx *= friction;
        body.vz *= friction;
        body.vy = (body.vy - .08) * .9800000190734863;
        body.groundAcceleration = body.sprint ? .13 : .1;
        body.airAcceleration = body.sprint ? .026 : .02;
    }

    private static void serverTick(State s, Config c, long time) {
        List<Packet> incoming = takeDue(s, 0, time);
        for (Packet packet : incoming) {
            switch (packet.kind) {
                case A_MOVE -> serverMovement(s, true, packet, c);
                case B_MOVE -> serverMovement(s, false, packet, c);
                case A_SPRINT -> { s.aServerSprint = packet.flag; s.lastServerSprintCommandMs = time; }
                case B_SPRINT -> s.bServerSprint = packet.flag;
                case ATTACK -> {
                    if (nearConfirmedHit(s, time)) break;
                    double dx = s.serverB.x - s.serverA.x, dy = s.serverB.y - s.serverA.y;
                    double dz = s.serverB.z - s.serverA.z;
                    if (dx * dx + dy * dy + dz * dz >= 36 || time < s.targetVulnerableAtMs) break;
                    acceptHit(s, c, time, packet.x);
                }
                case CONFIRMED_HIT -> acceptHit(s, c, time, Double.NaN);
                default -> throw new IllegalStateException("Wrong server packet");
            }
        }
        if ((s.serverTicks & 1) == (c.trackerPhase & 1)) {
            double ax = quantize(s.serverA.x), ay = quantize(s.serverA.y);
            double bx = quantize(s.serverB.x), by = quantize(s.serverB.y);
            double az = quantize(s.serverA.z), bz = quantize(s.serverB.z);
            if (Math.abs(ax - s.trackedAX) >= .125 || Math.abs(ay - s.trackedAY) >= .125
                    || Math.abs(az - s.trackedAZ) >= .125
                    || s.serverTicks % 60 == 0) {
                enqueue3(s, PacketKind.B_VIEW, time + c.opponentRttMs / 2, ax, ay, az, false);
                s.trackedAX = ax; s.trackedAY = ay; s.trackedAZ = az;
            }
            if (Math.abs(bx - s.trackedBX) >= .125 || Math.abs(by - s.trackedBY) >= .125
                    || Math.abs(bz - s.trackedBZ) >= .125
                    || s.serverTicks % 60 == 0) {
                enqueue3(s, PacketKind.A_VIEW, time + c.ownRttMs / 2, bx, by, bz, false);
                s.trackedBX = bx; s.trackedBY = by; s.trackedBZ = bz;
            }
        }
        s.serverTicks++;
    }

    private static boolean nearConfirmedHit(State state, long serverTimeMs) {
        for (long confirmed : state.confirmedHitTimes)
            if (Math.abs((double) serverTimeMs - confirmed) <= 100) return true;
        return false;
    }

    private static void acceptHit(State s, Config c, long time, double localRay) {
        s.acceptedHits++;
        s.accumulatedReward += c.hitReward;
        if (Double.isFinite(localRay)) {
            double penetration = 3 - localRay;
            s.accumulatedReward -= c.contactEdgePenalty * penetration * penetration;
        }
        s.targetVulnerableAtMs = time + c.freshHitIntervalMs;
        boolean sprint = s.aServerSprint;
        double[] impulse = knockback(s, sprint, c);
        s.lastPredictedHitMs = time; s.lastPredictedHitSprint = sprint;
        s.lastPredictedImpulseX = impulse[0]; s.lastPredictedImpulseY = impulse[1];
        s.lastPredictedImpulseZ = impulse[2];
        // S12 overwrites local velocity using latent server motion, not half of
        // the victim's actual local velocity. Replayed confirmations use this
        // same reconstructed event-time state, rather than cue-time motion.
        enqueueHit(s, PacketKind.B_VELOCITY, time + c.opponentRttMs / 2,
                impulse[0], impulse[1], impulse[2], time);
        enqueueHit(s, PacketKind.A_HURT_CUE, time + c.ownRttMs / 2, 0, 0, 0, time);
        if (sprint) {
            s.aServerSprint = false;
            s.serverMotionA.vx *= .6; s.serverMotionA.vz *= .6;
        }
    }

    /**
     * processPlayer runs onUpdateEntity once per processed C03, before resetting
     * the position to the reported movement. Its zero-input server motion is
     * distinct from the client's motion. If that update leaves it grounded and
     * the packet rises into air, vanilla calls jump(), including sprint +0.2.
     */
    private static void serverMovement(State s, boolean ours, Packet packet, Config c) {
        View position = ours ? s.serverA : s.serverB;
        View other = ours ? s.serverB : s.serverA;
        Body motion = ours ? s.serverMotionA : s.serverMotionB;
        Body otherMotion = ours ? s.serverMotionB : s.serverMotionA;
        boolean sprint = ours ? s.aServerSprint : s.bServerSprint;
        motion.x = position.x; motion.y = position.y; motion.z = position.z;
        motion.sprint = sprint;
        move(motion, 0, 0);
        if (c.modelServerCollisionPush) serverCollisionPush(motion, other, otherMotion);
        double lookX = Double.isFinite(packet.lookX) ? packet.lookX : position.lookX;
        double lookZ = Double.isFinite(packet.lookZ) ? packet.lookZ : position.lookZ;
        if (motion.ground && !packet.flag && packet.y > position.y) {
            motion.vy = .41999998688697815;
            if (sprint) {
                motion.vx += .2 * lookX;
                motion.vz += .2 * lookZ;
            }
        }
        position.x = packet.x; position.y = packet.y; position.z = packet.z;
        position.lookX = lookX; position.lookZ = lookZ;
        motion.x = packet.x; motion.y = packet.y; motion.z = packet.z;
        motion.ground = packet.flag;
    }

    /** Approximation of applyEntityCollision, evaluated at the simulated server position. */
    private static void serverCollisionPush(Body self, View other, Body otherMotion) {
        double dx = other.x - self.x, dz = other.z - self.z;
        if (Math.abs(dx) >= .8 || Math.abs(dz) >= .8 || Math.abs(other.y - self.y) >= 1.8) return;
        double largest = Math.max(Math.abs(dx), Math.abs(dz));
        if (largest < .01) return;
        double root = Math.sqrt(largest);
        double scale = .05 * Math.min(1, 1 / root) / root;
        double pushX = dx * scale, pushZ = dz * scale;
        self.vx -= pushX; self.vz -= pushZ;
        otherMotion.vx += pushX; otherMotion.vz += pushZ;
    }

    /** Exposed for offline model validation; accepts estimated state only. */
    public static double[] predictedKnockback(State s, boolean sprint, Config c) {
        return knockback(s, sprint, c);
    }

    private static double[] knockback(State s, boolean sprint, Config c) {
        double[] away = horizontalUnit(s.serverB.x - s.serverA.x, s.serverB.z - s.serverA.z);
        double vx, vy, vz;
        if (c.dynamicServerImpulses) {
            vx = s.serverMotionB.vx * .5 + .4000000059604645 * away[0];
            vy = Math.min(.4000000059604645, s.serverMotionB.vy * .5 + .4000000059604645);
            vz = s.serverMotionB.vz * .5 + .4000000059604645 * away[1];
            if (sprint) {
                // The sprint bonus follows last SERVER yaw, which can oppose
                // the base displacement direction after the players cross.
                vx += .5 * s.serverA.lookX; vy += .1; vz += .5 * s.serverA.lookZ;
            }
        } else {
            vx = c.normalImpulseX * away[0]; vz = c.normalImpulseX * away[1];
            vy = sprint ? c.sprintImpulseY : c.normalImpulseY;
            if (sprint) {
                vx += (c.sprintImpulseX - c.normalImpulseX) * s.serverA.lookX;
                vz += (c.sprintImpulseX - c.normalImpulseX) * s.serverA.lookZ;
            }
        }
        return new double[]{velocityQuantize(vx), velocityQuantize(vy), velocityQuantize(vz)};
    }

    private static void receiveClient(State s, boolean attacker, long time) {
        for (Packet packet : takeDue(s, attacker ? 1 : 2, time)) {
            switch (packet.kind) {
                case A_VIEW -> s.aSeesB.receive(packet.x, packet.y, packet.z);
                case B_VIEW -> s.bSeesA.receive(packet.x, packet.y, packet.z);
                case B_VELOCITY -> { s.b.vx = packet.x; s.b.vy = packet.y; s.b.vz = packet.z; }
                case A_HURT_CUE -> s.lastPredictedHurtCueMs = time;
                default -> throw new IllegalStateException("Wrong client packet");
            }
        }
    }

    private static int destination(PacketKind kind) {
        return switch (kind) {
            case A_VIEW, A_HURT_CUE -> 1;
            case B_VIEW, B_VELOCITY -> 2;
            default -> 0;
        };
    }

    private static List<Packet> takeDue(State s, int destination, double time) {
        ArrayList<Packet> result = new ArrayList<>();
        for (int i = s.packets.size() - 1; i >= 0; i--) {
            Packet packet = s.packets.get(i);
            if (destination(packet.kind) == destination && packet.dueMs <= time) {
                result.add(packet); s.packets.remove(i);
            }
        }
        result.sort(Comparator.comparingDouble((Packet p) -> p.dueMs)
                .thenComparingLong(p -> p.sequence));
        return result;
    }

    private static void enqueue(State s, PacketKind kind, double dueMs,
                                double x, double y, boolean flag) {
        s.packets.add(new Packet(kind, dueMs, x, y, flag, s.nextPacketSequence++));
    }
    private static void enqueue3(State s, PacketKind kind, double dueMs,
                                 double x, double y, double z, boolean flag) {
        s.packets.add(new Packet(kind, dueMs, x, y, z, flag, s.nextPacketSequence++,
                Double.NaN, Double.NaN, Long.MIN_VALUE));
    }
    private static void enqueueMove(State s, PacketKind kind, double dueMs, Body body, double[] look) {
        s.packets.add(new Packet(kind, dueMs, body.x, body.y, body.z, body.ground,
                s.nextPacketSequence++, look[0], look[1], Long.MIN_VALUE));
    }
    private static void enqueueHit(State s, PacketKind kind, double dueMs,
                                   double x, double y, double z, long hitMs) {
        s.packets.add(new Packet(kind, dueMs, x, y, z, false, s.nextPacketSequence++,
                Double.NaN, Double.NaN, hitMs));
    }

    /**
     * Distance along the lab's perfect-aim ray to the expanded player box.
     * Returns infinity for a miss. This is a geometric diagnostic, not a native
     * getMouseOver result; the native probe remains the evaluation authority.
     */
    public static double rayDistance(double eyeX, double feetY,
                                     double targetX, double targetFeetY) {
        return rayDistance(eyeX, feetY, 0, targetX, targetFeetY, 0);
    }
    public static double rayDistance(double eyeX, double feetY, double eyeZ,
                                     double targetX, double targetFeetY, double targetZ) {
        double eyeY = feetY + 1.62;
        double aimY = Math.max(targetFeetY + .15, Math.min(eyeY, targetFeetY + 1.65));
        double dx = targetX - eyeX, dy = aimY - eyeY, dz = targetZ - eyeZ;
        double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (length < 1e-12) return 0;
        dx /= length; dy /= length; dz /= length;
        double near = 0, far = Double.POSITIVE_INFINITY;
        double[] origins = {eyeX, eyeY, eyeZ}, directions = {dx, dy, dz};
        double[] minima = {targetX - .4, targetFeetY - .1, targetZ - .4};
        double[] maxima = {targetX + .4, targetFeetY + 1.9, targetZ + .4};
        for (int axis = 0; axis < 3; axis++) {
            if (Math.abs(directions[axis]) < 1e-12) {
                if (origins[axis] < minima[axis] || origins[axis] > maxima[axis])
                    return Double.POSITIVE_INFINITY;
                continue;
            }
            double t0 = (minima[axis] - origins[axis]) / directions[axis];
            double t1 = (maxima[axis] - origins[axis]) / directions[axis];
            near = Math.max(near, Math.min(t0, t1));
            far = Math.min(far, Math.max(t0, t1));
            if (near > far) return Double.POSITIVE_INFINITY;
        }
        return far < 0 ? Double.POSITIVE_INFINITY : near;
    }

    private static double rank(Node node, Config config) {
        State s = node.state;
        double ourRay = rayDistance(s.a.x, s.a.y, s.a.z, s.aSeesB.x, s.aSeesB.y, s.aSeesB.z);
        double finiteRay = Double.isFinite(ourRay) ? ourRay : 10;
        double lost = Math.max(0, finiteRay - 3.8);
        double contactGap = Math.max(0, finiteRay - 3.05);
        return s.accumulatedReward - config.lostPursuitPenalty * lost * lost
                - config.terminalGapPenalty * contactGap * contactGap;
    }
    private static double quantize(double position) { return Math.floor(position * 32) / 32; }
    private static double velocityQuantize(double velocity) {
        return ((int)(Math.max(-3.9, Math.min(3.9, velocity)) * 8000)) / 8000.0;
    }
    private static double[] horizontalUnit(double dx, double dz) {
        double length = Math.hypot(dx, dz);
        return length < 1e-9 ? new double[]{1, 0} : new double[]{dx / length, dz / length};
    }
    private static void setLook(View view, double dx, double dz) {
        double[] unit = horizontalUnit(dx, dz); view.lookX = unit[0]; view.lookZ = unit[1];
    }
    private static void requireFinite(double... values) {
        for (double value : values) if (!Double.isFinite(value))
            throw new IllegalArgumentException("State correction must be finite");
    }
    private static void translateBody(Body body, double dx, double dy, double dz) {
        body.x += dx;
        body.z += dz;
        body.y = Math.max(0, body.y + dy);
        if (body.y == 0 && dy < 0) {
            body.ground = true;
            if (body.vy < 0) body.vy = -.0784000015258789;
        } else if (body.y > 0) body.ground = false;
    }
    private static void translateView(View view, double dx, double dy, double dz) {
        view.x += dx; view.goalX += dx;
        view.z += dz; view.goalZ += dz;
        view.y = Math.max(0, view.y + dy);
        view.goalY = Math.max(0, view.goalY + dy);
    }
}
