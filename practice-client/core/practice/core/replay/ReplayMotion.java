package practice.core.replay;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Recorded motion samples and packet evidence, ordered on the replay clock. */
public final class ReplayMotion {
    public static final class Vector {
        public final double x,y,z;
        public Vector(double x,double y,double z){this.x=x;this.y=y;this.z=z;}
        public double horizontal(){return Math.hypot(x,z);}
        Vector plus(Vector v){return new Vector(x+v.x,y+v.y,z+v.z);}
        Vector minus(Vector v){return new Vector(x-v.x,y-v.y,z-v.z);}
        boolean same(Vector v){return v!=null&&x==v.x&&y==v.y&&z==v.z;}
        public String display(){return String.format(Locale.ROOT,"(%+.6f, %+.6f, %+.6f)",x,y,z);}
    }

    static final class Sample {
        double at;
        final int id;
        final String type;
        final Vector vector;
        final Object sprinting;
        final boolean aimed,ground,wall,verticalCollision;
        private Sample(String type,Map<?,?> state,Vector vector,int id){
            Map<?,?> ray=map(state.get("ray")),self=map(state.get("self"));
            this.type=type;this.vector=vector;this.id=id;sprinting=self.get("sprinting");
            aimed="ENTITY".equals(ray.get("type"))&&ray.get("entity") instanceof Number;
            ground=Boolean.TRUE.equals(self.get("ground"));
            wall=Boolean.TRUE.equals(self.get("isCollidedHorizontally"));
            verticalCollision=Boolean.TRUE.equals(self.get("isCollidedVertically"));
        }
    }

    public static final class Change {
        public final double at;
        public final String reason;
        public final Vector value, delta;
        public final boolean packet;
        private Change(double at,String reason,Vector value,Vector delta,boolean packet){
            this.at=at;this.reason=reason;this.value=value;this.delta=delta;this.packet=packet;
        }
    }

    public static final class Current {
        public final double at;
        public final Vector value;
        public final Object sprinting;
        private Current(double at,Vector value,Object sprinting){this.at=at;this.value=value;this.sprinting=sprinting;}
    }

    private final List<Change> changes=new ArrayList<>();
    private final List<Current> current=new ArrayList<>();

    static Sample read(String type,Map<String,Object> state){
        Map<?,?> self=map(state.get("self"));
        Vector vector=vector(self.get("motion"));
        Object id=self.get("id");
        return vector!=null&&id instanceof Number?new Sample(type,state,vector,((Number)id).intValue()):null;
    }

