package lab;

import java.util.Locale;
import java.util.Properties;

/** Match-scoped defaults. Every call returns independent properties. */
public final class PracticePresets {
    public static final String LABEL="practice_preset";
    public static final String LOCK8="lock8";
    /** Provisional passive Housing fit; not an exact Hypixel implementation. */
    public static final String HYPIXEL="hypixel";
    public static final String MMC="mmc";
    public static final String MMC_LEGACY="mmc-v2";
    public static final String RECORDED_MODEL="mmc_recorded_sumo_v3";
    public static final String KB_MODEL="combat_kb_model";
    private PracticePresets() {}

    public static String normalize(String name){
        if(name!=null){String n=name.toLowerCase(Locale.ROOT);if(LOCK8.equals(n)||HYPIXEL.equals(n)||MMC.equals(n)||MMC_LEGACY.equals(n))return n;}
        throw new IllegalArgumentException("Unknown practice preset '"+name+"'. Available: lock8, hypixel, mmc, mmc-v2");
    }
    public static Properties defaults(String name){
        String preset=normalize(name);Properties p=new Properties();
        p.setProperty(LABEL,preset);
        String hitTicks=Integer.toString(preset.equals(LOCK8)?8:preset.equals(MMC)?10:DuelSettings.DEFAULT_HIT_TICKS);
        p.setProperty("combat_hit_ticks",hitTicks);
        p.setProperty("hurt_window_ticks",hitTicks);
        if(MMC.equals(preset)||MMC_LEGACY.equals(preset)){
            p.setProperty(KB_MODEL,MMC.equals(preset)?RECORDED_MODEL:MMC);
            p.setProperty("combat_kb_horizontal","1.0");
            p.setProperty("combat_kb_vertical","1.0");
            p.setProperty("combat_sprint_vertical","0.0");
            return p;
        }
        p.setProperty("combat_kb_horizontal",preset.equals(LOCK8)?"1.1":"1.0");
        p.setProperty("combat_kb_vertical","1.0");
        p.setProperty("combat_sprint_vertical",preset.equals(HYPIXEL)?"0.07":"0.1");
        p.setProperty("click_cps","20");
        p.setProperty("attack_reach","3.0");
        p.setProperty("reaction_ms","0");
        p.setProperty("aim_delay_ms","0");
        p.setProperty("aim_noise_deg","0");
        p.setProperty("aim_turn_rate_dps","0");
        return p;
    }
    /** Controller/bot A RTT; human/opponent/bot B uses defaultOpponentRtt. */
    public static boolean overridesDuelRtt(String name){return normalize(name).equals(LOCK8);}
    public static double defaultControllerRtt(String name){return normalize(name).equals(LOCK8)?150:0;}
    public static double defaultOpponentRtt(String name){normalize(name);return 0;}
    public static boolean combatMatches(String name,Properties values){
        Properties expected=defaults(name);
        if(!expected.getProperty(KB_MODEL,"vanilla").equals(values.getProperty(KB_MODEL,"vanilla")))return false;
        try{
            for(String key:new String[]{"combat_kb_horizontal","combat_kb_vertical","combat_hit_ticks","combat_sprint_vertical"}){
                String actual=values.getProperty(key,key.equals("combat_sprint_vertical")?"0.1":null);
                if(actual==null||Double.parseDouble(actual)!=Double.parseDouble(expected.getProperty(key)))return false;
            }
            return true;
        }catch(NumberFormatException invalid){return false;}
    }
}
