package lab;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Provisional, three-coordinate forward model for a W-only attacker and a
 * defender that holds W + Ctrl and aims at its local opponent. The defender
 * is a non-clicking probe by default; Config.defenderClicks enables attacks.
 * Config.defenderWtap additionally mirrors the lab's delayed-cue W-tap policy.
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
 * Important omissions: A/D, enchantments, armor/potions, water/ladders/mounts,
 * exact body collisions, exact packet/tick phases and rendering float rounding.
 * Critical damage assumes the lab's dry, flat arena and unenchanted weapon.
 * Position replication is modeled with 2-tick server tracking,
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

    /** Offline diagnostic clocks; equal-time events execute in caller-supplied order. */
    public enum NativeEventKind { A, B, SERVER }

    public static final class Config {
        public double ownRttMs = 20, opponentRttMs = 100;
        /** Phases relative to the attacker tick, each in [0,49] ms. */
        public int serverPhaseMs = 25, opponentPhaseMs = 10, trackerPhase = 0;
        public int horizonTicks = 26, beamWidth = 24, minimumActionTicks = 2;
        /** Optional approximate state deduplication plus per-root-action quotas. */
        public boolean beamDiversity = false;
        /** Compare complete timed pause/resume plans against the final beam. */
        public boolean macroSeeds = false;
        /** Restrict proposed inputs only; recorded history can still contain WALK. */
        public boolean allowWalk = true;
        public int clickIntervalMs = 100;
        /** Native value is 500; larger values deliberately extend modeled immunity. */
        public int freshHitIntervalMs = 500;
        /** Native lab weapon damage, before the 1.5x falling critical multiplier. */
        public float baseAttackDamage = 8;
        public boolean modelCriticals = true;
        /** Known opponent policy, not a read of its private live controls. */
        public boolean defenderClicks = false;
        /** False models standstill: no W/Ctrl input, but clicks and physics continue. */
        public boolean defenderMoves = true;
        /** Client.kind=wtap: one W/Ctrl release per delayed outgoing fresh-hurt cue. */
        public boolean defenderWtap = false;
        public int defenderReactionMs = 150, defenderWtapPauseMs = 50;
        public int defenderClickIntervalMs = 100;
        /** Optional hypothesized physical click phase; never before control starts. */
        public long defenderFirstClickAtMs = Long.MIN_VALUE;
        /** The native probe waits for its initial delayed observation before W. */
        public long defenderControlAtMs = Long.MIN_VALUE;
        /** Current model uses these calibrated hypotheses, not hidden S12 reads. */
        public double normalImpulseX = .4, normalImpulseY = .3608;
        public double sprintImpulseX = .9, sprintImpulseY = .4608;
        public double hitReward = 1000, opportunityPenalty = 300;
        /** Cost of any accepted incoming hit, including a critical damage upgrade. */
        public double receivedHitPenalty = 1000;
        /** Optional fresh-hit cost for contact inside the 3-block local ray edge. */
        public double contactEdgePenalty = 0;
        public double oneSidedReward = 0, mutualOpportunityPenalty = 0;
        public double penetrationPenalty = 300, safetyMargin = .05;
        public double missPenalty = .4, switchPenalty = .15;
        public double lostPursuitPenalty = 35, terminalGapPenalty = 8;
        public boolean modelDoubleTapSprint = true;
        /** Isolated native-look factor: frame pitch is current, head yaw syncs during living update. */
        public boolean modelHeadYaw = false;
        /** Isolated dry-floor factor: landing damage, immunity and tracker velocityChanged flush. */
        public boolean modelFallDamage = false;
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
                    || minimumActionTicks < 1 || clickIntervalMs < 100 || defenderClickIntervalMs < 100
                    || freshHitIntervalMs < 500)
                throw new IllegalArgumentException("Invalid search or click limits");
            if (!Double.isFinite(contactEdgePenalty) || contactEdgePenalty < 0)
                throw new IllegalArgumentException("Contact-edge penalty must be finite and nonnegative");
            if (!Double.isFinite(receivedHitPenalty) || receivedHitPenalty < 0)
                throw new IllegalArgumentException("Received-hit penalty must be finite and nonnegative");
            if (!Double.isFinite(switchPenalty) || switchPenalty < 0)
                throw new IllegalArgumentException("Switch penalty must be finite and nonnegative");
            if (!Float.isFinite(baseAttackDamage) || baseAttackDamage <= 0)
                throw new IllegalArgumentException("Base attack damage must be finite and positive");
            if (defenderReactionMs < 150 || defenderWtapPauseMs < 0)
                throw new IllegalArgumentException("Defender reaction must be at least 150ms and pause nonnegative");
        }
    }

    public static final class Body {
        public double x, y, z, vx, vy, vz;
        public boolean ground = true, sprint;
        /** Ground attribute follows sprint immediately; only air factor is cached across travel. */
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

    private enum PacketKind { A_MOVE, B_MOVE, A_SPRINT, B_SPRINT, ATTACK, B_ATTACK,
        CONFIRMED_HIT, CONFIRMED_B_HIT, A_VIEW, B_VIEW, A_VELOCITY, B_VELOCITY,
        A_HURT_CUE, A_OWN_HURT_CUE, B_TARGET_HURT_CUE }

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
        public long nextDefenderPhysicalClickMs, ownVulnerableAtMs;
        /** Native per-victim timers; >10 allows only damage greater than lastDamage. */
        public int targetHurtResistantTicks, ownHurtResistantTicks;
        public float targetLastDamage, ownLastDamage;
        /** Server fallDistance follows reported C03 descent, not latent motionY. */
        public float serverFallDistanceA, serverFallDistanceB;
        /** Non-player damage is separate from the referee's accepted PvP-hit score. */
        public int ownFallEvents, targetFallEvents;
        public double ownFallDamage, targetFallDamage;
        /** Native pending velocityChanged; tracker flush is independent of position cadence. */
        public boolean aVelocityChanged, bVelocityChanged;
        public long lastPredictedHurtCueMs = Long.MIN_VALUE;
        public long lastPredictedOwnHurtCueMs = Long.MIN_VALUE;
        /** Modeled B-local observation timestamps; never an immediate server-hit oracle. */
        public long lastPredictedDefenderTargetHurtCueMs = Long.MIN_VALUE;
        public long defenderEligibleTargetHurtCueMs = Long.MIN_VALUE;
        public long defenderHandledTapCueMs = Long.MIN_VALUE, defenderTapUntilMs = Long.MIN_VALUE;
        public int defenderWtapCount;
        public boolean defenderForward;
        public Body a, b;
        /** Last horizontal head directions synchronized by each local living update. */
        public double aHeadLookX, aHeadLookZ, bHeadLookX, bHeadLookZ;
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
        public int receivedHits, defenderPhysicalClicks, defenderAirMisses;
        public int acceptedFreshHits, acceptedDamageUpgrades;
        public int receivedFreshHits, receivedDamageUpgrades;
        /** Actual health damage before armor; upgrades deal only the positive difference. */
        public double damageDealt, damageReceived;
        /** Consecutive accepted outgoing hits since the latest accepted incoming hit. */
        public int unansweredHits, longestUnansweredHits;
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
        public long lastPredictedReceivedHitMs = Long.MIN_VALUE;
        public double lastReceivedImpulseX, lastReceivedImpulseY, lastReceivedImpulseZ;
        public boolean lastReceivedHitSprint;
        private double trackedAX, trackedAY, trackedAZ, trackedBX, trackedBY, trackedBZ;
        private long nextPacketSequence;
        // Public vulnerableAt fields remain compatibility/diagnostic estimates.
        // Detect explicit caller changes, then use server ticks rather than wall
        // time internally so a delayed server tick cannot expire immunity early.
        private long mirroredTargetVulnerableAtMs, mirroredOwnVulnerableAtMs;
        private final ArrayList<Packet> packets = new ArrayList<>();
        // Keep consumed entries as well: later ordinary attacks must not recreate
        // a confirmed event, and repeated historical replay must be idempotent.
        private final ArrayList<Long> confirmedHitTimes = new ArrayList<>();
        private final ArrayList<Long> confirmedReceivedHitTimes = new ArrayList<>();
        // Fresh-hurt animations already delivered to B and sampled on a B
        // client tick, but not yet old enough for B's delayed policy input.
        private final ArrayList<Long> defenderVisibleHurtCues = new ArrayList<>();

        /**
         * Exact required initial estimates are deliberately explicit. Views are
         * NOT interchangeable with actual body positions. Pending packets and
         * fresh-hit phase must be reconstructed by advancing known history or
         * supplied as hypotheses using schedule* methods below.
         */
        public State(long nowMs, Body attacker, Body defender, View attackerView,
                     View defenderView, View serverAttacker, View serverDefender) {
            this.nowMs = nowMs; nextPhysicalClickMs = nextDefenderPhysicalClickMs = nowMs;
            a = attacker.copy(); b = defender.copy();
            aSeesB = attackerView.copy(); bSeesA = defenderView.copy();
            serverA = serverAttacker.copy(); serverB = serverDefender.copy();
            serverMotionA = new Body(serverA.x, serverA.y, serverA.z, 0, -.0784, 0, a.ground, a.sprint);
            serverMotionB = new Body(serverB.x, serverB.y, serverB.z, 0, -.0784, 0, b.ground, b.sprint);
            setLook(serverA, aSeesB.x - a.x, aSeesB.z - a.z);
            setLook(serverB, bSeesA.x - b.x, bSeesA.z - b.z);
            aHeadLookX = serverA.lookX; aHeadLookZ = serverA.lookZ;
            bHeadLookX = serverB.lookX; bHeadLookZ = serverB.lookZ;
            aServerSprint = aLastSentSprint = a.sprint;
            bServerSprint = bLastSentSprint = b.sprint;
            defenderForward = b.sprint;
            previousForward = a.sprint;
            previousAction = a.sprint ? Action.SPRINT : Action.RELEASE;
            trackedAX = quantize(serverA.x); trackedAY = quantize(serverA.y);
            trackedBX = quantize(serverB.x); trackedBY = quantize(serverB.y);
            trackedAZ = quantize(serverA.z); trackedBZ = quantize(serverB.z);
        }

        public State copy() {
            State result = new State(nowMs, a, b, aSeesB, bSeesA, serverA, serverB);
            result.nextPhysicalClickMs = nextPhysicalClickMs;
            result.nextDefenderPhysicalClickMs = nextDefenderPhysicalClickMs;
            result.targetVulnerableAtMs = targetVulnerableAtMs;
            result.ownVulnerableAtMs = ownVulnerableAtMs;
            result.mirroredTargetVulnerableAtMs = mirroredTargetVulnerableAtMs;
            result.mirroredOwnVulnerableAtMs = mirroredOwnVulnerableAtMs;
            result.targetHurtResistantTicks = targetHurtResistantTicks;
            result.ownHurtResistantTicks = ownHurtResistantTicks;
            result.targetLastDamage = targetLastDamage; result.ownLastDamage = ownLastDamage;
            result.serverFallDistanceA = serverFallDistanceA;
            result.serverFallDistanceB = serverFallDistanceB;
            result.ownFallEvents = ownFallEvents; result.targetFallEvents = targetFallEvents;
            result.ownFallDamage = ownFallDamage; result.targetFallDamage = targetFallDamage;
            result.aVelocityChanged = aVelocityChanged; result.bVelocityChanged = bVelocityChanged;
            result.lastPredictedHurtCueMs = lastPredictedHurtCueMs;
            result.lastPredictedOwnHurtCueMs = lastPredictedOwnHurtCueMs;
            result.lastPredictedDefenderTargetHurtCueMs = lastPredictedDefenderTargetHurtCueMs;
            result.defenderEligibleTargetHurtCueMs = defenderEligibleTargetHurtCueMs;
            result.defenderHandledTapCueMs = defenderHandledTapCueMs;
            result.defenderTapUntilMs = defenderTapUntilMs;
            result.defenderWtapCount = defenderWtapCount;
            result.defenderForward = defenderForward;
            result.aHeadLookX = aHeadLookX; result.aHeadLookZ = aHeadLookZ;
            result.bHeadLookX = bHeadLookX; result.bHeadLookZ = bHeadLookZ;
            result.aServerSprint = aServerSprint; result.bServerSprint = bServerSprint;
            result.aLastSentSprint = aLastSentSprint; result.bLastSentSprint = bLastSentSprint;
            result.previousForward = previousForward; result.doubleTapTicks = doubleTapTicks;
            result.serverTicks = serverTicks; result.actionTicks = actionTicks;
            result.previousAction = previousAction;
            result.acceptedHits = acceptedHits;
            result.receivedHits = receivedHits;
            result.acceptedFreshHits = acceptedFreshHits;
            result.acceptedDamageUpgrades = acceptedDamageUpgrades;
            result.receivedFreshHits = receivedFreshHits;
            result.receivedDamageUpgrades = receivedDamageUpgrades;
            result.damageDealt = damageDealt; result.damageReceived = damageReceived;
            result.defenderPhysicalClicks = defenderPhysicalClicks;
            result.defenderAirMisses = defenderAirMisses;
            result.unansweredHits = unansweredHits;
            result.longestUnansweredHits = longestUnansweredHits;
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
            result.lastPredictedReceivedHitMs = lastPredictedReceivedHitMs;
            result.lastReceivedImpulseX = lastReceivedImpulseX;
            result.lastReceivedImpulseY = lastReceivedImpulseY;
            result.lastReceivedImpulseZ = lastReceivedImpulseZ;
            result.lastReceivedHitSprint = lastReceivedHitSprint;
            result.trackedAX = trackedAX; result.trackedAY = trackedAY;
            result.trackedBX = trackedBX; result.trackedBY = trackedBY;
            result.trackedAZ = trackedAZ; result.trackedBZ = trackedBZ;
            result.nextPacketSequence = nextPacketSequence;
            result.packets.addAll(packets); // Packet is immutable.
            result.confirmedHitTimes.addAll(confirmedHitTimes);
            result.confirmedReceivedHitTimes.addAll(confirmedReceivedHitTimes);
            result.defenderVisibleHurtCues.addAll(defenderVisibleHurtCues);
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
            return scheduleConfirmation(serverTimeMs, config, true);
        }

        /**
         * Symmetric historical B->A hit, proven only by our eligible delayed own
         * hurt cue. Uses a separate copied ledger and suppresses only B_ATTACK.
         * The same rewind-before-hit-minus-100 requirement applies. No opponent
         * packet or referee input is needed or permitted by the policy.
         */
        public boolean scheduleConfirmedIncomingHit(long serverTimeMs, Config config) {
            return scheduleConfirmation(serverTimeMs, config, false);
        }

        private boolean scheduleConfirmation(long serverTimeMs, Config config, boolean fromA) {
            config.validate();
            ArrayList<Long> ledger = fromA ? confirmedHitTimes : confirmedReceivedHitTimes;
            long previous = fromA ? lastPredictedHitMs : lastPredictedReceivedHitMs;
            if (ledger.contains(serverTimeMs)) return false;
            if (serverTimeMs < nowMs)
                throw new IllegalArgumentException("Rewind before scheduling a past confirmed hit");
            if (previous != Long.MIN_VALUE && Math.abs((double) previous - serverTimeMs) <= 100)
                throw new IllegalStateException("Rewind before confirmed hit minus 100 ms to undo a conflicting prediction");
            ledger.add(serverTimeMs);
            enqueue(this, fromA ? PacketKind.CONFIRMED_HIT : PacketKind.CONFIRMED_B_HIT,
                    serverTimeMs, 0, 0, false);
            return true;
        }

        /** Add only a model hypothesis inferred from delayed legal observations. */
        public void scheduleDefenderImpulse(double arrivalMs, double vx, double vy) {
            scheduleDefenderImpulse(arrivalMs, vx, vy, 0);
        }
        public void scheduleDefenderImpulse(double arrivalMs, double vx, double vy, double vz) {
            enqueue3(this, PacketKind.B_VELOCITY, arrivalMs, vx, vy, vz, false);
        }
        /** Symmetric incoming-impulse hypothesis from delayed legal observations. */
        public void scheduleAttackerImpulse(double arrivalMs, double vx, double vy, double vz) {
            enqueue3(this, PacketKind.A_VELOCITY, arrivalMs, vx, vy, vz, false);
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
        /** "beam", "warm", or the selected complete pause/resume macro description. */
        public final String planSource;
        public final double score, minimumDefenderRay;
        public final int predictedHits, predictedReceivedHits, predictedDefenderOpportunityTicks;
        public final long firstDefenderOpportunityMs;
        public final State predictedEnd;
        /** Beam-only diagnostics; complete macro/warm candidates are compared afterward. */
        public final int finalBeamSize, distinctStateBins, rootActionsRetained;
        private Decision(Node best, State initial, Config config, List<Node> finalBeam) {
            action = best.plan[0];
            plan = List.of(best.plan.clone());
            planSource = best.planSource;
            score = rank(best, config) - initial.accumulatedReward;
            predictedHits = best.state.acceptedHits - initial.acceptedHits;
            predictedReceivedHits = best.state.receivedHits - initial.receivedHits;
            predictedDefenderOpportunityTicks = best.state.defenderOpportunityTicks
                    - initial.defenderOpportunityTicks;
            minimumDefenderRay = best.state.minimumDefenderRay;
            firstDefenderOpportunityMs = best.state.firstDefenderOpportunityMs;
            predictedEnd = best.state.copy();
            finalBeamSize = finalBeam.size();
            Set<String> states = new HashSet<>();
            Set<Action> roots = new HashSet<>();
            for (Node node : finalBeam) {
                states.add(stateBin(node.state, config));
                roots.add(node.plan[0]);
            }
            distinctStateBins = states.size(); rootActionsRetained = roots.size();
        }
    }

    private static final class Node {
        final State state;
        final Action[] plan;
        final String planSource;
        Node(State state, Action[] plan) { this(state, plan, "beam"); }
        Node(State state, Action[] plan, String planSource) {
            this.state = state; this.plan = plan; this.planSource = planSource;
        }
    }

    /** Beam-search approximation, not an optimal-policy claim. Does not mutate input. */
    public static Decision choose(State initial, Config config) {
        return choose(initial, config, null);
    }

    /**
     * Optionally compare an already shifted prior winning plan. The caller
     * removes actions actually issued since that decision; the first supplied
     * action is the next proposed input. A short plan is extended by holding its
     * final action, and a long plan is truncated to this horizon. An empty/null
     * plan adds no candidate. Illegal dwell transitions discard the candidate,
     * without silently rewriting it. Neither the input state nor list is changed.
     * This complete rollout is independent of Config.macroSeeds. On an exact
     * score tie the warm plan is retained to avoid a gratuitous plan change.
     */
    public static Decision choose(State initial, Config config, List<Action> warmPlan) {
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
                    if (!legalAction(node.state, action, config)) continue;
                    State state = node.state.copy();
                    step(state, action, config);
                    Action[] plan = new Action[depth + 1];
                    System.arraycopy(node.plan, 0, plan, 0, depth);
                    plan[depth] = action;
                    next.add(new Node(state, plan));
                }
            }
            next.sort(order);
            beam = config.beamDiversity ? diverseBeam(next, config)
                    : new ArrayList<>(next.subList(0, Math.min(config.beamWidth, next.size())));
        }
        beam.sort(order);
        Node best = beam.get(0);
        if (config.macroSeeds) {
            for (int start : new int[]{0, 1, 2, 3, 4, 6, 8, 10, 12}) {
                for (int duration : new int[]{1, 2, 3, 4, 6, 8}) {
                    // Keep the return to forward movement inside the horizon.
                    if (start + duration >= config.horizonTicks) continue;
                    for (Action resume : new Action[]{Action.SPRINT, Action.WALK}) {
                        Action[] plan = new Action[config.horizonTicks];
                        for (int tick = 0; tick < plan.length; tick++)
                            plan[tick] = tick < start ? Action.SPRINT
                                    : tick < start + duration ? Action.RELEASE : resume;
                        Node candidate = completeRollout(seed, plan, config,
                                "macro:start=" + start + ",release=" + duration + ",resume=" + resume);
                        if (candidate != null && rank(candidate, config) > rank(best, config)) best = candidate;
                    }
                }
            }
        }
        if (warmPlan != null && !warmPlan.isEmpty()) {
            Action[] plan = new Action[config.horizonTicks];
            for (int tick = 0; tick < plan.length; tick++)
                plan[tick] = warmPlan.get(Math.min(tick, warmPlan.size() - 1));
            Node candidate = completeRollout(seed, plan, config, "warm");
            if (candidate != null && rank(candidate, config) >= rank(best, config)) best = candidate;
        }
        return new Decision(best, initial, config, beam);
    }

    private static boolean legalAction(State state, Action action, Config config) {
        return action != null && (config.allowWalk || action != Action.WALK) && (state.actionTicks == 0
                || state.actionTicks >= config.minimumActionTicks || action == state.previousAction);
    }

    /** One state copy per complete candidate, then ordinary mutable native-tick steps. */
    private static Node completeRollout(State seed, Action[] plan, Config config, String source) {
        State state = seed.copy();
        for (Action action : plan) {
            if (!legalAction(state, action, config)) return null;
            step(state, action, config);
        }
        return new Node(state, plan, source);
    }

    /**
     * Preserve up to an equal quota for each legal first action, then fill by
     * score. Within each root keep only the best node in a quantized physical
     * state bin. Quotas stop an initially costly opener disappearing before its
     * later payoff; bins prevent many control histories filling the same quota.
     * A beam narrower than the number of roots cannot protect every root.
     * These are approximate bins, not an assertion of exact state equivalence.
     */
    private static ArrayList<Node> diverseBeam(List<Node> ranked, Config config) {
        ArrayList<Node> unique = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Set<Action> roots = new HashSet<>();
        for (Node node : ranked) {
            Action root = node.plan[0];
            if (seen.add(root.ordinal() + ":" + stateBin(node.state, config))) {
                unique.add(node); roots.add(root);
            }
        }
        if (config.beamWidth < roots.size())
            return new ArrayList<>(unique.subList(0, Math.min(config.beamWidth, unique.size())));
        int quota = Math.max(1, config.beamWidth / roots.size());
        int[] counts = new int[Action.values().length];
        Set<Node> selected = new HashSet<>();
        ArrayList<Node> result = new ArrayList<>();
        for (Node node : unique) {
            int root = node.plan[0].ordinal();
            if (counts[root] < quota) {
                result.add(node); selected.add(node); counts[root]++;
            }
        }
        for (Node node : unique) {
            if (result.size() >= config.beamWidth) break;
            if (selected.add(node)) result.add(node);
        }
        return result;
    }

    private static String stateBin(State s, Config c) {
        StringBuilder key = new StringBuilder(320);
        if (c.modelHeadYaw) {
            quantized(key, s.aHeadLookX, 20); quantized(key, s.aHeadLookZ, 20);
            quantized(key, s.bHeadLookX, 20); quantized(key, s.bHeadLookZ, 20);
        }
        if (c.modelFallDamage) {
            key.append('/').append(s.aVelocityChanged).append(s.bVelocityChanged);
            quantized(key, s.serverFallDistanceA, 100); quantized(key, s.serverFallDistanceB, 100);
        }
        // The arena is translation-invariant in x/z. Retain relative body,
        // client-view and server positions at 0.1m resolution, and each distinct
        // velocity at 0.025 blocks/tick. Vertical coordinates remain floor-based.
        positionBin(key, s.b.x, s.b.y, s.b.z, s.a);
        quantized(key, s.a.y, 10);
        for (Body body : new Body[]{s.a, s.b, s.serverMotionA, s.serverMotionB}) {
            quantized(key, body.vx, 40); quantized(key, body.vy, 40); quantized(key, body.vz, 40);
            key.append(body.ground ? 'g' : 'a');
        }
        for (View view : new View[]{s.aSeesB, s.bSeesA, s.serverA, s.serverB}) {
            positionBin(key, view.x, view.y, view.z, s.a);
            positionBin(key, view.goalX, view.goalY, view.goalZ, s.a);
            quantized(key, view.lookX, 20); quantized(key, view.lookZ, 20);
            key.append(view.interpolationTicks).append('/');
        }
        key.append(s.a.sprint).append(s.b.sprint).append(s.aServerSprint).append(s.bServerSprint)
                .append(s.aLastSentSprint).append(s.bLastSentSprint).append(s.previousForward)
                .append('/').append(s.targetHurtResistantTicks).append('/').append(s.ownHurtResistantTicks)
                .append('/').append(s.targetLastDamage).append('/').append(s.ownLastDamage)
                .append('/').append(s.serverFallDistanceA > 0).append(s.serverFallDistanceB > 0)
                .append('/').append(s.previousAction.ordinal())
                .append('/').append(Math.min(s.actionTicks, c.minimumActionTicks))
                .append('/').append(s.doubleTapTicks)
                .append('/').append(s.nextPhysicalClickMs - s.nowMs)
                .append('/').append(s.nextDefenderPhysicalClickMs - s.nowMs);
        // Equal bodies can still have different futures when START/STOP,
        // movement, interpolation or impulse packets are in flight. Keep their
        // order, timing and quantized contents rather than merging those states.
        for (Packet packet : s.packets) {
            key.append('|').append(packet.kind.ordinal()).append(':')
                    .append(Math.round((packet.dueMs - s.nowMs) * 2)).append(':').append(packet.flag);
            switch (packet.kind) {
                case A_MOVE, B_MOVE, A_VIEW, B_VIEW -> {
                    positionBin(key, packet.x, packet.y, packet.z, s.a);
                    if (Double.isFinite(packet.lookX)) {
                        quantized(key, packet.lookX, 20); quantized(key, packet.lookZ, 20);
                    }
                }
                case A_VELOCITY, B_VELOCITY -> {
                    quantized(key, packet.x, 40); quantized(key, packet.y, 40); quantized(key, packet.z, 40);
                }
                case ATTACK, B_ATTACK -> quantized(key, packet.x, 10);
                default -> { }
            }
        }
        for (long hit : s.confirmedHitTimes) if (hit >= s.nowMs - 100) key.append("/A").append(hit - s.nowMs);
        for (long hit : s.confirmedReceivedHitTimes) if (hit >= s.nowMs - 100) key.append("/B").append(hit - s.nowMs);
        if (c.defenderWtap) {
            key.append("/tap").append(s.defenderForward).append(':').append(s.defenderTapUntilMs)
                    .append(':').append(s.defenderEligibleTargetHurtCueMs)
                    .append(':').append(s.defenderHandledTapCueMs);
            for (long cue : s.defenderVisibleHurtCues) key.append(':').append(cue);
        }
        return key.toString();
    }

    private static void positionBin(StringBuilder key, double x, double y, double z, Body origin) {
        quantized(key, x - origin.x, 10); quantized(key, y, 10); quantized(key, z - origin.z, 10);
    }

    private static void quantized(StringBuilder key, double value, double scale) {
        key.append('/').append(Math.round(value * scale));
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

    /**
     * Offline clock diagnostic: execute exactly one recorded client/server tick,
     * using the SAME travel, reach, combat and packet queues as ordinary planning.
     * No other ticks or automatic clicks are inserted. eventTimeMs must be
     * nondecreasing; equal timestamps preserve the caller's explicit order.
     * nowMs in the result is the time of this completed event, not the next A tick.
     * Do not mix this clock convention with advance/choose on the resulting state.
     *
     * A/B physicalClickConsumed describes one recorded short press consumed on
     * that client tick, including misses and immune contacts. It is NOT a native
     * hit confirmation. B accepts only RELEASE/SPRINT, matching the recorded
     * hold-W, W-tap and standstill fixtures; WALK would require independent B
     * double-tap/input history. SERVER requires null action and no click.
     *
     * This is not an exact native replay: queues still drain at the corresponding
     * supplied event, server tracking retains its approximate cadence, and client
     * aiming is reconstructed from predicted views. End-of-server-tick logging
     * and a client travel timestamp are proxies for different points within a
     * tick, not exact network-task/ATTACK dispatch timestamps. The caller must
     * initialize once, then supply only clocks/inputs, never state corrections or
     * confirmed hits. Compare all later native state strictly as held-out data.
     */
    public static State replayNativeEvent(State state, long eventTimeMs,
                                         NativeEventKind eventKind, Action recordedAction,
                                         boolean physicalClickConsumed, Config config) {
        config.validate();
        if (eventKind == null || eventTimeMs < state.nowMs)
            throw new IllegalArgumentException("Native replay requires ordered events and a kind");
        if (eventKind == NativeEventKind.SERVER) {
            if (recordedAction != null || physicalClickConsumed)
                throw new IllegalArgumentException("SERVER events have no action or click");
        } else if (recordedAction == null
                || (eventKind == NativeEventKind.B && recordedAction == Action.WALK)) {
            throw new IllegalArgumentException("Client action required; B supports RELEASE/SPRINT only");
        }
        State result = state.copy();
        result.nowMs = eventTimeMs;
        switch (eventKind) {
            case A -> {
                recordAttackerAction(result, recordedAction, config);
                attackerTick(result, recordedAction, config, eventTimeMs, physicalClickConsumed);
            }
            case B -> defenderTick(result, config, eventTimeMs,
                    recordedAction, physicalClickConsumed);
            case SERVER -> serverTick(result, config, eventTimeMs);
        }
        return result;
    }

    private static void step(State s, Action action, Config c) {
        step(s, action, c, null);
    }

    private static void step(State s, Action action, Config c, Boolean recordedClick) {
        recordAttackerAction(s, action, c);
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

    private static void recordAttackerAction(State s, Action action, Config c) {
        if (action != s.previousAction) {
            s.accumulatedReward -= c.switchPenalty;
            s.actionTicks = 0;
        }
        s.previousAction = action; s.actionTicks++;
    }

    private static void attackerTick(State s, Action action, Config c, long time,
                                     Boolean recordedClick) {
        receiveClient(s, true, time);
        s.lastOurRay = clientRayDistance(s, true, c);
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
        // EntityPlayer.updateEntityActionState copies rotationYaw to head yaw
        // during living update, AFTER this tick's local attack and before travel.
        // Movement and the subsequently sent server yaw use current body aim.
        s.aHeadLookX = look[0]; s.aHeadLookZ = look[1];
        // EntityPlayer overrides getAIMoveSpeed with the current attribute;
        // sprint modifies that attribute BEFORE this tick's ground travel.
        // The air factor still comes from the preceding onLivingUpdate.
        s.a.groundAcceleration = s.a.sprint ? .13 : .1;
        move(s.a, action.forward ? look[0] : 0, action.forward ? look[1] : 0);
        s.aSeesB.step();
        if (s.a.sprint != s.aLastSentSprint) {
            enqueue(s, PacketKind.A_SPRINT, time + c.ownRttMs / 2, 0, 0, s.a.sprint);
            s.aLastSentSprint = s.a.sprint;
        }
        enqueueMove(s, PacketKind.A_MOVE, time + c.ownRttMs / 2, s.a, look);
    }

    private static void defenderTick(State s, Config c, long time) {
        defenderTick(s, c, time, null, null);
    }

    /** Null overrides preserve the normal known-policy prediction path. */
    private static void defenderTick(State s, Config c, long time,
                                     Action recordedAction, Boolean recordedClick) {
        receiveClient(s, false, time);
        s.lastDefenderRay = clientRayDistance(s, false, c);
        // Paired model opportunities at the same modeled instant. The final
        // metric is always measured from the two real clients' native rays.
        double ourRayNow = clientRayDistance(s, true, c);
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
        boolean active = time >= c.defenderControlAtMs;
        if (recordedAction == null && c.defenderWtap) {
            // Match Client.kind=wtap: first wait for a locally visible target
            // hurt animation, then wait until that sampled observation is 150ms
            // old. A fresh server hit alone must not cause a movement response.
            for (int i = 0; i < s.defenderVisibleHurtCues.size();) {
                long cue = s.defenderVisibleHurtCues.get(i);
                if (time - cue >= c.defenderReactionMs) {
                    s.defenderEligibleTargetHurtCueMs = Math.max(s.defenderEligibleTargetHurtCueMs, cue);
                    s.defenderVisibleHurtCues.remove(i);
                } else i++;
            }
            if (active && s.defenderEligibleTargetHurtCueMs > s.defenderHandledTapCueMs) {
                s.defenderTapUntilMs = time + c.defenderWtapPauseMs;
                s.defenderHandledTapCueMs = s.defenderEligibleTargetHurtCueMs;
                s.defenderWtapCount++;
            }
        }
        s.defenderForward = recordedAction != null ? recordedAction.forward
                : active && c.defenderMoves && (!c.defenderWtap || time >= s.defenderTapUntilMs);
        long nextClick = Math.max(s.nextDefenderPhysicalClickMs,
                Math.max(c.defenderControlAtMs, c.defenderFirstClickAtMs));
        if (recordedClick != null ? recordedClick : c.defenderClicks && active && time >= nextClick) {
            s.defenderPhysicalClicks++;
            // Independent short presses retain their physical phase even when
            // consumption occurs on a later native tick. Air misses do not add
            // an artificial 500ms cooldown; key release clears that counter.
            long injectionTime = recordedClick != null ? time : Math.max(nextClick, time - TICK_MS + 1);
            s.nextDefenderPhysicalClickMs = injectionTime + c.defenderClickIntervalMs;
            if (s.lastDefenderRay <= 3) {
                enqueue(s, PacketKind.B_ATTACK, time + c.opponentRttMs / 2,
                        s.lastDefenderRay, 0, false);
                if (s.b.sprint) {
                    s.b.vx *= .6;
                    s.b.vz *= .6;
                    s.b.sprint = false;
                }
            } else s.defenderAirMisses++;
        }
        // Native hold-W and wtap both hold Ctrl whenever W is down. A tap
        // releases W/Ctrl, but never suppresses the physical click above.
        // With W down, restore LOCAL sprint even after an immune contact; this
        // sends no new START if the last transmitted flag was already true.
        s.b.sprint = s.defenderForward;
        s.bHeadLookX = look[0]; s.bHeadLookZ = look[1];
        s.b.groundAcceleration = s.b.sprint ? .13 : .1;
        move(s.b, s.defenderForward ? look[0] : 0, s.defenderForward ? look[1] : 0);
        s.bSeesA.step();
        if (s.b.sprint != s.bLastSentSprint) {
            enqueue(s, PacketKind.B_SPRINT, time + c.opponentRttMs / 2, 0, 0, s.b.sprint);
            s.bLastSentSprint = s.b.sprint;
        }
        enqueueMove(s, PacketKind.B_MOVE, time + c.opponentRttMs / 2, s.b, look);
    }

    /** Native-inspired movement order, including ground friction on takeoff tick. */
    /** Offline native transition validation uses the same travel function as planning. */
    public static Body predictTravel(Body before,double inputX,double inputZ) {
        Body result=before.copy();move(result,inputX,inputZ);return result;
    }
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
        synchronizeExternalImmunity(s, c, time);
        List<Packet> incoming = takeDue(s, 0, time);
        for (Packet packet : incoming) {
            switch (packet.kind) {
                case A_MOVE -> serverMovement(s, true, packet, c, time);
                case B_MOVE -> serverMovement(s, false, packet, c, time);
                case A_SPRINT -> { s.aServerSprint = packet.flag; s.lastServerSprintCommandMs = time; }
                case B_SPRINT -> s.bServerSprint = packet.flag;
                case ATTACK -> {
                    if (nearConfirmedHit(s, time, true)) break;
                    double dx = s.serverB.x - s.serverA.x, dy = s.serverB.y - s.serverA.y;
                    double dz = s.serverB.z - s.serverA.z;
                    if (dx * dx + dy * dy + dz * dz >= 36) break;
                    acceptHit(s, c, time, packet.x, true, false);
                }
                case B_ATTACK -> {
                    if (nearConfirmedHit(s, time, false)) break;
                    double dx = s.serverA.x - s.serverB.x, dy = s.serverA.y - s.serverB.y;
                    double dz = s.serverA.z - s.serverB.z;
                    if (dx * dx + dy * dy + dz * dz >= 36) break;
                    acceptHit(s, c, time, packet.x, false, false);
                }
                // A visible hurt animation proves a FRESH hit, not a damage
                // upgrade. Its reconstructed critical condition still determines
                // lastDamage, which controls subsequent ordinary attack attempts.
                case CONFIRMED_HIT -> acceptHit(s, c, time, Double.NaN, true, true);
                case CONFIRMED_B_HIT -> acceptHit(s, c, time, Double.NaN, false, true);
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
        // EntityTrackerEntry checks velocityChanged outside its ordinary
        // position/update-frequency branch, and also sends to the player itself.
        flushVelocityChanged(s, true, c, time);
        flushVelocityChanged(s, false, c, time);
        // EntityPlayerMP.onUpdate decrements once per world/server tick. Its
        // per-C03 onUpdateEntity path explicitly does not decrement this timer.
        // Packets are processed before this update: a fresh 20 becomes 19 here.
        if (s.targetHurtResistantTicks > 0) s.targetHurtResistantTicks--;
        if (s.ownHurtResistantTicks > 0) s.ownHurtResistantTicks--;
        s.serverTicks++;
    }

    private static boolean nearConfirmedHit(State state, long serverTimeMs, boolean fromA) {
        for (long confirmed : fromA ? state.confirmedHitTimes : state.confirmedReceivedHitTimes)
            if (Math.abs((double) serverTimeMs - confirmed) <= 100) return true;
        return false;
    }

    private static void synchronizeExternalImmunity(State s, Config c, long time) {
        if (s.targetVulnerableAtMs != s.mirroredTargetVulnerableAtMs) {
            s.targetHurtResistantTicks = ticksFromDeadline(s.targetVulnerableAtMs, time);
            if (s.targetHurtResistantTicks > 10 && s.targetLastDamage == 0)
                s.targetLastDamage = c.baseAttackDamage;
            s.mirroredTargetVulnerableAtMs = s.targetVulnerableAtMs;
        }
        if (s.ownVulnerableAtMs != s.mirroredOwnVulnerableAtMs) {
            s.ownHurtResistantTicks = ticksFromDeadline(s.ownVulnerableAtMs, time);
            if (s.ownHurtResistantTicks > 10 && s.ownLastDamage == 0)
                s.ownLastDamage = c.baseAttackDamage;
            s.mirroredOwnVulnerableAtMs = s.ownVulnerableAtMs;
        }
    }

    private static int ticksFromDeadline(long deadline, long time) {
        if (deadline <= time) return 0;
        return (int) Math.min(Integer.MAX_VALUE, 10 + Math.ceil(((double) deadline - time) / TICK_MS));
    }

    /**
     * Vanilla 1.8.9 EntityPlayer.attackTargetEntityWithCurrentItem: sprinting is
     * compatible with criticals. Assume no ladder, water, blindness or mount in
     * this lab; damage uses server fallDistance and onGround, NOT motionY<0.
     */
    public static float predictedAttackDamage(State s, boolean fromA, Config c) {
        float distance = fromA ? s.serverFallDistanceA : s.serverFallDistanceB;
        Body motion = fromA ? s.serverMotionA : s.serverMotionB;
        return c.modelCriticals && distance > 0 && !motion.ground
                ? c.baseAttackDamage * 1.5f : c.baseAttackDamage;
    }

    private static void acceptHit(State s, Config c, long time, double localRay,
                                  boolean fromA, boolean confirmedFresh) {
        int resistantTicks = fromA ? s.targetHurtResistantTicks : s.ownHurtResistantTicks;
        float lastDamage = fromA ? s.targetLastDamage : s.ownLastDamage;
        float damage = predictedAttackDamage(s, fromA, c);
        boolean fresh = confirmedFresh || resistantTicks <= 10;
        // EntityLivingBase.attackEntityFrom compares full incoming damage,
        // deals only the difference for an upgrade, and returns true for it.
        if (!fresh && damage <= lastDamage) return;
        float appliedDamage = fresh ? damage : damage - lastDamage;
        boolean sprint = fromA ? s.aServerSprint : s.bServerSprint;
        boolean pendingVelocity = fromA ? s.bVelocityChanged : s.aVelocityChanged;
        double[] impulse = fresh ? knockback(s, fromA, sprint, c) : null;
        if (!fresh && pendingVelocity) {
            // An earlier fall in this same server task drain can leave the
            // victim dirty. Even an accepted damage upgrade then triggers the
            // outer player's S12/restore branch, without ordinary base KB.
            Body motion = fromA ? s.serverMotionB : s.serverMotionA;
            View hitter = fromA ? s.serverA : s.serverB;
            impulse = new double[]{velocityQuantize(motion.vx + (sprint ? .5 * hitter.lookX : 0)),
                    velocityQuantize(motion.vy + (sprint ? .1 : 0)),
                    velocityQuantize(motion.vz + (sprint ? .5 * hitter.lookZ : 0))};
        }
        if (fromA) {
            s.acceptedHits++;
            s.targetLastDamage = damage;
            s.damageDealt += appliedDamage;
            s.longestUnansweredHits = Math.max(s.longestUnansweredHits, ++s.unansweredHits);
            s.accumulatedReward += c.hitReward;
            if (fresh && Double.isFinite(localRay)) {
                double penetration = 3 - localRay;
                s.accumulatedReward -= c.contactEdgePenalty * penetration * penetration;
            }
            if (fresh) {
                s.acceptedFreshHits++;
                s.targetHurtResistantTicks = 10 + (int) Math.ceil(c.freshHitIntervalMs / (double) TICK_MS);
                s.targetVulnerableAtMs = s.mirroredTargetVulnerableAtMs = time + c.freshHitIntervalMs;
                s.lastPredictedHitMs = time; s.lastPredictedHitSprint = sprint;
                s.lastPredictedImpulseX = impulse[0]; s.lastPredictedImpulseY = impulse[1];
                s.lastPredictedImpulseZ = impulse[2];
            } else s.acceptedDamageUpgrades++;
        } else {
            s.receivedHits++;
            s.ownLastDamage = damage;
            s.damageReceived += appliedDamage;
            s.unansweredHits = 0;
            s.accumulatedReward -= c.receivedHitPenalty;
            if (fresh) {
                s.receivedFreshHits++;
                s.ownHurtResistantTicks = 10 + (int) Math.ceil(c.freshHitIntervalMs / (double) TICK_MS);
                s.ownVulnerableAtMs = s.mirroredOwnVulnerableAtMs = time + c.freshHitIntervalMs;
                s.lastPredictedReceivedHitMs = time; s.lastReceivedHitSprint = sprint;
                s.lastReceivedImpulseX = impulse[0]; s.lastReceivedImpulseY = impulse[1];
                s.lastReceivedImpulseZ = impulse[2];
            } else s.receivedDamageUpgrades++;
        }
        if (impulse != null) {
            // Fresh damage sets velocityChanged and base knockback. The outer
            // player attack sends S12 including any sprint bonus, then restores
            // the victim's pre-hit SERVER motion. Only local client motion changes.
            double victimRtt = fromA ? c.opponentRttMs : c.ownRttMs;
            enqueueHit(s, fromA ? PacketKind.B_VELOCITY : PacketKind.A_VELOCITY, time + victimRtt / 2,
                    impulse[0], impulse[1], impulse[2], time);
            if (fromA) s.bVelocityChanged = false;
            else s.aVelocityChanged = false;
        } else if (sprint) {
            // Upgrade damage does not set velocityChanged or base knockback.
            // addVelocity still adds sprint bonus to latent SERVER motion, but
            // sets only isAirBorne: no S12 is sent and motion is not restored.
            // Players have sendVelocityUpdates=false in vanilla EntityTracker,
            // so the tracker does not deliver this bonus to the victim either.
            Body victimMotion = fromA ? s.serverMotionB : s.serverMotionA;
            View hitter = fromA ? s.serverA : s.serverB;
            victimMotion.vx += .5 * hitter.lookX;
            victimMotion.vy += .1;
            victimMotion.vz += .5 * hitter.lookZ;
        }
        if (fresh) enqueueFreshDamageCues(s, !fromA, c, time, time);
        if (sprint) {
            if (fromA) s.aServerSprint = false;
            else s.bServerSprint = false;
            Body hitterMotion = fromA ? s.serverMotionA : s.serverMotionB;
            hitterMotion.vx *= .6; hitterMotion.vz *= .6;
        }
    }

    /**
     * processPlayer runs onUpdateEntity once per processed C03, before resetting
     * the position to the reported movement. Its zero-input server motion is
     * distinct from the client's motion. If that update leaves it grounded and
     * the packet rises into air, vanilla calls jump(), including sprint +0.2.
     */
    private static void serverMovement(State s, boolean ours, Packet packet, Config c, long time) {
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
        // EntityPlayerMP.handleFalling is called with the accepted C03 position
        // delta. Its ordinary simulated moveEntity/updateFallState override is
        // empty, so latent server gravity must NOT manufacture a critical.
        float fallDistance = ours ? s.serverFallDistanceA : s.serverFallDistanceB;
        if (packet.flag) {
            if (c.modelFallDamage) applyFallDamage(s, ours, fallDistance, c, time);
            // Native updateFallState uses the prior accumulated distance on
            // landing. It does NOT add this last grounded packet's negative dy.
            fallDistance = 0;
        } else if (packet.y < position.y) fallDistance = (float) (fallDistance + position.y - packet.y);
        if (ours) s.serverFallDistanceA = fallDistance;
        else s.serverFallDistanceB = fallDistance;
        position.x = packet.x; position.y = packet.y; position.z = packet.z;
        position.lookX = lookX; position.lookZ = lookZ;
        motion.x = packet.x; motion.y = packet.y; motion.z = packet.z;
        motion.ground = packet.flag;
    }

    /** Ordinary dry floor, multiplier 1, no jump boost/flight/enchantment reductions. */
    private static void applyFallDamage(State s, boolean ours, float distance, Config c, long time) {
        float damage = (float) Math.ceil(distance - 3.0f);
        if (damage <= 0) return;
        int resistant = ours ? s.ownHurtResistantTicks : s.targetHurtResistantTicks;
        float previousDamage = ours ? s.ownLastDamage : s.targetLastDamage;
        boolean fresh = resistant <= 10;
        if (!fresh && damage <= previousDamage) return;
        float applied = fresh ? damage : damage - previousDamage;
        if (ours) {
            s.ownFallEvents++; s.ownFallDamage += applied; s.ownLastDamage = damage;
            if (fresh) {
                s.ownHurtResistantTicks = 20;
                s.ownVulnerableAtMs = s.mirroredOwnVulnerableAtMs = time + 500;
                s.aVelocityChanged = true;
            }
        } else {
            s.targetFallEvents++; s.targetFallDamage += applied; s.targetLastDamage = damage;
            if (fresh) {
                s.targetHurtResistantTicks = 20;
                s.targetVulnerableAtMs = s.mirroredTargetVulnerableAtMs = time + 500;
                s.bVelocityChanged = true;
            }
        }
        // Fall has no source entity: attackEntityFrom emits the same fresh hurt
        // status and sets velocityChanged, but does NOT invoke base knockback.
        // No outer player attack restores motion here. The tracker will send
        // whatever latent server velocity exists at its later flush.
        if (fresh) enqueueFreshDamageCues(s, ours, c, time, Long.MIN_VALUE);
    }

    private static void enqueueFreshDamageCues(State s, boolean victimIsA, Config c,
                                               long time, long attackCauseMs) {
        enqueueHit(s, victimIsA ? PacketKind.A_OWN_HURT_CUE : PacketKind.A_HURT_CUE,
                time + c.ownRttMs / 2, 0, 0, 0, attackCauseMs);
        if (victimIsA && c.defenderWtap)
            enqueueHit(s, PacketKind.B_TARGET_HURT_CUE,
                    time + c.opponentRttMs / 2, 0, 0, 0, attackCauseMs);
    }

    private static void flushVelocityChanged(State s, boolean ours, Config c, long time) {
        if (!(ours ? s.aVelocityChanged : s.bVelocityChanged)) return;
        Body motion = ours ? s.serverMotionA : s.serverMotionB;
        enqueue3(s, ours ? PacketKind.A_VELOCITY : PacketKind.B_VELOCITY,
                time + (ours ? c.ownRttMs : c.opponentRttMs) / 2,
                velocityQuantize(motion.vx), velocityQuantize(motion.vy), velocityQuantize(motion.vz), false);
        // Watchers receive this packet too, but their remote motion fields do
        // not drive the model's interpolation positions. Only the victim's
        // local velocity overwrite changes the represented trajectory.
        if (ours) s.aVelocityChanged = false;
        else s.bVelocityChanged = false;
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

    /** B->A counterpart, using A's latent server motion and B's server yaw. */
    public static double[] predictedIncomingKnockback(State s, boolean sprint, Config c) {
        return knockback(s, false, sprint, c);
    }

    private static double[] knockback(State s, boolean sprint, Config c) {
        return knockback(s, true, sprint, c);
    }

    private static double[] knockback(State s, boolean fromA, boolean sprint, Config c) {
        View hitter = fromA ? s.serverA : s.serverB;
        View victim = fromA ? s.serverB : s.serverA;
        Body victimMotion = fromA ? s.serverMotionB : s.serverMotionA;
        double[] away = horizontalUnit(victim.x - hitter.x, victim.z - hitter.z);
        double vx, vy, vz;
        if (c.dynamicServerImpulses) {
            vx = victimMotion.vx * .5 + .4000000059604645 * away[0];
            vy = Math.min(.4000000059604645, victimMotion.vy * .5 + .4000000059604645);
            vz = victimMotion.vz * .5 + .4000000059604645 * away[1];
            if (sprint) {
                // The sprint bonus follows last SERVER yaw, which can oppose
                // the base displacement direction after the players cross.
                vx += .5 * hitter.lookX; vy += .1; vz += .5 * hitter.lookZ;
            }
        } else {
            vx = c.normalImpulseX * away[0]; vz = c.normalImpulseX * away[1];
            vy = sprint ? c.sprintImpulseY : c.normalImpulseY;
            if (sprint) {
                vx += (c.sprintImpulseX - c.normalImpulseX) * hitter.lookX;
                vz += (c.sprintImpulseX - c.normalImpulseX) * hitter.lookZ;
            }
        }
        return new double[]{velocityQuantize(vx), velocityQuantize(vy), velocityQuantize(vz)};
    }

    private static void receiveClient(State s, boolean attacker, long time) {
        for (Packet packet : takeDue(s, attacker ? 1 : 2, time)) {
            switch (packet.kind) {
                case A_VIEW -> s.aSeesB.receive(packet.x, packet.y, packet.z);
                case B_VIEW -> s.bSeesA.receive(packet.x, packet.y, packet.z);
                case A_VELOCITY -> { s.a.vx = packet.x; s.a.vy = packet.y; s.a.vz = packet.z; }
                case B_VELOCITY -> { s.b.vx = packet.x; s.b.vy = packet.y; s.b.vz = packet.z; }
                case A_HURT_CUE -> s.lastPredictedHurtCueMs = time;
                case A_OWN_HURT_CUE -> s.lastPredictedOwnHurtCueMs = time;
                case B_TARGET_HURT_CUE -> {
                    // Observe after B's network leg and at its own native tick.
                    // Multiple status packets before one observation produce
                    // one visible hurt-time rise, matching Client.Observation.
                    if (s.lastPredictedDefenderTargetHurtCueMs != time) s.defenderVisibleHurtCues.add(time);
                    s.lastPredictedDefenderTargetHurtCueMs = time;
                }
                default -> throw new IllegalStateException("Wrong client packet");
            }
        }
    }

    private static int destination(PacketKind kind) {
        return switch (kind) {
            case A_VIEW, A_VELOCITY, A_HURT_CUE, A_OWN_HURT_CUE -> 1;
            case B_VIEW, B_VELOCITY, B_TARGET_HURT_CUE -> 2;
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
        return rayDistanceAlong(eyeX, eyeY, eyeZ, dx, dy, dz, targetX, targetFeetY, targetZ);
    }

    /**
     * Geometric distance along a supplied eye/look ray to the ordinary expanded
     * player box. eyeY is ACTUAL EYE Y, while targetFeetY is feet Y. Direction
     * is normalized here, so its magnitude cannot change the returned block
     * distance. Returns +Infinity for a miss or a zero direction outside the
     * box; an eye inside/on the box returns zero. This excludes block occlusion,
     * entity eligibility and competing entities; it does not replace getMouseOver.
     */
    public static double rayDistanceAlong(double eyeX, double eyeY, double eyeZ,
                                          double dx, double dy, double dz,
                                          double targetX, double targetFeetY, double targetZ) {
        double[] minima = {targetX - .4, targetFeetY - .1, targetZ - .4};
        double[] maxima = {targetX + .4, targetFeetY + 1.9, targetZ + .4};
        if (eyeX >= minima[0] && eyeX <= maxima[0]
                && eyeY >= minima[1] && eyeY <= maxima[1]
                && eyeZ >= minima[2] && eyeZ <= maxima[2]) return 0;
        double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (length < 1e-12) return Double.POSITIVE_INFINITY;
        dx /= length; dy /= length; dz /= length;
        double near = 0, far = Double.POSITIVE_INFINITY;
        double[] origins = {eyeX, eyeY, eyeZ}, directions = {dx, dy, dz};
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

    private static double clientRayDistance(State s, boolean fromA, Config c) {
        Body body = fromA ? s.a : s.b;
        View target = fromA ? s.aSeesB : s.bSeesA;
        if (!c.modelHeadYaw) return rayDistance(body.x, body.y, body.z, target.x, target.y, target.z);
        double eyeY = body.y + 1.62;
        double horizontalGap = Math.hypot(target.x - body.x, target.z - body.z);
        double verticalAim = Math.max(target.y + .15, Math.min(eyeY, target.y + 1.65)) - eyeY;
        double pitchLength = Math.hypot(horizontalGap, verticalAim);
        double cosPitch = pitchLength < 1e-12 ? 1 : horizontalGap / pitchLength;
        double sinPitch = pitchLength < 1e-12 ? 0 : verticalAim / pitchLength;
        double headX = fromA ? s.aHeadLookX : s.bHeadLookX;
        double headZ = fromA ? s.aHeadLookZ : s.bHeadLookZ;
        return rayDistanceAlong(body.x, eyeY, body.z, headX * cosPitch, sinPitch,
                headZ * cosPitch, target.x, target.y, target.z);
    }

    private static double rank(Node node, Config config) {
        State s = node.state;
        double ourRay = clientRayDistance(s, true, config);
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