    ReplayMotion(List<Sample> samples,List<ReplayRecording.Mark> marks,List<ReplayRecording.Pose> poses){
        samples.sort(Comparator.comparingDouble(s->s.at));
        int sampleIndex=0,markIndex=0,id=Integer.MIN_VALUE,poseIndex=-1;
        Vector own=null;
        Object sprint=null;
        Sample beforeAttack=null;
        Sample lastSample=null;
        double correction=Double.NEGATIVE_INFINITY;
        while(sampleIndex<samples.size()||markIndex<marks.size()){
            if(markIndex<marks.size()&&(sampleIndex==samples.size()||marks.get(markIndex).atMs<=samples.get(sampleIndex).at)){
                ReplayRecording.Mark mark=marks.get(markIndex++);
                Map<String,Object> details=mark.details;
                while(poseIndex+1<poses.size()&&poses.get(poseIndex+1).atMs<=mark.atMs)poseIndex++;
                Object target=poseIndex<0?null:poses.get(poseIndex).opponent.get("id");
                boolean targetPacket=target instanceof Number&&details.get("entity_id") instanceof Number
                        &&((Number)target).intValue()==((Number)details.get("entity_id")).intValue();
                switch(mark.kind){
                    case "velocity":{
                        Vector v=vector(details.get("velocity"));
                        if(v==null)break;
                        String source="main_thread_handler".equals(details.get("time_source"))?"":" (wire time)";
                        if(Boolean.TRUE.equals(details.get("own"))){
                            changes.add(new Change(mark.atMs,"S12 · Your velocity packet"+source,v,
                                    source.isEmpty()&&own!=null?v.minus(own):null,true));
                            if(source.isEmpty()){
                                own=v;
                                current.add(new Current(mark.atMs,own,sprint));
                            }
                        }else if(targetPacket)changes.add(new Change(mark.atMs,"S12 · Server set target velocity"+source,v,null,true));
                        break;
                    }
                    case "explosion":{
                        Vector impulse=vector(details.get("velocity"));
                        if(impulse==null)break;
                        String source="main_thread_handler".equals(details.get("time_source"))?"":" (wire time)";
                        // The packet proves the impulse. A later native sample measures the total.
                        changes.add(new Change(mark.atMs,"S27 · Explosion added velocity"+source,null,impulse,true));
                        break;
                    }
                    case "hurt":
                        if(Boolean.TRUE.equals(details.get("own"))||targetPacket)
                            changes.add(new Change(mark.atMs,Boolean.TRUE.equals(details.get("own"))?"S19 · You showed hurt":"S19 · Target showed hurt",null,null,true));
                        break;
                    case "sprint":
                        changes.add(new Change(mark.atMs,"C0B · Sent sprint "+details.get("action"),null,null,true));
                        break;
                    case "attack":
                        changes.add(new Change(mark.atMs,"C02 · Sent attack",null,null,true));
                        break;
                    case "position_reset":{
                        correction=mark.atMs;
                        String packet=Boolean.TRUE.equals(details.get("teleport"))?"S08 · Position correction":"S07 · Respawn";
                        changes.add(new Change(mark.atMs,packet,null,null,true));
                        own=null;
                        current.add(new Current(mark.atMs,null,sprint));
                        break;
                    }
                    default:break;
                }
                continue;
            }
            Sample sample=samples.get(sampleIndex++);
            if(sample.id!=id){id=sample.id;own=null;beforeAttack=null;}
            if(sample.type.equals("attack_click_before"))beforeAttack=sample.aimed?sample:null;
            String cause="Between recorded samples";
            boolean click=sample.type.equals("attack_click_after")&&beforeAttack!=null&&sample.at-beforeAttack.at<=150;
            if(click){
                String sprintText=sprintLabel(beforeAttack.sprinting);
                Vector before=beforeAttack.vector,after=sample.vector;
                boolean cut=before.horizontal()>0&&after.y==before.y
                        &&Math.abs(after.x-.6*before.x)<1e-7&&Math.abs(after.z-.6*before.z)<1e-7;
                Vector delta=after.minus(before);
                changes.add(new Change(sample.at,(cut?"Attack ×0.6":"Attack click")+" · client sprint "+sprintText,
                        after,after.same(before)?null:delta,false));
                beforeAttack=null;
            }else if(own==null||!sample.vector.same(own)){
                if(sample.at-correction<=125)cause="After server position correction";
                else if(sample.type.equals("motion_jump_after")&&paired(lastSample,sample,"motion_jump_before"))cause="Local jump · sprint "+sprintLabel(sample.sprinting);
                else if(sample.type.equals("motion_travel_after")&&paired(lastSample,sample,"motion_travel_before"))cause="Movement step · "+movement(sample);
                else if(sample.type.equals("tick_end"))cause="Client tick · "+movement(sample);
                if(own!=null||sample.at-correction<=125)
                    changes.add(new Change(sample.at,cause,sample.vector,own==null?null:sample.vector.minus(own),false));
            }
            own=sample.vector;
            sprint=sample.sprinting==null?sprint:sample.sprinting;
            current.add(new Current(sample.at,own,sprint));
            lastSample=sample;
        }
    }
    private static boolean paired(Sample before,Sample after,String type){
        return before!=null&&before.id==after.id&&before.type.equals(type)&&after.at-before.at<=150;
    }

    private static String movement(Sample sample){
        if(sample.wall)return "wall contact";
        if(sample.verticalCollision&&!sample.ground)return "vertical collision";
        return sample.ground?"grounded":"airborne";
    }

    public static String sprintLabel(Object value){
        return value instanceof Boolean?(Boolean.TRUE.equals(value)?"on":"off"):"unknown";
    }

    public Current at(double time){
        int left=0,right=current.size();
        while(left<right){int center=(left+right)>>>1;if(current.get(center).at<=time)left=center+1;else right=center;}
        return left==0?null:current.get(left-1);
    }
    private int end(double time){
        int left=0,right=changes.size();
        while(left<right){int center=(left+right)>>>1;if(changes.get(center).at<=time)left=center+1;else right=center;}
        return left;
    }
    public List<Change> recent(double time,int count){
        int end=end(time);
        return Collections.unmodifiableList(changes.subList(Math.max(0,end-count),end));
    }
    public double step(double time,boolean forward){
        int index=end(forward?time:Math.nextDown(time));
        if(!forward)index--;
        return index<0||index>=changes.size()?Double.NaN:changes.get(index).at;
    }

    public static Vector vector(Object raw){
        if(!(raw instanceof List))return null;
        List<?> list=(List<?>)raw;
        if(list.size()<3||!(list.get(0) instanceof Number)||!(list.get(1) instanceof Number)||!(list.get(2) instanceof Number))return null;
        double x=((Number)list.get(0)).doubleValue(),y=((Number)list.get(1)).doubleValue(),z=((Number)list.get(2)).doubleValue();
        return Double.isFinite(x)&&Double.isFinite(y)&&Double.isFinite(z)?new Vector(x,y,z):null;
    }
    private static Map<?,?> map(Object value){return value instanceof Map?(Map<?,?>)value:Collections.emptyMap();}
}
