package practice.bridge;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import lab.SumoFootprint;
import practice.core.SumoStats;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.DirectoryStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/** Post-match details from the lab's existing local server and bot archives. */
final class LabSumoHistory {
    private static final long MAX_ARCHIVE = 64L * 1024 * 1024;
    private static final Gson GSON = new Gson();

    private static final class Launch {
        final long time;
        final double x, z, normalSpeed, floor;
        final boolean sprint;
        Launch(long time, double x, double z, double normalSpeed, double floor, boolean sprint) {
            this.time = time;
            this.x = x;
            this.z = z;
            this.normalSpeed = normalSpeed;
            this.floor = floor;
            this.sprint = sprint;
        }
    }

    private static final class BotSlide {
        final Launch launch;
        final SumoStats.Exchange exchange;
        BotSlide(Launch launch, SumoStats.Exchange exchange) {
            this.launch = launch;
            this.exchange = exchange;
        }
    }

    static List<String> opponentAnalysis(Path replays, String id, String player) {
        if (replays == null || !id.matches("m[0-9]{4,9}")) return Collections.emptyList();
        try {
            Path path = replays.resolve(id + ".jsonl");
            if (!Files.isRegularFile(path)) path = replays.resolve(id + ".jsonl.gz");
            List<JsonObject> server = events(path, "hit", "mmc_knockback", "finish");
            JsonObject result = null;
            for (JsonObject row : server) if ("finish".equals(string(row, "type"))) result = object(row, "result");
            if (result == null || !id.equals(string(result, "id")) || !"sumo".equals(string(result, "mode"))) return Collections.emptyList();
            String a = string(result, "a"), b = string(result, "b");
            if (!player.equals(a) && !player.equals(b)) return Collections.emptyList();
            String side = player.equals(a) ? "b" : "a";
            String bot = string(result, side);
            if (!bot.matches("LabSlot[0-7]") || "human".equals(string(result, "policy_" + side))) return Collections.emptyList();
            long start = (long)number(result, "start");
            long receiptWindow = 150 + Math.round(number(result, "ping_" + side) / 2);
            List<Launch> launches = launches(server, player, bot, result);
            if (launches.isEmpty()) return Collections.emptyList();
            Path folder = replays.resolveSibling("pov").resolve(id);
            List<JsonObject> botEvents = botEvents(folder, side);
            List<BotSlide> slides = botSlides(launches, botEvents, receiptWindow);
            return format(slides, launches, start, (long)number(result, "finish"), player.equals(string(result, "winner")));
        } catch (IOException | RuntimeException ignored) {
            return Collections.emptyList();
        }
    }

    private static List<Launch> launches(List<JsonObject> rows, String player, String bot, JsonObject result) {
        List<Launch> launches = new ArrayList<>();
        boolean rangeProfile = "mmc_recorded_range_v4".equals(string(object(result, "combat_profile"), "knockback_model"));
        boolean recordedFloor = number(object(result, "sumo_arena"), "floor_blocks") == 137;
        JsonObject pending = null;
        for (JsonObject row : rows) {
            if ("hit".equals(string(row, "type"))) {
                pending = player.equals(string(row, "attacker")) && bot.equals(string(row, "target")) &&
                        !flag(row, "rod") && !flag(row, "damage_upgrade") ? row : null;
            } else if (pending != null && bot.equals(string(row, "target"))) {
                long time = (long)number(pending, "time");
                if (Math.abs(number(row, "time") - time) > 15) continue;
                JsonArray velocity = row.getAsJsonArray("velocity");
                boolean sprint = flag(pending, "attacker_sprint");
                double x = velocity.get(0).getAsDouble(), z = velocity.get(2).getAsDouble();
                JsonObject target = object(pending, "target_state");
                double normal = Double.NaN;
                if (rangeProfile && sprint && number(row, "extra_levels") == 1) {
                    JsonObject source = object(pending, "attacker_state");
                    double gap = Math.hypot(number(source, "posX") - number(target, "posX"), number(source, "posZ") - number(target, "posZ"));
                    double attenuation = Math.min(.40, .35 * Math.max(0, gap - 3));
                    normal = Math.hypot(x, z) * .5275 / (.9495 * (1 - attenuation));
                }
                double floor = recordedFloor ? floorSupport(number(target, "posX"), number(target, "posZ"), x, z) : Double.NaN;
                launches.add(new Launch(time, x, z, normal, floor, sprint));
                pending = null;
            }
        }
        return launches;
    }

