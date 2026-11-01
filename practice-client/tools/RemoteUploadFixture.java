import practice.core.*;
import java.nio.file.*;
import java.util.*;
/** Local-only outbox fixture; never launches or connects to Minecraft. */
public final class RemoteUploadFixture {
    public static void main(String[] args)throws Exception{
        Path dir=Paths.get(args[1]);String id="raaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        if(args[0].equals("prepare")){
            Path first=dir.resolve("captures/a-client-fixture-events.jsonl.gz");
            CaptureLog log=new CaptureLog(first,1024*1024,8,100);
            log.offer(JsonData.encode(JsonData.map("type","capture_start","time_ms",1000,"data",JsonData.map("role","human-remote","remote_session",id))));
            Thread.sleep(150);log.offer(JsonData.encode(JsonData.map("type","remote_pose","time_ms",1100,"monotonic_ns",10,"data",JsonData.map("view",JsonData.map("self",JsonData.map("pos",Arrays.asList(0,4,0)))))));
            log.close();if(!log.awaitClosed(2000))throw new AssertionError("writer failed to close");
            try(RemoteCaptureUpload upload=new RemoteCaptureUpload(dir)){upload.register(id,"example.invalid:25565",first);Thread.sleep(2500);}
            System.out.println("LOCAL_SOURCE_READY");
        }else{
            try(RemoteCaptureUpload upload=new RemoteCaptureUpload(dir)){
                long end=System.currentTimeMillis()+8000;
                while(System.currentTimeMillis()<end){long gzip,ack;
                    try(java.util.stream.Stream<Path> s=Files.list(dir.resolve("captures"))){gzip=s.filter(p->p.toString().endsWith(".jsonl.gz")).count();}
                    try(java.util.stream.Stream<Path> s=Files.list(dir.resolve("remote-outbox"))){ack=s.filter(p->p.toString().endsWith(".ack")).count();}
                    if(gzip>0&&gzip==ack){System.out.println("RETRIED_AND_ACKED "+gzip);return;}Thread.sleep(100);
                }throw new AssertionError(upload.status());
            }
        }
    }
}
