import lab.ComboModel;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.util.StringJoiner;

/**
 * Offline diagnostic; no native state corrections or hit confirmations enter.
 *
 * Input TSV (times are integral milliseconds on one common relative clock):
 * INIT startMs ownRttMs opponentRttMs trackerPhase ax ay az bx by bz
 * optionally followed by aSeesB_x/y/z and bSeesA_x/y/z (six fields, initial only).
 * Both bodies initially stand still without sprint, at feet y relative to floor.
 * Server position equals initial body position; queues, immunity and damage are
 * initially empty. Initial settled vertical motion is -.0784000015258789.
 *
 * Remaining rows: timeMs A|B|SERVER RELEASE|WALK|SPRINT|- 0|1
 * Optional isolated model factor: JVM property -Dlab.model_head_yaw=true.
 * Optional ordinary-floor landing factor: -Dlab.model_fall_damage=true.
 * Default false preserves the ideal instantaneous ray; TSV stays unchanged.
 * A allows all three actions; B allows RELEASE/SPRINT; SERVER requires - and 0.
 * The last field is a physical press CONSUMED on that tick, including misses.
 * Actual physical injection cadence must be checked separately; tick consumption
 * may bunch after a stall. A boolean cannot represent multiple presses in a tick.
 * Empty lines and # comments are ignored. Equal-time rows keep their input order.
 *
 * Output is named TSV, one predicted post-event row per supplied event. Later
 * measured positions/velocities/hits belong only in the external comparison.
 * Actual client travel hooks occur after local click processing; a server-end
 * hook occurs after multiple earlier event phases. Their timestamps therefore
 * cannot resolve exact packet dispatch order. Report clock shifts/tie-order
 * sensitivity rather than fitting these proxies to the held-out trajectories.
 */
public final class NativeClockReplay {
    private NativeClockReplay() {}

    private static final String HEADER = String.join("\t",
            "time_ms", "event", "a_x", "a_y", "a_z", "a_vx", "a_vy", "a_vz", "a_ground", "a_sprint",
            "b_x", "b_y", "b_z", "b_vx", "b_vy", "b_vz", "b_ground", "b_sprint",
            "a_sees_b_x", "a_sees_b_y", "a_sees_b_z", "b_sees_a_x", "b_sees_a_y", "b_sees_a_z",
            "server_ax", "server_ay", "server_az", "server_bx", "server_by", "server_bz",
            "a_server_sprint", "b_server_sprint", "accepted_hits", "received_hits",
            "accepted_fresh_hits", "received_fresh_hits", "accepted_upgrades", "received_upgrades",
            "damage_dealt", "damage_received", "a_clicks", "b_clicks", "server_ticks", "pending_packets",
            "target_resistant_ticks", "own_resistant_ticks", "last_our_ray", "last_defender_ray",
            "own_fall_events", "target_fall_events", "own_fall_damage", "target_fall_damage",
            "server_fall_distance_a", "server_fall_distance_b");

    private static double number(String[] fields, int index) {
        double result = Double.parseDouble(fields[index]);
        if (!Double.isFinite(result)) throw new IllegalArgumentException("Nonfinite initial value");
        return result;
    }

    private static String nextDataLine(BufferedReader input) throws Exception {
        String line;
        while ((line = input.readLine()) != null)
            if (!line.isBlank() && !line.stripLeading().startsWith("#")) return line;
        return null;
    }

