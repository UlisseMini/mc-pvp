package lab;

import java.util.Locale;
import java.util.Properties;

/** Match-scoped defaults. Every call returns independent properties. */
public final class PracticePresets {
    public static final String LABEL="practice_preset";
    public static final String LOCK8="lock8";
    private PracticePresets() {}

    public static String normalize(String name){
        if(name!=null&&LOCK8.equals(name.toLowerCase(Locale.ROOT)))return LOCK8;
        throw new IllegalArgumentException("Unknown practice preset '"+name+"'. Available: lock8");
    }
    public static Properties defaults(String name){
        String preset=normalize(name);Properties p=new Properties();
        p.setProperty(LABEL,preset);
        p.setProperty("combat_kb_horizontal","1.1");
        p.setProperty("combat_kb_vertical","1.0");
        p.setProperty("combat_hit_ticks","8");
        p.setProperty("hurt_window_ticks","8");
        p.setProperty("click_cps","20");
        p.setProperty("attack_reach","3.0");
        p.setProperty("reaction_ms","0");
        p.setProperty("aim_delay_ms","0");
        p.setProperty("aim_noise_deg","0");
        p.setProperty("aim_turn_rate_dps","0");
        return p;
    }
    /** Controller/bot A RTT; human/opponent/bot B uses defaultOpponentRtt. */
    public static double defaultControllerRtt(String name){normalize(name);return 150;}
    public static double defaultOpponentRtt(String name){normalize(name);return 0;}
    public static boolean combatMatches(String name,Properties values){
        Properties expected=defaults(name);
        try{
            for(String key:new String[]{"combat_kb_horizontal","combat_kb_vertical","combat_hit_ticks"}){
                String actual=values.getProperty(key);
                if(actual==null||Double.parseDouble(actual)!=Double.parseDouble(expected.getProperty(key)))return false;
            }
            return true;
        }catch(NumberFormatException invalid){return false;}
    }
}
