import java.nio.file.*;
import java.util.*;
import lab.Json;

/** Test which part of the v4 impulse law sustains a combo, starting every arm at one identical establishment. */
final class RecordedBoxingRangeFork {
    static Map<String,Object> suffix(BoxingDrill.Checkpoint cp,Arena.Model actor,int mask){
        var g=cp.suffix(actor);var c=g.clock.config;
        if(!c.rangeMmc||c.hitTicks!=10)throw new AssertionError("Wrong prefix law");
        c.omitRangeAttenuation=(mask&1)!=0;c.omitRangeDirectionBlend=(mask&2)!=0;
        // Removing both components is exactly v3. Use the archived path for this control.
        if(mask==3)c.rangeMmc=false;
        int[] cycles={0,0};double[] stop={Double.NaN,Double.NaN};String[] why={"censored","censored"};
        double[] last={cp.landmark,cp.landmark};int seen=0;double exposure=0;
        do{
            while(seen<g.hits.list.size()){
                var h=g.hits.list.get(seen++);
                for(int end=0;end<2;end++){
                    if(Double.isFinite(stop[end]))continue;
                    if(end==1&&h.at()-last[end]>1100){stop[end]=last[end]+1100;why[end]="inactive";continue;}
                    if(h.sideA()!=g.first){stop[end]=h.at();why[end]="return";continue;}
                    if(!h.upgrade()){cycles[end]++;last[end]=h.at();}
                }
            }
            exposure=g.clock.time();
            if(!Double.isFinite(stop[1])&&exposure-last[1]>1100){stop[1]=last[1]+1100;why[1]="inactive";}
            if(Double.isFinite(stop[0])&&Double.isFinite(stop[1]))break;
        }while(g.advance());
        var result=new LinkedHashMap<String,Object>();
        for(int end=0;end<2;end++)result.put(end==0?"return":"active",Map.of("cycles",cycles[end],
                    "terminal",why[end],"ms",(Double.isFinite(stop[end])?stop[end]:exposure)-cp.landmark));
        return result;
    }
    public static void main(String[] args)throws Exception{
        var candidates=Matches.roster(Path.of(args[0]));
        for(int i=1;i<args.length;i++){
            int limit=60000;var tape=DuelEnv.Tape.read(Path.of(args[i]),limit);
            for(int rtt:new int[]{0,80}){
                var env=new DuelEnv(tape,"mmc-range",rtt,rtt,false,29,true,10,6,0,limit);env.initial.timing.mouseInput=true;
                for(boolean seat:List.of(true,false)){
                    var records=new ArrayList<Object>();
                    for(var cp:BoxingDrill.acquire(env,seat,true)){
                        String fingerprint=FullClockHash.digest(cp.state.clock);var arms=new TreeMap<String,Object>();
                        for(var f:candidates){
                            var results=new LinkedHashMap<String,Object>();
                            for(int law=0;law<4;law++)results.put(List.of("v4","angle_only","distance_only","v3").get(law),suffix(cp,f.model(),law));
                            arms.put(f.name(),results);
                        }
                        if(!fingerprint.equals(FullClockHash.digest(cp.state.clock)))throw new AssertionError("Source physics was mutated");
                        records.add(Map.of("k",cp.k,"landmark",cp.landmark,"prefix",fingerprint,"arms",arms));
                    }
                    System.out.println(Json.encode(Map.of("clock",env.clockId,"rtt",rtt,"side",seat?"a":"b","limit_ms",limit,"starts",records)));
                }
            }
        }
    }
}
