import lab.ComboModel;
import lab.VisibleTracker;
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
            latentServerMotionAndCues();
            correctionAndPlanningIsolation();
            confirmedHitReplay();
            freshContactEdgeScoring();
            System.out.println("PASS model geometry, controls, latent motion, confirmed replay, contact scoring and planning isolation");
        }
        if (group.equals("all") || group.equals("tracker")) {
            visibleInterpolation();
            visiblePacketReplacement();
            System.out.println("PASS visible-only interpolation, ambiguity, gaps and packet replacement");
        }
        require(group.equals("all") || group.equals("model") || group.equals("tracker"), "unknown test group");
    }
}
