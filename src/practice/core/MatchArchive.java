package practice.core;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Shared local archive, separate small pose index and full event stream. Never accepts remote paths. */
public final class MatchArchive implements AutoCloseable {
    private volatile Path root;private volatile String match="",source="";
    private volatile CaptureLog raw,poses;
    private final Queue<CaptureLog> finishing=new ConcurrentLinkedQueue<>();
    public synchronized void root(Path path){if(!Objects.equals(path,root))end();root=path;}
    public String match(){return match;}
    public synchronized void begin(String id,String source,Map<String,Object> metadata){
        if(root==null||!id.matches("m[0-9]{4,9}")||!source.matches("[ab]-client|server")){end();return;}
        if(match.equals(id)&&this.source.equals(source))return;end();match=id;this.source=source;
        String stem=source+"-"+System.currentTimeMillis()+"-"+UUID.randomUUID().toString().substring(0,8);
        Path folder=root.resolve(id);CaptureLog nextRaw=new CaptureLog(folder.resolve(stem+"-events.jsonl.gz"),128L*1024*1024,8);
        CaptureLog nextPoses=source.equals("server")?null:new CaptureLog(folder.resolve(stem+"-poses.jsonl.gz"),32L*1024*1024,8);
        String header=JsonData.encode(JsonData.map("type","pov_archive_start","schema",1,"match",match,"source",source,"time_ms",System.currentTimeMillis(),"metadata",metadata));
        // Publish writers only after their identity header is queued. Netty observers
        // can otherwise win this race and write a packet before source metadata.
        nextRaw.offer(header);if(nextPoses!=null)nextPoses.offer(header);poses=nextPoses;raw=nextRaw;
        finishing.removeIf(l->{try{return l.awaitClosed(1);}catch(InterruptedException e){Thread.currentThread().interrupt();return false;}});
    }
    public void event(String line){CaptureLog r=raw;if(r!=null)r.offer(line);}
    public void pose(long ms,long ns,Map<String,Object> view){CaptureLog p=poses;if(p!=null){
        Map<String,Object> row=JsonData.map("type","pov_frame","time_ms",ms,"monotonic_ns",ns,"match",match,"source",source,"view",view);event(JsonData.encode(row));
        row.put("view",JsonData.map("self",compact(view.get("self")),"opponent",compact(view.get("opponent")),"partial_ticks",view.get("partial_ticks")));p.offer(JsonData.encode(row));
    }}
    private static Map<String,Object> compact(Object o){Map<String,Object> out=new LinkedHashMap<>();if(!(o instanceof Map))return out;Map<?,?> in=(Map<?,?>)o;
        for(String k:Arrays.asList("pos","posX","posY","posZ","render","yaw","pitch","rotationYaw","rotationPitch","render_yaw","render_pitch","rotationYawHead","renderYawOffset","ground","onGround","sprint","sprinting","sneaking","blocking","health","eye_height","box","border","held_item_id","fish_hook","isSwingInProgress","swingProgressInt","swingProgress","limbSwing","limbSwingAmount","hurtTime"))if(in.containsKey(k))out.put(k,in.get(k));return out;}
    public synchronized void end(){for(CaptureLog l:new CaptureLog[]{raw,poses})if(l!=null){l.close();finishing.add(l);}raw=poses=null;match="";source="";}
    public String status(){CaptureLog p=poses,r=raw;return r==null?"archive idle":r.status()+"; poses: "+(p==null?"server ticks":p.status());}
    public void close(){end();long deadline=System.currentTimeMillis()+2000;for(CaptureLog l:finishing)try{l.awaitClosed(Math.max(1,deadline-System.currentTimeMillis()));}catch(InterruptedException e){Thread.currentThread().interrupt();break;}}
}
