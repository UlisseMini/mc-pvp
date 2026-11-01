package lab;

import java.util.LinkedHashMap;
import java.util.Map;

/** One attempt is one continuous native sword-use interval, not one blocked hit. */
public final class BlockAccuracy {
    int attempts,successful;
    boolean active,saved;
    public boolean update(boolean blocking){
        if(blocking==active)return false;
        active=blocking;
        if(blocking){attempts++;saved=false;}
        return true;
    }
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
