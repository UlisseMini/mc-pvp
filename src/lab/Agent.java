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
                || name.equals("wn") || name.equals("bcy") || name.equals("bex") || name.equals("hm") || name.equals("bfk")
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
                } else if(name.equals("bfk")&&method.name.equals("a")&&method.desc.equals("(F)V")){
                    // Explicit practice reach: keep native occlusion, target boxes,
                    // selection, and ordinary attack input. Defaults return 3.0.
                    int limits=0,rays=0;
                    for(AbstractInsnNode ins:method.instructions.toArray()){
                        if(ins instanceof LdcInsnNode value&&Double.valueOf(3.0).equals(value.cst)){
                            method.instructions.set(ins,new MethodInsnNode(Opcodes.INVOKESTATIC,"lab/Client","attackReach","()D",false));limits++;
                        }else if(ins instanceof MethodInsnNode call&&call.owner.equals("bda")&&call.name.equals("d")&&call.desc.equals("()F")){
                            method.instructions.insert(ins,new MethodInsnNode(Opcodes.INVOKESTATIC,"lab/Client","selectionRayLength","(F)F",false));rays++;
                        }
                    }
                    if(limits!=2||rays!=1)throw new IllegalStateException("Pinned native raycast shape changed: "+limits+" limits, "+rays+" rays");
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
                    int immuneBefore=method.maxLocals++;
                    InsnList capture=callThis("lab/CombatStats","attackImmune");
                    // This observer returns a boolean rather than void.
                    ((MethodInsnNode)capture.getLast()).desc="(Ljava/lang/Object;)Z";
                    capture.add(new VarInsnNode(Opcodes.ISTORE,immuneBefore));method.instructions.insert(capture);
                    for (AbstractInsnNode ins : method.instructions.toArray()) if (ins.getOpcode() == Opcodes.IRETURN) {
                        InsnList hook = new InsnList(); hook.add(new InsnNode(Opcodes.DUP));
                        hook.add(new VarInsnNode(Opcodes.ALOAD, 0)); hook.add(new VarInsnNode(Opcodes.ALOAD, 1));
                        hook.add(new VarInsnNode(Opcodes.FLOAD, 2));
                        hook.add(new VarInsnNode(Opcodes.ILOAD,immuneBefore));
                        hook.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "lab/Referee", "hit", "(ZLjava/lang/Object;Ljava/lang/Object;FZ)V", false));
                        method.instructions.insertBefore(ins, hook);
                    }
                    changed = true;
                } else if(name.equals("pr")&&(method.name.equals("b")||method.name.equals("c"))&&method.desc.equals("(Low;F)F")){
                    int observed=0;
                    for(AbstractInsnNode ins:method.instructions.toArray())if(ins instanceof MethodInsnNode call){
                        String observer=null;
                        if(method.name.equals("b")&&call.owner.equals("pr")&&call.name.equals("br")&&call.desc.equals("()I"))observer="armor";
                        if(method.name.equals("c")&&call.owner.equals("pf")&&call.name.equals("c")&&call.desc.equals("()I"))observer="resistance";
                        if(method.name.equals("c")&&call.owner.equals("ack")&&call.name.equals("a")&&call.desc.equals("([Lzx;Low;)I"))observer="protection";
                        if(observer!=null){
                            InsnList hook=new InsnList();hook.add(new InsnNode(Opcodes.DUP));hook.add(new VarInsnNode(Opcodes.ALOAD,0));hook.add(new VarInsnNode(Opcodes.ALOAD,1));
                            hook.add(new MethodInsnNode(Opcodes.INVOKESTATIC,"lab/DamageBreakdown",observer,"(ILjava/lang/Object;Ljava/lang/Object;)V",false));
                            method.instructions.insert(ins,hook);observed++;
                        }
                    }
                    if(observed!=(method.name.equals("b")?1:2))throw new IllegalStateException("Pinned damage modifier observations changed");
                    changed=true;
                } else if (name.equals("wn") && method.name.equals("a") && method.desc.equals("(Low;F)Z")) {
                    // PvP rod mode only: let zero-damage hook contacts reach the
                    // original living-entity hit/knockback/immunity machinery.
                    // All other zero-damage sources keep vanilla's early return.
                    int rodSites=0;
                    for(AbstractInsnNode ins:method.instructions.toArray()){
                        if(!(ins instanceof VarInsnNode damage)||damage.getOpcode()!=Opcodes.FLOAD||damage.var!=2)continue;
                        AbstractInsnNode zero=nextInstruction(ins),compare=nextInstruction(zero);
                        AbstractInsnNode branch=nextInstruction(compare);
                        if(zero==null||zero.getOpcode()!=Opcodes.FCONST_0||compare==null||compare.getOpcode()!=Opcodes.FCMPL||
                                !(branch instanceof JumpInsnNode jump)||jump.getOpcode()!=Opcodes.IFNE)continue;
                        AbstractInsnNode rejected=nextInstruction(branch),returned=nextInstruction(rejected);
                        if(rejected==null||rejected.getOpcode()!=Opcodes.ICONST_0||returned==null||returned.getOpcode()!=Opcodes.IRETURN)continue;
                        InsnList rod=new InsnList();rod.add(new VarInsnNode(Opcodes.ALOAD,0));rod.add(new VarInsnNode(Opcodes.ALOAD,1));
                        rod.add(new MethodInsnNode(Opcodes.INVOKESTATIC,"lab/Referee","allowRodContact","(Ljava/lang/Object;Ljava/lang/Object;)Z",false));
                        rod.add(new JumpInsnNode(Opcodes.IFNE,jump.label));method.instructions.insert(branch,rod);rodSites++;
                    }
                    if(rodSites!=1)throw new IllegalStateException("Expected one pinned player zero-damage rejection, found "+rodSites);
                    changed=true;
                } else if (name.equals("wn") && method.name.equals("d") && method.desc.equals("(Low;F)V")) {
                    // Observe the actual vanilla blocking reduction, after immunity
                    // acceptance and before armor. No predicate or damage is replaced.
                    int originalDamage=method.maxLocals++;
                    int healthBefore=method.maxLocals++;
                    InsnList capture=new InsnList();capture.add(new VarInsnNode(Opcodes.FLOAD,2));
                    capture.add(new VarInsnNode(Opcodes.FSTORE,originalDamage));
                    capture.add(new VarInsnNode(Opcodes.ALOAD,0));capture.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,"pr","bn","()F",false));
                    capture.add(new VarInsnNode(Opcodes.FSTORE,healthBefore));
                    capture.add(new VarInsnNode(Opcodes.ALOAD,0));capture.add(new VarInsnNode(Opcodes.ALOAD,1));capture.add(new VarInsnNode(Opcodes.FLOAD,2));
                    capture.add(new MethodInsnNode(Opcodes.INVOKESTATIC,"lab/DamageBreakdown","damageStart","(Ljava/lang/Object;Ljava/lang/Object;F)V",false));
                    method.instructions.insert(capture);
                    int blockSites=0;
                    for(AbstractInsnNode ins:method.instructions.toArray()){
                        if(ins instanceof VarInsnNode store&&store.getOpcode()==Opcodes.FSTORE&&store.var==2
                                &&ins.getPrevious()!=null&&ins.getPrevious().getOpcode()==Opcodes.FMUL
                                &&ins.getPrevious().getPrevious() instanceof LdcInsnNode factor&&Float.valueOf(.5f).equals(factor.cst)){
                            InsnList hook=new InsnList();hook.add(new VarInsnNode(Opcodes.ALOAD,0));hook.add(new VarInsnNode(Opcodes.ALOAD,1));
                            hook.add(new VarInsnNode(Opcodes.FLOAD,originalDamage));hook.add(new VarInsnNode(Opcodes.FLOAD,2));
                            hook.add(new MethodInsnNode(Opcodes.INVOKESTATIC,"lab/Referee","blockedDamage","(Ljava/lang/Object;Ljava/lang/Object;FF)V",false));
                            method.instructions.insert(ins,hook);blockSites++;
                        }
                    }
                    if(blockSites!=1)throw new IllegalStateException("Expected one pinned vanilla sword-block reduction, found "+blockSites);
                    for (AbstractInsnNode ins : method.instructions.toArray()) if (ins.getOpcode() == Opcodes.RETURN){
                        InsnList damage=new InsnList();damage.add(new VarInsnNode(Opcodes.ALOAD,0));damage.add(new VarInsnNode(Opcodes.ALOAD,1));
                        damage.add(new VarInsnNode(Opcodes.FLOAD,healthBefore));
                        damage.add(new MethodInsnNode(Opcodes.INVOKESTATIC,"lab/CombatStats","healthDamage","(Ljava/lang/Object;Ljava/lang/Object;F)V",false));
                        method.instructions.insertBefore(ins,damage);
                        method.instructions.insertBefore(ins, callThis("lab/Referee", "restoreHealth"));
                    }
                    changed = true;
                } else if(name.equals("pr")&&method.name.equals("h")&&method.desc.equals("(F)V")){
                    int healthBefore=method.maxLocals++;
                    InsnList capture=new InsnList();capture.add(new VarInsnNode(Opcodes.ALOAD,0));
                    capture.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,"pr","bn","()F",false));capture.add(new VarInsnNode(Opcodes.FSTORE,healthBefore));
                    method.instructions.insert(capture);
                    for(AbstractInsnNode ins:method.instructions.toArray())if(ins.getOpcode()==Opcodes.RETURN){
                        InsnList heal=new InsnList();heal.add(new VarInsnNode(Opcodes.ALOAD,0));heal.add(new VarInsnNode(Opcodes.FLOAD,healthBefore));
                        heal.add(new MethodInsnNode(Opcodes.INVOKESTATIC,"lab/CombatStats","healed","(Ljava/lang/Object;F)V",false));method.instructions.insertBefore(ins,heal);
                    }
                    changed=true;
                } else if(name.equals("wn")&&((method.name.equals("a")&&method.desc.equals("(Lzx;I)V"))||
                        (method.name.equals("bV")&&method.desc.equals("()V")))){
                    if(method.name.equals("bV"))method.instructions.insert(callThis("lab/Client","blockUseClearing"));
                    for(AbstractInsnNode ins:method.instructions.toArray())if(ins.getOpcode()==Opcodes.RETURN)
                        method.instructions.insertBefore(ins,callThis("lab/CombatStats","usingItemChanged"));
                    changed=true;
                } else if(name.equals("bcy")&&method.name.equals("a")&&method.desc.equals("(Lgi;)V")){
                    for(AbstractInsnNode ins:method.instructions.toArray())if(ins.getOpcode()==Opcodes.RETURN){
                        InsnList hook=new InsnList();hook.add(new VarInsnNode(Opcodes.ALOAD,1));
                        hook.add(new MethodInsnNode(Opcodes.INVOKESTATIC,"lab/Client","hurtStatus","(Ljava/lang/Object;)V",false));
                        method.instructions.insertBefore(ins,hook);
                    }
                    changed=true;
                } else if(name.equals("bcy")&&method.name.equals("a")&&method.desc.equals("(Lgs;)V")){
                    for(AbstractInsnNode ins:method.instructions.toArray())if(ins.getOpcode()==Opcodes.RETURN)
                        method.instructions.insertBefore(ins,callPair("lab/PracticeProbe","sound"));
                    changed=true;
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
                    int damageContext=method.maxLocals++;
                    int critBaseSites=0,plannedSites=0;
                    for(AbstractInsnNode ins:method.instructions.toArray()){
                        if(ins instanceof LdcInsnNode value&&Float.valueOf(1.5f).equals(value.cst)){
                            InsnList hook=new InsnList();hook.add(new VarInsnNode(Opcodes.ALOAD,0));hook.add(new VarInsnNode(Opcodes.ALOAD,1));hook.add(new VarInsnNode(Opcodes.FLOAD,2));
                            hook.add(new MethodInsnNode(Opcodes.INVOKESTATIC,"lab/DamageBreakdown","critBase","(Ljava/lang/Object;Ljava/lang/Object;F)V",false));
                            method.instructions.insertBefore(ins,hook);critBaseSites++;
                        }
                        if(ins instanceof MethodInsnNode call&&call.owner.equals("pk")&&call.name.equals("a")&&call.desc.equals("(Low;F)Z")){
                            InsnList hook=new InsnList();hook.add(new VarInsnNode(Opcodes.ALOAD,0));hook.add(new VarInsnNode(Opcodes.ALOAD,1));hook.add(new VarInsnNode(Opcodes.FLOAD,2));hook.add(new VarInsnNode(Opcodes.FLOAD,4));
                            hook.add(new MethodInsnNode(Opcodes.INVOKESTATIC,"lab/DamageBreakdown","planned","(Ljava/lang/Object;Ljava/lang/Object;FF)V",false));
                            method.instructions.insertBefore(ins,hook);plannedSites++;
                        }
                    }
                    if(critBaseSites!=1||plannedSites!=1)throw new IllegalStateException("Pinned critical damage sites changed");
                    int criticalSites=0;
                    for(AbstractInsnNode ins:method.instructions.toArray())if(ins instanceof MethodInsnNode call&&
                            call.owner.equals("wn")&&call.name.equals("b")&&call.desc.equals("(Lpk;)V")){
                        // Vanilla calls onCriticalHit only for an accepted critical,
                        // including a lethal hit. Preserve the two original arguments.
                        InsnList critical=new InsnList();critical.add(new InsnNode(Opcodes.DUP2));
                        critical.add(new MethodInsnNode(Opcodes.INVOKESTATIC,"lab/CombatStats","critical","(Ljava/lang/Object;Ljava/lang/Object;)V",false));
                        method.instructions.insertBefore(ins,critical);criticalSites++;
                    }
                    if(criticalSites!=1)throw new IllegalStateException("Expected one native accepted-critical callback, found "+criticalSites);
                    InsnList before=new InsnList();before.add(new VarInsnNode(Opcodes.ALOAD,0));before.add(new VarInsnNode(Opcodes.ALOAD,1));
                    before.add(new MethodInsnNode(Opcodes.INVOKESTATIC,"lab/DamageBreakdown","begin","(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;",false));
                    before.add(new VarInsnNode(Opcodes.ASTORE,damageContext));
                    before.add(new VarInsnNode(Opcodes.ALOAD,0));before.add(new VarInsnNode(Opcodes.ALOAD,1));
                    before.add(new MethodInsnNode(Opcodes.INVOKESTATIC,"lab/Client","attackBefore","(Ljava/lang/Object;Ljava/lang/Object;)V",false));
                    before.add(callPair("lab/ScienceTrace","attackBefore"));method.instructions.insert(before);
                    for(AbstractInsnNode ins:method.instructions.toArray())if(ins.getOpcode()==Opcodes.RETURN){
                        InsnList after=new InsnList();after.add(new VarInsnNode(Opcodes.ALOAD,0));after.add(new VarInsnNode(Opcodes.ALOAD,1));
                        after.add(new MethodInsnNode(Opcodes.INVOKESTATIC,"lab/Client","attackAfter","(Ljava/lang/Object;Ljava/lang/Object;)V",false));
                        after.add(callPair("lab/ScienceTrace","attackAfter"));method.instructions.insertBefore(ins,after);
                        InsnList damageEnd=new InsnList();damageEnd.add(new VarInsnNode(Opcodes.ALOAD,damageContext));
                        damageEnd.add(new MethodInsnNode(Opcodes.INVOKESTATIC,"lab/DamageBreakdown","end","(Ljava/lang/Object;)V",false));
                        method.instructions.insertBefore(ins,damageEnd);
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
    private static AbstractInsnNode nextInstruction(AbstractInsnNode node){
        if(node==null)return null;
        do{node=node.getNext();}while(node!=null&&node.getOpcode()<0);
        return node;
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
