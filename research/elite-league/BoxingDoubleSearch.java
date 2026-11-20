import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import lab.DuelNetwork;
import lab.Json;

/** Search forward movement and sprint timing against fixed-click W-tap. */
final class BoxingDoubleSearch {
    private static final int WIDTH = DuelNetwork.INPUTS + 1;
    private static final int[] MOTION_INPUTS = {0, 1, 2, 3, 5, 6, 8, 9, 10, 11, 12, 16, 17, 21, 22};
    private static final int HORIZON = 850;

    record Bout(DuelEnv env, boolean first, Score control) {}
    record Score(int own, int other, int ownFresh, int otherFresh, int ownDoubles, int otherDoubles) {
        int margin() { return own - other; }
        int freshMargin() { return ownFresh - otherFresh; }
        int doubleMargin() { return ownDoubles - otherDoubles; }
        List<Integer> values() { return List.of(own, other, ownFresh, otherFresh, ownDoubles, otherDoubles); }
    }
    record Entry(Arena.Fighter fighter, double merit, double margin, double netDoubles) {}
    record ReturnResult(int cycles, double exposure, String terminal) {
        List<Object> values() { return List.of(cycles, exposure, terminal); }
    }

    static DuelEnv environment(Path clock, int ping, int cps, int duration) throws Exception {
        return environment(clock, "mmc", ping, cps, duration);
    }

    static DuelEnv environment(Path clock, String profile, int ping, int cps, int duration) throws Exception {
        var tape = DuelEnv.Tape.read(clock, duration);
        var env = new DuelEnv(tape, profile, ping, ping, false, 29, true, 10, 6, 0, duration);
        env.initial.timing.clickIntervalNanos = (1_000_000_000L + cps - 1) / cps;
        env.initial.timing.mouseInput = true;
        return env;
    }

    static Score play(DuelEnv env, boolean first, Arena.Fighter fighter, int target) {
        return play(env, first, fighter.create(env.rtt, 0), target);
    }

    static Score play(DuelEnv env, boolean first, DuelEnv.Player subject, int target) {
        var game = env.new Game(first, subject, Evolve.WT.create(env.rtt, 0));
        game.trace();
        return play(game, target);
    }

    static Score play(DuelEnv.Game game, int target) {
        boolean first = game.first;
        int own = 0, other = 0, ownFresh = 0, otherFresh = 0;
        int ownDoubles = 0, otherDoubles = 0, runSide = 0, run = 0;
        double last = Double.NEGATIVE_INFINITY;
        int index = 0;
        while (own < target && other < target && game.advance()) {
            while (index < game.hits.list.size()) {
                double time = game.hits.list.get(index).at();
                int side = 0, fresh = 0;
                while (index < game.hits.list.size() && game.hits.list.get(index).at() == time) {
                    var hit = game.hits.list.get(index++);
                    boolean ours = hit.sideA() == first;
                    side |= ours ? 1 : 2;
                    if (ours) own++; else other++;
                    if (!hit.upgrade()) {
                        fresh++;
                        if (ours) ownFresh++; else otherFresh++;
                    }
                    if (own == target || other == target) break;
                }
                if (side == 3 || side != runSide || time - last > HORIZON) {
                    runSide = side == 3 ? 0 : side;
                    run = 0;
                }
                if (runSide != 0 && fresh > 0) {
                    if (run == 1) {
                        if (runSide == 1) ownDoubles++; else otherDoubles++;
                    }
                    run += fresh;
                    last = time;
                }
                if (own == target || other == target) break;
            }
        }
        return new Score(own, other, ownFresh, otherFresh, ownDoubles, otherDoubles);
    }

    private static Arena.Model constrained(Arena.Model model) {
        var w = model.weights().clone();
        int width = model.family() == DuelNetwork.Family.LINEAR ? WIDTH : DuelNetwork.HIDDEN + 1;
        int click = w.length - 3 * width;
        Arrays.fill(w, click, w.length, 0);
        w[click] = 1; // Always open at the same physical click cap.
        w[click + width] = -1; // Fixed jump and strafe.
        return new Arena.Model(model.family(), w);
    }

    private static Arena.Model imitateTap(float chase, float brakes, float rearm) {
        var w = new float[DuelNetwork.size(DuelNetwork.Family.LINEAR)];
        w[0] = .834f;
        w[12] = -1; // Fresh target hurt cue produces a short release in the seed.
        w[1] = chase;
        w[3] = brakes;
        w[WIDTH] = 1;
        w[WIDTH + 10] = rearm;
        return constrained(new Arena.Model(DuelNetwork.Family.LINEAR, w));
    }

