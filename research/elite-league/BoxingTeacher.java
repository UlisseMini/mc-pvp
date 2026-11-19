import java.io.*;
import java.nio.file.*;
import java.util.*;
import lab.DuelNetwork;
import lab.Json;

/** Only the offline teacher sees future returns. Input rows contain the student's own observable history. */
final class BoxingTeacher {
    static int key(PeriodicComboClock.Control c){
        return DuelNetwork.pack(c.key(),(int)Math.signum(c.strafe()),c.click(),c.jump());
    }
    static void record(BoxingDrill.Checkpoint s,BufferedWriter rows,BufferedWriter detail,int id)throws Exception{
        var best=BoxingOracle.search(s);
        var words=(List<?>)best.get("words");
        var encoder=new Arena.Neural(new Arena.Model(DuelNetwork.Family.LINEAR,
                new float[DuelNetwork.size(DuelNetwork.Family.LINEAR)]),s.env.rtt);
        for(var h:s.history){encoder.input(h.view());encoder.net.applied(key(h.applied()));}
        var root=new BoxingOracle.Node(s,true);var driver=(BoxingOracle.Driver)root.g.our();
        var data=new ArrayList<String>();
        driver.trace=(o,a,word)->{
            encoder.input(o);
            StringBuilder line=new StringBuilder();line.append(id).append(' ').append(word).append(' ').append(key(a));
            for(double v:encoder.net.features())line.append(' ').append(v);
            data.add(line.toString());encoder.net.applied(key(a));
        };
        for(var w:words)root=root.play(Arrays.asList(BoxingOracle.NAMES).indexOf(w));
        var tail=root.tail();
        if(tail.cycles()!=((Number)((List<?>)best.get("hindsight")).get(0)).intValue())throw new AssertionError("Teacher playback changed");
        best.put("id",id);best.put("rows",data.size());
        for(String l:data){rows.write(l);rows.newLine();}
        detail.write(Json.encode(best));detail.newLine();
    }
    public static void main(String[] args)throws Exception{
        Path out=Path.of(args[0]);int id=0;
        try(var rows=Files.newBufferedWriter(out.resolve("actions.txt"),StandardOpenOption.CREATE_NEW);
            var detail=Files.newBufferedWriter(out.resolve("starts.jsonl"),StandardOpenOption.CREATE_NEW)){
            for(String f:Arrays.copyOfRange(args,1,args.length)){
                var tape=DuelEnv.Tape.read(Path.of(f),20000);
                for(int[] shift:new int[][]{{0,0},{13,31},{37,7},{23,43}}){
                    var t=BoxingDrill.shift(tape,shift[0],shift[1]);
                    for(int ping:new int[]{0,80})for(boolean side:new boolean[]{true,false}){
                        for(var s:BoxingDrill.acquire(new DuelEnv(t,"mmc",ping,false),side,false))record(s,rows,detail,id++);
                    }
                }
                rows.flush();detail.flush();
            }
        }
        System.out.println("Teacher openings "+id);
    }
}
