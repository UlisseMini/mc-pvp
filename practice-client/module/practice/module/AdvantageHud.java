package practice.module;
import practice.api.Frame;
import java.util.*;
import static practice.replay.PoseTrack.number;

final class AdvantageHud {
    static void append(List<Frame.Line> lines,Map<String,Object> data,int height,String owner){
        int y=height-106;if(data.isEmpty()){lines.add(new Frame.Line("Reach advantage: waiting for both client views",0,y,0xAAAAAA,false,1,true));return;}
        double d1=number(data,"d1_theirs",0),d2=number(data,"d2_yours",0),advantage=d1-d2;int color=advantage>.005?0x55FFAA:advantage<-.005?0xFF7777:0xDDDDDD;
        lines.add(new Frame.Line(String.format(Locale.ROOT,"%s reach advantage %+.2f blocks",owner,Math.abs(advantage)<.005?0:advantage),0,y,color,false,1.5f,true));
        lines.add(new Frame.Line(String.format(Locale.ROOT,"Your view (d2): %.2f    Their view (d1): %.2f    |    d1 - d2",d2,d1),0,y+18,0xFFFFFF,false,1,true));
        String in=d2<=3?(d1<=3?"Both in reach":"Only you in reach"):(d1<=3?"Only opponent in reach":"Neither in reach");
        lines.add(new Frame.Line(in+"  |  3.00-block ideal-aim boundary",0,y+30,0xAAAAAA,false,1,true));
    }
}
