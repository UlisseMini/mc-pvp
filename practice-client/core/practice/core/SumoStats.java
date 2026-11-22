package practice.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;

/** Read-only measurements on the local input, incoming velocity and movement clocks. */
public final class SumoStats {
    public static final int MOVES = 6;
    public static final long HIT_PAIR_MS = 120;
    public static final double MIN_OUTWARD = .005;
    public static boolean sprintCut(double x,double y,double z,double afterX,double afterY,double afterZ){
        return Math.abs(afterX-x*.6)<1e-7&&Math.abs(afterZ-z*.6)<1e-7&&Math.abs(afterY-y)<1e-7;
    }
    public static final double COSTLY_BRAKE_BLOCKS = .50;
    private static final long EDGE_RECOVERY_MS = 1000;

    public enum Cue { BRAKE, SPRINT, COOLDOWN, UNCLICKED, AIM }

    private enum BrakeEventKind { BRAKED, SPRINT_OFF, NO_CLICK, MISS_TIMER, AIM_MISS, NO_TARGET }

    private static final class BrakeEvent {
        final BrakeEventKind kind;
        final int ms, move;
        final String description, correction;
        final boolean multipleFixes;
        final MissedBrake missed;

        BrakeEvent(BrakeEventKind kind, int ms, int move, String description, String correction,
                   boolean multipleFixes, MissedBrake missed) {
            this.kind = kind;
            this.ms = ms;
            this.move = move;
            this.description = description;
            this.correction = correction;
            this.multipleFixes = multipleFixes;
            this.missed = missed;
        }
    }

    private static final class MissedBrake {
        final boolean afterRelease;
        double speed, distance, peak;

        MissedBrake(double speed, double peak, boolean afterRelease) {
            this.speed = speed;
            this.peak = peak;
            this.afterRelease = afterRelease;
        }
    }

    public interface Listener {
        void cue(Exchange exchange, Cue cue);
        void closed(Exchange exchange);
    }

    public static final class Exchange {
        public final long impact;
        public final double axisX, axisZ, speed, margin;
        public final int jumpOffset;
        private final int[] issues = {-1, -1, -1, -1, -1};
        public int steps, cuts, firstCutStep = -1, firstCutMs = -1;
        public int firstClickMs = -1, wUp, unclickedTicks, missTicks;
        public double distance, firstClickDistance = Double.NaN;
        public boolean grounded, earlyOpening, earlyTarget, confirmed;
        private boolean slotOnTarget, slotOpen, clicked, cutThisMove, pendingBrake, leftGround, landed;
        private final List<BrakeEvent> brakeEvents = new ArrayList<>();
        private final EnumSet<BrakeEventKind> recordedThisMove = EnumSet.noneOf(BrakeEventKind.class);
        private boolean validSaving = true;
        private double removedSpeed, extraDistance, peakSlide, noBrakePeak;
        private boolean idealBrakeThisMove;
        private double idealRemovedSpeed, idealDistanceReduction, idealPeak;
        public double missedBrakeBlocks;
        public boolean missedBrakeAfterRelease;
        private int slotMs;
        private double slotOutward;

        private Exchange(long impact, double x, double z, double margin, int jumpOffset, boolean confirmed) {
            this.impact = impact;
            this.speed = Math.hypot(x, z);
            this.axisX = x / speed;
            this.axisZ = z / speed;
            this.margin = margin;
            this.jumpOffset = jumpOffset;
            this.confirmed = confirmed;
        }

        public double outward(double x, double z) { return x * axisX + z * axisZ; }
        public boolean earlyBrake() { return firstCutStep >= 0 && firstCutStep < 2; }
        public double peakSlide() { return peakSlide; }
        public double kbSaved() {
            return steps == MOVES && validSaving ? Math.max(0, noBrakePeak - peakSlide) : Double.NaN;
        }
        public double idealKbSaved() { return Math.max(kbSaved(), noBrakePeak - idealPeak); }

        private void idealBrake(double outward) {
            if (idealBrakeThisMove) return;
            double idealOutward = outward - idealRemovedSpeed;
            if (idealOutward > MIN_OUTWARD) idealRemovedSpeed += .4 * idealOutward;
            idealBrakeThisMove = true;
        }

        public Cue firstIssue() {
            Cue first = null;
            int earliest = Integer.MAX_VALUE;
            for (Cue cue : Cue.values()) {
                int time = issues[cue.ordinal()];
                if (time >= 0 && time < earliest) {
                    earliest = time;
                    first = cue;
                }
            }
            return first;
        }

