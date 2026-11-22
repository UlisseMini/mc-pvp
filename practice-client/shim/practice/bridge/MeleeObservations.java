package practice.bridge;

import java.util.Collection;
import java.util.Collections;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Map;
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
import net.minecraft.entity.projectile.EntityFishHook;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Item;
import net.minecraft.item.ItemSword;
import net.minecraft.item.ItemFishingRod;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.potion.Potion;
import net.minecraft.potion.PotionEffect;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.Vec3;

/** Player melee candidates from the visible world. Swing and look direction are
 * imperfect under packet delay, so neither rules out a nearby player's sword. */
final class MeleeObservations {
    static final double RANGE = 5.5;

    static final class Candidate {
        final int id;
        final double awayX, awayZ;
        final Set<Float> damage = new LinkedHashSet<>();
        final Set<Float> critical = new LinkedHashSet<>();
        boolean sword, rod, hiddenSharpness;
        boolean grounded;
        float base, visibleBonus, rodBase;
        String weaponKey;

        Candidate(EntityPlayerSP self, EntityPlayer attacker) {
            this(attacker.func_145782_y(), self.field_70165_t - attacker.field_70165_t,
                self.field_70161_v - attacker.field_70161_v);
            addDamage(this, attacker);
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
        return damage(candidates, only, false, false);
    }

    static Set<Float> damage(List<Candidate> candidates, int only, boolean critical, boolean swordOnly) {
        return damage(candidates, only, critical, swordOnly, Collections.emptyMap());
    }

    static Set<Float> damage(List<Candidate> candidates, int only, boolean critical, boolean swordOnly,
                             Map<String, Set<Float>> learned) {
        Set<Float> damage = new LinkedHashSet<>();
        for (Candidate c : candidates) {
            if ((only != -1 && only != c.id) || swordOnly && !c.sword) continue;
            Set<Float> bonuses = learned.get(c.weaponKey);
            if (bonuses != null) {
                for (float bonus : bonuses) {
                    addStrike(damage, c.base, bonus, critical);
                    if (swordOnly) addStrike(damage, c.rodBase, bonus, critical);
                }
            } else {
                damage.addAll(critical ? c.critical : c.damage);
                if (!swordOnly) continue;
                // A fast slot change can apply the sword's enchantments before
                // the server updates the attack attribute from the held rod.
                addStrike(damage, c.rodBase, c.visibleBonus, critical);
                if (c.hiddenSharpness) addStrike(damage, c.rodBase, c.visibleBonus + 1.25f, critical);
            }
        }
        return damage;
    }

    private static void addStrike(Set<Float> damage, float base, float bonus, boolean critical) {
        damage.add(base * 1.5f + bonus);
        if (!critical) damage.add(base + bonus);
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

    private static void addDamage(Candidate candidate, EntityPlayer opponent) {
        ItemStack weapon = opponent.func_70694_bm();
        candidate.sword = weapon != null && weapon.func_77973_b() instanceof ItemSword;
        candidate.rod = weapon != null && weapon.func_77973_b() instanceof ItemFishingRod;
        NBTTagList enchantments = candidate.sword ? weapon.func_77986_q() : null;
        candidate.hiddenSharpness = candidate.sword && (enchantments == null || enchantments.func_74745_c() == 0);
        candidate.grounded = opponent.field_70122_E;
        if (candidate.sword) candidate.weaponKey = candidate.id + ":" +
            Item.func_150891_b(weapon.func_77973_b()) + ":" + weapon.func_77978_p();
        double[] modifiers = {0, 0, 1};
        for (PotionEffect effect : (Collection<PotionEffect>) opponent.func_70651_bq()) {
            Potion potion = Potion.field_76425_a[effect.func_76456_a()];
            if (potion == null) continue;
            AttributeModifier mod = potion.func_111186_k().get(SharedMonsterAttributes.field_111264_e);
            if (mod != null) add(modifiers, mod, potion.func_111183_a(effect.func_76458_c(), mod));
        }
        candidate.rodBase = baseDamage(modifiers);
        if (weapon != null) {
            String name = SharedMonsterAttributes.field_111264_e.func_111108_a();
            for (AttributeModifier mod : weapon.func_111283_C().get(name)) add(modifiers, mod, mod.func_111164_d());
        }
        float base = baseDamage(modifiers);
        float enchantment = EnchantmentHelper.func_152377_a(weapon, EnumCreatureAttribute.UNDEFINED);
        candidate.base = base;
        candidate.visibleBonus = enchantment;
        candidate.damage.add(base + enchantment);
        candidate.critical.add(base * 1.5f + enchantment);
        if (candidate.hiddenSharpness) {
            // Some servers send a plain rendered sword. Allow hidden Sharpness I
            // as well as its visible base stats.
            candidate.damage.add(base + 1.25f);
            candidate.critical.add(base * 1.5f + 1.25f);
        }
        candidate.damage.addAll(candidate.critical);
    }

    private static float baseDamage(double[] modifiers) {
        return (float) Math.max(0, (1 + modifiers[0]) * (1 + modifiers[1]) * modifiers[2]);
    }

    static boolean nearbyRod(Minecraft mc, EntityPlayerSP self) {
        for (Entity entity : mc.field_71441_e.func_72839_b(self, self.func_174813_aQ().func_72314_b(2.5, 1, 2.5))) {
            if (entity instanceof EntityFishHook && ((EntityFishHook) entity).field_146042_b != self) return true;
        }
        return false;
    }

    private static void add(double[] modifiers, AttributeModifier modifier, double amount) {
        int operation = modifier.func_111169_c();
        if (operation == 2) modifiers[2] *= 1 + amount;
        else modifiers[operation] += amount;
    }

    static boolean arrowThreat(Minecraft mc, EntityPlayerSP self) {
        AxisAlignedBB hitBox = self.func_174813_aQ().func_72314_b(.35, .35, .35);
        for (Entity entity : mc.field_71441_e.func_72839_b(self, hitBox.func_72314_b(5, 5, 5))) {
            if (!(entity instanceof EntityArrow) || Boolean.TRUE.equals(GameObservations.field(entity, "inGround")))
                continue;
            double x = entity.field_70165_t, y = entity.field_70163_u, z = entity.field_70161_v;
            double vx = entity.field_70159_w, vy = entity.field_70181_x, vz = entity.field_70179_y;
            if (vx * vx + vy * vy + vz * vz < .01) continue;
            Vec3 start = new Vec3(x - vx, y - vy, z - vz);
            Vec3 end = new Vec3(x + 2 * vx, y + 2 * vy, z + 2 * vz);
            if (hitBox.func_72318_a(start) || hitBox.func_72327_a(start, end) != null) return true;
        }
        return false;
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
