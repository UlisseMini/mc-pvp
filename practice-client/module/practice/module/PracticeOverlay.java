package practice.module;

import practice.api.*;
import java.util.*;

/** Reloadable overlay presentation. Geometry and diagnostic data do not control the player. */
final class PracticeOverlay {
    Map<String,Object> views=Collections.emptyMap();
    private boolean opening,large,reach,botGhost,myGhost,debug,advantage;
    void configure(Properties p){advantage=flag(p,"advantage.enabled",true);opening=flag(p,"opening.enabled",true);large=flag(p,"opening.large",true);reach=flag(p,"reach.enabled",false);botGhost=flag(p,"ghost.bot.enabled",false);myGhost=flag(p,"ghost.me.enabled",false);debug=flag(p,"debug.enabled",false);}
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
        if(advantage)AdvantageHud.append(lines,available?obj(views,"reach_advantage"):Collections.emptyMap(),height,"Your");
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
        List<WorldFrame.Line> lines=new ArrayList<>();List<WorldFrame.Box> boxes=new ArrayList<>();List<WorldFrame.Sphere> spheres=new ArrayList<>();if(views.isEmpty())return new WorldFrame(lines,boxes,spheres);
        boolean available=Boolean.TRUE.equals(views.get("available"));
        if(reach&&!obj(views,"own").isEmpty())ReachDrawing.append(spheres,obj(views,"own"),3,0x1855FFFF);
        if(available){
            if(reach)ReachDrawing.append(spheres,obj(views,"bot_self"),num(obj(views,"bot_range"),"reach"),0x20FFCC66);
            if(botGhost)ghost(boxes,obj(views,"bot_self"),0x25FFBB44);
            if(myGhost)ghost(boxes,obj(views,"bot_sees_you"),0x25FF66DD);
        }
        return new WorldFrame(lines,boxes,spheres);
    }
    static void ghost(List<WorldFrame.Box> out,Map<String,Object> e,int color){
        double[] p=vec(e,e.get("render") instanceof List?"render":"pos");double yaw=-num(e,"yaw"),lower=Boolean.TRUE.equals(e.get("sneaking"))?.15:0;
        part(out,p,0,1.55-lower,0,.45,.45,.45,yaw,color);part(out,p,0,1.0-lower,0,.5,.65,.25,yaw,color);
        part(out,p,-.36,1.0-lower,0,.22,.65,.25,yaw,color);part(out,p,.36,1.0-lower,0,.22,.65,.25,yaw,color);
        part(out,p,-.13,.35,0,.23,.7,.25,yaw,color);part(out,p,.13,.35,0,.23,.7,.25,yaw,color);
    }
    private static void part(List<WorldFrame.Box> out,double[] p,double x,double y,double z,double w,double h,double d,double yaw,int c){double a=Math.toRadians(yaw);out.add(new WorldFrame.Box(p[0]+x*Math.cos(a)+z*Math.sin(a),p[1]+y,p[2]-x*Math.sin(a)+z*Math.cos(a),w,h,d,yaw,c));}
}
