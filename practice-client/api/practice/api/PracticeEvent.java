package practice.api;

import java.util.*;

/** Immutable, timestamped observation delivered on Minecraft's client thread. */
public final class PracticeEvent {
    public final String type;
    public final long sequence,timeMillis,timeNanos;
    public final Map<String,Object> data;
    public PracticeEvent(String type,long sequence,long millis,long nanos,Map<String,Object> data){
        this.type=type;this.sequence=sequence;this.timeMillis=millis;this.timeNanos=nanos;
        this.data=freezeMap(data);
    }
    private static Map<String,Object> freezeMap(Map<String,Object> source){
        Map<String,Object> out=new LinkedHashMap<>();for(Map.Entry<String,Object> e:source.entrySet())out.put(e.getKey(),freeze(e.getValue()));
        return Collections.unmodifiableMap(out);
    }
    @SuppressWarnings("unchecked") private static Object freeze(Object v){
        if(v instanceof Map)return freezeMap((Map<String,Object>)v);
        if(v instanceof List){List<Object> out=new ArrayList<>();for(Object x:(List<?>)v)out.add(freeze(x));return Collections.unmodifiableList(out);}
        if(v==null||v instanceof String||v instanceof Number||v instanceof Boolean)return v;
        throw new IllegalArgumentException("Observation must contain data, not live game objects");
    }
}
