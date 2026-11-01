import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import lab.Agent;

/** No native launch: strip observers and require original server bytecode. */
public final class ServerTaskTimingHooksCheck {
    static int checks;
    static void check(boolean yes,String why){checks++;if(!yes)throw new AssertionError(why);}
    static byte[] read(ZipFile jar,String entry)throws Exception{return jar.getInputStream(jar.getEntry(entry)).readAllBytes();}
    static byte[] methodBytes(MethodNode method){ClassWriter writer=new ClassWriter(0);writer.visit(Opcodes.V1_8,Opcodes.ACC_PUBLIC,"Fixture",null,"java/lang/Object",null);method.accept(writer);writer.visitEnd();return writer.toByteArray();}
    static void frames(MethodNode method){for(AbstractInsnNode i:method.instructions.toArray())if(i instanceof FrameNode)method.instructions.remove(i);}
    static void verify(byte[] bytes,String name)throws Exception{
        byte[] changed=new Agent().transform(null,name,null,null,bytes);check(changed!=null,"Pinned class transformed");
        ClassNode a=new ClassNode(),b=new ClassNode();new ClassReader(bytes).accept(a,0);new ClassReader(changed).accept(b,0);
        int enqueue=0,heads=0,returns=0,expectedReturns=0,methods=0;
        for(int i=0;i<a.methods.size();i++){
            MethodNode original=a.methods.get(i),observed=b.methods.get(i);
            boolean task=original.desc.equals("(Ljava/util/concurrent/Callable;)Lcom/google/common/util/concurrent/ListenableFuture;");
            boolean handler=name.equals("lm")&&original.name.equals("a")&&Set.of("(Lip;)V","(Lin;)V","(Lis;)V").contains(original.desc);
            if(!task&&!handler)continue;
            // C02 already has this independent toolbar guard in the committed
            // agent. Retain it in the expected baseline, not in the new diff.
            if(handler&&original.desc.equals("(Lin;)V")){
                InsnList guard=new InsnList();LabelNode proceed=new LabelNode();
                guard.add(new VarInsnNode(Opcodes.ALOAD,0));guard.add(new VarInsnNode(Opcodes.ALOAD,1));
                guard.add(new MethodInsnNode(Opcodes.INVOKESTATIC,"lab/ReplayToolbar","intercept","(Ljava/lang/Object;Ljava/lang/Object;)Z",false));
                guard.add(new JumpInsnNode(Opcodes.IFEQ,proceed));guard.add(new InsnNode(Opcodes.RETURN));guard.add(proceed);original.instructions.insert(guard);
            }
            methods++;
            if(handler)for(AbstractInsnNode x:original.instructions.toArray())if(x.getOpcode()==Opcodes.RETURN)expectedReturns++;
            for(AbstractInsnNode x:observed.instructions.toArray())if(x instanceof MethodInsnNode call&&call.owner.equals("lab/ServerReplicationTrace")){
                if(call.name.equals("queued")){
                    check(x.getPrevious() instanceof MethodInsnNode add&&add.owner.equals("java/util/Queue")&&add.name.equals("add"),"Enqueue marker immediately follows Queue.add");
                    check(x.getNext().getOpcode()==Opcodes.POP,"Native add result still consumed unchanged");
                    int depth=0;for(AbstractInsnNode y=observed.instructions.getFirst();y!=x;y=y.getNext()){if(y.getOpcode()==Opcodes.MONITORENTER)depth++;else if(y.getOpcode()==Opcodes.MONITOREXIT)depth--;}
                    check(depth==1,"Enqueue observation remains inside native monitor");observed.instructions.remove(x);enqueue++;
                }else if(call.name.equals("handler")){
                    AbstractInsnNode flag=x.getPrevious(),packet=flag.getPrevious(),self=packet.getPrevious();
                    check(flag.getOpcode()==Opcodes.ICONST_0||flag.getOpcode()==Opcodes.ICONST_1,"Read-only entry/return flag");
                    check(packet instanceof VarInsnNode p&&p.getOpcode()==Opcodes.ALOAD&&p.var==1,"Original packet identity passed");
                    check(self instanceof VarInsnNode p&&p.getOpcode()==Opcodes.ALOAD&&p.var==0,"Original handler passed");
                    if(flag.getOpcode()==Opcodes.ICONST_0)heads++;else{check(x.getNext().getOpcode()==Opcodes.RETURN,"Marker before each normal native return");returns++;}
                    observed.instructions.remove(self);observed.instructions.remove(packet);observed.instructions.remove(flag);observed.instructions.remove(x);
                }else if(call.name.equals("input")){
                    AbstractInsnNode packet=x.getPrevious(),self=packet.getPrevious();observed.instructions.remove(self);observed.instructions.remove(packet);observed.instructions.remove(x);
                }else throw new AssertionError("Unexpected observer in checked method: "+call.name);
            }
            frames(original);frames(observed);observed.maxStack=original.maxStack;
            check(Arrays.equals(methodBytes(original),methodBytes(observed)),"Native instructions/control flow/monitors changed: "+name+original.desc);
        }
        if(name.equals("lm"))check(methods==3&&heads==3&&returns==expectedReturns,"All three packet handlers and every return observed");
        else check(methods==1&&enqueue==1,"Exactly one native Callable queue insertion observed");
    }
    public static void main(String[] args)throws Exception{
        System.setProperty("lab.role","server");
        try(ZipFile jar=new ZipFile(args[0])){verify(read(jar,"net/minecraft/server/MinecraftServer.class"),"net/minecraft/server/MinecraftServer");verify(read(jar,"lm.class"),"lm");}
        Object packet=new Object(),other=new Object();long id=lab.ServerReplicationTrace.packetId(packet);
        check(id==lab.ServerReplicationTrace.packetId(packet),"Same packet object keeps ID");check(id!=lab.ServerReplicationTrace.packetId(other),"Distinct objects keep distinct IDs");
        check(lab.ServerReplicationTrace.currentPacketId()==-1,"No invented active handler context");
        lab.ServerReplicationTrace.handler(null,null,false);lab.ServerReplicationTrace.handler(null,null,true);lab.ServerReplicationTrace.queued();
        check(lab.ServerReplicationTrace.currentPacketId()==-1,"Observer failures do not propagate or invent a service context");
        System.out.println("ServerTaskTimingHooksCheck passed: "+checks+" checks");
    }
}
