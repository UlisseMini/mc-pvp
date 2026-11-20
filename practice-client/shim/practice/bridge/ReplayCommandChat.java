package practice.bridge;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.minecraft.client.gui.GuiChat;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.command.ICommand;
import net.minecraft.event.ClickEvent;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.IChatComponent;
import net.minecraftforge.client.ClientCommandHandler;
import org.lwjgl.input.Keyboard;

/** Replay chat dispatches practice commands directly on the client. */
final class ReplayCommandChat extends GuiChat {
    private final GuiScreen parent;

    ReplayCommandChat(GuiScreen parent){super("/");this.parent=parent;}

    @Override protected void func_73869_a(char c,int key){
        if(key==Keyboard.KEY_ESCAPE){field_146297_k.func_147108_a(parent);return;}
        if(key==Keyboard.KEY_RETURN||key==Keyboard.KEY_NUMPADENTER){
            String line=field_146415_a.func_146179_b().trim();
            field_146415_a.func_146180_a("/");
            if(!line.isEmpty()&&!line.equals("/"))func_175281_b(line,true);
            return;
        }
        super.func_73869_a(c,key);
    }

    @Override public void func_175281_b(String line,boolean remember){
        String root=line.split("\\s+",2)[0];
        if(!root.equals("/practice")&&!root.equals("/p")){
            notice("Use /p help to see the local replay commands.");
            return;
        }
        if(remember)field_146297_k.field_71456_v.func_146158_b().func_146239_a(line);
        ClientCommandHandler.instance.func_71556_a(field_146297_k.field_71439_g,line);
    }

    @Override protected boolean func_175276_a(IChatComponent component){
        if(component==null)return false;
        ClickEvent event=component.func_150256_b().func_150235_h();
        if(event==null)return false;
        String root=event.func_150668_b().split("\\s+",2)[0];
        return (root.equals("/p")||root.equals("/practice"))&&
                (event.func_150669_a()==ClickEvent.Action.RUN_COMMAND||event.func_150669_a()==ClickEvent.Action.SUGGEST_COMMAND)
                &&super.func_175276_a(component);
    }

    @Override public void func_146404_p_(){
        String before=field_146415_a.func_146179_b().substring(0,field_146415_a.func_146198_h());
        int space=before.indexOf(' ');
        List<String> choices;
        if(space<0){
            choices=new ArrayList<>();
            for(String root:Arrays.asList("/p","/practice"))if(root.startsWith(before))choices.add(root);
        }else{
            String root=before.substring(0,space);
            if(!root.equals("/practice")&&!root.equals("/p"))return;
            ICommand command=ClientCommandHandler.instance.func_71555_a().get(root.substring(1));
            choices=command.func_180525_a(field_146297_k.field_71439_g,before.substring(space+1).split(" ",-1),null);
        }
        if(choices==null||choices.isEmpty())return;
        String token=before.substring(before.lastIndexOf(' ')+1);
        if(choices.size()==1&&choices.get(0).startsWith(token))field_146415_a.func_146191_b(choices.get(0).substring(token.length()));
        else notice(String.join("  ",choices));
    }

    private void notice(String text){field_146297_k.field_71456_v.func_146158_b().func_146227_a(new ChatComponentText(text));}
}
