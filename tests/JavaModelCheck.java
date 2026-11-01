import lab.ComboModel;
import lab.VisibleTracker;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Dependency-free regression checks for the provisional model and visible-only
 * observer. These protect model contracts; they do not prove vanilla fidelity
 * or controller strength, which require independent native-client recordings.
 */
public final class JavaModelCheck {
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void near(double actual, double expected, double tolerance, String message) {
        require(Math.abs(actual - expected) <= tolerance,
                message + ": expected " + expected + ", got " + actual);
    }

    private static ComboModel.State state(double x, double z, boolean sprint) {
        return new ComboModel.State(0,
                new ComboModel.Body(0, 0, 0, 0, -.0784, 0, true, sprint),
                new ComboModel.Body(x, 0, z, 0, -.0784, 0, true, true),
                new ComboModel.View(x, 0, z), new ComboModel.View(0, 0, 0),
                new ComboModel.View(0, 0, 0), new ComboModel.View(x, 0, z));
    }

    private static void geometryAndMovement() {
        var config = new ComboModel.Config();
        near(ComboModel.rayDistance(0, 0, 3.4, 0), 3, 1e-9, "x reach boundary");
        near(ComboModel.rayDistance(0, 0, 0, 0, 0, 3.4), 3, 1e-9, "z reach boundary");
        require(ComboModel.rayDistance(0, 1.48, 3.3, 0) > 3, "elevated eye can lose range");
        var diagonal = ComboModel.advanceRecorded(state(100, 100, false),
                ComboModel.Action.WALK, false, config);
        near(diagonal.a.x, .098 / Math.sqrt(2), 1e-9, "W follows diagonal rendered target");
        near(diagonal.a.z, diagonal.a.x, 1e-9, "symmetric diagonal motion");

        // Independent native calibration m0360: first W+Ctrl travel reports
        // ground factor .13000001 while air factor remains .01999999955.
        var sprintStart = ComboModel.advanceRecorded(state(100,0,false),ComboModel.Action.SPRINT,false,config);
        near(sprintStart.a.x,.1274,1e-7,"ground sprint acceleration applies on its FIRST tick");
        var airStart=state(100,0,false);airStart.a.ground=false;airStart.a.y=1;airStart.a.vy=0;
        airStart=ComboModel.advanceRecorded(airStart,ComboModel.Action.SPRINT,false,config);
        near(airStart.a.x,.0196,1e-7,"first air sprint tick retains old airborne acceleration");
        double airX=airStart.a.x,airV=airStart.a.vx;
        airStart=ComboModel.advanceRecorded(airStart,ComboModel.Action.SPRINT,false,config);
        near(airStart.a.x-airX-airV,.02548,1e-7,"next air tick uses updated airborne sprint factor");

        var start = state(100, 0, false);
        var walk = start;
        for (int i = 0; i < 40; i++) walk = ComboModel.advance(walk, ComboModel.Action.WALK, config);
        near(walk.a.vx, .098 * .54600006 / (1 - .54600006), 1e-7,
                "ground walking converges to its terminal speed");
        require(start.nowMs == 0 && start.a.x == 0 && start.pendingPacketCount() == 0,
                "advancing must not mutate the history snapshot");

        var knocked = state(100, 0, true);
        knocked.scheduleDefenderImpulse(0, .9, .4608, 0);
        knocked = ComboModel.advance(knocked, ComboModel.Action.RELEASE, config);
        near(knocked.b.x, 100.7726, 1e-9, "grounded victim opposes the impulse with W");
        near(knocked.b.vx, .7726 * .54600006, 1e-9, "takeoff tick retains ground friction");
    }

    private static void inputAndSprintSemantics() {
        var config = new ComboModel.Config();
        config.defenderControlAtMs = 150;
        var current = state(100, 0, false);
        for (int i = 0; i < 3; i++)
            current = ComboModel.advanceRecorded(current, ComboModel.Action.RELEASE, false, config);
        require(current.b.x == 100 && current.physicalClicks == 0,
                "initial reaction wait must neither move the probe nor invent clicks");
        current = ComboModel.advanceRecorded(current, ComboModel.Action.RELEASE, false, config);
        require(current.b.x < 100, "known probe start time enables W");

        config.defenderControlAtMs = Long.MAX_VALUE;
        for (int interval : new int[] {100, 101}) {
            config.clickIntervalMs = interval;
            var misses = state(100, 0, false);
            for (int i = 0; i < 40; i++)
                misses = ComboModel.advance(misses, ComboModel.Action.RELEASE, config);
            require(misses.physicalClicks == 20 && misses.airMisses == 20,
                    "short press/release clicks retain their cadence after air misses: " + interval);
        }
        current.alignPhysicalClickClock(1234, config);
        require(current.nextPhysicalClickMs == 1335, "future click clock follows actual own input");

        config = new ComboModel.Config();
        var fresh = ComboModel.advanceRecorded(state(3, 0, true), ComboModel.Action.SPRINT, true, config);
        require(fresh.acceptedHits == 1 && fresh.a.sprint && !fresh.aServerSprint,
                "held Ctrl can restore local sprint after server sprint was consumed");
        var immune = state(3, 0, true);
        immune.a.vx = .2;
        immune.targetVulnerableAtMs = 500;
        immune = ComboModel.advanceRecorded(immune, ComboModel.Action.SPRINT, true, config);
        require(immune.acceptedHits == 0, "immune attack must not become an accepted hit");
        near(immune.a.vx, (.2 * .6 + .1274) * .54600006, 1e-9,
                "immune client contact still applies local sprint slowdown");
    }

    private static void optionalNativeHeadYaw() {
        var config = new ComboModel.Config();
        require(!config.modelHeadYaw, "head-yaw factor defaults off for an isolated native comparison");
        config.ownRttMs = config.opponentRttMs = 0;
        config.modelServerCollisionPush = false;
        var crossing = state(-.6, 0, false);
        // A's head was synchronized before the target crossed behind it. This
        // is initial history, not an outcome injected into subsequent replay.
        crossing.aHeadLookX = 1; crossing.aHeadLookZ = 0;
        var ideal = ComboModel.replayNativeEvent(crossing, 0, ComboModel.NativeEventKind.A,
                ComboModel.Action.RELEASE, true, config);
        near(ideal.lastOurRay, .2, 1e-9, "disabled factor preserves ideal immediate reversal ray");
        require(ideal.airMisses == 0, "disabled factor preserves contact on first reversed click");

        config.modelHeadYaw = true;
        var missed = ComboModel.replayNativeEvent(crossing, 0, ComboModel.NativeEventKind.A,
                ComboModel.Action.RELEASE, true, config);
        require(Double.isInfinite(missed.lastOurRay) && missed.airMisses == 1,
                "old head direction can miss a target only .2m beyond the eye");
        near(missed.aHeadLookX, -1, 1e-9, "living update synchronizes head after the missed click");
        require(crossing.aHeadLookX == 1 && crossing.physicalClicks == 0,
                "head synchronization leaves the replay anchor unchanged");
        var contact = ComboModel.replayNativeEvent(missed, 100, ComboModel.NativeEventKind.A,
                ComboModel.Action.RELEASE, true, config);
        near(contact.lastOurRay, .2, 1e-9, "following click uses the synchronized head");
        require(contact.physicalClicks == 2 && contact.airMisses == 1,
                "head lag does not suppress or add physical clicks");
        var copied = missed.copy();
        copied.aHeadLookX = 0; copied.aHeadLookZ = 1;
        copied.bHeadLookX = 0; copied.bHeadLookZ = -1;
        require(missed.aHeadLookX == -1 && missed.aHeadLookZ == 0
                        && missed.bHeadLookX != copied.bHeadLookX,
                "both clients' head directions are independently copied");

        var reverseB = state(.6, 0, false);
        reverseB.bHeadLookX = 1; reverseB.bHeadLookZ = 0;
        reverseB = ComboModel.replayNativeEvent(reverseB, 0, ComboModel.NativeEventKind.B,
                ComboModel.Action.RELEASE, true, config);
        require(Double.isInfinite(reverseB.lastDefenderRay) && reverseB.defenderAirMisses == 1,
                "defender ray obeys the same old-head rule");
        near(reverseB.bHeadLookX, -1, 1e-9, "defender head synchronizes in its own living update");

        var raised = state(2, 0, false);
        raised.aSeesB.y = raised.aSeesB.goalY = 2;
        // No head-pitch history exists in vanilla getLook: only yaw is lagged.
        require(Double.isInfinite(ComboModel.rayDistanceAlong(0, 1.62, 0, 1, 0, 0, 2, 2, 0)),
                "an old horizontal pitch would miss this raised box");
        var pitched = ComboModel.replayNativeEvent(raised, 0, ComboModel.NativeEventKind.A,
                ComboModel.Action.RELEASE, true, config);
        near(pitched.lastOurRay, ComboModel.rayDistance(0, 0, 0, 2, 2, 0), 1e-9,
                "head-yaw mode still uses the newly requested pitch immediately");
        require(pitched.airMisses == 0, "current pitch reaches the raised target on its first tick");

        var serverYaw = state(10, 0, false);
        serverYaw.aHeadLookX = 0; serverYaw.aHeadLookZ = 1;
        serverYaw = ComboModel.replayNativeEvent(serverYaw, 0, ComboModel.NativeEventKind.A,
                ComboModel.Action.SPRINT, false, config);
        near(serverYaw.a.x, .1274, 1e-7, "travel uses current body aim rather than old head yaw");
        near(serverYaw.a.z, 0, 1e-9, "head yaw cannot steer movement");
        serverYaw = ComboModel.replayNativeEvent(serverYaw, 1, ComboModel.NativeEventKind.SERVER,
                null, false, config);
        near(serverYaw.serverA.lookX, 1, 1e-9, "movement packet/knockback yaw uses current body aim");
        near(serverYaw.serverA.lookZ, 0, 1e-9, "old head yaw is not sent as server look");

        near(ComboModel.rayDistanceAlong(0, 1.62, 0, 12, 0, 0, 3.4, 0, 0), 3, 1e-9,
                "explicit native-look helper normalizes direction magnitude");
        near(ComboModel.rayDistanceAlong(0, 1.62, 0, -1, 0, 0, 0, 0, 0), 0, 1e-9,
                "eye inside expanded box has zero distance even while looking away");
        require(Double.isInfinite(ComboModel.rayDistanceAlong(0, 1.62, 0, 0, 0, 0, 2, 0, 0)),
                "zero direction outside a box cannot invent an intersection");
        config.horizonTicks = 2; config.beamWidth = 3; config.beamDiversity = true;
        ComboModel.choose(crossing, config);
        require(crossing.aHeadLookX == 1 && crossing.aHeadLookZ == 0,
                "planner alternatives preserve the head-history anchor");
    }

