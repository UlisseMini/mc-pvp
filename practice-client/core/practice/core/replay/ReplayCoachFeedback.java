package practice.core.replay;

import java.io.IOException;
import java.util.*;
import practice.core.SumoStats;
import static practice.replay.PoseTrack.number;
import static practice.replay.PoseTrack.object;

/** Native coach cues indexed by participant and playhead. Older archives can recover
 * a brake from the applied hurt/velocity pair and both sides of a native attack. */
public final class ReplayCoachFeedback {
    public static final int BRAKE_MS=160, COUNT_MS=480, AIM_MS=180;
    private final NavigableMap<Double,Integer> counts=new TreeMap<>();
    private final NavigableSet<Double> firstCuts=new TreeSet<>(),aims=new TreeSet<>();

    public void brake(double ms,int cuts,boolean first){
        if(first)firstCuts.add(ms);
        counts.put(ms,cuts);
    }
    public void aim(double ms){aims.add(ms);}
    public Map<String,Object> at(double ms){
        Double first=firstCuts.floor(ms),aim=aims.floor(ms);
        Map.Entry<Double,Integer> count=counts.floorEntry(ms);
        Map<String,Object> out=new HashMap<>();
        if(first!=null&&ms-first<BRAKE_MS)out.put("brake",true);
        if(aim!=null&&ms-aim<AIM_MS)out.put("aim",true);
        if(count!=null&&ms-count.getKey()<COUNT_MS&&count.getValue()>1)out.put("cuts",count.getValue());
        return out;
    }

    static final class Reader {
        final ReplayCoachFeedback cues=new ReplayCoachFeedback(),recorded=new ReplayCoachFeedback();
        boolean recordedBrake;
        Map<String,Object> at(double ms){
            Map<String,Object> out=recordedBrake?recorded.at(ms):cues.at(ms);
            if(!recordedBrake)out.putAll(recorded.at(ms));
            return out;
        }
        final String opponent;
        private int self=-1,target=-1,moves=SumoStats.MOVES,cuts;
        private boolean confirmed;
        private double hurt=Double.NEGATIVE_INFINITY,impact=Double.NEGATIVE_INFINITY,axisX,axisZ;
        private final Map<Long,byte[]> packets=new HashMap<>();
        private Map<String,Object> before;
        private int clickTick;
        private String clickThread;
        Reader(String opponent){this.opponent=opponent;}

        void reset(){
            moves=SumoStats.MOVES;cuts=0;confirmed=false;
            hurt=impact=Double.NEGATIVE_INFINITY;before=null;packets.clear();self=target=-1;
        }
        void accept(String type,double ms,Map<String,Object> row,Map<String,Object> data){
            switch(type){
                case "pov_archive_start":reset();return;
                case "sumo_coach_cue":
                    if("AIM".equals(data.get("cue")))recorded.aim(ms);
                    if("BRAKE".equals(data.get("cue"))&&data.get("cuts") instanceof Number){
                        recordedBrake=true;
                        recorded.brake(ms,((Number)data.get("cuts")).intValue(),Boolean.TRUE.equals(data.get("first")));
                    }
                    return;
                case "tick_start": case "tick_end":
                    self=(int)number(object(data.get("self")),"id",-1);
                    if(data.get("entities") instanceof List){
                        target=-1;
                        for(Object entity:(List<?>)data.get("entities")){
                            Map<String,Object> p=object(entity);
                            if(opponent.equals(p.get("name"))){target=(int)number(p,"id",-1);break;}
                        }
                    }
                    return;
                case "packet_wire":
                    Map<String,Object> packet=object(data.get("packet"));
                    if("in".equals(packet.get("direction"))&&tracked(packet.get("kind"))&&packet.get("packet_id") instanceof Number&&data.get("base64") instanceof String){
                        try{packets.put(((Number)packet.get("packet_id")).longValue(),Base64.getDecoder().decode((String)data.get("base64")));}
                        catch(IllegalArgumentException ignored){}
                    }
                    return;
                case "packet_handler_enter":
                    if(Boolean.TRUE.equals(data.get("main_thread"))&&data.get("packet_id") instanceof Number){
                        byte[] bytes=packets.remove(((Number)data.get("packet_id")).longValue());
                        if(bytes!=null)applied(ms,bytes);
                    }
                    return;
                case "motion_travel_after":if(moves<SumoStats.MOVES)moves++;return;
                case "attack_click_before":
                    before=data;clickTick=(int)number(row,"client_tick",-1);clickThread=String.valueOf(row.get("thread"));return;
                case "attack_click_after":
                    if(before!=null&&clickTick>=0&&clickTick==(int)number(row,"client_tick",-1)&&clickThread.equals(row.get("thread")))attack(ms,data);
                    before=null;return;
                default:return;
            }
        }
        private static boolean tracked(Object kind){
            return "S12PacketEntityVelocity".equals(kind)||"S19PacketEntityStatus".equals(kind)||"S08PacketPlayerPosLook".equals(kind);
        }
        private void applied(double ms,byte[] bytes){
            try{
                ReplayRecording.Cursor c=new ReplayRecording.Cursor(bytes);
                switch(c.varint()){
                    case 0x08:moves=SumoStats.MOVES;hurt=impact=Double.NEGATIVE_INFINITY;before=null;return;
                    case 0x1a:
                        if(c.int32()!=self||c.unsigned()!=2)return;
                        if(moves<SumoStats.MOVES&&!confirmed&&ms-impact<=SumoStats.HIT_PAIR_MS){
                            confirmed=true;hurt=Double.NEGATIVE_INFINITY;
                            if(cuts>0)cues.brake(ms,cuts,true);
                        }else hurt=ms;
                        return;
                    case 0x12:
                        if(c.varint()!=self||target<0)return;
                        double x=c.short16()/8000.;c.short16();double z=c.short16()/8000.,length=Math.hypot(x,z);
                        if(length<.1)return;
                        moves=cuts=0;axisX=x/length;axisZ=z/length;impact=ms;
                        confirmed=ms-hurt<=SumoStats.HIT_PAIR_MS;hurt=Double.NEGATIVE_INFINITY;before=null;
                        return;
                    default:return;
                }
            }catch(IOException ignored){ /* incomplete packet */ }
        }
        private void attack(double ms,Map<String,Object> after){
            Map<String,Object> ray=object(before.get("ray")),a=object(before.get("self")),b=object(after.get("self"));
            if(moves>=SumoStats.MOVES||target<0||!Boolean.TRUE.equals(a.get("sprinting"))||number(before,"left_click_counter",-1)!=0||
                    !"ENTITY".equals(ray.get("type"))||number(ray,"entity",-1)!=target||number(a,"id",-1)!=self||number(b,"id",-1)!=self)return;
            Object p=a.get("motion"),q=b.get("motion");
            if(!(p instanceof List)||!(q instanceof List)||((List<?>)p).size()!=3||((List<?>)q).size()!=3)return;
            double[] old=new double[3],next=new double[3];
            for(int i=0;i<3;i++){
                Object v=((List<?>)p).get(i),w=((List<?>)q).get(i);
                if(!(v instanceof Number)||!(w instanceof Number))return;
                old[i]=((Number)v).doubleValue();next[i]=((Number)w).doubleValue();
            }
            if(old[0]*axisX+old[2]*axisZ>SumoStats.MIN_OUTWARD&&SumoStats.sprintCut(old[0],old[1],old[2],next[0],next[1],next[2])){
                cuts++;
                if(confirmed)cues.brake(ms,cuts,cuts==1);
            }
        }
    }
}
