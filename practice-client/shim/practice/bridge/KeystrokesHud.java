package practice.bridge;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.awt.Point;
import java.awt.Rectangle;
import java.util.concurrent.TimeUnit;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.client.settings.KeyBinding;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.GL11;
import practice.core.replay.ReplayInputs.Key;
import practice.core.replay.ReplayInputs.State;
import practice.core.replay.ReplayInputs;
import static practice.replay.PoseTrack.object;
import static practice.replay.PoseTrack.number;

final class KeystrokesHud {
    static final String ID="keystrokes";
    private static final Key[] KEYS=Key.values();
    private static final long SECOND=TimeUnit.SECONDS.toNanos(1);
    private final Deque<Long> left=new ArrayDeque<>(),right=new ArrayDeque<>();
    private boolean playing;
    boolean enabled=true;

    static boolean playing(Minecraft mc){
        return mc.field_71439_g!=null&&mc.field_71462_r==null&&Display.isActive();
    }

    boolean gameplayEnded(Minecraft mc){
        boolean now=playing(mc),ended=playing&&!now;
        playing=now;
        return ended;
    }

    void clicked(int button,long now){
        Deque<Long> clicks=button==0?left:button==1?right:null;
        if(clicks!=null){clicks.addLast(now);cps(clicks,now);}
    }

    State live(long now){
        int mask=0;
        KeyBinding sprint=Minecraft.func_71410_x().field_71474_y.field_151444_V;
        for(Key key:KEYS){
            boolean pressed=key==Key.SPRINT?sprint.func_151470_d():
                key==Key.LMB||key==Key.RMB?Mouse.isButtonDown(key.code):Keyboard.isKeyDown(key.code);
            if(pressed)mask|=key.mask();
        }
        return new State(mask,cps(left,now),cps(right,now),sprint.func_151463_i());
    }

    private static int cps(Deque<Long> clicks,long now){
        while(!clicks.isEmpty()&&now-clicks.peekFirst()>=SECOND)clicks.removeFirst();
        return clicks.size();
    }

    static Rectangle defaultBounds(int width,int height){
        return new Rectangle(width-84,Math.max(2,height-101),76,74);
    }

    static void drawAtLayout(FontRenderer font,State state,int width,int height,HudLayout layout){
        Point point=layoutPosition(width,height,layout);
        draw(font,state,point.x,point.y);
    }

    private static Point layoutPosition(int width,int height,HudLayout layout){
        Rectangle box=defaultBounds(width,height);
        return layout.position(ID,box.x,box.y,width-box.width,height-box.height);
    }

    static void drawServerReplay(FontRenderer font,Map<String,Object> frame,int width,int height,HudLayout layout){
        Point point=layoutPosition(width,height,layout);
        Map<String,Object> keys=object(frame.get("keystrokes"));
        if(keys.isEmpty()){
            String status=Boolean.TRUE.equals(frame.get("keystrokes_loading"))?"Loading inputs…":"Inputs unavailable";
            int halfWidth=(font.func_78256_a(status)+1)/2;
            int center=Math.max(halfWidth,Math.min(width-halfWidth,point.x+38));
            centered(font,status,center,point.y,0xFFBBBBBB);
        }else{
            draw(font,new State((int)number(keys,"mask",0),(int)number(keys,"left_cps",0),(int)number(keys,"right_cps",0),
                (int)number(keys,"sprint_code",ReplayInputs.INPUT_UNKNOWN)),point.x,point.y);
            Map<String,Object> details=object(frame.get("bot_controls"));
            if(!details.isEmpty())drawBotDetails(font,details,point,width,height);
        }
    }

