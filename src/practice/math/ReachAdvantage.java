package practice.math;
import java.util.*;
import static practice.core.JsonData.map;

/** Each distance is measured wholly inside its owner's view, using logical hit-test positions. */
public final class ReachAdvantage {
    private ReachAdvantage(){}
    private static double n(Map<String,Object> m,String k,double d){Object v=m.get(k);return v instanceof Number?((Number)v).doubleValue():d;}
    private static double[] vector(Object value,int size){if(!(value instanceof List)||((List<?>)value).size()!=size)return null;double[] out=new double[size];for(int i=0;i<size;i++){Object v=((List<?>)value).get(i);if(!(v instanceof Number))return null;out[i]=((Number)v).doubleValue();if(!Double.isFinite(out[i]))return null;}return out;}
    public static double distance(Map<String,Object> self,Map<String,Object> opponent){
        double[] eye=vector(self.get("pos"),3),box=vector(opponent.get("box"),6);if(eye==null||box==null)return Double.NaN;
        eye[1]+=n(self,"eye_height",1.62);return ReachMath.distance(eye,ReachMath.expand(box,n(opponent,"border",.1)));
    }
    public static Map<String,Object> compare(Map<String,Object> yourSelf,Map<String,Object> yourOpponent,Map<String,Object> theirSelf,Map<String,Object> theirOpponent){
        double d1=distance(theirSelf,theirOpponent),d2=distance(yourSelf,yourOpponent);
        if(!Double.isFinite(d1)||!Double.isFinite(d2))return Collections.emptyMap();
        return map("d1_theirs",d1,"d2_yours",d2,"advantage",d1-d2,"yours_in_reach",d2<=3,"theirs_in_reach",d1<=3,"definition","opponent_distance_minus_your_distance; logical eye to expanded hitbox; ideal aim");
    }
}
