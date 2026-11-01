package practice.api;

import java.nio.file.Path;
import java.util.Properties;

/** Stable boundary. Modules receive snapshots, never live Minecraft objects. */
public interface PracticeExtension extends AutoCloseable {
    void configure(Properties settings, Path dataDirectory) throws Exception;
    Frame tick(Snapshot snapshot) throws Exception;
    default void services(PracticeServices services) throws Exception {}
    default void event(PracticeEvent event) throws Exception {}
    /** Return null to retain the tick HUD, or return a per-render-frame HUD. */
    default Frame render(float partialTicks,int width,int height) throws Exception {return null;}
    default boolean command(String[] arguments) throws Exception {return false;}
    default void chat(String text, int type) throws Exception {}
    default void close() throws Exception {}
}
