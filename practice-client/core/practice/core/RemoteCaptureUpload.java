package practice.core;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.*;

/** Durable local gzip outbox. The only network destination is literal loopback;
 * no redirects, proxy, Minecraft channel, authentication or external service. */
public final class RemoteCaptureUpload implements AutoCloseable {
    public static final int PORT=8766;
    private static final Pattern CAPTURE_NAME=Pattern.compile("(a-client-[A-Za-z0-9-]+-events)(?:-part[0-9]+)?\\.jsonl\\.gz");
    private final Path captures,outbox;
    private final int port;
    private final Queue<Properties> registrations=new ConcurrentLinkedQueue<>();
    private final Queue<Part> closedParts=new ConcurrentLinkedQueue<>();
    private final Map<Path,Part> backlog=new LinkedHashMap<>();
    private final Thread worker;
    private boolean recovered;
    private volatile boolean closing;
    private volatile String status="local outbox idle";
    public RemoteCaptureUpload(Path directory){this(directory,PORT);}
    RemoteCaptureUpload(Path directory,int port){
        captures=directory.resolve("captures");outbox=directory.resolve("remote-outbox");this.port=port;
        worker=new Thread(this::work,"practice-local-upload");worker.setDaemon(true);worker.start();
    }
    /** Persist the session identity and return the writer's notification for each successfully closed part. */
    public Consumer<Path> register(String session,String host,Path first){
        if(!session.matches("r[0-9a-f]{32}"))throw new IllegalArgumentException("Invalid local session");
        Properties p=new Properties();p.setProperty("session",session);p.setProperty("host",host);p.setProperty("first",first.getFileName().toString());registrations.add(p);
        return file->closedParts.add(new Part(p,file,true));
    }
    public String status(){return status;}
    private void work(){
        while(!closing){try{scan();}catch(Exception e){status="local backlog retained: "+e.getClass().getSimpleName()+": "+e.getMessage();}
            try{Thread.sleep(2000);}catch(InterruptedException e){if(closing)break;}}
        try{scan();}catch(Exception ignored){}
    }
    private void scan()throws Exception{
        if(!recovered){recover();recovered=true;}
        Properties p;
        while((p=registrations.peek())!=null){
            Files.createDirectories(outbox);
            Path dest=outbox.resolve(p.getProperty("first")+".properties");
            Path tmp=Files.createTempFile(outbox,"pending-",".tmp");
            try{
                try(OutputStream out=Files.newOutputStream(tmp)){p.store(out,"Local remote capture backlog; never sent through Minecraft");}
                Files.move(tmp,dest,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
                registrations.remove();
            }finally{Files.deleteIfExists(tmp);}
        }
        Part closed;
        while((closed=closedParts.poll())!=null)backlog.put(closed.file,closed);
        Iterator<Part> parts=backlog.values().iterator();
        while(parts.hasNext()){
            Part part=parts.next();
            Path ack=outbox.resolve(part.file.getFileName()+".ack");
            if(Files.exists(ack)||part.complete()&&upload(part.meta,part.file,ack))parts.remove();
        }
    }
    private void recover()throws IOException{
        if(!Files.isDirectory(outbox))return;
        Map<String,Properties> sessions=new HashMap<>();
        try(DirectoryStream<Path> descriptors=Files.newDirectoryStream(outbox,"*.properties")){
            for(Path descriptor:descriptors){
                Properties meta=new Properties();try(InputStream in=Files.newInputStream(descriptor)){meta.load(in);}
                sessions.put(meta.getProperty("first",""),meta);
            }
        }
        if(sessions.isEmpty()||!Files.isDirectory(captures))return;
        try(DirectoryStream<Path> files=Files.newDirectoryStream(captures,"a-client-*.jsonl.gz")){
            List<Path> sorted=new ArrayList<>();
            for(Path file:files)if(!Files.exists(outbox.resolve(file.getFileName()+".ack")))sorted.add(file);
            Collections.sort(sorted);
            for(Path file:sorted){
                Matcher name=CAPTURE_NAME.matcher(file.getFileName().toString());
                if(!name.matches())continue;
                Properties meta=sessions.get(name.group(1)+".jsonl.gz");
                if(meta!=null)backlog.put(file,new Part(meta,file,false));
            }
        }
    }
    private static final class Part {
        final Properties meta;
        final Path file;
        boolean verified;
        long checkedSize=-1;
        FileTime checkedAt;
        Part(Properties meta,Path file,boolean verified){this.meta=meta;this.file=file;this.verified=verified;}
        boolean complete(){
            if(verified)return true;
            try{
                BasicFileAttributes attrs=Files.readAttributes(file,BasicFileAttributes.class);
                if(attrs.size()==checkedSize&&attrs.lastModifiedTime().equals(checkedAt))return false;
                // Recovered files may include a writer's partial file. Verify its gzip trailer and footer.
                try(BufferedReader in=new BufferedReader(new InputStreamReader(new GZIPInputStream(Files.newInputStream(file)),StandardCharsets.UTF_8))){
                    String line,last="";while((line=in.readLine())!=null)last=line;
                    verified=last.contains("\"type\":\"capture_end\"");
                }catch(EOFException|ZipException incomplete){
                    verified=false;
                }
                checkedSize=attrs.size();checkedAt=attrs.lastModifiedTime();
                return verified;
            }catch(IOException e){return false;}
        }
    }
    private boolean upload(Properties meta,Path file,Path ack)throws Exception{
        long size=Files.size(file);if(size>32L*1024*1024){status="local part exceeds ingest limit; retained "+file.getFileName();return false;}
        MessageDigest digest=MessageDigest.getInstance("SHA-256");try(InputStream in=Files.newInputStream(file)){byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1)digest.update(b,0,n);}
        StringBuilder sha=new StringBuilder();for(byte b:digest.digest())sha.append(String.format("%02x",b&255));
        HttpURLConnection c=(HttpURLConnection)new URL("http","127.0.0.1",port,"/practice/capture").openConnection(Proxy.NO_PROXY);
        c.setInstanceFollowRedirects(false);c.setConnectTimeout(1000);c.setReadTimeout(5000);c.setRequestMethod("POST");c.setDoOutput(true);c.setFixedLengthStreamingMode(size);
        c.setRequestProperty("Content-Type","application/gzip");c.setRequestProperty("X-Practice-Session",meta.getProperty("session"));c.setRequestProperty("X-Practice-Part",file.getFileName().toString());
        c.setRequestProperty("X-Practice-Host",Base64.getEncoder().encodeToString(meta.getProperty("host","").getBytes(StandardCharsets.UTF_8)));c.setRequestProperty("X-Practice-Sha256",sha.toString());
        try{try(OutputStream out=c.getOutputStream();InputStream in=Files.newInputStream(file)){byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1)out.write(b,0,n);}
            if(c.getResponseCode()!=200)throw new IOException("local lab HTTP "+c.getResponseCode());
            String response;try(BufferedReader in=new BufferedReader(new InputStreamReader(c.getInputStream(),StandardCharsets.UTF_8))){response=in.readLine();}
            if(!sha.toString().equals(response))throw new IOException("local acknowledgement mismatch");
            Files.write(ack,(sha+"\n").getBytes(StandardCharsets.UTF_8),StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING);status="local lab acknowledged "+file.getFileName();
            return true;
        }finally{c.disconnect();}
    }
    public void close(){closing=true;worker.interrupt();try{worker.join(1000);}catch(InterruptedException e){Thread.currentThread().interrupt();}}
}