    private static Arena.Model mutate(Arena.Model model, Random random, double scale) {
        if (model.family() != DuelNetwork.Family.LINEAR) return constrained(Evolve.mutate(model, random, scale));
        float[] w = model.weights().clone();
        int changes = 1 + random.nextInt(8);
        for (int i = 0; i < changes; i++) {
            int slot = random.nextInt(2) * WIDTH + MOTION_INPUTS[random.nextInt(MOTION_INPUTS.length)];
            w[slot] = (float)Math.max(-4, Math.min(4, w[slot] + scale * random.nextGaussian()));
        }
        return new Arena.Model(model.family(), w);
    }

    private static Entry evaluate(Arena.Fighter fighter, List<Bout> bouts) {
        double[] byLatency = new double[2];
        int[] n = new int[2];
        double margin = 0, doubles = 0;
        for (Bout bout : bouts) {
            Score score = fighter.model() == null ? bout.control : play(bout.env, bout.first, fighter, 100);
            int accepted = score.margin() - bout.control.margin();
            int fresh = score.freshMargin() - bout.control.freshMargin();
            int doubled = score.doubleMargin() - bout.control.doubleMargin();
            int rate = bout.env.rtt == 0 ? 0 : 1;
            byLatency[rate] += Math.tanh((.7 * accepted + .65 * fresh + doubled) / 8);
            n[rate]++;
            margin += accepted;
            doubles += doubled;
        }
        double a = byLatency[0] / n[0], b = byLatency[1] / n[1];
        return new Entry(fighter, .7 * (a + b) / 2 + .3 * Math.min(a, b),
                margin / bouts.size(), doubles / bouts.size());
    }

    private static List<Entry> rank(List<Arena.Fighter> population, List<Bout> bouts, ExecutorService pool) throws Exception {
        var jobs = new ArrayList<Callable<Entry>>();
        for (var fighter : population) jobs.add(() -> evaluate(fighter, bouts));
        var ranked = new ArrayList<Entry>();
        for (var result : pool.invokeAll(jobs)) ranked.add(result.get());
        ranked.sort(Comparator.comparingDouble(Entry::merit).reversed()
                .thenComparing(e -> e.fighter.name()));
        return ranked;
    }

    private static void train(String[] args) throws Exception {
        int cps = Integer.parseInt(args[1]), generations = Integer.parseInt(args[2]);
        int seed = Integer.parseInt(args[3]);
        Path out = Path.of(args[4]);
        Files.createDirectory(out);
        List<List<Bout>> blocks = new ArrayList<>();
        for (String file : Arrays.copyOfRange(args, 6, args.length)) {
            var bouts = new ArrayList<Bout>();
            for (int ping : new int[]{0, 80}) {
                var env = environment(Path.of(file), ping, cps, 24_000);
                for (boolean first : new boolean[]{true, false}) {
                    bouts.add(new Bout(env, first, play(env, first, Evolve.WT, 100)));
                }
            }
            blocks.add(bouts);
        }
        var random = new Random(seed);
        var family = DuelNetwork.Family.valueOf(args[5]);
        if (family != DuelNetwork.Family.LINEAR && family != DuelNetwork.Family.MLP) {
            throw new IllegalArgumentException("Use LINEAR or MLP");
        }
        var anchors = new ArrayList<Arena.Model>();
        if (family == DuelNetwork.Family.LINEAR) {
            for (float chase : new float[]{0, 1.2f}) {
                for (float brake : new float[]{0, -.85f}) {
                    anchors.add(imitateTap(chase, brake, 0));
                    anchors.add(imitateTap(chase, brake, -1.35f));
                }
            }
            anchors.add(constrained(Arena.Model.decode(Files.readString(Path.of("research/elite-league/boxing-frozen/specialist.net")).strip())));
        } else {
            anchors.add(constrained(Arena.Model.decode(Files.readString(Path.of("research/elite-league/robust-frozen/champion.net")).strip())));
            anchors.add(constrained(Arena.Model.decode(Files.readString(Path.of("research/elite-league/boxing-distilled-frozen/student.net")).strip())));
        }
        var pool = Executors.newFixedThreadPool(2);
        try (var log = Files.newBufferedWriter(out.resolve("training.jsonl"))) {
            var elites = new ArrayList<Arena.Fighter>();
            int batch = 6;
            var order = new ArrayList<>(blocks);
            for (int generation = 0; generation < generations; generation++) {
                if (generation % 4 == 0) java.util.Collections.shuffle(order, random);
                var bouts = new ArrayList<Bout>();
                int offset = (generation % 4) * batch;
                for (int i = 0; i < batch; i++) bouts.addAll(order.get((offset + i) % order.size()));
                var population = new ArrayList<>(elites);
                population.add(Evolve.WT);
                if (generation == 0) {
                    for (int i = 0; i < anchors.size(); i++) population.add(new Arena.Fighter("anchor-" + i, anchors.get(i)));
                }
                while (population.size() < 29) {
                    Arena.Model parent = elites.isEmpty() || random.nextDouble() < .16
                            ? anchors.get(random.nextInt(anchors.size())) : elites.get(random.nextInt(elites.size())).model();
                    double scale = new double[]{.10, .25, .50, .90}[random.nextInt(4)];
                    population.add(new Arena.Fighter("g" + generation + "-" + population.size(), mutate(parent, random, scale)));
                }
                var ranked = rank(population, bouts, pool);
                elites.clear();
                ranked.stream().filter(e -> e.fighter.model() != null).limit(5).map(Entry::fighter).forEach(elites::add);
                var winner = ranked.stream().filter(e -> e.fighter.model() != null).findFirst().orElseThrow();
                if (generation % 4 == 0 || generation == generations - 1) {
                    Files.writeString(out.resolve(String.format("g%03d.net",generation)), winner.fighter.model().encode() + "\n");
                }
                log.write(Json.encode(Map.of("g", generation, "best", winner.fighter.name(), "wtapMerit", 0,
                        "bouts", bouts.size(), "merit", winner.merit, "marginDelta", winner.margin,
                        "netDoubleDelta", winner.netDoubles)) + "\n");
                log.flush();
            }
        } finally {
            pool.shutdown();
        }
    }

