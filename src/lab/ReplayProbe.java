package lab;

import java.util.*;

/** Explicit native-client viewer test; disabled in normal clients. */
public final class ReplayProbe {
    public static void sample(Object minecraft,long elapsed){
        if(!Boolean.getBoolean("lab.replay_probe"))return;
        Object world=R.get(minecraft,"theWorld"),self=R.get(minecraft,"thePlayer");if(world==null||self==null)return;
        List<Map<String,Object>> players=new ArrayList<>();int hooks=0;
        for(Object entity:(List<?>)R.get(world,"loadedEntityList")){
            if(R.cls("net.minecraft.entity.player.EntityPlayer").isInstance(entity)){
                Map<String,Object> p=Referee.replayPosition(entity);p.put("name",R.name(entity));p.put("id",R.call(entity,"getEntityId"));
                players.add(p);
            }else if(R.cls("net.minecraft.entity.projectile.EntityFishHook").isInstance(entity))hooks++;
        }
        Object camera=R.call(minecraft,"getRenderViewEntity");
        Referee.append(Client.DIR.resolve("replay-probe.jsonl"),Map.of("time",System.currentTimeMillis(),"elapsed_ms",elapsed,
            "players",players,"camera",R.call(camera,"getEntityId"),"self",R.call(self,"getEntityId"),"hooks",hooks,
            "spectator",R.call(self,"isSpectator"),"action_bar",R.get(R.get(minecraft,"ingameGUI"),"recordPlaying")));
    }
}
