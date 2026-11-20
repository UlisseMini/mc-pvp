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
                try {return edit(name,bytes);}catch(Throwable problem){problem.printStackTrace();return null;}
            }
            private byte[] edit(String name,byte[] bytes){
                boolean client="net/minecraft/client/Minecraft".equals(name);
                boolean clock=System.getProperty("sword.clock")!=null;
                if(clock&&"net/minecraft/network/Connection".equals(name))return ClockHooks.transform(name,bytes);
                if("net/minecraft/network/protocol/game/ClientboundSetEntityMotionPacket".equals(name)&&System.getProperty("sword.run")!=null){
                    var api=ClassFile.of();
                    return api.transformClass(api.parse(bytes),ClassTransform.transformingMethodBodies(
                        m->m.methodName().equalsString("<init>")&&m.methodType().equalsString("(Lnet/minecraft/world/entity/Entity;)V"),
                        new CodeTransform(){
                            public void atStart(CodeBuilder c){c.aload(1).invokestatic(ClassDesc.of("sword.Referee"),"motionPacket",
                                MethodTypeDesc.ofDescriptor("(Lnet/minecraft/world/entity/Entity;)V"));}
                            public void accept(CodeBuilder c,CodeElement e){c.with(e);}
                        }));
                }
                if("net/minecraft/world/entity/player/Player".equals(name)&&System.getProperty("sword.run")!=null){
                    var api=ClassFile.of();
                    var capture=ClassTransform.transformingMethodBodies(m->m.methodName().equalsString("attack"),new CodeTransform(){
                        public void atStart(CodeBuilder c){c.aload(0).aload(1).invokestatic(ClassDesc.of("sword.Referee"),"beforeAttack",
                            MethodTypeDesc.ofDescriptor("(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/entity/Entity;)V"));}
                        public void accept(CodeBuilder c,CodeElement e){c.with(e);}
                    });
                    var first=api.transformClass(api.parse(bytes),capture);
                    var result=api.transformClass(api.parse(first),ClassTransform.transformingMethodBodies(
                        m->m.methodName().equalsString("attackVisualEffects"),new CodeTransform(){
                            public void atStart(CodeBuilder c){
                                c.aload(0).aload(1).iload(2).iload(3).iload(4).iload(5).invokestatic(
                                    ClassDesc.of("sword.Referee"),"contact",MethodTypeDesc.ofDescriptor(
                                    "(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/entity/Entity;ZZZZ)V"));
                            }
                            public void accept(CodeBuilder c,CodeElement e){c.with(e);}
                        }));
                    System.out.println("Sword lab installed native contact recorder");return result;
                }
                if(!client&&!"net/minecraft/server/MinecraftServer".equals(name))return null;
                var game=ClassDesc.of(name.replace('/','.'));
                var hook=ClassDesc.of(client?"sword.ClientKeys":"sword.Referee");
                var api=ClassFile.of();
                ClassModel model=api.parse(clock?ClockHooks.transform(name,bytes):bytes);
                if(client&&Integer.getInteger("sword.speed",1)>1){
                    int speed=Integer.getInteger("sword.speed");
                    byte[] fast=api.transformClass(model,ClassTransform.transformingMethodBodies(
                        m->m.methodName().equalsString("getTickTargetMillis"),(code,element)->{
                            if(element instanceof java.lang.classfile.instruction.ReturnInstruction ret && ret.opcode()==Opcode.FRETURN){
                                code.loadConstant((float)speed).fdiv();
                            }
                            code.with(element);
                        }));
                    model=api.parse(fast);
                }
                return api.transformClass(model,ClassTransform.transformingMethodBodies(
                    m->m.methodName().equalsString(client?"tick":"tickServer"),new CodeTransform(){
                        public void atStart(CodeBuilder code){
                            if(clock&&client)code.aload(0).invokestatic(ClockHooks.CLIENT,"beforeTick",ClockHooks.MC);
                            code.aload(0).invokestatic(hook,"tick",MethodTypeDesc.of(ConstantDescs.CD_void,game));
                        }
                        public void accept(CodeBuilder code,CodeElement element){
                            if(clock&&client&&element instanceof java.lang.classfile.instruction.ReturnInstruction){
                                code.aload(0).invokestatic(ClockHooks.CLIENT,"afterTick",ClockHooks.MC);
                            }
                            code.with(element);
                        }
                    }));
            }
        });
    }
}