    private static ComboModel.State beforeLanding(boolean ours, float fallDistance) {
        var s = state(100, 0, false);
        var body = ours ? s.a : s.b;
        var server = ours ? s.serverA : s.serverB;
        var motion = ours ? s.serverMotionA : s.serverMotionB;
        body.y = server.y = motion.y = .1;
        body.ground = motion.ground = false;
        body.vy = motion.vy = -.2;
        body.vx = .6; motion.vx = -.2;
        if (ours) s.serverFallDistanceA = fallDistance;
        else s.serverFallDistanceB = fallDistance;
        return s;
    }

    private static ComboModel.State landAndProcess(ComboModel.State s, boolean ours, ComboModel.Config c) {
        s = ComboModel.replayNativeEvent(s, 0, ours ? ComboModel.NativeEventKind.A : ComboModel.NativeEventKind.B,
                ComboModel.Action.RELEASE, false, c);
        return ComboModel.replayNativeEvent(s, 0, ComboModel.NativeEventKind.SERVER, null, false, c);
    }

    private static void optionalNativeFallDamage() {
        var c = new ComboModel.Config();
        require(!c.modelFallDamage, "fall factor defaults off for isolated native comparison");
        c.ownRttMs = c.opponentRttMs = 0; c.modelServerCollisionPush = false;
        var off = landAndProcess(beforeLanding(false, 3.2f), false, c);
        require(off.targetFallEvents == 0 && off.targetHurtResistantTicks == 0,
                "disabled factor preserves prior landing behavior");
        c.modelFallDamage = true;
        c.trackerPhase = 1; // First tick does not run ordinary 2-tick position tracking.
        for (boolean ours : new boolean[]{false, true}) {
            var seed = beforeLanding(ours, 3.2f);
            var landed = landAndProcess(seed, ours, c);
            require((ours ? landed.ownFallEvents : landed.targetFallEvents) == 1
                            && (ours ? landed.ownHurtResistantTicks : landed.targetHurtResistantTicks) == 19
                            && (ours ? landed.ownLastDamage : landed.targetLastDamage) == 1,
                    "ceil(3.2-3) fresh fall damage starts ordinary immunity then decrements once");
            near(ours ? landed.ownFallDamage : landed.targetFallDamage, 1, 1e-9,
                    "fall damage is counted separately");
            require(landed.acceptedHits == 0 && landed.receivedHits == 0
                            && landed.damageDealt == 0 && landed.damageReceived == 0,
                    "environmental damage never creates referee PvP score or player damage");
            var motion = ours ? landed.serverMotionA : landed.serverMotionB;
            near(motion.vx, -.2 * .91, 1e-9, "fall does not add base knockback or halve server motion");
            near(motion.vy, -.0784000015258789, 1e-12,
                    "fall retains current latent server vertical motion");
            require(!landed.aVelocityChanged && !landed.bVelocityChanged,
                    "tracker flush clears the flag even off the position-update cadence");
            double x = ours ? landed.a.x : landed.b.x;
            var delivered = ComboModel.replayNativeEvent(landed, 1,
                    ours ? ComboModel.NativeEventKind.A : ComboModel.NativeEventKind.B,
                    ComboModel.Action.RELEASE, false, c);
            double encoded = ((int) (motion.vx * 8000)) / 8000.0;
            near((ours ? delivered.a.x : delivered.b.x) - x, encoded, 1e-9,
                    "fall S12 overwrites client motion with quantized server motion");
            require((ours ? seed.ownFallEvents : seed.targetFallEvents) == 0,
                    "fall counters and queues do not mutate historical snapshots");
            var visible = ComboModel.replayNativeEvent(delivered, 2, ComboModel.NativeEventKind.A,
                    ComboModel.Action.RELEASE, false, c);
            require((ours ? visible.lastPredictedOwnHurtCueMs : visible.lastPredictedHurtCueMs) >= 1,
                    "fresh fall emits ordinary visible hurt despite no player hit");
        }

        // Landing's final downward delta must not be added to the damage input.
        var safe = landAndProcess(beforeLanding(false, 2.95f), false, c);
        require(safe.targetFallEvents == 0 && safe.serverFallDistanceB == 0,
                "2.95 accumulated plus .1 landing drop remains damage-free");
        var threshold = landAndProcess(beforeLanding(false, 3.0f), false, c);
        require(threshold.targetFallEvents == 0, "exact 3-block accumulated fall does no damage");
        var rejected = beforeLanding(false, 3.2f);
        rejected.targetHurtResistantTicks = 15; rejected.targetLastDamage = 8;
        rejected = landAndProcess(rejected, false, c);
        require(rejected.targetFallEvents == 0 && rejected.targetLastDamage == 8
                        && rejected.targetHurtResistantTicks == 14,
                "ordinary hurt resistance rejects lower fall damage without resetting immunity");
        var upgrade = beforeLanding(false, 4.2f);
        upgrade.targetHurtResistantTicks = 15; upgrade.targetLastDamage = 1;
        upgrade = landAndProcess(upgrade, false, c);
        require(upgrade.targetFallEvents == 1 && upgrade.targetFallDamage == 1
                        && upgrade.targetLastDamage == 2 && upgrade.targetHurtResistantTicks == 14,
                "fall damage upgrade applies only the difference and does not reset immunity");
        double priorX = upgrade.b.x, priorV = upgrade.b.vx;
        upgrade = ComboModel.replayNativeEvent(upgrade, 1, ComboModel.NativeEventKind.B,
                ComboModel.Action.RELEASE, false, c);
        near(upgrade.b.x - priorX, priorV, 1e-9, "fall upgrade creates no fresh S12 overwrite");
        upgrade = ComboModel.replayNativeEvent(upgrade, 2, ComboModel.NativeEventKind.A,
                ComboModel.Action.RELEASE, false, c);
        require(upgrade.lastPredictedHurtCueMs == Long.MIN_VALUE,
                "fall upgrade creates no fresh hurt status");

        // Two movement packets share one server task drain: tracker samples the
        // final latent motion, not the motion at the earlier fall callback.
        var laterMotion = beforeLanding(false, 3.2f);
        laterMotion = ComboModel.replayNativeEvent(laterMotion, 0, ComboModel.NativeEventKind.B,
                ComboModel.Action.RELEASE, false, c);
        laterMotion = ComboModel.replayNativeEvent(laterMotion, 1, ComboModel.NativeEventKind.B,
                ComboModel.Action.RELEASE, false, c);
        laterMotion = ComboModel.replayNativeEvent(laterMotion, 10, ComboModel.NativeEventKind.SERVER,
                null, false, c);
        double expected = ((int) (laterMotion.serverMotionB.vx * 8000)) / 8000.0;
        priorX = laterMotion.b.x;
        laterMotion = ComboModel.replayNativeEvent(laterMotion, 11, ComboModel.NativeEventKind.B,
                ComboModel.Action.RELEASE, false, c);
        near(laterMotion.b.x - priorX, expected, 1e-9,
                "deferred dirty flush samples motion after all incoming packets");
        require(expected > -.15, "test distinguishes late flush from immediate -.182 fall velocity");

        var pending = state(3, 0, true);
        pending.targetHurtResistantTicks = 15; pending.targetLastDamage = 1;
        pending.bVelocityChanged = true;
        pending.serverMotionB.vx = -.1; pending.serverMotionB.vy = -.0784;
        var snapshot = pending.copy();
        pending = ComboModel.replayNativeEvent(pending, 0, ComboModel.NativeEventKind.A,
                ComboModel.Action.RELEASE, true, c);
        pending = ComboModel.replayNativeEvent(pending, 0, ComboModel.NativeEventKind.SERVER,
                null, false, c);
        require(pending.acceptedDamageUpgrades == 1 && pending.acceptedFreshHits == 0
                        && pending.damageDealt == 7 && !pending.bVelocityChanged,
                "accepted player upgrade can consume an earlier pending fall velocity flag");
        near(pending.serverMotionB.vx, -.1, 1e-9,
                "outer player attack restores pre-attack server motion after sending dirty upgrade");
        priorX = pending.b.x;
        pending = ComboModel.replayNativeEvent(pending, 1, ComboModel.NativeEventKind.B,
                ComboModel.Action.RELEASE, false, c);
        near(pending.b.x - priorX, .4, 1e-9,
                "dirty sprint upgrade sends old server motion plus sprint bonus, without base KB or duplicate flush");
        require(snapshot.bVelocityChanged && snapshot.serverMotionB.vx == -.1,
                "dirty flag and latent motion remain independent in copied histories");
    }

