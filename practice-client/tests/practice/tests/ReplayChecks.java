package practice.tests;
import practice.replay.*;
import practice.core.*;
import practice.api.*;
import java.util.*;
import java.nio.file.*;
import static practice.core.JsonData.map;

final class ReplayChecks {
    static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    static Map<String,Object> pose(double x,double render,double yaw){return map("pos",Arrays.asList(x,4.,0.),"render",Arrays.asList(render,4.,0.),"yaw",yaw,"eye_height",1.62);}
    static Map<String,Object> hitPose(double x,double y){return map("pos",Arrays.asList(x,y,0.),"eye_height",1.62,"box",Arrays.asList(x-.3,y,-.3,x+.3,y+1.8,.3),"border",.1);}
    static void run(Path module)throws Exception{
        Map<String,Object> u=hitPose(0,4),v=hitPose(3.4,4);
        Map<String,Object> equal=practice.math.ReachAdvantage.compare(u,v,v,u);
        check(Math.abs(PoseTrack.number(equal,"advantage",99))<1e-9,"settled equal-height symmetric views have zero advantage");
        check(Math.abs(PoseTrack.number(equal,"d2_yours",0)-3)<1e-9,"expanded target box puts ideal-aim boundary at 3.0, not center distance");
        Map<String,Object> lead=practice.math.ReachAdvantage.compare(u,hitPose(3.3,4),v,hitPose(-.1,4));
        check(Math.abs(PoseTrack.number(lead,"advantage",0)-.2)<1e-9&&Boolean.TRUE.equals(lead.get("yours_in_reach"))&&!Boolean.TRUE.equals(lead.get("theirs_in_reach")),"positive advantage means only we can reach at 2.9 versus 3.1");
        check(practice.math.ReachAdvantage.distance(u,hitPose(3.4,7))>3,"height enters the 3D nearest hitbox calculation");
        check(practice.math.ReachAdvantage.compare(u,v,Collections.emptyMap(),u).isEmpty(),"missing opponent view cannot manufacture a zero or advantage");
        PoseTrack track=new PoseTrack("b",250);
        track.add(100,map("self",pose(10,9,170),"opponent",pose(2,1,-170)),1);
        track.add(200,map("self",pose(12,11,-170),"opponent",pose(4,3,170)),1);track.sort();
        Map<String,Object> mid=track.at(150),a=PoseTrack.object(mid.get("a")),b=PoseTrack.object(mid.get("b"));
        check(PoseTrack.number(a,"posX",0)==2&&PoseTrack.number(b,"posX",0)==10,"side B must orient its self/opponent into global A/B");
        check(PoseTrack.number(a,"logicalX",0)==2,"interpolation preserves logical vs rendered positions");
        check(Math.abs(PoseTrack.number(b,"rotationYaw",0))==180,"yaw follows short arc across wrap");
        check(track.at(99).isEmpty()&&track.at(451).isEmpty(),"missing samples do not silently become another viewpoint");
        check(PoseTrack.number(PoseTrack.object(track.at(100).get("b")),"posX",0)==9,"scrubbing backward leaves raw samples unchanged");
        Path dir=Files.createTempDirectory("practice-replay-check-");Files.copy(module,dir.resolve("practice-module.jar"));Files.write(dir.resolve("settings.properties"),"reach.enabled=true\n".getBytes("UTF-8"));
        try(ReloadHost host=new ReloadHost(dir)){host.reload(true);
            Map<String,Object> pair=map("a",pose(0,0,0),"b",pose(3,3,180));
            host.event(new PracticeEvent("replay_frame",1,1000,0,map("active",true,"source","server","views",map("a",pair,"b",pair),"ghosts",Arrays.asList("a:a","a:b","b:a","b:b"),"reach",Arrays.asList(3.,3.))));
            WorldFrame world=host.world(0);check(world.spheres.size()==2&&world.lines.isEmpty()&&world.boxes.size()==24,"replay uses same filled fields and independent four ghosts");
            Map<String,Object> offline=map("active",true,"offline",true,"offline_reach",true,"source","a","views",map("a",pair));
            host.event(new PracticeEvent("replay_frame",2,1000,0,offline));
            check(host.world(0).spheres.size()==1,"offline replay shows configured reach fields");
            offline.put("offline_reach",false);
            host.event(new PracticeEvent("replay_frame",3,1000,0,offline));
            check(host.world(0).spheres.isEmpty(),"camera switch can hide reach fields while globally enabled");
            Files.write(dir.resolve("settings.properties"),"reach.enabled=false\n".getBytes("UTF-8"));
            check(host.reload(true),"reach off settings reload");
            offline.put("offline_reach",true);
            host.event(new PracticeEvent("replay_frame",4,1000,0,offline));
            check(host.world(0).spheres.isEmpty(),"global reach off hides fields even with the camera switch enabled");
            host.event(new PracticeEvent("replay_frame",2,1000,0,map("active",false)));check(host.world(0).spheres.isEmpty(),"leaving replay removes replay-only geometry");
        }
        System.out.println("Replay checks passed: time interpolation, side orientation, gaps, shared overlay rendering");
    }
}
