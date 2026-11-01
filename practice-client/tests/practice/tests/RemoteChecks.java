package practice.tests;
import practice.api.*;
import practice.core.*;
import java.nio.file.*;
import java.util.*;
public final class RemoteChecks {
    static int checks;
    static void check(boolean x,String why){checks++;if(!x)throw new AssertionError(why);}
    public static void run(Path jar)throws Exception{
        AntiAfkChecks.run();
        Path dir=Files.createTempDirectory("practice-remote-check-");Files.copy(jar,dir.resolve("practice-module.jar"));Files.write(dir.resolve("settings.properties"),("metrics.enabled=true\nopening.enabled=true\nadvantage.enabled=true\nreach.enabled=true\nghost.bot.enabled=true\nghost.me.enabled=true\ndebug.enabled=true\nfeedback.hurtFlash=true\nrange.yours.enabled=true\nrange.theirs.enabled=true\n").getBytes("UTF-8"));
        try(ReloadHost host=new ReloadHost(dir)){check(host.reload(true),"module load");Snapshot.Player p=new Snapshot.Player(1,0,4,0,.2,0,0,5.62,20,10,true,false,true);
            host.tick(new Snapshot(100,100,1,true,false,false,p,p,Arrays.asList("Speed II"),true));
            host.event(new PracticeEvent("connection_scope",1,100,100,JsonData.map("practice_allowed",false)));
            Frame f=host.render(0,800,600);check(f.lines.size()==2&&f.lines.get(0).right&&f.lines.get(1).text.contains("Speed"),"server switch immediately keeps only sprint and potion lines");check(f.rectangles.isEmpty()&&f.edgeColor==0,"no remote shapes/flash");
            host.tick(new Snapshot(200,200,2,true,false,false,p,p,Arrays.asList("Speed II"),false));f=host.render(0,800,600);check(f.lines.size()==2,"remote snapshot suppresses metrics and practice overlays");WorldFrame w=host.world(0);check(w.lines.isEmpty()&&w.boxes.isEmpty()&&w.spheres.isEmpty(),"no remote world fields/ghosts");
        }
        System.out.println("RemoteChecks passed: "+checks+" checks");
    }
}
