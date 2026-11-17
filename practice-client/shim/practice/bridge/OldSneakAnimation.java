package practice.bridge;

import java.util.Properties;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.multiplayer.WorldClient;

/** Smooths the local camera using the 1.7 sneak offset and release decay. */
public final class OldSneakAnimation {
    private static final Height HEIGHT = new Height();
    private static boolean enabled = true;
    private static EntityPlayerSP owner;
    private static WorldClient world;

    private OldSneakAnimation() {}

    static void settings(Properties settings) {
        boolean next = Boolean.parseBoolean(settings.getProperty("oldsneak.enabled", "true"));
        if (enabled != next) reset();
        enabled = next;
    }

    static void tick(Minecraft mc, boolean offline) {
        EntityPlayerSP player = mc.field_71439_g;
        if (!enabled || offline || BotRuntime.enabled() || player == null || mc.field_71441_e == null
                || player != mc.func_175606_aa() || player.func_70608_bn() || player.func_175149_v()) {
            reset();
            return;
        }
        float vanilla = player.func_70047_e();
        if (player != owner || mc.field_71441_e != world) {
            owner = player;
            world = mc.field_71441_e;
            HEIGHT.reset(vanilla);
        } else {
            HEIGHT.tick(vanilla);
        }
    }

    private static void reset() {
        owner = null;
        world = null;
    }

    // Called only from the camera renderer after its original getEyeHeight.
    public static float eyeHeight(Object entity, float vanilla, float partialTick) {
        return owner != null && entity == owner ? HEIGHT.render(partialTick) : vanilla;
    }

    static final class Height {
        private float previous, current;

        void reset(float height) {
            previous = current = height;
        }

        void tick(float vanilla) {
            previous = current;
            // In 1.7 the leftover camera drop decayed to 40% on each
            // movement tick after releasing sneak. Rendering interpolated ticks.
            current = vanilla > current ? vanilla - (vanilla - current) * 0.4f : vanilla;
        }

        float render(float partialTick) {
            return previous + (current - previous) * partialTick;
        }
    }
}
