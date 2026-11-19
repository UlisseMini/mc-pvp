package lab;
import practice.replay.PoseTrack;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.GZIPInputStream;

/** Readable, independently timestamped POV tracks; missing client views are never substituted. */
final class PovReplayData {
    final Map<String,PoseTrack> tracks=new LinkedHashMap<>();final Map<String,String> quality=new LinkedHashMap<>();final List<String> warnings=new ArrayList<>();
    private final ReplayData replay;private Map<String,Object> infoCache;
    PovReplayData(ReplayData replay,List<Map<String,Object>> legacy)throws IOException{
        this.replay=replay;Path folder=Referee.DATA.resolve("pov/"+replay.id);
        practice.replay.PoseFiles files=new practice.replay.PoseFiles(folder,replay.start,ReplayData::parse);warnings.addAll(files.warnings);
        for(int i=0;i<2;i++){
            String side=i==0?"a":"b";PoseTrack track=files.tracks.getOrDefault(side,new PoseTrack(side,250));
            if(track.size()>0){track.sort();tracks.put(side,track);quality.put(side,"recorded client poses");continue;}
            track=new PoseTrack(side,500);
            for(Map<String,Object> e:legacy)if(replay.result.get(side).equals(e.get("player")))track.add((long)ReplayData.number(e,"client_ms",0)-replay.start,ReplayData.map(e.get("view")),i);
            if(track.size()>0){track.sort();tracks.put(side,track);quality.put(side,"legacy 5 Hz snapshots");}
        }
    }
    private static final ExecutorService INDEXER=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"replay-event-index");t.setDaemon(true);return t;});
    private final Map<String,CompletableFuture<ReplaySteps>> indices=new HashMap<>();
    CompletableFuture<ReplaySteps> steps(String source){
        if(source.equals("server"))return CompletableFuture.completedFuture(replay.serverSteps);
        return indices.computeIfAbsent(source,side->CompletableFuture.supplyAsync(()->{
            try{return ClientReplaySteps.read(Referee.DATA.resolve("pov/"+replay.id),side,replay.start,replay.duration);}
            catch(IOException e){throw new CompletionException(e);}
        },INDEXER));
    }
    void close(){for(var job:indices.values())job.cancel(false);}
    String source(String name){
        if(Set.of("server","a","b").contains(name))return name;
        for(String side:List.of("a","b")){boolean human="human".equals(replay.result.get("policy_"+side));if(name.equals(human?"human":"bot"))return side;}
        throw new IllegalArgumentException("Use server, human, bot, a, or b.");
    }
    Map<String,Object> at(String source,double ms){return source.equals("server")?replay.frame((long)ms):tracks.containsKey(source)?tracks.get(source).at(ms):Map.of();}
    boolean available(String source){return source.equals("server")||tracks.containsKey(source);}
    Map<String,Object> info(){if(infoCache!=null)return infoCache;Map<String,Object> out=new LinkedHashMap<>();out.put("server",Map.of("available",true,"quality","server ticks"));
        for(String s:List.of("a","b")){Map<String,Object> m=new LinkedHashMap<>();m.put("available",tracks.containsKey(s));m.put("label",replay.label(s));m.put("role","human".equals(replay.result.get("policy_"+s))?"human":"bot");if(tracks.containsKey(s)){m.put("quality",quality.get(s));m.putAll(tracks.get(s).summary());}out.put(s,m);}out.put("warnings",warnings);return infoCache=Collections.unmodifiableMap(out);}
}
