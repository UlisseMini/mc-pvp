import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.GZIPOutputStream;

/** Reuse the six reserved clocks across the complete historical field, without tuning on it. */
final class InputArchive {
    public static void main(String[] args)throws Exception{
        Path d=args.length==0?InputStudy.DIR:Path.of(args[0]);
        var candidates=new ArrayList<Arena.Fighter>();
        for(String l:Files.readAllLines(d.resolve("archive-contenders.tsv"))){
            String[] f=l.split("\t");candidates.add(f.length==1?Arena.Fighter.named(f[0]):InputStudy.fighter(f[0],Path.of(f[1])));
        }
        var clocks=new ArrayList<DuelEnv>();
        for(String l:Files.readAllLines(d.resolve(args.length==0?"test-clocks.txt":"clocks.txt")))clocks.add(InputStudy.clock(Path.of(l)));
        var pool=Executors.newFixedThreadPool(3);
        try(var out=new PrintWriter(new GZIPOutputStream(Files.newOutputStream(d.resolve("archive.csv.gz.tmp"))))){
            out.println("arm,foe,calendar,seat,own_rtt,opponent_rtt,reward");
            for(String l:Files.readAllLines(InputStudy.DIR.resolve("archive-field.tsv"))){
                String[] f=l.split("\t");
                var foe=f.length==1?Arena.Fighter.named(f[0]):InputStudy.fighter(f[0],Path.of(f[1]));
                for(var arm:candidates){
                    var batch=new ArrayList<Callable<String>>();
                    for(var c:clocks)for(int own:new int[]{31,109})for(boolean first:new boolean[]{true,false}){
                        int other=140-own;var sc=PingLeague.scenario(c,foe,first,own,other);
                        batch.add(()->String.join(",",arm.name(),foe.name(),c.clockId,first?"A":"B",""+own,""+other,""+sc.game(arm)));
                    }
                    for(var r:pool.invokeAll(batch))out.println(r.get());
                }
            }
            if(out.checkError())throw new IOException("Historical result write failed");
        }finally{pool.shutdown();}
        Files.move(d.resolve("archive.csv.gz.tmp"),d.resolve("archive.csv.gz"),StandardCopyOption.ATOMIC_MOVE);
    }
}