    private static void latentServerMotionAndCues() {
        var config = new ComboModel.Config();
        config.modelServerCollisionPush = false;
        var current = state(100, 0, true);
        current.scheduleDefenderImpulse(0, .9, .46075, 0);
        for (int i = 0; i < 2; i++)
            current = ComboModel.advanceRecorded(current, ComboModel.Action.RELEASE, false, config);
        near(current.serverMotionB.vy, .41999998688697815, 1e-6,
                "rising client movement can trigger independent server jump motion");
        near(current.serverMotionB.vx, -.2, 1e-6, "server sprint jump follows its own yaw");
        current = ComboModel.advanceRecorded(current, ComboModel.Action.RELEASE, false, config);
        near(current.serverMotionB.vy, .33319999363422365, 1e-6, "server vertical motion decays");
        near(current.serverMotionB.vx, -.182, 1e-6, "server horizontal motion decays");

        // Fixed latent-motion examples from native S12 calibration; these do
        // not read another client or packet while the controller is running.
        current = state(3, 0, true);
        current.serverMotionB.vx = -.0856;
        current.serverMotionB.vy = -.3043;
        double[] kb = ComboModel.predictedKnockback(current, false, config);
        near(kb[0], .357125, 1e-6, "ordinary impulse uses latent server horizontal motion");
        near(kb[1], .24775, 1e-6, "ordinary impulse uses latent server vertical motion");
        current.serverMotionB.vx = 0;
        current.serverMotionB.vy = -.0784;
        current.serverA.lookX = -1;
        current.serverA.lookZ = 0;
        kb = ComboModel.predictedKnockback(current, true, config);
        near(kb[0], -.099875, 1e-6, "last server yaw can reverse the sprint bonus after crossing");
        near(current.copy().serverA.lookX, -1, 1e-9, "copy retains server yaw");

        config.ownRttMs = 0;
        config.opponentRttMs = 200;
        current = ComboModel.advanceRecorded(state(3, 0, true), ComboModel.Action.SPRINT, true, config);
        var cue = current.assimilateFreshHurt(50, config);
        require(cue.applied && cue.matchedPrediction && !cue.needsEarlierReplay,
                "delayed fresh hurt can reconcile a pending impulse");
        require(current.targetVulnerableAtMs == 550, "fresh cue aligns immunity phase");
        near(cue.estimatedVictimArrivalMs, 150, 1e-9, "cue aligns modeled victim delivery");
        require(!current.assimilateFreshHurt(50, config).applied, "same cue is consumed once");
    }

    private static void correctionAndPlanningIsolation() {
        var config = new ComboModel.Config();
        config.defenderControlAtMs = Long.MAX_VALUE;
        var current = state(100, 30, false);
        current.scheduleDefenderImpulse(0, .5, .3, .2);
        var original = current.copy();
        current.translateDefenderTrace(3, 0, 4);
        current = ComboModel.advanceRecorded(current, ComboModel.Action.RELEASE, false, config);
        near(current.b.vx, .5 * .54600006, 1e-9, "translation must not alter queued x impulse");
        near(current.b.vz, .2 * .54600006, 1e-9, "translation must not alter queued z impulse");
        near(original.b.x, 100, 1e-9, "copy body remains independent");
        require(original.pendingPacketCount() == 1, "copy queue remains independent");

        double targetView = current.aSeesB.x;
        double ownServer = current.serverA.x;
        double remoteView = current.bSeesA.x;
        current.correctOwn(current.a.x + 2, 0, current.a.z, .12, -.0784, .03, true, false);
        near(current.serverA.x, ownServer + 2, 1e-9, "own correction translates server estimate");
        near(current.bSeesA.x, remoteView + 2, 1e-9, "own correction translates defender view");
        near(current.aSeesB.x, targetView, 1e-9, "own correction does not translate the other body");
        double previousBody = current.b.x;
        current.correctObservedDefender(current.aSeesB.x + 3, current.aSeesB.y, current.aSeesB.z);
        near(current.b.x, previousBody + 3, 1e-9, "visible residual translates latent target history");

        var before = current.copy();
        var decision = ComboModel.choose(current, config);
        require(decision.plan.size() == config.horizonTicks, "planner returns a complete horizon");
        require(current.nowMs == before.nowMs && current.acceptedHits == before.acceptedHits
                        && current.a.x == before.a.x && current.b.z == before.b.z
                        && current.pendingPacketCount() == before.pendingPacketCount(),
                "planning alternatives must not mutate the observation anchor");
    }

    private static void confirmedHitReplay() {
        var config = new ComboModel.Config();
        config.ownRttMs = config.opponentRttMs = 0;
        config.serverPhaseMs = 25;
        config.opponentPhaseMs = 45;
        config.defenderControlAtMs = Long.MAX_VALUE;
        config.modelServerCollisionPush = false;

        // The delayed cue proves a hit even if the old model wrongly predicted
        // impossible range or immunity. Rewind, then reconstruct its event-time
        // knockback; at zero RTT it must reach the victim during replay.
        var historical = state(100, 0, false);
        historical.targetVulnerableAtMs = 10000;
        require(historical.scheduleConfirmedHit(125, config), "new confirmation schedules once");
        require(!historical.scheduleConfirmedHit(125, config), "pending confirmation is idempotent");
        historical.aServerSprint = true; // Learned by replay AFTER scheduling.
        for (int i = 0; i < 3; i++)
            historical = ComboModel.advanceRecorded(historical, ComboModel.Action.RELEASE, false, config);
        require(historical.acceptedHits == 1 && historical.lastPredictedHitMs == 125,
                "historical confirmation executes at its modeled server tick");
        require(historical.lastPredictedHitSprint && historical.lastPredictedImpulseX > .8,
                "confirmed impulse uses event-time server sprint, not scheduling-time sprint");
        require(historical.b.y > .4 && historical.b.vy > 0 && historical.targetVulnerableAtMs == 625,
                "zero-RTT replay applies the previously missed impulse and cooldown");
        require(!historical.scheduleConfirmedHit(125, config), "consumed confirmation remains idempotent");

        var duplicate = state(3, 0, false);
        duplicate.scheduleConfirmedHit(125, config);
        for (int tick = 0; tick < 5; tick++) {
            if (tick == 4) {
                // Remove ordinary immunity to isolate ledger suppression AFTER
                // consumption. A local contact still incurs its native slowdown.
                duplicate.targetVulnerableAtMs = 0;
                duplicate.aSeesB = new ComboModel.View(3, 0, 0);
                duplicate.a.sprint = true;
                duplicate.a.vx = .2;
            }
            duplicate = ComboModel.advanceRecorded(duplicate, ComboModel.Action.RELEASE,
                    (tick & 1) == 0, config);
        }
        require(duplicate.acceptedHits == 1 && duplicate.physicalClicks == 3,
                "ordinary attacks at confirmed time +/-100ms do not duplicate the hit");
        near(duplicate.a.vx, .2 * .6 * .54600006, 1e-9,
                "confirmed-hit deduplication must not suppress local physical attack effects");
        duplicate = ComboModel.advanceRecorded(duplicate, ComboModel.Action.RELEASE, false, config);
        duplicate.aSeesB = new ComboModel.View(3, 0, 0);
        duplicate = ComboModel.advanceRecorded(duplicate, ComboModel.Action.RELEASE, true, config);
        require(duplicate.acceptedHits == 2, "ordinary attacks outside confirmation window remain possible");

        var original = state(100, 0, false);
        original.scheduleConfirmedHit(125, config);
        var copied = original.copy();
        require(!copied.scheduleConfirmedHit(125, config), "copy retains pending ledger entries");
        copied.scheduleConfirmedHit(625, config);
        for (int tick = 0; tick < 13; tick++) {
            original = ComboModel.advanceRecorded(original, ComboModel.Action.RELEASE, false, config);
            copied = ComboModel.advanceRecorded(copied, ComboModel.Action.RELEASE, false, config);
        }
        require(original.acceptedHits == 1 && copied.acceptedHits == 2,
                "multiple chronological confirmations and their queues are independently copied");
        require(!copied.scheduleConfirmedHit(125, config) && !copied.scheduleConfirmedHit(625, config),
                "every consumed confirmation remains in the replay ledger");
        boolean rejected = false;
        try { copied.scheduleConfirmedHit(300, config); }
        catch (IllegalArgumentException expected) { rejected = true; }
        require(rejected, "new past confirmation requires a historical rewind");

        var conflicting = ComboModel.advanceRecorded(state(3, 0, false),
                ComboModel.Action.RELEASE, true, config);
        rejected = false;
        try { conflicting.scheduleConfirmedHit(75, config); }
        catch (IllegalStateException expected) { rejected = true; }
        require(rejected, "already integrated conflicting prediction requires an earlier rewind");
    }

