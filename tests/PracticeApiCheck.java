package lab;

/** Resolve practice-only APIs against the pinned dedicated server, not the client jar. */
public final class PracticeApiCheck {
    public static void main(String[] args){
        Object food=R.construct("net.minecraft.util.FoodStats");
        R.call(food,"setFoodLevel",20);R.set(food,"foodSaturationLevel",5.0f);
        R.set(food,"foodExhaustionLevel",0.0f);R.set(food,"foodTimer",0);
        if(((Number)R.call(food,"getFoodLevel")).intValue()!=20||R.num(food,"foodSaturationLevel")!=5)throw new AssertionError("Food reset");
        Object sound=R.construct("net.minecraft.network.play.server.S29PacketSoundEffect","note.pling",0.0,4.0,0.0,.65f,1.4f);
        if(!R.get(sound,"soundName").equals("note.pling"))throw new AssertionError("Sound packet");
        java.util.Set<String> methods=new java.util.HashSet<>();
        for(Class<?> c=R.cls("net.minecraft.entity.player.EntityPlayerMP");c!=null;c=c.getSuperclass())
            for(java.lang.reflect.Method m:c.getDeclaredMethods())
                methods.add(R.MAP.getProperty("M."+c.getName()+"."+m.getName()+org.objectweb.asm.Type.getMethodDescriptor(m),m.getName()));
        for(String method:java.util.List.of("getHealth","setHealth","isBlocking","getTotalArmorValue","clearItemInUse","getFoodStats","isEntityAlive"))
            if(!methods.contains(method))throw new AssertionError("Dedicated server is missing "+method);
        System.out.println("PASS dedicated-server food fields and private sound packet");
    }
}
