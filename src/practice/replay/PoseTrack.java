package practice.replay;
import java.util.*;
import static practice.core.JsonData.map;

/** Timestamped two-actor client view. Raw samples stay intact; interpolation is a display operation. */
public final class PoseTrack {
    private final List<Map<String,Object>> samples=new ArrayList<>();
    public final String source;public final long maxGap;
    public PoseTrack(String source,long maxGap){this.source=source;this.maxGap=maxGap;}
    @SuppressWarnings("unchecked") public static Map<String,Object> object(Object o){return o instanceof Map?(Map<String,Object>)o:Collections.emptyMap();}
    public static double number(Map<String,Object> m,String k,double d){Object o=m.get(k);return o instanceof Number?((Number)o).doubleValue():d;}
    private static double component(Object v,int i,double fallback){return v instanceof List&&((List<?>)v).size()>i&&((List<?>)v).get(i) instanceof Number?((Number)((List<?>)v).get(i)).doubleValue():fallback;}
    public static Map<String,Object> normalize(Map<String,Object> p){
        if(p.isEmpty())return Collections.emptyMap();Map<String,Object> out=new LinkedHashMap<>(p);
        String[] axes={"X","Y","Z"};for(int i=0;i<3;i++){
            double logical=number(p,"pos"+axes[i],component(p.get("pos"),i,Double.NaN));
            double drawn=component(p.get("render"),i,logical);if(!Double.isFinite(logical)||!Double.isFinite(drawn))return Collections.emptyMap();
            out.put("logical"+axes[i],logical);out.put("pos"+axes[i],drawn);
        }
        out.put("rotationYaw",number(p,"render_yaw",number(p,"rotationYaw",number(p,"yaw",0))));out.put("rotationPitch",number(p,"render_pitch",number(p,"rotationPitch",number(p,"pitch",0))));
        out.put("onGround",p.getOrDefault("onGround",p.getOrDefault("ground",false)));out.put("sprint",p.getOrDefault("sprint",p.getOrDefault("sprinting",false)));
        return out;
    }
    public void add(long ms,Map<String,Object> view,int selfSide){
        Map<String,Object> self=normalize(object(view.get("self"))),other=normalize(object(view.get("opponent")));
        if(self.isEmpty()||other.isEmpty())return;
        Map<String,Object> a=selfSide==0?self:other,b=selfSide==0?other:self;
        samples.add(map("time",ms,"a",a,"b",b,"health_a",number(a,"health",20),"health_b",number(b,"health",20)));
    }
    public void sort(){samples.sort(Comparator.comparingDouble(s->number(s,"time",0)));}
    public int size(){return samples.size();}
    public List<Map<String,Object>> samples(){return Collections.unmodifiableList(samples);}
    public Map<String,Object> at(double ms){
        if(samples.isEmpty())return Collections.emptyMap();int lo=0,hi=samples.size();while(lo<hi){int mid=(lo+hi)>>>1;if(number(samples.get(mid),"time",0)<=ms)lo=mid+1;else hi=mid;}
        if(lo==0)return Collections.emptyMap();Map<String,Object> first=samples.get(lo-1);double t=number(first,"time",0);
        if(ms-t>maxGap)return Collections.emptyMap();if(lo==samples.size())return first;
        Map<String,Object> next=samples.get(lo);double dt=number(next,"time",0)-t;
        if(dt<=0||dt>maxGap)return first;
        double f=(ms-t)/dt;Map<String,Object> out=new LinkedHashMap<>(first);out.put("time",ms);
        for(String side:Arrays.asList("a","b"))out.put(side,interpolate(object(first.get(side)),object(next.get(side)),f));return out;
    }
    public static Map<String,Object> interpolate(Map<String,Object> a,Map<String,Object> b,double f){
        Map<String,Object> out=new LinkedHashMap<>(a);f=Math.max(0,Math.min(1,f));
        // Logical positions and boxes remain sample-and-hold: vanilla hit tests are tick states.
        for(String k:Arrays.asList("posX","posY","posZ","rotationYaw","rotationPitch","rotationYawHead","renderYawOffset","swingProgress","limbSwing","limbSwingAmount")){
            if(!(a.get(k) instanceof Number)||!(b.get(k) instanceof Number))continue;double x=number(a,k,0),y=number(b,k,x),d=y-x;
            if(k.contains("Yaw")||k.equals("rotationPitch"))d=((d%360)+540)%360-180;
            // Teleports are discontinuities, never draw a journey through distant arenas.
            if(k.startsWith("pos")&&Math.abs(d)>8)continue;out.put(k,x+d*f);
        }
        return out;
    }
    public Map<String,Object> summary(){
        if(samples.isEmpty())return map("source",source,"samples",0);List<Double> gaps=new ArrayList<>();double max=0;
        for(int i=1;i<samples.size();i++){double gap=number(samples.get(i),"time",0)-number(samples.get(i-1),"time",0);if(gap>0)gaps.add(gap);max=Math.max(max,gap);}Collections.sort(gaps);
        return map("source",source,"samples",samples.size(),"first_ms",number(samples.get(0),"time",0),"last_ms",number(samples.get(samples.size()-1),"time",0),"median_gap_ms",gaps.isEmpty()?null:gaps.get(gaps.size()/2),"max_gap_ms",max);
    }
}
