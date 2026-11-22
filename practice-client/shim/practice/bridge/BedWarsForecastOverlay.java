package practice.bridge;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.scoreboard.Score;
import net.minecraft.scoreboard.ScoreObjective;
import net.minecraft.scoreboard.ScorePlayerTeam;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.Team;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumChatFormatting;
import org.lwjgl.input.Keyboard;
import org.lwjgl.opengl.GL11;
import practice.bridge.BedWarsForecastDisplay.Mode;
import practice.bridge.BedWarsForecastDisplay.Projection;
import practice.core.forecast.BedWarsForecastGhosts;
import practice.core.forecast.BedWarsForecastGhosts.Marker;
import practice.core.forecast.BedWarsForecastHistory;
import practice.core.forecast.BedWarsForecastHistory.Input;
import practice.core.forecast.BedWarsForecastHistory.Observation;
import practice.core.forecast.BedWarsPredictor;
import practice.core.forecast.BedWarsPredictor.Volume;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Read-only predictions from the running client's own entity and scoreboard snapshots. */
final class BedWarsForecastOverlay {
    private static final int[] HORIZONS = BedWarsForecastHistory.horizons();
    private static final int[] COLORS = {0x74D6FF, 0x52E8CA, 0xFED27D, 0xFFA380, 0xD8A7FF};
    private static final int GRID = BedWarsPredictor.SIDE, TILE = BedWarsPredictor.TILE_WIDTH,
            MAP_MIN = BedWarsPredictor.MIN_BLOCK;
    private static final int PATCH = 4, PATCHES = GRID * TILE / PATCH;
    private static final long SECOND = 1_000_000_000L;
    static final String START_MARKER = "Protect your bed and destroy the enemy beds";
    private static final String DEFAULT_MODEL = "personal-gate";
    private static final float TERRAIN_AIR_WEIGHT = 0.15f;
    private static final List<String> STANDARD_MODELS = Collections.unmodifiableList(Arrays.asList(
            "stay", "velocity", "drift", "motion", "motion-no-motion", "motion-no-context",
            "heatmap-ce", "heatmap", "head-tune", "anil-prior", "anil", "history", "meta-history",
            "personal-hotspots", "personal-routes", "personal-gate"));
    private static final Pattern PLAYER_NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");
    private static final Pattern TEAM_NAME = Pattern.compile("[A-Za-z0-9_-]{1,32}");
    private static final Pattern DIAMOND_TIMER = Pattern.compile("^Diamond II in:? (\\d{1,2}):(\\d{2})$");
    private static final Pattern COLOR_CODE = Pattern.compile("§([0-9a-f])", Pattern.CASE_INSENSITIVE);

