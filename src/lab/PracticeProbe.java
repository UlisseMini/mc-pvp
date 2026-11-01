package lab;

import java.util.*;

/** Explicit native-client integration-test input; inactive in every normal bot. */
public final class PracticeProbe {
    static boolean rodSelected;
    static long lastRodClick,lastAttack;
    static boolean window(String name,long elapsed){
        for(String window:System.getProperty(name,"").split(",")){
            String[] pair=window.split("-");if(pair.length==2&&elapsed>=Long.parseLong(pair[0])&&elapsed<Long.parseLong(pair[1]))return true;
        }
        return false;
    }
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
        Object settings=R.get(minecraft,"gameSettings");
        boolean rod=window("lab.practice_rod_windows",elapsed),attack=window("lab.practice_attack_windows",elapsed);
        if(rod!=rodSelected){
            Object key=((Object[])R.get(settings,"keyBindsHotbar"))[rod?1:0];
            R.call(R.cls("net.minecraft.client.settings.KeyBinding"),"onTick",(int)R.call(key,"getKeyCode"));rodSelected=rod;
        }
        if(rod||attack){
            // Explicit integration probe only: aim from the ordinary client POV.
            Object world=R.get(minecraft,"theWorld"),target=null;double nearest=10;
            for(Object other:(List<?>)R.get(world,"playerEntities"))if(R.name(other).startsWith("LabSlot")){
                double distance=Math.hypot(R.num(other,"posX")-R.num(player,"posX"),R.num(other,"posZ")-R.num(player,"posZ"));
                if(distance<nearest){target=other;nearest=distance;}
            }
            if(target!=null){
                double dx=R.num(target,"posX")-R.num(player,"posX"),dz=R.num(target,"posZ")-R.num(player,"posZ");
                double dy=R.num(target,"posY")+1.45-R.num(player,"posY")-((Number)R.call(player,"getEyeHeight")).doubleValue();
                R.set(player,"rotationYaw",(float)Math.toDegrees(Math.atan2(-dx,dz)));
                R.set(player,"rotationPitch",(float)-Math.toDegrees(Math.atan2(dy,Math.hypot(dx,dz))));
                R.call(R.get(minecraft,"entityRenderer"),"getMouseOver",1.0f);
            }
        }
        if(rod&&elapsed-lastRodClick>=750){
            Object key=R.get(settings,"keyBindUseItem");R.call(R.cls("net.minecraft.client.settings.KeyBinding"),"onTick",(int)R.call(key,"getKeyCode"));lastRodClick=elapsed;
        }
        if(attack){
            for(String name:List.of("keyBindForward","keyBindJump")){
                Object key=R.get(settings,name);R.call(R.cls("net.minecraft.client.settings.KeyBinding"),"setKeyBindState",(int)R.call(key,"getKeyCode"),true);
            }
            if(elapsed-lastAttack>=100){Object key=R.get(settings,"keyBindAttack");R.call(R.cls("net.minecraft.client.settings.KeyBinding"),"onTick",(int)R.call(key,"getKeyCode"));lastAttack=elapsed;}
        }
        Object key=R.get(R.get(minecraft,"gameSettings"),"keyBindUseItem");
        R.call(R.cls("net.minecraft.client.settings.KeyBinding"),"setKeyBindState",(int)R.call(key,"getKeyCode"),down&&!rod&&!attack);
        Map<String,Object> e=new LinkedHashMap<>();e.put("type","practice_input");e.put("time",System.currentTimeMillis());e.put("elapsed_ms",elapsed);
        e.put("use_key",down);e.put("blocking",R.call(player,"isBlocking"));e.put("health",R.call(player,"getHealth"));
        e.put("action_bar",R.get(R.get(minecraft,"ingameGUI"),"recordPlaying"));
        e.put("armor_points",R.call(player,"getTotalArmorValue"));e.put("food",R.call(R.call(player,"getFoodStats"),"getFoodLevel"));
        e.put("saturation",R.num(R.call(player,"getFoodStats"),"foodSaturationLevel"));
        e.put("rod_input",rod);e.put("attack_input",attack);
        e.put("selected_slot",R.get(R.get(player,"inventory"),"currentItem"));
        Object hook=R.get(player,"fishEntity");e.put("has_fish_hook",hook!=null);
        if(hook!=null)e.put("fish_hook_position",List.of(R.num(hook,"posX"),R.num(hook,"posY"),R.num(hook,"posZ")));
        Referee.append(Client.DIR.resolve("practice.jsonl"),e);return true;
    }
    public static void sound(Object handler,Object packet){
        if(!enabled())return;
        Referee.append(Client.DIR.resolve("practice.jsonl"),Map.of("type","practice_sound","time",System.currentTimeMillis(),
            "sound",R.call(packet,"getSoundName"),"volume",R.call(packet,"getVolume"),"pitch",R.call(packet,"getPitch")));
    }
}
