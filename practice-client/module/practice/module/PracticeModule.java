package practice.module;

import practice.api.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Rebuild/install this class while playing; the Forge bridge stays loaded. */
public final class PracticeModule implements PracticeExtension {
    private boolean enabled,potions,metrics,record,hurtFlash,sprint;
    private int inset,top,color,potionY;
    private String label;
    private Path directory;
    private BufferedWriter writer;
    private long blockedTicks,playingTicks;
    private int previousHurt;
    private long flashUntil;
    private Map<String,Object> linked=Collections.emptyMap();
    private long linkReceived;
    private final PracticeOverlay overlays=new PracticeOverlay();
    private Frame base=Frame.empty();
    private final ReplayOverlay replay=new ReplayOverlay();
    public void event(PracticeEvent e){if(e.type.equals("replay_frame"))replay.state=e.data;if(e.type.equals("link_state")){linked=e.data;linkReceived=e.timeMillis;}if(e.type.equals("practice_views"))overlays.views=e.data;}
    public Frame render(float partial,int width,int height){return replay.active()?replay.hud(base,width,height):overlays.hud(base,width,height);}
    public WorldFrame world(float partial){return replay.active()?replay.world():overlays.world();}

    public void configure(Properties p,Path directory) {
        this.directory=directory;
        overlays.configure(p);replay.configure(p);
        enabled=bool(p,"sprint.enabled",true);sprint=bool(p,"sprint.startEnabled",true);
        potions=bool(p,"potions.enabled",true);metrics=bool(p,"metrics.enabled",false);
        record=bool(p,"record.enabled",false);hurtFlash=bool(p,"feedback.hurtFlash",false);
        inset=integer(p,"hud.rightInset",6,0,2000);top=integer(p,"hud.top",6,0,2000);
        potionY=integer(p,"potions.top",22,0,2000);
        String hex=p.getProperty("hud.color","55FFFF");
        if(!hex.matches("[0-9a-fA-F]{6}"))throw new IllegalArgumentException("hud.color must be six hex digits");
        color=Integer.parseInt(hex,16);label=p.getProperty("sprint.label","[Sprinting (Toggled)]");
        if(label.length()>100)throw new IllegalArgumentException("sprint.label is too long");
    }
    private static boolean bool(Properties p,String key,boolean fallback) {
        String s=p.getProperty(key,String.valueOf(fallback)).trim();
        if(!s.equals("true")&&!s.equals("false"))throw new IllegalArgumentException(key+" must be true or false");
        return Boolean.parseBoolean(s);
    }
    private static int integer(Properties p,String key,int fallback,int min,int max){
        int value=Integer.parseInt(p.getProperty(key,String.valueOf(fallback)));
        if(value<min||value>max)throw new IllegalArgumentException(key+" out of bounds");return value;
    }
    public Frame tick(Snapshot s) throws IOException {
        if(s.sprintPressed && s.playing && !s.screenOpen)sprint=!sprint;
        List<Frame.Line> lines=new ArrayList<>();
        if(!s.playing){close();previousHurt=0;blockedTicks=playingTicks=0;overlays.views=Collections.emptyMap();return base=Frame.empty();}
        if(enabled && sprint)lines.add(new Frame.Line(label,inset,top,color,true));
        if(potions){int y=potionY;for(String effect:s.effects){lines.add(new Frame.Line(effect,inset,y,0xFFFFFF,true));y+=11;}}
        if(s.self!=null){
            playingTicks++;if(s.self.blocking)blockedTicks++;
            if(s.self.hurtTicks>previousHurt)flashUntil=s.timeMillis+150;
            previousHurt=s.self.hurtTicks;
            if(metrics){
                int y=6;
                lines.add(new Frame.Line(String.format(Locale.ROOT,"Speed: %.2f m/s (client)",s.self.speed()),6,y,0xFFFFFF,false));y+=11;
                if(s.target!=null){
                    double separation=Math.hypot(s.target.x-s.self.x,s.target.z-s.self.z);
                    lines.add(new Frame.Line(String.format(Locale.ROOT,"Target: %.2f m/s | gap %.2f m | height %+.2f",s.target.speed(),separation,s.target.y-s.self.y),6,y,0xFFFFFF,false));y+=11;
                }
                lines.add(new Frame.Line(String.format(Locale.ROOT,"Blocking: %.1f%% of observed ticks",100.0*blockedTicks/playingTicks),6,y,0x55FFFF,false));
                if(linkReceived>0){y+=11;Object clients=linked.get("clients");int views=clients instanceof Map?((Map<?,?>)clients).size():0;
                    lines.add(new Frame.Line("Practice link: server + "+views+" client views",6,y,0xAAAAAA,false));}
            }
            if(record)write(s);
        }
        return base=new Frame(enabled&&sprint&&!s.screenOpen,lines,hurtFlash&&s.timeMillis<flashUntil?0x40FF4444:0,5);
    }
    private void write(Snapshot s) throws IOException {
        if(writer==null){
            Path recordings=directory.resolve("recordings");Files.createDirectories(recordings);
            Path file=Files.createTempFile(recordings,"client-"+s.timeMillis+"-",".csv");
            writer=Files.newBufferedWriter(file,StandardCharsets.UTF_8);
            writer.write("wall_time_ms,monotonic_ns,client_tick,self_id,self_x,self_y,self_z,self_dx,self_dy,self_dz,self_grounded,self_blocking,self_hp,self_hurt_ticks,target_id,target_x,target_y,target_z,target_dx,target_dy,target_dz,target_grounded,target_blocking,target_hp,target_hurt_ticks\n");
        }
        writer.write(s.timeMillis+","+s.timeNanos+","+s.tick+","+csv(s.self)+","+csv(s.target)+"\n");
        if(s.tick%20==0)writer.flush();
    }
    private static String csv(Snapshot.Player p){
        if(p==null)return ",,,,,,,,,,";
        return p.id+","+p.x+","+p.y+","+p.z+","+p.dx+","+p.dy+","+p.dz+","+p.grounded+","+p.blocking+","+p.health+","+p.hurtTicks;
    }
    public void close() throws IOException {if(writer!=null){writer.close();writer=null;}}
}