    private static void freshContactEdgeScoring() {
        var config = new ComboModel.Config();
        config.ownRttMs = 100;
        config.defenderControlAtMs = Long.MAX_VALUE;
        config.opportunityPenalty = config.penetrationPenalty = config.switchPenalty = 0;
        var queued = ComboModel.advanceRecorded(state(3, 0, false), ComboModel.Action.RELEASE, true, config);
        // The local target has moved since the click. Scoring must use the ray
        // carried by the attack packet, not its new range at server processing.
        queued.aSeesB = new ComboModel.View(3.4, 0, 0);
        var baseline = ComboModel.advanceRecorded(queued, ComboModel.Action.RELEASE, false, config);
        config.contactEdgePenalty = 500;
        var penalized = ComboModel.advanceRecorded(queued, ComboModel.Action.RELEASE, false, config);
        require(baseline.acceptedHits == 1 && penalized.acceptedHits == 1,
                "optional contact scoring does not change hit acceptance");
        near(baseline.accumulatedReward - penalized.accumulatedReward, 500 * .4 * .4, 1e-7,
                "fresh contact penalty uses the recorded 2.6-block local ray");
        var immune = state(3, 0, false);
        immune.targetVulnerableAtMs = 10000;
        immune = ComboModel.advanceRecorded(immune, ComboModel.Action.RELEASE, true, config);
        immune = ComboModel.advanceRecorded(immune, ComboModel.Action.RELEASE, false, config);
        require(immune.acceptedHits == 0 && immune.physicalClicks == 1,
                "contact scoring does not filter immune physical clicks");
        near(immune.accumulatedReward, 0, 1e-9, "immune contact does not pay a fresh-hit penalty");
    }

    private static ComboModel.Config clickingConfig() {
        var config = new ComboModel.Config();
        config.defenderClicks = true;
        config.ownRttMs = config.opponentRttMs = 0;
        config.serverPhaseMs = 25;
        config.opponentPhaseMs = 10;
        config.modelServerCollisionPush = false;
        config.opportunityPenalty = config.penetrationPenalty = config.switchPenalty = 0;
        return config;
    }

    private static void defenderClickClockAndLocalDrag() {
        var config = clickingConfig();
        for (int interval : new int[] {100, 101}) {
            config.defenderClickIntervalMs = interval;
            var far = state(1000, 0, false);
            for (int i = 0; i < 40; i++)
                far = ComboModel.advanceRecorded(far, ComboModel.Action.RELEASE, false, config);
            require(far.defenderPhysicalClicks == 20 && far.defenderAirMisses == 20,
                    "defender short press/release cadence survives air misses: " + interval);
            require(far.receivedHits == 0, "air misses cannot become incoming server hits");
        }
        config.defenderClickIntervalMs = 100;
        config.defenderControlAtMs = 150;
        config.defenderFirstClickAtMs = 200;
        var waiting = state(1000, 0, false);
        for (int i = 0; i < 3; i++)
            waiting = ComboModel.advanceRecorded(waiting, ComboModel.Action.RELEASE, false, config);
        require(waiting.b.x == 1000 && waiting.defenderPhysicalClicks == 0,
                "defender neither moves nor clicks during its initial wait");
        waiting = ComboModel.advanceRecorded(waiting, ComboModel.Action.RELEASE, false, config);
        require(waiting.b.x < 1000 && waiting.defenderPhysicalClicks == 0,
                "movement and the independently hypothesized first click have separate times");
        waiting = ComboModel.advanceRecorded(waiting, ComboModel.Action.RELEASE, false, config);
        require(waiting.defenderPhysicalClicks == 1, "defender first click phase is honored");

        config = clickingConfig();
        var immune = state(3, 0, false);
        immune.b.vx = -.2;
        immune.ownVulnerableAtMs = 10000;
        immune = ComboModel.advanceRecorded(immune, ComboModel.Action.RELEASE, false, config);
        require(immune.receivedHits == 0 && immune.defenderPhysicalClicks == 1,
                "A immunity rejects damage without filtering the defender's click");
        near(immune.b.vx, (-.2 * .6 - .1274) * .54600006, 1e-9,
                "immune defender contact still drags its local sprint velocity");
        require(immune.b.sprint && immune.bLastSentSprint && immune.bServerSprint,
                "held Ctrl restores local sprint while an immune server contact preserves its flag");

        var fresh = state(3, 0, false);
        fresh.b.vx = -.2;
        fresh = ComboModel.advanceRecorded(fresh, ComboModel.Action.RELEASE, false, config);
        require(fresh.receivedHits == 1 && fresh.b.sprint && fresh.bLastSentSprint && !fresh.bServerSprint,
                "fresh B hit consumes server sprint; local Ctrl restoration sends no new START");
        require(fresh.a.y == 0, "zero-RTT incoming velocity waits for the next A client tick");
        near(fresh.accumulatedReward, -config.receivedHitPenalty, 1e-9,
                "incoming fresh hit enters the planner's score");
        fresh = ComboModel.advanceRecorded(fresh, ComboModel.Action.RELEASE, false, config);
        require(fresh.a.x < -.8 && fresh.a.y > .4 && fresh.lastPredictedOwnHurtCueMs == 50,
                "A receives fresh sprint knockback and its own visible hurt cue");
        require(!fresh.bServerSprint, "continued Ctrl does not recreate consumed server sprint");

        config.defenderClicks = false;
        var probe = state(3, 0, false);
        probe.b.vx = -.2;
        probe = ComboModel.advanceRecorded(probe, ComboModel.Action.RELEASE, false, config);
        near(probe.b.vx, (-.2 - .1274) * .54600006, 1e-9, "default probe has no attack slowdown");
        require(probe.receivedHits == 0 && probe.defenderPhysicalClicks == 0,
                "disabled defender attacks preserve probe behavior");
        config.defenderClickIntervalMs = 49;
        boolean rejected = false;
        try { config.validate(); } catch (IllegalArgumentException expected) { rejected = true; }
        require(rejected, "tick-level model cannot exceed twenty physical CPS");
    }

    private static void incomingKnockbackAndIndependentImmunity() {
        var config = clickingConfig();
        var zAxis = state(0, 3, false);
        zAxis.serverMotionA.vx = .2;
        zAxis.serverMotionA.vy = -.3043;
        zAxis.serverMotionA.vz = -.1;
        zAxis.serverMotionB.vx = 2; // Hitter motion must not replace victim motion.
        double[] incoming = ComboModel.predictedIncomingKnockback(zAxis, false, config);
        near(incoming[0], .1, 1e-6, "incoming KB retains half A's server lateral motion");
        near(incoming[1], .24775, 1e-6, "incoming KB uses A's server vertical motion");
        near(incoming[2], -.45, 1e-6, "incoming base KB points away from B in z");
        incoming = ComboModel.predictedIncomingKnockback(zAxis, true, config);
        near(incoming[2], -.95, 1e-6, "incoming sprint bonus follows B server yaw");
        zAxis.serverMotionA.vz = 0;
        zAxis.serverB.lookZ = 1;
        incoming = ComboModel.predictedIncomingKnockback(zAxis, true, config);
        near(incoming[2], .099875, 1e-6, "stale B yaw can reverse the incoming sprint bonus");

        var trade = ComboModel.advanceRecorded(state(3, 0, true), ComboModel.Action.SPRINT, true, config);
        require(trade.acceptedHits == 1 && trade.receivedHits == 1,
                "fresh outgoing and incoming hits have independent immunity windows");
        require(trade.targetVulnerableAtMs == 525 && trade.ownVulnerableAtMs == 525,
                "each victim receives its own 500ms fresh-hit deadline");
        require(!trade.aServerSprint && !trade.bServerSprint,
                "both server sprint flags can be consumed in one trade");
        require(trade.lastPredictedImpulseX > .8 && trade.lastReceivedImpulseX < -.8,
                "the two fresh impulses point in opposite directions");
        require(trade.unansweredHits == 0 && trade.longestUnansweredHits == 1,
                "incoming accepted hit ends the unanswered-hit streak");
        near(trade.accumulatedReward, config.hitReward - config.receivedHitPenalty, 1e-9,
                "trade score includes both accepted hits");

        config.opponentRttMs = 100;
        config.ownRttMs = 200;
        var delayed = state(3, 0, false);
        delayed = ComboModel.advanceRecorded(delayed, ComboModel.Action.RELEASE, false, config);
        require(delayed.receivedHits == 0, "B attack waits for B-to-server latency");
        delayed = ComboModel.advanceRecorded(delayed, ComboModel.Action.RELEASE, false, config);
        require(delayed.receivedHits == 1 && delayed.lastPredictedReceivedHitMs == 75,
                "B attack is processed at the first eligible server tick");
        for (int i = 0; i < 2; i++)
            delayed = ComboModel.advanceRecorded(delayed, ComboModel.Action.RELEASE, false, config);
        require(delayed.a.y == 0 && delayed.lastPredictedOwnHurtCueMs == Long.MIN_VALUE,
                "incoming impulse waits for server-to-A latency");
        delayed = ComboModel.advanceRecorded(delayed, ComboModel.Action.RELEASE, false, config);
        require(delayed.a.y > .4 && delayed.lastPredictedOwnHurtCueMs == 200,
                "incoming velocity and visible cue arrive on A's next eligible tick");
    }

