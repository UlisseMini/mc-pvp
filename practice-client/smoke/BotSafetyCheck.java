package practice.bridge;

import java.io.*;
import java.util.*;
import java.util.jar.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Offline native-hook shape and default-human isolation checks on Java 8. */
public final class BotSafetyCheck {
    private static void require(boolean ok,String why){if(!ok)throw new AssertionError(why);}
    public static void main(String[] args)throws Exception{
        System.clearProperty("practice.role");System.clearProperty("lab.root");
        System.setProperty("lab.role","client");System.setProperty("lab.slot","0");
        BotHooks.tick(null);BotHooks.frame(null);BotHooks.movement(null,1,1);BotHooks.attackLoop();
        require(BotHooks.reach()==3&&BotHooks.ray(4.5f)==4.5f,"human role ignores stale bot startup properties");
        require(!BotRuntime.enabled(),"human does not create a controller");
        Map<String,String> classes=new LinkedHashMap<>();
        classes.put("ave","net.minecraft.client.Minecraft");classes.put("bfk","net.minecraft.client.renderer.EntityRenderer");
        classes.put("wn","net.minecraft.entity.player.EntityPlayer");classes.put("pr","net.minecraft.entity.EntityLivingBase");
        classes.put("bex","net.minecraft.client.entity.EntityOtherPlayerMP");
        try(JarFile jar=new JarFile(args[0])){
            for(Map.Entry<String,String> entry:classes.entrySet()){
                ByteArrayOutputStream bytes=new ByteArrayOutputStream();try(InputStream in=jar.getInputStream(jar.getJarEntry(entry.getKey()+".class"))){byte[] b=new byte[8192];int n;while((n=in.read(b))>=0)bytes.write(b,0,n);}
                byte[] original=bytes.toByteArray();System.clearProperty("practice.role");
                require(BotTransformer.transform(entry.getValue(),original)==original,"human gameplay bytecode remains untouched");
                System.setProperty("practice.role","bot");byte[] transformed=BotTransformer.transform(entry.getValue(),original);
                require(!Arrays.equals(original,transformed),"bot receives native hooks");
                ClassNode node=new ClassNode();new ClassReader(transformed).accept(node,0);
                for(MethodNode method:node.methods)for(AbstractInsnNode ins:method.instructions.toArray())if(ins instanceof MethodInsnNode){MethodInsnNode call=(MethodInsnNode)ins;
                    if(call.owner.equals("practice/bridge/BotHooks")&&call.name.equals("attack"))require(method.name.equals("f")&&method.desc.equals("(Lpk;)V"),"only native entity attack receives paired object hook");}
            }
        }
        System.clearProperty("practice.role");
        System.out.println("PASS default-human input isolation, unchanged human gameplay bytecode and all pinned native bot hook shapes");
    }
}
