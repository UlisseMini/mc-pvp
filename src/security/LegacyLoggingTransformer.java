package security;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Preserve beta Log4j's linked class while disabling every JNDI lookup overload. */
public final class LegacyLoggingTransformer implements ClassFileTransformer {
    public byte[] transform(ClassLoader loader,String name,Class<?> type,ProtectionDomain domain,byte[] bytes){
        if(!"org/apache/logging/log4j/core/lookup/JndiLookup".equals(name))return null;
        ClassNode node=new ClassNode();new ClassReader(bytes).accept(node,0);int count=0;
        for(MethodNode method:node.methods)if(method.name.equals("lookup")&&method.desc.endsWith("Ljava/lang/String;")){
            method.instructions.clear();method.tryCatchBlocks.clear();if(method.localVariables!=null)method.localVariables.clear();
            method.instructions.add(new InsnNode(Opcodes.ACONST_NULL));method.instructions.add(new InsnNode(Opcodes.ARETURN));count++;
        }
        if(count==0)throw new IllegalStateException("Legacy JNDI mitigation found no lookup methods");
        ClassWriter out=new ClassWriter(ClassWriter.COMPUTE_MAXS|ClassWriter.COMPUTE_FRAMES);node.accept(out);return out.toByteArray();
    }
}
