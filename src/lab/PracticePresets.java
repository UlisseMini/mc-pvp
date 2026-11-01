package lab;

import java.util.Locale;
import java.util.Properties;

/** Match-scoped defaults. Every call returns independent properties. */
public final class PracticePresets {
    public static final String LABEL="practice_preset";
    public static final String LOCK8="lock8";
    /** Provisional passive Housing fit; not an exact Hypixel implementation. */
    public static final String HYPIXEL="hypixel";
    private PracticePresets() {}

    public static String normalize(String name){
        if(name!=null){String n=name.toLowerCase(Locale.ROOT);if(LOCK8.equals(n)||HYPIXEL.equals(n))return n;}
        throw new IllegalArgumentException("Unknown practice preset '"+name+"'. Available: lock8, hypixel (provisional Housing fit)");
    }
    public static Properties defaults(String name){
        String preset=normalize(name);Properties p=new Properties();
        p.setProperty(LABEL,preset);
        p.setProperty("combat_kb_horizontal",preset.equals(LOCK8)?"1.1":"1.0");
        p.setProperty("combat_kb_vertical","1.0");
        p.setProperty("combat_sprint_vertical",preset.equals(HYPIXEL)?"0.07":"0.1");
        p.setProperty("combat_hit_ticks",preset.equals(LOCK8)?"8":"9");
        p.setProperty("hurt_window_ticks",preset.equals(LOCK8)?"8":"9");
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
        try{
            for(String key:new String[]{"combat_kb_horizontal","combat_kb_vertical","combat_hit_ticks","combat_sprint_vertical"}){
                String actual=values.getProperty(key,key.equals("combat_sprint_vertical")?"0.1":null);
                if(actual==null||Double.parseDouble(actual)!=Double.parseDouble(expected.getProperty(key)))return false;
            }
            return true;
        }catch(NumberFormatException invalid){return false;}
    }
}
