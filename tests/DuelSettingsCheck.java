import lab.DuelSettings;
import java.util.Properties;

public final class DuelSettingsCheck {
    static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
    static DuelSettings parse(String command,Properties inherited){return DuelSettings.parse(command.split(" "),2,"boxing",0,80,inherited);}
    public static void main(String[] args){
        Properties empty=new Properties();
        DuelSettings defaults=parse("/duel wtap",empty);
        check(defaults.humanPing==80&&defaults.botPing==0&&defaults.overrides.isEmpty(),"saved player ping and policy defaults");
        DuelSettings named=parse("/duel wtap rod_diamond reach=5 cps=20 ping=100",empty);
        check(named.mode.equals("rod_diamond")&&named.botPing==100&&named.humanPing==80,"named mode and ping");
        check(DuelSettings.reach(named.overrides)==5&&named.overrides.getProperty("click_cps").equals("20"),"named reach/CPS");
        DuelSettings positional=parse("/duel wtap diamond 20 120 cps=15 reach=2.5",empty);
        check(positional.botPing==20&&positional.humanPing==120,"legacy positional pings");
        DuelSettings next=DuelSettings.parse(new String[]{"/rematch","cps=30"},1,named.mode,named.botPing,120,named.overrides);
        check(next.botPing==100&&next.humanPing==120&&DuelSettings.reach(next.overrides)==5&&next.overrides.getProperty("click_cps").equals("30"),"rematch overrides preserve other options");
        check(named.overrides.getProperty("click_cps").equals("20")&&empty.isEmpty(),"parser never mutates inherited/shared configuration");
        DuelSettings aim=parse("/duel wtap aimdelay=150 aimnoise=2 aimrate=360 tapdelay=180",empty);
        check(aim.overrides.getProperty("aim_delay_ms").equals("150.0")&&aim.overrides.getProperty("aim_noise_deg").equals("2.0")&&aim.overrides.getProperty("aim_turn_rate_dps").equals("360.0")&&aim.overrides.getProperty("reaction_ms").equals("180.0"),"independent aim and tap settings");
        check(DuelSettings.parse(new String[]{"/rematch"},1,"boxing",0,0,aim.overrides).overrides.equals(aim.overrides),"aim settings retained by rematch");
        DuelSettings combat=parse("/duel wtap kb_horizontal=0.95 kb_vertical=0.9 hit_ticks=9",empty);
        check(combat.overrides.getProperty("combat_hit_ticks").equals("9"),"explicit combat override");
        DuelSettings reset=DuelSettings.parse(new String[]{"/rematch","kb=vanilla"},1,"boxing",0,0,combat.overrides);
        check(reset.overrides.getProperty("combat_kb_horizontal").equals("1.0")&&reset.overrides.getProperty("combat_kb_vertical").equals("1.0")&&reset.overrides.getProperty("combat_hit_ticks").equals("10"),"rematch vanilla resets all combat settings");
        check(combat.overrides.getProperty("combat_hit_ticks").equals("9"),"combat rematch cannot mutate previous match");
        for(String option:new String[]{"kb=hypixel","kb=vanilla kb_vertical=1","kb_vertical=NaN","kb_horizontal=2.1","hit_ticks=0","hit_ticks=10.5"}){
            boolean rejected=false;try{parse("/duel wtap "+option,empty);}catch(IllegalArgumentException e){rejected=true;}
            check(rejected,"reject combat option "+option);
        }
        for(String option:new String[]{"reach=NaN","reach=Infinity","reach=0","reach=5.1","cps=0","cps=31","cps=10.5","ping=-1","myping=501","ping=NaN","oops=1","cps=","reach=3=4","ping=10 botping=20","20 ping=30","reach=3 reach=4","0 0 0","aimdelay=501","aimnoise=-1","aimnoise=NaN","aimrate=29","aimrate=1441","tapdelay=1001","tapdelay=NaN","aimdelay=10 aimdelay=20"}){
            boolean rejected=false;try{parse("/duel wtap "+option,empty);}catch(IllegalArgumentException e){rejected=true;}
            check(rejected,"reject "+option);
        }
        check(parse("/duel wtap reach=1 cps=1 ping=500 myping=0",empty).humanPing==0,"valid bounds");
        DuelSettings preset=parse("/duel wtap preset=lock8",empty);
        check(preset.mode.equals("boxing")&&preset.botPing==150&&preset.humanPing==0,"preset pings without changing selected mode");
        check(preset.overrides.getProperty("practice_preset").equals("lock8")&&preset.overrides.getProperty("combat_hit_ticks").equals("8"),"preset persisted with combat defaults");
        DuelSettings before=parse("/duel wtap diamond cps=12 aimnoise=2 40 50 preset=lock8",empty);
        DuelSettings after=parse("/duel wtap diamond preset=LOCK8 40 50 aimnoise=2 cps=12",empty);
        check(before.mode.equals("diamond")&&before.botPing==40&&before.humanPing==50&&before.overrides.equals(after.overrides)&&after.botPing==40&&after.humanPing==50,"preset token order cannot override explicit positional pings/settings");
        DuelSettings namedPreset=parse("/duel wtap ping=25 myping=70 reach=4 preset=lock8",empty);
        check(namedPreset.botPing==25&&namedPreset.humanPing==70&&DuelSettings.reach(namedPreset.overrides)==4,"explicit named options beat preset");
        DuelSettings keep=DuelSettings.parse(new String[]{"/rematch","cps=13"},1,namedPreset.mode,namedPreset.botPing,namedPreset.humanPing,namedPreset.overrides);
        check(keep.botPing==25&&keep.humanPing==70&&DuelSettings.reach(keep.overrides)==4&&keep.overrides.getProperty("click_cps").equals("13")&&keep.overrides.getProperty("practice_preset").equals("lock8"),"rematch preserves preset-derived settings without reapplying defaults");
        DuelSettings fresh=DuelSettings.parse(new String[]{"/rematch","cps=14","preset=lock8"},1,"sumo",keep.botPing,keep.humanPing,keep.overrides);
        check(fresh.mode.equals("sumo")&&fresh.botPing==150&&fresh.humanPing==0&&DuelSettings.reach(fresh.overrides)==3&&fresh.overrides.getProperty("click_cps").equals("14"),"new preset replaces inherited defaults and keeps mode; explicit options still win");
        check(DuelSettings.reach(namedPreset.overrides)==4&&keep.overrides.getProperty("click_cps").equals("13")&&empty.isEmpty(),"preset/rematch cannot mutate previous match or player defaults");
        DuelSettings vanilla=DuelSettings.parse(new String[]{"/rematch","kb=vanilla"},1,preset.mode,preset.botPing,preset.humanPing,preset.overrides);
        check(!vanilla.overrides.containsKey("practice_preset")&&vanilla.overrides.getProperty("combat_hit_ticks").equals("10")&&vanilla.overrides.getProperty("hurt_window_ticks").equals("10"),"combat reset clears label and synchronizes timing");
        DuelSettings changed=parse("/duel wtap hit_ticks=9 preset=lock8",empty);
        check(!changed.overrides.containsKey("practice_preset")&&changed.overrides.getProperty("hurt_window_ticks").equals("9"),"explicit hurt interval wins before preset token and updates controller timing");
        check(parse("/duel wtap preset=lock8 kb_horizontal=1.10",empty).overrides.containsKey("practice_preset"),"equivalent combat keeps label");
        for(String option:new String[]{"preset=unknown","preset=","preset=lock8 preset=lock8","PRESET=lock8 preset=LOCK8","preset=lock8 kb=vanilla hit_ticks=9"}){
            boolean rejected=false;try{parse("/duel wtap "+option,empty);}catch(IllegalArgumentException expected){rejected=true;}check(rejected,"reject invalid preset option "+option);
        }
        System.out.println("PASS duel defaults, named/positional options, rematch isolation and invalid input rejection");
    }
}
