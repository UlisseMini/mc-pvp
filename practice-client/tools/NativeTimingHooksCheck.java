import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import practice.bridge.PracticeTransformer;

/** Read-only loop marker: exact native bytecode restored after stripping hooks. */
public final class NativeTimingHooksCheck {
    static AbstractInsnNode nextCode(AbstractInsnNode ins){do{ins=ins.getNext();}while(ins!=null&&ins.getOpcode()<0);return ins;}
    static void verify(Path jar,String entry,String tickName)throws Exception{
        byte[] original;try(ZipFile z=new ZipFile(jar.toFile())){original=PlayerLabelsCheck.read(z.getInputStream(z.getEntry(entry)));}
        System.setProperty("practice.role","bot");
        byte[] transformed=new PracticeTransformer().transform("ave","net.minecraft.client.Minecraft",original);
        ClassNode before=new ClassNode(),after=new ClassNode();new ClassReader(original).accept(before,0);new ClassReader(transformed).accept(after,0);
        int markers=0,heads=0;
        for(int i=0;i<before.methods.size();i++){
            MethodNode a=before.methods.get(i),b=after.methods.get(i);
            if(!a.name.equals(tickName)||!a.desc.equals("()V"))continue;
            for(AbstractInsnNode ins:b.instructions.toArray())if(ins instanceof MethodInsnNode){
                MethodInsnNode call=(MethodInsnNode)ins;if(!call.owner.equals("practice/bridge/BotHooks"))continue;
                if(call.name.equals("tick")){
                    AbstractInsnNode arg=ins.getPrevious();PlayerLabelsCheck.check(arg.getOpcode()==Opcodes.ALOAD&&((VarInsnNode)arg).var==0,"head receives only native Minecraft");
                    b.instructions.remove(arg);b.instructions.remove(ins);heads++;
                }else if(call.name.equals("attackLoop")){
                    AbstractInsnNode previous=ins.getPrevious();while(previous!=null&&previous.getOpcode()<0&&!(previous instanceof LabelNode))previous=previous.getPrevious();
                    PlayerLabelsCheck.check(call.desc.equals("()V")&&previous instanceof LabelNode,"empty-stack read-only loop marker at header");
                    LabelNode header=(LabelNode)previous;
                    AbstractInsnNode cursor=nextCode(ins);int bindings=0,conditions=0,backedges=0;
                    while(cursor!=null){
                        if(cursor instanceof FieldInsnNode){String name=((FieldInsnNode)cursor).name;if(name.equals("ai")||name.equals("field_74312_F"))bindings++;}
                        if(cursor instanceof MethodInsnNode&&((MethodInsnNode)cursor).desc.equals("()Z"))conditions++;
                        if(cursor instanceof JumpInsnNode&&cursor.getOpcode()==Opcodes.IFEQ)break;
                        cursor=nextCode(cursor);
                    }
                    PlayerLabelsCheck.check(bindings==1&&conditions==1&&cursor!=null,"marker precedes attack binding false/empty condition");
                    for(AbstractInsnNode x:b.instructions.toArray())if(x instanceof JumpInsnNode&&x.getOpcode()==Opcodes.GOTO&&((JumpInsnNode)x).label==header)backedges++;
                    PlayerLabelsCheck.check(backedges==1,"exact native press-loop backedge retained");
                    b.instructions.remove(ins);markers++;
                }else throw new AssertionError("Unexpected runTick hook "+call.name);
            }
            b.maxStack=a.maxStack;
            PlayerLabelsCheck.check(Arrays.equals(PlayerLabelsCheck.methodBytes(a),PlayerLabelsCheck.methodBytes(b)),"native runTick instructions/values changed");
        }
        PlayerLabelsCheck.check(heads==1&&markers==1,"one native head and one applicable loop marker per runTick");
    }
    public static void main(String[] args)throws Exception{
        verify(Paths.get(args[0]),"ave.class","s");
        verify(Paths.get(args[1]),"net/minecraft/client/Minecraft.class","func_71407_l");
        System.clearProperty("practice.role");
        System.out.println("PASS native timing hook shape: empty loop observed, native runTick unchanged after stripping read-only calls");
    }
}
