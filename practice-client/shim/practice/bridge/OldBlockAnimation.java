package practice.bridge;

import java.lang.ref.WeakReference;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.item.ItemStack;
import net.minecraft.potion.Potion;
import net.minecraft.potion.PotionEffect;

/** Restores the 1.7 first-person swing during item use. */
public final class OldBlockAnimation {
    static boolean enabled = true;
    private static WeakReference<EntityPlayerSP> owner = new WeakReference<>(null);
    private static ItemStack clickedItem;
    private static int clickTick, clickDuration;

    private OldBlockAnimation() {}

    static void clicked(Minecraft mc) {
        if (!enabled || BotRuntime.enabled()) return;
        EntityPlayerSP player = mc.field_71439_g;
        if (player == null) return;
        ItemStack item = player.func_70694_bm();
        if (item == null) return;
        // The input event can precede the start of item use in the same client tick.
        if (owner.get() != player || clickedItem != item || player.field_70173_aa - clickTick >= clickDuration / 2) {
            owner = new WeakReference<>(player);
            clickedItem = item;
            clickTick = player.field_70173_aa;
            clickDuration = duration(player);
        }
    }

    public static float swing(float vanilla, float partial) {
        if (!enabled || BotRuntime.enabled()) return vanilla;
        EntityPlayerSP player = Minecraft.func_71410_x().field_71439_g;
        if (player == null) return vanilla;
        float nativeSwing = player.func_70678_g(partial);
        if (owner.get() != player || clickedItem != player.func_70694_bm()) return nativeSwing;
        float elapsed = player.field_70173_aa - clickTick + partial;
        if (elapsed < 0 || elapsed >= clickDuration) return nativeSwing;
        float clickSwing = elapsed / clickDuration;
        // In 1.8 the game may discard the click while using an item. Use the
        // newer swing for rendering alone so it can restart an older swing.
        return nativeSwing > 0 ? Math.min(nativeSwing, clickSwing) : clickSwing;
    }

    private static int duration(EntityPlayerSP player) {
        PotionEffect haste = player.func_70660_b(Potion.field_76422_e);
        if (haste != null) return Math.max(1, 5 - haste.func_76458_c());
        PotionEffect fatigue = player.func_70660_b(Potion.field_76419_f);
        return fatigue == null ? 6 : 8 + 2 * fatigue.func_76458_c();
    }
}