        public String note(Cue cue) {
            if (cue == Cue.BRAKE) return "Brake at +" + firstCutMs + " ms";
            if (cue == Cue.SPRINT) return "Sprint off at contact";
            if (cue == Cue.COOLDOWN) return "Miss timer: " + missTicks + " ticks left";
            if (cue == Cue.AIM) return "Close hitbox, aim ray missed";
            return "On target for " + unclickedTicks + (unclickedTicks == 1 ? " tick" : " ticks") + ", no click";
        }

        public String explanation() {
            Cue cue = firstIssue();
            if (cue == Cue.UNCLICKED) {
                int unused = issues[Cue.UNCLICKED.ordinal()];
                String timing = "On target at +" + unused + " ms";
                if (firstClickMs < unused) return timing + ", no click in that opening.";
                return timing + ". First click +" + firstClickMs + " ms" +
                        (Double.isFinite(firstClickDistance) ? ", " + blocks(firstClickDistance) + " to hitbox." : ".");
            }
            if (cue != null) return note(cue) + " at +" + issues[cue.ordinal()] + " ms.";
            if (cuts > 0) return "First brake +" + firstCutMs + " ms. " + cuts + " in this exchange.";
            return "No clear braking chance was observed.";
        }

        private void record(BrakeEventKind kind, int ms, String description) {
            record(kind, ms, description, null, false, 0, false);
        }

        private void record(BrakeEventKind kind, int ms, String description, String correction,
                            boolean multipleFixes, double outward, boolean afterRelease) {
            if (!recordedThisMove.add(kind)) return;
            MissedBrake missed = correction == null ? null : new MissedBrake(.4 * outward, peakSlide, afterRelease);
            brakeEvents.add(new BrakeEvent(kind, ms, steps, description, correction, multipleFixes, missed));
        }

        private void observedCut(double outward) {
            for (BrakeEvent event : brakeEvents) {
                if (event.missed == null) continue;
                // Both paths take later cuts. A cut on this same move uses the
                // sprint that the hypothetical click would already have spent.
                if (event.move == steps) event.missed.speed -= .4 * outward;
                else event.missed.speed *= .6;
            }
        }

        public List<String> brakeTimeline() {
            List<String> lines = new ArrayList<>();
            for (BrakeEvent event : brakeEvents) {
                String line = "+" + event.ms + " ms  " + event.description;
                if (event.missed != null && event.missed.afterRelease) line += " after a W-up move";
                if (event.multipleFixes) line += ". One fix alone gives no brake";
                if (event.missed != null && validSaving && event.move < steps) {
                    double cost = peakSlide - event.missed.peak;
                    line += ". With " + event.correction + ", " +
                            (cost >= .005 ? "~" + blocks(cost) + " less slide" : "no change to peak slide");
                } else if (event.kind != BrakeEventKind.BRAKED) line += ". No slide estimate for this moment";
                lines.add(line + ".");
            }
            return lines;
        }
    }

    public Exchange active, last, worst, best;
    public int launches, earlyTargets, earlyOpenings, earlyBrakes, brakes, lateBrakes;
    public int grounded, airborne, beforeImpactJumps, oneMoveReleases, longerReleases;
    public int edgeChecked, edgeSaved, sprintOffContacts;
    public int costlyBrakeExchanges, costlyBrakeAfterRelease;
    public double largestMissedBrake;
    public int savingSamples;
    public double totalKbSaved;
    public Exchange lastEdge;
    private Exchange edgePending;
    private boolean sawForward, waitingToLand;
    private int releaseMoves;
    private final int[] losses = new int[Cue.values().length];
    private final List<Double> fullSlides = new ArrayList<>();
    private final List<Exchange> timeline = new ArrayList<>();
    private final Listener listener;

    public SumoStats(Listener listener) { this.listener = listener; }

    public void impact(long now, double x, double z, double margin, int jumpOffset, boolean confirmed) {
        closeWindow();
        expireEdge(now);
        active = new Exchange(now, x, z, margin, jumpOffset, confirmed);
    }

    public void confirm() {
        Exchange exchange = active;
        if (exchange == null || exchange.confirmed) return;
        exchange.confirmed = true;
        if (exchange.pendingBrake) listener.cue(exchange, Cue.BRAKE);
        Cue issue = exchange.firstIssue();
        if (issue != null && issue != Cue.UNCLICKED && exchange.cuts == 0) listener.cue(exchange, issue);
    }