    static void run(BufferedReader input, PrintStream output) throws Exception {
        String first = nextDataLine(input);
        if (first == null) throw new IllegalArgumentException("Missing INIT row");
        String[] init = first.split("\t", -1);
        if ((init.length != 11 && init.length != 17) || !init[0].equals("INIT"))
            throw new IllegalArgumentException("INIT requires 11 fields, or 17 with both initial views");
        long start = Long.parseLong(init[1]);
        ComboModel.Config config = new ComboModel.Config();
        config.modelHeadYaw = Boolean.getBoolean("lab.model_head_yaw");
        config.modelFallDamage = Boolean.getBoolean("lab.model_fall_damage");
        config.ownRttMs = number(init, 2);
        config.opponentRttMs = number(init, 3);
        config.trackerPhase = Integer.parseInt(init[4]);
        if (config.trackerPhase < 0 || config.trackerPhase > 1)
            throw new IllegalArgumentException("trackerPhase must be 0 or 1");
        config.validate();
        ComboModel.Body a = body(init, 5), b = body(init, 8);
        ComboModel.View serverA = new ComboModel.View(a.x, a.y, a.z);
        ComboModel.View serverB = new ComboModel.View(b.x, b.y, b.z);
        ComboModel.View aViewB = init.length == 17
                ? new ComboModel.View(number(init, 11), number(init, 12), number(init, 13)) : serverB;
        ComboModel.View bViewA = init.length == 17
                ? new ComboModel.View(number(init, 14), number(init, 15), number(init, 16)) : serverA;
        ComboModel.State state = new ComboModel.State(start, a, b, aViewB, bViewA, serverA, serverB);
        output.println(HEADER);
        String line;
        while ((line = nextDataLine(input)) != null) {
            String[] event = line.split("\t", -1);
            if (event.length != 4) throw new IllegalArgumentException("Event requires four fields");
            long time = Long.parseLong(event[0]);
            ComboModel.NativeEventKind kind = ComboModel.NativeEventKind.valueOf(event[1]);
            ComboModel.Action action = event[2].equals("-") ? null : ComboModel.Action.valueOf(event[2]);
            if (!event[3].equals("0") && !event[3].equals("1"))
                throw new IllegalArgumentException("Click must be 0 or 1");
            state = ComboModel.replayNativeEvent(state, time, kind, action, event[3].equals("1"), config);
            printState(output, state, kind);
        }
    }

    private static ComboModel.Body body(String[] init, int first) {
        double y = number(init, first + 1);
        if (y < 0) throw new IllegalArgumentException("Initial feet must be above the flat floor");
        return new ComboModel.Body(number(init, first), y, number(init, first + 2),
                0, -.0784000015258789, 0, y == 0, false);
    }

    private static void printState(PrintStream output, ComboModel.State s, ComboModel.NativeEventKind kind) {
        StringJoiner row = new StringJoiner("\t");
        row.add(Long.toString(s.nowMs)).add(kind.name());
        bodyValues(row, s.a); bodyValues(row, s.b);
        for (ComboModel.View view : new ComboModel.View[]{s.aSeesB, s.bSeesA, s.serverA, s.serverB})
            values(row, view.x, view.y, view.z);
        row.add(s.aServerSprint ? "1" : "0").add(s.bServerSprint ? "1" : "0");
        values(row, s.acceptedHits, s.receivedHits, s.acceptedFreshHits, s.receivedFreshHits,
                s.acceptedDamageUpgrades, s.receivedDamageUpgrades, s.damageDealt, s.damageReceived,
                s.physicalClicks, s.defenderPhysicalClicks, s.serverTicks, s.pendingPacketCount(),
                s.targetHurtResistantTicks, s.ownHurtResistantTicks, s.lastOurRay, s.lastDefenderRay,
                s.ownFallEvents, s.targetFallEvents, s.ownFallDamage, s.targetFallDamage,
                s.serverFallDistanceA, s.serverFallDistanceB);
        output.println(row);
    }

    private static void bodyValues(StringJoiner row, ComboModel.Body body) {
        values(row, body.x, body.y, body.z, body.vx, body.vy, body.vz);
        row.add(body.ground ? "1" : "0").add(body.sprint ? "1" : "0");
    }

    private static void values(StringJoiner row, double... values) {
        for (double value : values) row.add(Double.toString(value));
    }

    public static void main(String[] args) throws Exception {
        run(new BufferedReader(new InputStreamReader(System.in)), System.out);
    }
}
