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
        float yaw;
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
        float elapsed, remaining, pace=1f;
        int trackerTicks;
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
            tx=px=gx=ex;ty=py=gy=ey;tz=pz=gz=ez;
        }
        void receive(int tick) {
            var d=down[slot(tick)];
            if(d.motion){local.vx=d.vx;local.vy=d.vy;local.vz=d.vz;}
            if(d.position){
                if(path.isEmpty()){startX=tx;startY=ty;startZ=tz;elapsed=1;}
                gx=d.x;gy=d.y;gz=d.z;path.addLast(new Waypoint(gx,gy,gz,d.duration));
                remaining+=d.duration;
            }
            if(d.damage){seenHP=d.hp;if(d.freshDamage)hurt=10;}
            if(d.enemyDamage)otherHurt=10;
            if(d.sprint!=0)sprint=d.sprint>0;
            d.clear();
        }
        View view() {
            return new View(local.x,local.y,local.z,local.vx,local.vy,local.vz,tx,ty,tz,
                tx-px,ty-py,tz-pz,local.ground,sprint,Math.min(1f,age/ATTACK_DELAY),seenHP,hurt,otherHurt,local.yaw);
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
            if(!sprint&&forward){
                if(k.sprint()||(tap>0&&!previousForward)){sprint=true;tap=0;}
                else if(!previousForward&&local.ground)tap=7;
            }
            if(!forward||local.wall)sprint=false;
            previousForward=forward;
            px=tx;py=ty;pz=tz;
            if(!localFirst){advanceDisplay();collide(local,tx,ty,tz);}
            local.snapSmallVelocity();
            if(jumpDelay>0)jumpDelay--;
            if(!k.jump())jumpDelay=0;
            if(k.jump()&&local.ground&&jumpDelay==0){local.jump(sprint);jumpDelay=10;stats.jumps++;}
            local.travel(k.forward(),k.side(),sprint);
            u.x=local.x;u.y=local.y;u.z=local.z;u.yaw=yaw;u.ground=local.ground;
            u.sprint=sprint==lastSent?0:sprint?1:-1;lastSent=sprint;
            collide(local,tx,ty,tz);
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
            var u=up[slot(t)];
            if(u.attack&&hp>0&&other.hp>0)attack(t,other);
            if(u.punch)serverAge=0;
            if(u.sprint!=0)setServerSprint(u.sprint>0);
            if(server.ground&&!u.ground&&u.y>server.y){server.jump(serverSprint);needsSync=true;}
            if(u.ground||u.y>server.y)fall=false;
            else if(u.y<server.y)fall=true;
            server.set(u.x,u.y,u.z);server.yaw=u.yaw;server.ground=u.ground;
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
        void broadcast(int t,Seat observer) {
            trackerTicks++;
            boolean full=server.ground!=prevTrackGround;
            if(((t+phase)&1)==0||dirty||needsSync){
                var d=observer.down[slot(t+observer.lag+1)];
                d.position=true;d.duration=trackerTicks;trackerTicks=0;
                d.x=full?server.x:grid(server.x);d.y=full?server.y:grid(server.y);d.z=full?server.z:grid(server.z);
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
    static double grid(double n){return Math.round(n*4096)/4096.;}
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
        int t=0;
        for(;t<Model.LIMIT&&a.hp>0&&b.hp>0;t++){
            a.receive(t);b.receive(t);var va=a.view();var vb=b.view();
            Keys ka,kb;
            if(t<start.prefix().size()){
                var frame=start.prefix().get(t);ka=frame.a();kb=frame.b();ca.prime(va,ka);cb.prime(vb,kb);
            }else{ka=ca.act(va);kb=cb.act(vb);}
            double before=a.hp-b.hp;
            a.input(t,ka,(float)steer(va,ka),pitch(va));b.input(t,kb,(float)steer(vb,kb),pitch(vb));
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
