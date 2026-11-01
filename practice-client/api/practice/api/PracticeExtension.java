package practice.api;

import java.nio.file.Path;
import java.util.Properties;

/** Stable boundary. Modules receive snapshots, never live Minecraft objects. */
public interface PracticeExtension extends AutoCloseable {
    void configure(Properties settings, Path dataDirectory) throws Exception;
    Frame tick(Snapshot snapshot) throws Exception;
    default void chat(String text, int type) throws Exception {}
    default void close() throws Exception {}
}
