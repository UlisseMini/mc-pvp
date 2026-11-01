package practice.tests;
import practice.api.*;
import practice.core.*;
import java.nio.file.*;
import java.util.*;
public final class RemoteChecks {
    static int checks;
    static void check(boolean x,String why){checks++;if(!x)throw new AssertionError(why);}
    public static void run(Path jar)throws Exception{
        AntiAfk a=new AntiAfk();Object c=new Object();boolean unchanged=true;for(int n=0;n<1000;n++)unchanged&=a.tick(n*1_000_000_000L,c,true,false,false)==0;check(unchanged,"1000 defaultoff ticks never press movement");
        a.enable(0,c,60);check(a.tick(59_999_999_999L,c,true,false,false)==0,"60s default interval");
        for(int i=0;i<6;i++)check(a.tick(60_000_000_000L+i*50_000_000L,c,true,false,false)==(i<3?1:-1),"3 forward then3 back ticks");
        check(a.tick(60_300_000_000L,c,true,false,false)==0,"pulse releases");
        a.enable(0,c,10);check(a.tick(10_000_000_000L,c,true,false,false)==1,"explicit interval");
        check(a.tick(10_050_000_000L,c,true,true,false)==0,"GUI aborts midpulse");check(a.tick(10_100_000_000L,c,true,false,false)==0,"GUI restarts wait");
        a.enable(0,c,10);check(a.tick(10_000_000_000L,c,true,false,false)==1,"second pulse");check(a.tick(10_050_000_000L,c,true,false,true)==0,"physical movement aborts");
        check(a.tick(10_100_000_000L,new Object(),true,false,false)==0&&!a.enabled(),"server change disables");
        a.enable(0,c,10);check(a.tick(0,null,false,false,false)==0&&!a.enabled(),"disconnect disables");
        check(!new AntiAfk().enabled(),"restart never restores activation");
        for(int v:new int[]{0,9,3601,Integer.MAX_VALUE}){boolean bad=false;try{a.enable(0,c,v);}catch(IllegalArgumentException ok){bad=true;}check(bad,"invalid interval rejected");}
        AntiAfk.Ownership keys=new AntiAfk.Ownership();check(keys.apply(0,false,false)[0]==0,"off owns no keys");check(keys.apply(1,false,false)[0]==1,"only forward pressed");check(keys.apply(0,true,false)[1]==0,"human-held W never cleared");
        check(keys.apply(-1,false,false)[0]==2,"back pressed");check(keys.apply(0,false,false)[1]==2,"owned back released on abort");check(keys.apply(0,false,false)[1]==0,"no unowned release");
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