    private final Path bundle, displayFile;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "practice-bedwars-forecast");
        thread.setDaemon(true);
        return thread;
    });
    private final BedWarsForecastHistory history = new BedWarsForecastHistory();
    private final Set<String> eliminated = new HashSet<>();
    private final TreeSet<Integer> selectedHorizons = new TreeSet<>(Collections.singleton(10));
    private Set<String> selectedPlayers = Collections.emptySet(), selectedTeams = Collections.emptySet();
    private NetHandlerPlayClient connection;
    private WorldClient world;
    private long start, revision, lastWorldChange;
    private int lastSecond = -1;
    private boolean enabled, estimated, attemptedLoad, modelRequested;
    private String model = DEFAULT_MODEL, issue = "";
    private Future<BedWarsPredictor> loading;
    private Future<Result> pending;
    private BedWarsPredictor predictor;
    private Result result;
    private float[] terrain;
    private BedWarsForecastDisplay display;
    private List<Marker> ghosts = Collections.emptyList();
    private int[][] volumeBins;

    BedWarsForecastOverlay(Path config) {
        bundle = config.resolve("bedwars-forecast.bin.gz");
        displayFile = config.resolve("bedwars-display.properties");
        display = BedWarsForecastDisplay.load(displayFile);
    }

    private static final class Pose {
        final UUID id;
        final String name;
        final Set<String> team;
        final Observation observation;
        Pose(EntityPlayer player, Set<String> team) {
            id = player.func_110124_au();
            name = player.func_70005_c_();
            this.team = team;
            ItemStack held = player.func_70694_bm();
            int item = held == null ? 0 : Item.func_150891_b(held.func_77973_b());
            boolean sword = item == 267 || item == 268 || item == 272 || item == 276 || item == 283;
            observation = new Observation((float)player.field_70165_t, (float)player.field_70163_u,
                    (float)player.field_70161_v, (float)Math.toRadians(player.field_70177_z % 360),
                    player.func_70093_af(), sword);
        }
    }

    private static final class Query {
        final Input input;
        final float[][][] supports;
        final BedWarsForecastHistory.PersonalContext[] personal;
        Query(Input input, float[][][] supports, BedWarsForecastHistory.PersonalContext[] personal) {
            this.input = input;
            this.supports = supports;
            this.personal = personal;
        }
    }

    private static final class TerrainMask {
        final float[] voxelWeight;
        final int[] walkableFeet;
        TerrainMask(float[] voxelWeight, int[] walkableFeet) {
            this.voxelWeight = voxelWeight;
            this.walkableFeet = walkableFeet;
        }
    }

    private static final class Result {
        final long revision;
        final int second, players;
        final int[] horizons, labels;
        final float[][] grids;
        final Volume[] volumes;
        Result(long revision, int second, int players, int[] horizons, int[] labels, float[][] grids, Volume[] volumes) {
            this.revision = revision;
            this.second = second;
            this.players = players;
            this.horizons = horizons;
            this.labels = labels;
            this.grids = grids;
            this.volumes = volumes;
        }
    }

    private static String lower(String value) { return value.toLowerCase(Locale.ROOT); }

    static ScorePlayerTeam tabTeam(Minecraft mc, UUID id) {
        NetHandlerPlayClient handler = mc.func_147114_u();
        NetworkPlayerInfo info = handler == null ? null : handler.func_175102_a(id);
        return info == null ? null : info.func_178850_i();
    }

    private static Set<String> teamKeys(ScorePlayerTeam team) {
        if (team == null) return Collections.emptySet();
        Set<String> keys = new HashSet<>();
        keys.add(lower(team.func_96661_b()));
        EnumChatFormatting color = team.func_178775_l();
        Matcher prefix = COLOR_CODE.matcher(team.func_96668_e());
        while (prefix.find()) color = EnumChatFormatting.func_175744_a(Integer.parseInt(prefix.group(1), 16));
        if (color == null) return keys;
        switch (color) {
            case RED: case DARK_RED: keys.add("red"); break;
            case BLUE: case DARK_BLUE: keys.add("blue"); break;
            case GREEN: case DARK_GREEN: keys.add("green"); break;
            case YELLOW: case GOLD: keys.add("yellow"); break;
            case AQUA: case DARK_AQUA: keys.add("aqua"); break;
            case LIGHT_PURPLE: case DARK_PURPLE: keys.add("pink"); break;
            case GRAY: case DARK_GRAY: keys.add("gray"); break;
            case WHITE: keys.add("white"); break;
            default: break;
        }
        return keys;
    }

    private static ScorePlayerTeam team(Minecraft mc, EntityPlayer player) {
        Team team = player.func_96124_cp();
        return team instanceof ScorePlayerTeam ? (ScorePlayerTeam)team : tabTeam(mc, player.func_110124_au());
    }

    private boolean selected(Pose pose, UUID local) {
        if (selectedPlayers.isEmpty() && selectedTeams.isEmpty()) return !pose.id.equals(local);
        if (selectedPlayers.contains(lower(pose.name)) || pose.id.equals(local) && selectedPlayers.contains("me")) return true;
        return !Collections.disjoint(pose.team, selectedTeams);
    }

    private boolean valid(EntityPlayer player) {
        UUID id = player.func_110124_au();
        String name = player.func_70005_c_();
        return id != null && id.version() == 4 && PLAYER_NAME.matcher(name).matches()
                && !eliminated.contains(lower(name)) && !player.field_70145_X && !player.func_175149_v()
                && player.func_110143_aJ() > 0;
    }

    void chat(String message) {
        Minecraft mc = Minecraft.func_71410_x();
        sync(mc, System.nanoTime());
        String line = EnumChatFormatting.func_110646_a(message).trim();
        if (line.startsWith(START_MARKER)) begin(System.nanoTime(), 0, false);
        else if (line.startsWith("Sending you to ") || line.equals("Bed Wars")) {
            if (start != 0) end();
        } else if (start != 0 && line.endsWith("FINAL KILL!")) {
            int space = line.indexOf(' ');
            if (space > 0 && PLAYER_NAME.matcher(line.substring(0, space)).matches())
                eliminated.add(lower(line.substring(0, space)));
        }
    }

    void suspend() { end(); }
    void close() { worker.shutdownNow(); }

    private void clearResults() {
        revision++;
        result = null;
        terrain = null;
        ghosts = Collections.emptyList();
        volumeBins = null;
    }

    private void end() {
        start = 0;
        estimated = false;
        lastSecond = -1;
        history.clear();
        eliminated.clear();
        clearResults();
    }

    private void begin(long now, int alreadyElapsed, boolean estimated) {
        end();
        start = now - alreadyElapsed * SECOND;
        this.estimated = estimated;
    }

    private void sync(Minecraft mc, long now) {
        NetHandlerPlayClient next = mc.func_147114_u();
        if (connection != next) {
            connection = next;
            end();
        }
        if (world != mc.field_71441_e) {
            world = mc.field_71441_e;
            lastWorldChange = now;
            clearResults();
        }
    }

    /** Recover an early-match clock from the first visible upgrade countdown after reconnecting. */
    private void recoverClock(Minecraft mc, long now) {
        if (start != 0 || world == null || now - lastWorldChange < 100_000_000L) return;
        Scoreboard board = world.func_96441_U();
        ScoreObjective side = board.func_96539_a(1);
        ScorePlayerTeam ownTeam = team(mc, mc.field_71439_g);
        EnumChatFormatting color = ownTeam == null ? null : ownTeam.func_178775_l();
        if (color != null && color.func_175746_b() >= 0) {
            ScoreObjective teamSide = board.func_96539_a(3 + color.func_175746_b());
            if (teamSide != null) side = teamSide;
        }
        if (side == null || !"BED WARS".equalsIgnoreCase(EnumChatFormatting.func_110646_a(side.func_96678_d()).trim())) return;
        for (Score score : board.func_96534_i(side)) {
            String name = score.func_96653_e();
            String row = ScorePlayerTeam.func_96667_a(board.func_96509_i(name), name);
            Matcher timer = DIAMOND_TIMER.matcher(EnumChatFormatting.func_110646_a(row).trim());
            if (!timer.matches()) continue;
            int remaining = 60 * Integer.parseInt(timer.group(1)) + Integer.parseInt(timer.group(2));
            // The captures start this first upgrade clock at 6:00. Other modes can use attach.
            if (remaining > 0 && remaining <= 360) begin(now, 360 - remaining, true);
            return;
        }
    }

    private Map<UUID, Pose> poses(Minecraft mc) {
        Map<UUID, Pose> poses = new LinkedHashMap<>();
        for (Object object : world.field_73010_i) {
            EntityPlayer player = (EntityPlayer)object;
            if (valid(player)) {
                Pose pose = new Pose(player, teamKeys(team(mc, player)));
                poses.put(pose.id, pose);
            }
        }
        EntityPlayer own = mc.field_71439_g;
        if (valid(own) && !poses.containsKey(own.func_110124_au())) {
            Pose pose = new Pose(own, teamKeys(team(mc, own)));
            poses.put(pose.id, pose);
        }
        return poses;
    }

    void tick(Minecraft mc) {
        long now = System.nanoTime();
        sync(mc, now);
        updateWorker();
        if (world == null || mc.field_71439_g == null) return;
        recoverClock(mc, now);
        if (start == 0) return;
        long elapsed = now - start;
        int second = (int)(elapsed / SECOND);
        if (second < 2 || second <= lastSecond || elapsed % SECOND > SECOND / 4) return;
        lastSecond = second;
        UUID local = mc.field_71439_g.func_110124_au();
        Map<UUID, Pose> poses = poses(mc);
        Map<UUID, Observation> observations = new LinkedHashMap<>();
        for (Pose pose : poses.values()) observations.put(pose.id, pose.observation);
        Map<UUID, Input> inputs = history.add(second, local, observations);
        if (!enabled || predictor == null || pending != null || selectedHorizons.isEmpty()) return;
        boolean withVolume = usesVolume(display) && predictor.hasVolume();
        int[] horizons = selectedHorizons.stream().mapToInt(Integer::intValue).toArray();
        List<Query> queries = new ArrayList<>();
        for (Pose pose : poses.values()) {
            if (!selected(pose, local) || !BedWarsForecastHistory.withinGrid(pose.observation.xyz)) continue;
            float[][][] examples = new float[horizons.length][][];
            BedWarsForecastHistory.PersonalContext[] personal = new BedWarsForecastHistory.PersonalContext[horizons.length];
            for (int i = 0; i < horizons.length; i++) {
                examples[i] = history.examples(pose.id, horizons[i], second);
                personal[i] = history.personalContext(pose.id, horizons[i], second);
            }
            queries.add(new Query(inputs.get(pose.id), examples, personal));
        }
        if (queries.isEmpty()) {
            result = null;
            refreshDrawing();
            return;
        }
        final BedWarsPredictor network = predictor;
        final long generation = revision;
        final String chosenModel = model;
        final TerrainMask terrainMask = withVolume ? snapshotTerrain(world) : null;
        pending = worker.submit(() -> predict(network, generation, second, chosenModel, horizons, queries, withVolume, terrainMask));
    }

    private static Result predict(BedWarsPredictor network, long generation, int second, String model,
                                  int[] horizons, List<Query> queries, boolean withVolume, TerrainMask terrainMask) {
        float[][] grids = new float[horizons.length][GRID * GRID];
        Volume[] volumes = withVolume ? new Volume[horizons.length] : null;
        if (volumes != null) for (int i = 0; i < horizons.length; i++) {
            volumes[i] = new Volume();
            grids[i] = volumes[i].horizontal;
        }
        int[] labels = new int[horizons.length];
        for (Query query : queries) {
            Input input = query.input;
            for (int i = 0; i < horizons.length; i++) {
                float[][] support = query.supports[i];
                BedWarsForecastHistory.PersonalContext personal = query.personal[i];
                if (volumes != null) {
                    Volume single = network.forecast3d(model, horizons[i], input.features, input.observation.xyz,
                            input.velocity, input.observation.yaw, support, personal);
                    if (terrainMask != null) {
                        single.applyTerrain(terrainMask.voxelWeight, terrainMask.walkableFeet, TERRAIN_AIR_WEIGHT);
                    }
                    volumes[i].unionWith(single);
                } else {
                    float[] values = network.forecast(model, horizons[i], input.features, input.observation.xyz,
                            input.velocity, input.observation.yaw, support, personal);
                    for (int n = 0; n < values.length; n++) {
                        float a = Math.max(0f, Math.min(1f, grids[i][n]));
                        float b = Math.max(0f, Math.min(1f, values[n]));
                        grids[i][n] = 1f - (1f - a) * (1f - b);
                    }
                }
                labels[i] += "personal-gate".equals(model) && personal != null ? personal.routes.length : support.length;
            }
        }
        return new Result(generation, second, queries.size(), horizons, labels, grids, volumes);
    }

    private void updateWorker() {
        if (loading != null && loading.isDone()) {
            try {
                predictor = loading.get();
                issue = "";
                if (!models().contains(model)) model = models().contains("meta-history") ? "meta-history" : models().get(0);
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            catch (ExecutionException e) { issue = "Could not open " + bundle.getFileName() + " (/p forecast reload)"; }
            loading = null;
        }
        if ((enabled || modelRequested) && predictor == null && !attemptedLoad) {
            attemptedLoad = true;
            loading = worker.submit(() -> BedWarsPredictor.load(bundle));
        }
        if (pending != null && pending.isDone()) {
            try {
                Result ready = pending.get();
                if (ready.revision == revision) {
                    result = ready;
                    terrain = world == null ? null : surfaces(world);
                    refreshDrawing();
                    issue = "";
                }
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            catch (ExecutionException e) { issue = "Forecast failed: " + e.getCause().getMessage(); }
            pending = null;
        }
    }

    private TerrainMask snapshotTerrain(WorldClient world) {
        float[] voxelWeight = new float[BedWarsPredictor.VOXELS];
        int[] walkableFeet = new int[BedWarsPredictor.VOXELS];
        int[] bestDist = new int[BedWarsPredictor.VOXELS];
        Arrays.fill(bestDist, Integer.MAX_VALUE);
        int levels = BedWarsPredictor.LEVELS;
        for (int tx = 0; tx < GRID; tx++) {
            int baseX = MAP_MIN + tx * TILE;
            for (int tz = 0; tz < GRID; tz++) {
                int baseZ = MAP_MIN + tz * TILE;
                int cell = tx * GRID + tz;
                int cellBase = cell * levels;
                boolean loaded = world.func_175667_e(new BlockPos(baseX + TILE / 2, 0, baseZ + TILE / 2));
                if (!loaded) {
                    // Never suppress unloaded distant islands.
                    for (int l = 0; l < levels; l++) voxelWeight[cellBase + l] = 1f;
                    continue;
                }
                for (int dx = 0; dx < TILE; dx += 2) {
                    for (int dz = 0; dz < TILE; dz += 2) {
                        int wx = baseX + dx, wz = baseZ + dz;
                        int surface = world.func_175645_m(new BlockPos(wx, 0, wz)).func_177956_o();
                        if (surface <= 0 || surface >= 248) continue;
                        int dist = Math.abs(dx - TILE / 2) + Math.abs(dz - TILE / 2);
                        // Mark surface level + 1 level below (covered base interior) and 1 level above (jump/stairs).
                        int sLevel = (surface - BedWarsPredictor.MIN_Y) / BedWarsPredictor.HEIGHT_STEP;
                        for (int dl = -1; dl <= 1; dl++) {
                            int lvl = sLevel + dl;
                            if (lvl < 0 || lvl >= levels) continue;
                            int v = cellBase + lvl;
                            float w = dl == 0 ? 1f : 0.45f;
                            if (w > voxelWeight[v]) voxelWeight[v] = w;
                            if (dl == 0 && dist < bestDist[v]) {
                                bestDist[v] = dist;
                                walkableFeet[v] = BedWarsPredictor.packFeet(wx, surface, wz);
                            }
                        }
                    }
                }
            }
        }
        return new TerrainMask(voxelWeight, walkableFeet);
    }

    private float[] surfaces(WorldClient world) {
        float[] heights = new float[PATCHES * PATCHES];
        Arrays.fill(heights, Float.NaN);
        for (int i = 0; i < PATCHES; i++) for (int j = 0; j < PATCHES; j++) {
            int baseX = MAP_MIN + i * PATCH, baseZ = MAP_MIN + j * PATCH;
            if (!world.func_175667_e(new BlockPos(baseX + PATCH / 2, 0, baseZ + PATCH / 2))) continue;
            int best = 0;
            for (int dx = 0; dx < PATCH; dx++) {
                for (int dz = 0; dz < PATCH; dz++) {
                    int surface = world.func_175645_m(new BlockPos(baseX + dx, 0, baseZ + dz)).func_177956_o();
                    if (surface > best) best = surface;
                }
            }
            if (best > 0) heights[i * PATCHES + j] = best + .045f;
        }
        return heights;
    }

    private static boolean usesVolume(BedWarsForecastDisplay options) {
        return options.mode != Mode.HEATMAP || options.projection == Projection.VOLUME;
    }

    private void refreshDrawing() {
        boolean hasVolume = result != null && result.volumes != null;
        ghosts = display.mode == Mode.HEATMAP || !hasVolume
                ? Collections.emptyList()
                : BedWarsForecastGhosts.select(result.volumes, result.horizons, display.threshold, display.limit);
        volumeBins = null;
        if (hasVolume && display.mode != Mode.GHOSTS && display.projection == Projection.VOLUME) {
            volumeBins = new int[result.horizons.length][];
            for (int i = 0; i < volumeBins.length; i++) volumeBins[i] = BedWarsVolumeDrawing.strongest(result.volumes[i]);
        }
    }

    private void setDisplay(Mode mode, Projection projection, int threshold, int limit) throws IOException {
        if (display.mode == mode && display.projection == projection
                && display.threshold == threshold && display.limit == limit) return;
        BedWarsForecastDisplay change = new BedWarsForecastDisplay(mode, projection, threshold, limit);
        change.save(displayFile);
        boolean needsNewVolume = usesVolume(change) && !usesVolume(display) && (result == null || result.volumes == null);
        display = change;
        if (needsNewVolume && pending != null) revision++;
        refreshDrawing();
    }

    void draw(Minecraft mc, float partial) {
        Result data = result;
        Entity camera = mc.func_175606_aa();
        if (!enabled || mc.field_71441_e != world || data == null || terrain == null || camera == null) return;
        if (System.nanoTime() - start > ((long)data.second + 4) * SECOND) return;
        double cx = camera.field_70142_S + (camera.field_70165_t - camera.field_70142_S)*partial;
        double cy = camera.field_70137_T + (camera.field_70163_u - camera.field_70137_T)*partial;
        double cz = camera.field_70136_U + (camera.field_70161_v - camera.field_70136_U)*partial;
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT | GL11.GL_CURRENT_BIT);
        GL11.glPushMatrix();
        try {
            GL11.glTranslated(-cx, -cy, -cz);
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            GL11.glDisable(GL11.GL_LIGHTING);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glDisable(GL11.GL_ALPHA_TEST);
            GL11.glEnable(GL11.GL_DEPTH_TEST);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GL11.glDepthMask(false);
            if (display.mode != Mode.GHOSTS) {
                if (display.projection == Projection.SUM_Y) drawHeatmap(data, cx, cz);
                else if (volumeBins != null) for (int i = 0; i < data.horizons.length; i++) {
                    float opacity = display.mode == Mode.BOTH ? .58f : 1f;
                    BedWarsVolumeDrawing.draw(data.volumes[i], volumeBins[i], color(data.horizons[i]),
                            i, data.horizons.length, cx, cz, opacity);
                }
            }
            for (Marker marker : ghosts)
                BedWarsGhostDrawing.draw(mc.func_175598_ae(), marker, BedWarsForecastOverlay::color, cx, cy, cz);
        } finally {
            GL11.glPopMatrix();
            GL11.glPopAttrib();
        }
    }

    private void drawHeatmap(Result data, double cx, double cz) {
        float opacity = display.mode == Mode.BOTH ? .58f : 1f;
        GL11.glBegin(GL11.GL_QUADS);
        for (int layer = 0; layer < data.horizons.length; layer++) {
            int color = color(data.horizons[layer]);
            for (int i = 0; i < PATCHES; i++) for (int j = 0; j < PATCHES; j++) {
                float y = terrain[i*PATCHES+j];
                float chance = data.grids[layer][(i*PATCH/TILE)*GRID + j*PATCH/TILE];
                if (!Float.isFinite(y) || chance < .002f) continue;
                double x = MAP_MIN + i*PATCH, z = MAP_MIN + j*PATCH;
                if ((x-cx)*(x-cx)+(z-cz)*(z-cz) > 148*148) continue;
                double part = (PATCH-.10)/data.horizons.length;
                double a = x + .05 + layer*part, b = a + part - .04;
                float alpha = Math.min(.64f, (float)(Math.sqrt(chance)*1.08)) * opacity;
                GL11.glColor4f(((color>>16)&255)/255f, ((color>>8)&255)/255f, (color&255)/255f, alpha);
                GL11.glVertex3d(a,y,z+.05);GL11.glVertex3d(b,y,z+.05);
                GL11.glVertex3d(b,y,z+PATCH-.05);GL11.glVertex3d(a,y,z+PATCH-.05);
            }
        }
        GL11.glEnd();
    }

    private static int color(int seconds) { return COLORS[Arrays.binarySearch(HORIZONS,seconds)]; }

    void hud(Minecraft mc, int width, int height) {
        if (!enabled || mc.field_71462_r != null) return;
        String line = status();
        int y = height - 43;
        if (y < 4) return;
        String clipped = mc.field_71466_p.func_78269_a(line, width - 14);
        int lineWidth = mc.field_71466_p.func_78256_a(clipped);
        Gui.func_73734_a(4,y-3,Math.min(width-2,Math.max(140,12+lineWidth)),y+(result==null?11:23),0xBC10151D);
        mc.field_71466_p.func_175063_a(clipped,7,y,0xFFE7EFF1);
        if (result == null) return;
        int x = 7;
        for (int i = 0; i < result.horizons.length; i++) {
            int horizon = result.horizons[i];
            String label = horizon + "s";
            if (adaptive(model) && result.horizons.length == 1)
                label += " · " + result.labels[i] / result.players + " earlier outcomes per player";
            int color = color(horizon) | 0xFF000000;
            Gui.func_73734_a(x,y+13,x+5,y+18,color);
            mc.field_71466_p.func_175063_a(label,x+8,y+12,color);
            x += 14+mc.field_71466_p.func_78256_a(label);
        }
    }

    private String status() {
        if (!enabled) return "Forecast off";
        if (!issue.isEmpty()) return issue;
        if (loading != null || predictor == null) return "Loading forecast model";
        if (!predictor.hasVolume() && usesVolume(display)) return "3D forecasts require the updated model bundle";
        if (start == 0) return "Forecast waiting for BedWars start";
        if (selectedHorizons.isEmpty()) return "Forecast needs a selected horizon";
        if (result == null) return "Forecast waiting for selected players";
        return displayModel(model) + " · " + result.players + (result.players == 1 ? " player" : " players")
                + (display.mode == Mode.GHOSTS ? "" : " · " + display.projection.label)
                + (display.mode == Mode.HEATMAP ? "" : " · " + ghosts.size() + " ghosts ≥" + display.threshold + "%")
                + (estimated ? " · estimated clock" : "");
    }

    private static String displayModel(String id) { return id.replace('-', ' ').replace('_', ' '); }

    private static boolean adaptive(String id) {
        return Arrays.asList("head-tune","anil","history","meta-history","personal-hotspots","personal-routes","personal-gate").contains(id);
    }

    private List<String> models() {
        return predictor == null ? STANDARD_MODELS : Arrays.asList(predictor.models());
    }

    private void enable(boolean value) {
        enabled = value;
        clearResults();
        if (value && predictor == null) attemptedLoad = false;
    }

    private void reload() {
        if (loading != null) loading.cancel(true);
        loading = null;
        predictor = null;
        attemptedLoad = false;
        modelRequested = true;
        issue = "";
        clearResults();
    }

    private static Set<String> parseNames(String csv, Pattern pattern) {
        TreeSet<String> names = new TreeSet<>();
        String text = csv.trim();
        if (text.isEmpty() || text.equalsIgnoreCase("all") || text.equalsIgnoreCase("clear")) return names;
        for (String name : text.split("[ ,]+")) {
            if (!pattern.matcher(name).matches()) throw new IllegalArgumentException("Invalid selection " + name);
            names.add(lower(name));
        }
        return names;
    }

    String command(Minecraft mc, String[] args) throws IOException {
        String option = lower(args[1]);
        String value = String.join(" ", Arrays.copyOfRange(args, 2, args.length)).trim();
        switch (option) {
            case "on": case "off": enable(option.equals("on")); return status();
            case "status": return status();
            case "models": return "Models · " + String.join(", ", models());
            case "reload": reload(); return "BedWars model will reload when enabled";
            case "display":
                if (!value.isEmpty()) setDisplay(Mode.parse(value), display.projection, display.threshold, display.limit);
                return "Forecast display " + display.mode.label;
            case "projection":
                if (!value.isEmpty()) setDisplay(display.mode, Projection.parse(value), display.threshold, display.limit);
                return "Forecast heatmap " + display.projection.label;
            case "threshold":
                if (!value.isEmpty()) setDisplay(display.mode, display.projection,
                        BedWarsForecastDisplay.threshold(value), display.limit);
                return "Minimum ghost hotspot probability " + display.threshold + "%";
            case "ghost-limit":
                if (!value.isEmpty()) setDisplay(display.mode, display.projection, display.threshold,
                        BedWarsForecastDisplay.limit(value));
                return "Maximum forecast ghosts " + display.limit;
            case "model":
                String choice = lower(value);
                if (!models().contains(choice)) throw new IllegalArgumentException("Choose /p forecast models");
                model = choice;
                clearResults();
                return "Forecast model " + displayModel(model);
            case "horizons":
                TreeSet<Integer> values = new TreeSet<>();
                for (String text : value.split("[ ,]+")) {
                    int seconds = Integer.parseInt(text);
                    if (Arrays.binarySearch(HORIZONS, seconds) < 0) throw new IllegalArgumentException("Choose 5, 10, 30, 60 or 120 seconds");
                    values.add(seconds);
                }
                selectedHorizons.clear();
                selectedHorizons.addAll(values);
                clearResults();
                return "Forecast horizons " + selectedHorizons + " seconds";
            case "players":
                selectedPlayers = parseNames(value, PLAYER_NAME);
                clearResults();
                return "Player filter " + (selectedPlayers.isEmpty()?"cleared":String.join(", ", selectedPlayers));
            case "teams":
                selectedTeams = parseNames(value, TEAM_NAME);
                clearResults();
                return "Team filter " + (selectedTeams.isEmpty()?"cleared":String.join(", ", selectedTeams));
            case "attach":
                if (mc.field_71439_g == null || mc.field_71441_e == null) throw new IllegalArgumentException("Join the match first");
                int seconds = value.isEmpty() ? 0 : Integer.parseInt(value);
                if (seconds < 0 || seconds > 7200) throw new IllegalArgumentException("Choose elapsed seconds from 0 to 7200");
                sync(mc, System.nanoTime());
                begin(System.nanoTime(), seconds, true);
                enable(true);
                return "Attached with estimated elapsed time " + seconds + "s";
            default: throw new IllegalArgumentException("Use /p help forecast");
        }
    }

    List<String> completeCommand(Minecraft mc, String[] args) {
        if (args.length == 2) return Arrays.asList("on","off","status","models","model","horizons","players","teams",
                "display","projection","threshold","ghost-limit","attach","reload");
        if (args.length != 3) return Collections.emptyList();
        switch (args[1]) {
            case "model": return models();
            case "horizons": return Arrays.asList("5","10","30","60","120");
            case "display": return Arrays.asList("heatmap","ghosts","both");
            case "projection": return Arrays.asList("sum-y","3d");
            case "players":
                TreeSet<String> players = new TreeSet<>(Arrays.asList("me", "clear"));
                for (NetworkPlayerInfo info : roster(mc)) players.add(info.func_178845_a().getName());
                return new ArrayList<>(players);
            case "teams": return teams(mc);
            default: return Collections.emptyList();
        }
    }

    private static List<NetworkPlayerInfo> roster(Minecraft mc) {
        NetHandlerPlayClient connection = mc.func_147114_u();
        if (connection == null) return Collections.emptyList();
        List<NetworkPlayerInfo> players = new ArrayList<>();
        for (NetworkPlayerInfo info : connection.func_175106_d()) {
            UUID id = info.func_178845_a().getId();
            String name = info.func_178845_a().getName();
            if (id != null && id.version() == 4 && PLAYER_NAME.matcher(name).matches()) players.add(info);
        }
        return players;
    }

    private static List<String> teams(Minecraft mc) {
        TreeSet<String> teams = new TreeSet<>(Arrays.asList("red","blue","green","yellow","aqua","pink","gray","white","clear"));
        for (NetworkPlayerInfo info : roster(mc)) teams.addAll(teamKeys(info.func_178850_i()));
        return new ArrayList<>(teams);
    }

    GuiScreen screen(GuiScreen parent) { modelRequested = true; return new Options(parent); }

    private final class Options extends GuiScreen {
        private final GuiScreen parent;
        private GuiButton modelButton, enabledButton, displayButton, projectionButton;
        private GuiTextField players, teams, threshold, limit;
        private GuiTextField[] fields;
        private Mode mode = display.mode;
        private Projection projection = display.projection;
        private int left, width, top;
        private String error = "";
        Options(GuiScreen parent) { this.parent = parent; }
        @Override public boolean func_73868_f() { return false; }

        @Override public void func_73866_w_() {
            String names = players == null ? String.join(", ", selectedPlayers) : players.func_146179_b();
            String colors = teams == null ? String.join(", ", selectedTeams) : teams.func_146179_b();
            String minimum = threshold == null ? Integer.toString(display.threshold) : threshold.func_146179_b();
            String maximum = limit == null ? Integer.toString(display.limit) : limit.func_146179_b();
            width = Math.min(320, field_146294_l - 20);
            left = (field_146294_l - width)/2;
            top = Math.max(4, (field_146295_m - 222)/2);
            field_146292_n.clear();
            modelButton = button(0,left+92,top+29,width-92,"");
            enabledButton = button(1,left,top+29,86,"");
            for (int i = 0; i < HORIZONS.length; i++) button(10+i,left+i*width/5,top+61,width/5-2,"");
            displayButton = button(4,left,top+95,width/4-5,"");
            projectionButton = button(5,left+width/4,top+95,width/4-5,"");
            threshold = field(2,left+width/2,top+96,width/4-5,minimum);
            limit = field(3,left+3*width/4,top+96,width/4,maximum);
            threshold.func_146203_f(4);
            limit.func_146203_f(2);
            players = field(0,left,top+127,width,names);
            teams = field(1,left,top+159,width,colors);
            fields = new GuiTextField[]{threshold, limit, players, teams};
            button(2,left,top+194,83,"Clear filters");
            button(3,left+width-70,top+194,70,"Done");
            update();
        }

        private GuiTextField field(int id, int x, int y, int w, String value) {
            GuiTextField field = new GuiTextField(id,field_146289_q,x,y,w,18);
            field.func_146203_f(128);
            field.func_146180_a(value);
            return field;
        }

        private GuiButton button(int id,int x,int y,int w,String text) {
            GuiButton button = new GuiButton(id,x,y,w,20,text);
            field_146292_n.add(button);
            return button;
        }

        private void update() {
            modelButton.field_146126_j = "Model  " + displayModel(model);
            enabledButton.field_146126_j = enabled ? "Overlay on" : "Overlay off";
            displayButton.field_146126_j = mode.label;
            projectionButton.field_146126_j = projection.label;
            projectionButton.field_146124_l = mode != Mode.GHOSTS;
            for (Object object : field_146292_n) {
                GuiButton button = (GuiButton)object;
                int i = button.field_146127_k - 10;
                if (i >= 0 && i < HORIZONS.length) {
                    int h = HORIZONS[i];
                    button.field_146126_j = (selectedHorizons.contains(h)?"§a":"§7") + h + "s";
                }
            }
        }

        @Override protected void func_146284_a(GuiButton button) {
            int id = button.field_146127_k;
            if (id == 0) {
                List<String> choices = models();
                model = choices.get((choices.indexOf(model)+1) % choices.size());
                clearResults();
            } else if (id == 1) enable(!enabled);
            else if (id == 2) { players.func_146180_a(""); teams.func_146180_a(""); }
            else if (id == 3) { done(); return; }
            else if (id == 4) mode = Mode.values()[(mode.ordinal() + 1) % Mode.values().length];
            else if (id == 5) projection = projection == Projection.SUM_Y ? Projection.VOLUME : Projection.SUM_Y;
            else if (id >= 10 && id < 10+HORIZONS.length) {
                int horizon = HORIZONS[id-10];
                if (!selectedHorizons.add(horizon)) selectedHorizons.remove(horizon);
                clearResults();
            }
            update();
        }

        private void done() {
            try {
                Set<String> names = parseNames(players.func_146179_b(), PLAYER_NAME);
                Set<String> colors = parseNames(teams.func_146179_b(), TEAM_NAME);
                setDisplay(mode, projection, BedWarsForecastDisplay.threshold(threshold.func_146179_b()),
                        BedWarsForecastDisplay.limit(limit.func_146179_b()));
                boolean filtersChanged = !selectedPlayers.equals(names) || !selectedTeams.equals(colors);
                selectedPlayers = names;
                selectedTeams = colors;
                if (filtersChanged) clearResults();
                field_146297_k.func_147108_a(parent);
            } catch (IllegalArgumentException | IOException failure) { error = failure.getMessage(); }
        }

        @Override protected void func_73864_a(int x, int y, int button) {
            for (GuiTextField field : fields) field.func_146192_a(x,y,button);
            super.func_73864_a(x,y,button);
        }
        @Override protected void func_73869_a(char character, int key) {
            if (key == Keyboard.KEY_ESCAPE) field_146297_k.func_147108_a(parent);
            else if (key == Keyboard.KEY_RETURN || key == Keyboard.KEY_NUMPADENTER) done();
            else if (key == Keyboard.KEY_TAB) {
                int next = 0;
                for (int i = 0; i < fields.length; i++) {
                    if (fields[i].func_146181_i()) next = (i + 1) % fields.length;
                    fields[i].func_146195_b(false);
                }
                fields[next].func_146195_b(true);
            } else for (GuiTextField field : fields) {
                if (field.func_146201_a(character,key)) break;
            }
        }
        @Override public void func_73876_c() {
            for (GuiTextField field : fields) field.func_146178_a();
        }
        @Override public void func_73863_a(int mouseX, int mouseY, float partial) {
            func_146276_q_();
            func_73732_a(field_146289_q,"BedWars Forecast",field_146294_l/2,top+5,0xFFF4FAFB);
            field_146289_q.func_175063_a(field_146289_q.func_78269_a(status(),width),left,top+17,0xFF91C9CE);
            field_146289_q.func_175063_a("Horizons",left,top+50,0xFFDEE6EA);
            field_146289_q.func_175063_a("Display",left,top+84,0xFFDEE6EA);
            field_146289_q.func_175063_a("Heatmap",left+width/4,top+84,0xFFDEE6EA);
            field_146289_q.func_175063_a("Ghost min %",left+width/2,top+84,0xFFDEE6EA);
            field_146289_q.func_175063_a("Max ghosts",left+3*width/4,top+84,0xFFDEE6EA);
            field_146289_q.func_175063_a("Players · comma-separated names",left,top+116,0xFFDEE6EA);
            field_146289_q.func_175063_a("Teams · comma-separated colors or IDs",left,top+148,0xFFDEE6EA);
            field_146289_q.func_175063_a(field_146289_q.func_78269_a(
                    error.isEmpty()?"Leave filters blank to include all observed players":error,width),left,top+180,
                    error.isEmpty()?0xFF91A4AD:0xFFF19B72);
            for (GuiTextField field : fields) field.func_146194_f();
            super.func_73863_a(mouseX,mouseY,partial);
            update();
        }
    }
}
