import lab.Json;
import java.nio.file.*;
import java.util.*;

/** Targeted feedback correction: adapt coast length to the locally visible gap.
 * One coast step costs roughly .2–.28 blocks of forward travel, suggesting a
 * gain near4steps/block. Testing this local feedback replaces blind word tuning.
 */
public final class ComboFeedbackSearch {
    public static void main(String[] args)throws Exception{
        Path out=Path.of(args[0]);if(Files.exists(out))throw new IllegalArgumentException("Refuse overwrite");
        double reach=Double.parseDouble(args[1]),horizontal=Double.parseDouble(args[2]),vertical=Double.parseDouble(args[3]);int ticks=Integer.parseInt(args[4]);
        double rtt=args.length>5?Double.parseDouble(args[5]):120,seconds=args.length>6?Double.parseDouble(args[6]):60;
        List<Map<String,Object>> rows=new ArrayList<>();
        for(int start:new int[]{ticks-1,ticks,ticks+1})for(int base:new int[]{3,4})for(double target:new double[]{2.0,2.2,2.4,2.6,2.8})for(double gain:new double[]{2,4,6})for(int burst:new int[]{2,3,5}){
            var p=new ComboCycleSearch.Plan(2*ticks,start,base,0,burst,0,target,gain);
            rows.add(ComboCycleSearch.trial(p,0,rtt,reach,horizontal,vertical,ticks,seconds,10,25));
        }
        rows.sort(Comparator.comparingDouble(ComboCycleSearch::rank).reversed());
        Files.writeString(out,Json.encode(Map.of("scope","Own-view range feedback around the observed coast-overbraking failure; hypothetical fixed phases0/10/25ms,2s opening handicap,20CPS ceiling; finite model evidence only","combat",List.of(horizontal,vertical,ticks),"defender_reach",reach,"rtt",List.of(0,rtt),"candidates",rows.size(),"results",rows))+"\n");
        for(var row:rows.subList(0,Math.min(5,rows.size())))System.out.println(Json.encode(row));
    }
}
