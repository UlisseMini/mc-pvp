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
        boolean network=transformed.equals("net.minecraft.network.NetworkManager"),
            decoder=transformed.equals("net.minecraft.util.MessageDeserializer"),
            encoder=transformed.equals("net.minecraft.util.MessageSerializer"),
            handler=transformed.equals("net.minecraft.client.network.NetHandlerPlayClient"),
            minecraft=transformed.equals("net.minecraft.client.Minecraft");
        if(!network&&!decoder&&!encoder&&!handler&&!minecraft)return bytes;
        ClassNode node=new ClassNode();new ClassReader(bytes).accept(node,0);int hooks=0;
        for(MethodNode m:node.methods){
            if((m.access&(Opcodes.ACC_SYNTHETIC|Opcodes.ACC_BRIDGE|Opcodes.ACC_STATIC))!=0)continue;
            if(network&&m.name.equals("channelActive")){
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
}
