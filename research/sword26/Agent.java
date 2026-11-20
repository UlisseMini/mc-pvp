package sword;

import java.lang.classfile.*;
import java.lang.constant.*;
import java.lang.instrument.*;
import java.security.ProtectionDomain;

/** Insert an input callback on the client and a match referee on the server. */
public final class Agent {
    public static void premain(String ignored,Instrumentation instrumentation){
        instrumentation.addTransformer(new ClassFileTransformer(){
            public byte[] transform(ClassLoader loader,String name,Class<?> cls,ProtectionDomain domain,byte[] bytes){
                boolean client="net/minecraft/client/Minecraft".equals(name);
                if(!client&&!"net/minecraft/server/MinecraftServer".equals(name))return null;
                var game=ClassDesc.of(name.replace('/','.'));
                var hook=ClassDesc.of(client?"sword.ClientKeys":"sword.Referee");
                var api=ClassFile.of();
                return api.transformClass(api.parse(bytes),ClassTransform.transformingMethodBodies(
                    m->m.methodName().equalsString(client?"tick":"tickServer"),new CodeTransform(){
                        public void atStart(CodeBuilder code){
                            code.aload(0).invokestatic(hook,"tick",MethodTypeDesc.of(ConstantDescs.CD_void,game));
                        }
                        public void accept(CodeBuilder code,CodeElement element){code.with(element);}
                    }));
            }
        });
    }
}
