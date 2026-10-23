package lab;
import java.net.*;
import java.util.concurrent.atomic.AtomicInteger;
public final class LoggingProbe {
    public static void main(String[] args)throws Exception{
        AtomicInteger connections=new AtomicInteger();
        try(ServerSocket server=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))){
            server.setSoTimeout(300);
            Thread listener=new Thread(()->{try(Socket socket=server.accept()){connections.incrementAndGet();}catch(Exception ignored){}});listener.start();
            Class<?> lookup=Class.forName("org.apache.logging.log4j.core.lookup.JndiLookup");
            Object result=lookup.getMethod("lookup",String.class).invoke(lookup.getConstructor().newInstance(),"ldap://127.0.0.1:"+server.getLocalPort()+"/lab-probe");
            listener.join(1000);
            if(result!=null||connections.get()!=0)throw new AssertionError("JNDI lookup was not disabled");
            System.out.println("Legacy JNDI lookup disabled; zero connections to controlled loopback probe.");
        }
    }
}