    /** Native ray/flags at the first KeyBinding.isPressed check of the tick. */
    public void inputSlot(long now, boolean target, boolean sprint, int cooldown, double outward) {
        Exchange exchange = active;
        if (exchange == null || outward <= MIN_OUTWARD) return;
        exchange.slotMs = offset(exchange, now);
        exchange.slotOutward = outward;
        exchange.slotOnTarget = target && cooldown == 0;
        exchange.slotOpen = exchange.slotOnTarget && sprint;
        opening(exchange, target, exchange.slotOpen);
        if (exchange.slotOnTarget) exchange.idealBrake(outward);
    }

    public void click(long now, boolean target, boolean nearMiss, boolean sprint, int cooldown,
                      double outward, double distance, boolean cut) {
        Exchange exchange = active;
        if (exchange == null || outward <= MIN_OUTWARD) return;
        int ms = offset(exchange, now);
        if (exchange.firstClickMs < 0) {
            exchange.firstClickMs = ms;
            exchange.firstClickDistance = distance;
        }
        exchange.clicked = true;
        opening(exchange, target, target && sprint && cooldown == 0);
        if (target && cooldown == 0) exchange.idealBrake(outward);
        if (cut) {
            exchange.record(BrakeEventKind.BRAKED, ms, "Clicked the opponent and braked");
            exchange.removedSpeed += .4 * outward;
            // The difference is relative to the observed speed, which was just cut.
            exchange.idealRemovedSpeed -= .4 * outward;
            exchange.observedCut(outward);
            if (exchange.firstCutStep < 0) {
                exchange.firstCutStep = exchange.steps;
                exchange.firstCutMs = ms;
            }
            exchange.cuts++;
            if (exchange.confirmed) listener.cue(exchange, Cue.BRAKE);
            else exchange.pendingBrake = true;
        } else if (!exchange.cutThisMove) {
            String sprintStatus = sprint ? "" : ", sprint was also off";
            if (cooldown > 0) {
                String ticks = cooldown == 1 ? " tick left" : " ticks left";
                String fix = target ? "the timer clear" : nearMiss ? "the timer clear and aim corrected" : null;
                if (fix != null && !sprint) fix += " and sprint on";
                exchange.record(BrakeEventKind.MISS_TIMER, ms,
                        (target ? "On-target" : "Off-target") + " click blocked by the miss timer (" + cooldown + ticks + ")" +
                                sprintStatus, fix, fix != null && (!sprint || !target), outward, false);
            } else if (!target) {
                String location = Double.isFinite(distance) ? " (" + blocks(distance) + " to hitbox)" : "";
                String spacing = distance > 3.05 ? ". Aim alone would still be out of reach here" : "";
                exchange.record(nearMiss ? BrakeEventKind.AIM_MISS : BrakeEventKind.NO_TARGET, ms,
                        (nearMiss ? "Clicked close, but your aim missed" : "Clicked without selecting the opponent") +
                                location + sprintStatus + spacing, nearMiss ? "aim corrected" + (sprint ? "" : " and sprint on") : null,
                        nearMiss && !sprint, outward, false);
            }
            if (target && sprint && cooldown > 0) {
                if (exchange.missTicks == 0) exchange.missTicks = cooldown;
                issue(exchange, Cue.COOLDOWN, ms);
            } else if (target && !sprint && cooldown == 0) {
                exchange.record(BrakeEventKind.SPRINT_OFF, ms,
                        "Clicked the opponent while sliding back, but sprint was off", "sprint on", false, outward, releaseMoves > 0);
                issue(exchange, Cue.SPRINT, ms);
            } else if (nearMiss && sprint && cooldown == 0) {
                issue(exchange, Cue.AIM, ms);
            }
        }
        exchange.cutThisMove |= cut;
    }

    private static void opening(Exchange exchange, boolean target, boolean clear) {
        if (target && exchange.steps < 2) exchange.earlyTarget = true;
        if (clear && exchange.steps < 2) exchange.earlyOpening = true;
    }

    private void issue(Exchange exchange, Cue cue, int ms) {
        if (exchange.issues[cue.ordinal()] >= 0) return;
        exchange.issues[cue.ordinal()] = ms;
        if (exchange.confirmed && cue != Cue.UNCLICKED) listener.cue(exchange, cue);
    }

