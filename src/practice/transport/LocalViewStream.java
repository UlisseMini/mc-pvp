package practice.transport;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

/** Fixed-size authenticated loopback diagnostic stream. No commands or object deserialization. */
public final class LocalViewStream {
    public static final int VALUES=36,MAGIC=0x50565032;
    public static final class Sample {
        public final long sequence,nanos,millis,tickNanos,receivedNanos,offsetNanos,rttNanos;
        private final double[] values;
        public Sample(long seq,long ns,long ms,long tick,double[] v){this(seq,ns,ms,tick,v,0,0,0);}
        private Sample(long seq,long ns,long ms,long tick,double[] v,long recv,long offset,long rtt){
            if(v.length!=VALUES)throw new IllegalArgumentException("Snapshot size");
            sequence=seq;nanos=ns;millis=ms;tickNanos=tick;values=v.clone();receivedNanos=recv;offsetNanos=offset;rttNanos=rtt;
        }
        public double value(int n){return values[n];}
        public double ageMs(long now){return (now+offsetNanos-nanos)/1e6;}
        public double deliveryMs(){return (receivedNanos+offsetNanos-nanos)/1e6;}
    }
    public static final class Publisher implements AutoCloseable {
        private final ServerSocket listener;
        private volatile Socket peer;
        private volatile boolean closed;
        private volatile Sample latest;
        private final Object signal=new Object();
        public final String token;
        public Publisher()throws IOException{
            byte[] secret=new byte[16];new SecureRandom().nextBytes(secret);StringBuilder t=new StringBuilder();for(byte b:secret)t.append(String.format(Locale.ROOT,"%02x",b&255));token=t.toString();
            listener=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"));
            Thread worker=new Thread(this::serve,"practice-view-publisher");worker.setDaemon(true);worker.start();
        }
        public int port(){return listener.getLocalPort();}
        public void publish(Sample sample){latest=sample;synchronized(signal){signal.notifyAll();}}
        private void serve(){
            while(!closed)try(Socket socket=listener.accept()){
                socket.setTcpNoDelay(true);socket.setSoTimeout(2000);
                if(!socket.getInetAddress().isLoopbackAddress())continue;
                DataInputStream in=new DataInputStream(new BufferedInputStream(socket.getInputStream()));DataOutputStream out=new DataOutputStream(new BufferedOutputStream(socket.getOutputStream(),4096));
                byte[] supplied=new byte[32];in.readFully(supplied);
                if(!MessageDigest.isEqual(supplied,token.getBytes(StandardCharsets.US_ASCII)))continue;
                peer=socket;
                for(int i=0;i<8;i++){long sent=in.readLong(),received=System.nanoTime();out.writeLong(sent);out.writeLong(received);out.writeLong(System.nanoTime());out.flush();}
                long last=-1;
                while(!closed){
                    Sample s=latest;
                    if(s==null||s.sequence==last){synchronized(signal){if(latest==null||latest.sequence==last)signal.wait(10);}continue;}
                    out.writeInt(MAGIC);out.writeLong(s.sequence);out.writeLong(s.nanos);out.writeLong(s.millis);out.writeLong(s.tickNanos);
                    for(int i=0;i<VALUES;i++)out.writeDouble(s.value(i));out.flush();last=s.sequence;
                }
            }catch(IOException e){/* Disconnects are normal. */}catch(InterruptedException e){Thread.currentThread().interrupt();break;}
            finally{Socket p=peer;peer=null;if(p!=null)try{p.close();}catch(IOException ignored){}}
        }
        public void close(){closed=true;try{listener.close();}catch(IOException ignored){}Socket p=peer;if(p!=null)try{p.close();}catch(IOException ignored){}synchronized(signal){signal.notifyAll();}}
    }
    public static final class Reader implements AutoCloseable {
        private volatile boolean closed;
        private volatile Socket socket;
        private volatile Sample latest;
        private volatile String status="connecting";
        private final ArrayDeque<Sample> received=new ArrayDeque<>();
        private long dropped;
        public Reader(int port,String token){
            if(port<1||port>65535||!token.matches("[0-9a-f]{32}"))throw new IllegalArgumentException("Invalid local stream descriptor");
            Thread worker=new Thread(()->read(port,token),"practice-view-reader");worker.setDaemon(true);worker.start();
        }
        public Sample latest(){return latest;}
        public String status(){return status;}
        public synchronized List<Sample> drain(){List<Sample> out=new ArrayList<>(received);received.clear();return out;}
        public synchronized long dropped(){return dropped;}
        private void read(int port,String token){
            while(!closed){
                try(Socket s=new Socket()){
                    socket=s;s.connect(new InetSocketAddress("127.0.0.1",port),1000);s.setTcpNoDelay(true);s.setSoTimeout(2000);
                    DataInputStream in=new DataInputStream(new BufferedInputStream(s.getInputStream()));DataOutputStream out=new DataOutputStream(new BufferedOutputStream(s.getOutputStream()));
                    out.write(token.getBytes(StandardCharsets.US_ASCII));out.flush();long offset=0,best=Long.MAX_VALUE;
                    for(int i=0;i<8;i++){
                        long t0=System.nanoTime();out.writeLong(t0);out.flush();long echoed=in.readLong(),t1=in.readLong(),t2=in.readLong(),t3=System.nanoTime();
                        if(echoed!=t0)throw new IOException("Clock probe mismatch");long rtt=(t3-t0)-(t2-t1);
                        if(rtt>=0&&rtt<best){best=rtt;offset=((t1-t0)+(t2-t3))/2;}
                    }
                    status="connected";
                    while(!closed){
                        if(in.readInt()!=MAGIC)throw new IOException("Stream format mismatch");
                        long seq=in.readLong(),ns=in.readLong(),ms=in.readLong(),tick=in.readLong();double[] values=new double[VALUES];
                        for(int i=0;i<VALUES;i++){values[i]=in.readDouble();if(!Double.isFinite(values[i]))throw new IOException("Non-finite view value");}
                        Sample sample=new Sample(seq,ns,ms,tick,values,System.nanoTime(),offset,best);
                        synchronized(this){if(received.size()>=2048){received.removeFirst();dropped++;}received.addLast(sample);}latest=sample;
                    }
                }catch(IOException e){status="disconnected";latest=null;}finally{socket=null;}
                if(!closed)try{Thread.sleep(250);}catch(InterruptedException e){Thread.currentThread().interrupt();break;}
            }
        }
        public void close(){closed=true;latest=null;synchronized(this){received.clear();}Socket s=socket;if(s!=null)try{s.close();}catch(IOException ignored){}}
    }
}
