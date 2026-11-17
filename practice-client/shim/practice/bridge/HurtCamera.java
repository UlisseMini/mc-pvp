package practice.bridge;

import java.util.Properties;

/** Optional damage-camera tilt for the native 1.8.9 renderer. */
public final class HurtCamera {
    private static boolean enabled = true;

    private HurtCamera() {}

    static void settings(Properties settings) {
        enabled = Boolean.parseBoolean(settings.getProperty("hurtcam.enabled", "true"));
    }

    public static float damageTime(float vanilla) {
        // The renderer checks for a negative timer after applying the death tilt.
        return enabled ? vanilla : -1f;
    }
}
