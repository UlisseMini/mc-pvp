package practice.bridge;

import io.netty.channel.Channel;
import java.net.*;

/** Trust the current real socket, never a previous capture's active flag. */
final class ConnectionScope {
    static boolean loopback(SocketAddress address){return address instanceof InetSocketAddress&&((InetSocketAddress)address).getAddress()!=null&&((InetSocketAddress)address).getAddress().isLoopbackAddress();}
    static boolean local(Channel channel){return channel!=null&&channel.isActive()&&loopback(channel.remoteAddress())&&!Boolean.getBoolean("practice.test.forceRemote");}
    // Set by actual CLIENTBOUND NetworkManager.channelActive, even when recording
    // is off. This also covers first PLAY before Minecraft.thePlayer exists.
    private static final java.util.concurrent.atomic.AtomicReference<Channel> connection=new java.util.concurrent.atomic.AtomicReference<>();
    static void connected(Channel c){connection.set(c);}
    static void disconnected(Channel c){connection.compareAndSet(c,null);}
    static Channel current(){Channel c=connection.get();return c!=null&&c.isActive()?c:null;}
    static boolean practice(){return local(current());}
    static boolean canSend(Channel active){Channel actual=current();return canSend(active,actual);}
    static boolean canSend(Channel active,Channel actual){return actual==active&&local(actual);}
    static String host(Channel channel){if(channel==null)return "unknown";String s=String.valueOf(channel.remoteAddress()).replaceAll("[\\r\\n\\p{Cntrl}]","");return s.substring(0,Math.min(255,s.length()));}
}
