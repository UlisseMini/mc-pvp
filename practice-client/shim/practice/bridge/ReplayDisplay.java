package practice.bridge;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Properties;

/** Replay-only visibility choices saved independently for each camera. */
final class ReplayDisplay {
    enum Layer {
        OWN_VELOCITY("Your velocity"),
        CHANGE_LOG("Velocity and packet log"),
        KB_COUNT("KB reduction counter"),
        RECOIL_FLASH("KB cut crosshair flash"),
        REACH_FIELDS("Reach fields"),
        OWN_RANGE("Your range indicator"),
        TARGET_RANGE("Opponent range indicator"),
        DETAILS("Replay diagnostics"),
        KEYSTROKES("Keystrokes and CPS");

        final String label;
        Layer(String label) { this.label=label; }
    }

    private final Path file;
    private final Properties settings=new Properties();

    ReplayDisplay(Path directory) {
        file=directory.resolve("replay-display.properties");
        if(!Files.isRegularFile(file))return;
        try(InputStream in=Files.newInputStream(file)){settings.load(in);}
        catch(IOException|IllegalArgumentException failure){
            settings.clear();
            System.out.println("[PracticeReplay] Could not load display settings: "+failure.getMessage());
        }
    }

    boolean shows(String camera,Layer layer) {
        String value=settings.getProperty(key(camera,layer));
        if("true".equals(value))return true;
        if("false".equals(value))return false;
        return layer!=Layer.DETAILS;
    }

    void toggle(String camera,Layer layer)throws IOException {
        settings.setProperty(key(camera,layer),Boolean.toString(!shows(camera,layer)));
        Path temporary=Files.createTempFile(file.getParent(),"replay-display-",".tmp");
        try{
            try(OutputStream out=Files.newOutputStream(temporary)){settings.store(out,"Practice replay display settings");}
            Files.move(temporary,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        }finally{Files.deleteIfExists(temporary);}
    }

    private static String key(String camera,Layer layer) {
        return camera+"."+layer.name().toLowerCase(Locale.ROOT);
    }
}
