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
        s=parse("lab match wtap hold-w kb_horizontal=0.95 hit_ticks=9");
        check(s.a.equals(s.b)&&s.b.getProperty("combat_hit_ticks").equals("9"),"combat profile shared by both players");
        for(String value:new String[]{"a.kb_horizontal=0.9","b.hit_ticks=9","a.kb=vanilla"}){
            boolean rejected=false;try{parse("lab match wtap hold-w "+value);}catch(IllegalArgumentException e){rejected=true;}
            check(rejected,"reject asymmetric combat option "+value);
        }
        for(String value:new String[]{"a.aimnoise=NaN","c.cps=20","cps=20 cps=10","b.cps=31","myping=20","0 0 req extra","a.ping=10 a.botping=20"}){
            boolean rejected=false;try{parse("lab match wtap wtap "+value);}catch(IllegalArgumentException e){rejected=true;}
            check(rejected,"reject "+value);
        }
        BotMatchSettings preset=parse("lab match lock8 lock8-target preset=lock8");
        check(preset.pingA==150&&preset.pingB==0&&preset.request.equals("default"),"match preset uses controller A RTT convention");
        check(preset.a.equals(preset.b)&&preset.a.getProperty("practice_preset").equals("lock8")&&preset.a.getProperty("hurt_window_ticks").equals("8"),"shared match-scoped preset properties");
        BotMatchSettings before=parse("lab match wtap hold-w preset=lock8 20 80 req-preset cps=12 b.aimnoise=2");
        BotMatchSettings after=parse("lab match wtap hold-w 20 80 req-preset cps=12 preset=LOCK8 b.aimnoise=2");
        check(before.pingA==20&&before.pingB==80&&before.request.equals("req-preset")&&before.a.equals(after.a)&&before.b.equals(after.b)&&after.pingA==20&&after.pingB==80,"preset order preserves explicit positional pings, request and options");
        BotMatchSettings named=parse("lab match wtap hold-w a.ping=35 b.ping=65 cps=11 b.cps=9 preset=lock8");
        check(named.pingA==35&&named.pingB==65&&named.a.getProperty("click_cps").equals("11")&&named.b.getProperty("click_cps").equals("9"),"explicit per-side settings beat preset");
        BotMatchSettings one=parse("lab match wtap hold-w preset=lock8 45");
        check(one.pingA==45&&one.pingB==0,"single explicit positional RTT overrides only its side");
        BotMatchSettings reset=parse("lab match wtap hold-w kb=vanilla preset=lock8");
        check(!reset.a.containsKey("practice_preset")&&!reset.b.containsKey("practice_preset")&&reset.a.getProperty("hurt_window_ticks").equals("10"),"explicit vanilla clears both labels and timing defaults");
        named.a.setProperty("click_cps","1");
        check(named.b.getProperty("click_cps").equals("9")&&preset.a.getProperty("click_cps").equals("20")&&parse("lab match wtap hold-w").a.isEmpty(),"sides, separate matches and legacy defaults do not share preset mutation");
        for(String value:new String[]{"a.preset=lock8","b.preset=lock8","preset=lock8 a.preset=lock8","preset=unknown","preset=lock8 preset=LOCK8"}){
            boolean rejected=false;try{parse("lab match wtap hold-w "+value);}catch(IllegalArgumentException expected){rejected=true;}check(rejected,"reject match-scoped/invalid preset "+value);
        }
        System.out.println("PASS bot match old syntax, shared and per-side settings, invalid options");
    }
}
