package practice.bridge;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.Function;
import java.util.zip.GZIPInputStream;
import practice.replay.PoseTrack;
import static practice.replay.PoseTrack.*;
/** Only the independent other-client index is needed for advantage. Keep just
 * overlay fields; parsing both complete actor inventories duplicates needless data. */
final class OfflinePoseLinks {
    static Map<String,PoseTrack> load(Path folder,long origin,String own,Function<String,Map<String,Object>> parse)throws IOException{
        if(own==null)return Collections.emptyMap();String side=own.equals("a")?"b":"a";PoseTrack track=new PoseTrack(side,250);List<Path> paths=new ArrayList<>();
        try(DirectoryStream<Path> stream=Files.newDirectoryStream(folder)){for(Path p:stream)if(p.getFileName().toString().matches(side+"-client-.*-poses(?:-part[0-9]+)?\\.jsonl\\.gz"))paths.add(p);}Collections.sort(paths);long bytes=0;
        for(Path p:paths)try(BufferedReader in=new BufferedReader(new InputStreamReader(new GZIPInputStream(Files.newInputStream(p)),"UTF-8"))){String line;while((line=in.readLine())!=null){bytes+=line.length();if(bytes>128L*1024*1024)throw new IOException("Linked pose index exceeds128MiB");Map<String,Object> row=parse.apply(line);if("pov_frame".equals(row.get("type"))){Map<String,Object> view=object(row.get("view"));track.add((long)number(row,"time_ms",0)-origin,practice.core.JsonData.map("self",small(object(view.get("self"))),"opponent",small(object(view.get("opponent")))),own.equals("a")?1:0);if(track.size()>20000)throw new IOException("Linked pose sample limit exceeded");}}}
        if(track.size()==0)return Collections.emptyMap();track.sort();return Collections.singletonMap(side,track);
    }
    static Map<String,Object> small(Map<String,Object> pose){Map<String,Object> out=new LinkedHashMap<>();for(String k:Arrays.asList("id","pos","render","box","yaw","pitch","render_yaw","render_pitch","posX","posY","posZ","rotationYaw","rotationPitch","eye_height","border","hurtTime","ground","sprinting"))if(pose.containsKey(k))out.put(k,pose.get(k));return out;}
}
