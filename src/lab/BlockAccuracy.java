package lab;

import java.util.LinkedHashMap;
import java.util.Map;

/** One attempt is one continuous native sword-use interval, not one blocked hit. */
public final class BlockAccuracy {
    int attempts,successful;
    boolean active,saved;
    long activeSince,totalMs;
    public boolean update(boolean blocking){
        return update(blocking,System.currentTimeMillis());
    }
    public boolean update(boolean blocking,long now){
        if(blocking==active)return false;
        if(active)totalMs+=Math.max(0,now-activeSince);
        active=blocking;
        if(blocking){attempts++;saved=false;activeSince=now;}
        return true;
    }
    public long totalMillis(long now){return totalMs+(active?Math.max(0,now-activeSince):0);}
    public void damageReduced(){
        // Includes a guard already held when the duel countdown ended.
        update(true);
        if(!saved){successful++;saved=true;}
    }
    public Map<String,Object> state(){
        Map<String,Object> m=new LinkedHashMap<>();m.put("attempts",attempts);m.put("successful_attempts",successful);
        m.put("accuracy_percent",attempts==0?null:100.0*successful/attempts);m.put("active",active);return m;
    }
}
