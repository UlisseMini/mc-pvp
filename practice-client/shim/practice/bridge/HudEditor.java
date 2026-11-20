package practice.bridge;

import java.awt.Point;
import java.awt.Rectangle;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import org.lwjgl.input.Keyboard;
import practice.api.Frame;
import practice.core.replay.ReplayInputs.Key;
import practice.core.replay.ReplayInputs.State;

final class HudEditor extends GuiScreen {
    private final HudLayout layout;
    private final Frame.Line sprint;
    private final GuiScreen parent;
    private final List<Widget> widgets=new ArrayList<>();
    private Widget dragging;
    private int grabX,grabY;
    private String message="";

    HudEditor(HudLayout layout,Frame.Line sprint,GuiScreen parent){
        this.layout=layout;this.sprint=sprint;this.parent=parent;
    }

    private static final class Widget {
        final String id;
        final Rectangle initial;
        final Consumer<Point> draw;
        Widget(String id,Rectangle initial,Consumer<Point> draw){this.id=id;this.initial=initial;this.draw=draw;}
    }

    @Override public void func_73866_w_(){
        widgets.clear();dragging=null;
        State sample=new State(Key.W.mask()|Key.LMB.mask(),8,0);
        widgets.add(new Widget(KeystrokesHud.ID,KeystrokesHud.defaultBounds(field_146294_l,field_146295_m),
                at->KeystrokesHud.draw(field_146289_q,sample,at.x,at.y)));
        int width=field_146289_q.func_78256_a(sprint.text);
        widgets.add(new Widget(sprint.hudId,new Rectangle(field_146294_l-sprint.x-width,sprint.y,width,9),
                at->field_146289_q.func_175063_a(sprint.text,at.x,at.y,sprint.color)));
        int center=field_146294_l/2;
        field_146292_n.add(new GuiButton(0,center-103,field_146295_m-24,100,20,"Reset layout"));
        field_146292_n.add(new GuiButton(1,center+3,field_146295_m-24,100,20,"Done"));
    }

    @Override public boolean func_73868_f(){return false;}

    private Rectangle bounds(Widget widget){
        Rectangle initial=widget.initial;
        Point at=layout.position(widget.id,initial.x,initial.y,field_146294_l-initial.width,field_146295_m-initial.height);
        return new Rectangle(at.x,at.y,initial.width,initial.height);
    }

    @Override public void func_73863_a(int mouseX,int mouseY,float partial){
        func_146276_q_();
        func_73732_a(field_146289_q,"Drag HUD elements to move them",field_146294_l/2,field_146295_m-38,0xFFFFFF);
        if(!message.isEmpty())func_73732_a(field_146289_q,message,field_146294_l/2,field_146295_m-50,0xFFCC77);
        for(Widget widget:widgets){
            Rectangle box=bounds(widget);
            if(widget==dragging||box.contains(mouseX,mouseY))
                func_73734_a(box.x-1,box.y-1,box.x+box.width+1,box.y+box.height+1,0x8046BFFF);
            widget.draw.accept(box.getLocation());
        }
        super.func_73863_a(mouseX,mouseY,partial);
    }

    @Override protected void func_73864_a(int x,int y,int button){
        if(button==0){
            for(GuiButton control:field_146292_n)if(control.func_146116_c(field_146297_k,x,y)){
                super.func_73864_a(x,y,button);return;
            }
            for(int i=widgets.size()-1;i>=0;i--){
                Widget widget=widgets.get(i);
                Rectangle box=bounds(widget);
                if(box.contains(x,y)){dragging=widget;grabX=x-box.x;grabY=y-box.y;return;}
            }
        }
        super.func_73864_a(x,y,button);
    }

    @Override protected void func_146273_a(int x,int y,int button,long elapsed){
        if(dragging!=null&&button==0)layout.move(dragging.id,x-grabX,y-grabY,
                field_146294_l-dragging.initial.width,field_146295_m-dragging.initial.height);
    }

    @Override protected void func_146286_b(int x,int y,int button){
        if(dragging!=null&&button==0){func_146273_a(x,y,button,0);dragging=null;save();}
        super.func_146286_b(x,y,button);
    }

    @Override protected void func_146284_a(GuiButton button){
        if(button.field_146127_k==0){layout.reset();save();}
        else close();
    }

    @Override protected void func_73869_a(char typed,int key){if(key==Keyboard.KEY_ESCAPE)close();}

    private void close(){if(save())field_146297_k.func_147108_a(parent);}

    private boolean save(){
        try{layout.save();message="";return true;}
        catch(IOException error){message="Could not save HUD layout";return false;}
    }
}