    private static void confirmedIncomingReplayAndCopies() {
        var config = clickingConfig();
        var pending = state(3, 0, false);
        require(pending.scheduleConfirmedIncomingHit(125, config), "incoming confirmation schedules once");
        require(!pending.scheduleConfirmedIncomingHit(125, config), "incoming schedule is idempotent");
        var original = pending.copy();
        for (int tick = 0; tick < 5; tick++) {
            if (tick == 4) {
                pending.ownVulnerableAtMs = 0;
                pending.a = new ComboModel.Body(0, 0, 0, 0, -.0784, 0, true, false);
                pending.b = new ComboModel.Body(3, 0, 0, 0, -.0784, 0, true, true);
                pending.aSeesB = new ComboModel.View(3, 0, 0);
                pending.bSeesA = new ComboModel.View(0, 0, 0);
            }
            // A's contact at the confirmed B hit time must remain independent.
            pending = ComboModel.advanceRecorded(pending, ComboModel.Action.RELEASE, tick == 2, config);
        }
        require(pending.receivedHits == 1 && pending.acceptedHits == 1,
                "incoming deduplication suppresses only B's attacks before/after confirmation");
        require(!pending.scheduleConfirmedIncomingHit(125, config),
                "consumed incoming confirmation remains idempotent");
        require(original.receivedHits == 0 && original.defenderPhysicalClicks == 0,
                "replay preserves the copied anchor and defender clock");
        require(!original.scheduleConfirmedIncomingHit(125, config), "copy preserves incoming ledger");
        original.scheduleConfirmedHit(125, config);
        original.scheduleConfirmedIncomingHit(625, config);
        config.defenderClicks = false;
        for (int tick = 0; tick < 13; tick++)
            original = ComboModel.advanceRecorded(original, ComboModel.Action.RELEASE, false, config);
        require(original.acceptedHits == 1 && original.receivedHits == 2,
                "outgoing and multiple incoming confirmed events have independent ledgers");
        require(pending.receivedHits == 1, "new copied ledger entries do not change another state");

        config = clickingConfig();
        config.horizonTicks = 6;
        config.beamWidth = 8;
        var initial = state(3, 0, true);
        var decision = ComboModel.choose(initial, config);
        require(initial.receivedHits == 0 && initial.defenderPhysicalClicks == 0,
                "planning against clicking hold-W leaves the initial estimate untouched");
        require(decision.predictedReceivedHits == decision.predictedEnd.receivedHits - initial.receivedHits,
                "planner exposes incoming hit count alongside its outgoing count");
        require(decision.predictedEnd.defenderPhysicalClicks > 0,
                "all search branches include the configured clicking opponent");
    }

    private static ComboModel.Config damageConfig() {
        var config = clickingConfig();
        config.defenderClicks = false;
        config.defenderControlAtMs = Long.MAX_VALUE;
        config.opponentPhaseMs = 45;
        config.missPenalty = 0;
        return config;
    }

    private static ComboModel.State fallingContact(boolean sprint) {
        var current = state(3, 0, sprint);
        current.a.y = current.serverA.y = current.serverMotionA.y = 1;
        current.a.ground = current.serverMotionA.ground = false;
        current.a.vy = -.05;
        current.serverFallDistanceA = .1f;
        return current;
    }

    private static void criticalDamageAndImmunity() {
        var config = damageConfig();
        var upgrade = fallingContact(false);
        upgrade.targetHurtResistantTicks = 15;
        upgrade.targetLastDamage = 8;
        var anchor = upgrade.copy();
        upgrade = ComboModel.advanceRecorded(upgrade, ComboModel.Action.RELEASE, true, config);
        require(upgrade.acceptedHits == 1 && upgrade.acceptedDamageUpgrades == 1
                        && upgrade.acceptedFreshHits == 0, "12 after 8 during immunity is one scored upgrade");
        near(upgrade.damageDealt, 4, 1e-9, "upgrade deals only the positive damage difference");
        near(upgrade.accumulatedReward, config.hitReward, 1e-9, "upgrade receives the full accepted-hit score");
        require(upgrade.targetLastDamage == 12 && upgrade.targetHurtResistantTicks == 14
                        && upgrade.targetVulnerableAtMs == 0,
                "upgrade changes lastDamage but neither restarts immunity nor its deadline");
        require(upgrade.lastPredictedHitMs == Long.MIN_VALUE
                        && upgrade.lastPredictedHurtCueMs == Long.MIN_VALUE,
                "upgrade does not manufacture a fresh-hit timestamp or visible hurt cue");
        config.contactEdgePenalty = 1000;
        var edgeUpgrade = ComboModel.advanceRecorded(anchor, ComboModel.Action.RELEASE, true, config);
        near(edgeUpgrade.accumulatedReward, config.hitReward, 1e-9,
                "fresh-contact edge cost does not penalize an immune damage upgrade");
        config.contactEdgePenalty = 0;
        require(anchor.targetLastDamage == 8 && anchor.targetHurtResistantTicks == 15,
                "copy keeps independent damage history and immunity counters");

        var equal = fallingContact(false);
        equal.targetHurtResistantTicks = 15;
        equal.targetLastDamage = 12;
        equal = ComboModel.advanceRecorded(equal, ComboModel.Action.RELEASE, true, config);
        require(equal.acceptedHits == 0 && equal.damageDealt == 0,
                "equal critical damage is rejected during the protected half-window");

        for (int ticks : new int[] {11, 10}) {
            var boundary = state(3, 0, false);
            boundary.targetHurtResistantTicks = ticks;
            boundary.targetLastDamage = 12;
            boundary = ComboModel.advanceRecorded(boundary, ComboModel.Action.RELEASE, true, config);
            require(boundary.acceptedHits == (ticks == 10 ? 1 : 0),
                    "native hurtResistantTime comparison is strictly greater than ten: " + ticks);
            if (ticks == 10) require(boundary.targetLastDamage == 8
                            && boundary.targetHurtResistantTicks == 19 && boundary.acceptedFreshHits == 1,
                    "ordinary damage starts a new full window at exactly ten, even after a critical");
        }

        // Victim latency keeps the local target stationary while the native
        // server timer is checked independently of geometry/knockback arrival.
        config.opponentRttMs = 2000;
        var cycle = ComboModel.advanceRecorded(state(3, 0, false), ComboModel.Action.RELEASE, true, config);
        for (int i = 0; i < 8; i++)
            cycle = ComboModel.advanceRecorded(cycle, ComboModel.Action.RELEASE, false, config);
        require(cycle.targetHurtResistantTicks == 11 && cycle.nowMs == 450,
                "fresh damage decrements once per server tick, including its processing tick");
        var tooSoon = ComboModel.advanceRecorded(cycle, ComboModel.Action.RELEASE, true, config);
        require(tooSoon.acceptedHits == 1, "450ms after a fresh hit remains protected");
        cycle = ComboModel.advanceRecorded(cycle, ComboModel.Action.RELEASE, false, config);
        cycle = ComboModel.advanceRecorded(cycle, ComboModel.Action.RELEASE, true, config);
        require(cycle.acceptedFreshHits == 2 && cycle.lastPredictedHitMs == 525,
                "500ms after a fresh hit starts the next native damage window");
        var stalled = ComboModel.advanceRecorded(state(3, 0, false), ComboModel.Action.RELEASE, true, config);
        stalled.nowMs += 1000;
        stalled = ComboModel.advanceRecorded(stalled, ComboModel.Action.RELEASE, true, config);
        require(stalled.acceptedHits == 1 && stalled.targetHurtResistantTicks == 18,
                "wall-clock delay without server ticks does not expire native immunity");
    }

    private static void criticalFallHistoryAndSprintUpgrade() {
        var config = damageConfig();
        var descent = state(100, 0, false);
        descent.a.y = descent.serverA.y = descent.serverMotionA.y = 1;
        descent.a.ground = descent.serverMotionA.ground = false;
        descent.a.vy = -.1;
        descent.serverMotionA.vy = .2; // Deliberately opposite latent vertical velocity.
        descent = ComboModel.advanceRecorded(descent, ComboModel.Action.RELEASE, false, config);
        near(descent.serverFallDistanceA, .1, 1e-6, "reported descent accumulates native float fallDistance");
        require(descent.serverMotionA.vy > 0 && ComboModel.predictedAttackDamage(descent, true, config) == 12,
                "critical eligibility follows reported fall history, not latent motionY sign");
        descent.a.y = .01;
        descent.a.vy = -.1;
        descent = ComboModel.advanceRecorded(descent, ComboModel.Action.RELEASE, false, config);
        require(descent.serverFallDistanceA == 0 && ComboModel.predictedAttackDamage(descent, true, config) == 8,
                "a reported on-ground landing clears falling critical eligibility");

        var rising = state(100, 0, false);
        rising.a.y = rising.serverA.y = rising.serverMotionA.y = 1;
        rising.a.ground = rising.serverMotionA.ground = false;
        rising.a.vy = .1;
        rising.serverMotionA.vy = -.3;
        rising = ComboModel.advanceRecorded(rising, ComboModel.Action.RELEASE, false, config);
        require(rising.serverMotionA.vy < 0 && rising.serverFallDistanceA == 0
                        && ComboModel.predictedAttackDamage(rising, true, config) == 8,
                "server gravity alone cannot invent fallDistance for a rising client");

        var sprint = fallingContact(true);
        sprint.targetHurtResistantTicks = 15;
        sprint.targetLastDamage = 8;
        sprint.serverMotionB.vx = .02;
        sprint.serverMotionB.vy = -.2;
        var blocked = sprint.copy();
        blocked.targetLastDamage = 12;
        sprint = ComboModel.advanceRecorded(sprint, ComboModel.Action.SPRINT, true, config);
        blocked = ComboModel.advanceRecorded(blocked, ComboModel.Action.SPRINT, true, config);
        require(sprint.acceptedDamageUpgrades == 1 && !sprint.aServerSprint
                        && sprint.a.sprint && sprint.aLastSentSprint,
                "sprinting critical upgrades consume server sprint while held Ctrl restores local sprint");
        near(sprint.serverMotionB.vx, .52, 1e-9, "upgrade retains sprint bonus in victim server motion");
        near(sprint.serverMotionB.vy, -.1, 1e-9, "upgrade adds only sprint vertical bonus, not fresh base KB");
        near(sprint.b.x, blocked.b.x, 1e-9, "upgrade sends no victim S12 horizontal impulse");
        near(sprint.b.y, blocked.b.y, 1e-9, "upgrade sends no victim S12 vertical impulse");
        require(sprint.lastPredictedHurtCueMs == Long.MIN_VALUE,
                "sprint bonus does not turn upgrade damage into a fresh hurt animation");
        for (int i = 0; i < 3; i++) {
            sprint = ComboModel.advanceRecorded(sprint, ComboModel.Action.RELEASE, false, config);
            blocked = ComboModel.advanceRecorded(blocked, ComboModel.Action.RELEASE, false, config);
        }
        near(sprint.b.x, blocked.b.x, 1e-9, "player tracker does not later deliver upgrade server velocity");
        near(sprint.b.y, blocked.b.y, 1e-9, "upgrade retains client trajectory after tracker updates");

        var freshCritical = fallingContact(true);
        freshCritical = ComboModel.advanceRecorded(freshCritical, ComboModel.Action.SPRINT, true, config);
        require(freshCritical.acceptedFreshHits == 1 && freshCritical.targetLastDamage == 12
                        && freshCritical.lastPredictedImpulseX > .8 && freshCritical.b.y > .4,
                "fresh sprint critical still sends ordinary sprint knockback and full damage");
        near(freshCritical.damageDealt, 12, 1e-9, "fresh critical deals its full damage");
    }

