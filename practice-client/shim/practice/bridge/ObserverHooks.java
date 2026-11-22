package practice.bridge;

/** Calls injected at stable game boundaries; observers never cancel game work. */
public final class ObserverHooks {
    public interface Sink {
        void connected(Object manager,Object context);
        void wireBefore(Object context,Object buffer);
        void wireAfter(Object packets);
        void wireOut(Object context,Object packet,Object buffer);
        void apply(Object packet,boolean after);
        void input(String kind,boolean after);
        void motion(String kind,Object player,boolean after);
        void clock(String phase);
        void queued();
    }
    public static volatile Sink sink;
    public static volatile long errors;
    static final ThreadLocal<Boolean> replayWorldLoad=new ThreadLocal<>();
    public static void worldLoadGc(){if(!Boolean.TRUE.equals(replayWorldLoad.get()))System.gc();}
    public static boolean hideEmptyHand(boolean invisible){
        if(!invisible)return false;
        net.minecraft.entity.Entity view=net.minecraft.client.Minecraft.func_71410_x().func_175606_aa();
        return !(view instanceof OfflineReplay.RecordedPlayer&&((OfflineReplay.RecordedPlayer)view).self);
    }
    public static float replayWorldPartial(float partial){
        net.minecraft.client.multiplayer.WorldClient world=net.minecraft.client.Minecraft.func_71410_x().field_71441_e;
        return world instanceof OfflineReplay.ReplayWorld&&((OfflineReplay.ReplayWorld)world).frozen()?1:partial;
    }
    public static void connected(Object m,Object c){try{if(sink!=null)sink.connected(m,c);}catch(Exception|LinkageError e){errors++;}}
    public static void wireBefore(Object c,Object b){try{if(sink!=null)sink.wireBefore(c,b);}catch(Exception|LinkageError e){errors++;}}
    public static void wireAfter(Object p){
        try{CombatCoach.inbound(p);}catch(Exception|LinkageError e){errors++;}
        try{if(sink!=null)sink.wireAfter(p);}catch(Exception|LinkageError e){errors++;}
    }
    public static void wireOut(Object c,Object p,Object b){
        try{CombatCoach.outbound(c,p);}catch(Exception|LinkageError e){errors++;}
        try{if(sink!=null)sink.wireOut(c,p,b);}catch(Exception|LinkageError e){errors++;}
    }
    public static void apply(Object p,boolean a){
        try{CombatCoach.packet(p,a);}catch(Exception|LinkageError e){errors++;}
        try{if(a&&SumoCoach.hooks!=null)SumoCoach.hooks.packet(p);}catch(Exception|LinkageError e){errors++;}
        try{if(a&&RemoteCrosshair.hooks!=null)RemoteCrosshair.hooks.packet(p);}catch(Exception|LinkageError e){errors++;}
        try{if(sink!=null)sink.apply(p,a);}catch(Exception|LinkageError e){errors++;}
    }
    public static void jump(Object player,boolean after){
        try{if(after&&SumoCoach.hooks!=null)SumoCoach.hooks.jump(player);}catch(Exception|LinkageError e){errors++;}
        try{CombatCoach.jump(player,after);if(sink!=null)sink.motion("jump",player,after);}catch(Exception|LinkageError e){errors++;}
    }
    public static void travel(Object player,boolean after){
        try{if(SumoCoach.hooks!=null)SumoCoach.hooks.travel(player,after);}catch(Exception|LinkageError e){errors++;}
        try{if(after&&RemoteCrosshair.hooks!=null)RemoteCrosshair.hooks.moved(player);}catch(Exception|LinkageError e){errors++;}
        try{CombatCoach.travel(player,after);if(sink!=null)sink.motion("travel",player,after);}catch(Exception|LinkageError e){errors++;}
    }
    public static void input(String k,boolean a){
        try{if(SumoCoach.hooks!=null&&k.equals("attack_click"))SumoCoach.hooks.click(a);}catch(Exception|LinkageError e){errors++;}
        try{if(RemoteCrosshair.hooks!=null&&k.equals("attack_click"))RemoteCrosshair.hooks.click(a);}catch(Exception|LinkageError e){errors++;}
        try{if(sink!=null)sink.input(k,a);}catch(Exception|LinkageError e){errors++;}
    }
    public static void attackSlot(){try{if(SumoCoach.hooks!=null)SumoCoach.hooks.attackSlot();}catch(Exception|LinkageError e){errors++;}}
    public static void clock(String phase){try{if(sink!=null)sink.clock(phase);}catch(Exception|LinkageError e){errors++;}}
    public static void queued(){try{if(sink!=null)sink.queued();}catch(Exception|LinkageError e){errors++;}}
}
