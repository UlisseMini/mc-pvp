package practice.core.replay;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static practice.core.JsonData.map;

/** Own-client attack motion measured at the two native click boundaries. */
public final class ReplayRecoil {
    private static final double MIN_BACKWARD_SPEED = .005;
    private static final double EPSILON = 1e-7;

    public static final class Count {
        public final int total, afterKnockback;
        public final boolean hasKnockback;
        private Count(int total, int afterKnockback, boolean hasKnockback) {
            this.total = total;
            this.afterKnockback = afterKnockback;
            this.hasKnockback = hasKnockback;
        }
    }

    private final List<Double> times = new ArrayList<>();
    private final List<Count> counts = new ArrayList<>();
    private final double[] scales;

    ReplayRecoil(List<ReplayRecording.Mark> marks, double startMs) {
        scales=marks.stream().filter(mark->mark.atMs>=startMs&&scalesHorizontalMotion(mark))
                .mapToDouble(mark->mark.atMs).toArray();
        int total = 0, afterKnockback = 0;
        double at = Double.NaN, vx = 0, vz = 0, speed = 0;
        for (ReplayRecording.Mark mark : marks) {
            if (mark.atMs < startMs) continue;
            Map<String, Object> details = mark.details;
            if (mark.kind.equals("join") || mark.kind.equals("position_reset")) {
                at = Double.NaN;
                afterKnockback = 0;
            } else if (mark.kind.equals("velocity") && Boolean.TRUE.equals(details.get("own"))) {
                vx = component(details.get("velocity"), 0);
                vz = component(details.get("velocity"), 2);
                speed = Math.hypot(vx, vz);
                at = speed >= MIN_BACKWARD_SPEED && "main_thread_handler".equals(details.get("time_source"))
                        ? mark.atMs : Double.NaN;
                afterKnockback = 0;
            } else if (mark.kind.equals("recoil_reduction")) {
                total++;
                double along = (number(details.get("motion_x")) * vx + number(details.get("motion_z")) * vz);
                if (number(details.get("click_start_ms")) > at && Boolean.TRUE.equals(details.get("own_hurt"))
                        && along > MIN_BACKWARD_SPEED * speed) afterKnockback++;
            } else {
                continue;
            }
            times.add(mark.atMs);
            counts.add(new Count(total, afterKnockback, Double.isFinite(at)));
        }
    }

    static boolean scalesHorizontalMotion(ReplayRecording.Mark mark){
        return mark.kind.equals("recoil_reduction")||mark.kind.equals("horizontal_attack_scale");
    }

    boolean flashingAt(double ms){
        int index=Arrays.binarySearch(scales,ms);
        if(index<0)index=-index-2;
        return index>=0&&ms-scales[index]<100;
    }

    Count at(double ms) {
        int lo = 0, hi = times.size();
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (times.get(mid) <= ms) lo = mid + 1;
            else hi = mid;
        }
        return lo == 0 ? new Count(0, 0, false) : counts.get(lo - 1);
    }

    static Map<String, Object> reduction(Map<String, Object> before, Map<String, Object> after) {
        Map<?, ?> a = object(before.get("self")), b = object(after.get("self"));
        Map<?, ?> ray = object(before.get("ray")), nextRay = object(after.get("ray"));
        Object target = ray.get("entity");
        if (!(a.get("id") instanceof Number) || !a.get("id").equals(b.get("id")) || !(target instanceof Number)
                || target.equals(a.get("id")) || !target.equals(nextRay.get("entity"))
                || !"ENTITY".equals(ray.get("type")) || !"ENTITY".equals(nextRay.get("type"))
                || !(a.get("pos") instanceof List) || !a.get("pos").equals(b.get("pos"))) return Collections.emptyMap();

        Object motion = a.get("motion"), result = b.get("motion");
        double x = component(motion, 0), z = component(motion, 2);
        double nextX = component(result, 0), nextZ = component(result, 2);
        if (Math.hypot(x,z)<=EPSILON*10 || !matches(nextX, .6 * x) || !matches(nextZ, .6 * z)
                || !matches(component(result, 1), component(motion, 1))) return Collections.emptyMap();

        double dx = component(ray.get("point"), 0) - component(a.get("pos"), 0);
        double dz = component(ray.get("point"), 2) - component(a.get("pos"), 2);
        double gap = Math.hypot(dx, dz);
        boolean backward=gap>EPSILON&&(x*dx+z*dz)/gap<-MIN_BACKWARD_SPEED;
        return map("target_entity_id", target, "motion_x", x, "motion_z", z,
                "cut", Math.hypot(x, z) - Math.hypot(nextX, nextZ), "own_hurt", number(a.get("hurtTime")) > 0,
                "backward",backward);
    }

    private static boolean matches(double a, double b) {
        return Double.isFinite(a) && Double.isFinite(b) && Math.abs(a - b) <= EPSILON;
    }

    private static Map<?, ?> object(Object value) {
        return value instanceof Map ? (Map<?, ?>) value : Collections.emptyMap();
    }

    private static double number(Object value) {
        return value instanceof Number ? ((Number) value).doubleValue() : Double.NaN;
    }

    private static double component(Object vector, int axis) {
        if (!(vector instanceof List)) return Double.NaN;
        List<?> values = (List<?>) vector;
        return values.size() > axis ? number(values.get(axis)) : Double.NaN;
    }
}
