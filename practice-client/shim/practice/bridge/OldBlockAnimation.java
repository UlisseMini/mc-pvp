package practice.bridge;

import java.lang.ref.WeakReference;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.item.ItemSword;
import net.minecraft.item.ItemStack;

/** Private render swing. It never touches the player's native swing or attack state. */
public final class OldBlockAnimation {
    static boolean enabled = true;
    private static WeakReference<EntityPlayerSP> owner = new WeakReference<>(null);
    private static ItemStack clickedSword;
    private static int clickTick;

    private OldBlockAnimation() {}

    static void clicked(Minecraft mc) {
        if (!enabled || BotRuntime.enabled()) return;
        EntityPlayerSP player = mc.field_71439_g;
        if (player == null || !player.func_70632_aY()) return;
        ItemStack sword = player.func_70694_bm();
        if (!sword(sword)) return;
        if (owner.get() != player || clickedSword != sword || player.field_70173_aa - clickTick >= 3) {
            owner = new WeakReference<>(player);
            clickedSword = sword;
            clickTick = player.field_70173_aa;
        }
    }

    public static float swing(float vanilla, float partial) {
        if (!enabled || BotRuntime.enabled()) return vanilla;
        EntityPlayerSP player = Minecraft.func_71410_x().field_71439_g;
        if (player == null) return vanilla;
        ItemStack sword = player.func_70694_bm();
        if (!sword(sword)) return vanilla;
        float nativeSwing = player.func_70678_g(partial);
        if (nativeSwing > 0) return nativeSwing;
        if (owner.get() != player || clickedSword != sword) return vanilla;
        float elapsed = player.field_70173_aa - clickTick + partial;
        return elapsed >= 0 && elapsed < 6 ? elapsed / 6 : vanilla;
    }

    private static boolean sword(ItemStack stack) {
        return stack != null && stack.func_77973_b() instanceof ItemSword;
    }
}
