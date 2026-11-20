package sword;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;

/** Check accepted hits using their recorded Java pre-hit state, on either clock. */
public final class ContactParity {
    public static void main(String[] args) throws Exception {
        System.out.println("id,hits,health_exact,motion_packet_present_exact,comparable_impulses,impulses_exact,overlap_rng_unknown,max_impulse_error");
        for (String file : args) compare(Path.of(file));
    }

    static void compare(Path file) throws Exception {
        var lines = Files.readAllLines(file);
        List<String> columns = Arrays.asList(lines.getFirst().split("\t"));
        var lastRaw = new HashMap<String, Float>();
        var previousHealth = new HashMap<String, Float>();
        int healthExact = 0, presenceExact = 0, comparable = 0, impulsesExact = 0, unknown = 0;
        double maxError = 0;
        for (String line : lines.subList(1, lines.size())) {
            var r = Arrays.asList(line.split("\t"));
            java.util.function.Function<String, String> get = name -> r.get(columns.indexOf(name));
            java.util.function.ToDoubleFunction<String> number = name -> Double.parseDouble(get.apply(name));
            String target = get.apply("defender");
            boolean fresh = number.applyAsDouble("preDamageCooldown") <= 10;
            boolean boost = Boolean.parseBoolean(get.apply("boosted"));
            float raw = FlatPhysics.rawDamage(Float.parseFloat(get.apply("charge")), Boolean.parseBoolean(get.apply("critical")));
            float damage = fresh ? raw : raw - lastRaw.get(target);
            float hp = Math.max(0, previousHealth.getOrDefault(target, 20f) - FlatPhysics.armored(damage));
            float actualHP = Float.parseFloat(get.apply("defenderHP"));
            if (hp == actualHP) healthExact++;
            previousHealth.put(target, actualHP);
            lastRaw.put(target, raw);

            double x = number.applyAsDouble("packetVX"), y = number.applyAsDouble("packetVY"), z = number.applyAsDouble("packetVZ");
            boolean emitted = Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z);
            if (emitted == (fresh || boost)) presenceExact++;
            if (!emitted || !(fresh || boost)) continue;
            double dx = number.applyAsDouble("dx"), dz = number.applyAsDouble("dz");
            if (dx*dx + dz*dz < 1e-5f) { unknown++; continue; }
            var impulse = new FlatPhysics.Impulse();
            impulse.hit(number.applyAsDouble("serverVX"), number.applyAsDouble("serverVY"), number.applyAsDouble("serverVZ"),
                        Boolean.parseBoolean(get.apply("defenderGround")), dx, dz,
                        Float.parseFloat(get.apply("attackerYaw")), fresh, boost);
            comparable++;
            // Referee.motionPacket records the constructor's vector, before wire quantization.
            if (impulse.x == x && impulse.y == y && impulse.z == z) impulsesExact++;
            double error = Math.hypot(Math.hypot(impulse.x - x, impulse.y - y), impulse.z - z);
            maxError = Math.max(maxError, error);
        }
        System.out.printf(Locale.ROOT, "%s,%d,%d,%d,%d,%d,%d,%.10g%n",
                          file.getFileName().toString().replace("-contacts.tsv", ""), lines.size()-1, healthExact,
                          presenceExact, comparable, impulsesExact, unknown, maxError);
    }
}
