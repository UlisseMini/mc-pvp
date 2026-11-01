package practice.bridge;
import io.netty.channel.embedded.EmbeddedChannel;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
/** Actual connection-address gate; no game launch or external connection. */
public final class RemoteSafetyCheck {
    static int checks;
    static void check(boolean yes,String why){checks++;if(!yes)throw new AssertionError(why);}
    static class SocketChannel extends EmbeddedChannel {
        final SocketAddress address;SocketChannel(SocketAddress a){super(new io.netty.channel.ChannelInboundHandlerAdapter());address=a;}
        public SocketAddress remoteAddress(){return address;}
    }
    public static void main(String[] args)throws Exception{
        SocketChannel local=new SocketChannel(new InetSocketAddress("127.0.0.1",25565));
        SocketChannel remote=new SocketChannel(new InetSocketAddress(InetAddress.getByAddress(new byte[]{(byte)203,0,113,1}),25565));
        check(ConnectionScope.canSend(local,local),"actual loopback connection permits existing lab link");
        check(!ConnectionScope.canSend(remote,remote),"tracked remote recording cannot permit custom packets");
        check(!ConnectionScope.canSend(local,remote),"stale local active cannot authorize new remote connection");
        check(!ConnectionScope.canSend(remote,local),"stale remote cannot authorize different local socket");
        ConnectionScope.connected(local);check(ConnectionScope.canSend(local),"early PLAY current socket works without player/capture");
        ConnectionScope.connected(remote);check(!ConnectionScope.canSend(local),"remote server switch updates socket even when capture off");
        ConnectionScope.disconnected(local);check(ConnectionScope.current()==remote,"old close cannot clear new connection");
        ConnectionScope.disconnected(remote);check(ConnectionScope.current()==null,"actual disconnect fails closed");
        check(!ConnectionScope.canSend(null,remote)&&!ConnectionScope.canSend(local,null),"missing active/current fails closed");
        check(!ConnectionScope.loopback(InetSocketAddress.createUnresolved("localhost",25565)),"unresolved host never trusted");
        System.setProperty("practice.test.forceRemote","true");check(!ConnectionScope.canSend(local,local),"pretend-remote only removes privileges");System.clearProperty("practice.test.forceRemote");
        ClassNode node=new ClassNode();new ClassReader(Files.readAllBytes(Paths.get(args[0],"practice/bridge/PracticeCapture.class"))).accept(node,0);
        int constructors=0;for(MethodNode m:node.methods){boolean guard=false;for(AbstractInsnNode ins:m.instructions.toArray()){
            if(ins instanceof MethodInsnNode){MethodInsnNode call=(MethodInsnNode)ins;
                if(call.owner.equals("practice/bridge/ConnectionScope")&&call.name.equals("canSend"))guard=true;
                if(call.owner.equals("net/minecraft/network/play/client/C17PacketCustomPayload")&&call.name.equals("<init>")){constructors++;check(m.name.equals("sendRaw")&&guard,"every practice C17 construction follows current-socket guard");}
            }
        }}check(constructors==1,"single audited custom-payload emission site");
        local.close();remote.close();System.out.println("RemoteSafetyCheck passed: "+checks+" checks");
    }
}
