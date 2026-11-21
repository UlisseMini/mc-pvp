package practice.bridge;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import java.util.Locale;

final class SlowMotionScreen extends GuiScreen {
    private static final int[] PRESETS={10,25,50,75,100};
    private final GuiScreen parent;
    private int percent;
    SlowMotionScreen(GuiScreen parent){this.parent=parent;percent=Math.round(100*TrainingTimer.target());}
    @Override public void func_73866_w_(){
        int x=field_146294_l/2,y=field_146295_m/2;
        for(int i=0;i<PRESETS.length;i++)
            field_146292_n.add(new GuiButton(i,x-111+i*45,y-25,42,20,PRESETS[i]+"%"));
        field_146292_n.add(new GuiButton(5,x-65,y,30,20,"-"));
        field_146292_n.add(new GuiButton(6,x+35,y,30,20,"+"));
        field_146292_n.add(new GuiButton(7,x-101,y+33,99,20,"Apply"));
        field_146292_n.add(new GuiButton(8,x+2,y+33,99,20,"Cancel"));
    }
    @Override protected void func_146284_a(GuiButton button){
        int id=button.field_146127_k;
        if(id>=0 && id<PRESETS.length)percent=PRESETS[id];
        if(id==5)percent=Math.max(10,percent-1);
        if(id==6)percent=Math.min(100,percent+1);
        if(id==7 && TrainingTimer.configurable() && field_146297_k.field_71439_g!=null){
            field_146297_k.field_71439_g.func_71165_d(String.format(Locale.ROOT,"/slowmo %.2f",percent/100.0));
            field_146297_k.func_147108_a(null);
        }
        if(id==8)field_146297_k.func_147108_a(parent);
    }
    @Override protected void func_73869_a(char typed,int key){
        if(key==1)field_146297_k.func_147108_a(parent);else super.func_73869_a(typed,key);
    }
    @Override public void func_73863_a(int mx,int my,float delta){
        func_146276_q_();
        int x=field_146294_l/2,y=field_146295_m/2;
        func_73732_a(field_146289_q,"Sumo room speed",x,y-48,0xffffff);
        func_73732_a(field_146289_q,percent+"%",x,y+6,0xffffff);
        super.func_73863_a(mx,my,delta);
    }
    @Override public boolean func_73868_f(){return false;}
}
