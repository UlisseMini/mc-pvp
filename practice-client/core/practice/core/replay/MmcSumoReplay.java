package practice.core.replay;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Properties;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Matches MMC's own announcement, Sumo instructions and participant result. */
public final class MmcSumoReplay {
    private static final Pattern OPPONENT = Pattern.compile("(?m)^\\s*[●•]?\\s*Opponent: ([A-Za-z0-9_]{1,16})(?:\\s*\\([^\\r\\n]*\\))?\\s*$");
    private static final Pattern RESULT = Pattern.compile("^Winner: ([A-Za-z0-9_]{1,16})\\s*[▏|]\\s*Loser: ([A-Za-z0-9_]{1,16})$");
    private static final Pattern LOCAL_CAPTURE = Pattern.compile("a-client-[0-9A-Fa-f-]{16,160}-events\\.jsonl\\.gz");
    private static final Pattern ID = Pattern.compile("[a-f0-9]{32}");
    private String capture, opponent;
    private int part;
    private long start, fight, resultHeader;
    private boolean sumo;

    public String activeId() { return sumo && opponent != null ? id() : null; }
    public String opponent() { return opponent; }
    public long combatStart() { return fight; }

    private String id() {
        return UUID.nameUUIDFromBytes((capture + ":" + fight).getBytes(StandardCharsets.UTF_8)).toString().replace("-", "");
    }

    public static final class Match {
        public final String id, capture, opponent;
        public final int part;
        public final long start, finish;
        public final boolean won;

        private Match(String id, String capture, String opponent, int part, long start, long finish, boolean won) {
            this.id = id;
            this.capture = capture;
            this.opponent = opponent;
            this.part = part;
            this.start = start;
            this.finish = finish;
            this.won = won;
        }

        public Path firstSegment(Path captures) {
            String name = part == 1 ? capture : capture.replace(".jsonl.gz", "-part" + part + ".jsonl.gz");
            return captures.resolve(name);
        }

        public void save(Path folder) throws IOException {
            Files.createDirectories(folder);
            Properties p = new Properties();
            p.setProperty("capture", capture);
            p.setProperty("opponent", opponent);
            p.setProperty("part", Integer.toString(part));
            p.setProperty("start", Long.toString(start));
            p.setProperty("finish", Long.toString(finish));
            p.setProperty("won", Boolean.toString(won));
            Path temp = Files.createTempFile(folder, "sumo-", ".tmp");
            try {
                try (OutputStream out = Files.newOutputStream(temp)) { p.store(out, "MMC Sumo replay"); }
                Files.move(temp, folder.resolve(id + ".properties"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(temp);
            }
        }
    }

    public static boolean server(String address) {
        if (address == null) return false;
        String host = address.trim().toLowerCase(Locale.ROOT).replaceFirst(":[0-9]+$", "").replaceFirst("\\.$", "");
        return host.equals("minemen.club") || host.endsWith(".minemen.club")
                || host.equals("mineman.club") || host.endsWith(".mineman.club");
    }

    public static Match read(Path folder, String id) throws IOException {
        if (!ID.matcher(id).matches()) return null;
        Path file = folder.resolve(id + ".properties");
        if (!Files.isRegularFile(file) || Files.size(file) > 4096) return null;
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(file)) { p.load(in); }
        String capture = p.getProperty("capture", ""), opponent = p.getProperty("opponent", "");
        if (!LOCAL_CAPTURE.matcher(capture).matches() || !opponent.matches("[A-Za-z0-9_]{1,16}")) return null;
        try {
            int part = Integer.parseInt(p.getProperty("part", ""));
            long start = Long.parseLong(p.getProperty("start", "")), finish = Long.parseLong(p.getProperty("finish", ""));
            if (part < 1 || part > 10000 || start <= 0 || finish <= start || finish - start > 600000) return null;
            return new Match(id, capture, opponent, part, start, finish, Boolean.parseBoolean(p.getProperty("won")));
        } catch (NumberFormatException malformed) {
            return null;
        }
    }

    public void reset() {
        capture = null;
        clearMatch();
    }

    private void clearMatch() {
        opponent = null;
        start = fight = resultHeader = 0;
        sumo = false;
    }

    public Match chat(String raw, String player, long now, String filename, int segment) {
        if (filename == null || !LOCAL_CAPTURE.matcher(filename).matches()) {
            reset();
            return null;
        }
        if (!filename.equals(capture)) {
            clearMatch();
            capture = filename;
        }
        String text = raw.replaceAll("(?i)§[0-9A-FK-OR]", "").trim();
        Matcher intro = OPPONENT.matcher(text);
        if (text.contains("Map: ") && intro.find()) {
            clearMatch();
            start = now;
            part = Math.max(1, segment - 2);
            opponent = intro.group(1);
        } else if (text.equals("5...") && fight == 0 && start != 0 && now - start <= 10000) {
            start = now;
        } else if (text.equals("Match started!")) {
            if (fight != 0 && now - fight <= 2500) return null;
            if (start == 0 || now - start > 30000) {
                clearMatch();
                start = now;
                part = Math.max(1, segment - 1);
            }
            fight = now;
        } else if (fight > 0 && now - fight <= 2500
                && text.matches("(?s)^Sumo\\s+Knock your opponents off the platform!\\s*$")) {
            sumo = true;
        } else if (text.equals("Match Results (Click to view)")) {
            resultHeader = now;
        } else if (sumo && resultHeader > fight && now - resultHeader <= 2500 && now - start <= 600000) {
            Matcher result = RESULT.matcher(text);
            if (!result.matches()) return null;
            String winner = result.group(1), loser = result.group(2);
            boolean won = winner.equalsIgnoreCase(player);
            if (!won && !loser.equalsIgnoreCase(player)) return null;
            String other = won ? loser : winner;
            if (opponent != null && !opponent.equalsIgnoreCase(other)) return null;
            Match match = new Match(id(), capture, other, part, start, now, won);
            clearMatch();
            return match;
        }
        return null;
    }

    static boolean resultLine(String text){return RESULT.matcher(text).matches();}
}
