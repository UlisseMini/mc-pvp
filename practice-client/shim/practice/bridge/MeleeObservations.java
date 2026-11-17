package practice.bridge;

import java.util.Collection;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EnumCreatureAttribute;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.ai.attributes.AttributeModifier;
import net.minecraft.entity.monster.IMob;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.projectile.EntityArrow;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemSword;
import net.minecraft.potion.Potion;
import net.minecraft.potion.PotionEffect;

/** Player melee candidates from the visible world. Swing and look direction are
 * imperfect under packet delay, so neither rules out a nearby player's sword. */
final class MeleeObservations {
    static final double RANGE = 5.5;

    static final class Candidate {
        final int id;
        final double awayX, awayZ;
        final Set<Float> damage = new LinkedHashSet<>();

        Candidate(EntityPlayerSP self, EntityPlayer attacker) {
            this(attacker.func_145782_y(), self.field_70165_t - attacker.field_70165_t,
                self.field_70161_v - attacker.field_70161_v);
            addDamage(damage, attacker);
        }

        Candidate(int id, double awayX, double awayZ) {
            this.id = id;
            this.awayX = awayX;
            this.awayZ = awayZ;
        }
    }

    static List<Candidate> nearby(Minecraft mc, EntityPlayerSP self) {
        List<Candidate> candidates = new ArrayList<>();
        for (EntityPlayer opponent : mc.field_71441_e.field_73010_i) {
            if (nearby(opponent, self)) candidates.add(new Candidate(self, opponent));
        }
        return candidates;
    }

    static boolean hasNearbyPlayer(Minecraft mc, EntityPlayerSP self) {
        for (EntityPlayer opponent : mc.field_71441_e.field_73010_i) if (nearby(opponent, self)) return true;
        return false;
    }

    private static boolean nearby(EntityPlayer opponent, EntityPlayerSP self) {
        return opponent != self && !opponent.func_175149_v() && opponent.func_70089_S() &&
            self.func_70068_e(opponent) <= RANGE * RANGE;
    }

    static Set<Float> damage(List<Candidate> candidates, int only) {
        Set<Float> damage = new LinkedHashSet<>();
        for (Candidate c : candidates) if (only == -1 || only == c.id) damage.addAll(c.damage);
        return damage;
    }

    static int fromDirection(List<Candidate> candidates, double x, double z) {
        double amount = Math.hypot(x, z);
        if (amount < .25) return -1;
        int aligned = -1;
        Set<Integer> possible = new LinkedHashSet<>();
        for (Candidate c : candidates) {
            double distance = Math.hypot(c.awayX, c.awayZ);
            if (distance < .3) return -1;
            double cosine = (c.awayX * x + c.awayZ * z) / (distance * amount);
            if (cosine > 0) possible.add(c.id);
            if (cosine >= .8 && distance <= 4.5) aligned = c.id;
        }
        // Reject side-by-side layouts and a candidate whose recent position
        // does not consistently agree with the direction.
        if (aligned == -1 || possible.size() != 1) return -1;
        for (Candidate c : candidates) if (c.id == aligned &&
                (c.awayX * x + c.awayZ * z) < .7 * Math.hypot(c.awayX, c.awayZ) * amount) return -1;
        return aligned;
    }

    private static void addDamage(Set<Float> damage, EntityPlayer opponent) {
        ItemStack weapon = opponent.func_70694_bm();
        double[] modifiers = {0, 0, 1};
        String name = SharedMonsterAttributes.field_111264_e.func_111108_a();
        if (weapon != null) {
            for (AttributeModifier mod : weapon.func_111283_C().get(name)) add(modifiers, mod, mod.func_111164_d());
        }
        for (PotionEffect effect : (Collection<PotionEffect>) opponent.func_70651_bq()) {
            Potion potion = Potion.field_76425_a[effect.func_76456_a()];
            if (potion == null) continue;
            AttributeModifier mod = potion.func_111186_k().get(SharedMonsterAttributes.field_111264_e);
            if (mod != null) add(modifiers, mod, potion.func_111183_a(effect.func_76458_c(), mod));
        }
        float base = (float) Math.max(0, (1 + modifiers[0]) * (1 + modifiers[1]) * modifiers[2]);
        float enchantment = EnchantmentHelper.func_152377_a(weapon, EnumCreatureAttribute.UNDEFINED);
        damage.add(base + enchantment);
        damage.add(base * 1.5f + enchantment);
        if (weapon != null && weapon.func_77973_b() instanceof ItemSword && weapon.func_77986_q() == null) {
            // Some servers send a plain rendered sword. Allow hidden Sharpness I
            // as well as its visible base stats.
            damage.add(base + 1.25f);
            damage.add(base * 1.5f + 1.25f);
        }
    }

    private static void add(double[] modifiers, AttributeModifier modifier, double amount) {
        int operation = modifier.func_111169_c();
        if (operation == 2) modifiers[2] *= 1 + amount;
        else modifiers[operation] += amount;
    }

    static boolean mixedSources(Minecraft mc, EntityPlayerSP self) {
        if (self.field_70143_R > 3 || self.func_70027_ad() || self.func_70090_H() && self.func_70086_ai() <= 0)
            return true;
        for (Entity entity : mc.field_71441_e.func_72839_b(self, self.func_174813_aQ().func_72314_b(3.5, 2, 3.5))) {
            if (entity instanceof IMob || entity instanceof EntityArrow && self.func_70068_e(entity) < 4) return true;
        }
        return false;
    }

    static int protection(EntityPlayerSP self) {
        int score = 0;
        for (ItemStack armor : self.field_71071_by.field_70460_b) {
            int level = EnchantmentHelper.func_77506_a(0, armor);
            if (level > 0) score += (int) (((6 + level * level) / 3f) * .75f);
        }
        return score;
    }

    static int resistance(EntityPlayerSP self) {
        PotionEffect effect = self.func_70660_b(Potion.field_76429_m);
        return effect == null ? -1 : effect.func_76458_c();
    }
}
