package practice.bridge;

import java.nio.file.*;
import java.util.*;
import com.google.gson.Gson;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityOtherPlayerMP;
import net.minecraft.entity.Entity;
import practice.replay.*;
import static practice.core.JsonData.map;

/** Replay-only presentation. Uses the exact same timestamped pose reader as the server. */
final class ReplayPresentation {
    private Path root;
    private Map<String,Object> state=Collections.emptyMap();
    private volatile Map<String,PoseTrack> tracks=Collections.emptyMap();
    private volatile String loadStatus="",loadedMatch="";
    private volatile long generation;
    void root(Path root){this.root=root;}
    Map<String,Object> state(){return state;}
    boolean active(){return Boolean.TRUE.equals(state.get("active"));}
    void clear(){generation++;state=Collections.emptyMap();tracks=Collections.emptyMap();loadedMatch="";}
    @SuppressWarnings("unchecked") void receive(Map<String,Object> value){
        state=value;if(!active()){clear();return;}
        String id=String.valueOf(value.get("match"));if(!id.matches("m[0-9]{4,9}")||id.equals(loadedMatch))return;
        loadedMatch=id;tracks=Collections.emptyMap();final long token=++generation;
        final Path folder=root==null?null:root.resolve(id);final long start=(long)n(value,"start_ms",0);
        loadStatus=folder==null?"network snapshots":"loading pose archive";
        if(folder==null)return;
        Thread reader=new Thread(()->{
            try{Gson gson=new Gson();PoseFiles files=new PoseFiles(folder,start,s->gson.fromJson(s,Map.class));
                if(token==generation){tracks=Collections.unmodifiableMap(files.tracks);loadStatus=files.tracks.isEmpty()?"network snapshots":files.warnings.isEmpty()?"local pose archive":"archive has gaps; see replay warnings";}
            }catch(Exception e){if(token==generation)loadStatus="network snapshots (local archive unavailable)";}
        },"practice-replay-poses");reader.setDaemon(true);reader.start();
    }
    Map<String,Object> render(Minecraft mc){
        if(!active()||mc.field_71441_e==null||mc.field_71439_g==null)return Collections.emptyMap();
        double elapsed=Boolean.TRUE.equals(state.get("paused"))?0:Math.max(0,System.currentTimeMillis()-n(state,"server_ms",0));
        double time=Math.min(n(state,"duration_ms",0),n(state,"time_ms",0)+elapsed*n(state,"speed",1));
        Map<String,Object> views=new LinkedHashMap<>(),packet=object(state.get("views")),next=object(state.get("next"));
        for(String source:Arrays.asList("server","a","b")){
            Map<String,Object> frame;PoseTrack track=tracks.get(source);
            if(track!=null)frame=track.at(time);
            else{Map<String,Object> current=object(packet.get(source)),following=object(next.get(source));frame=new LinkedHashMap<>();
                if(!current.isEmpty())for(String side:Arrays.asList("a","b")){Map<String,Object> p=object(current.get(side)),q=object(following.get(side));frame.put(side,q.isEmpty()?p:PoseTrack.interpolate(p,q,elapsed/50));}}
            if(!frame.isEmpty())views.put(source,frame);
        }
        Map<String,Object> chosen=object(views.get(String.valueOf(state.get("source"))));double offset=n(state,"offset_x",0);
        Object ids=state.get("actor_ids");
        if(ids instanceof List&&((List<?>)ids).size()==2)for(int i=0;i<2;i++){
            Entity e=mc.field_71441_e.func_73045_a(((Number)((List<?>)ids).get(i)).intValue());
            Map<String,Object> p=object(chosen.get(i==0?"a":"b"));
            // Only the two private replay actors may be moved. Never the real player.
            if(e instanceof EntityOtherPlayerMP&&e!=mc.field_71439_g&&!p.isEmpty())apply((EntityOtherPlayerMP)e,p,offset);
        }
        Map<String,Object> normalized=new LinkedHashMap<>();
        for(Map.Entry<String,Object> entry:views.entrySet()){Map<String,Object> view=object(entry.getValue());normalized.put(entry.getKey(),map("a",observation(object(view.get("a")),offset),"b",observation(object(view.get("b")),offset)));}
        Map<String,Object> availability=object(state.get("availability"));String mine="human".equals(object(availability.get("b")).get("role"))?"b":"a",other=mine.equals("a")?"b":"a";
        Map<String,Object> ownView=object(normalized.get(mine)),otherView=object(normalized.get(other));
        Map<String,Object> out=new LinkedHashMap<>(state);out.put("reach_advantage",practice.math.ReachAdvantage.compare(object(ownView.get(mine)),object(ownView.get(other)),object(otherView.get(other)),object(otherView.get(mine))));out.put("advantage_owner",object(availability.get(mine)).getOrDefault("label",mine));out.put("views",normalized);out.put("time_ms",time);out.put("view_missing",chosen.isEmpty());out.put("archive_status",loadStatus);return out;
    }
    private static void apply(EntityOtherPlayerMP e,Map<String,Object> p,double offset){
        double x=n(p,"posX",0)+offset,y=n(p,"posY",4),z=n(p,"posZ",0);e.func_70107_b(x,y,z);
        e.field_70169_q=e.field_70142_S=x;e.field_70167_r=e.field_70137_T=y;e.field_70166_s=e.field_70136_U=z;
        e.field_70177_z=e.field_70126_B=(float)n(p,"rotationYaw",0);e.field_70125_A=e.field_70127_C=(float)n(p,"rotationPitch",0);
        e.field_70759_as=e.field_70758_at=(float)n(p,"rotationYawHead",e.field_70177_z);
        e.field_70761_aq=e.field_70760_ar=(float)n(p,"renderYawOffset",e.field_70177_z);
        e.field_70733_aJ=e.field_70732_aI=(float)n(p,"swingProgress",0);
        e.field_70754_ba=(float)n(p,"limbSwing",0);e.field_70721_aZ=e.field_70722_aY=(float)n(p,"limbSwingAmount",0);
        e.field_70737_aN=(int)n(p,"hurtTime",0);
    }
    static Map<String,Object> observation(Map<String,Object> p,double offset){
        if(p.isEmpty())return Collections.emptyMap();double x=n(p,"posX",0),y=n(p,"posY",4),z=n(p,"posZ",0);
        double lx=n(p,"logicalX",x),ly=n(p,"logicalY",y),lz=n(p,"logicalZ",z);
        Map<String,Object> out=new LinkedHashMap<>(p);out.put("pos",Arrays.asList(lx+offset,ly,lz));out.put("render",Arrays.asList(x+offset,y,z));
        out.put("yaw",n(p,"rotationYaw",0));out.put("pitch",n(p,"rotationPitch",0));out.put("eye_height",n(p,"eye_height",Boolean.TRUE.equals(p.get("sneaking"))?1.54:1.62));out.put("border",n(p,"border",.1));
        Object raw=p.get("box");List<Double> box=new ArrayList<>();if(raw instanceof List&&((List<?>)raw).size()==6){for(Object v:(List<?>)raw)box.add(((Number)v).doubleValue());box.set(0,box.get(0)+offset);box.set(3,box.get(3)+offset);}
        else box.addAll(Arrays.asList(lx+offset-.3,ly,lz-.3,lx+offset+.3,ly+1.8,lz+.3));out.put("box",box);return out;
    }
    static Map<String,Object> object(Object value){return PoseTrack.object(value);}
    static double n(Map<String,Object> m,String key,double fallback){return PoseTrack.number(m,key,fallback);}
}
