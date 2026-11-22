package practice.bridge;

import net.minecraft.launchwrapper.IClassTransformer;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Read-only packet/input observers; preserves original instructions and return values.
 * Observation takes time; capture timestamps bracket work rather than promising zero overhead.
 * Also fixes the native outbound queue race and applies client presentation options. */
public final class PracticeTransformer implements IClassTransformer {
    private static final String HOOK="practice/bridge/ObserverHooks";
    public byte[] transform(String name,String transformed,byte[] bytes){
        if(bytes==null)return null;
        bytes=BotTransformer.transform(transformed,bytes);
        if(transformed.equals("net.minecraft.util.Timer")){
            ClassNode timer=new ClassNode();new ClassReader(bytes).accept(timer,0);
            int reads=0,clockReads=0;
            for(MethodNode m:timer.methods)if(m.desc.equals("()V") && named(m,"func_74275_a","updateTimer","a"))
                for(AbstractInsnNode ins:m.instructions.toArray())if(ins instanceof FieldInsnNode){
                    FieldInsnNode f=(FieldInsnNode)ins;
                    if(f.getOpcode()==Opcodes.GETFIELD && f.owner.equals(timer.name) && f.desc.equals("F") &&
                            (f.name.equals("field_74278_d")||f.name.equals("timerSpeed")||f.name.equals("d"))){
                        m.instructions.insert(ins,new MethodInsnNode(Opcodes.INVOKESTATIC,"practice/bridge/TrainingTimer","scale","(F)F",false));
                        reads++;
                    }
                    // updateTimer multiplies monotonic elapsed seconds by a smoothed
                    // estimate of the LWJGL/system clock ratio. Both sides of a
                    // local training duel use the same host's monotonic seconds.
                    if(f.getOpcode()==Opcodes.GETFIELD && f.owner.equals(timer.name) && f.desc.equals("D") &&
                            (f.name.equals("field_74283_j")||f.name.equals("timeSyncAdjustment")||f.name.equals("j")) &&
                            ins.getNext()!=null && ins.getNext().getOpcode()==Opcodes.DMUL){
                        m.instructions.insert(ins,new MethodInsnNode(Opcodes.INVOKESTATIC,"practice/bridge/TrainingTimer","clockAdjustment","(D)D",false));
                        clockReads++;
                    }
                }
            if(reads!=1 || clockReads!=1)throw new IllegalStateException("Unexpected Minecraft 1.8.9 Timer.updateTimer");
            ClassWriter out=new ClassWriter(0);timer.accept(out);return out.toByteArray();
        }
        if(transformed.equals("net.minecraft.client.gui.GuiIngame"))return replayHotbar(bytes);
        if(transformed.equals("net.minecraft.client.renderer.ItemRenderer"))return replayHand(firstPersonUse(bytes));
        if(transformed.equals("net.minecraft.client.renderer.EntityRenderer"))return replayWorldPartial(hurtCamera(sneakCamera(bytes)));
        if(transformed.equals("net.minecraft.client.entity.AbstractClientPlayer"))return movementFov(bytes);
        if(transformed.equals("net.minecraft.client.multiplayer.PlayerControllerMP") &&
                !"bot".equals(System.getProperty("practice.role","human")))return bowRelease(bytes);
        boolean network=transformed.equals("net.minecraft.network.NetworkManager"),
            decoder=transformed.equals("net.minecraft.util.MessageDeserializer"),
            encoder=transformed.equals("net.minecraft.util.MessageSerializer"),
            handler=transformed.equals("net.minecraft.client.network.NetHandlerPlayClient"),
            minecraft=transformed.equals("net.minecraft.client.Minecraft"),
            player=transformed.equals("net.minecraft.entity.player.EntityPlayer"),
            living=transformed.equals("net.minecraft.entity.EntityLivingBase");
        if(!network&&!decoder&&!encoder&&!handler&&!minecraft&&!player&&!living)return bytes;
        ClassNode node=new ClassNode();new ClassReader(bytes).accept(node,0);int hooks=0;
        for(MethodNode m:node.methods){
            if((m.access&(Opcodes.ACC_SYNTHETIC|Opcodes.ACC_BRIDGE|Opcodes.ACC_STATIC))!=0)continue;
            if(living){
                String kind=m.desc.equals("()V")&&named(m,"func_70664_aZ","jump","bF")?"jump":
                    m.desc.equals("(FF)V")&&named(m,"func_70612_e","moveEntityWithHeading","g")?"travel":null;
                if(kind!=null){
                    m.instructions.insert(flag(kind,0,false));
                    for(AbstractInsnNode i:m.instructions.toArray())if(i.getOpcode()==Opcodes.RETURN)
                        m.instructions.insertBefore(i,flag(kind,0,true));
                    hooks++;
                }
            }else if(player&&(m.name.equals("func_145748_c_")||m.name.equals("getDisplayName")||m.name.equals("f_"))&&m.desc.startsWith("()L")){
                // Replace only the presentation component. Native getName, profiles,
                // UUIDs, target lookup, telemetry and all original work stay intact.
                for(AbstractInsnNode i:m.instructions.toArray())if(i.getOpcode()==Opcodes.ARETURN){
                    InsnList alias=new InsnList();alias.add(new VarInsnNode(Opcodes.ALOAD,0));
                    alias.add(new MethodInsnNode(Opcodes.INVOKESTATIC,"practice/bridge/PlayerLabels","displayName","(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;",false));
                    alias.add(new TypeInsnNode(Opcodes.CHECKCAST,Type.getReturnType(m.desc).getInternalName()));
                    m.instructions.insertBefore(i,alias);hooks++;
                }
            }else if(network&&m.name.equals("channelActive")){
                for(AbstractInsnNode i:m.instructions.toArray())if(i.getOpcode()==Opcodes.RETURN){m.instructions.insertBefore(i,call("connected",0,1));hooks++;}
            }else if(network&&m.desc.equals("()V")&&named(m,"func_150733_h","flushOutboundQueue","m")){
                lockOutboundDrain(m);
            }else if(decoder&&m.name.equals("decode")&&m.desc.contains("Lio/netty/buffer/ByteBuf;")){
                m.instructions.insert(call("wireBefore",1,2));
                for(AbstractInsnNode i:m.instructions.toArray())if(i.getOpcode()==Opcodes.RETURN)m.instructions.insertBefore(i,call("wireAfter",3));hooks++;
            }else if(encoder&&!m.name.startsWith("<")&&m.desc.startsWith("(Lio/netty/channel/ChannelHandlerContext;")&&m.desc.endsWith("Lio/netty/buffer/ByteBuf;)V")){
                for(AbstractInsnNode i:m.instructions.toArray())if(i.getOpcode()==Opcodes.RETURN)m.instructions.insertBefore(i,call("wireOut",1,2,3));hooks++;
            }else if(handler&&m.desc.endsWith(")V")&&Type.getArgumentTypes(m.desc).length==1 && !m.name.startsWith("<")){
                String arg=Type.getArgumentTypes(m.desc)[0].getClassName();
                // All play-handler packet methods have one clientbound packet argument. Exclude
                // addToSendQueue(Packet) (`ff`) and onDisconnect(IChatComponent) (`eu`).
                if((arg.startsWith("net.minecraft.network.play.server.") || (arg.matches("[a-z]{2,3}") && !arg.equals("ff") && !arg.equals("eu")))
                        && !named(m,"func_147297_a","addToSendQueue","func_147297_a")
                        && !named(m,"func_147231_a","onDisconnect","func_147231_a")){
                    m.instructions.insert(flag("apply",1,false));
                    for(AbstractInsnNode i:m.instructions.toArray())if(i.getOpcode()==Opcodes.RETURN)m.instructions.insertBefore(i,flag("apply",1,true));hooks++;
                }
            }else if(minecraft&&m.desc.equals("(Ljava/util/concurrent/Callable;)Lcom/google/common/util/concurrent/ListenableFuture;")){
                int adds=0;
                for(AbstractInsnNode i:m.instructions.toArray())if(i instanceof MethodInsnNode){
                    MethodInsnNode c=(MethodInsnNode)i;
                    if(c.owner.equals("java/util/Queue")&&c.name.equals("add")&&c.desc.equals("(Ljava/lang/Object;)Z")){
                        m.instructions.insert(i,call("queued"));adds++;
                    }
                }
                if(adds!=1)throw new IllegalStateException("Practice task enqueue hook changed: "+adds);
                hooks++;
            }else if(minecraft&&named(m,"func_71353_a","loadWorld","a")
                    &&(m.desc.equals("(Lnet/minecraft/client/multiplayer/WorldClient;Ljava/lang/String;)V")
                        ||m.desc.equals("(Lbdb;Ljava/lang/String;)V"))){
                for(AbstractInsnNode instruction:m.instructions.toArray())if(instruction instanceof MethodInsnNode){
                    MethodInsnNode call=(MethodInsnNode)instruction;
                    if(call.getOpcode()==Opcodes.INVOKESTATIC&&call.owner.equals("java/lang/System")
                            &&call.name.equals("gc")&&call.desc.equals("()V")){
                        m.instructions.set(call,new MethodInsnNode(Opcodes.INVOKESTATIC,HOOK,"worldLoadGc","()V",false));
                        hooks++;
                    }
                }
            }else if(minecraft&&m.desc.equals("()V")&&(m.name.equals("func_71411_J")||m.name.equals("runGameLoop")||m.name.equals("av"))){
                int locks=0,unlocks=0;
                for(AbstractInsnNode i:m.instructions.toArray()){
                    if(i.getOpcode()==Opcodes.MONITORENTER){m.instructions.insert(i,clock("start"));locks++;}
                    if(i.getOpcode()==Opcodes.MONITOREXIT){m.instructions.insertBefore(i,clock("end"));unlocks++;}
                }
                if(locks!=1||unlocks!=2)throw new IllegalStateException("Practice task drain hook changed: "+locks+","+unlocks);
                hooks++;
            }else if(minecraft&&m.desc.equals("()V")&&named(m,"func_71407_l","runTick","s")){
                if(!"bot".equals(System.getProperty("practice.role","human"))){
                    m.instructions.insertBefore(BotTransformer.attackLoopEntry(m,node.name),call("attackSlot"));
                    hooks++;
                }
            }else if(minecraft&&m.desc.equals("()V")){
                String kind=m.name.equals("func_147116_af")||m.name.equals("clickMouse")||m.name.equals("aw")?"attack_click":
                    m.name.equals("func_147121_ag")||m.name.equals("rightClickMouse")||m.name.equals("ax")?"use_click":null;
                if(kind!=null){
                    m.instructions.insert(input(kind,false));
                    for(AbstractInsnNode i:m.instructions.toArray())if(i.getOpcode()==Opcodes.RETURN)m.instructions.insertBefore(i,input(kind,true));hooks++;
                }
            }
        }
        if(hooks==0)throw new IllegalStateException("Practice observer hooks missing for "+transformed);
        ClassWriter out=new ClassWriter(ClassWriter.COMPUTE_MAXS);node.accept(out);
        System.out.println("[PracticeBridge] Observer hooks: "+transformed+" ("+hooks+")");return out.toByteArray();
    }
    private static byte[] replayHotbar(byte[] bytes){
        ClassNode node=new ClassNode();new ClassReader(bytes).accept(node,0);
        int lookups=0;
        for(MethodNode method:node.methods){
            if(!named(method,"func_180479_a","renderTooltip","a")||
                    !(method.desc.equals("(Lnet/minecraft/client/gui/ScaledResolution;F)V")||method.desc.equals("(Lavr;F)V")))continue;
            for(AbstractInsnNode insn:method.instructions.toArray()){
                if(!(insn instanceof MethodInsnNode))continue;
                MethodInsnNode getter=(MethodInsnNode)insn;
                if(!(getter.name.equals("func_175606_aa")||getter.name.equals("getRenderViewEntity")||getter.name.equals("ac"))
                        ||!(getter.desc.equals("()Lnet/minecraft/entity/Entity;")||getter.desc.equals("()Lpk;")))continue;
                InsnList owner=new InsnList();
                owner.add(new MethodInsnNode(Opcodes.INVOKESTATIC,"practice/bridge/ServerReplayHotbar","owner","(Ljava/lang/Object;)Ljava/lang/Object;",false));
                owner.add(new TypeInsnNode(Opcodes.CHECKCAST,Type.getReturnType(getter.desc).getInternalName()));
                method.instructions.insert(insn,owner);lookups++;
            }
        }
        if(lookups==0)throw new IllegalStateException("Replay hotbar camera lookup missing");
        ClassWriter writer=new ClassWriter(0);node.accept(writer);return writer.toByteArray();
    }
    private static boolean named(MethodNode m,String srg,String mcp,String obf){
        return m.name.equals(srg)||m.name.equals(mcp)||m.name.equals(obf);
    }
    private static byte[] bowRelease(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        for (MethodNode m : node.methods) {
            if (!named(m, "func_78766_c", "onStoppedUsingItem", "c")
                    || !(m.desc.equals("(Lnet/minecraft/entity/player/EntityPlayer;)V") || m.desc.equals("(Lwn;)V"))) continue;
            InsnList hook = new InsnList();
            hook.add(new VarInsnNode(Opcodes.ALOAD, 1));
            hook.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "practice/bridge/BowAim", "beforeRelease",
                    "(Ljava/lang/Object;)V", false));
            m.instructions.insert(hook);
            ClassWriter output = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            node.accept(output);
            return output.toByteArray();
        }
        throw new IllegalStateException("Minecraft bow release hook missing");
    }
    private static void lockOutboundDrain(MethodNode method) {
        // sendPacket and processReceivedPackets can drain this queue concurrently.
        // Use the same exclusive lock as enqueue so isEmpty, poll and dispatch stay ordered.
        String lock = "java/util/concurrent/locks/ReentrantReadWriteLock";
        String read = lock + "$ReadLock";
        String write = lock + "$WriteLock";
        int rewritten = 0;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (!(instruction instanceof MethodInsnNode)) continue;
            MethodInsnNode call = (MethodInsnNode) instruction;
            if (call.owner.equals(lock) && call.name.equals("readLock") && call.desc.equals("()L" + read + ";")) {
                call.name = "writeLock";
                call.desc = "()L" + write + ";";
                rewritten++;
            } else if (call.owner.equals(read) && call.desc.equals("()V")
                    && (call.name.equals("lock") || call.name.equals("unlock"))) {
                call.owner = write;
                rewritten++;
            }
        }
        if (rewritten < 3) throw new IllegalStateException("Outbound drain lock hook incomplete: " + rewritten);
    }
    private static AbstractInsnNode previousCode(AbstractInsnNode instruction){
        do{instruction=instruction.getPrevious();}while(instruction!=null&&instruction.getOpcode()<0);
        return instruction;
    }
    private static byte[] replayWorldPartial(byte[] bytes){
        ClassNode node=new ClassNode();new ClassReader(bytes).accept(node,0);
        for(MethodNode method:node.methods){
            if(!"(FJ)V".equals(method.desc)||!named(method,"func_78471_a","renderWorld","b"))continue;
            InsnList paused=new InsnList();
            paused.add(new VarInsnNode(Opcodes.FLOAD,1));
            paused.add(new MethodInsnNode(Opcodes.INVOKESTATIC,HOOK,"replayWorldPartial","(F)F",false));
            paused.add(new VarInsnNode(Opcodes.FSTORE,1));
            method.instructions.insert(paused);
            ClassWriter out=new ClassWriter(ClassWriter.COMPUTE_MAXS);node.accept(out);
            return out.toByteArray();
        }
        return bytes;
    }
    private static byte[] movementFov(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        for (MethodNode method : node.methods) {
            if (!"()F".equals(method.desc) || !named(method, "func_175156_o", "getFovModifier", "o")) continue;
            // The camera multiplies the flying FOV by (speed / walkSpeed + 1) / 2 before bow zoom.
            for (AbstractInsnNode multiply : method.instructions.toArray()) {
                if (multiply.getOpcode() != Opcodes.DMUL) continue;
                AbstractInsnNode divide = previousCode(multiply);
                if (divide == null || divide.getOpcode() != Opcodes.DDIV) continue;
                AbstractInsnNode two = previousCode(divide);
                if (!(two instanceof LdcInsnNode) || !Double.valueOf(2).equals(((LdcInsnNode)two).cst)) continue;
                AbstractInsnNode add = previousCode(two);
                if (add == null || add.getOpcode() != Opcodes.DADD) continue;
                method.instructions.insertBefore(multiply, new MethodInsnNode(Opcodes.INVOKESTATIC,
                        "practice/bridge/MovementFov", "multiplier", "(D)D", false));
                ClassWriter out = new ClassWriter(0);
                node.accept(out);
                System.out.println("[PracticeBridge] Movement FOV toggle hooked");
                return out.toByteArray();
            }
        }
        System.out.println("[PracticeBridge] Movement FOV toggle unavailable for this player renderer");
        return bytes;
    }
    private static byte[] hurtCamera(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        for (MethodNode method : node.methods) {
            if (!"(F)V".equals(method.desc)
                    || !named(method, "func_78482_e", "hurtCameraEffect", "d")) continue;
            AbstractInsnNode timer = null;
            for (AbstractInsnNode instruction : method.instructions.toArray()) {
                if (instruction.getOpcode() != Opcodes.FSUB) continue;
                AbstractInsnNode partial = previousCode(instruction);
                if (!(partial instanceof VarInsnNode) || partial.getOpcode() != Opcodes.FLOAD
                        || ((VarInsnNode) partial).var != 1) continue;
                AbstractInsnNode cast = previousCode(partial);
                if (cast == null || cast.getOpcode() != Opcodes.I2F) continue;
                AbstractInsnNode read = previousCode(cast);
                if (!(read instanceof FieldInsnNode) || read.getOpcode() != Opcodes.GETFIELD) continue;
                FieldInsnNode field = (FieldInsnNode) read;
                if (!"I".equals(field.desc)
                        || !(field.owner.equals("net/minecraft/entity/EntityLivingBase") || field.owner.equals("pr"))
                        || !(field.name.equals("field_70737_aN") || field.name.equals("hurtTime") || field.name.equals("au"))) continue;
                if (timer != null) {
                    timer = null;
                    break;
                }
                timer = instruction;
            }
            if (timer == null) continue;
            method.instructions.insert(timer, new MethodInsnNode(Opcodes.INVOKESTATIC,
                    "practice/bridge/HurtCamera", "damageTime", "(F)F", false));
            ClassWriter out = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            node.accept(out);
            System.out.println("[PracticeBridge] Damage camera toggle hooked");
            return out.toByteArray();
        }
        System.out.println("[PracticeBridge] Damage camera toggle unavailable for this renderer");
        return bytes;
    }
    private static byte[] sneakCamera(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        boolean cameraHooked = false;
        for (MethodNode method : node.methods) {
            if (!"(F)V".equals(method.desc)) continue;
            boolean camera = named(method, "func_78467_g", "orientCamera", "f");
            boolean directions = named(method, "func_175067_i", "renderWorldDirections", "h");
            if (!camera && !directions) continue;
            MethodInsnNode eyeHeight = null;
            for (AbstractInsnNode instruction : method.instructions.toArray()) {
                if (!(instruction instanceof MethodInsnNode)) continue;
                MethodInsnNode call = (MethodInsnNode) instruction;
                if (call.getOpcode() != Opcodes.INVOKEVIRTUAL || !"()F".equals(call.desc)
                        || !(call.owner.equals("net/minecraft/entity/Entity") || call.owner.equals("pk"))
                        || !(call.name.equals("func_70047_e") || call.name.equals("getEyeHeight") || call.name.equals("aS"))) continue;
                if (eyeHeight != null) {
                    eyeHeight = null;
                    break;
                }
                eyeHeight = call;
            }
            if (eyeHeight == null) continue;
            method.instructions.insertBefore(eyeHeight, new InsnNode(Opcodes.DUP));
            InsnList hook = new InsnList();
            hook.add(new VarInsnNode(Opcodes.FLOAD, 1));
            hook.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "practice/bridge/OldSneakAnimation",
                    "eyeHeight", "(Ljava/lang/Object;FF)F", false));
            method.instructions.insert(eyeHeight, hook);
            cameraHooked |= camera;
        }
        if (!cameraHooked) {
            System.out.println("[PracticeBridge] 1.7 sneak camera unavailable for this renderer");
            return bytes;
        }
        ClassWriter out = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(out);
        System.out.println("[PracticeBridge] 1.7 sneak camera hooked");
        return out.toByteArray();
    }
    private static byte[] firstPersonUse(byte[] bytes){
        ClassNode node=new ClassNode();new ClassReader(bytes).accept(node,0);
        int sites=0;
        for(MethodNode m:node.methods){
            if(!m.desc.equals("(F)V")||!named(m,"func_78440_a","renderItemInFirstPerson","a"))continue;
            // 1.8 supplies zero swing to the common pose for each item-use
            // branch: none, eat/drink, block and bow. Ordinary swings already
            // pass their progress and keep their original transformation.
            for(AbstractInsnNode i:m.instructions.toArray()){
                if(!(i instanceof MethodInsnNode))continue;
                MethodInsnNode transform=(MethodInsnNode)i;
                if(!node.name.equals(transform.owner)||!transform.desc.equals("(FF)V")||
                        !(transform.name.equals("func_178096_b")||transform.name.equals("transformFirstPersonItem")||transform.name.equals("b")))continue;
                AbstractInsnNode zero=previousCode(i);
                if(zero==null||zero.getOpcode()!=Opcodes.FCONST_0)continue;
                AbstractInsnNode equip=previousCode(zero);
                if(equip==null||equip.getOpcode()!=Opcodes.FLOAD)continue;
                AbstractInsnNode receiver=previousCode(equip);
                if(!(receiver instanceof VarInsnNode)||receiver.getOpcode()!=Opcodes.ALOAD||((VarInsnNode)receiver).var!=0)continue;
                InsnList swing=new InsnList();
                swing.add(new VarInsnNode(Opcodes.FLOAD,1));
                swing.add(new MethodInsnNode(Opcodes.INVOKESTATIC,"practice/bridge/OldBlockAnimation","swing","(FF)F",false));
                m.instructions.insertBefore(i,swing);
                sites++;
            }
        }
        if(sites!=4){
            System.out.println("[PracticeBridge] 1.7 item-use swing unavailable for this renderer ("+sites+"/4 poses)");
            return bytes;
        }
        ClassWriter out=new ClassWriter(ClassWriter.COMPUTE_MAXS);node.accept(out);
        System.out.println("[PracticeBridge] 1.7 item-use swing hooked ("+sites+" poses)");
        return out.toByteArray();
    }
    private static byte[] replayHand(byte[] bytes){
        ClassNode node=new ClassNode();new ClassReader(bytes).accept(node,0);
        for(MethodNode method:node.methods){
            if(!method.desc.equals("(F)V")||!named(method,"func_78440_a","renderItemInFirstPerson","a"))continue;
            for(AbstractInsnNode instruction:method.instructions.toArray()){
                if(!(instruction instanceof MethodInsnNode))continue;
                MethodInsnNode call=(MethodInsnNode)instruction;
                if(!call.desc.equals("()Z")||!(call.name.equals("func_82150_aj")||call.name.equals("isInvisible")||call.name.equals("ax")))continue;
                method.instructions.insert(call,new MethodInsnNode(Opcodes.INVOKESTATIC,HOOK,"hideEmptyHand","(Z)Z",false));
                ClassWriter out=new ClassWriter(ClassWriter.COMPUTE_MAXS);node.accept(out);
                System.out.println("[PracticeBridge] Offline replay hand hooked");
                return out.toByteArray();
            }
        }
        return bytes;
    }
    private static InsnList call(String name,int... locals){
        InsnList out=new InsnList();String desc="(";
        for(int local:locals){out.add(new VarInsnNode(Opcodes.ALOAD,local));desc+="Ljava/lang/Object;";}
        out.add(new MethodInsnNode(Opcodes.INVOKESTATIC,HOOK,name,desc+")V",false));return out;
    }
    private static InsnList flag(String name,int local,boolean after){
        InsnList out=new InsnList();out.add(new VarInsnNode(Opcodes.ALOAD,local));out.add(new InsnNode(after?Opcodes.ICONST_1:Opcodes.ICONST_0));
        out.add(new MethodInsnNode(Opcodes.INVOKESTATIC,HOOK,name,"(Ljava/lang/Object;Z)V",false));return out;
    }
    private static InsnList input(String kind,boolean after){
        InsnList out=new InsnList();out.add(new LdcInsnNode(kind));out.add(new InsnNode(after?Opcodes.ICONST_1:Opcodes.ICONST_0));
        out.add(new MethodInsnNode(Opcodes.INVOKESTATIC,HOOK,"input","(Ljava/lang/String;Z)V",false));return out;
    }
    private static InsnList clock(String phase){
        InsnList out=new InsnList();out.add(new LdcInsnNode(phase));
        out.add(new MethodInsnNode(Opcodes.INVOKESTATIC,HOOK,"clock","(Ljava/lang/String;)V",false));return out;
    }
}
