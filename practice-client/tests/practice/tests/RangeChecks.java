package practice.tests;

import practice.api.*;
import practice.core.*;
import java.nio.file.*;
import java.io.*;
import java.util.*;
import static practice.core.JsonData.map;

/** Exercise the installed module ABI and real HUD output, including reload and replay. */
final class RangeChecks {
    private static int checks;
    private static final int BLUE=0x55AAFF,RED=0xFF5555;
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    private static void event(ReloadHost host,String kind,Map<String,Object> value){host.event(new PracticeEvent(kind,1,System.currentTimeMillis(),System.nanoTime(),value));}
    private static Map<String,Object> live(double own,double other,double reach,boolean available){return map("match","test","your_range",map("distance",own,"reach",3),"bot_range",map("distance",other,"reach",reach),"available",available);}
    private static Frame render(ReloadHost host){Frame f=host.render(.5f,800,450);check(f!=null&&!host.status().contains("error"),"module remains healthy");return f;}
    private static Frame liveFrame(ReloadHost host,double own,double other,double reach,boolean available){event(host,"practice_views",live(own,other,reach,available));return render(host);}
    private static int alpha(Frame f,int rgb){int a=0;for(Frame.Rectangle r:f.rectangles)if((r.color&0xFFFFFF)==rgb)a=Math.max(a,r.color>>>24);return a;}
    private static boolean has(Frame f,int rgb){return alpha(f,rgb)>0;}
    private static void configure(ReloadHost host,Path dir,String text)throws IOException{Files.write(dir.resolve("settings.properties"),text.getBytes("UTF-8"));check(host.reload(true),"module configuration loads");}
    private static void command(ReloadHost host,String...args){check(host.command(args),"module handles range command");}
    private static Properties settings(Path dir)throws IOException{Properties p=new Properties();try(InputStream in=Files.newInputStream(dir.resolve("settings.properties"))){p.load(in);}return p;}
    private static Map<String,Object> pose(double x,double y){return map("pos",Arrays.asList(x,y,0.),"render",Arrays.asList(x+1000,y,0.),"eye_height",1.62,"box",Arrays.asList(x-.3,y,-.3,x+.3,y+1.8,.3),"border",.1);}
    // Human is B here deliberately. A-client and B-client distances are different.
    private static Map<String,Object> replay(double time,double humanGap,double botGap){return map("active",true,"match","replay-test","time_ms",time,"duration_ms",20000,"speed",1.,"paused",true,"source","server","reach",Arrays.asList(3.,3.),"availability",map("a",map("role","bot"),"b",map("role","human")),"views",map("a",map("a",pose(0,4),"b",pose(botGap,4)),"b",map("b",pose(0,4),"a",pose(humanGap,4))));}
    private static Frame replayFrame(ReloadHost host,Map<String,Object> value){event(host,"replay_frame",value);return render(host);}
    static void run(Path module)throws Exception{
        Path dir=Files.createTempDirectory("practice-range-check-");Files.copy(module,dir.resolve("practice-module.jar"));
        try(ReloadHost host=new ReloadHost(dir)){
            configure(host,dir,"");
            Frame f=liveFrame(host,3.01,3.01,3,true);check(f.rectangles.isEmpty(),"neither in reach means no tint or brackets");
            f=liveFrame(host,3,Math.nextUp(3.),3,true);check(has(f,BLUE)&&!has(f,RED),"own exact boundary lights without false opponent entry");
            f=liveFrame(host,Math.nextUp(3.),3,3,true);check(!has(f,BLUE)&&has(f,RED),"opponent exact boundary and immediate own exit");
            f=liveFrame(host,2.9,2.9,3,true);check(has(f,BLUE)&&has(f,RED),"both signals visible simultaneously");
            check(f.rectangles.size()<=256,"HUD stays within native renderer limit");
            for(Frame.Rectangle r:f.rectangles)check((r.color&0xFFFFFF)!=RED||r.right<400||r.left>400||r.bottom<225||r.top>225,"default danger brackets keep center clear");
            f=liveFrame(host,2.9,2.9,3,false);check(has(f,BLUE)&&!has(f,RED),"missing bot stream hides only danger");
            f=liveFrame(host,Double.NaN,2.9,3,true);check(!has(f,BLUE)&&has(f,RED),"invalid own distance hides only own signal");
            f=liveFrame(host,2.9,Double.NaN,3,true);check(has(f,BLUE)&&!has(f,RED),"invalid opponent distance cannot be safe-looking zero");
            f=liveFrame(host,3.5,3.5,4,true);check(!has(f,BLUE)&&has(f,RED),"modified bot reach uses its actual cap");
            Thread.sleep(275);check(render(host).rectangles.isEmpty(),"expired live observations hide both signals");
            configure(host,dir,"metrics.enabled=true\n");
            command(host,"range","theirs","off");f=liveFrame(host,2.9,2.9,3,true);check(has(f,BLUE)&&!has(f,RED),"independent off");
            check(settings(dir).getProperty("metrics.enabled").equals("true"),"commands preserve unrelated settings");
            command(host,"range","both","off");check(liveFrame(host,2.9,2.9,3,true).rectangles.isEmpty(),"both disabled");
            command(host,"range","theirs","on");f=liveFrame(host,2.9,2.9,3,true);check(!has(f,BLUE)&&has(f,RED),"on preserves previous style");
            command(host,"range","both","crosshair");f=liveFrame(host,2.9,2.9,3,true);check(f.rectangles.size()==16,"two distinct crosshair bracket sets");
            for(Frame.Rectangle a:f.rectangles)for(Frame.Rectangle b:f.rectangles)if((a.color&0xFFFFFF)!=(b.color&0xFFFFFF))check(a.right<=b.left||a.left>=b.right||a.bottom<=b.top||a.top>=b.bottom,"bracket signals do not overlap");
            command(host,"range","both","edge");f=liveFrame(host,2.9,2.9,3,true);check(has(f,BLUE)&&has(f,RED)&&f.rectangles.size()<=256,"both edge signals fit renderer budget");
            command(host,"range","both","screen");f=liveFrame(host,2.9,2.9,3,true);check(f.rectangles.stream().filter(r->r.left==0&&r.top==0&&r.right==800&&r.bottom==450).count()==2&&f.rectangles.size()==18,"screen tints retain separate brackets when mixed");
            command(host,"range","yours","color","#00FF00");command(host,"range","yours","opacity","25");f=liveFrame(host,2.9,2.9,3,true);check(alpha(f,0x00FF00)==64&&!has(f,BLUE),"custom color and percentage opacity applied");
            check(host.reload(false),"saved commands survive actual module replacement");f=liveFrame(host,2.9,2.9,3,true);check(alpha(f,0x00FF00)==64&&has(f,RED),"settings and both choices persist after reload");
            check(f.lines.stream().anyMatch(l->l.text.contains("Range yours opacity 25")),"command feedback survives automatic reload");
            byte[] saved=Files.readAllBytes(dir.resolve("settings.properties"));
            for(String[] invalid:new String[][]{{"range","yours","opacity","NaN"},{"range","both","opacity","101"},{"range","theirs","color","BAD"},{"range","both","pulse","bad"}}){command(host,invalid);check(Arrays.equals(saved,Files.readAllBytes(dir.resolve("settings.properties"))),"invalid command does not change settings");check(render(host).lines.stream().anyMatch(l->l.color==0xFF8888),"invalid command gives feedback without disabling module");}
            check(!host.command(new String[]{"unrelated"}),"other commands still handled by bridge");
            configure(host,dir,"");
            Map<String,Object> state=replay(1000,3.3,3.6);f=replayFrame(host,state);check(has(f,BLUE)&&!has(f,RED),"replay uses each logical POV and human B orientation");
            state.put("source","a");f=replayFrame(host,state);check(has(f,BLUE)&&!has(f,RED),"camera/source switches do not flip whose signal is whose");
            state.put("reach",Arrays.asList(4.,3.));check(has(replayFrame(host,state),RED),"replay honors bot A's custom reach");
            state.put("views",map("b",map("b",pose(0,4),"a",pose(3.3,4))));f=replayFrame(host,state);check(has(f,BLUE)&&!has(f,RED),"missing replay source only suppresses its signal");
            state.put("views",map("b",map("b",pose(0,4),"a",pose(3.3,7))));check(replayFrame(host,state).rectangles.isEmpty(),"height matters for true 3D reach");
            state=replay(1100,3.6,3.3);state.put("availability",map("a",map("role","human"),"b",map("role","bot")));f=replayFrame(host,state);check(has(f,BLUE)&&!has(f,RED),"normal human A orientation also works");
            configure(host,dir,"range.yours.pulse=true\nrange.yours.opacity=30\nrange.theirs.enabled=false\n");
            int steady=alpha(replayFrame(host,replay(1000,3.3,3.6)),BLUE);check(steady==77,"initial in-range observation does not invent an entry pulse");
            replayFrame(host,replay(1010,3.6,3.6));int pulse=alpha(replayFrame(host,replay(1020,3.3,3.6)),BLUE);check(pulse>steady,"actual outside-to-inside entry pulses");
            check(alpha(replayFrame(host,replay(1020,3.3,3.6)),BLUE)==pulse,"paused replay freezes pulse on replay clock");
            check(alpha(replayFrame(host,replay(1220,3.3,3.6)),BLUE)==steady,"entry pulse settles to steady indication");
            check(!has(replayFrame(host,replay(1230,3.6,3.6)),BLUE),"exit never leaves misleading fading indicator");
            replayFrame(host,replay(1240,3.3,3.6));check(alpha(replayFrame(host,replay(1100,3.3,3.6)),BLUE)==steady,"rewind clears pulse history");
            replayFrame(host,replay(1110,3.6,3.6));check(alpha(replayFrame(host,replay(5000,3.3,3.6)),BLUE)==steady,"seek forward does not fake a range-entry event");
            event(host,"replay_frame",map("active",false));check(liveFrame(host,3.5,3.5,3,true).rectangles.isEmpty(),"leaving replay restores live thresholds");
            liveFrame(host,2.9,2.9,3,true);host.tick(new Snapshot(0,0,1,false,false,false,null,null,Collections.emptyList()));check(render(host).rectangles.isEmpty(),"disconnect removes all indicators");
        }finally{try(java.util.stream.Stream<Path> paths=Files.walk(dir)){paths.sorted(Comparator.reverseOrder()).forEach(p->{try{Files.delete(p);}catch(IOException e){throw new UncheckedIOException(e);}});}}
        System.out.println("Range indicator checks passed: "+checks+" (live/replay, boundaries, missing views, independent controls, persistence, pulse/seek)");
    }
}
