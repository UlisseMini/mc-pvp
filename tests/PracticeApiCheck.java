package lab;

/** Resolve practice-only APIs against the pinned dedicated server, not the client jar. */
public final class PracticeApiCheck {
    public static void main(String[] args){
        Object food=R.construct("net.minecraft.util.FoodStats");
        R.call(food,"setFoodLevel",20);R.set(food,"foodSaturationLevel",5.0f);
        R.set(food,"foodExhaustionLevel",0.0f);R.set(food,"foodTimer",0);
        if(((Number)R.call(food,"getFoodLevel")).intValue()!=20||R.num(food,"foodSaturationLevel")!=5)throw new AssertionError("Food reset");
        Object sound=R.construct("net.minecraft.network.play.server.S29PacketSoundEffect","random.anvil_land",0.0,4.0,0.0,.25f,1.6f);
        if(!R.get(sound,"soundName").equals("random.anvil_land"))throw new AssertionError("Sound packet");
        java.util.Set<String> methods=new java.util.HashSet<>();
        for(Class<?> c=R.cls("net.minecraft.entity.player.EntityPlayerMP");c!=null;c=c.getSuperclass())
            for(java.lang.reflect.Method m:c.getDeclaredMethods())
                methods.add(R.MAP.getProperty("M."+c.getName()+"."+m.getName()+org.objectweb.asm.Type.getMethodDescriptor(m),m.getName()));
        for(String method:java.util.List.of("getHealth","setHealth","isBlocking","getTotalArmorValue","clearItemInUse","getFoodStats","isEntityAlive"))
            if(!methods.contains(method))throw new AssertionError("Dedicated server is missing "+method);
        R.call(R.cls("net.minecraft.init.Bootstrap"),"register");
        Object helmet=R.construct("net.minecraft.item.ItemStack",R.get(R.cls("net.minecraft.init.Items"),"diamond_helmet"));
        R.call(helmet,"setTagCompound",R.call(R.cls("net.minecraft.nbt.JsonToNBT"),"getTagFromJson","{ench:[{id:0s,lvl:4s}]}"));
        if((int)R.call(R.cls("net.minecraft.enchantment.EnchantmentHelper"),"getEnchantmentLevel",0,helmet)!=4)
            throw new AssertionError("Protection IV NBT");
        Object sword=R.construct("net.minecraft.item.ItemStack",R.get(R.cls("net.minecraft.init.Items"),"wooden_sword"));
        R.call(sword,"setTagCompound",R.call(R.cls("net.minecraft.nbt.JsonToNBT"),"getTagFromJson","{Unbreakable:1b}"));
        if(!(Boolean)R.call(R.call(sword,"getTagCompound"),"getBoolean","Unbreakable"))throw new AssertionError("Wood sword durability");
        System.out.println("PASS dedicated-server food fields, private sound packet, Protection IV and wooden-sword NBT");
    }
}
