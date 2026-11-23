package practice.core.replay;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import static practice.replay.PoseTrack.*;

/** Client-observed hit onsets for the two recorded Sumo participants. */
public final class ReplaySumoMoments {
    public static final class Impact {
        public final double atMs;
        public final boolean own;
        Impact(double atMs,boolean own){this.atMs=atMs;this.own=own;}
    }

    public final List<Impact> impacts;
    public final double openingMs;

    private ReplaySumoMoments(List<Impact> impacts,double openingMs){
        this.impacts=Collections.unmodifiableList(impacts);
        this.openingMs=openingMs;
    }

    public static Map<String,Object> opponent(ReplayRecording.Pose pose,String name){
        if(name==null||name.equalsIgnoreCase(String.valueOf(pose.opponent.get("name"))))return pose.opponent;
        Object entities=pose.state.get("entities");
        if(entities instanceof List)for(Object value:(List<?>)entities){
            Map<String,Object> actor=object(value);
            if(name.equalsIgnoreCase(String.valueOf(actor.get("name"))))return actor;
        }
        return Collections.emptyMap();
    }

    static ReplaySumoMoments read(ReplaySumoClip clip,MmcSumoReplay.Match match,List<ReplayRecording.Pose> poses,
                                  List<ReplayRecording.Mark> marks){
        List<Impact> wire=new ArrayList<>(),visual=new ArrayList<>();
        Map<Integer,Boolean> participants=new HashMap<>();
        int[] priorHurt={-1,-1},priorId={-1,-1};
        String name=match==null?null:match.opponent;
        for(ReplayRecording.Pose pose:poses){
            if(pose.atMs<clip.startMs)continue;
            if(pose.atMs>clip.finishMs)break;
            Map<String,Object> other=opponent(pose,name);
            observe(pose.atMs,pose.self,0,participants,priorHurt,priorId,visual);
            observe(pose.atMs,other,1,participants,priorHurt,priorId,visual);
        }
        double[] lastWire={Double.NEGATIVE_INFINITY,Double.NEGATIVE_INFINITY};
        for(ReplayRecording.Mark mark:marks){
            if(mark.atMs<clip.startMs||mark.atMs>clip.finishMs||!mark.kind.equals("hurt"))continue;
            Boolean own=participants.get((int)number(mark.details,"entity_id",-1));
            if(own==null)continue;
            int side=own?0:1;
            if(mark.atMs-lastWire[side]>=175)wire.add(new Impact(mark.atMs,own));
            lastWire[side]=mark.atMs;
        }
        List<Impact> impacts=new ArrayList<>(wire);
        int first=0;
        for(Impact hit:visual){
            while(first<wire.size()&&wire.get(first).atMs<hit.atMs-150)first++;
            boolean duplicate=false;
            for(int i=first;i<wire.size()&&wire.get(i).atMs<=hit.atMs+150;i++){
                if(wire.get(i).own==hit.own){duplicate=true;break;}
            }
            if(!duplicate)impacts.add(hit);
        }
        impacts.sort(Comparator.comparingDouble(hit->hit.atMs));
        double opening=Math.max(clip.startMs,clip.finishMs-3000);
        if(match!=null){
            int last=impacts.size()-1;
            // A return hit after the winner's last hit breaks the finishing chain.
            if(last>=0&&impacts.get(last).own!=match.won&&clip.finishMs-impacts.get(last).atMs<=4000){
                int start=last;
                while(start>0&&impacts.get(start-1).own!=match.won
                        &&impacts.get(start).atMs-impacts.get(start-1).atMs<=1400)start--;
                if(start<last)opening=Math.max(clip.startMs,impacts.get(start).atMs-450);
            }
        }
        return new ReplaySumoMoments(impacts,opening);
    }

    private static void observe(double time,Map<String,Object> actor,int side,Map<Integer,Boolean> participants,
                                int[] priorHurt,int[] priorId,List<Impact> visual){
        if(actor.isEmpty())return;
        int id=(int)number(actor,"id",-1),hurt=(int)number(actor,"hurtTime",-1);
        if(id==-1)return;
        participants.put(id,side==0);
        if(id==priorId[side]&&priorHurt[side]>=0&&hurt>priorHurt[side])visual.add(new Impact(time,side==0));
        priorHurt[side]=hurt;
        priorId[side]=id;
    }

    public int after(double time){
        int lo=0,hi=impacts.size();
        while(lo<hi){int mid=(lo+hi)>>>1;if(impacts.get(mid).atMs<=time)lo=mid+1;else hi=mid;}
        return lo;
    }
}
