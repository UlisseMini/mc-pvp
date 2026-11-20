package practice.module;
import practice.api.*;
import java.util.*;
import static practice.replay.PoseTrack.*;

/** Live and replay share observation shapes and the reach/ghost drawing functions. */
final class ReplayOverlay {
    Map<String,Object> state=Collections.emptyMap();boolean reach,advantage;
    boolean active(){return Boolean.TRUE.equals(state.get("active"));}
    void configure(Properties p){advantage=Boolean.parseBoolean(p.getProperty("advantage.enabled","true"));reach=Boolean.parseBoolean(p.getProperty("reach.enabled","false"));}
    Frame hud(Frame base,int width,int height){
        List<Frame.Line> lines=Boolean.TRUE.equals(state.get("offline"))?new ArrayList<>():new ArrayList<>(base.lines);Map<String,Object> availability=object(state.get("availability"));String source=String.valueOf(state.get("source"));
        String label=source.equals("server")?"Server":String.valueOf(object(availability.get(source)).get("label"));
        String text=String.format(Locale.ROOT,"REPLAY  %.2f / %.2f s   %.2fx   %s POV%s",number(state,"time_ms",0)/1000,number(state,"duration_ms",0)/1000,number(state,"speed",1),label,Boolean.TRUE.equals(state.get("paused"))?"  PAUSED":"");
        lines.add(new Frame.Line(text,0,30,0xFFFFAA,false,1,true));
        if(Boolean.TRUE.equals(state.get("view_missing")))lines.add(new Frame.Line("This viewpoint has no sample at this time",0,43,0xFF8888,false,1,true));
        if(advantage&&!Boolean.TRUE.equals(state.get("offline")))AdvantageHud.append(lines,object(state.get("reach_advantage")),height,String.valueOf(state.getOrDefault("advantage_owner","A")));
        if(showReach())lines.add(new Frame.Line("Reach fields: cyan = A's client; amber = B's client",6,height-58,0xBBBBBB,false));
        return new Frame(base.holdSprint,lines,0,0,base.rectangles);
    }
    boolean showReach(){return reach&&(!Boolean.TRUE.equals(state.get("offline"))||Boolean.TRUE.equals(state.get("offline_reach")));}
    WorldFrame world(){
        List<WorldFrame.Box> boxes=new ArrayList<>();List<WorldFrame.Sphere> spheres=new ArrayList<>();Map<String,Object> views=object(state.get("views"));
        if(showReach())for(int i=0;i<2;i++){String side=i==0?"a":"b";Map<String,Object> pose=object(object(views.get(side)).get(side));Object reaches=state.get("reach");double radius=reaches instanceof List?((Number)((List<?>)reaches).get(i)).doubleValue():3;
            if(!pose.isEmpty())ReachDrawing.append(spheres,pose,radius,i==0?0x1855FFFF:0x20FFCC66);}
        Object selected=state.get("ghosts");if(selected instanceof List)for(Object v:(List<?>)selected){String[] key=String.valueOf(v).split(":");if(key.length!=2)continue;
            Map<String,Object> pose=object(object(views.get(key[0])).get(key[1]));if(!pose.isEmpty())PracticeOverlay.ghost(boxes,pose,color(key[0],key[1]));}
        return new WorldFrame(Collections.emptyList(),boxes,spheres);
    }
    private static int color(String source,String side){return source.equals("server")?(side.equals("a")?0x2566FF88:0x25BBAAFF):source.equals("a")?(side.equals("a")?0x2555FFFF:0x255588FF):(side.equals("a")?0x25FF66DD:0x25FFBB44);}
}
