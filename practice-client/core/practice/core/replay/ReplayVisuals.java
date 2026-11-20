package practice.core.replay;

import java.util.Arrays;
import java.util.Map;
import practice.replay.PoseTrack;
import static practice.replay.PoseTrack.number;

/** Smooth display coordinates, leaving the recorded logical pose untouched. */
public final class ReplayVisuals {
    private ReplayVisuals(){}

    public static Map<String,Object> between(Map<String,Object> from,Map<String,Object> to,double fraction){
        Object id=from.get("id");
        if(fraction<=0||id==null||!id.equals(to.get("id")))return from;
        Map<String,Object> a=PoseTrack.normalize(from),b=PoseTrack.normalize(to);
        if(a.isEmpty()||b.isEmpty())return from;
        double dx=number(b,"posX",0)-number(a,"posX",0);
        double dy=number(b,"posY",0)-number(a,"posY",0);
        double dz=number(b,"posZ",0)-number(a,"posZ",0);
        if(dx*dx+dy*dy+dz*dz>64)return from;
        Map<String,Object> result=PoseTrack.interpolate(a,b,fraction);
        result.put("render",Arrays.asList(result.get("posX"),result.get("posY"),result.get("posZ")));
        result.put("render_yaw",result.get("rotationYaw"));
        result.put("render_pitch",result.get("rotationPitch"));
        for(String key:Arrays.asList("hand_yaw_offset","hand_pitch_offset")){
            if(!(a.get(key) instanceof Number)||!(b.get(key) instanceof Number))continue;
            double offset=number(a,key,0);
            result.put(key,offset+(number(b,key,offset)-offset)*fraction);
        }
        double swing=number(a,"swingProgress",0),next=number(b,"swingProgress",swing);
        if(next<swing){
            double wrapped=(swing+(1-swing+next)*fraction)%1;
            result.put("swingProgress",wrapped);
        }
        if(result.containsKey("swingProgress"))result.put("prevSwingProgress",result.get("swingProgress"));
        return result;
    }
}