    private static void drawBotDetails(FontRenderer font,Map<String,Object> data,Point point,int width,int height){
        java.util.List<String> lines=new java.util.ArrayList<>();
        if(data.get("local_sprint") instanceof Boolean)lines.add("Client sprint "+(Boolean.TRUE.equals(data.get("local_sprint"))?"ON":"OFF"));
        if(data.get("sent_sprint") instanceof Boolean)lines.add("Sent "+(Boolean.TRUE.equals(data.get("sent_sprint"))?"START":"STOP")+"  "+(int)number(data,"sent_ago_ms",0)+" ms");
        if(data.get("click_gate") instanceof Boolean)lines.add("Clicks "+(Boolean.TRUE.equals(data.get("click_gate"))?"allowed":"held back"));
        int w=0;for(String line:lines)w=Math.max(w,font.func_78256_a(line));
        int x=Math.max(0,Math.min(width-w-6,point.x+76-w-6)),h=lines.size()*10+4;
        int y=point.y>=h+2?point.y-h-2:Math.min(height-h,point.y+76);
        Gui.func_73734_a(x,y,x+w+6,y+h,0xBA0D131B);
        for(String line:lines){font.func_78276_b(line,x+3,y+2,0xFFE4EFF5);y+=10;}
    }

    static void drawCompact(FontRenderer font,State state,int width){
        int x=(width-242)/2;
        for(Key key:KEYS){
            String label=label(key,state);
            int size=key==Key.SPRINT?36:key==Key.SPACE?40:key==Key.LMB||key==Key.RMB?48:14;
            tile(font,state,key,label,x,1,size,12);
            x+=size+2;
        }
    }

    static void draw(FontRenderer font,State state,int x,int y){
        tile(font,state,Key.W,"W",x+26,y,24,15);
        tile(font,state,Key.A,"A",x,y+17,24,15);
        tile(font,state,Key.S,"S",x+26,y+17,24,15);
        tile(font,state,Key.D,"D",x+52,y+17,24,15);
        tile(font,state,Key.SPACE,"SPACE",x,y+34,35,14);
        tile(font,state,Key.SPRINT,label(Key.SPRINT,state),x+37,y+34,39,14);
        tile(font,state,Key.LMB,"LMB",x,y+50,37,24);
        tile(font,state,Key.RMB,"RMB",x+39,y+50,37,24);
    }

    private static String label(Key key,State state){
        if(key==Key.SPRINT){
            if(state.sprintCode==ReplayInputs.INPUT_UNKNOWN)return "SPR ?";
            if(state.sprintCode==ReplayInputs.BINDING_UNKNOWN)return "Sprint";
            if(state.sprintCode==Keyboard.KEY_LCONTROL)return "LCtrl";
            if(state.sprintCode==Keyboard.KEY_RCONTROL)return "RCtrl";
            if(state.sprintCode<0&&state.sprintCode>=-100)return "M"+(101+state.sprintCode);
            if(state.sprintCode<=0||state.sprintCode>=Keyboard.KEYBOARD_SIZE)return "Unbound";
            return GameSettings.func_74298_c(state.sprintCode);
        }
        if(key==Key.LMB)return "L "+state.leftCps+" CPS";
        if(key==Key.RMB)return "R "+state.rightCps+" CPS";
        return key.name();
    }

    private static void tile(FontRenderer font,State state,Key key,String label,int x,int y,int width,int height){
        boolean down=state.down(key);
        int foreground=down?0xFF17212B:0xFFFFFFFF;
        Gui.func_73734_a(x,y,x+width,y+height,down?0xE7B6ECFF:0xAD0D131B);
        int center=x+width/2;
        if(height==24){
            centered(font,label,center,y+2,foreground);
            centered(font,(key==Key.LMB?state.leftCps:state.rightCps)+" CPS",center,y+13,foreground);
        }else{
            int color=key==Key.SPRINT&&state.sprintCode==ReplayInputs.INPUT_UNKNOWN?0xFF9AA3AE:foreground;
            int textWidth=font.func_78256_a(label);
            if(textWidth<=width-2)centered(font,label,center,y+(height-8)/2,color);
            else{
                float scale=(width-2f)/textWidth;
                GL11.glPushMatrix();
                try{
                    GL11.glTranslatef(center,y+(height-8*scale)/2,0);
                    GL11.glScalef(scale,scale,1);
                    centered(font,label,0,0,color);
                }finally{GL11.glPopMatrix();}
            }
        }
    }

    private static void centered(FontRenderer font,String label,int x,int y,int color){
        font.func_78276_b(label,x-font.func_78256_a(label)/2,y,color);
    }
}
