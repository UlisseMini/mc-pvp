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
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.Display;
import practice.core.replay.ReplayInputs.Key;
import practice.core.replay.ReplayInputs.State;
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
        for(Key key:KEYS){
            boolean pressed=key==Key.LMB||key==Key.RMB?Mouse.isButtonDown(key.code):Keyboard.isKeyDown(key.code);
            if(pressed)mask|=key.mask();
        }
        return new State(mask,cps(left,now),cps(right,now));
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
            draw(font,new State((int)number(keys,"mask",0),(int)number(keys,"left_cps",0),(int)number(keys,"right_cps",0)),point.x,point.y);
        }
    }

    static void drawCompact(FontRenderer font,State state,int width){
        int x=(width-204)/2;
        for(Key key:KEYS){
            String label=label(key,state);
            int size=key==Key.SPACE?40:key.ordinal()>=Key.LMB.ordinal()?48:14;
            tile(font,state,key,label,x,1,size,12);
            x+=size+2;
        }
    }

    static void draw(FontRenderer font,State state,int x,int y){
        tile(font,state,Key.W,"W",x+26,y,24,15);
        tile(font,state,Key.A,"A",x,y+17,24,15);
        tile(font,state,Key.S,"S",x+26,y+17,24,15);
        tile(font,state,Key.D,"D",x+52,y+17,24,15);
        tile(font,state,Key.SPACE,"SPACE",x,y+34,76,14);
        tile(font,state,Key.LMB,"LMB",x,y+50,37,24);
        tile(font,state,Key.RMB,"RMB",x+39,y+50,37,24);
    }

    private static String label(Key key,State state){
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
        }else centered(font,label,center,y+(height-8)/2,foreground);
    }

    private static void centered(FontRenderer font,String label,int x,int y,int color){
        font.func_78276_b(label,x-font.func_78256_a(label)/2,y,color);
    }
}
