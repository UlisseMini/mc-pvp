package sword;

import java.util.*;
import static sword.Fighter.*;

/** Flat-arena rollout model. Client observations are delayed per seat.
 * Movement, contact and transport are approximations checked against the native runner. */
final class Model {
    static final int LIMIT=600;
    static final class Body {
        double x,y,z,vx,vy,vz,yaw,hp=20,lastDamage;
        int age=20,immune,hurt,jumpDelay,miss;
        boolean ground=true,sprint,serverSprint;
        Keys keys=Keys.STILL;
        final double[][] history=new double[12][4];
        final double[][] pendingVelocity=new double[12][4];
        final boolean[] contacts=new boolean[12];
        final int[] chargeAt=new int[12];
        final boolean[] runningAt=new boolean[12],fallingAt=new boolean[12];
        int firstHit=-1, hits, clicks, airHits, jumps;
        void snapshot(int t){var row=history[t%12];row[0]=x;row[1]=y;row[2]=z;row[3]=hurt;}
    }
    interface Controller {Keys act(View v);}
    record Score(double a,double b,int ticks,int hitsA,int hitsB,int clicksA,int clicksB,int jumpsA,int jumpsB,int airHitsA,int airHitsB){}
    record Scene(long seed,int delayA,int delayB){}
    static View view(Body a,Body b,int tick,int lag){
        var p=b.history[Math.floorMod(tick-lag,12)];var q=b.history[Math.floorMod(tick-lag-1,12)];
        return new View(a.x,a.y,a.z,a.vx,a.vy,a.vz,p[0],p[1],p[2],p[0]-q[0],p[1]-q[1],p[2]-q[2],a.ground,a.sprint,
            Math.min(1,a.age/12.5),a.hp,a.hurt,(int)p[3],a.yaw);
    }
    static void input(Body a,View v,Keys k,int tick,int up){
        a.keys=k;a.yaw=steer(v,k);
        double dx=v.tx()-a.x,dz=v.tz()-a.z,d=Math.hypot(dx,dz);
        // Ray from the eye to the nearest height inside the displayed box.
        double dy=clip(a.y+1.62,v.ty()+.1,v.ty()+1.7)-(a.y+1.62);
        double factor=d>1e-9?Math.max(0,1-.3/Math.max(Math.abs(dx),Math.abs(dz))):0;
        boolean ray=Math.hypot(d*factor,dy*factor)<=3 && (d<.30 || Math.abs(angle(targetYaw(v)-a.yaw))<=Math.toDegrees(Math.asin(Math.min(1,.30/d))));
        if(k.click() && a.miss==0){
            a.clicks++;
            if(ray){
                int i=(tick+up)%12;a.contacts[i]=true;a.chargeAt[i]=a.age;
                a.runningAt[i]=a.serverSprint;a.fallingAt[i]=a.vy<0&&!a.ground;
            }else a.miss=10;
            a.age=0;
        }
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
        if(a.firstHit<0)a.firstHit=tick;if(critical)a.airHits++;
        double dx=b.x-a.x,dz=b.z-a.z,d=Math.max(.001,Math.hypot(dx,dz));
        double vx=b.vx,vz=b.vz,vy=b.vy;
        if(fresh){vx=vx*.5+.4*dx/d;vz=vz*.5+.4*dz/d;vy=b.ground?Math.min(.4,vy*.5+.4):vy;}
        if(boosted){vx=vx*.5-.5*Math.sin(Math.toRadians(a.yaw));vz=vz*.5+.5*Math.cos(Math.toRadians(a.yaw));
            if(b.ground)vy=Math.min(.4,vy*.5+.5);a.serverSprint=false;a.vx*=.6;a.vz*=.6;}
        if(fresh||boosted){var slot=b.pendingVelocity[(tick+down)%12];slot[0]=1;slot[1]=vx;slot[2]=vy;slot[3]=vz;}
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
        var rng=new SplittableRandom(scene.seed);Body a=new Body(),b=new Body();
        double gap=rng.nextDouble(3.5,8),angle=rng.nextDouble(2*Math.PI);
        a.x=-Math.cos(angle)*gap/2;a.z=-Math.sin(angle)*gap/2;b.x=-a.x;b.z=-a.z;
        a.yaw=Math.toDegrees(angle)-90;b.yaw=a.yaw+180;
        for(int i=0;i<12;i++){a.snapshot(i);b.snapshot(i);}
        int t=0;for(;t<LIMIT&&a.hp>0&&b.hp>0;t++){
            var va=view(a,b,t,scene.delayB+scene.delayA+1);var vb=view(b,a,t,scene.delayA+scene.delayB+1);
            input(a,va,left.act(va),t,scene.delayA);input(b,vb,right.act(vb),t,scene.delayB);
            if(rng.nextBoolean()){hit(a,b,t,scene.delayB);if(b.hp>0)hit(b,a,t,scene.delayA);}
            else{hit(b,a,t,scene.delayA);if(a.hp>0)hit(a,b,t,scene.delayB);}
            move(a,t);move(b,t);a.snapshot(t+1);b.snapshot(t+1);
        }
        return new Score(a.hp,b.hp,t,a.hits,b.hits,a.clicks,b.clicks,a.jumps,b.jumps,a.airHits,b.airHits);
    }
}
