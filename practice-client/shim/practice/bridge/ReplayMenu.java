package practice.bridge;
import java.util.*;
import net.minecraft.client.gui.*;
import static practice.replay.PoseTrack.*;

/** Native, non-pausing menu; actions are validated again by the replay server. */
final class ReplayMenu extends GuiScreen {
    private final PracticeCapture capture;private final Map<Integer,String> actions=new HashMap<>();private int ticks,rowHeight;
    ReplayMenu(PracticeCapture capture){this.capture=capture;}
    public boolean func_73868_f(){return false;}
    private void button(int x,int y,int width,String text,String command,boolean enabled){int id=actions.size();actions.put(id,command);GuiButton b=new GuiButton(id,x,y,width,Math.min(20,rowHeight-1),text);b.field_146124_l=enabled;field_146292_n.add(b);}
    public void func_73866_w_(){
        field_146292_n.clear();actions.clear();Map<String,Object> s=capture.replay.state(),available=object(s.get("availability"));rowHeight=Math.max(12,Math.min(23,(field_146295_m-31)/12));int x=field_146294_l/2-152,y=top();
        button(x,y,60,"-5s","back 5",true);button(x+61,y,60,"-1s","back 1",true);button(x+122,y,60,Boolean.TRUE.equals(s.get("paused"))?"Play":"Pause",Boolean.TRUE.equals(s.get("paused"))?"play":"pause",true);button(x+183,y,60,"+1s","forward 1",true);button(x+244,y,60,"+5s","forward 5",true);y+=rowHeight;
        button(x,y,75,"< Event","event prev",true);button(x+76,y,75,"Event >","event next",true);
        button(x+153,y,75,"< Tick","tick prev",true);button(x+229,y,75,"Tick >","tick next",true);y+=rowHeight;
        String[] speeds={"0.1","0.25","0.5","1","2"};for(int i=0;i<5;i++)button(x+i*61,y,60,speeds[i]+"x","speed "+speeds[i],true);y+=rowHeight;
        String[] sources={"server","a","b"};for(int i=0;i<3;i++){String source=sources[i];Map<String,Object> info=object(available.get(source));String label=source.equals("server")?"Server":String.valueOf(info.get("role"))+" ("+source.toUpperCase(Locale.ROOT)+")";
            button(x+i*102,y,100,(source.equals(s.get("source"))?"* ":"")+label,"pov "+source,Boolean.TRUE.equals(info.get("available")));}y+=rowHeight;
        button(x,y,100,"Free camera","camera free",true);button(x+102,y,100,"Follow A","camera a",true);button(x+204,y,100,"Follow B","camera b",true);y+=rowHeight;
        Object selected=s.get("ghosts");for(String source:sources){Map<String,Object> info=object(available.get(source));for(int i=0;i<2;i++){String side=i==0?"a":"b",key=source+":"+side;boolean on=selected instanceof List&&((List<?>)selected).contains(key);
            button(x+i*153,y,151,(on?"ON ":"off ")+side.toUpperCase(Locale.ROOT)+" in "+(source.equals("server")?"server":source.toUpperCase(Locale.ROOT)+" client")+" view","ghost "+key+" "+(on?"off":"on"),Boolean.TRUE.equals(info.get("available")));}y+=rowHeight;}
        button(x,y,151,"Previous combo","control 7 left",true);button(x+153,y,151,"Next combo","control 7 right",true);y+=rowHeight;
        button(x,y,100,"Reach ON","local_reach on",true);button(x+102,y,100,"Reach OFF","local_reach off",true);button(x+204,y,100,"Exit replay","stop",true);
    }
    protected void func_146284_a(GuiButton b){String command=actions.get(b.field_146127_k);if(command==null)return;
        if(command.startsWith("local_reach"))net.minecraftforge.client.ClientCommandHandler.instance.func_71556_a(field_146297_k.field_71439_g,"/practice reach "+command.split(" ")[1]);
        else capture.send("replay_control",command);
        if(command.equals("stop"))field_146297_k.func_147108_a(null);
    }
    public void func_73876_c(){if(!capture.replay.active())field_146297_k.func_147108_a(null);else if(++ticks%4==0)func_73866_w_();}
    private int top(){return Math.max(24,(field_146295_m-10*rowHeight)/2);}
    public void func_73863_a(int mx,int my,float partial){func_146276_q_();func_73732_a(field_146289_q,"Replay settings",field_146294_l/2,top()-22,0xFFFFFF);Object label=capture.replay.state().get("step_label");if(label instanceof String)func_73732_a(field_146289_q,field_146289_q.func_78269_a((String)label,field_146294_l-8),field_146294_l/2,top()-11,0xFFDB82);super.func_73863_a(mx,my,partial);}
}
