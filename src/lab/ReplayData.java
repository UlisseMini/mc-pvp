package lab;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.GZIPInputStream;

/** Reads only completed local match recordings. Uses Minecraft's existing Gson. */
final class ReplayData {
    private static final Object GSON=R.construct("com.google.gson.Gson");
    final String id;
    final Map<String,Object> result;
    Map<String,Object> setup=Map.of();
    final List<Map<String,Object>> frames=new ArrayList<>(),events=new ArrayList<>();
    final List<Long> times=new ArrayList<>();
    final long start,duration;
    final double originalCenter;
    final boolean posesRecorded;
    @SuppressWarnings("unchecked") static Map<String,Object> map(Object value){return value instanceof Map?(Map<String,Object>)value:Map.of();}
    static double number(Map<String,Object> m,String key,double fallback){Object v=m.get(key);return v instanceof Number?((Number)v).doubleValue():fallback;}
    static String string(Map<String,Object> m,String key,String fallback){Object v=m.get(key);return v instanceof String?(String)v:fallback;}
    static boolean flag(Map<String,Object> m,String key){return Boolean.TRUE.equals(m.get(key));}
    static Map<String,Object> parse(String text){return map(R.call(GSON,"fromJson",text,Map.class));}
    static Path resultPath(String id){
        if(!id.matches("m[0-9]{4,9}"))throw new IllegalArgumentException("Use a match ID such as m0930, or /replay list.");
        return Referee.DATA.resolve("results/"+id+".json");
    }
    static Map<String,Object> result(String id)throws IOException {
        Path path=resultPath(id);
        if(!Files.isRegularFile(path))throw new IllegalArgumentException("No completed recording for "+id+". Use /replay list.");
        return parse(Files.readString(path));
    }
    static List<Map<String,Object>> recent(String player,boolean all)throws IOException {
        List<Map<String,Object>> out=new ArrayList<>();
        try(var paths=Files.list(Referee.DATA.resolve("results"))){
            for(Path p:paths.filter(p->p.getFileName().toString().matches("m[0-9]{4,9}\\.json"))
                    .sorted(Comparator.<Path>comparingLong(p->Long.parseLong(p.getFileName().toString().substring(1).replace(".json",""))).reversed()).toList()){
                Map<String,Object> r=parse(Files.readString(p));
                if(all||player.equals(r.get("a"))||player.equals(r.get("b")))out.add(r);
                if(out.size()==10)break;
            }
        }
        return out;
    }
    ReplayData(String id)throws IOException {
        this.id=id;result=result(id);start=(long)number(result,"start",0);
        duration=Math.max(0,(long)number(result,"finish",start)-start);
        originalCenter=4096*(number(result,"arena",0)+1);
        Path path=Referee.DATA.resolve("replays/"+id+".jsonl");boolean gzip=false;
        if(!Files.isRegularFile(path)){path=R.ROOT.resolve("research/traces/"+id+"-server.jsonl.gz");gzip=true;}
        if(!Files.isRegularFile(path))throw new IllegalArgumentException("The result exists, but its server recording is missing.");
        if(Files.size(path)>64*1024*1024)throw new IllegalArgumentException("Recording is too large for interactive playback.");
        try(InputStream raw=Files.newInputStream(path);InputStream in=gzip?new GZIPInputStream(raw):raw;
            BufferedReader reader=new BufferedReader(new InputStreamReader(in,java.nio.charset.StandardCharsets.UTF_8))){
            long bytes=0;String line;
            while((line=reader.readLine())!=null){
                bytes+=line.length();if(bytes>128*1024*1024)throw new IllegalArgumentException("Recording exceeds playback limit.");
                Map<String,Object> e=parse(line);String type=string(e,"type","");
                if(type.equals("start")){setup=map(e.get("match"));continue;}
                long time=(long)number(e,"time",0);if(time<start)continue;
                if(type.equals("tick"))frames.add(e);
                else if(Set.of("hit","critical","blocked_damage","rod_trick").contains(type)){
                    events.add(e);
                    if(type.equals("hit")&&!frames.isEmpty()){
                        Map<String,Object> f=new LinkedHashMap<>(frames.get(frames.size()-1));f.put("time",time);
                        String attacker=Objects.equals(e.get("attacker"),result.get("a"))?"a":"b",victim=attacker.equals("a")?"b":"a";
                        for(String side:List.of("a","b")){
                            Map<String,Object> p=new LinkedHashMap<>(map(f.get(side)));
                            p.putAll(map(e.get(side.equals(attacker)?"attacker_state":"target_state")));f.put(side,p);
                        }
                        f.put("health_"+victim,e.get("health_after"));f.put("hits_a",e.get("hits_a"));f.put("hits_b",e.get("hits_b"));frames.add(f);
                    }
                }
            }
        }
        if(frames.isEmpty())throw new IllegalArgumentException("This recording has no playable server samples.");
        Map<String,Object> last=new LinkedHashMap<>(frames.get(frames.size()-1));last.put("time",start+duration);
        for(String side:List.of("a","b"))if(result.get("health_"+side) instanceof Number)last.put("health_"+side,result.get("health_"+side));
        frames.add(last);
        frames.sort(Comparator.comparingDouble(f->number(f,"time",0)));events.sort(Comparator.comparingDouble(e->number(e,"time",0)));
        for(Map<String,Object> f:frames)times.add((long)number(f,"time",start)-start);
        posesRecorded=map(frames.get(0).get("a")).containsKey("rotationYaw");
    }
    Map<String,Object> frame(long ms){return frames.get(ReplayTimeline.floor(times,ms));}
    String label(String side){String policy=string(result,"policy_"+side,side);return policy.equals("human")?string(result,side,side):policy;}
}
