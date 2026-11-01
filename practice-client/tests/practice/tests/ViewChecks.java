package practice.tests;
import practice.math.*;
import practice.transport.LocalViewStream;
import practice.api.*;
import practice.core.*;
import java.nio.file.*;
import java.util.*;

final class ViewChecks {
    private static void check(boolean ok,String text){if(!ok)throw new AssertionError(text);}
    private static Map<?,?> last(OpeningWindow w){return (Map<?,?>)w.state().get("last");}
    private static double n(Map<?,?> m,String k){return ((Number)m.get(k)).doubleValue();}
    static void run(Path directory)throws Exception{
        double[] box={3.4,0,-.3,4,1.8,.3};check(Math.abs(ReachMath.distance(new double[]{0,1.62,0},ReachMath.expand(box,.1))-3.3)<1e-9,"reach uses nearest box surface and collision border");
        check(ReachMath.distance(new double[]{0,4,0},box)>3.4,"height changes reach, not just horizontal gap");
        OpeningWindow w=new OpeningWindow(1000);
        // Deliberately deliver the complete human sequence before the bot sequence.
        w.add(0,1000,-1);w.add(0,1010,.1);w.add(0,1100,.2);
        w.add(1,1000,-1);w.add(1,1030,-.1);w.add(1,1040,.1);w.add(1,1100,.1);
        check(n(last(w),"low_a_ms")==20&&n(last(w),"high_a_ms")==40,"timestamp brackets independent of packet arrival order; positive human lead");
        w.add(0,1200,.2);w.add(1,1200,.2);check(((List<?>)w.state().get("history")).size()==1,"continuous close combat is one approach");
        w.add(0,1300,-1);w.add(1,1300,-1);w.add(0,1400,-1);w.add(1,1400,-1);w.add(0,1500,-1);w.add(1,1500,-1);
        w.add(1,1510,.1);w.add(0,1520,-.1);w.add(0,1530,.1);w.add(1,1540,.1);w.add(0,1540,.1);
        check(n(last(w),"high_a_ms")<0&&n(last(w),"index")==2,"rearm after separation and negative bot-first lead");
        OpeningWindow missing=new OpeningWindow(0);missing.add(0,0,-1);missing.add(1,0,-1);missing.add(0,500,.1);missing.add(1,510,.1);missing.add(0,520,.1);
        check(last(missing).isEmpty(),"missing observations cannot manufacture precise crossing");
        OpeningWindow partial=new OpeningWindow(0);partial.add(0,0,-1);partial.add(1,0,-1);partial.add(0,10,.1);partial.add(1,20,-1);
        for(int t=30;t<=330;t+=50){partial.add(0,t,-1);partial.add(1,t,-1);}
        partial.add(1,340,.1);partial.add(0,350,.1);partial.add(1,360,.1);partial.add(0,360,.1);
        check(((List<?>)last(partial).get("a_window")).get(0).equals(330L),"abandoned one-sided opening does not contaminate next approach");
        try(LocalViewStream.Publisher pub=new LocalViewStream.Publisher();LocalViewStream.Reader reader=new LocalViewStream.Reader(pub.port(),pub.token)){
            long deadline=System.nanoTime()+3000000000L;while(!reader.status().equals("connected")&&System.nanoTime()<deadline)Thread.sleep(1);
            check(reader.status().equals("connected"),"loopback authenticated stream connects");double[] values=new double[36];values[18]=42;
            long ns=System.nanoTime();pub.publish(new LocalViewStream.Sample(1,ns,1000,ns,values));values[18]=0;
            while(reader.latest()==null&&System.nanoTime()<deadline)Thread.sleep(1);
            LocalViewStream.Sample s=reader.latest();check(s!=null&&s.value(18)==42&&s.sequence==1,"immutable complete frame transported");
            check(s.rttNanos>=0&&Math.abs(s.ageMs(System.nanoTime()))<1000,"calibrated monotonic origins");
            check(reader.drain().size()==1&&reader.drain().isEmpty(),"opening receives each sample once");
            System.out.printf(Locale.ROOT,"Local stream check: delivery %.3f ms, clock RTT %.3f ms%n",s.deliveryMs(),s.rttNanos/1e6);
        }
        // Drawing settings are independent and the module contains real world geometry.
        Files.write(directory.resolve("settings.properties"),"ghost.bot.enabled=true\nghost.me.enabled=false\nreach.enabled=false\ndebug.enabled=true\n".getBytes("UTF-8"));
        Map<String,Object> entity=JsonData.map("pos",Arrays.asList(0.,4.,0.),"render",Arrays.asList(.25,3.9,0.),"eye_height",1.62,"yaw",0,"box",Arrays.asList(-.3,4.,-.3,.3,5.8,.3),"border",.1);
        Map<String,Object> data=JsonData.map("available",true,"own",entity,"target",entity,"bot_self",entity,"bot_sees_you",entity,"bot_range",JsonData.map("reach",3),"opening",w.state());
        try(ReloadHost host=new ReloadHost(directory)){
            check(host.reload(true),"view module loads");host.event(new PracticeEvent("practice_views",1,1,1,data));
            WorldFrame scene=host.world(0);check(scene.boxes.size()==6&&scene.lines.isEmpty()&&scene.spheres.isEmpty(),"one independent ghost, no reach overlay");
            Frame hud=host.render(0,800,600);check(hud.lines.stream().anyMatch(l->l.centered&&l.scale==2&&l.text.startsWith("Opening")),"prominent opening HUD");
            check(hud.lines.stream().anyMatch(l->l.text.startsWith("Sample -> receive")),"optional debug delivery HUD");
            Files.write(directory.resolve("settings.properties"),"ghost.me.enabled=true\nreach.enabled=true\n".getBytes("UTF-8"));host.reload(true);host.event(new PracticeEvent("practice_views",2,1,1,data));
            scene=host.world(0);check(scene.boxes.size()==6&&scene.lines.isEmpty()&&scene.spheres.size()==2,"other ghost plus both filled reach fields, with no outlines or rays");
            check(Math.abs(scene.spheres.get(0).x-.25)<1e-9&&Math.abs(scene.spheres.get(0).y-5.52)<1e-9&&scene.spheres.get(0).radius==3,"reach field follows render interpolation at eye height");
            check(scene.spheres.get(1).x==.25,"bot field uses its own client's interpolated position");
            entity.put("render",Arrays.asList(.75,3.95,0.));host.event(new PracticeEvent("practice_views",3,1,1,data));
            check(host.world(0).spheres.get(0).x==.75&&((Number)((List<?>)entity.get("pos")).get(0)).doubleValue()==0,"between-tick rendering moves without modifying logical state");
            entity.remove("render");host.event(new PracticeEvent("practice_views",4,1,1,data));
            check(host.world(0).spheres.get(0).x==0,"older pose archives fall back to logical position when render pose is absent");
            entity.put("render",Arrays.asList(.75,3.95,0.));
            check(host.render(0,800,600).lines.stream().noneMatch(l->l.text.contains("Sample ->")),"normal HUD has no delay label");
            data.put("available",false);host.event(new PracticeEvent("practice_views",5,1,1,data));check(host.world(0).boxes.isEmpty()&&host.world(0).spheres.size()==1,"stale bot views hide both ghosts and bot field");
        }
        System.out.println("View checks passed: reach, opening intervals, stream, overlays, stale-data handling");
    }
}
