import lab.PracticePresets;
import java.util.Properties;

public final class PracticePresetsCheck {
    static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
    public static void main(String[] args){
        Properties first=PracticePresets.defaults("lock8"),second=PracticePresets.defaults("LOCK8");
        check(first.equals(second)&&PracticePresets.combatMatches("lock8",first),"case-insensitive canonical preset");
        check(first.getProperty("practice_preset").equals("lock8"),"canonical persisted name");
        check(first.getProperty("combat_kb_horizontal").equals("1.1")&&first.getProperty("combat_kb_vertical").equals("1.0")&&first.getProperty("combat_hit_ticks").equals("8"),"combat profile");
        check(first.getProperty("hurt_window_ticks").equals("8")&&first.getProperty("click_cps").equals("20")&&first.getProperty("attack_reach").equals("3.0"),"timing, click ceiling and reach");
        for(String key:new String[]{"reaction_ms","aim_delay_ms","aim_noise_deg","aim_turn_rate_dps"})check(first.getProperty(key).equals("0"),"perfect local aim and reaction: "+key);
        check(!first.containsKey("kind")&&!first.containsKey("entry")&&!first.containsKey("click_mode"),"preset does not select bot behavior");
        check(PracticePresets.defaultControllerRtt("lock8")==150&&PracticePresets.defaultOpponentRtt("lock8")==0,"declared asymmetric RTT defaults");
        first.setProperty("combat_kb_horizontal","0.9");first.setProperty("click_cps","1");
        check(!PracticePresets.combatMatches("lock8",first)&&second.equals(PracticePresets.defaults("lock8")),"fresh independent defaults on every call");
        second.setProperty("combat_kb_horizontal","1.10");check(PracticePresets.combatMatches("lock8",second),"numeric combat comparison");
        second.setProperty("combat_hit_ticks","NaN");check(!PracticePresets.combatMatches("lock8",second),"invalid combat cannot retain label");
        for(String name:new String[]{"","vanilla","unknown"}){boolean rejected=false;try{PracticePresets.defaults(name);}catch(IllegalArgumentException expected){rejected=true;}check(rejected,"unknown preset: "+name);}
        System.out.println("PASS lock8 defaults, canonical names, isolation and unknown rejection");
    }
}
