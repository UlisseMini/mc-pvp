import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lab.Json;
import lab.MouseLook;

/** Restore a strict native prefix, then fork ordinary movement keys on the recorded input opportunities. */
final class BoxingNativeTradeFork {
    private static final boolean APPROACH = Boolean.getBoolean("lab.fork_approach");
    private static final String APPROACH_KEYS = "krwsb";
    // Left/right pairs for release, W, W+Ctrl, and S, in that order.
    private static final String APPROACH_STRAFES = "adefghij";
    private static final int MAX_APPROACH_HEADS = 24;
    private static final boolean COMPACT_APPROACH = Boolean.getBoolean("lab.fork_compact_approach");
    private static final boolean KEEP_LAUNCHES = Boolean.getBoolean("lab.fork_keep_launches");
    private static final boolean CROSS_LANDING = Boolean.getBoolean("lab.fork_cross_landing");
    private static final boolean IMPACT_JUMP = Boolean.getBoolean("lab.fork_impact_jump");
    private static final boolean SHADOW_APPROACH = Boolean.getBoolean("lab.fork_approach_shadows");
    private static final boolean STRESS_APPROACH = Boolean.getBoolean("lab.fork_approach_attack_timing");
    private static final AimMode APPROACH_AIM = AimMode.valueOf(System.getProperty("lab.fork_approach_aim", "RECORDED"));
    private static final String CAMERA_PREFIX = "camera-yaw-";
    private static final String CAMERA_OFFSETS = System.getProperty("lab.fork_camera_offsets", "");
    private static final String FIRST_CONTACT_VETO = "skip-first-contact";
    private static final double CONTACT_VETO_MS = Double.parseDouble(System.getProperty("lab.fork_first_contact_veto_ms", "0"));
    private static final double CONTACT_HOLD_MS = Double.parseDouble(System.getProperty("lab.fork_contact_hold_ms", "0"));
    private static final double CONTACT_RECOIL_SPEED = Double.parseDouble(System.getProperty("lab.fork_contact_recoil_speed", ".05"));

    private static final String GUARDED_SKIP = "guarded-skip-first-contact";
    private static final String CONTACT_HOLD = "hold-first-contact";
    private static final String GUARDED_HOLD = "guarded-hold-first-contact";

    /** The press calendar stays fixed. This probe excludes local contact on selected presses. */
    private static final class ContactSelection {
        final double start;
        final double holdMs;
        final boolean guard;
        final boolean skip;
        double firstContact = Double.NaN;
        double firstSkipped = Double.NaN;
        double lastVelocity = Double.NEGATIVE_INFINITY;
        boolean decided = false;
        boolean stoppedByGuard = false;
        List<Object> firstObservation = List.of();
        final List<Double> skipped = new ArrayList<>();
        ContactSelection(String arm, double start) {
            this.start = start;
            guard = arm.equals(GUARDED_HOLD) || arm.equals(GUARDED_SKIP);
            holdMs = arm.equals(CONTACT_HOLD) || arm.equals(GUARDED_HOLD) ? CONTACT_HOLD_MS : 0;
            skip = arm.equals(FIRST_CONTACT_VETO) || guard || holdMs > 0;
        }
        boolean onPress(double now, PeriodicComboFightModel.Body body, PeriodicComboFightModel.View target) {
            if (decided || (Double.isNaN(firstContact) && now >= start + CONTACT_VETO_MS)) return false;
            double ray = PeriodicComboFightModel.ray(body, target);
            if (!(ray <= 3)) return false;
            double dx = target.x - body.q.x(), dz = target.z - body.q.z();
            double toward = (body.q.vx() * dx + body.q.vz() * dz) / Math.max(.001, Math.hypot(dx, dz));
            boolean hurt = body.lastHurtTime >= start;
            boolean velocity = lastVelocity >= start;
            if (Double.isNaN(firstContact)) {
                firstContact = now;
                firstObservation = List.of(now, ray, toward, body.sprint, body.q.onGround(), hurt, velocity);
            }
            if (!skip || (Double.isFinite(firstSkipped) && now >= firstSkipped + holdMs)) {
                decided = true;
                return false;
            }
            if (guard && (hurt || velocity || toward < -CONTACT_RECOIL_SPEED)) {
                decided = true;
                stoppedByGuard = true;
                return false;
            }
            if (Double.isNaN(firstSkipped)) firstSkipped = now;
            skipped.add(now);
            if (holdMs == 0) decided = true;
            return true;
        }
    }

    private enum AimMode {
        RECORDED, DEFENDER, BOTH;

        boolean tracks(boolean ours) { return this == BOTH || this == DEFENDER && !ours; }
    }

    private record Event(double time, String kind, String[] words) {
        Event key(int key) {
            return movement(key, Float.NaN);
        }

        Event movement(int key, float strafe) {
            var next = words.clone();
            next[2] = Integer.toString(key);
            if (Float.isFinite(strafe)) next[4] = Float.toString(strafe);
            return new Event(time, kind, next);
        }

        Event jump(boolean pressed) {
            var next = words.clone();
            next[5] = pressed ? "1" : "0";
            return new Event(time, kind, next);
        }

        Event trackedLook(PeriodicComboFightModel.Body body) {
            if (!kind.startsWith("INPUT") && !kind.startsWith("MOVE")) return this;
            var next = words.clone();
            next[3] = Float.toString(body.yaw);
            if (kind.startsWith("INPUT")) next[4] = Float.toString(body.pitch);
            return new Event(time, kind, next);
        }
    }

    private record Sample(double send, double apply) {}
    private record ImpulseProbe(double attack, double contact, double sprintMagnitude) {}
    private record LandingProbe(double press, double firstTravel) {
        static LandingProbe fromProperty() {
            String spec = System.getProperty("lab.fork_landing_probe");
            if (spec == null) return null;
            String[] fields = spec.split(",");
            if (fields.length != 2) throw new IllegalArgumentException("Expected consumed click time and defender movement time");
            double press = Double.parseDouble(fields[0]), travel = Double.parseDouble(fields[1]);
            if (!Double.isFinite(press) || !Double.isFinite(travel) || !(press < travel))
                throw new IllegalArgumentException("Landing probe movement must follow the selected click");
            return new LandingProbe(press, travel);
        }
    }
    private record KeyOverride(double time, int key, double brakeAt) {
        String arm() { return key == 0 ? "release-head" : "contact-ctrl"; }
    }

    private enum DeliveryScope {
        ALL, OWN_UPLOAD, DEFENDER_UPLOAD, OWN_DOWNLOAD, DEFENDER_DOWNLOAD;

        boolean includes(int route, boolean first) {
            int own = first ? 0 : 1;
            return switch (this) {
                case ALL -> true;
                case OWN_UPLOAD -> route == own;
                case DEFENDER_UPLOAD -> route == 1 - own;
                case OWN_DOWNLOAD -> route == own + 2;
                case DEFENDER_DOWNLOAD -> route == 3 - own;
            };
        }
    }

