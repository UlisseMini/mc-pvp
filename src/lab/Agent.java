package lab;

import io.github.headlesshq.headlessmc.lwjgl.agent.LwjglAgent;
import io.github.headlesshq.headlessmc.lwjgl.agent.MacosMenuAgent;
import java.lang.instrument.*;
import java.security.ProtectionDomain;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Only the pinned vanilla 1.8.9 entry points below are instrumented. */
public final class Agent implements ClassFileTransformer {
    public static void premain(String args, Instrumentation instrumentation) {
        System.setProperty("java.awt.headless", "true");
        if ("client".equals(System.getProperty("lab.role"))) {
            LwjglAgent.premain("", instrumentation);
            MacosMenuAgent.premain("", instrumentation);
        }
        instrumentation.addTransformer(new Agent());
        System.out.println("[PvP Lab] Agent loaded: " + System.getProperty("lab.role"));
    }

    @Override public byte[] transform(ClassLoader loader, String name, Class<?> cls,
                                      ProtectionDomain domain, byte[] bytes) {
        if ("org/apache/logging/log4j/core/lookup/JndiLookup".equals(name)) {
            // Legacy beta Log4j hard-links this class: keep its shape but disable JNDI itself.
            ClassNode logging = new ClassNode(); new ClassReader(bytes).accept(logging,0);
            for(MethodNode m:logging.methods)if(m.name.equals("lookup")&&m.desc.endsWith("Ljava/lang/String;")){
                m.instructions.clear();m.tryCatchBlocks.clear();if(m.localVariables!=null)m.localVariables.clear();
                m.instructions.add(new InsnNode(Opcodes.ACONST_NULL));m.instructions.add(new InsnNode(Opcodes.ARETURN));
            }
            ClassWriter out=new ClassWriter(ClassWriter.COMPUTE_MAXS|ClassWriter.COMPUTE_FRAMES);logging.accept(out);return out.toByteArray();
        }
        if (name == null || !(name.equals("ave") || name.equals("j") || name.equals("pr")
                || name.equals("wn") || name.equals("bcy") || name.equals("bex") || name.equals("hm")
                || name.equals("lh") || name.equals("lm") || name.equals("ek") || name.equals("net/minecraft/server/MinecraftServer"))) return null;
        try {
            ClassNode node = new ClassNode();
            new ClassReader(bytes).accept(node, 0);
            boolean changed = false;
            for (MethodNode method : node.methods) {
                if (name.equals("ave") && method.name.equals("s") && method.desc.equals("()V")) {
                    method.instructions.insert(callThis("lab/Client", "tick")); changed = true;
                } else if (name.equals("ave") && method.name.equals("av") && method.desc.equals("()V")) {
                    method.instructions.insert(callThis("lab/Client", "frame")); changed = true;
                } else if (name.equals("ave") && method.name.equals("aw") && method.desc.equals("()V")) {
                    method.instructions.insert(callThis("lab/ScienceTrace","clickBefore"));
                    for(AbstractInsnNode ins:method.instructions.toArray())if(ins.getOpcode()==Opcodes.RETURN)
                        method.instructions.insertBefore(ins,callThis("lab/ScienceTrace","clickAfter"));
                    changed=true;
                } else if (name.equals("net/minecraft/server/MinecraftServer") && method.name.equals("A") && method.desc.equals("()V")) {
                    for (AbstractInsnNode ins : method.instructions.toArray()) if (ins.getOpcode() == Opcodes.RETURN)
                        method.instructions.insertBefore(ins, callThis("lab/Referee", "tick"));
                    changed = true;
                } else if (name.equals("j") && method.name.equals("a") && method.desc.equals("(Lm;Ljava/lang/String;)I")) {
                    InsnList hook = new InsnList(); LabelNode proceed = new LabelNode();
                    hook.add(new VarInsnNode(Opcodes.ALOAD, 1)); hook.add(new VarInsnNode(Opcodes.ALOAD, 2));
                    hook.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "lab/Referee", "command", "(Ljava/lang/Object;Ljava/lang/String;)Z", false));
                    hook.add(new JumpInsnNode(Opcodes.IFEQ, proceed)); hook.add(new InsnNode(Opcodes.ICONST_1));
                    hook.add(new InsnNode(Opcodes.IRETURN)); hook.add(proceed);
                    method.instructions.insert(hook); changed = true;
                } else if (name.equals("pr") && method.name.equals("a") && method.desc.equals("(Low;F)Z")) {
                    for (AbstractInsnNode ins : method.instructions.toArray()) if (ins.getOpcode() == Opcodes.IRETURN) {
                        InsnList hook = new InsnList(); hook.add(new InsnNode(Opcodes.DUP));
                        hook.add(new VarInsnNode(Opcodes.ALOAD, 0)); hook.add(new VarInsnNode(Opcodes.ALOAD, 1));
                        hook.add(new VarInsnNode(Opcodes.FLOAD, 2));
                        hook.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "lab/Referee", "hit", "(ZLjava/lang/Object;Ljava/lang/Object;F)V", false));
                        method.instructions.insertBefore(ins, hook);
                    }
                    changed = true;
                } else if (name.equals("wn") && method.name.equals("d") && method.desc.equals("(Low;F)V")) {
                    for (AbstractInsnNode ins : method.instructions.toArray()) if (ins.getOpcode() == Opcodes.RETURN)
                        method.instructions.insertBefore(ins, callThis("lab/Referee", "restoreHealth"));
                    changed = true;
                } else if(name.equals("bcy")&&method.name.equals("a")&&method.desc.equals("(Lhm;)V")){
                    for(AbstractInsnNode ins:method.instructions.toArray())if(ins.getOpcode()==Opcodes.RETURN){
                        InsnList hook=new InsnList();hook.add(new VarInsnNode(Opcodes.ALOAD,1));
                        hook.add(new MethodInsnNode(Opcodes.INVOKESTATIC,"lab/Client","velocityPacket","(Ljava/lang/Object;)V",false));
                        method.instructions.insertBefore(ins,hook);
                    }
                    changed=true;
                } else if(name.equals("pr")&&method.name.equals("g")&&method.desc.equals("(FF)V")){
                    InsnList before=new InsnList();before.add(new VarInsnNode(Opcodes.ALOAD,0));before.add(new VarInsnNode(Opcodes.FLOAD,1));before.add(new VarInsnNode(Opcodes.FLOAD,2));
                    before.add(new MethodInsnNode(Opcodes.INVOKESTATIC,"lab/ScienceTrace","movementBefore","(Ljava/lang/Object;FF)V",false));method.instructions.insert(before);
                    for(AbstractInsnNode ins:method.instructions.toArray())if(ins.getOpcode()==Opcodes.RETURN)method.instructions.insertBefore(ins,callThis("lab/ScienceTrace","movementAfter"));
                    changed=true;
                } else if(name.equals("bex")&&method.name.equals("m")&&method.desc.equals("()V")){
                    method.instructions.insert(callThis("lab/ScienceTrace","interpolationBefore"));
                    for(AbstractInsnNode ins:method.instructions.toArray())if(ins.getOpcode()==Opcodes.RETURN)method.instructions.insertBefore(ins,callThis("lab/ScienceTrace","interpolationAfter"));changed=true;
                } else if(name.equals("bex")&&method.name.equals("a")&&method.desc.equals("(DDDFFIZ)V")){
                    for(AbstractInsnNode ins:method.instructions.toArray())if(ins.getOpcode()==Opcodes.RETURN)method.instructions.insertBefore(ins,callThis("lab/ScienceTrace","remoteUpdate"));changed=true;
                } else if(name.equals("hm")&&method.name.equals("<init>")&&method.desc.equals("(Lpk;)V")){
                    for(AbstractInsnNode ins:method.instructions.toArray())if(ins.getOpcode()==Opcodes.RETURN){
                        InsnList after=callPair("lab/ScienceTrace","velocityCreated");method.instructions.insertBefore(ins,after);
                    }changed=true;
                } else if(name.equals("wn")&&method.name.equals("f")&&method.desc.equals("(Lpk;)V")){
                    InsnList before=new InsnList();before.add(new VarInsnNode(Opcodes.ALOAD,0));before.add(new VarInsnNode(Opcodes.ALOAD,1));
                    before.add(new MethodInsnNode(Opcodes.INVOKESTATIC,"lab/Client","attackBefore","(Ljava/lang/Object;Ljava/lang/Object;)V",false));
                    before.add(callPair("lab/ScienceTrace","attackBefore"));method.instructions.insert(before);
                    for(AbstractInsnNode ins:method.instructions.toArray())if(ins.getOpcode()==Opcodes.RETURN){
                        InsnList after=new InsnList();after.add(new VarInsnNode(Opcodes.ALOAD,0));after.add(new VarInsnNode(Opcodes.ALOAD,1));
                        after.add(new MethodInsnNode(Opcodes.INVOKESTATIC,"lab/Client","attackAfter","(Ljava/lang/Object;Ljava/lang/Object;)V",false));
                        after.add(callPair("lab/ScienceTrace","attackAfter"));method.instructions.insertBefore(ins,after);
                    }
                    changed=true;
                }
                // Read-only replication probes: network receipt and main-thread application
                // are distinct events. The diagnostics never supply controller inputs.
                if(name.equals("lh")&&method.name.equals("a")&&method.desc.equals("(Ljava/util/List;)V")){
                    method.instructions.insert(callBoolean("tracker",0,false));
                    for(AbstractInsnNode ins:method.instructions.toArray())if(ins.getOpcode()==Opcodes.RETURN)
                        method.instructions.insertBefore(ins,callBoolean("tracker",0,true));
                    changed=true;
                }else if(name.equals("lm")&&method.name.equals("a")&&method.desc.equals("(Lff;)V")){
                    method.instructions.insert(callPair("lab/ReplicationTrace","send"));changed=true;
                }else if(name.equals("lm")&&method.name.equals("a")&&java.util.Set.of("(Lip;)V","(Lin;)V","(Lis;)V").contains(method.desc)){
                    method.instructions.insert(callPair("lab/ReplicationTrace","input"));changed=true;
                }else if(name.equals("ek")&&method.name.equals("a")&&method.desc.equals("(Lio/netty/channel/ChannelHandlerContext;Lff;)V")){
                    InsnList hook=new InsnList();hook.add(new VarInsnNode(Opcodes.ALOAD,2));
                    hook.add(new MethodInsnNode(Opcodes.INVOKESTATIC,"lab/ReplicationTrace","receive","(Ljava/lang/Object;)V",false));
                    method.instructions.insert(hook);changed=true;
                }else if(name.equals("bcy")&&method.name.equals("a")&&java.util.Set.of("(Lgv;)V","(Lhz;)V","(Lhm;)V","(Lgi;)V","(Lhk;)V").contains(method.desc)){
                    method.instructions.insert(callBoolean("apply",1,false));
                    for(AbstractInsnNode ins:method.instructions.toArray())if(ins.getOpcode()==Opcodes.RETURN)
                        method.instructions.insertBefore(ins,callBoolean("apply",1,true));
                    changed=true;
                }
            }
            if (!changed) return null;
            // Minecraft 1.8.9 uses Java 6 class files. Preserve legacy verification, avoiding
            // COMPUTE_FRAMES class loading that would bypass instrumentation of dependencies.
            if(node.version<=Opcodes.V1_6)for(MethodNode m:node.methods)
                for(AbstractInsnNode ins:m.instructions.toArray())if(ins instanceof FrameNode)m.instructions.remove(ins);
            ClassWriter out = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            node.accept(out);
            System.out.println("[PvP Lab] Instrumented " + name);
            return out.toByteArray();
        } catch (Throwable error) {
            error.printStackTrace();
            // Fail closed: an uninstrumented experiment would silently invalidate results.
            Runtime.getRuntime().halt(78);
            return null;
        }
    }
    private static InsnList callThis(String owner, String method) {
        InsnList list = new InsnList(); list.add(new VarInsnNode(Opcodes.ALOAD, 0));
        list.add(new MethodInsnNode(Opcodes.INVOKESTATIC, owner, method, "(Ljava/lang/Object;)V", false));
        return list;
    }
    private static InsnList callPair(String owner,String method) {
        InsnList list=new InsnList();list.add(new VarInsnNode(Opcodes.ALOAD,0));list.add(new VarInsnNode(Opcodes.ALOAD,1));
        list.add(new MethodInsnNode(Opcodes.INVOKESTATIC,owner,method,"(Ljava/lang/Object;Ljava/lang/Object;)V",false));return list;
    }
    private static InsnList callBoolean(String method,int local,boolean after){
        InsnList list=new InsnList();list.add(new VarInsnNode(Opcodes.ALOAD,local));
        list.add(new InsnNode(after?Opcodes.ICONST_1:Opcodes.ICONST_0));
        list.add(new MethodInsnNode(Opcodes.INVOKESTATIC,"lab/ReplicationTrace",method,"(Ljava/lang/Object;Z)V",false));return list;
    }
}