    private static void incomingCriticalAndConfirmedFresh() {
        var config = clickingConfig();
        var incoming = state(3, 0, false);
        incoming.b.y = incoming.serverB.y = incoming.serverMotionB.y = 1;
        incoming.b.ground = incoming.serverMotionB.ground = false;
        incoming.serverFallDistanceB = .1f;
        incoming.ownHurtResistantTicks = 15;
        incoming.ownLastDamage = 8;
        incoming.unansweredHits = 3;
        incoming = ComboModel.advanceRecorded(incoming, ComboModel.Action.RELEASE, false, config);
        require(incoming.receivedHits == 1 && incoming.receivedDamageUpgrades == 1
                        && incoming.receivedFreshHits == 0 && incoming.ownLastDamage == 12,
                "clicking defender can score the same critical immunity upgrade");
        require(incoming.ownHurtResistantTicks == 14 && incoming.unansweredHits == 0,
                "incoming upgrade ends an unanswered score streak without resetting immunity");
        near(incoming.damageReceived, 4, 1e-9, "incoming upgrade damage is the difference");
        near(incoming.accumulatedReward, -config.receivedHitPenalty, 1e-9,
                "incoming upgrade receives the full score penalty");
        require(incoming.lastPredictedReceivedHitMs == Long.MIN_VALUE
                        && incoming.lastPredictedOwnHurtCueMs == Long.MIN_VALUE,
                "incoming upgrade cannot manufacture our fresh hurt cue");

        config = damageConfig();
        var confirmed = fallingContact(false);
        confirmed.targetHurtResistantTicks = 15;
        confirmed.targetLastDamage = 12;
        confirmed.scheduleConfirmedHit(25, config);
        confirmed = ComboModel.advanceRecorded(confirmed, ComboModel.Action.RELEASE, false, config);
        require(confirmed.acceptedFreshHits == 1 && confirmed.acceptedDamageUpgrades == 0
                        && confirmed.targetLastDamage == 12 && confirmed.targetHurtResistantTicks == 19,
                "hurt-cue confirmation forces fresh damage while retaining reconstructed critical damage");
        var copied = confirmed.copy();
        copied.serverFallDistanceA = 2;
        copied.targetLastDamage = 8;
        require(confirmed.serverFallDistanceA != 2 && confirmed.targetLastDamage == 12,
                "confirmation snapshot owns independent critical and immunity history");
    }

    private static void optionalSearchDiversity() {
        var config = clickingConfig();
        require(!config.beamDiversity, "diversity defaults off for controlled native comparisons");
        config.horizonTicks = 20;
        config.beamWidth = 24;
        config.minimumActionTicks = 1;
        var initial = state(6, 0, false);
        initial.scheduleAttackerImpulse(400, -.4, .36, 0);
        var original = initial.copy();
        config.beamDiversity = true;
        var diverse = ComboModel.choose(initial, config);
        require(diverse.rootActionsRetained == 3,
                "a sufficiently wide diverse beam retains all three legal first actions through the horizon");
        require(diverse.finalBeamSize > 0 && diverse.finalBeamSize <= config.beamWidth
                        && diverse.distinctStateBins > 1 && diverse.distinctStateBins <= diverse.finalBeamSize,
                "diversity returns a bounded beam with physically distinct alternatives");
        require(diverse.plan.size() == config.horizonTicks
                        && diverse.predictedEnd.nowMs == initial.nowMs + 50L * config.horizonTicks,
                "root quotas do not shorten the requested planning horizon");
        require(initial.pendingPacketCount() == original.pendingPacketCount()
                        && initial.a.x == original.a.x && initial.b.x == original.b.x
                        && initial.acceptedHits == 0 && initial.receivedHits == 0,
                "diversity exploration preserves the anchor and its pending incoming impulse");

        // A real ongoing key dwell can make two roots illegal. Exploration
        // must respect that input constraint instead of inventing alternatives.
        initial = ComboModel.advanceRecorded(state(6, 0, false), ComboModel.Action.WALK, false, config);
        config.minimumActionTicks = 2;
        var constrained = ComboModel.choose(initial, config);
        require(constrained.action == ComboModel.Action.WALK && constrained.rootActionsRetained == 1,
                "root protection cannot bypass the configured minimum action duration");

        // With fewer slots than legal roots, global rank still decides which
        // roots survive; enum iteration order must not force an arbitrary one.
        config.minimumActionTicks = 1;
        config.beamWidth = 1;
        initial = state(6, 0, false);
        var narrow = ComboModel.choose(initial, config);
        config.beamDiversity = false;
        var ranked = ComboModel.choose(initial, config);
        require(narrow.finalBeamSize == 1 && narrow.rootActionsRetained == 1
                        && narrow.plan.equals(ranked.plan),
                "a one-slot diverse beam respects the width and retains rank-based behavior");
        near(narrow.score, ranked.score, 1e-9, "disabled and one-slot search have the same score");
    }

    private static void completeMacrosAndWarmPlans() {
        var config = new ComboModel.Config();
        require(!config.macroSeeds, "complete macro candidates default off for controlled comparisons");
        config.defenderClicks = true;
        config.defenderControlAtMs = 150;
        config.clickIntervalMs = config.defenderClickIntervalMs = 105;
        config.minimumActionTicks = 1;
        config.horizonTicks = 60;
        config.beamWidth = 24;
        config.opportunityPenalty = config.penetrationPenalty = config.contactEdgePenalty = 0;
        // Documented pure-model case: both initially stationary, 6m apart,
        // declared RTT 20/100, both clocks start at 150ms and then click at105ms.
        var initial = new ComboModel.State(150,
                new ComboModel.Body(0, 0, 0, -.0784, true, false),
                new ComboModel.Body(6, 0, 0, -.0784, true, false),
                new ComboModel.View(6, 0), new ComboModel.View(0, 0),
                new ComboModel.View(0, 0), new ComboModel.View(6, 0));
        var hold = initial;
        for (int tick = 0; tick < config.horizonTicks; tick++)
            hold = ComboModel.advance(hold, ComboModel.Action.SPRINT, config);
        var ranked = ComboModel.choose(initial, config);
        require(ranked.planSource.equals("beam"), "disabled macro/warm candidates preserve the beam source");
        config.macroSeeds = true;
        var seeded = ComboModel.choose(initial, config);
        require(seeded.planSource.startsWith("macro:") && seeded.score > ranked.score,
                "a complete delayed-payoff opening can beat the final ranked beam");
        require(seeded.predictedHits - seeded.predictedReceivedHits > hold.acceptedHits - hold.receivedHits,
                "macro opening beats all-SPRINT on the documented three-second score case");
        require(initial.nowMs == 150 && initial.pendingPacketCount() == 0
                        && initial.acceptedHits == 0 && initial.receivedHits == 0,
                "complete rollout candidates do not mutate their shared input anchor");
        require(seeded.plan.size() == 60 && seeded.predictedEnd.nowMs == 3150,
                "macro and beam candidates receive the same complete horizon");

        // Supplying that same plan as a warm candidate must retain the score
        // and choose it on an exact tie, even when macro generation is disabled.
        config.macroSeeds = false;
        var warmInputs = new ArrayList<>(seeded.plan);
        var warmed = ComboModel.choose(initial, config, warmInputs);
        require(warmed.planSource.equals("warm") && warmed.plan.equals(seeded.plan),
                "a full warm plan survives intermediate pruning and wins an equal-score tie");
        near(warmed.score, seeded.score, 1e-9, "warm candidate uses the same rollout/scoring rules");
        warmInputs.set(0, ComboModel.Action.WALK);
        require(warmed.plan.get(0) == seeded.plan.get(0), "returned plan does not alias the caller's list");

        // Reuse after one issued tick: caller shifts once and the implementation
        // extends the final action, rather than accidentally replaying the first.
        var afterFirst = ComboModel.advance(initial, seeded.plan.get(0), config);
        var shifted = seeded.plan.subList(1, seeded.plan.size());
        var continued = ComboModel.choose(afterFirst, config, shifted);
        var expected = ComboModel.advance(afterFirst, shifted, config);
        expected = ComboModel.advance(expected, shifted.get(shifted.size() - 1), config);
        require(continued.planSource.equals("warm") && continued.plan.get(0) == shifted.get(0),
                "already shifted warm plan begins with the next input and holds its last action");
        near(continued.predictedEnd.a.x, expected.a.x, 1e-9, "shifted warm rollout has no duplicated first tick");
        require(continued.predictedEnd.acceptedHits == expected.acceptedHits
                        && continued.predictedEnd.receivedHits == expected.receivedHits,
                "shifted warm rollout retains the same predicted score events");

        config.horizonTicks = 6;
        config.minimumActionTicks = 3;
        config.macroSeeds = true;
        var dwell = ComboModel.advanceRecorded(state(6, 0, false), ComboModel.Action.WALK, false, config);
        var restricted = ComboModel.choose(dwell, config, List.of(ComboModel.Action.RELEASE));
        require(restricted.action == ComboModel.Action.WALK && restricted.planSource.equals("beam"),
                "macro and warm candidates cannot bypass an existing initial action dwell");

        // Every returned transition must also respect dwell later in a plan,
        // including short release windows and a switch back to sprint/walk.
        config.minimumActionTicks = 4;
        config.horizonTicks = 20;
        var constrained = ComboModel.choose(initial, config);
        var replay = initial;
        for (var action : constrained.plan) {
            require(replay.actionTicks == 0 || replay.actionTicks >= config.minimumActionTicks
                            || action == replay.previousAction,
                    "complete candidate respects minimum action duration at every transition");
            replay = ComboModel.advance(replay, action, config);
        }
        config.horizonTicks = 1;
        var tiny = ComboModel.choose(initial, config, List.of());
        require(tiny.plan.size() == 1 && tiny.planSource.equals("beam"),
                "short horizons and empty warm plans need no complete pause/resume candidate");
    }

