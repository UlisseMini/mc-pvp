package practice.bridge;

import java.util.*;
import java.text.SimpleDateFormat;
import net.minecraft.client.gui.*;
import practice.core.replay.ReplayRecording;
import static practice.replay.PoseTrack.*;
import org.lwjgl.input.Keyboard;

/** Available at the title screen and in-client; no server commands. */
final class OfflineReplayMenu extends GuiScreen {
    final OfflineReplay replay;int page;Map<Integer,Runnable> actions=new HashMap<>();int timelineY;boolean sumoOnly,display;
    OfflineReplayMenu(OfflineReplay replay){this.replay=replay;}
    public boolean func_73868_f(){return false;}
    void button(int id,int x,int y,int w,String label,Runnable action){GuiButton b=new GuiButton(id,x,y,w,20,label);field_146292_n.add(b);actions.put(id,action);}
    private List<ReplayRecording.Entry> entries(){
        if(!sumoOnly)return replay.entries;
        List<ReplayRecording.Entry> matches=new ArrayList<>();
        for(ReplayRecording.Entry entry:replay.entries)if(entry.id.startsWith("sumo:"))matches.add(entry);
        return matches;
    }
    private void cameraButtons(int x,int y){
        String[] modes={"recorded","third_person","opponent"};
        String[] labels={"First person","Spectator","Follow target"};
        for(int i=0;i<modes.length;i++){
            final String mode=modes[i];
            button(20+i,x+i*80,y,78,labels[i],()->replay.camera(mode));
            if(mode.equals(replay.camera))field_146292_n.get(field_146292_n.size()-1).field_146124_l=false;
        }
        if(replay.spectatorCamera())button(23,x+240,y,78,"Recenter",replay::centerSpectator);
    }
    public void func_73866_w_(){
        field_146292_n.clear();actions.clear();int x=field_146294_l/2-160,y=35;
        if(replay.active&&display){
            cameraButtons(x,y);
            y+=26;
            ReplayDisplay.Layer[] layers=ReplayDisplay.Layer.values();
            for(int i=0;i<layers.length;i++){
                ReplayDisplay.Layer layer=layers[i];
                button(60+i,x+(i%2)*160,y+(i/2)*23,158,layer.label+": "+(replay.shows(layer)?"On":"Off"),
                        ()->replay.toggleDisplay(layer));
                if(!replay.displayAvailable(layer))field_146292_n.get(field_146292_n.size()-1).field_146124_l=false;
            }
            y+=((layers.length+1)/2)*23+6;
            button(78,x,y,158,"Back to controls",()->display=false);
            button(79,x+160,y,158,"Return to replay",()->field_146297_k.func_147108_a(null));
        }else if(replay.active){
            button(0,x,y,62,replay.paused?"Play":"Pause",replay::togglePause);
            button(1,x+64,y,62,"Tick back",replay::backTick);
            button(2,x+128,y,62,"Tick step",replay::step);
            button(3,x+192,y,62,"-5 seconds",()->replay.seek(replay.time-5000));
            button(4,x+256,y,62,"+5 seconds",()->replay.seek(replay.time+5000));y+=23;
            for(int i=0;i<OfflineReplay.PLAYBACK_SPEEDS.length;i++){
                double speed=OfflineReplay.PLAYBACK_SPEEDS[i];
                button(10+i,x+i*53,y,51,speed+"x",()->replay.speed(speed));
                if(speed==replay.speed)field_146292_n.get(field_146292_n.size()-1).field_146124_l=false;
            }
            y+=23;
            cameraButtons(x,y);
            y+=25;timelineY=y;y+=47;
            button(30,x,y,104,"Close (F8)",()->field_146297_k.func_147108_a(null));
            button(31,x+107,y,104,"Exit replay",replay::stop);
            button(35,x+214,y,104,"Return to MMC",replay::returnToMmc);y+=25;
            button(32,x,y,112,"Display…",()->display=true);
            button(33,x+114,y,101,"Previous KB cut",()->replay.seekReduction(false));
            button(34,x+217,y,101,"Next KB cut",()->replay.seekReduction(true));
            y+=23;
            button(36,x,y,108,"Previous change",()->replay.stepMotion(false));
            button(37,x+110,y,108,"Next change",()->replay.stepMotion(true));
            button(38,x+220,y,98,"Edit HUD",replay::editHud);
        }else if(replay.queuedMatch!=null){
            button(40,x,y+31,318,"Return to game",()->field_146297_k.func_147108_a(null));
            button(41,x,y+55,318,"Cancel replay selection",()->{
                replay.queuedMatch=null;
                replay.refresh();
                func_73866_w_();
            });
        }else{
            List<ReplayRecording.Entry> entries=entries();
            page=Math.min(page,Math.max(0,(entries.size()-1)/6));
            int count=Math.min(6,entries.size()-page*6),listWidth=Math.min(440,field_146294_l-12);
            SimpleDateFormat date=new SimpleDateFormat("MM-dd HH:mm");
            for(int i=0;i<count;i++){final ReplayRecording.Entry e=entries.get(page*6+i);String text=date.format(new Date(e.startMs))+" | "+e.server+" | "+(e.durationEstimated?"≈":"")+String.format(Locale.ROOT,"%.1fs",e.durationMs/1000)+" | "+e.label;
                button(100+i,(field_146294_l-listWidth)/2,y,listWidth,field_146289_q.func_78269_a(text,listWidth-12),()->replay.select(e));y+=24;}
            if(entries.isEmpty())y+=24;
            button(200,x,y,104,"Previous",()->{page=Math.max(0,page-1);func_73866_w_();});button(201,x+107,y,104,"Next",()->{page=Math.min(Math.max(0,(entries.size()-1)/6),page+1);func_73866_w_();});button(202,x+214,y,104,"Refresh",()->{replay.refresh();page=0;func_73866_w_();});
            y+=24;button(203,x,y,77,"Back",()->field_146297_k.func_147108_a(field_146297_k.field_71439_g==null?new GuiMainMenu():null));
            button(204,x+80,y,158,sumoOnly?"Show all captures":"Show MMC Sumo only",()->{sumoOnly=!sumoOnly;page=0;func_73866_w_();});
            button(205,x+241,y,77,"Edit HUD",replay::editHud);
        }
    }
    protected void func_146284_a(GuiButton button){Runnable action=actions.get(button.field_146127_k);if(action!=null){action.run();if(replay.active)func_73866_w_();}}
    protected void func_73864_a(int x,int y,int button){
        int left=field_146294_l/2-160;
        if(replay.active&&!display&&button==0&&y>=timelineY&&y<=timelineY+18&&x>=left&&x<=left+320){
            double start=replay.recording.playStart(),duration=replay.recording.playEnd()-start;
            replay.seek(start+(x-left)/320.0*duration);return;
        }
        super.func_73864_a(x,y,button);
    }
    protected void func_73869_a(char c,int key){
        if(replay.active&&replay.openCommandChat(key))return;
        if(key==Keyboard.KEY_ESCAPE&&replay.active&&display){display=false;func_73866_w_();}
        else if(replay.active&&replay.key(key,GuiScreen.func_146272_n()))func_73866_w_();
        else if(replay.active&&key==field_146297_k.field_71474_y.field_151457_aa.func_151463_i()){replay.togglePerspective();func_73866_w_();}
        else super.func_73869_a(c,key);
    }
    public void func_73876_c(){if(replay.active)func_73866_w_();}
    public void func_73863_a(int mx,int my,float partial){
        func_146276_q_();
        String title=replay.active?(display?"Display settings · "+replay.cameraLabel():"Local offline playback"):"Local captures";
        if(replay.active)replay.drawKeystrokes(field_146289_q,field_146294_l,field_146295_m,true);
        func_73732_a(field_146289_q,title,field_146294_l/2,15,0xFFFFFF);
        if(replay.active&&display){
            String hint=replay.displayAvailable(ReplayDisplay.Layer.REACH_FIELDS)?"Settings are saved for the selected camera.":"Type /p reach on to enable reach fields.";
            func_73732_a(field_146289_q,hint,field_146294_l/2,213,0xBBBBBB);
        }else if(replay.active){
            int x=field_146294_l/2-160;
            double start=replay.recording.playStart(),end=replay.recording.playEnd(),duration=Math.max(1,end-start);
            func_73734_a(x,timelineY,x+320,timelineY+16,0xFF333333);
            double elapsed=replay.playhead()-start;
            int head=x+(int)(320*elapsed/duration);
            for(ReplayRecording.Mark mark:replay.recording.timeline){
                if(mark.atMs<start||mark.atMs>end)continue;
                int at=x+(int)(320*(mark.atMs-start)/duration);
                int color=mark.kind.equals("attack")?0xFFFFBB44:mark.kind.contains("velocity")?(Boolean.TRUE.equals(mark.details.get("own"))?0xFF55FFFF:0xFF668899):mark.kind.equals("hurt")?0xFFFF6666:0xFF888888;
                func_73734_a(at,timelineY+2,at+1,timelineY+14,color);
            }
            for(ReplayRecording.Mark mark:replay.recording.timeline){
                if((!mark.kind.equals("recoil_reduction")&&!mark.kind.equals("horizontal_attack_scale"))||mark.atMs<start||mark.atMs>end)continue;
                int at=x+(int)(320*(mark.atMs-start)/duration);
                func_73734_a(at,timelineY+1,at+2,timelineY+8,0xFF91FFB0);
            }
            func_73734_a(head,timelineY,head+2,timelineY+16,0xFFFFFFFF);
            func_73732_a(field_146289_q,String.format(Locale.ROOT,"%.2f / %.2fs  %.2fx | green: horizontal ×0.6",elapsed/1000,duration/1000,replay.speed),field_146294_l/2,timelineY+19,0xBBBBBB);
            if(replay.shows(ReplayDisplay.Layer.KB_COUNT))func_73732_a(field_146289_q,replay.recoilLabel(),field_146294_l/2,timelineY+31,0x91FFB0);
            func_73732_a(field_146289_q,"Space play · ←/→ scrub · , . tick · [ ] speed · ↑/↓ match · O text",field_146294_l/2,224,0xBBBBBB);
        }else if(replay.queuedMatch!=null){
            func_73732_a(field_146289_q,replay.queuedMatch.label,field_146294_l/2,35,0xFFFFFF);
            func_73732_a(field_146289_q,replay.message.isEmpty()?"Disconnect whenever you're ready to watch.":replay.message,field_146294_l/2,48,0xBBBBBB);
        }else if(entries().isEmpty()){
            func_73732_a(field_146289_q,sumoOnly?"No saved MMC Sumo matches":"No local captures found",field_146294_l/2,field_146295_m-40,0xBBBBBB);
        }
        if(replay.queuedMatch==null&&!replay.message.isEmpty())func_73732_a(field_146289_q,replay.message,field_146294_l/2,field_146295_m-25,0xFFCC77);
        super.func_73863_a(mx,my,partial);
    }
}
