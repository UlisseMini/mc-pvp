import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import practice.bridge.PracticeTransformer;

/** Native task order/locking must remain identical after removing observer calls. */
public final class ClockHooksCheck {
    static void verify(Path path,String entry)throws Exception{
        byte[] original;try(ZipFile z=new ZipFile(path.toFile())){original=PlayerLabelsCheck.read(z.getInputStream(z.getEntry(entry)));}
        byte[] modified=new PracticeTransformer().transform("ave","net.minecraft.client.Minecraft",original);
        ClassNode a=new ClassNode(),b=new ClassNode();new ClassReader(original).accept(a,0);new ClassReader(modified).accept(b,0);
        int clocks=0,queued=0,methods=0;
        for(int n=0;n<a.methods.size();n++){
            MethodNode before=a.methods.get(n),after=b.methods.get(n);boolean touched=false;
            for(AbstractInsnNode ins:after.instructions.toArray())if(ins instanceof MethodInsnNode){
                MethodInsnNode call=(MethodInsnNode)ins;
                if(!call.owner.equals("practice/bridge/ObserverHooks"))continue;
                if(call.name.equals("clock")){
                    AbstractInsnNode arg=ins.getPrevious();PlayerLabelsCheck.check(arg instanceof LdcInsnNode,"clock phase literal");
                    String phase=(String)((LdcInsnNode)arg).cst;
                    PlayerLabelsCheck.check(phase.equals("start")?arg.getPrevious().getOpcode()==Opcodes.MONITORENTER:ins.getNext().getOpcode()==Opcodes.MONITOREXIT,"clock remains inside queue lock");
                    after.instructions.remove(arg);after.instructions.remove(ins);clocks++;touched=true;
                }else if(call.name.equals("queued")){
                    PlayerLabelsCheck.check(ins.getPrevious() instanceof MethodInsnNode&&((MethodInsnNode)ins.getPrevious()).name.equals("add"),"queued immediately after add");
                    after.instructions.remove(ins);queued++;touched=true;
                }
            }
            if(touched){after.maxStack=before.maxStack;PlayerLabelsCheck.check(Arrays.equals(PlayerLabelsCheck.methodBytes(before),PlayerLabelsCheck.methodBytes(after)),"native task method changed: "+before.name);methods++;}
        }
        PlayerLabelsCheck.check(clocks==3&&queued==1&&methods==2,"all pinned queue boundaries observed");
    }
    public static void main(String[] args)throws Exception{
        verify(Paths.get(args[0]),"ave.class");verify(Paths.get(args[1]),"net/minecraft/client/Minecraft.class");
        System.out.println("PASS pinned client clocks: native queue order and lock instructions unchanged");
    }
}