    private static void defenderWtapLatencyAndControls() {
        for (int[] ping : new int[][]{{0, 0}, {20, 100}, {100, 20}}) {
            var config = clickingConfig();
            require(!config.defenderWtap, "default defender remains ordinary hold-W");
            config.ownRttMs = ping[0]; config.opponentRttMs = ping[1];
            config.defenderWtap = true;
            config.defenderClickIntervalMs = 100;
            var current = state(3, 0, false);
            // First physical B click is consumed at tick10. Its server hit,
            // B-local visible cue and delayed tactical reaction are DISTINCT.
            long hit = 25 + 50 * (long)Math.ceil((10 + ping[1] / 2.0 - 25) / 50);
            long visible = 10 + 50 * (long)Math.ceil((hit + ping[1] / 2.0 - 10) / 50);
            long release = visible + 150, resume = release + 50;
            long serverRestart = 25 + 50 * (long)Math.ceil((resume + ping[1] / 2.0 - 25) / 50);
            for (int tick = 0; tick <= (serverRestart + 50) / 50; tick++) {
                long defenderTime = current.nowMs + config.opponentPhaseMs;
                int beforeClicks = current.defenderPhysicalClicks;
                current = ComboModel.advanceRecorded(current, ComboModel.Action.RELEASE, false, config);
                if (defenderTime < visible) require(current.lastPredictedDefenderTargetHurtCueMs == Long.MIN_VALUE,
                        "server hit cannot instantly become B's local visible cue at RTT " + ping[1]);
                if (defenderTime >= visible) require(current.lastPredictedDefenderTargetHurtCueMs == visible,
                        "outgoing hurt cue uses B's RTT and B's native observation tick");
                if (defenderTime < release) require(current.defenderForward && current.defenderWtapCount == 0,
                        "visible cue must age 150ms before defender movement can react");
                if (defenderTime == release) {
                    require(current.defenderWtapCount == 1 && !current.defenderForward
                                    && !current.b.sprint && !current.bLastSentSprint,
                            "eligible cue releases W/Ctrl and transmits STOP once");
                    require(current.defenderHandledTapCueMs == visible && current.defenderTapUntilMs == resume,
                            "tap records its local cue time and a 50ms wall-clock deadline");
                    require(current.defenderPhysicalClicks == beforeClicks + 1 && current.receivedHits == 1,
                            "the physical immune-window click still runs on the W-release tick");
                }
                if (defenderTime >= resume) require(current.defenderForward && current.b.sprint
                                && current.bLastSentSprint && current.defenderWtapCount == 1,
                        "one 50ms release resumes W/Ctrl without retriggering the same hurt cue");
                long serverTime = current.nowMs - 50 + config.serverPhaseMs;
                if (defenderTime >= resume && serverTime < serverRestart)
                    require(!current.bServerSprint, "local sprint restoration waits for B-to-server START delivery");
                if (serverTime >= serverRestart)
                    require(current.bServerSprint, "tap's distinct STOP/START transition rearms server sprint");
            }
            require(current.lastPredictedReceivedHitMs == hit,
                    "delayed W-tap does not manufacture another fresh hit during the first window");
        }
    }

    private static void defenderWtapHistoryAndCueSemantics() {
        var config = clickingConfig();
        config.defenderClicks = false; // Historical event isolates the cue policy.
        config.defenderWtap = true;
        var pending = state(3, 0, false);
        pending.scheduleConfirmedIncomingHit(25, config);
        for (int tick = 0; tick < 2; tick++)
            pending = ComboModel.advanceRecorded(pending, ComboModel.Action.RELEASE, false, config);
        require(pending.lastPredictedDefenderTargetHurtCueMs == 60 && pending.defenderWtapCount == 0,
                "copied anchor contains a delivered but not yet eligible defender cue");
        var early = pending.copy();
        for (int tick = 0; tick < 3; tick++)
            early = ComboModel.advanceRecorded(early, ComboModel.Action.RELEASE, false, config);
        require(early.defenderWtapCount == 1 && !early.defenderForward,
                "150ms branch consumes its cue at defender tick210");
        config.defenderReactionMs = 250;
        var later = pending.copy();
        for (int tick = 0; tick < 3; tick++)
            later = ComboModel.advanceRecorded(later, ComboModel.Action.RELEASE, false, config);
        require(later.defenderWtapCount == 0 && later.defenderForward,
                "another copy retains its own unconsumed observation history");
        for (int tick = 0; tick < 2; tick++)
            later = ComboModel.advanceRecorded(later, ComboModel.Action.RELEASE, false, config);
        require(later.defenderWtapCount == 1 && later.defenderHandledTapCueMs == 60,
                "consuming one copy's cue cannot remove it from another copy");
        require(pending.nowMs == 100 && pending.defenderWtapCount == 0,
                "history replay never mutates the source snapshot");

        config.defenderReactionMs = 150;
        for (int pause : new int[]{0, 25, 50, 51}) {
            config.defenderWtapPauseMs = pause;
            var quantized = state(3, 0, false);
            quantized.scheduleConfirmedIncomingHit(25, config);
            int releasedTicks = 0;
            for (int tick = 0; tick < 9; tick++) {
                quantized = ComboModel.advanceRecorded(quantized, ComboModel.Action.RELEASE, false, config);
                if (!quantized.defenderForward) releasedTicks++;
            }
            require(releasedTicks == (int)Math.ceil(pause / 50.0) && quantized.defenderWtapCount == 1,
                    "pause deadline is sampled on native ticks, including zero/nonmultiples: " + pause);
        }

        config.defenderWtapPauseMs = 50;
        var multiple = state(3, 0, false);
        multiple.scheduleConfirmedIncomingHit(25, config);
        multiple.scheduleConfirmedIncomingHit(625, config);
        for (int tick = 0; tick < 20; tick++)
            multiple = ComboModel.advanceRecorded(multiple, ComboModel.Action.RELEASE, false, config);
        require(multiple.defenderWtapCount == 2 && multiple.defenderHandledTapCueMs == 660,
                "each distinct outgoing fresh-hurt cue arms exactly one tap");

        var ownHurtOnly = state(3, 0, false);
        ownHurtOnly.scheduleConfirmedHit(25, config);
        for (int tick = 0; tick < 9; tick++)
            ownHurtOnly = ComboModel.advanceRecorded(ownHurtOnly, ComboModel.Action.RELEASE, false, config);
        require(ownHurtOnly.defenderWtapCount == 0
                        && ownHurtOnly.lastPredictedDefenderTargetHurtCueMs == Long.MIN_VALUE,
                "being hit does not arm the defender's outgoing-hit W-tap policy");

        config.defenderWtap = false;
        var hold = state(3, 0, false);
        hold.scheduleConfirmedIncomingHit(25, config);
        for (int tick = 0; tick < 9; tick++) {
            hold = ComboModel.advanceRecorded(hold, ComboModel.Action.RELEASE, false, config);
            require(hold.defenderForward && hold.b.sprint, "disabled W-tap preserves continuous W/Ctrl");
        }
        require(hold.defenderWtapCount == 0, "hold-W does not acquire periodic resets");

        config.defenderWtap = config.defenderClicks = true;
        var upgraded = state(3, 0, false);
        upgraded.b.y = upgraded.serverB.y = upgraded.serverMotionB.y = 1;
        upgraded.b.ground = upgraded.serverMotionB.ground = false;
        upgraded.serverFallDistanceB = .1f;
        upgraded.ownHurtResistantTicks = 15;
        upgraded.ownLastDamage = 8;
        upgraded = ComboModel.advanceRecorded(upgraded, ComboModel.Action.RELEASE, false, config);
        require(upgraded.receivedDamageUpgrades == 1, "fixture scores an outgoing B critical upgrade");
        config.defenderClicks = false;
        for (int tick = 0; tick < 8; tick++)
            upgraded = ComboModel.advanceRecorded(upgraded, ComboModel.Action.RELEASE, false, config);
        require(upgraded.defenderWtapCount == 0
                        && upgraded.lastPredictedDefenderTargetHurtCueMs == Long.MIN_VALUE,
                "a scored critical upgrade has no fresh animation and cannot trigger W-tap");
        config.defenderReactionMs = 149;
        boolean rejected = false;
        try { config.validate(); } catch (IllegalArgumentException expected) { rejected = true; }
        require(rejected, "defender tactical reactions cannot bypass the 150ms observation limit");
    }

