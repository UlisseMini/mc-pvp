package practice.core;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.zip.GZIPOutputStream;

/** Bounded asynchronous capture. Neither game nor Netty thread waits for disk IO. */
public final class CaptureLog implements AutoCloseable {
    private final ArrayBlockingQueue<Row> queue;
    private final AtomicLong queuedBytes=new AtomicLong(),dropped=new AtomicLong();
    private final long pendingLimit;
    private final long limit;
    private final int maxSegments;
    private final long rotateMillis;
    private final Consumer<Path> partClosed;
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
        this(path,limit,maxSegments,rotateMillis,2048,32L*1024*1024,file->{});
    }
    public CaptureLog(Path path,long limit,int maxSegments,long rotateMillis,int pendingRows,long pendingLimit,Consumer<Path> partClosed){
        if(limit<1||maxSegments<1)throw new IllegalArgumentException("Positive capture limits required");
        if(rotateMillis<0)throw new IllegalArgumentException("Negative rotation interval");
        if(pendingLimit<1)throw new IllegalArgumentException("Positive pending limit required");
        this.path=path;this.limit=limit;this.maxSegments=maxSegments;this.rotateMillis=rotateMillis;
        this.queue=new ArrayBlockingQueue<>(pendingRows);this.pendingLimit=pendingLimit;this.partClosed=partClosed;
        worker=new Thread(this::write,"practice-recording");worker.setDaemon(true);worker.start();
    }
    public synchronized void offer(String line){
        if(!accepting())return;
        byte[] bytes=encodeLine(line);
        enqueue(new Row(bytes.length,bytes,null));
    }
    /** The encoder must own its data and capture no live game objects. Charge retained memory before queueing. */
    public synchronized void offerDeferred(long estimatedBytes,Supplier<String> encoder){
        if(!accepting())return;
        enqueue(new Row(estimatedBytes,null,encoder));
    }
    private void enqueue(Row row){
        long pending=queuedBytes.addAndGet(row.estimatedBytes);
        if(pending>pendingLimit || !queue.offer(row)){queuedBytes.addAndGet(-row.estimatedBytes);dropped.incrementAndGet();}
    }
    private static byte[] encodeLine(String line){return (line+"\n").getBytes(StandardCharsets.UTF_8);}
    private static final class Row {
        final long estimatedBytes;
        final byte[] bytes;
        final Supplier<String> encoder;
        Row(long estimatedBytes,byte[] bytes,Supplier<String> encoder){
            this.estimatedBytes=estimatedBytes;this.bytes=bytes;this.encoder=encoder;
        }
        byte[] encode(){return bytes==null?encodeLine(encoder.get()):bytes;}
    }
    public long dropped(){return dropped.get();}
    public int currentPart(){return segment;}
    public boolean accepting(){return !closing&&!failed&&!capped;}
    public String status(){
        if(failed)return "Recording error: "+error;
        if(capped)return "Recording size limit reached";
        return (closing?"Recording closed":"Recording "+path.getFileName()+" (part "+segment+")")
            +(dropped.get()==0?"":"; dropped records "+dropped.get());
    }
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
            Path current=path;
            out=open(current);
            while(!closing||!queue.isEmpty()){
                Row pending=queue.poll(250,TimeUnit.MILLISECONDS);if(pending==null)continue;
                byte[] row=pending.encode();
                queuedBytes.addAndGet(-pending.estimatedBytes);
                if((bytes+row.length>limit||rotateMillis>0&&bytes>0&&System.currentTimeMillis()-opened>=rotateMillis)&&row.length<=limit&&segment<maxSegments){
                    Path next=segmentPath(segment+1);footer(out,next.getFileName().toString());out.close();out=null;
                    partClosed.accept(current);
                    current=next;out=open(current);segment++;bytes=0;opened=System.currentTimeMillis();
                    out.write(encodeLine(JsonData.encode(JsonData.map("type","capture_segment","first",path.getFileName().toString(),"part",segment))));
                }
                if(bytes+row.length>limit){capped=true;dropped.incrementAndGet();continue;}
                out.write(row);bytes+=row.length;
            }
            footer(out,null);
            out.close();out=null;
            partClosed.accept(current);
        }catch(Exception e){failed=true;error=e.toString();queue.clear();}
        finally{if(out!=null)try{out.close();}catch(IOException e){failed=true;error=e.toString();}}
    }
    public synchronized void close(){closing=true;}
    public boolean closed(){return !worker.isAlive();}
    public boolean awaitClosed(long millis)throws InterruptedException{worker.join(millis);return !worker.isAlive();}
}