    /** A finite horizontal drag certifies an unobstructed native movement step. */
    public void move(long now, double x, double z, boolean ground, boolean forward, double drag) {
        if (forward) {
            if (releaseMoves == 1) oneMoveReleases++;
            if (releaseMoves > 1) longerReleases++;
            sawForward = true;
            releaseMoves = 0;
        } else if (sawForward) {
            releaseMoves++;
        }
        expireEdge(now);
        if (edgePending != null) {
            if (!ground) waitingToLand = true;
            else if (waitingToLand) {
                edgeChecked++;
                edgeSaved++;
                edgePending = null;
            }
        }
        Exchange exchange = active;
        if (exchange == null) return;
        if (exchange.steps == 0) exchange.grounded = ground;
        if (!ground) exchange.leftGround = true;
        else if (exchange.leftGround) exchange.landed = true;
        if (!forward) exchange.wUp++;
        if (exchange.slotOnTarget && !exchange.clicked)
            exchange.record(BrakeEventKind.NO_CLICK, exchange.slotMs,
                    "Crosshair on the opponent, no click" + (exchange.slotOpen ? "" : ", sprint was also off"),
                    "a click here" + (exchange.slotOpen ? "" : " and sprint on"), !exchange.slotOpen, exchange.slotOutward, false);
        if (exchange.slotOpen && !exchange.clicked) {
            exchange.unclickedTicks++;
            issue(exchange, Cue.UNCLICKED, exchange.slotMs);
        }
        exchange.slotOnTarget = exchange.slotOpen = exchange.clicked = exchange.cutThisMove = exchange.idealBrakeThisMove = false;
        exchange.recordedThisMove.clear();
        exchange.steps++;
        exchange.distance += exchange.outward(x, z);
        exchange.peakSlide = Math.max(exchange.peakSlide, exchange.distance);
        if (!Double.isFinite(drag)) exchange.validSaving = false;
        if (exchange.validSaving) {
            // Fixed acceleration is common to both paths. Only the measured
            // velocity removed by clicks and its remaining drag differ.
            exchange.extraDistance += exchange.removedSpeed;
            exchange.noBrakePeak = Math.max(exchange.noBrakePeak, exchange.distance + exchange.extraDistance);
            exchange.removedSpeed *= drag;
            exchange.idealDistanceReduction += exchange.idealRemovedSpeed;
            exchange.idealPeak = Math.max(exchange.idealPeak, exchange.distance - exchange.idealDistanceReduction);
            exchange.idealRemovedSpeed *= drag;
            for (BrakeEvent event : exchange.brakeEvents) if (event.missed != null) {
                MissedBrake miss = event.missed;
                miss.distance += miss.speed;
                miss.peak = Math.max(miss.peak, exchange.distance - miss.distance);
                miss.speed *= drag;
            }
        }
        if (exchange.steps >= MOVES) closeWindow();
    }

    public void expireEdge(long now) {
        if (edgePending != null && now - edgePending.impact >= EDGE_RECOVERY_MS) {
            edgeChecked++;
            if (!waitingToLand) edgeSaved++;
            edgePending = null;
        }
    }

    public void closeWindow() {
        Exchange exchange = active;
        active = null;
        if (exchange == null || !exchange.confirmed || exchange.steps == 0 && exchange.cuts == 0) return;
        launches++;
        last = exchange;
        brakes += exchange.cuts;
        if (exchange.earlyTarget) earlyTargets++;
        if (exchange.earlyOpening) earlyOpenings++;
        if (exchange.issues[Cue.SPRINT.ordinal()] >= 0) sprintOffContacts++;
        if (exchange.validSaving) {
            for (BrakeEvent event : exchange.brakeEvents) if (event.kind == BrakeEventKind.SPRINT_OFF && event.move < exchange.steps) {
                MissedBrake miss = event.missed;
                double blocks = exchange.peakSlide - miss.peak;
                if (blocks > exchange.missedBrakeBlocks) {
                    exchange.missedBrakeBlocks = blocks;
                    exchange.missedBrakeAfterRelease = miss.afterRelease;
                }
            }
            largestMissedBrake = Math.max(largestMissedBrake, exchange.missedBrakeBlocks);
            if (exchange.missedBrakeBlocks >= COSTLY_BRAKE_BLOCKS) {
                costlyBrakeExchanges++;
                if (exchange.missedBrakeAfterRelease) costlyBrakeAfterRelease++;
            }
        }
        if (exchange.earlyBrake()) earlyBrakes++;
        else {
            if (exchange.cuts > 0) lateBrakes++;
            Cue cue = exchange.firstIssue();
            if (cue != null) losses[cue.ordinal()]++;
        }
        if (exchange.steps > 0) {
            timeline.add(exchange);
            if (exchange.grounded) grounded++;
            else airborne++;
            if (!exchange.grounded && exchange.jumpOffset <= 0 && exchange.jumpOffset >= -120) beforeImpactJumps++;
        }
        if (exchange.steps == MOVES) {
            double saved = exchange.kbSaved();
            if (Double.isFinite(saved)) {
                savingSamples++;
                totalKbSaved += saved;
            }
            fullSlides.add(exchange.distance);
            if (worst == null || exchange.distance > worst.distance) worst = exchange;
            if (exchange.cuts > 0 && (best == null || exchange.firstCutStep < best.firstCutStep ||
                    exchange.firstCutStep == best.firstCutStep && exchange.distance < best.distance)) best = exchange;
        }
        if (Double.isFinite(exchange.margin) && exchange.margin <= 2.5) {
            lastEdge = exchange;
            if (exchange.landed) {
                edgeChecked++;
                edgeSaved++;
            } else {
                edgePending = exchange;
                waitingToLand = exchange.leftGround;
            }
        }
        listener.closed(exchange);
    }