    private static void standstillDefenderKeepsClicksAndPhysics() {
        var config = clickingConfig();
        require(config.defenderMoves, "defender pursuit is enabled by default");
        config.defenderMoves = false;
        var still = new ComboModel.State(0,
                new ComboModel.Body(0, 0, 0, -.0784, true, false),
                new ComboModel.Body(3, 0, 0, -.0784, true, false),
                new ComboModel.View(3, 0), new ComboModel.View(0, 0),
                new ComboModel.View(0, 0), new ComboModel.View(3, 0));
        for (int tick = 0; tick < 4; tick++)
            still = ComboModel.advanceRecorded(still, ComboModel.Action.RELEASE, false, config);
        near(still.b.x, 3, 1e-9, "standstill defender has no W pursuit acceleration");
        require(!still.defenderForward && !still.b.sprint && !still.bLastSentSprint && !still.bServerSprint,
                "standstill defender holds neither movement nor sprint input");
        require(still.defenderPhysicalClicks == 2 && still.receivedHits == 1 && !still.lastReceivedHitSprint,
                "standstill defender still clicks at ten CPS and deals an in-range ordinary hit");
        require(still.lastReceivedImpulseX < -.39 && still.lastReceivedImpulseX > -.41,
                "standstill outgoing hit has ordinary knockback without sprint bonus");
        still.scheduleDefenderImpulse(still.nowMs, .4, .3608, 0);
        still = ComboModel.advanceRecorded(still, ComboModel.Action.RELEASE, false, config);
        near(still.b.x, 3.4, 1e-9, "normal received knockback still moves the standstill body");
        require(still.b.y > .35 && !still.b.ground && still.defenderPhysicalClicks == 3,
                "standstill preserves airborne physics and continues physical clicks during knockback");
    }

    private static void visibleInterpolation() {
        var tracker = new VisibleTracker();
        require(!tracker.update(0, 0, 0, 0, 0).valid, "one sample cannot reveal interpolation");
        var first = tracker.update(1, 50, 1.0 / 96, 2.0 / 96, 4.0 / 96);
        require(first.valid && first.divisor == 3 && first.confidence == VisibleTracker.Confidence.UNIQUE,
                "three-coordinate grid identifies unique divisor");
        require(first.firstUseTimeMs == 0, "new goal first affected the previous sample's update");
        near(first.sourceTimeMs(20, 100), -60, 1e-9, "declared RTTs give nominal source age");
        var second = tracker.update(2, 100, 2.0 / 96, 4.0 / 96, 8.0 / 96);
        require(second.divisor == 2 && !second.goalChanged && second.firstUseTimeMs == 0,
                "continuation retains source time");
        var third = tracker.update(3, 150, 3.0 / 96, 6.0 / 96, 12.0 / 96);
        require(third.divisor == 1 && !third.goalChanged, "final interpolation step");
        var still = tracker.update(4, 200, 3.0 / 96, 6.0 / 96, 12.0 / 96);
        require(still.valid && still.confidence == VisibleTracker.Confidence.STATIONARY
                        && !still.goalChanged && !still.velocityValid,
                "stationary sample is not a newly timed position packet");
        require(tracker.update(4, 200, 3.0 / 96, 6.0 / 96, 12.0 / 96) == still,
                "repeated eligible observation is idempotent");
        require(!tracker.update(6, 300, 0, 0, 0).valid, "missing native tick resets continuity");

        tracker.reset();
        tracker.update(0, 0, 0, 0, 0);
        var ambiguous = tracker.update(1, 50, 1.0 / 32, 0, 0);
        require(ambiguous.ambiguous && ambiguous.divisor == 3
                        && ambiguous.confidence == VisibleTracker.Confidence.AMBIGUOUS,
                "equally exact inverse solutions remain explicitly ambiguous");
        var alternatives = ambiguous.candidates();
        alternatives[0] = null;
        require(ambiguous.candidates()[0] != null, "caller cannot modify retained alternatives");
        var continued = tracker.update(2, 100, 2.0 / 32, 0, 0);
        require(continued.ambiguous && continued.divisor == 2
                        && continued.confidence == VisibleTracker.Confidence.CONTINUATION,
                "known interpolation continuation breaks a grid tie");
        require(!tracker.update(3, 150, .07123, .01112, .001221).valid,
                "non-native grid differences are rejected, not rounded into invented goals");
        tracker.reset();
        tracker.update(0, 0, 1, 2, 3);
        var unknownAge = tracker.update(1, 50, 1, 2, 3);
        require(Double.isNaN(unknownAge.sourceTimeMs(0, 0)), "initial stationary goal has unknown age");
    }

    private static void visiblePacketReplacement() {
        var tracker = new VisibleTracker();
        tracker.update(0, 0, 0, 0, 0);
        tracker.update(1, 50, 1.0 / 96, 2.0 / 96, 4.0 / 96);
        tracker.update(2, 100, 2.0 / 96, 4.0 / 96, 8.0 / 96);
        double px = 2.0 / 96, py = 4.0 / 96, pz = 8.0 / 96;
        var next = tracker.update(3, 150, px + (.5 - px) / 3,
                py + (.625 - py) / 3, pz + (.875 - pz) / 3);
        require(next.valid && next.divisor == 3 && next.goalChanged && next.velocityValid,
                "new goal replaces an unfinished interpolation");
        require(next.velocityIntervalMs == 100, "secant uses distinct goal times");
        near(next.vx, (.5 - 1.0 / 32) / 2, 1e-9, "secant velocity units are blocks/tick");

        // Independent synthetic native interpolation, including negative world
        // coordinates and quantization ties. Every admissible truth must survive
        // in the alternatives; only a unique solution can promise identification.
        tracker.reset();
        var random = new Random(189);
        double[] position = {4093, 4, -8193};
        double[] goal = position.clone();
        int remaining = 0;
        tracker.update(0, 0, position[0], position[1], position[2]);
        for (int tick = 1; tick <= 1000; tick++) {
            if ((tick & 1) == 1) {
                for (int axis = 0; axis < 3; axis++)
                    goal[axis] = Math.floor(position[axis] * 32 + random.nextInt(65) - 32) / 32;
                remaining = 3;
            }
            for (int axis = 0; axis < 3; axis++) position[axis] += (goal[axis] - position[axis]) / remaining;
            remaining--;
            var inferred = tracker.update(tick, tick * 50L, position[0], position[1], position[2]);
            require(inferred.valid, "valid native interpolation must have a grid solution at " + tick);
            boolean included = false;
            for (var candidate : inferred.candidates())
                included |= candidate.x == goal[0] && candidate.y == goal[1] && candidate.z == goal[2];
            require(included, "true goal must remain an alternative at " + tick);
            if (inferred.confidence == VisibleTracker.Confidence.UNIQUE)
                require(inferred.goalX == goal[0] && inferred.goalY == goal[1] && inferred.goalZ == goal[2],
                        "unique exact-grid inference must identify the synthetic native goal");
        }
    }

    public static void main(String[] args) {
        String group = args.length == 0 ? "all" : args[0];
        if (group.equals("all") || group.equals("model")) {
            geometryAndMovement();
            inputAndSprintSemantics();
            optionalNativeHeadYaw();
            optionalNativeFallDamage();
            latentServerMotionAndCues();
            correctionAndPlanningIsolation();
            confirmedHitReplay();
            freshContactEdgeScoring();
            defenderClickClockAndLocalDrag();
            incomingKnockbackAndIndependentImmunity();
            confirmedIncomingReplayAndCopies();
            criticalDamageAndImmunity();
            criticalFallHistoryAndSprintUpgrade();
            incomingCriticalAndConfirmedFresh();
            optionalSearchDiversity();
            completeMacrosAndWarmPlans();
            defenderWtapLatencyAndControls();
            defenderWtapHistoryAndCueSemantics();
            standstillDefenderKeepsClicksAndPhysics();
            System.out.println("PASS model geometry, controls, optional native head yaw/fall damage, symmetric combat, critical upgrades, confirmed replay, delayed W-tap/standstill defenders, scoring, diversity and macro/warm rollouts");
        }
        if (group.equals("all") || group.equals("tracker")) {
            visibleInterpolation();
            visiblePacketReplacement();
            System.out.println("PASS visible-only interpolation, ambiguity, gaps and packet replacement");
        }
        require(group.equals("all") || group.equals("model") || group.equals("tracker"), "unknown test group");
    }
}
