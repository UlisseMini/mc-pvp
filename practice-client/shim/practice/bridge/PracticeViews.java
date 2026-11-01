package practice.bridge;

import practice.math.*;
import practice.transport.LocalViewStream;
import static practice.core.JsonData.map;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.AxisAlignedBB;

/** Main-thread view sampling. The socket worker only touches immutable numeric snapshots. */
final class PracticeViews implements AutoCloseable {
    private LocalViewStream.Reader reader;
    private String match="",descriptor="";
    private OpeningWindow opening;
    private long statsAt;
    private final ArrayDeque<Double> delivery=new ArrayDeque<>(),draw=new ArrayDeque<>();
    private Map<String,Object> statistics=Collections.emptyMap();
    @SuppressWarnings("unchecked") Map<String,Object> update(Minecraft mc,Map<String,Object> state,float partial){
        String next=String.valueOf(state.getOrDefault("match",""));
        if(mc.field_71439_g==null||mc.field_71441_e==null||next.isEmpty()||!(state.get("players") instanceof List)){close();return map();}
        if(!next.equals(match)){close();match=next;opening=new OpeningWindow(number(state,"combat_start",System.currentTimeMillis()).longValue());}
        List<?> names=(List<?>)state.get("players");String mine=mc.field_71439_g.func_70005_c_();int side=names.indexOf(mine);
        if(side<0||names.size()!=2){close();return map();}
        Object desc=state.get("fast_view");
        if(desc instanceof Map){Map<String,Object> d=(Map<String,Object>)desc;
            String key=String.valueOf(d.get("port"))+":"+d.get("token");
            if(match.equals(d.get("match"))&&!key.equals(descriptor)){
                if(reader!=null)reader.close();reader=null;descriptor=key;
                try{reader=new LocalViewStream.Reader(number(d,"port",0).intValue(),String.valueOf(d.get("token")));}catch(IllegalArgumentException ignored){}
            }
        }
        EntityPlayer target=null;String other=String.valueOf(names.get(1-side));
        for(Object o:mc.field_71441_e.field_73010_i){EntityPlayer p=(EntityPlayer)o;if(p!=mc.field_71439_g&&other.equals(p.func_70005_c_())){target=p;break;}}
        if(target==null)return map("match",match,"available",false);
        long now=System.currentTimeMillis(),ns=System.nanoTime();
        Map<String,Object> own=entity(mc.field_71439_g,partial),opponent=entity(target,partial);
        double botReach=3;Object reaches=state.get("reach");if(reaches instanceof List&&((List<?>)reaches).size()==2)botReach=((Number)((List<?>)reaches).get(1-side)).doubleValue();
        double yourDistance=distance(own,opponent);opening.add(0,now,3-yourDistance);
        if(reader!=null)for(LocalViewStream.Sample s:reader.drain()){
            Map<String,Object> self=entity(s,0),seen=entity(s,18);opening.add(1,s.millis,botReach-distance(self,seen));push(delivery,s.deliveryMs());
        }
        LocalViewStream.Sample sample=reader==null?null:reader.latest();
        boolean available=sample!=null&&sample.ageMs(ns)>=-10&&sample.ageMs(ns)<250;
        Map<String,Object> out=map("match",match,"own",own,"target",opponent,"your_range",map("reach",3,"distance",yourDistance),
            "available",available,"opening",opening.state(),"source_status",reader==null?"waiting":reader.status());
        if(available){
            Map<String,Object> self=entity(sample,0),seen=entity(sample,18);push(draw,sample.ageMs(ns));
            if(now-statsAt>=250){statsAt=now;statistics=map("delivery",stats(delivery),"draw",stats(draw));}
            out.put("bot_self",self);out.put("bot_sees_you",seen);out.put("bot_range",map("reach",botReach,"distance",distance(self,seen)));
            out.put("timing",map("delivery_ms",sample.deliveryMs(),"draw_ms",sample.ageMs(ns),"clock_rtt_ms",sample.rttNanos/1e6,
                "simulation_tick_age_ms",(ns+sample.offsetNanos-sample.tickNanos)/1e6,"statistics",statistics,"dropped",reader.dropped()));
            out.put("source_sequence",sample.sequence);out.put("source_ms",sample.millis);out.put("source_ns",sample.nanos);
        }
        return out;
    }
    private static Number number(Map<String,Object> m,String key,Number fallback){Object v=m.get(key);return v instanceof Number?(Number)v:fallback;}
    private static void push(ArrayDeque<Double> q,double value){q.addLast(value);while(q.size()>600)q.removeFirst();}
    private static Map<String,Object> stats(ArrayDeque<Double> q){double[] a=new double[q.size()];int i=0;double sum=0;for(double n:q){a[i++]=n;sum+=n;}Arrays.sort(a);return a.length==0?map():map("count",a.length,"mean_ms",sum/a.length,"p95_ms",a[(int)Math.ceil(a.length*.95)-1],"max_ms",a[a.length-1]);}
    private static Map<String,Object> entity(EntityPlayer p,float partial){
        AxisAlignedBB b=p.func_174813_aQ();return map("pos",Arrays.asList(p.field_70165_t,p.field_70163_u,p.field_70161_v),
            "render",Arrays.asList(p.field_70142_S+(p.field_70165_t-p.field_70142_S)*partial,p.field_70137_T+(p.field_70163_u-p.field_70137_T)*partial,p.field_70136_U+(p.field_70161_v-p.field_70136_U)*partial),
            "yaw",p.field_70177_z,"pitch",p.field_70125_A,"eye_height",p.func_70047_e(),"box",Arrays.asList(b.field_72340_a,b.field_72338_b,b.field_72339_c,b.field_72336_d,b.field_72337_e,b.field_72334_f),"border",p.func_70111_Y(),"sneaking",p.func_70093_af());
    }
    private static Map<String,Object> entity(LocalViewStream.Sample s,int i){
        List<Double> pos=new ArrayList<>(),render=new ArrayList<>(),box=new ArrayList<>();for(int j=0;j<3;j++){pos.add(s.value(i+j));render.add(s.value(i+3+j));}for(int j=0;j<6;j++)box.add(s.value(i+9+j));
        return map("pos",pos,"render",render,"yaw",s.value(i+6),"pitch",s.value(i+7),"eye_height",s.value(i+8),"box",box,"border",s.value(i+15),"sneaking",s.value(i+16)>0);
    }
    private static double[] values(Object o){List<?> a=(List<?>)o;double[] v=new double[a.size()];for(int i=0;i<v.length;i++)v[i]=((Number)a.get(i)).doubleValue();return v;}
    private static double distance(Map<String,Object> eye,Map<String,Object> target){double[] p=values(eye.get("pos"));p[1]+=number(eye,"eye_height",1.62).doubleValue();return ReachMath.distance(p,ReachMath.expand(values(target.get("box")),number(target,"border",0.1).doubleValue()));}
    public void close(){if(reader!=null)reader.close();reader=null;match="";descriptor="";opening=null;delivery.clear();draw.clear();statistics=Collections.emptyMap();statsAt=0;}
}