    public void discontinuity() {
        active = null;
        edgePending = null;
        sawForward = false;
        releaseMoves = 0;
    }

    public void finish(long now, boolean ringOutLoss) {
        closeWindow();
        expireEdge(now);
        if (edgePending != null && ringOutLoss) edgeChecked++;
        edgePending = null;
    }

    public int losses(Cue cue) { return losses[cue.ordinal()]; }

    public boolean reachesEstimatedEdge(Exchange exchange) {
        return exchange.margin > .10 && exchange.margin <= exchange.peakSlide() + .15;
    }

    public Exchange terminalLoss(long finished) {
        Exchange latestSupported = null;
        for (int i = timeline.size() - 1; i >= 0; i--) {
            Exchange exchange = timeline.get(i);
            if (finished - exchange.impact > 1250) break;
            if (exchange.peakSlide() < .40 || exchange.margin <= .10) continue;
            if (latestSupported == null) latestSupported = exchange;
            if (reachesEstimatedEdge(exchange)) return exchange;
        }
        return latestSupported;
    }

    public Exchange precedingAlignedLoss(Exchange exchange) {
        for (int i = timeline.indexOf(exchange) - 1; i >= 0; i--) {
            Exchange previous = timeline.get(i);
            if (exchange.impact - previous.impact > 1250) break;
            if (previous.peakSlide() >= .50 && previous.axisX * exchange.axisX + previous.axisZ * exchange.axisZ >= .85)
                return previous;
        }
        return null;
    }

    public int earlySprintOffAfterRelease() {
        int count = 0;
        for (Exchange exchange : timeline) {
            if (exchange.brakeEvents.stream().anyMatch(event -> event.kind == BrakeEventKind.SPRINT_OFF &&
                    event.move < 2 && event.missed.afterRelease)) count++;
        }
        return count;
    }

    /** Highlights are selected by measured peak slide, then shown in match order. */
    public List<Exchange> lossMoments(long finished, boolean lost) {
        List<Exchange> ranked = new ArrayList<>(timeline);
        ranked.sort(Comparator.comparingDouble(Exchange::peakSlide).reversed());
        List<Exchange> selected = new ArrayList<>();
        Exchange finalHit = lost ? terminalLoss(finished) : null;
        if (finalHit != null) selected.add(finalHit);
        for (Exchange exchange : ranked) {
            if (selected.size() >= 4 || exchange.peakSlide() < .50) break;
            if (exchange.margin <= .10 && exchange.steps < MOVES) continue;
            if (!selected.contains(exchange)) selected.add(exchange);
        }
        selected.sort(Comparator.comparingLong(exchange -> exchange.impact));
        return selected;
    }

    public List<Exchange> gainMoments() {
        List<Exchange> ranked = new ArrayList<>();
        for (Exchange exchange : timeline) if (exchange.kbSaved() >= .25) ranked.add(exchange);
        ranked.sort(Comparator.comparingDouble(Exchange::kbSaved).reversed());
        List<Exchange> selected = new ArrayList<>(ranked.subList(0, Math.min(3, ranked.size())));
        selected.sort(Comparator.comparingLong(exchange -> exchange.impact));
        return selected;
    }

    public static String timestamp(long elapsed) {
        elapsed = Math.max(0, elapsed);
        return String.format(Locale.ROOT, "%d:%02d.%03d", elapsed / 60000, elapsed / 1000 % 60, elapsed % 1000);
    }

    public String slides() {
        if (fullSlides.isEmpty()) return "No complete six-move slides";
        List<Double> ordered = new ArrayList<>(fullSlides);
        Collections.sort(ordered);
        int n = ordered.size();
        return "Six-move slides: median " + blocks((ordered.get((n - 1) / 2) + ordered.get(n / 2)) / 2) +
                " / max " + blocks(ordered.get(n - 1));
    }

    public static String blocks(double value) { return String.format(Locale.ROOT, "%.2f blocks", value); }

    private static int offset(Exchange exchange, long now) { return (int)Math.max(0, now - exchange.impact); }
}
