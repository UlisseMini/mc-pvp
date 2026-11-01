package practice.bridge;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.ChatComponentText;

/** Display-only alias supplied in vanilla entity metadata. Never changes getName/profile/UUID. */
public final class PlayerLabels {
    private PlayerLabels() {}

    public static Object displayName(Object original,Object entity) {
        if(entity instanceof EntityPlayer) {
            EntityPlayer player=(EntityPlayer)entity;
            String label=player.func_95999_t();
            if(label!=null&&!label.isEmpty())return new ChatComponentText(label);
        }
        return original;
    }
}