    private static double floorSupport(double x, double z, double dx, double dz) {
        double speed = Math.hypot(dx, dz);
        if (!Double.isFinite(x) || !Double.isFinite(z) || speed < .1) return Double.NaN;
        x -= 4096 * Math.rint(x / 4096);
        for (int step = 0; step <= 160; step++) {
            double length = step / 20.0;
            if (!supports(x + length * dx / speed, z + length * dz / speed)) return length;
        }
        return Double.NaN;
    }

    private static boolean supports(double x, double z) {
        for (int bx = (int)Math.floor(x-.3); bx < x+.3; bx++) {
            for (int bz = (int)Math.floor(z-.3); bz < z+.3; bz++) {
                if (SumoFootprint.cell(bx, bz, SumoFootprint.DEFAULT)) return true;
            }
        }
        return false;
    }

    private static List<JsonObject> botEvents(Path folder, String side) {
        if (!Files.isDirectory(folder)) return Collections.emptyList();
        for (int attempt = 0; attempt < 5; attempt++) {
            try (DirectoryStream<Path> paths = Files.newDirectoryStream(folder, side + "-client-*-events.jsonl.gz")) {
                for (Path path : paths) {
                    List<JsonObject> rows = events(path, "velocity_packet", "science_move", "science_click", "engine_attack", "reach_probe", "capture_end");
                    if (rows.stream().anyMatch(row -> "capture_end".equals(string(row, "type")))) return rows;
                }
            } catch (IOException ignored) {
                // The bot can still be closing its gzip immediately after the referee announces the result.
            }
            try { Thread.sleep(350); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); break; }
        }
        return Collections.emptyList();
    }

    private static List<BotSlide> botSlides(List<Launch> launches, List<JsonObject> events, long receiptWindow) {
        List<BotSlide> slides = new ArrayList<>();
        Map<Long, JsonObject> contacts = new HashMap<>();
        for (JsonObject row : events) if ("engine_attack".equals(string(row, "type")))
            contacts.put((long)number(row, "click_attempt"), row);
        SumoStats stats = new SumoStats(new SumoStats.Listener() {
            @Override public void cue(SumoStats.Exchange exchange, SumoStats.Cue cue) {}
            @Override public void closed(SumoStats.Exchange exchange) {}
        });
        Launch launch = null;
        int launchIndex = 0;
        JsonObject probe = null;
        boolean clicked = false;
        for (JsonObject row : events) {
            String type = string(row, "type");
            long time = (long)number(row, "time");
            if (type.equals("velocity_packet") && flag(row, "own_entity")) {
                if (launch != null && stats.active != null) {
                    SumoStats.Exchange exchange = stats.active;
                    stats.closeWindow();
                    if (exchange.steps > 0) slides.add(new BotSlide(launch, exchange));
                }
                launch = null;
                probe = null;
                clicked = false;
                while (launchIndex < launches.size() && time - launches.get(launchIndex).time > receiptWindow) launchIndex++;
                if (launchIndex < launches.size()) {
                    Launch next = launches.get(launchIndex);
                    double x = number(row, "vx"), z = number(row, "vz");
                    if (time >= next.time - 2 && Math.hypot(x, z) >= .10 && Math.abs(x - next.x) < .0002 && Math.abs(z - next.z) < .0002) {
                        launchIndex++;
                        launch = next;
                        stats.impact(time, x, z, Double.NaN, Integer.MIN_VALUE, true);
                    }
                }
            } else if (type.equals("science_move")) {
                if (launch != null && stats.active != null) {
                    SumoStats.Exchange exchange = stats.active;
                    if (!clicked && probe != null && number(probe, "time") <= time && time - number(probe, "time") <= 12 &&
                            !flag(probe, "raycast_stale") && flag(probe, "could_attempt")) {
                        JsonObject own = object(probe, "own");
                        stats.inputSlot((long)number(probe, "time"), true, flag(own, "sprint"), 0,
                                exchange.outward(number(own, "motionX"), number(own, "motionZ")));
                    }
                    move(stats, row, time);
                    if (stats.active == null) {
                        slides.add(new BotSlide(launch, exchange));
                        launch = null;
                    }
                } else move(stats, row, time);
                probe = null;
                clicked = false;
            } else if (launch != null && type.equals("reach_probe")) probe = row;
            else if (launch != null && type.equals("science_click")) {
                clicked = true;
                click(stats, row, contacts.get((long)number(row, "sequence")), time);
            }
        }
        if (launch != null && stats.active != null && stats.active.steps > 0) {
            stats.closeWindow();
            slides.add(new BotSlide(launch, stats.last));
        }
        return slides;
    }

    private static void move(SumoStats stats, JsonObject row, long time) {
        JsonObject before = object(row, "before"), after = object(row, "after");
        double dx = number(after, "posX") - number(before, "posX"), dz = number(after, "posZ") - number(before, "posZ");
        if (!Double.isFinite(dx) || !Double.isFinite(dz)) { stats.discontinuity(); return; }
        boolean ground = flag(before, "ground");
        double drag = ground ? .91f * (float)number(row, "floor_slipperiness") : .91f;
        if (flag(after, "isCollidedHorizontally") || Math.abs(dx * drag - number(after, "motionX")) >= 1e-4 ||
                Math.abs(dz * drag - number(after, "motionZ")) >= 1e-4) drag = Double.NaN;
        stats.move(time, dx, dz, ground, number(row, "forward") > 0, drag);
    }

    private static void click(SumoStats stats, JsonObject row, JsonObject contact, long time) {
        JsonObject own = object(row, "own"), target = object(row, "target");
        if (own == null || target == null || stats.active == null) return;
        boolean selected = flag(row, "selected_target"), sprint = flag(own, "sprint");
        int timer = (int)number(row, "miss_cooldown_before");
        double x = number(own, "motionX"), z = number(own, "motionZ");
        boolean cut = selected && sprint && timer == 0 && contact != null &&
                Math.abs(.6 * x - number(contact, "vx_after")) < 1e-7 && Math.abs(.6 * z - number(contact, "vz_after")) < 1e-7;
        double distance = nearestDistance(own, target);
        // The bot's trace lacks a visibility check on a missed click. Report the
        // observed selection and spacing without inferring a clean aim-only fix.
        stats.click(time, selected, false, sprint, timer, stats.active.outward(x, z), distance, cut);
    }

    private static double nearestDistance(JsonObject own, JsonObject target) {
        JsonArray box = target.has("box") ? target.getAsJsonArray("box") : null;
        if (box == null || box.size() != 6) return Double.NaN;
        double x = gap(number(own, "posX"), box, 0), y = gap(number(own, "posY") + 1.62, box, 1), z = gap(number(own, "posZ"), box, 2);
        return Math.sqrt(x * x + y * y + z * z);
    }

    private static double gap(double value, JsonArray box, int axis) {
        return Math.max(0, Math.max(box.get(axis).getAsDouble() - .1 - value, value - box.get(axis + 3).getAsDouble() - .1));
    }

    private static List<String> format(List<BotSlide> slides, List<Launch> launches, long start, long finish, boolean won) {
        boolean hasSlides = !slides.isEmpty();
        BotSlide edge = won ? slides.stream().filter(slide -> finish - slide.launch.time <= 1250 && slide.launch.floor > .10 &&
                slide.launch.floor <= slide.exchange.peakSlide() + .15)
                .max(Comparator.comparingLong(slide -> slide.launch.time)).orElse(null) : null;
        BotSlide defense = slides.stream().filter(slide -> slide.exchange.kbSaved() >= 1.0)
                .max(Comparator.comparingDouble(slide -> slide.exchange.kbSaved())).orElse(null);
        slides.removeIf(slide -> slide.exchange.peakSlide() < .50 || slide.launch.floor == 0);
        slides.sort(Comparator.comparingDouble((BotSlide slide) -> slide.exchange.peakSlide()).reversed());
        List<BotSlide> highlights = new ArrayList<>(slides.subList(0, Math.min(3, slides.size())));
        if (edge != null && !highlights.contains(edge)) highlights.add(edge);
        if (defense != null && !highlights.contains(defense)) highlights.add(defense);
        highlights.sort(Comparator.comparingLong(slide -> slide.launch.time));
        List<String> lines = new ArrayList<>();
        lines.add("");
        lines.add("Practice lab opponent view");
        if (highlights.isEmpty()) {
            Launch strongest = Collections.max(launches, Comparator.comparingDouble(hit -> Math.hypot(hit.x, hit.z)));
            addHit(lines, strongest, start);
            lines.add(hasSlides ? "No matched bot slide reached half a block." : "No matching bot movement was available for a slide comparison.");
        }
        for (BotSlide slide : highlights) {
            addHit(lines, slide.launch, start);
            SumoStats.Exchange exchange = slide.exchange;
            lines.add("Bot slid " + SumoStats.blocks(exchange.peakSlide()) + " and braked " + exchange.cuts + (exchange.cuts == 1 ? " time." : " times."));
            if (slide.launch.floor > 0 && slide.launch.floor <= 2.5) {
                lines.add("Bot had ~" + SumoStats.blocks(slide.launch.floor) + " of floor support in the push direction.");
                if (slide.launch.floor <= exchange.peakSlide() + .15) lines.add("The bot's slide reached that estimated edge.");
            }
            if (exchange.kbSaved() >= .25) lines.add("The bot's brakes kept ~" + SumoStats.blocks(exchange.kbSaved()) + ".");
            List<String> observations = exchange.brakeTimeline();
            if (observations.isEmpty()) lines.add("No clear bot return click or on-target opening was recorded.");
            else {
                for (String observation : observations) lines.add("Bot " + observation);
            }
            lines.add("");
        }
        return lines;
    }

    private static void addHit(List<String> lines, Launch hit, long start) {
        lines.add(SumoStats.timestamp(hit.time - start) + String.format(Locale.ROOT,
                "   Your hit launched the bot at %.2f blocks/tick.", Math.hypot(hit.x, hit.z)));
        if (Double.isFinite(hit.normalSpeed)) lines.add(String.format(Locale.ROOT,
                "Server sprint added ~%.2f blocks/tick. The same hit without sprint would launch at ~%.2f.",
                Math.hypot(hit.x, hit.z) - hit.normalSpeed, hit.normalSpeed));
        else if (hit.sprint) lines.add("The server registered this as a sprint hit.");
    }

    private static List<JsonObject> events(Path path, String... types) throws IOException {
        if (!Files.isRegularFile(path) || Files.size(path) > MAX_ARCHIVE) return Collections.emptyList();
        List<JsonObject> rows = new ArrayList<>();
        try (BufferedReader reader = path.toString().endsWith(".gz")
                ? new BufferedReader(new InputStreamReader(new GZIPInputStream(Files.newInputStream(path)), StandardCharsets.UTF_8))
                : Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            long total = 0;
            for (String line; (line = reader.readLine()) != null;) {
                if ((total += line.length() + 1L) > 8 * MAX_ARCHIVE) return Collections.emptyList();
                for (String type : types) if (line.contains("\"type\":\"" + type + "\"")) {
                    JsonObject row = GSON.fromJson(line, JsonObject.class);
                    if (type.equals(string(row, "type"))) { rows.add(row); break; }
                }
            }
        }
        return rows;
    }

    private static JsonObject object(JsonObject row, String key) { return row != null && row.has(key) ? row.getAsJsonObject(key) : null; }
    private static String string(JsonObject row, String key) { JsonElement v = row == null ? null : row.get(key); return v == null || v.isJsonNull() ? "" : v.getAsString(); }
    private static double number(JsonObject row, String key) { JsonElement v = row == null ? null : row.get(key); return v == null || v.isJsonNull() ? Double.NaN : v.getAsDouble(); }
    private static boolean flag(JsonObject row, String key) { JsonElement v = row == null ? null : row.get(key); return v != null && !v.isJsonNull() && v.getAsBoolean(); }

    static int[] sprintHits(Path replays, String id, String player) {
        if (replays == null || !id.matches("m[0-9]{4,9}")) return null;
        Path path = replays.resolve(id + ".jsonl");
        try {
            if (!Files.isRegularFile(path) || Files.size(path) > 64L * 1024 * 1024) return null;
            Gson gson = new Gson();
            int sprint = 0, fresh = 0;
            try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                for (String line; (line = reader.readLine()) != null;) {
                    if (line.contains("\"type\":\"hit\"")) {
                        JsonObject hit = gson.fromJson(line, JsonObject.class);
                        if (!"hit".equals(hit.get("type").getAsString()) ||
                                !player.equals(hit.get("attacker").getAsString())) continue;
                        if (hit.get("rod").getAsBoolean() || hit.get("damage_upgrade").getAsBoolean()) continue;
                        fresh++;
                        if (hit.get("attacker_sprint").getAsBoolean()) sprint++;
                    } else if (line.contains("\"type\":\"finish\"")) {
                        JsonObject finish = gson.fromJson(line, JsonObject.class);
                        if (!"finish".equals(finish.get("type").getAsString())) continue;
                        JsonObject result = finish.getAsJsonObject("result");
                        if (id.equals(result.get("id").getAsString()) && "sumo".equals(result.get("mode").getAsString()) &&
                                (player.equals(result.get("a").getAsString()) || player.equals(result.get("b").getAsString())))
                            return new int[]{sprint, fresh};
                        return null;
                    }
                }
            }
        } catch (IOException | RuntimeException ignored) {}
        return null;
    }

    private LabSumoHistory() {}
}
