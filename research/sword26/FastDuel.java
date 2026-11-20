package sword;

import java.util.*;
import static sword.Fighter.*;
import static sword.FlatPhysics.*;

/** A flat sword duel with two local predictors and two authoritative server players.
 * Packets from the first seat are handled before the second under the native clock.
 * One extra tick separates each server send from the next client input poll. */
final class FastDuel {
    static final int BUFFER = 16;
    static int slot(int t) { return Math.floorMod(t,BUFFER); }
    static final class Up {
        boolean attack, punch, ground;
        int sprint;
        float yaw, pitch;
        double x,y,z;
    }
    static final class Down {
        boolean motion, position, damage, freshDamage, enemyDamage;
        int sprint, duration;
        double vx,vy,vz,x,y,z;
        float hp;
        void clear() { motion=position=damage=freshDamage=enemyDamage=false; sprint=0; }
    }
    record Waypoint(double x,double y,double z,int ticks){}
    static final class Seat {
        final SmoothAim aim=SmoothAim.ENABLED?new SmoothAim():null;
        final Motion local = new Motion(), server = new Motion();
        final Up[] up = new Up[BUFFER];
        final Down[] down = new Down[BUFFER];
        final Impulse kb = new Impulse();
        final Model.Body stats = new Model.Body();
        final SplittableRandom random;
        final int lag, phase;
        boolean lastCritical,lastBoost;float lastCharge;
        double tx,ty,tz,px,py,pz,gx,gy,gz;
        final ArrayDeque<Waypoint> path = new ArrayDeque<>();
        double startX,startY,startZ;
        double sentX,sentY,sentZ,decodedX,decodedY,decodedZ;
        float elapsed, remaining, pace=1f;
        int trackerTicks;
        byte lastYaw, lastPitch;
        float serverPitch;
        int age=20,serverAge=20,immune,hurt,otherHurt,jumpDelay,miss,tap;
        boolean localFirst=true;
        boolean sprint,serverSprint,lastSent,previousForward,dirty,fall,needsSync,prevTrackGround=true;
        float hp=20,seenHP=20,lastDamage;
        Seat(int lag,int phase,long seed) {
            if(lag<0||lag>=BUFFER-2)throw new IllegalArgumentException("Unsupported one-way tick delay: "+lag);
            this.lag=lag;this.phase=phase;trackerTicks=1-phase;random=new SplittableRandom(seed);
            Arrays.setAll(up,i->new Up());Arrays.setAll(down,i->new Down());
        }
        void start(double x,double y,double z,float yaw,double ex,double ey,double ez) {
            local.set(x,y,z);server.set(x,y,z);local.yaw=server.yaw=(float)Fighter.angle(yaw);
            lastYaw=packDegrees(local.yaw);
            sentX=decodedX=x;sentY=decodedY=y;sentZ=decodedZ=z;
            tx=px=gx=ex;ty=py=gy=ey;tz=pz=gz=ez;
        }
        void receive(int tick) {
            receive(down[slot(tick)]);
        }
        void receive(Down d) {
            if(d.motion){local.vx=d.vx;local.vy=d.vy;local.vz=d.vz;}
            if(d.position){
                if(path.isEmpty()){startX=tx;startY=ty;startZ=tz;elapsed=1;}
                int duration=(gx==d.x&&gy==d.y&&gz==d.z)?2:d.duration;
                gx=d.x;gy=d.y;gz=d.z;path.addLast(new Waypoint(gx,gy,gz,duration));
                remaining+=duration;
            }
            if(d.damage){seenHP=d.hp;if(d.freshDamage)hurt=10;}
            if(d.enemyDamage)otherHurt=10;
            if(d.sprint!=0)sprint=d.sprint>0;
            d.clear();
        }
        View view() {
            return new View(local.x,local.y,local.z,local.vx,local.vy,local.vz,tx,ty,tz,
                tx-px,ty-py,tz-pz,local.ground,sprint,Math.min(1f,age/ATTACK_DELAY),seenHP,hurt,otherHurt,aim!=null&&aim.ready?aim.yawAt(1):local.yaw);
        }
        void drive(int t,View view,Keys keys) {
            if(aim==null){input(t,keys,(float)steer(view,keys),pitch(view));return;}
            float currentPitch=aim.ready?aim.pitchAt(1):0;
            aim.plan(view,keys,currentPitch,pitch(view));
            input(t,keys,aim.yawAt(0),currentPitch);
        }
        void input(int t,Keys k,float yaw,float pitch) {
            var u=up[slot(t+lag)];
            local.yaw=yaw;
            if(miss>0)miss--;
            u.attack=false;u.punch=k.click();
            if(k.click()){
                stats.clicks++;
                if(miss==0){
                    double h=angle(yaw),v=angle(pitch);
                    u.attack=Model.rayBox(tx-local.x,ty-local.y-1.62f,tz-local.z,
                        sin(-h)*cos(v),-sin(v),cos(-h)*cos(v));
                    if(u.attack&&sprint&&charge(age)>.9f){sprint=false;local.vx*=.6;local.vz*=.6;}
                    // The runner submits press edges. continueAttack(false) clears the miss delay at tick end.
                    age=0;
                }
            }
            boolean forward=k.forward()>0;
            if(tap>0)tap--;
            if(k.forward()<0)tap=0;
            if(!sprint&&forward){
                // LocalPlayer arms the second forward press on the ground and in midair.
                if(!previousForward){
                    if(tap>0)sprint=true;
                    else tap=7;
                }
                if(k.sprint())sprint=true;
            }
            if(!forward||local.blocksSprint)sprint=false;
            previousForward=forward;
            px=tx;py=ty;pz=tz;
            if(!localFirst){advanceDisplay();collide(local,tx,ty,tz);}
            local.snapSmallVelocity();
            if(jumpDelay>0)jumpDelay--;
            if(!k.jump())jumpDelay=0;
            if(k.jump()&&local.ground&&jumpDelay==0){local.jump(sprint);jumpDelay=10;stats.jumps++;}
            local.travel(k.forward(),k.side(),sprint);
            u.x=local.x;u.y=local.y;u.z=local.z;u.yaw=yaw;u.pitch=pitch;u.ground=local.ground;
            u.sprint=sprint==lastSent?0:sprint?1:-1;lastSent=sprint;
            // EntitySelector.pushableBy selects only the local player on a client.
            // The remote player's entity tick is the one that can push us.
            if(localFirst){advanceDisplay();collide(local,tx,ty,tz);}
            age++;hurt=Math.max(0,hurt-1);otherHurt=Math.max(0,otherHurt-1);
        }
        void advanceDisplay() {
            if(!path.isEmpty()){
                while(!path.isEmpty()){
                    var w=path.getFirst();
                    if(elapsed<w.ticks){
                        double f=elapsed/w.ticks;
                        tx=startX+(w.x-startX)*f;ty=startY+(w.y-startY)*f;tz=startZ+(w.z-startZ)*f;
                        break;
                    }
                    elapsed-=w.ticks;startX=tx=w.x;startY=ty=w.y;startZ=tz=w.z;path.removeFirst();
                }
                pace+=.5f*(Math.max(1f,remaining/2)-pace);
                float advance=Math.min(remaining,pace);if(pace>=remaining)pace=1;
                remaining-=advance;elapsed+=advance;
            }else{remaining=0;pace=1;}

        }
        void setServerSprint(boolean value) {
            if(serverSprint!=value){serverSprint=value;dirty=true;}
        }
        void handle(int t,Seat other) {
            if(t<lag)return;
            handle(t,other,up[slot(t)]);
        }
        void handle(int t,Seat other,Up u) {
            if(u.attack&&hp>0&&other.hp>0)attack(t,other);
            if(u.punch)serverAge=0;
            if(u.sprint!=0)setServerSprint(u.sprint>0);
            if(server.ground&&!u.ground&&u.y>server.y){server.jump(serverSprint);needsSync=true;}
            if(u.ground||u.y>server.y)fall=false;
            else if(u.y<server.y)fall=true;
            server.set(u.x,u.y,u.z);server.yaw=wrapYaw(u.yaw);serverPitch=u.pitch;server.ground=u.ground;
        }
        void attack(int t,Seat other) {
            double dx=other.server.x-server.x,dz=other.server.z-server.z;
            // Survival's server check is distance from the eye to the target box,
            // with the three-block packet tolerance added to the interaction range.
            double x=Math.max(0,Math.abs(dx)-.3f),z=Math.max(0,Math.abs(dz)-.3f);
            double eye=server.y+1.62f;
            double y=Math.max(0,Math.max(other.server.y-eye,eye-other.server.y-1.8f));
            if(x*x+y*y+z*z>=36)return;
            float c=charge(serverAge);serverAge=0;
            boolean critical=c>.9f&&!serverSprint&&fall&&!server.ground;
            float raw=rawDamage(c,critical);
            boolean fresh=other.immune<=10, boost=c>.9f&&serverSprint;
            float damage=fresh?raw:raw-other.lastDamage;
            if(damage<=0)return;
            other.hp=Math.max(0,other.hp-armored(damage));
            other.lastDamage=raw;stats.hits++;lastCritical=critical;lastBoost=boost;lastCharge=c;
            if(fresh){other.immune=20;stats.lastFresh=t;}
            if(stats.firstHit<0)stats.firstHit=t;
            if(critical)stats.airHits++;
            var d=other.down[slot(t+other.lag+1)];
            d.damage=true;d.freshDamage=fresh;d.hp=other.hp;
            if(fresh)down[slot(t+lag+1)].enemyDamage=true;
            if(fresh||boost){
                while(dx*dx+dz*dz<1e-5f){
                    dx=(other.random.nextDouble()-other.random.nextDouble())*.01;
                    dz=(other.random.nextDouble()-other.random.nextDouble())*.01;
                }
                kb.hit(other.server.vx,other.server.vy,other.server.vz,other.server.ground,dx,dz,server.yaw,fresh,boost);
                kb.wire();d.motion=true;d.vx=kb.x;d.vy=kb.y;d.vz=kb.z;
                other.needsSync=true;
            }
            if(boost){server.vx*=.6;server.vz*=.6;setServerSprint(false);}
        }
        static byte packDegrees(float value){return (byte)Math.floor(value*256f/360f);}
        void broadcast(int t,Seat observer) {
            trackerTicks++;
            boolean full=server.ground!=prevTrackGround;
            if(((t+phase)&1)==0||dirty||needsSync){
                double dx=server.x-sentX,dy=server.y-sentY,dz=server.z-sentZ;
                byte yaw=packDegrees(server.yaw),pitch=packDegrees(serverPitch);
                boolean rotated=yaw!=lastYaw||pitch!=lastPitch;
                var d=observer.down[slot(t+observer.lag+1)];
                if(full||dx*dx+dy*dy+dz*dz>=7.62939453125e-6||(t+phase+8)%60==0){
                    d.position=true;d.duration=trackerTicks;
                    d.x=decodedX=full?server.x:decodeStep(server.x,sentX,decodedX);
                    d.y=decodedY=full?server.y:decodeStep(server.y,sentY,decodedY);
                    d.z=decodedZ=full?server.z:decodeStep(server.z,sentZ,decodedZ);
                    sentX=server.x;sentY=server.y;sentZ=server.z;
                }else if(rotated){
                    // A rotation-only packet asks the remote player to interpolate at its existing destination.
                    d.position=true;d.duration=2;
                    d.x=decodedX;d.y=decodedY;d.z=decodedZ;
                }
                lastYaw=yaw;lastPitch=pitch;
                trackerTicks=0;
                prevTrackGround=server.ground;
                if(dirty)down[slot(t+lag+1)].sprint=serverSprint?1:-1;
            }
            dirty=false;needsSync=false;
        }
        void serverTick(Seat other) {
            var s=server;double x=s.x,y=s.y,z=s.z;
            s.snapSmallVelocity();s.travel(0,0,serverSprint);
            if(collide(s,other.server.x,other.server.y,other.server.z)){
                collide(other.server,s.x,s.y,s.z);
                needsSync=other.needsSync=true;
            }
            s.set(x,y,z);
            serverAge++;immune=Math.max(0,immune-1);
            stats.hp=hp;
        }
    }
    static final float ATTACK_DELAY = (float)(1./(4.-(double)2.4f)*20.);
    static boolean collide(Motion p,double x,double y,double z){
        double dx=p.x-x,dz=p.z-z;
        if(Math.abs(dx)>=.6f||Math.abs(dz)>=.6f||Math.abs(p.y-y)>=1.8f)return false;
        double radius=Math.max(Math.abs(dx),Math.abs(dz));
        if(radius<.01f)return false;
        double scale=Math.sqrt(radius);
        p.vx+=(dx/scale)*Math.min(1.,1./scale)*.05f;
        p.vz+=(dz/scale)*Math.min(1.,1./scale)*.05f;
        return true;
    }
    static float charge(int age){return Math.min(1f,(age+.5f)/ATTACK_DELAY);}
    static double decodeStep(double value,double base,double decoded){
        long delta=Math.round(value*4096)-Math.round(base*4096);
        return delta==0?decoded:(Math.round(decoded*4096)+delta)/4096.;
    }
    static float wrapYaw(float yaw){
        float wrapped=yaw%360f;
        if(wrapped>=180)wrapped-=360;
        if(wrapped< -180)wrapped+=360;
        return wrapped;
    }
    static float pitch(View v) {
        double eye=v.y()+1.62f;
        return (float)-Math.toDegrees(Math.atan2(clip(eye,v.ty()+.1,v.ty()+1.7)-eye,Math.hypot(v.tx()-v.x(),v.tz()-v.z())));
    }
    static Model.Score duel(Model.Controller ca,Model.Controller cb,Model.Start start,Model.Observer observer){
        var scene=start.scene();var random=new SplittableRandom(scene.seed());
        double gap=random.nextDouble(3.5,8),angle=random.nextDouble(2*Math.PI);
        double x=Math.rint(Math.cos(angle)*gap*5000)/10000,z=Math.rint(Math.sin(angle)*gap*5000)/10000;
        float yaw=(float)(Math.rint((Math.toDegrees(angle)-90)*100)/100);
        var a=new Seat(scene.delayA(),random.nextInt(2),random.nextLong());
        var b=new Seat(scene.delayB(),random.nextInt(2),random.nextLong());
        a.start(-x,FLOOR,-z,yaw,x,FLOOR,z);b.start(x,FLOOR,z,yaw+180,-x,FLOOR,-z);
        boolean aFirst=random.nextBoolean();a.localFirst=random.nextBoolean();b.localFirst=random.nextBoolean();
        if(PhaseClock.enabled(scene.seed()))
            return PhaseClock.duel(a,b,ca,cb,start,observer,random.nextLong(),aFirst);
        return scheduled(a,b,ca,cb,start,observer,aFirst);
    }
    static Model.Score scheduled(Seat a,Seat b,Model.Controller ca,Model.Controller cb,Model.Start start,Model.Observer observer,boolean aFirst){
        int t=0;
        for(;t<Model.LIMIT&&a.hp>0&&b.hp>0;t++){
            a.receive(t);b.receive(t);var va=a.view();var vb=b.view();
            Keys ka,kb;
            if(t<start.prefix().size()){
                var frame=start.prefix().get(t);ka=frame.a();kb=frame.b();ca.prime(va,ka);cb.prime(vb,kb);
            }else{ka=ca.act(va);kb=cb.act(vb);}
            double before=a.hp-b.hp;
            a.drive(t,va,ka);b.drive(t,vb,kb);
            if(aFirst){a.handle(t,b);b.handle(t,a);}else{b.handle(t,a);a.handle(t,b);}
            a.broadcast(t,b);b.broadcast(t,a);a.serverTick(b);b.serverTick(a);
            if(observer!=null)observer.step(t,a.stats,b.stats,ka,kb,aFirst);
            double reward=(a.hp-b.hp-before)/20+(b.hp==0?1:a.hp==0?-1:0);
            if(t>=start.prefix().size()){ca.reward(reward);cb.reward(-reward);}
        }
        return new Model.Score(a.hp,b.hp,t,a.stats.hits,b.stats.hits,a.stats.clicks,b.stats.clicks,
            a.stats.jumps,b.stats.jumps,a.stats.airHits,b.stats.airHits);
    }
}