    private enum PacketGroup {
        ALL, POSITION, ATTACK, OTHER;

        boolean includes(int route, int packetId) {
            boolean position = route >= 2 && packetId >= 0x14 && packetId <= 0x18;
            if (this == ATTACK) return route < 2 && packetId == 2;
            return this == ALL || (this == POSITION) == position;
        }
    }

    private record DeliveryFilter(PacketGroup group, double from, double until, Set<Double> fixedReceipts) {
        boolean canMove(double delivery) {
            return from <= delivery && delivery <= until && !fixedReceipts.contains(delivery);
        }

        boolean includes(int route, int packetId, double delivery) {
            return group.includes(route, packetId) && canMove(delivery);
        }

        static DeliveryFilter settings() {
            var group = PacketGroup.valueOf(System.getProperty("lab.fork_packet_group", "ALL"));
            var bound = System.getProperty("lab.fork_delivery_window_ms", "-Infinity,Infinity").split(",");
            if (bound.length != 2) throw new IllegalArgumentException("Expected lower and upper delivery time");
            double from = Double.parseDouble(bound[0]);
            double until = Double.parseDouble(bound[1]);
            if (!(from <= until)) throw new IllegalArgumentException("Invalid delivery interval");
            var fixed = Arrays.stream(System.getProperty("lab.fork_fixed_receipts_ms", "").split(","))
                    .filter(s -> !s.isEmpty()).map(Double::parseDouble).toList();
            if (fixed.stream().anyMatch(x -> !Double.isFinite(x)))
                throw new IllegalArgumentException("Fixed receipts must be finite");
            return new DeliveryFilter(group, from, until, Set.copyOf(fixed));
        }
    }

    private enum InDrain { EARLY, LATE }

    /** The source main-thread bracket and readiness are separate clocks. */
    private record Drain(double start, double end) {}
    private record PositionSample(double send, double apply, double wire, double enqueued, int drain) {}

