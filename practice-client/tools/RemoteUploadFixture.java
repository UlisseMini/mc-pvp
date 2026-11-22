package practice.core;

import java.nio.file.*;
import java.util.*;
/** Local-only outbox fixture; never launches or connects to Minecraft. */
public final class RemoteUploadFixture {
    public static void main(String[] args)throws Exception{
        Path dir=Paths.get(args[1]);
        Files.createDirectories(dir.resolve("remote-outbox"));
        try(RemoteCaptureUpload upload=new RemoteCaptureUpload(dir,Integer.parseInt(args[2]))){
            boolean live=args[0].equals("live");
            if(!args[0].equals("retry")){
                String id=live?"rbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb":"raaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
                Path first=dir.resolve("captures/a-client-fixture-events.jsonl.gz");
                CaptureLog log=new CaptureLog(first,1024*1024,8,100,2048,32L*1024*1024,
                    upload.register(id,"example.invalid:25565",first));
                log.offer(JsonData.encode(JsonData.map("type","capture_start","time_ms",1000,"data",JsonData.map("role","human-remote","remote_session",id))));
                Thread.sleep(150);
                log.offerDeferred(4096,()->JsonData.encode(JsonData.map("type","remote_pose","time_ms",1100,"monotonic_ns",10,"data",JsonData.map("view",JsonData.map("self",JsonData.map("pos",Arrays.asList(0,4,0)))))));
                log.close();if(!log.awaitClosed(2000))throw new AssertionError("writer failed to close");
                if(!live){System.out.println("LOCAL_SOURCE_READY");return;}
            }
            long end=System.currentTimeMillis()+8000;
            while(System.currentTimeMillis()<end){
                long gzip,ack;
                try(java.util.stream.Stream<Path> s=Files.list(dir.resolve("captures"))){gzip=s.filter(p->p.toString().endsWith(".jsonl.gz")).count();}
                try(java.util.stream.Stream<Path> s=Files.list(dir.resolve("remote-outbox"))){ack=s.filter(p->p.toString().endsWith(".ack")).count();}
                if(gzip>0&&gzip==ack){System.out.println("ACKNOWLEDGED "+gzip);return;}
                Thread.sleep(100);
            }throw new AssertionError(upload.status());
        }
    }
}
