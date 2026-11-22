package practice.bridge;

import java.util.Properties;

/** Controls the movement-speed multiplier in the native player FOV calculation. */
public final class MovementFov {
    private static boolean enabled = true;

    private MovementFov() {}

    static void settings(Properties settings) {
        enabled = Boolean.parseBoolean(settings.getProperty("dynamicfov.enabled", "true"));
    }

    public static double multiplier(double vanilla) {
        return enabled ? vanilla : 1.0;
    }
}
