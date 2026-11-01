package practice.core;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.*;

/** Durable local gzip outbox. The only network destination is literal loopback;
 * no redirects, proxy, Minecraft channel, authentication or external service. */
public final class RemoteCaptureUpload implements AutoCloseable {
    public static final int PORT=8766;
    private final Path captures,outbox;
    private final Queue<Properties> registrations=new ConcurrentLinkedQueue<>();
    private final Thread worker;
    private volatile boolean closing;
    private volatile String status="local outbox idle";
    public RemoteCaptureUpload(Path directory){captures=directory.resolve("captures");outbox=directory.resolve("remote-outbox");worker=new Thread(this::work,"practice-local-upload");worker.setDaemon(true);worker.start();}
    public void register(String session,String host,Path first){
        if(!session.matches("r[0-9a-f]{32}"))throw new IllegalArgumentException("Invalid local session");
        Properties p=new Properties();p.setProperty("session",session);p.setProperty("host",host);p.setProperty("first",first.getFileName().toString());registrations.add(p);
    }
    public String status(){return status;}
    private void work(){
        while(!closing){try{scan();}catch(Exception e){status="local backlog retained: "+e.getClass().getSimpleName()+": "+e.getMessage();}
            try{Thread.sleep(2000);}catch(InterruptedException e){if(closing)break;}}
        try{scan();}catch(Exception ignored){}
    }
    private void scan()throws Exception{
        Properties p;while((p=registrations.poll())!=null){Files.createDirectories(outbox);Path dest;
            // One descriptor per recording segment; retain it and all gzip files after acknowledgement.
            dest=outbox.resolve(p.getProperty("first")+".properties");
            Path tmp=Files.createTempFile(outbox,"pending-",".tmp");try(OutputStream out=Files.newOutputStream(tmp)){p.store(out,"Local remote capture backlog; never sent through Minecraft");}Files.move(tmp,dest,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}
        if(!Files.isDirectory(outbox))return;
        try(DirectoryStream<Path> descriptors=Files.newDirectoryStream(outbox,"*.properties")){
            for(Path descriptor:descriptors){Properties meta=new Properties();try(InputStream in=Files.newInputStream(descriptor)){meta.load(in);}
                String first=meta.getProperty("first","");if(!first.matches("a-client-[A-Za-z0-9-]+-events\\.jsonl\\.gz"))continue;
                String stem=first.substring(0,first.length()-9);
                try(DirectoryStream<Path> files=Files.newDirectoryStream(captures,stem+"*.jsonl.gz")){
                    List<Path> sorted=new ArrayList<>();for(Path file:files)sorted.add(file);Collections.sort(sorted);
                    for(Path file:sorted){Path ack=outbox.resolve(file.getFileName()+".ack");if(Files.exists(ack))continue;
                        if(!complete(file))continue;
                        upload(meta,file,ack);}
                }
            }
        }
    }
    private boolean complete(Path file){
        // A live current part is deliberately never uploaded. Closed parts have
        // a gzip trailer and capture_end; failures remain local and visible.
        try(BufferedReader in=new BufferedReader(new InputStreamReader(new GZIPInputStream(Files.newInputStream(file)),StandardCharsets.UTF_8))){String line,last="";while((line=in.readLine())!=null)last=line;return last.contains("\"type\":\"capture_end\"");}
        catch(IOException e){return false;}
    }
    private void upload(Properties meta,Path file,Path ack)throws Exception{
        long size=Files.size(file);if(size>32L*1024*1024){status="local part exceeds ingest limit; retained "+file.getFileName();return;}
        MessageDigest digest=MessageDigest.getInstance("SHA-256");try(InputStream in=Files.newInputStream(file)){byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1)digest.update(b,0,n);}
        StringBuilder sha=new StringBuilder();for(byte b:digest.digest())sha.append(String.format("%02x",b&255));
        HttpURLConnection c=(HttpURLConnection)new URL("http","127.0.0.1",PORT,"/practice/capture").openConnection(Proxy.NO_PROXY);
        c.setInstanceFollowRedirects(false);c.setConnectTimeout(1000);c.setReadTimeout(5000);c.setRequestMethod("POST");c.setDoOutput(true);c.setFixedLengthStreamingMode(size);
        c.setRequestProperty("Content-Type","application/gzip");c.setRequestProperty("X-Practice-Session",meta.getProperty("session"));c.setRequestProperty("X-Practice-Part",file.getFileName().toString());
        c.setRequestProperty("X-Practice-Host",Base64.getEncoder().encodeToString(meta.getProperty("host","").getBytes(StandardCharsets.UTF_8)));c.setRequestProperty("X-Practice-Sha256",sha.toString());
        try{try(OutputStream out=c.getOutputStream();InputStream in=Files.newInputStream(file)){byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1)out.write(b,0,n);}
            if(c.getResponseCode()!=200)throw new IOException("local lab HTTP "+c.getResponseCode());
            String response;try(BufferedReader in=new BufferedReader(new InputStreamReader(c.getInputStream(),StandardCharsets.UTF_8))){response=in.readLine();}
            if(!sha.toString().equals(response))throw new IOException("local acknowledgement mismatch");
            Files.write(ack,(sha+"\n").getBytes(StandardCharsets.UTF_8),StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING);status="local lab acknowledged "+file.getFileName();
        }finally{c.disconnect();}
    }
    public void close(){closing=true;worker.interrupt();try{worker.join(1000);}catch(InterruptedException e){Thread.currentThread().interrupt();}}
}
