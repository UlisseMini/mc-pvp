package practice.core.replay;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import practice.core.replay.ReplayInputs.Event;
import practice.core.replay.ReplayInputs.Key;
import practice.core.replay.ReplayInputs.State;
import static practice.replay.PoseTrack.object;
import static practice.replay.PoseTrack.number;

/** Inputs from one participant's match archive, on the server replay clock. */
public final class ServerReplayInputs {
    private static final Key[] MOVEMENT = {Key.W, Key.A, Key.S, Key.D, Key.SPACE};
    private static final int CLICK_FLASH_MS = 32;
    private final ReplayInputs inputs;
    private final double first, last;

    private ServerReplayInputs(Reader reader) {
        if (reader.bot) {
            for (double ms : reader.injections.isEmpty() ? reader.attacks : reader.injections)
                reader.flash(ms, Key.LMB);
            if (!reader.hasBlockInputs) for (double ms : reader.uses) reader.flash(ms, Key.RMB);
        }
        inputs = new ReplayInputs(reader.events);
        first = reader.first;
        last = reader.last;
    }

    public State at(double ms) {
        return first <= ms && ms <= last && inputs.available ? inputs.at(ms) : null;
    }

    public static ServerReplayInputs read(Path folder, String side, boolean bot, long start,
                                          Function<String, Map<String, Object>> parse) throws IOException {
        Reader reader = new Reader(bot, start);
        if (!Files.isDirectory(folder)) return new ServerReplayInputs(reader);
        List<Path> files;
        try (Stream<Path> listing = Files.list(folder)) {
            files = listing.filter(p -> p.getFileName().toString().matches(side + "-client-.*-events(?:-part[0-9]+)?\\.jsonl\\.gz"))
                .sorted(Comparator.comparing((Path p) -> p.getFileName().toString().split("-events")[0])
                    .thenComparingInt(ServerReplayInputs::part)).collect(Collectors.toList());
        }
        long chars = 0;
        for (Path file : files) {
            try (BufferedReader in = new BufferedReader(new InputStreamReader(new GZIPInputStream(Files.newInputStream(file)), StandardCharsets.UTF_8))) {
                for (String line; (line = in.readLine()) != null;) {
                    if (Thread.currentThread().isInterrupted()) throw new IOException("Replay input loading canceled");
                    chars += line.length();
                    if (chars > 512L * 1024 * 1024) throw new IOException("Replay input archive exceeds 512 MiB");
                    // MatchArchive writes type first. Skip large packet and pose rows before decoding JSON.
                    if (line.startsWith("{\"type\":\"")) {
                        int end = line.indexOf('"', 9);
                        if (end >= 9 && !reader.relevant(line.substring(9, end))) continue;
                    }
                    reader.add(parse.apply(line));
                }
            }
        }
        return new ServerReplayInputs(reader);
    }

    private static int part(Path path) {
        String name = path.getFileName().toString();
        int start = name.lastIndexOf("-part");
        return start < 0 ? 1 : Integer.parseInt(name.substring(start + 5, name.indexOf('.', start)));
    }

    private static final class Reader {
        final boolean bot;
        final long start;
        final List<Event> events = new ArrayList<>();
        final List<Double> injections = new ArrayList<>(), attacks = new ArrayList<>(), uses = new ArrayList<>();
        double first = Double.POSITIVE_INFINITY, last = Double.NEGATIVE_INFINITY;
        int movement;
        boolean sampledMovement, screenOpen, block, hasBlockInputs;

        Reader(boolean bot, long start) {
            this.bot = bot;
            this.start = start;
        }

        boolean relevant(String type) {
            switch (type) {
                case "pov_archive_start": case "tick_end": return true;
                case "key": case "mouse": case "keystrokes_reset": return !bot;
                case "click": case "attack_click_before": case "use_click_before": case "blockhit_plan": return bot;
                default: return false;
            }
        }

        void add(Map<String, Object> row) {
            if (row == null) return;
            String type = String.valueOf(row.get("type"));
            if (!relevant(type)) return;
            double ms = number(row, "time_ms", number(row, "time", Double.NaN)) - start;
            if (!Double.isFinite(ms)) return;
            first = Math.min(first, ms);
            last = Math.max(last, ms);
            Map<String, Object> data = object(row.get("data"));
            switch (type) {
                case "pov_archive_start":
                    events.add(new Event(ms, null, false));
                    movement = 0;
                    sampledMovement = screenOpen = block = false;
                    if (!bot) movement(ms, object(object(row.get("metadata")).get("initial")));
                    break;
                case "tick_end":
                    if (bot) movement(ms, data);
                    else if (data.containsKey("screen")) {
                        boolean open = data.get("screen") != null;
                        if (open && !screenOpen) events.add(new Event(ms, null, false));
                        screenOpen = open;
                    }
                    break;
                case "keystrokes_reset": events.add(new Event(ms, null, false)); break;
                case "key":
                    if (!Boolean.TRUE.equals(data.get("repeat"))) edge(ms, Key.keyboard((int) number(data, "key", -1)), data.get("down"));
                    break;
                case "mouse": edge(ms, Key.mouse((int) number(data, "button", -1)), data.get("down")); break;
                case "click": injections.add(ms); break;
                case "attack_click_before": attacks.add(ms); break;
                case "use_click_before": uses.add(ms); break;
                case "blockhit_plan":
                    Object down = row.get("block_key");
                    if (down instanceof Boolean) {
                        hasBlockInputs = true;
                        if (block != (Boolean) down) edge(ms, Key.RMB, down);
                        block = (Boolean) down;
                    }
                    break;
            }
        }

        void movement(double ms, Map<String, Object> sample) {
            Map<String, Object> input = object(object(sample.get("self")).get("movement_input"));
            if (input.isEmpty()) return;
            double forward = number(input, "moveForward", 0), strafe = number(input, "moveStrafe", 0);
            int mask = (forward > 0 ? Key.W.mask() : forward < 0 ? Key.S.mask() : 0)
                | (strafe > 0 ? Key.A.mask() : strafe < 0 ? Key.D.mask() : 0)
                | (Boolean.TRUE.equals(input.get("jump")) ? Key.SPACE.mask() : 0);
            for (Key key : MOVEMENT) {
                int bit = key.mask();
                if ((bit & (movement ^ mask)) != 0 || bot && !sampledMovement)
                    events.add(new Event(ms, key, (mask & bit) != 0));
            }
            movement = mask;
            sampledMovement = true;
        }

        void edge(double ms, Key key, Object down) {
            if (key != null && down instanceof Boolean) events.add(new Event(ms, key, (Boolean) down));
        }

        void flash(double ms, Key key) {
            events.add(new Event(ms, key, true));
            events.add(new Event(ms + CLICK_FLASH_MS, key, false));
        }
    }
}
