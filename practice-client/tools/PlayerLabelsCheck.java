import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import practice.bridge.PracticeTransformer;

/** Validate the render alias against both pinned obfuscated and remapped player bytecode. */
public final class PlayerLabelsCheck {
    static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    static byte[] methodBytes(MethodNode method){
        ClassWriter writer=new ClassWriter(0);writer.visit(Opcodes.V1_8,Opcodes.ACC_PUBLIC,"Fixture",null,"java/lang/Object",null);
        method.accept(writer);writer.visitEnd();return writer.toByteArray();
    }
    static void verify(Path jar,String entry,String displayName,String identityName)throws Exception{
        byte[] original;try(ZipFile zip=new ZipFile(jar.toFile())){original=read(zip.getInputStream(zip.getEntry(entry)));}
        byte[] transformed=new PracticeTransformer().transform("wn","net.minecraft.entity.player.EntityPlayer",original);
        ClassNode before=new ClassNode(),after=new ClassNode();new ClassReader(original).accept(before,0);new ClassReader(transformed).accept(after,0);
        int hooks=0,identity=0;
        for(int j=0;j<before.methods.size();j++){
            MethodNode a=before.methods.get(j),b=after.methods.get(j);
            if(!a.name.equals(displayName)){check(Arrays.equals(methodBytes(a),methodBytes(b)),"non-display method changed: "+a.name);if(a.name.equals(identityName))identity++;continue;}
            int returns=0;for(AbstractInsnNode i:a.instructions.toArray())if(i.getOpcode()==Opcodes.ARETURN)returns++;
            for(AbstractInsnNode i:b.instructions.toArray())if(i instanceof MethodInsnNode&&((MethodInsnNode)i).owner.equals("practice/bridge/PlayerLabels")){
                MethodInsnNode call=(MethodInsnNode)i;check(call.name.equals("displayName")&&call.desc.equals("(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"),"hook descriptor");
                check(i.getPrevious() instanceof VarInsnNode&&((VarInsnNode)i.getPrevious()).var==0,"native entity passed");
                check(i.getNext().getOpcode()==Opcodes.CHECKCAST&&i.getNext().getNext().getOpcode()==Opcodes.ARETURN,"typed return");hooks++;
            }
            check(hooks==returns&&hooks>0,"each display exit hooked");
        }
        check(identity>0&&hooks>0,"pinned getName/display methods found");
    }
    static byte[] read(java.io.InputStream stream)throws Exception{try(java.io.InputStream in=stream;java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream()){byte[] buf=new byte[8192];int n;while((n=in.read(buf))!=-1)out.write(buf,0,n);return out.toByteArray();}}
    public static void main(String[] args)throws Exception{
        verify(Paths.get(args[0]),"wn.class","f_","e_");
        verify(Paths.get(args[1]),"net/minecraft/entity/player/EntityPlayer.class","func_145748_c_","func_70005_c_");
        System.out.println("PASS pinned player transform: display return hooked; getName and every other native method unchanged");
    }
}
