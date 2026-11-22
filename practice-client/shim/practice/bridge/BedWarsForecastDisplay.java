package practice.bridge;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Properties;

final class BedWarsForecastDisplay {
    enum Mode {
        HEATMAP("Heatmap"), GHOSTS("Ghosts"), BOTH("Both");

        final String label;

        Mode(String label) { this.label = label; }

        static Mode parse(String text) {
            try { return valueOf(text.toUpperCase(Locale.ROOT)); }
            catch (IllegalArgumentException failure) {
                throw new IllegalArgumentException("Choose heatmap, ghosts or both");
            }
        }
    }

    enum Projection {
        SUM_Y("Sum over Y", "sum-y"), VOLUME("3D", "3d");

        final String label, command;

        Projection(String label, String command) {
            this.label = label;
            this.command = command;
        }

        static Projection parse(String text) {
            for (Projection option : values()) if (option.command.equalsIgnoreCase(text)) return option;
            throw new IllegalArgumentException("Choose sum-y or 3d");
        }
    }

    final Mode mode;
    final Projection projection;
    final int threshold, limit;

    BedWarsForecastDisplay(Mode mode, Projection projection, int threshold, int limit) {
        this.mode = mode;
        this.projection = projection;
        this.threshold = threshold;
        this.limit = limit;
    }

    static int threshold(String value) {
        String text = value.trim();
        if (text.endsWith("%")) text = text.substring(0, text.length() - 1).trim();
        return boundedInteger(text, 0, 100, "Enter a minimum hotspot percentage from 0 to 100");
    }

    static int limit(String text) {
        return boundedInteger(text.trim(), 1, 8, "Enter a ghost limit from 1 to 8");
    }

    private static int boundedInteger(String text, int min, int max, String message) {
        try {
            int n = Integer.parseInt(text);
            if (n >= min && n <= max) return n;
        } catch (NumberFormatException ignored) {}
        throw new IllegalArgumentException(message);
    }

    static BedWarsForecastDisplay load(Path path) {
        Mode mode = Mode.HEATMAP;
        Projection projection = Projection.SUM_Y;
        int threshold = 25, limit = 3;
        Properties options = new Properties();
        if (Files.isRegularFile(path)) try (InputStream in = Files.newInputStream(path)) { options.load(in); }
        catch (IOException | IllegalArgumentException ignored) { options.clear(); }
        try { mode = Mode.parse(options.getProperty("display", "heatmap")); }
        catch (IllegalArgumentException ignored) {}
        try { projection = Projection.parse(options.getProperty("projection", "sum-y")); }
        catch (IllegalArgumentException ignored) {}
        try { threshold = threshold(options.getProperty("threshold", "25")); }
        catch (IllegalArgumentException ignored) {}
        try { limit = limit(options.getProperty("limit", "3")); }
        catch (IllegalArgumentException ignored) {}
        return new BedWarsForecastDisplay(mode, projection, threshold, limit);
    }

    void save(Path path) throws IOException {
        Properties options = new Properties();
        options.setProperty("display", mode.name().toLowerCase(Locale.ROOT));
        options.setProperty("projection", projection.command);
        options.setProperty("threshold", Integer.toString(threshold));
        options.setProperty("limit", Integer.toString(limit));
        Path temporary = Files.createTempFile(path.getParent(), "bedwars-display-", ".tmp");
        try {
            try (OutputStream out = Files.newOutputStream(temporary)) {
                options.store(out, "BedWars forecast display");
            }
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
