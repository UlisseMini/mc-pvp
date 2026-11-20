import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import lab.RecordedCollisions;

/** Offline, matched changes to the start distance on the two recorded floors. */
final class OpeningContrast {
    private static DuelEnv opening(Path path, RecordedCollisions terrain, int gap) throws Exception {
        var map = terrain.map;
        double centerX = (map.ax + map.bx + 1) / 2.0;
        double centerZ = (map.az + map.bz + 1) / 2.0;
        double dx = Integer.signum(map.bx - map.ax) * gap / 2.0;
        double dz = Integer.signum(map.bz - map.az) * gap / 2.0;
        double[] spawns = { centerX - dx, centerZ - dz, centerX + dx, centerZ + dz, map.yaw(true), map.yaw(false) };
        var duel = new DuelEnv(DuelEnv.Tape.read(path, DuelEnv.LIMIT), "mmc-range", 0, 0, true, 29, true,
                Math.max(map.width, map.depth) - 2, gap, 0, DuelEnv.LIMIT, spawns, terrain);
        duel.initial.timing.mouseInput = true;
        CappedFrozenResponse.cap(duel, 7);
        return duel;
    }

    public static void main(String[] args) throws Exception {
        LeagueWorker.load();
        var terrain = new RecordedCollisions(args[0], 4096, 11);
        int gap = Integer.parseInt(args[1]);
        var actor = LeagueWorker.fighter(args[2]);
        var opponent = LeagueWorker.fighter(args[3]);
        Map<String, DuelEnv> clocks = new HashMap<>();
        for (String line : Files.readAllLines(Path.of(args[4]))) {
            String[] row = line.split("\t");
            String path = row[0];
            boolean first = row[1].equals("a");
            int own = Integer.parseInt(row[2]), other = Integer.parseInt(row[3]);
            if (!clocks.containsKey(path)) clocks.put(path, opening(Path.of(path), terrain, gap));
            var duel = new DuelEnv(clocks.get(path), first ? own : other, first ? other : own);
            var game = duel.new Game(first,
                    LeagueWorker.player(actor, own, other, duel, Long.parseLong(row[4])),
                    LeagueWorker.player(opponent, other, own, duel, Long.parseLong(row[5])));
            game.trace();
            game.finish();
            int fresh = 0;
            double initial = -1, last = -1;
            for (var hit : game.hits.list) {
                if (hit.upgrade()) continue;
                if (fresh++ == 0) initial = hit.at();
                last = hit.at();
            }
            System.out.println(game.reward() + "\t" + (float) game.clock.time() + "\t" + fresh + "\t" + initial + "\t" + last);
        }
    }
}
