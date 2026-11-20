package sword;

import java.util.*;
import static sword.Fighter.*;

/** Flat-arena rollout model. Client observations are delayed per seat.
 * Movement, contact and transport are approximations checked against the native runner. */
final class Model {
    static final int LIMIT=600;
    // Optional timing model fitted to independent development client recordings.
    static final int EXTRA_DISPLAY="measured".equals(System.getProperty("sword.transport"))?1:0;
    static final int EXTRA_IMPULSE=EXTRA_DISPLAY;
    static final class Body {
        double x,y,z,vx,vy,vz,yaw,hp=20,lastDamage;
        int age=20,immune,hurt,jumpDelay,miss;
        boolean ground=true,sprint,serverSprint;
        Keys keys=Keys.STILL;
        final double[][] history=new double[12][5];
        final double[][] pendingVelocity=new double[12][4];
        final boolean[] contacts=new boolean[12];
        final int[] chargeAt=new int[12];
        final boolean[] runningAt=new boolean[12],fallingAt=new boolean[12];
        int lastFresh=-1,firstHit=-1, hits, clicks, airHits, jumps;
        void snapshot(int t){var row=history[t%12];row[0]=x;row[1]=y;row[2]=z;row[3]=hurt;row[4]=hp;}
    }
    interface Controller {
        Keys act(View v);
        default void prime(View v,Keys applied){act(v);}
        default void reward(double r){}
    }
    interface Observer {void step(int tick,Body a,Body b,Keys left,Keys right,boolean aFirst);}
    record Frame(Keys a,Keys b){}
    record Start(Scene scene,List<Frame> prefix){}
    record Score(double a,double b,int ticks,int hitsA,int hitsB,int clicksA,int clicksB,int jumpsA,int jumpsB,int airHitsA,int airHitsB){}
    record Scene(long seed,int delayA,int delayB){}
    static View view(Body a,Body b,int tick,int lag,int down){
        var p=b.history[Math.floorMod(tick-lag,12)];var q=b.history[Math.floorMod(tick-lag-1,12)];
        var own=a.history[Math.floorMod(tick-down,12)];
        var observedTarget=b.history[Math.floorMod(tick-down,12)];
        return new View(a.x,a.y,a.z,a.vx,a.vy,a.vz,p[0],p[1],p[2],p[0]-q[0],p[1]-q[1],p[2]-q[2],a.ground,a.sprint,
            Math.min(1,a.age/12.5),own[4],(int)own[3],(int)observedTarget[3],a.yaw);
    }
    static void input(Body a,View v,Keys k,int tick,int up){
        a.keys=k;a.yaw=steer(v,k);
        double d=Math.hypot(v.tx()-a.x,v.tz()-a.z);
        double dx=v.tx()-a.x,dz=v.tz()-a.z;
        // Ray from the eye to the nearest height inside the displayed box.
        double dy=clip(a.y+1.62,v.ty()+.1,v.ty()+1.7)-(a.y+1.62);
        double yaw=Math.toRadians(a.yaw), pitch=Math.atan2(dy,d);
        boolean ray=rayBox(dx,v.ty()-a.y-1.62,dz,-Math.sin(yaw)*Math.cos(pitch),Math.sin(pitch),Math.cos(yaw)*Math.cos(pitch));
        if(k.click() && a.miss==0){
            a.clicks++;
            if(ray){
                int i=(tick+up)%12;a.contacts[i]=true;a.chargeAt[i]=a.age;
                a.runningAt[i]=a.serverSprint;a.fallingAt[i]=a.vy<0&&!a.ground;
            }
            a.age=0;
        }
    }
    static boolean rayBox(double x,double y,double z,double vx,double vy,double vz){
        double near=0,far=3;
        for(int axis=0;axis<3;axis++){
            double lo=axis==0?x-.3:axis==1?y:z-.3;
            double hi=lo+(axis==1?1.8:.6),v=axis==0?vx:axis==1?vy:vz;
            if(Math.abs(v)<1e-10){if(lo>0||hi<0)return false;continue;}
            double a=lo/v,b=hi/v;near=Math.max(near,Math.min(a,b));far=Math.min(far,Math.max(a,b));
        }
        return near<=far;
    }
    static void hit(Body a,Body b,int tick,int down){
        int i=tick%12;if(!a.contacts[i])return;a.contacts[i]=false;
        if(Math.hypot(a.x-b.x,a.z-b.z)>5)return;
        double charge=Math.min(1,(a.chargeAt[i]+.5)/12.5);
        boolean strong=charge>.9, boosted=strong&&a.runningAt[i], critical=strong&&!a.runningAt[i]&&a.fallingAt[i];
        double raw=7*(.2+.8*charge*charge)*(critical?1.5:1);
        double damage=raw;
        if(b.immune>10){damage-=b.lastDamage;if(damage<=0)return;}
        else {b.immune=20;b.hurt=10;}
        boolean fresh=damage==raw;
        b.lastDamage=raw;
        // Full diamond armor, eight toughness, no enchantments.
        b.hp=Math.max(0,b.hp-damage*(1-clip(20-damage/4,4,20)/25));a.hits++;
        if(fresh)a.lastFresh=tick;if(a.firstHit<0)a.firstHit=tick;if(critical)a.airHits++;
        double dx=b.x-a.x,dz=b.z-a.z,d=Math.max(.001,Math.hypot(dx,dz));
        double vx=b.vx,vz=b.vz,vy=b.vy;
        if(fresh){vx=vx*.5+.4*dx/d;vz=vz*.5+.4*dz/d;vy=b.ground?Math.min(.4,vy*.5+.4):vy;}
        if(boosted){vx=vx*.5-.5*Math.sin(Math.toRadians(a.yaw));vz=vz*.5+.5*Math.cos(Math.toRadians(a.yaw));
            if(b.ground)vy=Math.min(.4,vy*.5+.5);a.serverSprint=false;a.vx*=.6;a.vz*=.6;}
        if(fresh||boosted){var slot=b.pendingVelocity[(tick+down+EXTRA_IMPULSE)%12];slot[0]=1;slot[1]=vx;slot[2]=vy;slot[3]=vz;}
    }
    static void move(Body a,int tick){
        var push=a.pendingVelocity[tick%12];if(push[0]!=0){a.vx=push[1];a.vy=push[2];a.vz=push[3];push[0]=0;}
        Keys k=a.keys;boolean run=k.forward()>0&&(k.sprint()||a.sprint);
        if(run!=a.sprint)a.serverSprint=run;a.sprint=run;
        double yaw=Math.toRadians(a.yaw),sin=Math.sin(yaw),cos=Math.cos(yaw);
        if(a.ground&&k.jump()&&a.jumpDelay==0){
            a.jumps++;a.vy=.42;a.jumpDelay=10;if(run){a.vx-=.2*sin;a.vz+=.2*cos;}
        }
        if(!k.jump())a.jumpDelay=0;
        double speed=(a.ground?(run?.13:.1):(run?.026:.02))*.98/Math.max(1,Math.hypot(k.forward(),k.side()));
        a.vx+=(-sin*k.forward()+cos*k.side())*speed;a.vz+=(cos*k.forward()+sin*k.side())*speed;
        double drag=a.ground?.546:.91;
        a.x=clip(a.x+a.vx,-11.7,11.7);a.z=clip(a.z+a.vz,-11.7,11.7);
        if(Math.abs(a.x)>=11.7)a.vx=0;if(Math.abs(a.z)>=11.7)a.vz=0;
        a.y+=a.vy;a.ground=a.y<=0;if(a.ground){a.y=0;a.vy=0;}
        a.vx*=drag;a.vz*=drag;a.vy=(a.vy-.08)*.98;a.age++;
        a.immune=Math.max(0,a.immune-1);a.hurt=Math.max(0,a.hurt-1);
        a.jumpDelay=Math.max(0,a.jumpDelay-1);a.miss=Math.max(0,a.miss-1);
    }
    static Score duel(Controller left,Controller right,Scene scene){
        return duel(left,right,new Start(scene,List.of()),null);
    }
    static Score duel(Controller left,Controller right,Start start,Observer observer){
        if("lockstep".equals(System.getProperty("sword.transport")))return FastDuel.duel(left,right,start,observer);
        Scene scene=start.scene;
        var rng=new SplittableRandom(scene.seed);Body a=new Body(),b=new Body();
        double gap=rng.nextDouble(3.5,8),angle=rng.nextDouble(2*Math.PI);
        a.x=-Math.cos(angle)*gap/2;a.z=-Math.sin(angle)*gap/2;b.x=-a.x;b.z=-a.z;
        a.yaw=Math.toDegrees(angle)-90;b.yaw=a.yaw+180;
        for(int i=0;i<12;i++){a.snapshot(i);b.snapshot(i);}
        int t=0;for(;t<LIMIT&&a.hp>0&&b.hp>0;t++){
            var va=view(a,b,t,scene.delayB+scene.delayA+1+EXTRA_DISPLAY,scene.delayA);var vb=view(b,a,t,scene.delayA+scene.delayB+1+EXTRA_DISPLAY,scene.delayB);
            double prevA=a.hp,prevB=b.hp;
            boolean prefix=t<start.prefix.size();
            Keys ka,kb;
            if(prefix){
                var frame=start.prefix.get(t);ka=frame.a;kb=frame.b;left.prime(va,ka);right.prime(vb,kb);
            }else{ka=left.act(va);kb=right.act(vb);}
            input(a,va,ka,t,scene.delayA);input(b,vb,kb,t,scene.delayB);
            boolean aFirst=rng.nextBoolean();
            if(aFirst){hit(a,b,t,scene.delayB);if(b.hp>0)hit(b,a,t,scene.delayA);}
            else{hit(b,a,t,scene.delayA);if(a.hp>0)hit(a,b,t,scene.delayB);}
            double score=((a.hp-prevA)-(b.hp-prevB))/20+(b.hp==0?1:a.hp==0?-1:0);
            if(!prefix){left.reward(score);right.reward(-score);}
            if(observer!=null)observer.step(t,a,b,ka,kb,aFirst);
            move(a,t);move(b,t);a.snapshot(t+1);b.snapshot(t+1);
        }
        return new Score(a.hp,b.hp,t,a.hits,b.hits,a.clicks,b.clicks,a.jumps,b.jumps,a.airHits,b.airHits);
    }
}
