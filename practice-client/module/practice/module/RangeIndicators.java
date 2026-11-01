package practice.module;

import practice.api.Frame;
import practice.math.ReachAdvantage;
import java.util.*;
import static practice.replay.PoseTrack.object;
import static practice.replay.PoseTrack.number;

/** Two independent geometric reach signals. Never predicts clicks or changes combat input. */
final class RangeIndicators {
    private enum Style { OFF, CROSSHAIR, EDGE, SCREEN }
    private static final class Signal {
        Style style;int rgb;double opacity;boolean pulse;
        Boolean previous;double entered=Double.NaN;
        void clear(){previous=null;entered=Double.NaN;}
        double alpha(Boolean inside,double time){
            if(inside==null){clear();return 0;}
            if(inside&&Boolean.FALSE.equals(previous))entered=time;
            previous=inside;
            if(!inside){entered=Double.NaN;return 0;}
            // A pulse changes brightness only while genuinely in range; exits are immediate.
            double boost=pulse&&Double.isFinite(entered)?Math.max(0,1-(time-entered)/180):0;
            return style==Style.OFF?0:Math.min(1,opacity*(1+.7*boost));
        }
    }
    private final Signal yours=new Signal(),theirs=new Signal();
    private String context="";private double previousTime=Double.NaN;private long previousWall;
    void configure(Properties p){configure(yours,p,"yours","crosshair","55FFFF");configure(theirs,p,"theirs","edge","FF5555");}
    private static void configure(Signal s,Properties p,String side,String fallback,String rgb){
        String key="range."+side+".";
        Style selected=style(p.getProperty(key+"style",fallback));
        s.style=bool(p.getProperty(key+"enabled","true"))?selected:Style.OFF;
        String color=p.getProperty(key+"color",rgb);if(!color.matches("[0-9a-fA-F]{6}"))throw new IllegalArgumentException(key+"color needs six hex digits");s.rgb=Integer.parseInt(color,16);
        String opacity=p.getProperty(key+"opacity","auto");s.opacity=opacity.equals("auto")?selected==Style.CROSSHAIR?.85:selected==Style.SCREEN?.06:.12:percent(opacity)/100;
        s.pulse=bool(p.getProperty(key+"pulse","false"));
    }
    static String styleName(String value){return style(value).name().toLowerCase(Locale.ROOT);}
    private static Style style(String value){try{return Style.valueOf(value.toUpperCase(Locale.ROOT));}catch(IllegalArgumentException e){throw new IllegalArgumentException("Style: crosshair, edge, screen, or off");}}
    static double percent(String value){double n;try{n=Double.parseDouble(value);}catch(NumberFormatException e){throw new IllegalArgumentException("Opacity must be 0–100 or auto");}if(!Double.isFinite(n)||n<0||n>100)throw new IllegalArgumentException("Opacity must be 0–100 or auto");return n;}
    private static boolean bool(String value){if(!value.equals("true")&&!value.equals("false"))throw new IllegalArgumentException("Expected true or false");return Boolean.parseBoolean(value);}
    private static Boolean inRange(Map<String,Object> range){return inRange(number(range,"distance",Double.NaN),number(range,"reach",Double.NaN));}
    private static Boolean inRange(double distance,double reach){return Double.isFinite(distance)&&distance>=0&&Double.isFinite(reach)&&reach>0?distance<=reach:null;}
    private static double limit(Map<String,Object> state,int side){Object r=state.get("reach");return r instanceof List&&((List<?>)r).size()==2&&((List<?>)r).get(side) instanceof Number?((Number)((List<?>)r).get(side)).doubleValue():3;}

