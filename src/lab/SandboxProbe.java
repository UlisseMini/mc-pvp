package lab;
import java.nio.file.*;
import java.net.*;
public final class SandboxProbe {
    public static void main(String[] args)throws Exception{
        Path canary=Paths.get(args[0]),allowed=Paths.get(args[1]);
        boolean deniedRead=false,deniedWrite=false,deniedNetwork=false;
        try{Files.readString(canary);}catch(java.io.IOException e){deniedRead=true;}
        try{Files.writeString(canary.resolveSibling(canary.getFileName()+".write"),"probe");}catch(java.io.IOException e){deniedWrite=true;}
        try(Socket socket=new Socket()){socket.connect(new InetSocketAddress("1.1.1.1",443),1000);}catch(java.net.SocketException e){deniedNetwork=e.getMessage().toLowerCase().contains("permitted")||e.getMessage().toLowerCase().contains("denied");}
        Files.writeString(allowed,"sandbox allowed write");
        try(ServerSocket listener=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"));Socket client=new Socket("127.0.0.1",listener.getLocalPort());Socket accepted=listener.accept()){
            client.getOutputStream().write(7);if(accepted.getInputStream().read()!=7)throw new AssertionError("loopback");
        }
        System.out.println("denied_outside_read="+deniedRead+" denied_outside_write="+deniedWrite+" denied_public_network="+deniedNetwork+" allowed_loopback=true allowed_runtime_write=true");
        if(!(deniedRead&&deniedWrite&&deniedNetwork))System.exit(1);
    }
}
