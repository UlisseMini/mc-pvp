package practice.core;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.zip.GZIPOutputStream;

/** Bounded asynchronous capture. Neither game nor Netty thread waits for disk IO. */
public final class CaptureLog implements AutoCloseable {
    private final ArrayBlockingQueue<byte[]> queue=new ArrayBlockingQueue<>(2048);
    private final AtomicLong queuedBytes=new AtomicLong(),dropped=new AtomicLong();
    private final long limit;
    private final int maxSegments;
    private final long rotateMillis;
    private volatile int segment=1;
    private volatile boolean closing,failed,capped;
    private final Thread worker;
    public final Path path;
    private volatile String error="";
    public CaptureLog(Path path,long limit){
        this(path,limit,1);
    }
    public CaptureLog(Path path,long limit,int maxSegments){this(path,limit,maxSegments,0);}
    /** Optional time-bounded parts let a local side channel ingest closed gzip files. */
    public CaptureLog(Path path,long limit,int maxSegments,long rotateMillis){
        if(limit<1||maxSegments<1)throw new IllegalArgumentException("Positive capture limits required");
        if(rotateMillis<0)throw new IllegalArgumentException("Negative rotation interval");
        this.path=path;this.limit=limit;this.maxSegments=maxSegments;this.rotateMillis=rotateMillis;worker=new Thread(this::write,"practice-recording");worker.setDaemon(true);worker.start();
    }
    public synchronized void offer(String line){
        if(closing||failed||capped)return;
        byte[] bytes=(line+"\n").getBytes(StandardCharsets.UTF_8);
        long pending=queuedBytes.addAndGet(bytes.length);
        if(pending>32*1024*1024 || !queue.offer(bytes)){queuedBytes.addAndGet(-bytes.length);dropped.incrementAndGet();}
    }
    public long dropped(){return dropped.get();}
    public int currentPart(){return segment;}
    public boolean accepting(){return !closing&&!failed&&!capped;}
    public String status(){return failed?"Recording error: "+error:capped?"Recording size limit reached":closing?"Recording closed":"Recording "+path.getFileName()+" (part "+segment+")";}
    private Path segmentPath(int part){return part==1?path:path.resolveSibling(path.getFileName().toString().replace(".jsonl.gz","-part"+part+".jsonl.gz"));}
    private OutputStream open(Path p)throws IOException{
        if(Files.getFileStore(path.getParent()).getUsableSpace()<512L*1024*1024)throw new IOException("Less than 512 MiB free disk space");
        return new GZIPOutputStream(Files.newOutputStream(p,StandardOpenOption.CREATE_NEW),65536);
    }
    private void footer(OutputStream out,String next)throws IOException{
        out.write((JsonData.encode(JsonData.map("type","capture_end","dropped",dropped.get(),"size_capped",capped,"writer_complete",dropped.get()==0&&!capped,"continues",next))+"\n").getBytes(StandardCharsets.UTF_8));
    }
    private void write(){
        long bytes=0,opened=System.currentTimeMillis();OutputStream out=null;
        try{
            Files.createDirectories(path.getParent());
            out=open(path);
                while(!closing||!queue.isEmpty()){
                    byte[] row=queue.poll(250,TimeUnit.MILLISECONDS);if(row==null)continue;
                    queuedBytes.addAndGet(-row.length);
                    if((bytes+row.length>limit||rotateMillis>0&&bytes>0&&System.currentTimeMillis()-opened>=rotateMillis)&&row.length<=limit&&segment<maxSegments){
                        Path next=segmentPath(segment+1);footer(out,next.getFileName().toString());out.close();out=null;
                        out=open(next);segment++;bytes=0;opened=System.currentTimeMillis();
                        out.write((JsonData.encode(JsonData.map("type","capture_segment","first",path.getFileName().toString(),"part",segment))+"\n").getBytes(StandardCharsets.UTF_8));
                    }
                    if(bytes+row.length>limit){capped=true;dropped.incrementAndGet();continue;}
                    out.write(row);bytes+=row.length;
                }
                footer(out,null);
        }catch(Exception e){failed=true;error=e.toString();queue.clear();}
        finally{if(out!=null)try{out.close();}catch(IOException e){failed=true;error=e.toString();}}
    }
    public synchronized void close(){closing=true;}
    public boolean awaitClosed(long millis)throws InterruptedException{worker.join(millis);return !worker.isAlive();}
}
