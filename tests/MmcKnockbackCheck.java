package lab;

import java.util.Properties;

public final class MmcKnockbackCheck {
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        DuelSettings mmc = DuelSettings.parse(new String[]{"sumo", "preset=mmc"}, 0, "boxing", 20, 120, new Properties());
        CombatProfile profile = CombatProfile.fromOverrides(mmc.overrides, CombatProfile.VANILLA);
        check(profile.mmc() && profile.sprintVertical() == 0 && profile.freshTicks() == 9, "MMC combat config");
        check(DuelSettings.hurtWindowTicks(mmc.overrides) == 9, "MMC bot timing follows the combat profile");
        check(mmc.botPing == 20 && mmc.humanPing == 120 && !mmc.overrides.containsKey("click_cps"), "KB-only preset");
        DuelSettings reset = DuelSettings.parse(new String[]{"kb=vanilla"}, 0, "sumo", 20, 120, mmc.overrides);
        check(CombatProfile.fromOverrides(reset.overrides, profile).vanilla(), "Explicit reset clears custom impulses");
        DuelSettings hypixel = DuelSettings.parse(new String[]{"preset=hypixel"}, 0, "sumo", 20, 120, mmc.overrides);
        check(!CombatProfile.fromOverrides(hypixel.overrides, CombatProfile.VANILLA).mmc(), "Switching presets clears MMC");
        check(MmcKnockback.landsBeforeDelivery(.45, -.2, 120), "Predicts a descent through the platform");
        check(!MmcKnockback.landsBeforeDelivery(.45, -.2, 20), "Short ping has no compensation horizon");
        check(!MmcKnockback.landsBeforeDelivery(.45, .25, 120), "Jump still ascending in delivery window");
        check(SumoArena.supports(0, 4096.0, 0) && !SumoArena.supports(0, 4101.4, 0), "Sumo floor support");
        System.out.println("PASS MMC preset selection and landing estimate");
    }
}
