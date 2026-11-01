package lab;

import java.util.*;

/** Maximal unanswered melee-hit runs, in the recording's server event order. */
public final class ReplayCombos {
    static final long LEAD_IN_MS=750;
    static final int PAGE_SIZE=20;
    static final class Combo {
        final int side,hits;
        final long firstMs,lastMs;
        Combo(int side,int hits,long firstMs,long lastMs){this.side=side;this.hits=hits;this.firstMs=firstMs;this.lastMs=lastMs;}
        long seekMs(){return Math.max(0,firstMs-LEAD_IN_MS);}
    }
    private static Number number(Map<String,Object> event,String key){Object value=event.get(key);return value instanceof Number?(Number)value:null;}
    private static long hitIndex(Map<String,Object> event){
        Number index=number(event,"hit_index");if(index!=null&&index.longValue()>0)return index.longValue();
        Number a=number(event,"hits_a"),b=number(event,"hits_b");return a!=null&&b!=null?a.longValue()+b.longValue():-1;
    }
    static List<Combo> find(List<Map<String,Object>> events,String a,String b,long start,boolean boxing){
        Map<Long,Map<String,Object>> damage=new HashMap<>();
        for(Map<String,Object> event:events)if("melee_damage".equals(event.get("type"))&&hitIndex(event)>0)damage.put(hitIndex(event),event);
        List<Combo> out=new ArrayList<>();int side=-1,count=0;long first=0,last=0;
        // Input is stably sorted by server time. Same-time trades retain recorded call order.
        for(Map<String,Object> event:events){
            if(!"hit".equals(event.get("type"))||Boolean.TRUE.equals(event.get("rod")))continue;
            int next=Objects.equals(a,event.get("attacker"))&&Objects.equals(b,event.get("target"))?0:
                Objects.equals(b,event.get("attacker"))&&Objects.equals(a,event.get("target"))?1:-1;
            Number time=number(event,"time");if(next<0||time==null||time.longValue()<start)continue;
            Map<String,Object> detail=damage.get(hitIndex(event));
            // Boxing intentionally suppresses HP loss. Older recordings without melee_damage
            // also use accepted melee contacts; modern health modes match the result's Hits.
            if(!boxing&&detail!=null&&Objects.equals(event.get("attacker"),detail.get("attacker"))&&
                    Objects.equals(event.get("target"),detail.get("target"))){
                Number loss=number(detail,"health_loss");if(loss!=null&&!(loss.doubleValue()>0))continue;
            }
            long ms=time.longValue()-start;
            if(next!=side){
                if(count>=3)out.add(new Combo(side,count,first,last));
                side=next;count=0;first=ms;
            }
            count++;last=ms;
        }
        if(count>=3)out.add(new Combo(side,count,first,last));
        return List.copyOf(out);
    }
    static String timestamp(long ms){return String.format(Locale.ROOT,"%d:%05.2f",ms/60000,(ms%60000)/1000.0);}
}