    private static void evaluatePanel(String[] args) throws Exception {
        int cps = Integer.parseInt(args[1]), duration = Integer.parseInt(args[2]);
        var field = Matches.roster(Path.of(args[3]));
        int requestedRtt = Integer.getInteger("lab.boxing.rtt", -1);
        int[] rtts = requestedRtt < 0 ? new int[]{0, 80} : new int[]{requestedRtt};
        for (String file : Arrays.copyOfRange(args, 4, args.length)) {
            for (int ping : rtts) {
                var env = environment(Path.of(file), ping, cps, duration);
                var drill = duration == 20_000 ? env : environment(Path.of(file), ping, cps, 20_000);
                for (boolean first : new boolean[]{true, false}) {
                    Score wtap = play(env, first, Evolve.WT, 100);
                    var starts = BoxingDrill.acquire(drill, first, true);
                    var baselineReturns = starts.stream().map(start -> returnOnly(start, null)).toList();
                    for (var fighter : field) {
                        Score score = fighter.model() == null ? wtap : play(env, first, fighter, 100);
                        var maintenance = new ArrayList<Object>();
                        var returns = new ArrayList<Object>();
                        for (int i = 0; i < starts.size(); i++) {
                            var start = starts.get(i);
                            var base = start.baseline;
                            var candidate = fighter.model() == null ? base : start.run(fighter.model(), null);
                            maintenance.add(List.of(start.k, base.cycles(), candidate.cycles(),
                                    base.ms(), candidate.ms(), base.terminal(), candidate.terminal()));
                            var baseReturn = baselineReturns.get(i);
                            var nextReturn = fighter.model() == null ? baseReturn : returnOnly(start, fighter.model());
                            returns.add(List.of(start.k, baseReturn.values(), nextReturn.values()));
                        }
                        System.out.println(Json.encode(Map.of("clock", env.clockId, "rtt", ping, "side", first?"a":"b",
                                "arm", fighter.name(), "wtap", wtap.values(), "candidate", score.values(),
                                "maintenance", maintenance, "returnOnly", returns)));
                    }
                }
            }
        }
    }

    private static ReturnResult returnOnly(BoxingDrill.Checkpoint start, Arena.Model model) {
        var game = start.suffix(model);
        int cycles = 0, seen = 0;
        do {
            while (seen < game.hits.list.size()) {
                var hit = game.hits.list.get(seen++);
                if (hit.sideA() != game.first) {
                    return new ReturnResult(cycles, hit.at() - start.landmark, "return");
                }
                if (!hit.upgrade()) cycles++;
            }
        } while (game.advance());
        return new ReturnResult(cycles, game.clock.time() - start.landmark, "censored");
    }

    public static void main(String[] args) throws Exception {
        if (args[0].equals("fit")) train(args);
        else if (args[0].equals("score")) evaluatePanel(args);
        else throw new IllegalArgumentException("Use fit or score");
    }
}
