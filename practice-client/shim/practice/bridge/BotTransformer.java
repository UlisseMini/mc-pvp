package practice.bridge;

import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Pinned native boundaries, shared artifact but pass-through hooks for human role. */
final class BotTransformer {
    private static final String HOOK="practice/bridge/BotHooks";
    static byte[] transform(String type,byte[] bytes){
        // Role is immutable launch configuration, never a username or server message.
        if(!"bot".equals(System.getProperty("practice.role","human")))return bytes;
        boolean minecraft=type.equals("net.minecraft.client.Minecraft"),renderer=type.equals("net.minecraft.client.renderer.EntityRenderer"),
            player=type.equals("net.minecraft.entity.player.EntityPlayer"),living=type.equals("net.minecraft.entity.EntityLivingBase"),
            remote=type.equals("net.minecraft.client.entity.EntityOtherPlayerMP"),handler=type.equals("net.minecraft.client.network.NetHandlerPlayClient");
        if(!minecraft&&!renderer&&!player&&!living&&!remote&&!handler)return bytes;
        ClassNode node=new ClassNode();new ClassReader(bytes).accept(node,0);int hooks=0;
        for(MethodNode m:node.methods){
            if(handler&&named(m,"func_147297_a","addToSendQueue","a")&&
                    (m.desc.equals("(Lnet/minecraft/network/Packet;)V")||m.desc.equals("(Lff;)V"))){
                InsnList setup=new InsnList();LabelNode normal=new LabelNode();
                setup.add(new VarInsnNode(Opcodes.ALOAD,1));
                setup.add(new MethodInsnNode(Opcodes.INVOKESTATIC,HOOK,"suppressSeedPacket","(Ljava/lang/Object;)Z",false));
                setup.add(new JumpInsnNode(Opcodes.IFEQ,normal));setup.add(new InsnNode(Opcodes.RETURN));setup.add(normal);
                setup.add(new FrameNode(Opcodes.F_SAME,0,null,0,null));m.instructions.insert(setup);hooks++;
            }
            if(minecraft&&m.desc.equals("()V")){
                if(named(m,"func_71407_l","runTick","s")){
                    hookAttackLoop(m,node.name);m.instructions.insert(call("tick",0));hooks+=2;
                }
                if(named(m,"func_71411_J","runGameLoop","av")){m.instructions.insert(call("frame",0));hooks++;}
                if(named(m,"func_147116_af","clickMouse","aw")){pair(m,"click",0);hooks++;}
            }
            if(renderer&&m.desc.equals("(F)V")&&named(m,"func_78473_a","getMouseOver","a")){
                int limits=0,rays=0;
                for(AbstractInsnNode ins:m.instructions.toArray()){
                    if(ins instanceof LdcInsnNode&&Double.valueOf(3.0).equals(((LdcInsnNode)ins).cst)){
                        m.instructions.set(ins,new MethodInsnNode(Opcodes.INVOKESTATIC,HOOK,"reach","()D",false));limits++;
                    }else if(ins instanceof MethodInsnNode){MethodInsnNode call=(MethodInsnNode)ins;
                        if(call.desc.equals("()F")&&(call.name.equals("func_78757_d")||call.name.equals("getBlockReachDistance")||call.owner.equals("bda")&&call.name.equals("d"))){
                            m.instructions.insert(ins,new MethodInsnNode(Opcodes.INVOKESTATIC,HOOK,"ray","(F)F",false));rays++;}}
                }
                if(limits!=2||rays!=1)throw new IllegalStateException("Native reach hook mismatch: "+limits+" limits, "+rays+" rays");hooks++;
            }
            if(player&&named(m,"func_71059_n","attackTargetEntityWithCurrentItem","f")&&(m.desc.equals("(Lnet/minecraft/entity/Entity;)V")||m.desc.equals("(Lpk;)V"))){pair(m,"attack",0,1);hooks++;}
            if(player&&m.desc.equals("()V")&&named(m,"func_71041_bz","clearItemInUse","bV")){m.instructions.insert(call("clearUse",0));hooks++;}
            if(living&&m.desc.equals("(FF)V")&&named(m,"func_70612_e","moveEntityWithHeading","g")){
                InsnList hook=new InsnList();hook.add(new VarInsnNode(Opcodes.ALOAD,0));hook.add(new VarInsnNode(Opcodes.FLOAD,1));hook.add(new VarInsnNode(Opcodes.FLOAD,2));
                hook.add(new MethodInsnNode(Opcodes.INVOKESTATIC,HOOK,"movement","(Ljava/lang/Object;FF)V",false));m.instructions.insert(hook);
                for(AbstractInsnNode ins:m.instructions.toArray())if(ins.getOpcode()==Opcodes.RETURN)m.instructions.insertBefore(ins,call("moved",0));hooks++;
            }
            if(remote&&m.desc.equals("()V")&&named(m,"func_70636_d","onLivingUpdate","m")){pair(m,"interpolation",0);hooks++;}
            if(remote&&m.desc.equals("(DDDFFIZ)V")){
                for(AbstractInsnNode ins:m.instructions.toArray())if(ins.getOpcode()==Opcodes.RETURN)m.instructions.insertBefore(ins,call("remote",0));hooks++;
            }
        }
        int expected=minecraft?4:renderer?1:player?2:living?1:handler?1:2;
        if(hooks!=expected)throw new IllegalStateException("Native bot hooks missing in "+type+": "+hooks+" / "+expected);
        ClassWriter out=new ClassWriter(ClassWriter.COMPUTE_MAXS);node.accept(out);
        System.out.println("[PracticeBridge] Native bot hooks: "+type+" ("+hooks+")");return out.toByteArray();
    }
    private static boolean named(MethodNode m,String srg,String mcp,String obf){return m.name.equals(srg)||m.name.equals(mcp)||m.name.equals(obf);}
    private static AbstractInsnNode codeBefore(AbstractInsnNode ins){do{ins=ins.getPrevious();}while(ins!=null&&ins.getOpcode()<0);return ins;}
    private static AbstractInsnNode codeAfter(AbstractInsnNode ins){do{ins=ins.getNext();}while(ins!=null&&ins.getOpcode()<0);return ins;}
    /** Pinned while(keyBindAttack.isPressed()) clickMouse() loop. The native
     * condition and backedge remain intact; the observer never reads pressTime. */
    private static void hookAttackLoop(MethodNode method,String owner){
        int found=0;
        for(AbstractInsnNode ins:method.instructions.toArray())if(ins instanceof MethodInsnNode){
            MethodInsnNode click=(MethodInsnNode)ins;
            if(!click.owner.equals(owner)||!click.desc.equals("()V")||
                    !(click.name.equals("func_147116_af")||click.name.equals("clickMouse")||click.name.equals("aw")))continue;
            AbstractInsnNode load=codeBefore(ins),condition=load==null?null:codeBefore(load),backedge=codeAfter(ins);
            if(load==null||load.getOpcode()!=Opcodes.ALOAD||((VarInsnNode)load).var!=0
                    ||!(condition instanceof JumpInsnNode)||condition.getOpcode()!=Opcodes.IFEQ
                    ||!(backedge instanceof JumpInsnNode)||backedge.getOpcode()!=Opcodes.GOTO)
                throw new IllegalStateException("Pinned attack loop branch changed");
            AbstractInsnNode pressed=codeBefore(condition),field=pressed==null?null:codeBefore(pressed);
            if(!(pressed instanceof MethodInsnNode)||!((MethodInsnNode)pressed).desc.equals("()Z")
                    ||!(((MethodInsnNode)pressed).name.equals("func_151468_f")||((MethodInsnNode)pressed).name.equals("isPressed")||((MethodInsnNode)pressed).name.equals("f"))
                    ||!(field instanceof FieldInsnNode)||field.getOpcode()!=Opcodes.GETFIELD
                    ||!(((FieldInsnNode)field).name.equals("field_74312_F")||((FieldInsnNode)field).name.equals("keyBindAttack")||((FieldInsnNode)field).name.equals("ai")))
                throw new IllegalStateException("Pinned attack KeyBinding condition changed");
            LabelNode header=((JumpInsnNode)backedge).label;
            boolean reachesCondition=false;
            for(AbstractInsnNode cursor=header;cursor!=null&&cursor!=ins;cursor=cursor.getNext())if(cursor==pressed)reachesCondition=true;
            if(!reachesCondition)throw new IllegalStateException("Pinned attack loop backedge changed");
            // Keep the existing label/line/stack-map frame at the loop entry.
            // Inserting before its FrameNode would move the frame past the hook.
            AbstractInsnNode first=header;while(first!=null&&first.getOpcode()<0)first=first.getNext();
            if(first==null)throw new IllegalStateException("Missing attack loop body");
            method.instructions.insertBefore(first,call("attackLoop"));found++;
        }
        if(found!=1)throw new IllegalStateException("Pinned attack loop count changed: "+found);
    }
    private static InsnList call(String name,int... locals){return call(name,false,false,locals);}
    private static InsnList call(String name,boolean flag,boolean after,int... locals){
        InsnList out=new InsnList();String desc="(";
        for(int local:locals){out.add(new VarInsnNode(Opcodes.ALOAD,local));desc+="Ljava/lang/Object;";}
        if(flag){out.add(new InsnNode(after?Opcodes.ICONST_1:Opcodes.ICONST_0));desc+="Z";}
        out.add(new MethodInsnNode(Opcodes.INVOKESTATIC,HOOK,name,desc+")V",false));return out;
    }
    private static void pair(MethodNode m,String name,int... locals){
        m.instructions.insert(call(name,true,false,locals));
        for(AbstractInsnNode ins:m.instructions.toArray())if(ins.getOpcode()==Opcodes.RETURN)m.instructions.insertBefore(ins,call(name,true,true,locals));
    }
}
