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
        instrumentation.addTransformer(new security.LegacyLoggingTransformer());
        instrumentation.addTransformer(new Agent());
        System.out.println("[PvP Lab] Agent loaded: " + System.getProperty("lab.role"));
    }

    @Override public byte[] transform(ClassLoader loader, String name, Class<?> cls,
                                      ProtectionDomain domain, byte[] bytes) {
        boolean watchSprint = "pk".equals(name) && "server".equals(System.getProperty("lab.role"));
        if (name == null || !(name.equals("ave") || name.equals("j") || name.equals("pr")
                || name.equals("wn") || name.equals("bcy") || name.equals("bex") || name.equals("hm") || name.equals("bfk")
                || name.equals("en") || name.equals("eo") || name.equals("lh") || name.equals("lm") || name.equals("ek")
                || name.equals("net/minecraft/server/MinecraftServer") || watchSprint)) return null;
        try {
            ClassNode node = new ClassNode();
            new ClassReader(bytes).accept(node, 0);
            boolean changed = false;
            for (MethodNode method : node.methods) {
                if (watchSprint && method.name.equals("d") && method.desc.equals("(Z)V")) {
                    // Observe Entity.setSprinting at the mutation, even if both edges occur in one tick.
                    int previous = method.maxLocals++;
                    InsnList before = new InsnList();
                    before.add(new VarInsnNode(Opcodes.ALOAD, 0));
                    before.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "pk", "aw", "()Z", false));
                    before.add(new VarInsnNode(Opcodes.ISTORE, previous));
                    method.instructions.insert(before);
                    for (AbstractInsnNode ins : method.instructions.toArray()) if (ins.getOpcode() == Opcodes.RETURN) {
                        InsnList after = new InsnList();
                        after.add(new VarInsnNode(Opcodes.ALOAD, 0));
                        after.add(new VarInsnNode(Opcodes.ILOAD, previous));
                        after.add(new VarInsnNode(Opcodes.ALOAD, 0));
                        after.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "pk", "aw", "()Z", false));
                        after.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "lab/PovLink", "serverSprintChanged",
                                "(Ljava/lang/Object;ZZ)V", false));
                        method.instructions.insertBefore(ins, after);
                    }
                    changed = true;
                }
                // The watchdog compares the last tick timestamp with physical wall time.
                if (TrainingClock.ROOM && name.equals("net/minecraft/server/MinecraftServer") && method.name.equals("aL") && method.desc.equals("()J")) {
                    for(AbstractInsnNode ins:method.instructions.toArray()) if(ins.getOpcode()==Opcodes.LRETURN)
                        method.instructions.insertBefore(ins,new MethodInsnNode(Opcodes.INVOKESTATIC,"lab/TrainingClock","wallTimestamp","(J)J",false));
                    changed=true;
                }
                if (TrainingClock.ROOM && name.equals("net/minecraft/server/MinecraftServer") && method.name.equals("run") && method.desc.equals("()V")) {
                    int timers=0, sleeps=0;
                    for(AbstractInsnNode ins:method.instructions.toArray()) if(ins instanceof MethodInsnNode fn) {
                        if(fn.owner.equals(name) && fn.name.equals("az") && fn.desc.equals("()J")) {
                            fn.owner="lab/TrainingClock";fn.name="millis";timers++;
                        } else if(fn.owner.equals("java/lang/Thread") && fn.name.equals("sleep") && fn.desc.equals("(J)V")) {
                            fn.owner="lab/TrainingClock";sleeps++;
                        }
                    }
                    if(timers!=2||sleeps!=1)throw new IllegalStateException("Unexpected 1.8.9 server scheduler");
                    changed=true;
                }
                if(name.equals("en")&&method.name.equals("decode")&&method.desc.equals("(Lio/netty/channel/ChannelHandlerContext;Lio/netty/buffer/ByteBuf;Ljava/util/List;)V")){
                    method.instructions.insert(objects("lab/ArchiveWire","before",1,2));
                    for(AbstractInsnNode i:method.instructions.toArray())if(i.getOpcode()==Opcodes.RETURN)method.instructions.insertBefore(i,objects("lab/ArchiveWire","after",3));changed=true;
                }else if(name.equals("eo")&&method.desc.equals("(Lio/netty/channel/ChannelHandlerContext;Lff;Lio/netty/buffer/ByteBuf;)V")){
                    for(AbstractInsnNode i:method.instructions.toArray())if(i.getOpcode()==Opcodes.RETURN)method.instructions.insertBefore(i,objects("lab/ArchiveWire","out",1,2,3));changed=true;
                }
                if(name.equals("lm")&&method.name.equals("a")&&java.util.Set.of("(Lja;)V","(Liy;)V","(Lir;)V","(Lin;)V","(Lik;)V").contains(method.desc)){
                    InsnList gate=new InsnList();LabelNode normal=new LabelNode();gate.add(new VarInsnNode(Opcodes.ALOAD,0));gate.add(new VarInsnNode(Opcodes.ALOAD,1));
                    gate.add(new MethodInsnNode(Opcodes.INVOKESTATIC,"lab/ReplayToolbar","intercept","(Ljava/lang/Object;Ljava/lang/Object;)Z",false));gate.add(new JumpInsnNode(Opcodes.IFEQ,normal));gate.add(new InsnNode(Opcodes.RETURN));gate.add(normal);method.instructions.insert(gate);changed=true;
                }
                if (name.equals("ave") && method.name.equals("s") && method.desc.equals("()V")) {
                    method.instructions.insert(callThis("lab/Client", "tick")); changed = true;
                } else if (name.equals("ave") && method.name.equals("av") && method.desc.equals("()V")) {
                    method.instructions.insert(callThis("lab/Client", "frame")); changed = true;
                } else if (name.equals("ave") && method.name.equals("aw") && method.desc.equals("()V")) {
                    method.instructions.insert(callThis("lab/ScienceTrace","clickBefore"));
                    method.instructions.insert(callThis("lab/Client","inputWordConsumed"));
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
                    method.instructions.insert(serverClock("tick_start"));
                    for (AbstractInsnNode ins : method.instructions.toArray()) if (ins.getOpcode() == Opcodes.RETURN){
                        method.instructions.insertBefore(ins, serverClock("tick_end"));
                        method.instructions.insertBefore(ins, callThis("lab/Referee", "tick"));
                    }
                    changed = true;
                } else if (name.equals("net/minecraft/server/MinecraftServer") && method.name.equals("B") && method.desc.equals("()V")) {
                    int locks=0,unlocks=0,worlds=0,entities=0,trackers=0;
                    for(AbstractInsnNode ins:method.instructions.toArray()){
                        if(ins.getOpcode()==Opcodes.MONITORENTER){method.instructions.insert(ins,serverClock("drain_start"));locks++;}
                        else if(ins.getOpcode()==Opcodes.MONITOREXIT){method.instructions.insertBefore(ins,serverClock("drain_end"));unlocks++;}
                        else if(ins instanceof MethodInsnNode call&&call.desc.equals("()V")){
                            String phase=null;
                            if(call.owner.equals("le")&&call.name.equals("c")){phase="world_tick";worlds++;}
                            if(call.owner.equals("le")&&call.name.equals("i")){phase="entity_tick";entities++;}
                            if(call.owner.equals("la")&&call.name.equals("a")){phase="tracker_tick";trackers++;}
                            if(phase!=null){method.instructions.insertBefore(ins,serverClock(phase+"_start"));method.instructions.insert(ins,serverClock(phase+"_end"));}
                        }
                    }
                    if(locks!=1||unlocks!=2||worlds!=1||entities!=1||trackers!=1)
                        throw new IllegalStateException("Pinned server clock shape changed: "+locks+","+unlocks+","+worlds+","+entities+","+trackers);
                    changed = true;
                } else if(name.equals("net/minecraft/server/MinecraftServer")&&method.desc.equals("(Ljava/util/concurrent/Callable;)Lcom/google/common/util/concurrent/ListenableFuture;")){
                    int adds=0;
                    for(AbstractInsnNode ins:method.instructions.toArray())if(ins instanceof MethodInsnNode call&&
                            call.owner.equals("java/util/Queue")&&call.name.equals("add")&&call.desc.equals("(Ljava/lang/Object;)Z")){
                        method.instructions.insert(ins,new MethodInsnNode(Opcodes.INVOKESTATIC,"lab/ServerReplicationTrace","queued","()V",false));adds++;
                    }
                    if(adds!=1)throw new IllegalStateException("Pinned server task enqueue changed: "+adds);
                    changed=true;
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
                    for(AbstractInsnNode ins:method.instructions.toArray())if(ins.getOpcode()==Opcodes.RETURN){
                        method.instructions.insertBefore(ins,callThis("lab/ScienceTrace","movementAfter"));
                        method.instructions.insertBefore(ins,callThis("lab/Client","inputWordMoved"));
                    }
                    changed=true;
                } else if(name.equals("bex")&&method.name.equals("m")&&method.desc.equals("()V")){
                    method.instructions.insert(callThis("lab/ScienceTrace","interpolationBefore"));
                    for(AbstractInsnNode ins:method.instructions.toArray())if(ins.getOpcode()==Opcodes.RETURN)method.instructions.insertBefore(ins,callThis("lab/ScienceTrace","interpolationAfter"));changed=true;
                } else if(name.equals("bex")&&method.name.equals("a")&&method.desc.equals("(DDDFFIZ)V")){
                    for(AbstractInsnNode ins:method.instructions.toArray())if(ins.getOpcode()==Opcodes.RETURN)method.instructions.insertBefore(ins,callThis("lab/ScienceTrace","remoteUpdate"));changed=true;
                } else if(name.equals("hm")&&method.name.equals("<init>")&&method.desc.equals("(Lpk;)V")){
                    for(AbstractInsnNode ins:method.instructions.toArray())if(ins.getOpcode()==Opcodes.RETURN){
                        InsnList after=callPair("lab/ServerScienceTrace","velocityCreated");method.instructions.insertBefore(ins,after);
                    }changed=true;
                } else if(name.equals("wn")&&method.name.equals("f")&&method.desc.equals("(Lpk;)V")){
                    if("server".equals(System.getProperty("lab.role"))){
                        method.instructions.insert(callPair("lab/CombatTuning","before"));
                        int velocitySites=0, velocitySends=0;
                        for(AbstractInsnNode ins:method.instructions.toArray()){
                            if(ins instanceof TypeInsnNode allocation&&allocation.getOpcode()==Opcodes.NEW&&allocation.desc.equals("hm")){
                                InsnList tune=new InsnList();tune.add(new VarInsnNode(Opcodes.ALOAD,1));
                                tune.add(new MethodInsnNode(Opcodes.INVOKESTATIC,"lab/CombatTuning","outgoing","(Ljava/lang/Object;)V",false));
                                method.instructions.insertBefore(ins,tune);velocitySites++;
                            }
                            if(ins instanceof MethodInsnNode call && call.owner.equals("lm") &&
                                    call.name.equals("a") && call.desc.equals("(Lff;)V")){
                                // Keep the original vanilla send. The same handler and S12
                                // are visible to the two drill-only callbacks around it.
                                InsnList bracket=new InsnList();
                                bracket.add(new InsnNode(Opcodes.DUP2));
                                bracket.add(new VarInsnNode(Opcodes.ALOAD,0));
                                bracket.add(new MethodInsnNode(Opcodes.INVOKESTATIC,"lab/JumpResetFeedback","beforeVelocity",
                                    "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V",false));
                                bracket.add(new InsnNode(Opcodes.DUP2));
                                method.instructions.insertBefore(ins,bracket);
                                method.instructions.insert(ins,new MethodInsnNode(Opcodes.INVOKESTATIC,"lab/JumpResetFeedback","afterVelocity",
                                    "(Ljava/lang/Object;Ljava/lang/Object;)V",false));
                                velocitySends++;
                            }
                            if(ins.getOpcode()==Opcodes.RETURN)method.instructions.insertBefore(ins,new MethodInsnNode(Opcodes.INVOKESTATIC,"lab/CombatTuning","after","()V",false));
                        }
                        if(velocitySites!=1||velocitySends!=1)
                            throw new IllegalStateException("Pinned player fresh velocity sites changed: "+velocitySites+"/"+velocitySends);
                    }
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
                    before.add(callPair("lab/ServerScienceTrace","attackBefore"));method.instructions.insert(before);
                    for(AbstractInsnNode ins:method.instructions.toArray())if(ins.getOpcode()==Opcodes.RETURN){
                        InsnList after=new InsnList();after.add(new VarInsnNode(Opcodes.ALOAD,0));after.add(new VarInsnNode(Opcodes.ALOAD,1));
                        after.add(new MethodInsnNode(Opcodes.INVOKESTATIC,"lab/Client","attackAfter","(Ljava/lang/Object;Ljava/lang/Object;)V",false));
                        after.add(callPair("lab/ServerScienceTrace","attackAfter"));method.instructions.insertBefore(ins,after);
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
                    method.instructions.insert(callPair("lab/ServerReplicationTrace","send"));
                    InsnList setup=new InsnList();LabelNode normal=new LabelNode();
                    setup.add(new VarInsnNode(Opcodes.ALOAD,0));setup.add(new VarInsnNode(Opcodes.ALOAD,1));
                    setup.add(new MethodInsnNode(Opcodes.INVOKESTATIC,"lab/SeededStartServer","suppressOutput","(Ljava/lang/Object;Ljava/lang/Object;)Z",false));
                    setup.add(new JumpInsnNode(Opcodes.IFEQ,normal));setup.add(new InsnNode(Opcodes.RETURN));setup.add(normal);
                    method.instructions.insert(setup);changed=true;
                }else if(name.equals("lm")&&method.name.equals("a")&&method.desc.equals("(Lim;)V")){
                    method.instructions.insert(callPair("lab/PovLink","receive"));changed=true;
                }else if(name.equals("lm")&&method.name.equals("a")&&method.desc.equals("(Lii;)V")){
                    for(AbstractInsnNode ins:method.instructions.toArray())if(ins.getOpcode()==Opcodes.RETURN)
                        method.instructions.insertBefore(ins,callPair("lab/JumpResetFeedback","confirm"));
                    changed=true;
                }else if(name.equals("lm")&&method.name.equals("a")&&method.desc.equals("(DDDFFLjava/util/Set;)V")){
                    method.instructions.insert(callThis("lab/JumpResetFeedback","corrected"));changed=true;
                    method.instructions.insert(callThis("lab/MmcKnockback","corrected"));
                }else if(name.equals("lm")&&method.name.equals("a")&&java.util.Set.of("(Lip;)V","(Lin;)V","(Lis;)V").contains(method.desc)){
                    if(method.desc.equals("(Lip;)V")){
                        int accepted=0;
                        for(AbstractInsnNode ins:method.instructions.toArray())if(ins instanceof MethodInsnNode call &&
                                call.owner.equals("lf")&&call.name.equals("a")&&call.desc.equals("(DZ)V")){
                            method.instructions.insert(ins,callPair("lab/JumpResetFeedback","moved"));accepted++;
                            method.instructions.insert(ins,callPair("lab/MmcKnockback","playerMove"));
                        }
                        if(accepted!=1)throw new IllegalStateException("Pinned accepted player movement site changed: "+accepted);
                    }
                    method.instructions.insert(callPair("lab/ServerReplicationTrace","input"));
                    method.instructions.insert(serverHandler(false));
                    for(AbstractInsnNode ins:method.instructions.toArray())if(ins.getOpcode()==Opcodes.RETURN)
                        method.instructions.insertBefore(ins,serverHandler(true));
                    InsnList setup=new InsnList();LabelNode normal=new LabelNode();
                    setup.add(new VarInsnNode(Opcodes.ALOAD,0));setup.add(new VarInsnNode(Opcodes.ALOAD,1));
                    setup.add(new MethodInsnNode(Opcodes.INVOKESTATIC,"lab/SeededStartServer","suppressInput","(Ljava/lang/Object;Ljava/lang/Object;)Z",false));
                    setup.add(new JumpInsnNode(Opcodes.IFEQ,normal));setup.add(new InsnNode(Opcodes.RETURN));setup.add(normal);
                    method.instructions.insert(setup);
                    changed=true;
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
    private static InsnList objects(String owner,String name,int... locals){InsnList out=new InsnList();String desc="(";for(int n:locals){out.add(new VarInsnNode(Opcodes.ALOAD,n));desc+="Ljava/lang/Object;";}out.add(new MethodInsnNode(Opcodes.INVOKESTATIC,owner,name,desc+")V",false));return out;}
    private static InsnList callThis(String owner, String method) {
        InsnList list = new InsnList(); list.add(new VarInsnNode(Opcodes.ALOAD, 0));
        list.add(new MethodInsnNode(Opcodes.INVOKESTATIC, owner, method, "(Ljava/lang/Object;)V", false));
        return list;
    }
    private static InsnList serverClock(String phase){
        InsnList out=new InsnList();out.add(new VarInsnNode(Opcodes.ALOAD,0));out.add(new LdcInsnNode(phase));
        out.add(new MethodInsnNode(Opcodes.INVOKESTATIC,"lab/ServerReplicationTrace","clock","(Ljava/lang/Object;Ljava/lang/String;)V",false));return out;
    }
    private static InsnList serverHandler(boolean after){
        InsnList out=new InsnList();out.add(new VarInsnNode(Opcodes.ALOAD,0));out.add(new VarInsnNode(Opcodes.ALOAD,1));
        out.add(new InsnNode(after?Opcodes.ICONST_1:Opcodes.ICONST_0));
        out.add(new MethodInsnNode(Opcodes.INVOKESTATIC,"lab/ServerReplicationTrace","handler","(Ljava/lang/Object;Ljava/lang/Object;Z)V",false));return out;
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
        list.add(new MethodInsnNode(Opcodes.INVOKESTATIC,method.equals("tracker")?"lab/ServerReplicationTrace":"lab/ReplicationTrace",method,"(Ljava/lang/Object;Z)V",false));return list;
    }
}
