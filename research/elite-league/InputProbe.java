import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Evaluate an immutable roster on named clocks, with the ordinary input-study field and settings. */
final class InputProbe {
    public static void main(String[] args) throws Exception {
        Path directory = Path.of(args[0]);
        InputStudy.field();
        try {
            var clocks = new ArrayList<DuelEnv>();
            for (String line : Files.readAllLines(directory.resolve("clocks.txt")))
                clocks.add(InputStudy.clock(Path.of(line)));
            var panel = InputStudy.panel(clocks, true);
            Path temp = directory.resolve("transfer.tmp");
            try (var out = new PrintWriter(Files.newBufferedWriter(temp))) {
                out.println("arm,foe,calendar,seat,own_rtt,opponent_rtt,reward");
                for (String line : Files.readAllLines(directory.resolve("entrants.tsv"))) {
                    String[] fields = line.split("\t");
                    var actor = fields.length == 2 ? InputStudy.fighter(fields[0], Path.of(fields[1]))
                                                  : Arena.Fighter.named(fields[0]);
                    PingLeague.evaluate(actor, panel, out);
                }
                if (out.checkError()) throw new IOException("Failed to write probe scores");
            }
            Files.move(temp, directory.resolve("transfer.csv"), StandardCopyOption.ATOMIC_MOVE);
        } finally {
            PingLeague.pool.shutdown();
        }
    }
}
