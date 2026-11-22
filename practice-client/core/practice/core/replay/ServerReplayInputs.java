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
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.TreeMap;
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
    private final TreeMap<Double,Boolean> localSprint,clickGate,sentSprint;
    private final ReplayCoachFeedback.Reader coach;

    private ServerReplayInputs(Reader reader) {
        reader.finishDecisions();
        coach=reader.coach;
        localSprint=reader.localSprint;
        clickGate=reader.clickGate;
        sentSprint=reader.sentSprint;
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
    public Map<String,Object> coachAt(double ms){return coach.at(ms);}

    /** Bot-only evidence up to the playhead. A sprint message is an outbound client packet. */
    public Map<String,Object> botDetails(double ms){
        Map<String,Object> details=new LinkedHashMap<>();
        if(ms<first||ms>last)return details;
        putSample(details,"local_sprint",localSprint.floorEntry(ms));
        putSample(details,"click_gate",clickGate.floorEntry(ms));
        Map.Entry<Double,Boolean> sent=sentSprint.floorEntry(ms);
        putSample(details,"sent_sprint",sent);
        if(sent!=null&&sent.getValue()!=null)details.put("sent_ago_ms",(int)(ms-sent.getKey()));
        return details;
    }

    private static void putSample(Map<String,Object> out,String key,Map.Entry<Double,Boolean> sample){
        if(sample!=null&&sample.getValue()!=null)out.put(key,sample.getValue());
    }

    public static ServerReplayInputs read(Path folder, String side, boolean bot, long start,
                                          Function<String, Map<String, Object>> parse) throws IOException {
        Reader reader = new Reader(bot, start,opponent(folder,side,parse));
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
                        if ((line.startsWith("{\"type\":\"packet_wire\"")||line.startsWith("{\"type\":\"packet_handler_enter\""))
                                &&!line.contains("\"C0BPacketEntityAction\"")&&!line.contains("\"S12PacketEntityVelocity\"")
                                &&!line.contains("\"S19PacketEntityStatus\"")&&!line.contains("\"S08PacketPlayerPosLook\""))continue;
                    }
                    reader.add(parse.apply(line));
                }
            }
        }
        return new ServerReplayInputs(reader);
    }

    static String opponent(Path folder,String side,Function<String,Map<String,Object>> parse){
        if(folder==null)return "";
        Path manifest=folder.resolve("manifest.json");
        try{
            if(!Files.isRegularFile(manifest)||Files.size(manifest)>1024*1024)return "";
            Map<String,Object> meta=parse.apply(new String(Files.readAllBytes(manifest),StandardCharsets.UTF_8));
            if(meta!=null){
                Map<String,Object> players=object(meta.get("players"));
                return String.valueOf(object(players.get(side.equals("a")?"b":"a")).getOrDefault("name",""));
            }
        }catch(IOException|RuntimeException ignored){ /* movement and click records are independently usable */ }
        return "";
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
        final TreeMap<Double,Boolean> localSprint=new TreeMap<>(),clickGate=new TreeMap<>(),sentSprint=new TreeMap<>();
        final Map<Integer,Double> tickStarts=new HashMap<>();
        final Map<Integer,Boolean> sprintDecisions=new HashMap<>(),gateDecisions=new HashMap<>();
        final Set<Integer> capturedSprint=new HashSet<>();
        int headTick=-1;
        double headTime=Double.NaN,anchorNs=Double.NaN,anchorMs;

        final ReplayCoachFeedback.Reader coach;
        Reader(boolean bot, long start,String opponent) {
            this.bot = bot;
            this.start = start;
            coach=new ReplayCoachFeedback.Reader(opponent);
        }

        boolean relevant(String type) {
            switch (type) {
                case "pov_archive_start": case "tick_end": case "tick_start": return true;
                case "key": case "mouse": case "keystrokes_reset": return !bot;
                case "click": case "use_click_before": case "blockhit_plan": return bot;
                case "native_input_head": case "observation": case "duel_network": return bot;
                case "attack_click_before": case "attack_click_after": case "packet_wire":
                case "packet_handler_enter": case "motion_travel_after": case "sumo_coach_cue": return true;
                default: return false;
            }
        }

        void add(Map<String, Object> row) {
            if (row == null) return;
            String type = String.valueOf(row.get("type"));
            if (!relevant(type)) return;
            double ms = number(row, "time_ms", number(row, "time", Double.NaN)) - start;
            if (!Double.isFinite(ms)) return;
            // Bot policy rows can use scaled game time, even after returning to 1x.
            // Attach applied controls to the native tick in this participant's capture.
            if(type.equals("native_input_head")){
                headTick=(int)number(row,"native_tick",-1);headTime=ms;
                return;
            }
            if(type.equals("duel_network")){
                int tick=(int)number(row,"tick",-1),action=(int)number(row,"action",-1);
                if(tick>=0&&action>=0&&action<48){
                    sprintDecisions.put(tick,action%4==2);
                    gateDecisions.put(tick,(action/12&1)!=0);
                }
                return;
            }
            if(type.equals("observation")){
                if(headTick>=0&&ms==headTime){
                    if(row.get("sprint_key") instanceof Boolean)sprintDecisions.putIfAbsent(headTick,(Boolean)row.get("sprint_key"));
                    if(row.get("click_gate") instanceof Boolean)gateDecisions.putIfAbsent(headTick,(Boolean)row.get("click_gate"));
                }
                return;
            }
            if(type.equals("click")&&row.get("wall_monotonic_ns") instanceof Number&&Double.isFinite(anchorNs))
                ms=anchorMs+(number(row,"wall_monotonic_ns",anchorNs)-anchorNs)/1e6;
            first = Math.min(first, ms);
            last = Math.max(last, ms);
            Map<String, Object> data = object(row.get("data"));
            coach.accept(type,ms,row,data);
            switch (type) {
                case "pov_archive_start":
                    finishDecisions();
                    headTick=-1;headTime=anchorNs=Double.NaN;
                    localSprint.put(ms,null);sentSprint.put(ms,null);clickGate.put(ms,null);
                    events.add(new Event(ms, null, false));
                    movement = 0;
                    sampledMovement = screenOpen = block = false;
                    if (!bot) movement(ms, object(object(row.get("metadata")).get("initial")));
                    break;
                case "tick_start": case "tick_end":
                    int tick=(int)number(row,"client_tick",-1);
                    if(tick>=0&&bot){
                        // Prefer start, with end as a fallback for older partial captures.
                        if(type.equals("tick_start"))tickStarts.put(tick,ms);
                        else tickStarts.putIfAbsent(tick,ms);
                    }
                    anchorNs=number(row,"monotonic_ns",Double.NaN);anchorMs=ms;
                    Event sprint=Event.recordedSprint(ms,data);
                    if(sprint!=null){events.add(sprint);if(bot)capturedSprint.add(tick);}
                    localSample(ms,data);
                    if(type.equals("tick_start"))break;
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
                case "attack_click_before": attacks.add(ms); localSample(ms,data); break;
                case "attack_click_after": localSample(ms,data); break;
                case "packet_wire": sprintPacket(ms,data); break;
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

        void finishDecisions(){
            for(Map.Entry<Integer,Double> tick:tickStarts.entrySet()){
                int id=tick.getKey();double ms=tick.getValue();
                Boolean sprint=sprintDecisions.get(id),gate=gateDecisions.get(id);
                if(sprint!=null&&!capturedSprint.contains(id))events.add(new Event(ms,Key.SPRINT,sprint));
                if(gate!=null)clickGate.put(ms,gate);
            }
            tickStarts.clear();sprintDecisions.clear();gateDecisions.clear();capturedSprint.clear();
        }

        void localSample(double ms,Map<String,Object> data){
            Object sprint=object(data.get("self")).get("sprinting");
            if(bot&&sprint instanceof Boolean)localSprint.put(ms,(Boolean)sprint);
        }

        void sprintPacket(double ms,Map<String,Object> data){
            Map<String,Object> packet=object(data.get("packet"));
            if(!"out".equals(packet.get("direction"))||!"C0BPacketEntityAction".equals(packet.get("kind")))return;
            try{
                ReplayRecording.Cursor wire=new ReplayRecording.Cursor(Base64.getDecoder().decode(String.valueOf(data.get("base64"))));
                if(wire.varint()!=0x0b)return;
                wire.varint(); // entity id
                int action=wire.varint();
                if(action==3||action==4)sentSprint.put(ms,action==3);
            }catch(IllegalArgumentException|IOException ignored){ /* no evidence for this packet */ }
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
