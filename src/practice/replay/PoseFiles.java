package practice.replay;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.Function;
import java.util.stream.*;
import java.util.zip.GZIPInputStream;

/** Shared server/client reader for the small replay pose index. Event/packet archives remain separate. */
public final class PoseFiles {
    public final Map<String,PoseTrack> tracks=new LinkedHashMap<>();public final List<String> warnings=new ArrayList<>();
    public PoseFiles(Path folder,long start,Function<String,Map<String,Object>> parse)throws IOException{
        if(!Files.isDirectory(folder))return;
        for(int side=0;side<2;side++){
            String key=side==0?"a":"b";PoseTrack track=new PoseTrack(key,250);long bytes=0;
            List<Path> paths;try(Stream<Path> stream=Files.list(folder)){paths=stream.filter(p->p.getFileName().toString().matches(key+"-client-.*-poses(?:-part[0-9]+)?\\.jsonl\\.gz")).sorted().collect(Collectors.toList());}
            for(Path path:paths){boolean complete=false;
                try(BufferedReader in=new BufferedReader(new InputStreamReader(new GZIPInputStream(Files.newInputStream(path)),"UTF-8"))){String line;
                    while((line=in.readLine())!=null){bytes+=line.length();if(bytes>128L*1024*1024)throw new IOException("POV pose index exceeds 128 MiB");Map<String,Object> row=parse.apply(line);
                        if("pov_frame".equals(row.get("type")))track.add((long)PoseTrack.number(row,"time_ms",0)-start,PoseTrack.object(row.get("view")),side);
                        if("capture_end".equals(row.get("type")))complete=Boolean.TRUE.equals(row.get("writer_complete"));
                    }
                }catch(EOFException e){warnings.add(key+": truncated archive segment");}
                if(!complete)warnings.add(key+": archive incomplete or still closing ("+path.getFileName()+")");
            }
            if(track.size()>0){track.sort();tracks.put(key,track);}
        }
    }
}
