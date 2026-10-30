package lab;

import java.util.*;

/** Explicit native-client integration-test input; inactive in every normal bot. */
public final class PracticeProbe {
    static boolean enabled(){return System.getProperty("lab.practice_block_windows")!=null;}
    public static boolean input(Object minecraft,Object player,long elapsed){
        if(!enabled())return false;
        boolean down=false;
        for(String window:System.getProperty("lab.practice_block_windows").split(",")){
            String[] pair=window.split("-");if(pair.length==2&&elapsed>=Long.parseLong(pair[0])&&elapsed<Long.parseLong(pair[1]))down=true;
        }
        // Ordinary right mouse input only: no server state, attack or velocity writes.
        R.call(R.cls("net.minecraft.client.settings.KeyBinding"),"unPressAllKeys");
        if(R.get(minecraft,"currentScreen")!=null)R.call(minecraft,"displayGuiScreen",(Object)null);
        R.call(minecraft,"setIngameFocus");
        Object key=R.get(R.get(minecraft,"gameSettings"),"keyBindUseItem");
        R.call(R.cls("net.minecraft.client.settings.KeyBinding"),"setKeyBindState",(int)R.call(key,"getKeyCode"),down);
        Map<String,Object> e=new LinkedHashMap<>();e.put("type","practice_input");e.put("time",System.currentTimeMillis());e.put("elapsed_ms",elapsed);
        e.put("use_key",down);e.put("blocking",R.call(player,"isBlocking"));e.put("health",R.call(player,"getHealth"));
        e.put("armor_points",R.call(player,"getTotalArmorValue"));e.put("food",R.call(R.call(player,"getFoodStats"),"getFoodLevel"));
        Referee.append(Client.DIR.resolve("practice.jsonl"),e);return true;
    }
    public static void sound(Object handler,Object packet){
        if(!enabled())return;
        Referee.append(Client.DIR.resolve("practice.jsonl"),Map.of("type","practice_sound","time",System.currentTimeMillis(),
            "sound",R.call(packet,"getSoundName"),"volume",R.call(packet,"getVolume"),"pitch",R.call(packet,"getPitch")));
    }
}
