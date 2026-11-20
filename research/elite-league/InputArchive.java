import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.GZIPOutputStream;

/** Evaluate a fresh run on the active 7-CPS field, or reproduce the frozen 20-CPS archive. */
final class InputArchive {
    public static void main(String[] args)throws Exception{
        if(args.length<1 || args.length>2)throw new IllegalArgumentException("InputArchive RUN_DIRECTORY [historical-field-257.tsv]");
        Path d=Path.of(args[0]);
        Path historical=InputStudy.DIR.resolve("historical-field-257.tsv").toAbsolutePath().normalize();
        boolean replay=args.length==2;
        if(replay && !Path.of(args[1]).toAbsolutePath().normalize().equals(historical))throw new IllegalArgumentException("Expected "+historical);
        if(Files.exists(d.resolve("archive.csv.gz")))throw new IOException("Archive results already exist in "+d);
        Path field=replay?historical:InputStudy.DIR.resolve("archive-field.tsv");
        var candidates=new ArrayList<Arena.Fighter>();
        for(String l:Files.readAllLines(d.resolve("archive-contenders.tsv"))){
            String[] f=l.split("\t");candidates.add(f.length==1?Arena.Fighter.named(f[0]):InputStudy.fighter(f[0],Path.of(f[1])));
        }
        var clocks=new ArrayList<DuelEnv>();
        boolean original=d.toAbsolutePath().normalize().equals(InputStudy.DIR.toAbsolutePath().normalize());
        if(original && !replay)throw new IllegalArgumentException("Choose a fresh run directory for the active field");
        var cap=new Properties();cap.setProperty("click_cps","7");
        for(String l:Files.readAllLines(d.resolve(original?"test-clocks.txt":"clocks.txt"))){
            DuelEnv clock=InputStudy.clock(Path.of(l));
            if(!replay)clock.initial.timing.clickIntervalNanos=lab.ClickRate.intervalNanos(cap);
            clocks.add(clock);
        }
        var pool=Executors.newFixedThreadPool(3);
        try(var out=new PrintWriter(new GZIPOutputStream(Files.newOutputStream(d.resolve("archive.csv.gz.tmp"))))){
            out.println("arm,foe,calendar,seat,own_rtt,opponent_rtt,reward");
            for(String l:Files.readAllLines(field)){
                String[] f=l.split("\t");
                if(f.length>1){
                    String digest=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(Path.of(f[1]))));
                    if(f.length!=3 || !digest.equals(f[2]))throw new IOException("Changed archive opponent: "+f[0]);
                }
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
