package practice.bridge;

import net.minecraft.launchwrapper.IClassTransformer;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Read-only packet/input observers; preserves original instructions and return values.
 * Observation takes time; capture timestamps bracket work rather than promising zero overhead. */
public final class PracticeTransformer implements IClassTransformer {
    private static final String HOOK="practice/bridge/ObserverHooks";
    public byte[] transform(String name,String transformed,byte[] bytes){
        if(bytes==null)return null;
        bytes=BotTransformer.transform(transformed,bytes);
        boolean network=transformed.equals("net.minecraft.network.NetworkManager"),
            decoder=transformed.equals("net.minecraft.util.MessageDeserializer"),
            encoder=transformed.equals("net.minecraft.util.MessageSerializer"),
            handler=transformed.equals("net.minecraft.client.network.NetHandlerPlayClient"),
            minecraft=transformed.equals("net.minecraft.client.Minecraft"),
            player=transformed.equals("net.minecraft.entity.player.EntityPlayer");
        if(!network&&!decoder&&!encoder&&!handler&&!minecraft&&!player)return bytes;
        ClassNode node=new ClassNode();new ClassReader(bytes).accept(node,0);int hooks=0;
        for(MethodNode m:node.methods){
            if((m.access&(Opcodes.ACC_SYNTHETIC|Opcodes.ACC_BRIDGE|Opcodes.ACC_STATIC))!=0)continue;
            if(player&&(m.name.equals("func_145748_c_")||m.name.equals("getDisplayName")||m.name.equals("f_"))&&m.desc.startsWith("()L")){
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
            }else if(decoder&&m.name.equals("decode")&&m.desc.contains("Lio/netty/buffer/ByteBuf;")){
                m.instructions.insert(call("wireBefore",1,2));
                for(AbstractInsnNode i:m.instructions.toArray())if(i.getOpcode()==Opcodes.RETURN)m.instructions.insertBefore(i,call("wireAfter",3));hooks++;
            }else if(encoder&&!m.name.startsWith("<")&&m.desc.startsWith("(Lio/netty/channel/ChannelHandlerContext;")&&m.desc.endsWith("Lio/netty/buffer/ByteBuf;)V")){
                for(AbstractInsnNode i:m.instructions.toArray())if(i.getOpcode()==Opcodes.RETURN)m.instructions.insertBefore(i,call("wireOut",1,2,3));hooks++;
            }else if(handler&&m.desc.endsWith(")V")&&Type.getArgumentTypes(m.desc).length==1 && !m.name.startsWith("<")){
                String arg=Type.getArgumentTypes(m.desc)[0].getClassName();
                // All play-handler packet methods have one packet argument. This also
                // covers obfuscated descriptors before Forge's deobfuscation pass.
                if(arg.startsWith("net.minecraft.network.play.server.") || arg.matches("[a-z]{2,3}")){
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
            }else if(minecraft&&m.desc.equals("()V")&&(m.name.equals("func_71411_J")||m.name.equals("runGameLoop")||m.name.equals("av"))){
                int locks=0,unlocks=0;
                for(AbstractInsnNode i:m.instructions.toArray()){
                    if(i.getOpcode()==Opcodes.MONITORENTER){m.instructions.insert(i,clock("start"));locks++;}
                    if(i.getOpcode()==Opcodes.MONITOREXIT){m.instructions.insertBefore(i,clock("end"));unlocks++;}
                }
                if(locks!=1||unlocks!=2)throw new IllegalStateException("Practice task drain hook changed: "+locks+","+unlocks);
                hooks++;
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
