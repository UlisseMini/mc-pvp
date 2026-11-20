package practice.bridge;
import net.minecraft.network.*;
import net.minecraft.network.play.client.*;
import io.netty.util.concurrent.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.nio.file.*;
/** Checks actual compiled socket-free send sink, not a mocked transport. */
public final class OfflineSafetyCheck {
    public static void main(String[] args)throws Exception{
        OfflineReplay.SinkNetwork sink=new OfflineReplay.SinkNetwork();
        sink.func_179290_a(new C00PacketKeepAlive(1));sink.func_179290_a(new C01PacketChatMessage("never transmitted"));sink.func_179288_a(new C03PacketPlayer(),null);
        if(sink.discarded!=3)throw new AssertionError("Both send overloads must discard");
        for(java.lang.reflect.Field f:NetworkManager.class.getDeclaredFields())if(io.netty.channel.Channel.class.isAssignableFrom(f.getType())){f.setAccessible(true);if(f.get(sink)!=null)throw new AssertionError("Offline transport has a channel");}
        ClassNode node=new ClassNode();new ClassReader(Files.readAllBytes(Paths.get(args[0],"practice/bridge/OfflineReplay.class"))).accept(node,0);
        for(MethodNode m:node.methods)for(AbstractInsnNode i:m.instructions.toArray())if(i instanceof MethodInsnNode){MethodInsnNode call=(MethodInsnNode)i;if(call.owner.equals("net/minecraft/network/NetworkManager")&&(call.name.equals("func_181124_a")||call.name.equals("func_150722_a")))throw new AssertionError("Replay must never create a connection");}
        java.lang.reflect.Constructor<?> command=Class.forName("practice.bridge.PracticeBridge$Command").getDeclaredConstructor(PracticeBridge.class);command.setAccessible(true);
        net.minecraft.command.ICommand root=(net.minecraft.command.ICommand)command.newInstance(new PracticeBridge());
        if(!root.func_71517_b().equals("practice")||!root.func_71514_a().equals(java.util.Collections.singletonList("p")))throw new AssertionError("Only /practice and /p may be registered; never /replay");
        if(OfflineReplay.usableLinkedSource("b","b")||OfflineReplay.usableLinkedSource(null,"b")||!OfflineReplay.usableLinkedSource("b","a"))throw new AssertionError("B-only view must never become its own opponent POV");
        java.util.Map<String,Object> frame=practice.core.JsonData.map("a",practice.core.JsonData.map("posX",1),"b",practice.core.JsonData.map("posX",10));
        java.util.Map<String,Object> normalized=OfflineReplay.linkedView(frame,"b");
        if(!((java.util.List)((java.util.Map)normalized.get("a")).get("pos")).get(0).equals(10.0))throw new AssertionError("B source needs role reindexing");
        net.minecraft.client.settings.KeyBinding action=new net.minecraft.client.settings.KeyBinding("offline-check",-100,"offline");
        net.minecraft.client.settings.KeyBinding.func_74510_a(-100,true);net.minecraft.client.settings.KeyBinding.func_74507_a(-100);OfflineReplay.blockBinding(action);
        if(action.func_151470_d()||action.func_151468_f())throw new AssertionError("Playback must consume gameplay actions without mutating world");
        System.out.println("OfflineSafetyCheck passed: both send overloads discard; no channel or connect factory");
    }
}
