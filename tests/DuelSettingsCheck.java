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
        for(String option:new String[]{"reach=NaN","reach=Infinity","reach=0","reach=5.1","cps=0","cps=31","cps=10.5","ping=-1","myping=501","ping=NaN","oops=1","cps=","reach=3=4","ping=10 botping=20","20 ping=30","reach=3 reach=4","0 0 0","aimdelay=501","aimnoise=-1","aimnoise=NaN","aimrate=29","aimrate=1441","tapdelay=1001","tapdelay=NaN","aimdelay=10 aimdelay=20"}){
            boolean rejected=false;try{parse("/duel wtap "+option,empty);}catch(IllegalArgumentException e){rejected=true;}
            check(rejected,"reject "+option);
        }
        check(parse("/duel wtap reach=1 cps=1 ping=500 myping=0",empty).humanPing==0,"valid bounds");
        System.out.println("PASS duel defaults, named/positional options, rematch isolation and invalid input rejection");
    }
}
