import lab.BotMatchSettings;

public final class BotMatchSettingsCheck {
    static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
    static BotMatchSettings parse(String text){return BotMatchSettings.parse(text.split(" "),4,"default");}
    public static void main(String[] args){
        BotMatchSettings old=parse("lab match wtap hold-w 20 100 req-123");
        check(old.pingA==20&&old.pingB==100&&old.request.equals("req-123")&&old.a.isEmpty()&&old.b.isEmpty(),"legacy scheduler syntax");
        BotMatchSettings s=parse("lab match wtap wtap 20 80 cps=20 b.aimdelay=150 b.aimnoise=2 b.tapdelay=200 a.ping=10");
        check(s.a.getProperty("click_cps").equals("20")&&s.b.getProperty("click_cps").equals("20"),"shared CPS");
        check(s.pingA==10&&s.pingB==80&&!s.a.containsKey("aim_delay_ms")&&s.b.getProperty("aim_delay_ms").equals("150.0"),"side-isolated overrides and named RTT");
        check(s.b.getProperty("reaction_ms").equals("200.0"),"tap setting");
        s=parse("lab match wtap wtap cps=20 b.cps=10");
        check(s.a.getProperty("click_cps").equals("20")&&s.b.getProperty("click_cps").equals("10"),"specific settings override shared settings");
        for(String value:new String[]{"a.aimnoise=NaN","c.cps=20","cps=20 cps=10","b.cps=31","myping=20","0 0 req extra","a.ping=10 a.botping=20"}){
            boolean rejected=false;try{parse("lab match wtap wtap "+value);}catch(IllegalArgumentException e){rejected=true;}
            check(rejected,"reject "+value);
        }
        System.out.println("PASS bot match old syntax, shared and per-side settings, invalid options");
    }
}
