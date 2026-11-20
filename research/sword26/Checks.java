package sword;

import java.nio.file.*;
import java.util.*;
import static sword.Fighter.*;

/** Regression checks for observation timing, native input mapping and deterministic branching. */
public final class Checks {
    static void check(boolean ok,String msg){if(!ok)throw new AssertionError(msg);}
    static void airborneSprint(boolean reverse) {
        var seat=new FastDuel.Seat(0,0,10);
        seat.start(0,110,0,0,0,110,5);
        seat.local.ground=false;
        var forward=new Keys(1,0,false,false,false,0);
        seat.input(0,forward,0,0);
        check(!seat.sprint,"First airborne tap should arm sprint");
        seat.input(1,new Keys(reverse?-1:0,0,false,false,false,0),0,0);
        seat.input(2,forward,0,0);
        check(seat.sprint!=reverse,"A backward press must clear the airborne double-tap window");
    }
    static void smoothTurns(){
        var aim=new SmoothAim();
        float yaw=179,pitch=0;
        // A target just across the signed-angle seam should turn two degrees to the right.
        double bearing=Math.toRadians(-179);
        var still=Keys.STILL;
        for(int tick=0;tick<75;tick++){
            var view=new View(0,0,0,0,0,0,-Math.sin(bearing),0,Math.cos(bearing),0,0,0,true,false,1,20,0,0,yaw);
            aim.plan(view,still,pitch,0);
            check(aim.yawAt(0)==yaw && aim.pitchAt(0)==pitch,"A click must use the starting pose of the render curve");
            if(tick==0)check(aim.yawAt(1)>179 && aim.yawAt(1)<181,"Mouse took the long way across the signed seam");
            if(tick==35)bearing=0;
            for(int frame=1;frame<=3;frame++){
                float from=(frame-1)/3f,to=frame/3f;
                check(Math.abs(aim.yawAt(to)-aim.yawAt(from))<=8.001,"Mouse exceeded its per-frame speed limit");
            }
            yaw=aim.yawAt(1);pitch=aim.pitchAt(1);
        }
        check(Math.abs(angle(yaw))<.005,"Mouse failed to settle on a stationary target");
    }
    static void checkPerfectAim(){
        Random random=new Random(28841);
        int tested=0;
        for(int i=0;i<32000;i++){
            double x=random.nextDouble(-3.4,3.4),z=random.nextDouble(-3.4,3.4),bottom=random.nextDouble(-3.1,2.5);
            double dx=Math.max(0,Math.abs(x)-.3f),dz=Math.max(0,Math.abs(z)-.3f);
            double dy=Math.max(0,Math.max(bottom,-bottom-1.8f));
            if(dx*dx+dy*dy+dz*dz>=9)continue;
            var lock=PerfectAim.lock(x,bottom,z,77);
            double yaw=FlatPhysics.angle(lock.yaw()),pitch=FlatPhysics.angle(lock.pitch());
            check(Model.rayBox(x,bottom,z,FlatPhysics.sin(-yaw)*FlatPhysics.cos(pitch),-FlatPhysics.sin(pitch),
                FlatPhysics.cos(-yaw)*FlatPhysics.cos(pitch)),"Reachable target missed at "+x+", "+bottom+", "+z);
            tested++;
        }
        check(tested>15000,"Missing boundary samples");
        check(PerfectAim.lock(0,-1.62f,0,77).yaw()==77,"Coincident horizontal positions must preserve a stable yaw");
    }
    public static void main(String[] args)throws Exception{
        checkPerfectAim();
        smoothTurns();
        airborneSprint(false);airborneSprint(true);
        var stamps=new ArrayList<Long>();
        for(int i=0;i<=20;i++)stamps.add(121L+100*(i/2));
        var burst=PollClock.Cadence.from(100,stamps);
        check(burst.time(0)==21 && burst.time(1)==21 && burst.time(2)==121 && burst.time(21)==1021,
            "A measured double input poll or its phase was lost across the repeated window");
        var resultLatch=new RoundOutcome();
        resultLatch.contact(false,7.1899996f,0);
        resultLatch.contact(true,6.612501f,0);
        check(resultLatch.healthA(20)==0 && resultLatch.healthB(0)==7.1899996f,"A respawn or later kill reversed the first knockout");
        resultLatch.reset();
        check(!resultLatch.finished() && resultLatch.healthA(20)==20,"The next round inherited a knockout");
        check(Model.rayBox(0,-1.62,3.29,0,0,1),"Reach at near face");
        check(!Model.rayBox(0,-1.62,3.32,0,0,1),"Reach past far boundary");
        check(!Model.rayBox(2,-1.62,2,0,0,1),"Ray direction");
        var body=new Model.Body();var other=new Model.Body();body.snapshot(0);other.snapshot(0);
        body.hp=17;body.hurt=10;body.snapshot(1);other.snapshot(1);
        check(Model.view(body,other,1,1,1).health()==20,"Incoming damage arrived early");
        check(Model.view(body,other,1,1,0).health()==17,"Incoming damage lost");
        var scene=new Model.Scene(62932,1,0);var original=new ArrayList<Model.Frame>();
        double[] w=Fighter.read(Path.of(args[0]));
        Model.Score score=Model.duel(new Fighter(w),Controls.get("wtap"),new Model.Start(scene,List.of()),
            (t,a,b,x,y,first)->original.add(new Model.Frame(x,y)));
        for(int fork:new int[]{0,1,5,original.size()/2,original.size()-1}){
            var replayed=new ArrayList<Model.Frame>();
            var result=Model.duel(new Fighter(w),Controls.get("wtap"),new Model.Start(scene,original.subList(0,fork)),
                (t,a,b,x,y,first)->replayed.add(new Model.Frame(x,y)));
            check(result.equals(score)&&original.equals(replayed),"Branch diverged at "+fork);
        }
        var v=new View(0,0,0,0,0,0,0,0,4,0,0,0,true,false,1,20,0,0,0);
        check(steer(v,new Keys(1,0,true,false,false,180))==45,"Heading rate limit");
        System.out.println("Reach, delayed observations, five full-state continuations and turn rate passed.");
    }
}