    private static int firstPossible(List<Drain> spans, double ready) {
        int lo = 0, hi = spans.size();
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (spans.get(mid).end < ready) lo = mid + 1;
            else hi = mid;
        }
        return lo;
    }

    private static final class PositionQueue {
        final List<List<Drain>> drains = List.of(new ArrayList<>(), new ArrayList<>());
        final List<List<PositionSample>> samples = List.of(new ArrayList<>(), new ArrayList<>());
        final List<Map<Double, PositionSample>> byHandler = List.of(new HashMap<>(), new HashMap<>());
        final InDrain ambiguity;
        final boolean reusePositionFamily;
        final double generatedDelay;

        PositionQueue(Path path, InDrain ambiguity) throws java.io.IOException {
            this.ambiguity = ambiguity;
            reusePositionFamily = Boolean.parseBoolean(System.getProperty("lab.fork_position_family", "true"));
            generatedDelay = Double.parseDouble(System.getProperty("lab.fork_generated_position_delay_ms", ".35"));
            if (!Double.isFinite(generatedDelay) || generatedDelay < 0)
                throw new IllegalArgumentException("Expected a nonnegative generated position delay");
            for (String line : Files.readAllLines(path)) {
                var p = line.split("\t");
                int side = Integer.parseInt(p[1]) - 2;
                if (p[0].equals("D")) {
                    drains.get(side).add(new Drain(Double.parseDouble(p[2]), Double.parseDouble(p[3])));
                } else if (p[0].equals("P")) {
                    var sample = new PositionSample(Double.parseDouble(p[2]), Double.parseDouble(p[3]),
                            Double.parseDouble(p[4]), Double.parseDouble(p[5]), Integer.parseInt(p[6]));
                    samples.get(side).add(sample);
                    if (byHandler.get(side).put(sample.apply, sample) != null)
                        throw new IllegalArgumentException("Position handlers share a source timestamp");
                } else throw new IllegalArgumentException("Unknown position-queue row " + p[0]);
            }
            for (var list : samples) list.sort(Comparator.comparingDouble(PositionSample::send));
        }

        void addDrainEvents(List<Event> events) {
            for (int side = 0; side < 2; side++) {
                String kind = side == 0 ? "POSITION_DRAIN_A" : "POSITION_DRAIN_B";
                for (var d : drains.get(side)) events.add(new Event(d.end, kind, new String[0]));
            }
            events.sort(Comparator.comparingDouble(Event::time));
        }

        private PositionSample nearestInFamily(int side, double send) {
            var list = samples.get(side);
            int lo = 0, hi = list.size();
            while (lo < hi) {
                int mid = (lo + hi) >>> 1;
                if (list.get(mid).send < send) lo = mid + 1;
                else hi = mid;
            }
            var before = lo == 0 ? null : list.get(lo - 1);
            var after = lo == list.size() ? null : list.get(lo);
            if (before != null && (after == null || send - before.send <= after.send - send)) return before;
            return after;
        }

        PeriodicComboFightModel.PacketTimingPlan plan(int route, double send, double half, double apply,
                                                      double shift, Map<String, Integer> stats) {
            int side = route - 2;
            var spans = drains.get(side);
            var source = byHandler.get(side).get(apply);
            if (source != null) stats.merge("same_subtype_sample", 1, Integer::sum);
            else {
                var peer = reusePositionFamily ? nearestInFamily(side, send) : null;
                if (peer != null && Math.abs(peer.send - send) <= 2) source = peer;
                stats.merge(source == null ? "without_source_sample" : "other_position_subtype_sample", 1, Integer::sum);
            }
            double originalWire = source == null ? send + half + generatedDelay : source.wire;
            double wire = Math.max(send, originalWire + shift);
            double ready = wire + (source == null ? 0 : source.enqueued - source.wire);
            if (source != null && shift == 0 && ready <= source.apply) {
                stats.merge("source_drain", 1, Integer::sum);
                return new PeriodicComboFightModel.PacketTimingPlan(wire, source.apply, null);
            }
            int index = firstPossible(spans, ready);
            if (index < spans.size() && ready > spans.get(index).start) {
                stats.merge("ready_during_drain", 1, Integer::sum);
                if (ambiguity == InDrain.LATE) index++;
            }
            if (index == spans.size()) {
                stats.merge("no_covered_drain", 1, Integer::sum);
                return new PeriodicComboFightModel.PacketTimingPlan(wire, Math.max(ready, spans.get(index - 1).end + 60_000), null);
            }
            if (source != null && index == source.drain && ready <= source.apply) {
                stats.merge("source_drain", 1, Integer::sum);
                return new PeriodicComboFightModel.PacketTimingPlan(wire, source.apply, null);
            }
            if (source != null && index != source.drain) stats.merge("changed_drain", 1, Integer::sum);
            return new PeriodicComboFightModel.PacketTimingPlan(wire, spans.get(index).end, null);
        }
    }

    private record ServerSample(double apply, double wire, double enqueued, int drain) {}

    private static final class AttackQueue {
        final List<Drain> drains = new ArrayList<>();
        final List<List<ServerSample>> jobs = new ArrayList<>();
        final List<Map<Double, ServerSample>> attacks = List.of(new HashMap<>(), new HashMap<>());
        final double generatedDelay;

        AttackQueue(Path path) throws java.io.IOException {
            generatedDelay = Double.parseDouble(System.getProperty("lab.fork_generated_attack_delay_ms", ".35"));
            if (!Double.isFinite(generatedDelay) || generatedDelay < 0)
                throw new IllegalArgumentException("Expected a nonnegative generated attack delay");
            for (String line : Files.readAllLines(path)) {
                var p = line.split("\t");
                if (p[0].equals("D")) {
                    drains.add(new Drain(Double.parseDouble(p[1]), Double.parseDouble(p[2])));
                    jobs.add(new ArrayList<>());
                } else if (p[0].equals("P")) {
                    int route = Integer.parseInt(p[1]), id = Integer.parseInt(p[2]);
                    var sample = new ServerSample(Double.parseDouble(p[4]), Double.parseDouble(p[5]),
                            Double.parseDouble(p[6]), Integer.parseInt(p[7]));
                    jobs.get(sample.drain).add(sample);
                    if (id == 2 && attacks.get(route).put(sample.apply, sample) != null)
                        throw new IllegalArgumentException("Attack handlers share a source timestamp");
                } else throw new IllegalArgumentException("Unknown server-queue row " + p[0]);
            }
            if (drains.isEmpty()) throw new IllegalArgumentException("No native server drains");
            for (var batch : jobs) {
                batch.sort(Comparator.comparingDouble(ServerSample::enqueued));
                for (int i = 1; i < batch.size(); i++) {
                    if (batch.get(i - 1).apply >= batch.get(i).apply)
                        throw new IllegalArgumentException("Server handler order disagrees with native enqueue order");
                }
            }
        }

        boolean serves(double time) {
            int batch = firstPossible(drains, time);
            return batch < drains.size() && drains.get(batch).start <= time;
        }

        private double serviceTime(int index, double ready) {
            var drain = drains.get(index);
            var batch = jobs.get(index);
            int next = 0;
            while (next < batch.size() && batch.get(next).enqueued < ready) next++;
            var after = next == batch.size() ? null : batch.get(next);
            if (after != null && after.enqueued == ready) return after.apply;
            var before = next == 0 ? null : batch.get(next - 1);
            double startReady = before == null ? drain.start - 50 : before.enqueued;
            double endReady = after == null ? drain.start : after.enqueued;
            double start = before == null ? drain.start : before.apply;
            double end = after == null ? drain.end : after.apply;
            double frac = Math.max(0, Math.min(1, (ready - startReady) / (endReady - startReady)));
            return Math.max(Math.nextUp(start), Math.min(Math.nextDown(end), start + frac * (end - start)));
        }

        PeriodicComboFightModel.PacketTimingPlan plan(int route, double send, double half, double apply,
                                                      double shift, Map<String, Integer> stats) {
            var source = attacks.get(route).get(apply);
            stats.merge(source == null ? "without_source_sample" : "source_sample", 1, Integer::sum);
            double wire = Math.max(send, (source == null ? send + half + generatedDelay : source.wire) + shift);
            double ready = wire + (source == null ? 0 : source.enqueued - source.wire);
            if (source != null && shift == 0 && ready <= source.apply) {
                stats.merge("exact_source_handler", 1, Integer::sum);
                return new PeriodicComboFightModel.PacketTimingPlan(wire, source.apply, null);
            }
            int index = firstPossible(drains, ready);
            if (index < drains.size() && ready > drains.get(index).start) {
                stats.merge("ready_during_drain", 1, Integer::sum);
                index++;
            }
            if (index == drains.size()) {
                stats.merge("no_covered_drain", 1, Integer::sum);
                return new PeriodicComboFightModel.PacketTimingPlan(wire, Math.max(ready, drains.get(index - 1).end + 60_000), null);
            }
            if (source != null) stats.merge(index == source.drain ? "same_drain" : "changed_drain", 1, Integer::sum);
            return new PeriodicComboFightModel.PacketTimingPlan(wire, serviceTime(index, ready), null);
        }
    }

    /** Match only route, kind and send opportunity. Changed payloads are generated by the model. */
    private record Timing(List<List<Sample>> samples, double shift, DeliveryScope scope, boolean first,
                          boolean ordered, DeliveryFilter filter, int jointMask, boolean positionOnly, PositionQueue positionQueue,
                          Map<String, Integer> positionStats, AttackQueue attackQueue,
                          Map<String, Integer> attackStats) implements PeriodicComboFightModel.PacketTiming {
        Timing(List<List<Sample>> samples, double shift, DeliveryScope scope, boolean first,
               boolean ordered, DeliveryFilter filter) {
            this(samples, shift, scope, first, ordered, filter, -1, false, null, null, null, null);
        }

        Timing forFork(PositionQueue positionQueue, Map<String, Integer> positionStats,
                       AttackQueue attackQueue, Map<String, Integer> attackStats) {
            return new Timing(samples, shift, scope, first, ordered, filter, jointMask, positionOnly, positionQueue,
                    positionStats, attackQueue, attackStats);
        }

        String label() {
            if (jointMask < 0) return scope.toString();
            return positionOnly ? "POSITION-PAIR-" + (jointMask >> 2) : "JOINT-" + jointMask;
        }

        private double shiftFor(int route, int packetId, double apply) {
            if (jointMask < 0) return scope.includes(route, first) && filter.includes(route, packetId, apply) ? shift : 0;
            if (positionOnly && route < 2) return 0;
            var group = route < 2 ? PacketGroup.ATTACK : PacketGroup.POSITION;
            if (!group.includes(route, packetId) || !filter.canMove(apply)) return 0;
            // In each direction the nominated seat comes first, then the defender.
            int bit = first ? route : route ^ 1;
            return (jointMask & (1 << bit)) == 0 ? -shift : shift;
        }

        @Override public double arrival(int route, double send, double half) { return send + half + .35; }

        @Override public PeriodicComboFightModel.PacketTimingPlan planForPacket(
                int route, double send, double half, PeriodicComboFightModel.TimingSemantic semantic, int packetId) {
            int kind = packetId >= 3 && packetId <= 6 ? 3 : packetId;
            var nearest = closest(samples.get(route * 32 + kind), send);
            double apply = arrival(route, send, half);
            if (nearest != null && Math.abs(nearest.send - send) <= 2) apply = nearest.apply;
            else if (packetId == 11) {
                var position = closest(samples.get(route * 32 + 3), send);
                if (position != null && Math.abs(position.send - send) <= 2) apply = position.apply - .001;
            }
            if (positionQueue != null && PacketGroup.POSITION.includes(route, packetId))
                return positionQueue.plan(route, send, half, apply, shiftFor(route, packetId, apply), positionStats);
            if (attackQueue != null && PacketGroup.ATTACK.includes(route, packetId))
                return attackQueue.plan(route, send, half, apply, shiftFor(route, packetId, apply), attackStats);
            double delivery = Math.max(send, apply + shiftFor(route, packetId, apply));
            return new PeriodicComboFightModel.PacketTimingPlan(delivery, ordered ? delivery : null, null);
        }

        private static Sample closest(List<Sample> list, double time) {
            int lo = 0, hi = list.size();
            while (lo < hi) {
                int mid = (lo + hi) >>> 1;
                if (list.get(mid).send < time) lo = mid + 1;
                else hi = mid;
            }
            if (lo == 0) return list.isEmpty() ? null : list.get(0);
            if (lo == list.size()) return list.get(lo - 1);
            var before = list.get(lo - 1);
            var after = list.get(lo);
            return time - before.send <= after.send - time ? before : after;
        }

        @Override public PeriodicComboFightModel.PacketTiming deepCopy() { return this; }
    }

    private static int wtap(DuelEnv.Player tap, PeriodicComboFightModel.State s, boolean first, double time) {
        var b = first ? s.a : s.b;
        var v = first ? s.ab : s.ba;
        return tap.input(new PeriodicComboClock.Observation(time, 0, b.q, v.x, v.y, v.z, b.sprint, b.headYaw,
                b.lastContactTime, b.localContacts, v.lastHurtTime, b.lastHurtTime)).key();
    }

    private static void apply(PeriodicComboFightModel.State s, PeriodicComboFightModel.Config c, Event event) {
        if (event.kind.startsWith("POSITION_DRAIN")) {
            boolean a = event.kind.endsWith("A");
            int route = a ? 2 : 3;
            if (s.packets.stream().anyMatch(p -> PeriodicComboTransport.route(p.kind) == route && p.ready == event.time))
                PeriodicComboFightModel.receive(s, event.time, a);
            return;
        }
        PeriodicComboReplay.step(s, c, event.words, event.time, event.kind, event.kind.endsWith("A"));
    }

    private static void calibrateUploadOrigins(PeriodicComboFightModel.State initial,
                                               PeriodicComboFightModel.Config config,
                                               List<Event> events, List<List<Sample>> samples, double end,
                                               boolean allUploads) {
        var state = initial.deepCopy();
        var transport = state.transport;
        for (int route = 0; route < 2; route++) {
            samples.get(route * 32 + 2).clear();
            if (allUploads) {
                samples.get(route * 32 + 3).clear();
                samples.get(route * 32 + 11).clear();
            }
        }
        for (var event : events) {
            if (event.time > end) break;
            transport.assertDeliveredBy(event.time);
            int a = transport.next[0], b = transport.next[1];
            apply(state, config, event);
            for (int route = 0; route < 2; route++) {
                int from = route == 0 ? a : b;
                for (int index = from; index < transport.next[route]; index++) {
                    var packet = transport.routes[route].get(index);
                    if ((allUploads || packet.packetId == 2) && Double.isFinite(packet.delivery)) {
                        int kind = packet.packetId >= 3 && packet.packetId <= 6 ? 3 : packet.packetId;
                        samples.get(route * 32 + kind).add(new Sample(event.time, packet.delivery));
                    }
                }
            }
        }
    }

    private static Event approachMove(Event event, String word, int head, int key) {
        if (head >= word.length()) return event.key(key);
        char control = word.charAt(head);
        int strafe = APPROACH_STRAFES.indexOf(control);
        if (strafe >= 0) return event.movement(strafe / 2, strafe % 2 == 0 ? .98f : -.98f);
        int next = APPROACH_KEYS.indexOf(control) - 1;
        return event.key(next < 0 ? key : next);
    }

    private static void trackLook(PeriodicComboFightModel.Body body, PeriodicComboFightModel.View target,
                                  double elapsed, float yawOffset) {
        double dx = target.x - body.q.x(), dz = target.z - body.q.z();
        double eye = body.q.y() + PeriodicComboFightModel.EYE;
        double targetY = Math.max(target.y + .15, Math.min(eye, target.y + 1.65));
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) -Math.toDegrees(Math.atan2(targetY - eye, Math.hypot(dx, dz)));
        var mouse = MouseLook.plan(body.yaw, body.pitch, yaw + yawOffset, pitch, .5f, false, elapsed);
        body.yaw = mouse.yawAfter(body.yaw);
        body.pitch = mouse.pitchAfter(body.pitch);
    }

    private static Map<String, Object> fork(PeriodicComboFightModel.State prefix, PeriodicComboFightModel.Config c,
                                            List<Event> events, int start, double end, boolean first, String arm,
                                            Timing timing, DuelEnv.Player primed, DuelEnv.Player defender,
                                            ImpulseProbe probe, KeyOverride override, PositionQueue positionQueue, AttackQueue attackQueue,
                                            LandingProbe landingProbe) {
        var s = prefix.deepCopy();
        s.transport = null;
        s.nominalHalfRtt = new double[]{c.ownRtt/2, c.otherRtt/2, c.ownRtt/2, c.otherRtt/2};
        var positionStats = new java.util.LinkedHashMap<String, Integer>();
        var attackStats = new java.util.LinkedHashMap<String, Integer>();
        s.packetTiming = timing.forFork(positionQueue, positionStats, attackQueue, attackStats);
        var tap = primed.copy();
        var otherTap = defender.copy();
        boolean shadow = SHADOW_APPROACH && !arm.equals("recorded");
        var hits = new ArrayList<Object>();
        var steps = new ArrayList<Object>();
        var sprint = new ArrayList<Object>();
        var clicks = new ArrayList<Object>();
        var attempts = new ArrayList<Object>();
        var linkedHits = new ArrayList<Object>();
        boolean packetLog = Boolean.getBoolean("lab.fork_packet_log");
        boolean reachTrace = Boolean.getBoolean("lab.fork_reach_trace");
        var processed = new ArrayList<Object>();
        var reachStates = new ArrayList<Object>();
        double ownFrame = Double.NaN, otherFrame = Double.NaN;
        int localHead = 0, presses = 0, targets = 0, otherPresses = 0, otherTargets = 0;
        long selectedLandingAttack = -1;
        PeriodicComboFightModel.Packet landingVelocity = null;
        List<Object> landingApplication = List.of();
        List<Object> impactStep = List.of();
        boolean lookAway = landingProbe != null && arm.equals("miss-original-press");
        var selection = new ContactSelection(arm, events.get(start).time);
        List<Object> firstContactServerState = List.of();
        boolean crossed = arm.startsWith("after-approach-");
        boolean holdForLanding = landingProbe != null && (arm.equals("velocity-after-travel") || crossed);
        String approach = crossed ? arm.substring("after-".length()) : arm;
        String word = approach.startsWith("approach-") ? approach.substring("approach-".length()) : "";
        int split = word.indexOf('/');
        String beforeCue = split < 0 ? word : word.substring(0, split);
        String afterCue = split < 0 ? "" : word.substring(split+1);
        int cueHead = 0;
        double movementCue = Double.NaN;
        float cameraOffset = arm.startsWith(CAMERA_PREFIX) ? Float.parseFloat(arm.substring(CAMERA_PREFIX.length())) : 0;
        double cameraUntil = events.get(start).time + Double.parseDouble(System.getProperty("lab.fork_camera_duration_ms", "100"));
        var outgoingImpulses = new ArrayList<Object>();
        for (int i = start; i < events.size();) {
            var event = events.get(i);
            double nextService = event.time;
            if (attackQueue != null) for (var packet : s.packets) {
                if ((packet.kind == 3 || packet.kind == 7) && packet.ready < nextService && attackQueue.serves(packet.ready))
                    nextService = packet.ready;
            }
            if (nextService < event.time) event = new Event(nextService, "SERVER_APPLY", new String[0]);
            else i++;
            if (event.time > end) break;
            boolean ours = first == event.kind.endsWith("A");
            boolean isMove = event.kind.startsWith("MOVE");
            boolean firstOwnStep = ours && isMove && localHead == 0;
            var own = first ? s.a : s.b;
            boolean pressed = event.kind.startsWith("INPUT") && event.words[5].equals("1");
            var clicking = ours ? own : first ? s.b : s.a;
            var opposite = event.kind.endsWith("A") ? s.ab : s.ba;
            if (shadow && APPROACH_AIM.tracks(ours)) {
                if (event.kind.startsWith("FRAME")) {
                    double previous = ours ? ownFrame : otherFrame;
                    float offset = ours && event.time < cameraUntil ? cameraOffset : 0;
                    trackLook(clicking, opposite, Double.isNaN(previous) ? 0 : event.time - previous, offset);
                    if (ours) ownFrame = event.time;
                    else otherFrame = event.time;
                } else event = event.trackedLook(clicking);
            }
            var beforeClick = clicking.q;
            boolean sprintBeforeClick = clicking.sprint;
            if (ours && isMove) {
                int key = Integer.parseInt(event.words[2]);
                key = switch (arm) {
                    case "wtap" -> wtap(tap, s, first, event.time);
                    case "ctrl" -> localHead < 2 && key == 1 ? 2 : key;
                    case "release-restart", "reset-plus-ctrl" -> localHead == 0 ? 0 : localHead == 1 ? 2 : key;
                    default -> shadow ? wtap(tap, s, first, event.time) : key;
                };
                if (override != null && (arm.equals(override.arm()) || arm.equals("reset-plus-ctrl")
                        || arm.equals("release-with-brake")) && event.time == override.time)
                    key = override.key;
                if (APPROACH && override != null && arm.equals("hold-without-brake") && event.time == override.time)
                    key = PeriodicComboFightModel.S;
                // A word with two parts resumes at the next own movement after
                // the attacker has actually received a new target-hurt status.
                double visibleHurt = (first ? s.ab : s.ba).lastHurtTime;
                if (landingProbe != null && !afterCue.isEmpty() && Double.isNaN(movementCue) && visibleHurt >= landingProbe.press)
                    movementCue = visibleHurt;
                if (Double.isNaN(movementCue)) event = approachMove(event, beforeCue, localHead, key);
                else event = approachMove(event, afterCue, cueHead++, key);
                if (IMPACT_JUMP && shadow) event = event.jump(firstOwnStep && arm.equals("impact-jump"));
                localHead++;
            } else if (isMove && shadow) {
                event = event.key(wtap(otherTap, s, !first, event.time));
            }
            var before = own.q;
            int a = s.acceptedA, b = s.acceptedB, af = s.hits, bf = s.incomingHits;
            long sequence = s.seq, contacts = clicking.localContacts;
            List<PeriodicComboFightModel.Packet> pending = packetLog ? List.copyOf(s.packets) : List.of();
            boolean originalPress = landingProbe != null && ours && pressed && Math.abs(event.time - landingProbe.press) < .002;
            boolean firstContactVeto = CONTACT_VETO_MS > 0 && ours && pressed && selection.onPress(event.time, clicking, opposite);
            if (CONTACT_HOLD_MS > 0 && selection.firstContact == event.time && ours && pressed) {
                // Offline annotation only. The contact decision above uses the owning client's state.
                int incoming = first ? s.acceptedB - prefix.acceptedB : s.acceptedA - prefix.acceptedA;
                int incomingFresh = first ? s.incomingHits - prefix.incomingHits : s.hits - prefix.hits;
                int incomingAttackKind = first ? 7 : 3;
                boolean attackPending = s.packets.stream().anyMatch(p -> p.kind == incomingAttackKind);
                firstContactServerState = List.of(incoming, incomingFresh, attackPending);
            }
            boolean veto = originalPress && lookAway || firstContactVeto;
            float normalHead = clicking.headYaw;
            if (veto) clicking.headYaw += 180;
            var receiverBefore = (first ? s.b : s.a).q;
            apply(s, c, event);
            if (CONTACT_HOLD_MS > 0 && ours && own.q != before && (event.kind.startsWith("APPLY") ||
                    event.kind.startsWith("POSITION_DRAIN") || event.kind.startsWith("FRAME"))) {
                // Only an applied self-velocity packet replaces the owning body at these service stages.
                selection.lastVelocity = event.time;
            }
            if (IMPACT_JUMP && firstOwnStep) {
                var after = own.q;
                impactStep = List.of(event.time, event.words[5].equals("1"), before.onGround(), before.y(),
                        before.vx(), before.vy(), before.vz(), after.x()-before.x(), after.y()-before.y(),
                        after.z()-before.z(), after.vy());
            }
            if (landingVelocity != null && !s.packets.contains(landingVelocity) && landingApplication.isEmpty()) {
                landingApplication = List.of(event.time, receiverBefore.y(), receiverBefore.vy(), receiverBefore.onGround());
            }
            if (originalPress && clicking.localContacts > contacts) selectedLandingAttack = s.seq-1;
            boolean thisShot = selectedLandingAttack >= 0 && (first ? s.hits > af : s.incomingHits > bf) &&
                    (first ? s.lastFreshPacketA : s.lastFreshPacketB) == selectedLandingAttack;
            if (thisShot) {
                var generated = s.packets.stream().filter(p -> p.sequence >= sequence && p.kind == (first ? 4 : 8)).toList();
                if (generated.size() == 1) {
                    landingVelocity = generated.get(0);
                    if (holdForLanding) landingVelocity.ready = Math.max(landingVelocity.ready, Math.nextUp(landingProbe.firstTravel));
                }
            }
            if (reachTrace && !event.kind.startsWith("FRAME")) {
                var target = first ? s.b.q : s.a.q;
                var theirView = first ? s.ba : s.ab;
                var ourView = first ? s.ab : s.ba;
                reachStates.add(List.of(event.time, event.kind,
                        own.q.x(), own.q.y(), own.q.z(), target.x(), target.y(), target.z(),
                        first ? s.serverAx : s.serverBx, first ? s.serverAz : s.serverBz,
                        theirView.x, theirView.y, theirView.z, theirView.gx, theirView.gy, theirView.gz, theirView.left,
                        ourView.x, ourView.y, ourView.z));
            }
            for (var packet : pending) {
                if (!s.packets.contains(packet)) {
                    processed.add(List.of(event.time, event.kind, packet.kind, packet.packetId,
                            packet.due, packet.ready, packet.sequence, packet.vx, packet.vy, packet.vz));
                }
            }
            if (probe != null && (arm.equals("launch-only") || arm.equals("launch-and-brake")) && event.time == probe.attack) {
                replaceLaunch(s, first, event, sequence, probe.sprintMagnitude);
            }
            if (probe != null && (arm.equals("brake-only") || arm.equals("launch-and-brake")) && event.time == probe.contact) {
                if (!ours || !event.kind.startsWith("INPUT")) throw new AssertionError("Expected own input");
                if (own.localContacts > contacts) own.velocity(.6 * own.q.vx(), own.q.vy(), .6 * own.q.vz());
            }
            if (pressed) {
                boolean contact = clicking.localContacts > contacts;
                if (veto && contact) throw new IllegalStateException("Look-away probe still selected the target");
                if (override != null && event.time == override.brakeAt && ours && contact) {
                    double horizontalFactor = switch (arm) {
                        case "hold-without-brake" -> 1;
                        case "release-with-brake" -> .6;
                        default -> sprintBeforeClick ? .6 : 1;
                    };
                    if (horizontalFactor != (sprintBeforeClick ? .6 : 1))
                        own.velocity(horizontalFactor * beforeClick.vx(), own.q.vy(), horizontalFactor * beforeClick.vz());
                }
                double dx = opposite.x - beforeClick.x(), dz = opposite.z - beforeClick.z();
                double gap = Math.hypot(dx, dz);
                double toward = gap == 0 ? 0 : (beforeClick.vx() * dx + beforeClick.vz() * dz) / gap;
                if (ours) {
                    presses++;
                    if (contact) targets++;
                } else {
                    otherPresses++;
                    if (contact) otherTargets++;
                }
                clicks.add(List.of(event.time, ours, contact, sprintBeforeClick,
                        beforeClick.vx(), beforeClick.vz(), clicking.q.vx(), clicking.q.vz(),
                        PeriodicComboFightModel.nearest(beforeClick, opposite), toward));
                if (APPROACH) {
                    long packet = contact ? s.seq - 1 : -1;
                    double ray = PeriodicComboFightModel.ray(clicking, opposite);
                    attempts.add(List.of(event.time, ours, contact, PeriodicComboFightModel.nearest(beforeClick, opposite),
                            Double.isFinite(ray) ? ray : -1.0, packet, beforeClick.onGround(), toward));
                }
            }
            if (veto) clicking.headYaw = normalHead;
            if (ours && isMove) {
                steps.add(List.of(event.time, Integer.parseInt(event.words[2]), before.vx(),
                        own.q.x() - before.x(), own.q.vx(), own.sprint, first ? s.serverSprint : s.serverSprintB));
                for (var packet : s.packets) if (packet.sequence >= sequence && packet.kind == (first ? 2 : 9))
                    sprint.add(List.of(event.time, packet.due, packet.flag));
            }
            if (s.acceptedA != a) hits.add(hit(event.time, first, s.acceptedA - a, s.hits > af, s.lastFreshB));
            if (s.acceptedB != b) hits.add(hit(event.time, !first, s.acceptedB - b, s.incomingHits > bf, s.lastFreshA));
            if (!CAMERA_OFFSETS.isEmpty() && (first ? s.hits > af : s.incomingHits > bf)) {
                var impulse = first ? s.lastFreshB : s.lastFreshA;
                double dx = s.serverBx - s.serverAx, dz = s.serverBz - s.serverAz;
                outgoingImpulses.add(List.of(event.time, impulse.x(), impulse.y(), impulse.z(),
                        first ? dx : -dx, first ? dz : -dz, first ? s.serverYawA : s.serverYawB));
            }
            if (APPROACH) {
                if (s.hits > af) linkedHits.add(List.of(event.time, first, true, s.lastFreshPacketA));
                if (s.incomingHits > bf) linkedHits.add(List.of(event.time, !first, true, s.lastFreshPacketB));
                if (s.acceptedA - a > s.hits - af) linkedHits.add(List.of(event.time, first, false, s.lastAcceptedPacketA));
                if (s.acceptedB - b > s.incomingHits - bf) linkedHits.add(List.of(event.time, !first, false, s.lastAcceptedPacketB));
            }
        }
        int own = first ? s.acceptedA - prefix.acceptedA : s.acceptedB - prefix.acceptedB;
        int other = first ? s.acceptedB - prefix.acceptedB : s.acceptedA - prefix.acceptedA;
        var result = new java.util.LinkedHashMap<String, Object>(Map.of(
                "score", List.of(own, other), "hits", hits, "move", steps, "sprint", sprint,
                "counts", List.of(presses, otherPresses), "contacts", List.of(targets, otherTargets), "clicks", clicks));
        if (packetLog) result.put("processed", processed);
        if (reachTrace) result.put("reachStates", reachStates);
        if (positionQueue != null) result.put("positionService", positionStats);
        if (attackQueue != null) result.put("attackService", attackStats);
        if (APPROACH) {
            result.put("attempts", attempts);
            result.put("linkedHits", linkedHits);
            if (COMPACT_APPROACH && !arm.equals("recorded")) {
                for (String key : List.of("move", "sprint", "clicks")) result.remove(key);
                if (!KEEP_LAUNCHES) result.remove("hits");
            }
        }
        if (landingProbe != null) {
            result.put("landingApplication", landingApplication);
            result.put("movementCue", Double.isFinite(movementCue) ? movementCue : null);
        }
        if (IMPACT_JUMP) result.put("impactStep", impactStep);
        if (!CAMERA_OFFSETS.isEmpty()) result.put("outgoingImpulses", outgoingImpulses);
        if (selection.skip) result.put("vetoedPress", Double.isFinite(selection.firstSkipped) ? selection.firstSkipped : null);
        if (CONTACT_HOLD_MS > 0) {
            result.put("firstContactObservation", selection.firstObservation);
            result.put("firstContactServerState", firstContactServerState);
            result.put("contactSelectionSkips", selection.skipped);
            result.put("contactSelectionGuard", selection.stoppedByGuard);
        }
        return result;
    }

    private static void replaceLaunch(PeriodicComboFightModel.State s, boolean first, Event event,
                                      long sequence, double magnitude) {
        var launch = first ? s.lastFreshB : s.lastFreshA;
        if (launch == null || launch.time() != event.time || launch.z() != 0) {
            throw new AssertionError("Expected the source trade's fresh axial launch");
        }
        var packets = s.packets.stream().filter(p -> p.sequence >= sequence && p.kind == (first ? 4 : 8)).toList();
        if (packets.size() != 1) throw new AssertionError("Missing source velocity packet");
        var p = packets.get(0);
        p.vx = Math.copySign(magnitude, p.vx);
        var replaced = new PeriodicComboFightModel.FreshImpulse(launch.time(), launch.victimBefore(), p.vx, p.vy, p.vz);
        if (first) s.lastFreshB = replaced;
        else s.lastFreshA = replaced;
    }

    private static List<Object> hit(double time, boolean ours, int count, boolean fresh,
                                    PeriodicComboFightModel.FreshImpulse packet) {
        if (fresh && packet == null) throw new AssertionError("Missing launch in trace");
        return List.of(time, ours, count, fresh, fresh ? Math.hypot(packet.x(), packet.z()) : 0, fresh ? packet.y() : 0);
    }

    public static void main(String[] args) throws Exception {
        var tape = Path.of(args[0]);
        boolean first = args[1].equals("a");
        double begin = Double.parseDouble(args[2]), cutoff = Double.parseDouble(args[3]), end = Double.parseDouble(args[4]);
        double approachHorizon = Double.parseDouble(System.getProperty("lab.fork_approach_horizon_ms", "2200"));
        if (!(approachHorizon > 0) || !Double.isFinite(approachHorizon))
            throw new IllegalArgumentException("Expected a finite positive approach horizon");
        var landingProbe = LandingProbe.fromProperty();
        var probe = args.length == 8 ? new ImpulseProbe(
                Double.parseDouble(args[5]), Double.parseDouble(args[6]), Double.parseDouble(args[7])) : null;
        var override = args.length == 6 || args.length == 7 || args.length == 9
                ? new KeyOverride(Double.parseDouble(args[5]), args.length >= 7 ? Integer.parseInt(args[6]) : 2,
                        args.length == 9 ? Double.parseDouble(args[8]) : Double.NaN) : null;
        if (override != null && override.key != 0 && override.key != 2)
            throw new IllegalArgumentException("A movement override must release or request sprint");
        if (args.length == 9 && (!args[7].equals("brake") || override.key != 0
                || !(override.time < override.brakeAt && override.brakeAt < end)))
            throw new IllegalArgumentException("Brake probe requires a recorded sprint hold and a later consumed click");
        double[] phases = probe == null
                ? Arrays.stream(System.getProperty("lab.fork_delivery_shifts_ms", "0,-.5,.5").split(","))
                        .mapToDouble(Double::parseDouble).toArray()
                : new double[]{0};
        if (phases.length == 0 || Arrays.stream(phases).anyMatch(phase -> !Double.isFinite(phase)))
            throw new IllegalArgumentException("Expected finite packet delivery shifts in milliseconds");
        var scope = DeliveryScope.valueOf(System.getProperty("lab.fork_delivery_scope", "ALL"));
        boolean ordered = Boolean.getBoolean("lab.fork_ordered_delivery");
        var filter = DeliveryFilter.settings();
        String path = System.getProperty("lab.fork_client_position_queue", "");
        var positionQueue = path.isEmpty() ? null : new PositionQueue(Path.of(path),
                InDrain.valueOf(System.getProperty("lab.fork_position_in_drain", "LATE")));
        String attackPath = System.getProperty("lab.fork_server_attack_queue", "");
        var attackQueue = attackPath.isEmpty() ? null : new AttackQueue(Path.of(attackPath));
        var selectedCuts = Arrays.stream(System.getProperty("lab.fork_selected_approach_ms", "").split(","))
                .filter(x -> !x.isEmpty()).map(Double::parseDouble).toList();
        var approachWords = Arrays.stream(System.getProperty("lab.fork_approach_words", "").split(","))
                .filter(s -> !s.isEmpty()).toList();
        var cameraOffsets = Arrays.stream(CAMERA_OFFSETS.split(","))
                .filter(s -> !s.isEmpty()).map(Float::parseFloat).distinct().toList();
        var cameraDuration = Double.parseDouble(System.getProperty("lab.fork_camera_duration_ms", "100"));
        if (!Double.isFinite(CONTACT_VETO_MS) || CONTACT_VETO_MS < 0 || (CONTACT_VETO_MS > 0 &&
                (!APPROACH || !SHADOW_APPROACH || APPROACH_AIM != AimMode.BOTH)))
            throw new IllegalArgumentException("First-contact skip needs a finite window and tracked W-tap arms");
        if (!Double.isFinite(CONTACT_HOLD_MS) || CONTACT_HOLD_MS < 0 || !Double.isFinite(CONTACT_RECOIL_SPEED) ||
                CONTACT_RECOIL_SPEED < 0 || (CONTACT_HOLD_MS > 0 && CONTACT_VETO_MS == 0))
            throw new IllegalArgumentException("First-contact hold needs a finite positive gate and recoil setting");
        if (!cameraOffsets.isEmpty() && (!APPROACH || !SHADOW_APPROACH || APPROACH_AIM != AimMode.BOTH ||
                IMPACT_JUMP || landingProbe != null || !approachWords.isEmpty() || !Double.isFinite(cameraDuration) ||
                cameraDuration <= 0 || cameraOffsets.stream().anyMatch(d -> !Float.isFinite(d) || d == 0 || Math.abs(d) > 25)))
            throw new IllegalArgumentException("Camera comparison needs tracked aim on both sides and finite yaw pulses");
        double[] approachShift = Arrays.stream(System.getProperty("lab.fork_approach_shift_ms", "-2,2").split(","))
                .mapToDouble(Double::parseDouble).toArray();
        if (Arrays.stream(approachShift).anyMatch(d -> d == 0 || !Double.isFinite(d)))
            throw new IllegalArgumentException("Approach perturbations must be finite and nonzero");
        double jointRadius = Double.parseDouble(System.getProperty("lab.fork_approach_joint_ms", "0"));
        if (!Double.isFinite(jointRadius) || jointRadius < 0)
            throw new IllegalArgumentException("Expected a finite nonnegative joint receipt variation");
        double[] positionPairs = Arrays.stream(System.getProperty("lab.fork_position_pairs_ms", "").split(","))
                .filter(v -> !v.isEmpty()).mapToDouble(Double::parseDouble).toArray();
        if (Arrays.stream(positionPairs).anyMatch(d -> !Double.isFinite(d) || d <= 0))
            throw new IllegalArgumentException("Position-pair variations must be finite and positive");
        if (CROSS_LANDING && landingProbe == null)
            throw new IllegalArgumentException("Crossed landing comparison needs a controlled receipt");
        if (IMPACT_JUMP && (!APPROACH || !SHADOW_APPROACH || landingProbe != null || !approachWords.isEmpty()))
            throw new IllegalArgumentException("An impact jump needs a paired stock approach and no movement words");
        if (approachWords.stream().anyMatch(word -> word.length() > MAX_APPROACH_HEADS ||
                (word.indexOf('/') >= 0 && (landingProbe == null || word.indexOf('/') != word.lastIndexOf('/') || word.endsWith("/"))) ||
                word.chars().anyMatch(key -> key != '/' && APPROACH_KEYS.indexOf(key) < 0 && APPROACH_STRAFES.indexOf(key) < 0)))
            throw new IllegalArgumentException("Invalid approach word");
        var transport = new PeriodicComboTransport();
        var config = new PeriodicComboFightModel.Config();
        config.forceContacts = false;
        var samples = new ArrayList<List<Sample>>();
        for (int i = 0; i < 128; i++) samples.add(new ArrayList<>());
        var events = new ArrayList<Event>();
        PeriodicComboFightModel.State s = null;
        for (String line : Files.readAllLines(tape)) {
            var p = line.split("\t");
            if (p[0].equals("NETWORK")) {
                transport.add(p);
                var entry = new PeriodicComboTransport.Expected(p);
                if (!Double.isFinite(entry.delivery)) continue;
                int id = entry.packetId >= 3 && entry.packetId <= 6 ? 3 : entry.packetId;
                samples.get(PeriodicComboTransport.route(entry.kind) * 32 + id).add(new Sample(entry.send,entry.delivery));
            } else if (p[0].equals("INIT")) {
                s = PeriodicComboReplay.initialState(p, config, transport);
            } else {
                events.add(new Event(Double.parseDouble(p[0]), p[1], p));
            }
        }
        if (s == null) throw new IllegalArgumentException("No initial state");
        boolean uploadOrigins = Boolean.getBoolean("lab.fork_calibrate_upload_origins");
        if (uploadOrigins || Boolean.getBoolean("lab.fork_calibrate_attack_origin"))
            calibrateUploadOrigins(s, config, events, samples, end, uploadOrigins);
        if (positionQueue != null) positionQueue.addDrainEvents(events);
        for (var list : samples) list.sort(java.util.Comparator.comparingDouble(Sample::send));
        var tap = new DuelEnv.Player("wtap", 0, (int)(first ? config.ownRtt : config.otherRtt), 0);
        var defenderTap = new DuelEnv.Player("wtap", 0, (int)(first ? config.otherRtt : config.ownRtt), 0);
        for (int i = 0; i < events.size(); i++) {
            var event = events.get(i);
            if (event.time > cutoff) break;
            if (event.kind.startsWith("POSITION_DRAIN")) continue;
            transport.assertDeliveredBy(event.time);
            if (event.kind.equals(first ? "MOVEA" : "MOVEB")) {
                if (begin <= event.time && event.time <= cutoff &&
                        (!APPROACH || selectedCuts.contains(event.time))) {
                    var labels = new ArrayList<>(List.of("recorded", "wtap", "release-restart"));
                    if (APPROACH) {
                        labels = new ArrayList<>(List.of("recorded"));
                        if (SHADOW_APPROACH) labels.add("shadow-wtap");
                        if (landingProbe != null) labels.addAll(List.of("velocity-after-travel", "miss-original-press"));
                        if (override != null && Double.isFinite(override.brakeAt))
                            labels.addAll(List.of("hold-without-brake", "release-with-brake"));
                        if (IMPACT_JUMP) labels.add("impact-jump");
                        else if (!cameraOffsets.isEmpty()) {
                            for (float offset : cameraOffsets) labels.add(CAMERA_PREFIX + offset);
                        } else if (CONTACT_VETO_MS > 0) {
                            labels.add(FIRST_CONTACT_VETO);
                            if (CONTACT_HOLD_MS > 0) labels.addAll(List.of(GUARDED_SKIP, CONTACT_HOLD, GUARDED_HOLD));
                        } else if (approachWords.isEmpty()) {
                            for (char firstKey : APPROACH_KEYS.toCharArray()) {
                                for (char secondKey : APPROACH_KEYS.toCharArray()) {
                                    if (firstKey == 'k' && secondKey == 'k') continue;
                                    labels.add("approach-" + firstKey + secondKey);
                                }
                            }
                        } else {
                            for (String word : approachWords) labels.add("approach-" + word);
                        }
                        if (CROSS_LANDING) {
                            var crossed = labels.stream().filter(name -> name.startsWith("approach-"))
                                    .map(name -> "after-" + name).toList();
                            labels.addAll(crossed);
                        }
                    } else if (probe != null) labels.addAll(List.of("launch-only", "brake-only", "launch-and-brake"));
                    else if (override != null) {
                        labels.addAll(List.of(override.arm(), "reset-plus-ctrl"));
                        if (Double.isFinite(override.brakeAt))
                            labels.addAll(List.of("hold-without-brake", "release-with-brake"));
                    }
                    else labels.add("ctrl");
                    var trialTimings = new ArrayList<Timing>();
                    if (APPROACH) {
                        trialTimings.add(new Timing(samples, 0, DeliveryScope.ALL, first, ordered, filter));
                        for (var route : List.of(DeliveryScope.OWN_DOWNLOAD, DeliveryScope.DEFENDER_DOWNLOAD))
                            for (double shift : approachShift)
                                trialTimings.add(new Timing(samples, shift, route, first, ordered, filter));
                        if (STRESS_APPROACH) {
                            var attacks = new DeliveryFilter(PacketGroup.ATTACK, filter.from, filter.until, filter.fixedReceipts);
                            for (var route : List.of(DeliveryScope.OWN_UPLOAD, DeliveryScope.DEFENDER_UPLOAD))
                                for (double shift : approachShift)
                                    trialTimings.add(new Timing(samples, shift, route, first, ordered, attacks));
                        }
                        if (jointRadius > 0) {
                            for (int mask = 0; mask < 16; mask++)
                                trialTimings.add(new Timing(samples, jointRadius, DeliveryScope.ALL, first, ordered, filter, mask, false, null, null, null, null));
                        }
                        for (double radius : positionPairs) for (int mask = 0; mask < 4; mask++)
                            trialTimings.add(new Timing(samples, radius, DeliveryScope.ALL, first, ordered, filter, mask << 2, true, null, null, null, null));
                    } else {
                        for (double phase : phases) trialTimings.add(new Timing(samples, phase, scope, first, ordered, filter));
                    }
                    for (var timing : trialTimings) {
                        var arms = new java.util.LinkedHashMap<String, Object>();
                        for (String arm : labels)
                            arms.put(arm, fork(s, config, events, i, APPROACH ? event.time + approachHorizon : end,
                                    first, arm, timing, tap, defenderTap, probe, override, positionQueue, attackQueue, landingProbe));
                        var row = new java.util.LinkedHashMap<>(Map.of("clock", tape.getFileName().toString(), "at", event.time,
                                "seat", args[1], "phase", timing.shift, "arms", arms,
                                "sourceSprint", first ? s.serverSprint : s.serverSprintB));
                        if (APPROACH) row.put("scope", timing.label());
                        System.out.println(Json.encode(row));
                    }
                }
                wtap(tap, s, first, event.time);
            } else if (SHADOW_APPROACH && event.kind.equals(first ? "MOVEB" : "MOVEA")) {
                wtap(defenderTap, s, !first, event.time);
            }
            apply(s, config, event);
        }
    }
}