    Frame render(Frame base,int width,int height,Map<String,Object> live,Map<String,Object> replay,long liveReceived){
        long wall=System.nanoTime();double time=wall/1e6,speed=1;Boolean own=null,opponent=null;String next="";
        if(Boolean.TRUE.equals(replay.get("active"))){
            next="replay:"+replay.get("match");time=number(replay,"time_ms",0);speed=number(replay,"speed",1);
            Map<String,Object> views=object(replay.get("views")),availability=object(replay.get("availability"));
            String mine="human".equals(object(availability.get("b")).get("role"))?"b":"a",other=mine.equals("a")?"b":"a";
            Map<String,Object> a=object(views.get(mine)),b=object(views.get(other));
            own=inRange(ReachAdvantage.distance(object(a.get(mine)),object(a.get(other))),limit(replay,mine.equals("a")?0:1));
            opponent=inRange(ReachAdvantage.distance(object(b.get(other)),object(b.get(mine))),limit(replay,other.equals("a")?0:1));
        }else if(!live.isEmpty()&&wall-liveReceived<250000000L){
            next="live:"+live.get("match");own=inRange(object(live.get("your_range")));
            if(Boolean.TRUE.equals(live.get("available")))opponent=inRange(object(live.get("bot_range")));
        }
        double elapsed=(wall-previousWall)/1e6;
        if(!next.equals(context)||!Double.isFinite(previousTime)||time<previousTime||time-previousTime>Math.max(250,elapsed*Math.max(1,speed)+100)){
            yours.clear();theirs.clear();
        }
        context=next;previousTime=time;previousWall=wall;
        double ya=yours.alpha(own,time),ta=theirs.alpha(opponent,time);
        List<Frame.Rectangle> shapes=new ArrayList<>();boolean split=yours.style==Style.EDGE&&theirs.style==Style.EDGE;
        // Tints first, crosshair marks last, then the bridge draws the existing HUD text.
        paint(shapes,yours,ya,true,width,height,split);paint(shapes,theirs,ta,false,width,height,split);
        boolean bothScreen=yours.style==Style.SCREEN&&theirs.style==Style.SCREEN;
        if(yours.style==Style.CROSSHAIR||bothScreen)brackets(shapes,yours.rgb,ya,true,width,height);
        if(theirs.style==Style.CROSSHAIR||bothScreen)brackets(shapes,theirs.rgb,ta,false,width,height);
        shapes.addAll(base.rectangles);
        return new Frame(base.holdSprint,base.lines,base.edgeColor,base.edgeWidth,shapes);
    }
    private static int color(int rgb,double alpha){return ((int)Math.round(255*alpha)<<24)|rgb;}
    private static void rect(List<Frame.Rectangle> out,int l,int t,int r,int b,int color){if(r>l&&b>t&&(color>>>24)>0)out.add(new Frame.Rectangle(l,t,r,b,color));}
    private static void paint(List<Frame.Rectangle> out,Signal s,double alpha,boolean own,int w,int h,boolean split){
        if(alpha<=0)return;
        if(s.style==Style.SCREEN){rect(out,0,0,w,h,color(s.rgb,alpha));return;}
        if(s.style!=Style.EDGE)return;
        int depth=Math.min(Math.min(w,h)/4,Math.max(24,Math.min(60,(int)(Math.min(w,h)*.09))));
        int bands=Math.min(20,depth);
        for(int i=0;i<bands;i++){
            int a=i*depth/bands,b=(i+1)*depth/bands;double fade=1-(i+.5)/bands;int c=color(s.rgb,alpha*fade*fade);
            if(!split){
                rect(out,a,a,w-a,b,c);rect(out,a,h-b,w-a,h-a,c);
                rect(out,a,b,b,h-b,c);rect(out,w-b,b,w-a,h-b,c);
            }else if(own){rect(out,0,a,w,b,c);rect(out,0,h-b,w,h-a,c);}
            else{rect(out,a,0,b,h,c);rect(out,w-b,0,w-a,h,c);}
        }
    }
    private static void brackets(List<Frame.Rectangle> out,int rgb,double alpha,boolean own,int w,int h){
        if(alpha<=0)return;int x=w/2,y=h/2,r=own?11:18,len=5,c=color(rgb,alpha);
        for(int sx:new int[]{-1,1})for(int sy:new int[]{-1,1}){
            int left=sx<0?x-r:x+r-len+1,top=sy<0?y-r:y+r;
            rect(out,left,top,left+len,top+1,c);
            int vx=sx<0?x-r:x+r,vy=sy<0?y-r+1:y+r-len+1;
            rect(out,vx,vy,vx+1,vy+len-1,c);
        }
    }
}
