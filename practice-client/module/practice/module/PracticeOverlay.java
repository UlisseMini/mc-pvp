package practice.module;

import practice.api.*;
import java.util.*;

/** Reloadable overlay presentation. Geometry and diagnostic data do not control the player. */
final class PracticeOverlay {
    Map<String,Object> views=Collections.emptyMap();
    private boolean opening,large,reach,botGhost,myGhost,debug;
    void configure(Properties p){opening=flag(p,"opening.enabled",true);large=flag(p,"opening.large",true);reach=flag(p,"reach.enabled",false);botGhost=flag(p,"ghost.bot.enabled",false);myGhost=flag(p,"ghost.me.enabled",false);debug=flag(p,"debug.enabled",false);}
    private static boolean flag(Properties p,String k,boolean fallback){return Boolean.parseBoolean(p.getProperty(k,String.valueOf(fallback)));}
    @SuppressWarnings("unchecked") private static Map<String,Object> obj(Map<String,Object> m,String key){Object o=m.get(key);return o instanceof Map?(Map<String,Object>)o:Collections.emptyMap();}
    private static double num(Map<String,Object> m,String key){Object v=m.get(key);return v instanceof Number?((Number)v).doubleValue():0;}
    private static double[] vec(Map<String,Object> m,String key){List<?> a=(List<?>)m.get(key);double[] v=new double[a.size()];for(int i=0;i<v.length;i++)v[i]=((Number)a.get(i)).doubleValue();return v;}
    private static String fmt(String s,Object... args){return String.format(Locale.ROOT,s,args);}
    Frame hud(Frame base,int width,int height){
        if(views.isEmpty())return base;List<Frame.Line> lines=new ArrayList<>(base.lines);boolean available=Boolean.TRUE.equals(views.get("available"));
        if(opening){
            Map<String,Object> state=obj(views,"opening"),last=obj(state,"last");String title="Opening: approach each other";int color=0xDDDDDD;
            if(!last.isEmpty()){double lead=num(last,"lead_a_ms"),uncertainty=num(last,"uncertainty_ms");title=fmt("Opening %+.0f ms  +/- %.0f",lead,uncertainty);color=lead>uncertainty?0x55FFAA:lead< -uncertainty?0xFF7777:0xFFFFAA;}
            if("one_sided".equals(state.get("status"))){boolean first=num(state,"first_side")==0;title=fmt("Opening %s%.0f+ ms",first?"+":"-",num(state,"minimum_lead_ms"));color=first?0x55FFAA:0xFF7777;}
            if(!available)title="Opening: waiting for bot view";
            int y=Math.max(34,height/6);lines.add(new Frame.Line(title,0,y,color,false,large?2:1,true));
            lines.add(new Frame.Line("+ means you entered reach first | geometry, not hit outcome",0,y+(large?23:13),0xBBBBBB,false,1,true));
            Object history=state.get("history");if(history instanceof List&&!((List<?>)history).isEmpty()){
                StringBuilder text=new StringBuilder("Recent: ");for(Object value:(List<?>)history)text.append(fmt("%+.0f  ",num(cast(value),"lead_a_ms")));
                lines.add(new Frame.Line(text.toString(),0,y+(large?35:25),0xAAAAAA,false,1,true));
            }
        }
        int y=height-64;
        if(reach){lines.add(new Frame.Line(fmt("Cyan: your reach %.2f / 3.00",num(obj(views,"your_range"),"distance")),6,y,0x55FFFF,false));y+=11;
            Map<String,Object> r=obj(views,"bot_range");lines.add(new Frame.Line(available?fmt("Amber: bot reach %.2f / %.2f",num(r,"distance"),num(r,"reach")):"Bot view unavailable",6,y,0xFFCC66,false));y+=11;}
        if(botGhost||myGhost)lines.add(new Frame.Line((botGhost?"Amber ghost: bot on its screen  ":"")+(myGhost?"Pink ghost: you on its screen":""),6,y,0xBBBBBB,false));
        if(debug){
            Map<String,Object> timing=obj(views,"timing"),stats=obj(timing,"statistics"),delivery=obj(stats,"delivery"),draw=obj(stats,"draw");int dy=65;
            lines.add(new Frame.Line("Local view debug: "+views.get("source_status"),6,dy,0xFFFFFF,false));dy+=11;
            if(available){
                lines.add(new Frame.Line(fmt("Sample -> receive: %.2f ms | mean %.2f | p95 %.2f",num(timing,"delivery_ms"),num(delivery,"mean_ms"),num(delivery,"p95_ms")),6,dy,0x55FFFF,false));dy+=11;
                lines.add(new Frame.Line(fmt("Sample -> draw: %.2f ms | mean %.2f | p95 %.2f",num(timing,"draw_ms"),num(draw,"mean_ms"),num(draw,"p95_ms")),6,dy,0xFFCC66,false));dy+=11;
                lines.add(new Frame.Line(fmt("Clock probe RTT %.3f ms | last physics tick %.1f ms | drops %.0f",num(timing,"clock_rtt_ms"),num(timing,"simulation_tick_age_ms"),num(timing,"dropped")),6,dy,0xAAAAAA,false));
            }
        }
        return new Frame(base.holdSprint,lines,base.edgeColor,base.edgeWidth,base.rectangles);
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> cast(Object o){return (Map<String,Object>)o;}
    WorldFrame world(){
        List<WorldFrame.Line> lines=new ArrayList<>();List<WorldFrame.Box> boxes=new ArrayList<>();if(views.isEmpty())return new WorldFrame(lines,boxes);
        boolean available=Boolean.TRUE.equals(views.get("available"));
        if(reach&&!obj(views,"own").isEmpty()){sphere(lines,obj(views,"own"),3,0x7055FFFF);target(lines,obj(views,"own"),obj(views,"target"),0xAA55FFFF);}
        if(available){
            if(reach){sphere(lines,obj(views,"bot_self"),num(obj(views,"bot_range"),"reach"),0x70FFCC66);target(lines,obj(views,"bot_self"),obj(views,"bot_sees_you"),0xAAFFCC66);}
            if(botGhost)ghost(boxes,obj(views,"bot_self"),0x25FFBB44);
            if(myGhost)ghost(boxes,obj(views,"bot_sees_you"),0x25FF66DD);
        }
        return new WorldFrame(lines,boxes);
    }
    private static double[] eye(Map<String,Object> e){double[] p=vec(e,"pos");p[1]+=num(e,"eye_height");return p;}
    private static void sphere(List<WorldFrame.Line> out,Map<String,Object> e,double r,int c){
        double[] p=eye(e);for(int plane=0;plane<3;plane++)for(int i=0;i<48;i++){
            double a=i*Math.PI/24,b=(i+1)*Math.PI/24;double[] s=p.clone(),t=p.clone();int u=plane,v=(plane+1)%3;s[u]+=r*Math.cos(a);s[v]+=r*Math.sin(a);t[u]+=r*Math.cos(b);t[v]+=r*Math.sin(b);out.add(new WorldFrame.Line(s[0],s[1],s[2],t[0],t[1],t[2],c));}
    }
    private static void target(List<WorldFrame.Line> out,Map<String,Object> source,Map<String,Object> target,int c){
        double[] p=eye(source),b=vec(target,"box");double border=num(target,"border");for(int i=0;i<3;i++){b[i]-=border;b[i+3]+=border;}
        out.add(new WorldFrame.Line(p[0],p[1],p[2],Math.max(b[0],Math.min(b[3],p[0])),Math.max(b[1],Math.min(b[4],p[1])),Math.max(b[2],Math.min(b[5],p[2])),c));
        for(int axis=0;axis<3;axis++)for(int k=0;k<4;k++){double[] a=new double[3],d=new double[3];for(int j=0;j<3;j++){a[j]=b[j];if(j!=axis){int bit=j<axis?j:j-1;a[j]=b[j+(((k>>bit)&1)*3)];}d[j]=a[j];}d[axis]=b[axis+3];out.add(new WorldFrame.Line(a[0],a[1],a[2],d[0],d[1],d[2],c));}
    }
    private static void ghost(List<WorldFrame.Box> out,Map<String,Object> e,int color){
        double[] p=vec(e,"render");double yaw=-num(e,"yaw"),lower=Boolean.TRUE.equals(e.get("sneaking"))?.15:0;
        part(out,p,0,1.55-lower,0,.45,.45,.45,yaw,color);part(out,p,0,1.0-lower,0,.5,.65,.25,yaw,color);
        part(out,p,-.36,1.0-lower,0,.22,.65,.25,yaw,color);part(out,p,.36,1.0-lower,0,.22,.65,.25,yaw,color);
        part(out,p,-.13,.35,0,.23,.7,.25,yaw,color);part(out,p,.13,.35,0,.23,.7,.25,yaw,color);
    }
    private static void part(List<WorldFrame.Box> out,double[] p,double x,double y,double z,double w,double h,double d,double yaw,int c){double a=Math.toRadians(yaw);out.add(new WorldFrame.Box(p[0]+x*Math.cos(a)+z*Math.sin(a),p[1]+y,p[2]-x*Math.sin(a)+z*Math.cos(a),w,h,d,yaw,c));}
}
