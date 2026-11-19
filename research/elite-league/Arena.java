import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import lab.DuelNetwork;

/** Policy adapters may see only the local input-head observation. */
final class Arena {
    static final Set<String> NATIVE_STYLES=Set.of("zest-strafe","wtap-tick-window","phase-spacing","kb-displace-pull","kb-displace-edge");
    static final String[] SCRIPTED={"wtap","hold","trade","reset","pressure","select","counterstrafe"};
    record Model(DuelNetwork.Family family,float[] weights){
        String encode(){return DuelNetwork.encode(family,weights);}
        static Model decode(String encoded){
            DuelNetwork n=DuelNetwork.decode(encoded);
            return new Model(n.family(),n.weights());
        }
    }
    record Fighter(String name,Model model){
        static Fighter named(String name){return new Fighter(name,null);}
        DuelEnv.Player create(int rtt,int wake){return create(rtt,wake,true,10);}
        DuelEnv.Player create(int rtt,int wake,boolean mmc,int diameter){
            if(model!=null)return new Neural(model,rtt);
            if(name.startsWith("legacy-"))return new HistoricalMovement(name.substring(7),rtt,rtt);
            if(NATIVE_STYLES.contains(name))
                return new StrafeOpponent(name,rtt,mmc,diameter);
            if(name.equals("select")||name.equals("counterstrafe"))return new Sparring(name,rtt);
            if(name.equals("pressure")){var p=new DuelEnv.Player("hold",5168,rtt,wake);return p;}
            return new DuelEnv.Player(name,0,rtt,wake);
        }
    }
    static final class Neural extends DuelEnv.Player{
        DuelNetwork net;
        boolean shadow;
        Neural(Model model,int rtt){super("network",0,rtt,0);net=new DuelNetwork(model.family,model.weights);}
        private Neural(Neural p){
            super(p.style,0,p.rtt,p.wake);net=p.net.copy();shadow=p.shadow;firstClick=p.firstClick;
            handled=p.handled;releaseUntil=p.releaseUntil;jumps=p.jumps;
        }
        @Override Neural copy(){return new Neural(this);}
        @Override public PeriodicComboClock.Control input(PeriodicComboClock.Observation o){
            var q=o.own();
            int action=net.step(q.x(),q.y(),q.z(),o.targetX(),o.targetY(),o.targetZ(),q.vx(),q.vy(),q.vz(),q.onGround(),
                o.sprint(),o.time()-o.lastOwnHurtTime(),o.time()-o.lastTargetHurtTime(),rtt);
            if(shadow){
                var w=super.input(o);
                net.applied(DuelNetwork.pack(w.key(),(int)Math.signum(w.strafe()),w.click(),w.jump()));
                return w;
            }
            net.applied(action);
            if(DuelNetwork.jump(action))jumps++;
            return new PeriodicComboClock.Control(DuelNetwork.key(action),.98f*DuelNetwork.side(action),DuelNetwork.click(action),DuelNetwork.jump(action));
        }
    }
    /** Diverse development opponents. A delayed retaliation and an opponent strafing into center after recoil. */
    static final class Sparring extends DuelEnv.Player {
        final boolean selecting;
        double originX=Double.NaN,originZ,previousOwnCue=Double.NEGATIVE_INFINITY;
        int direction=1;
        Sparring(String style,int rtt){super(style,0,rtt,0);selecting=style.equals("select");}
        @Override Sparring copy(){
            Sparring c=new Sparring(style,rtt);c.originX=originX;c.originZ=originZ;c.previousOwnCue=previousOwnCue;
            c.direction=direction;c.firstClick=firstClick;return c;
        }
        @Override public PeriodicComboClock.Control input(PeriodicComboClock.Observation o){
            var q=o.own();double dx=o.targetX()-q.x(),dz=o.targetZ()-q.z(),gap=Math.hypot(dx,dz);
            if(Double.isNaN(originX)){originX=(q.x()+o.targetX())/2;originZ=(q.z()+o.targetZ())/2;}
            double sinceOwn=o.time()-o.lastOwnHurtTime(),sinceTarget=o.time()-o.lastTargetHurtTime();
            if(o.lastOwnHurtTime()>previousOwnCue){
                previousOwnCue=o.lastOwnHurtTime();
                double side=(originX-q.x())*dz-(originZ-q.z())*dx;
                direction=Math.abs(side)>.5?(side>0?1:-1):-direction;
            }
            boolean wait=selecting&&o.time()<900&&!Double.isFinite(o.lastOwnHurtTime())&&gap<3.8;
            int key=wait?0:sinceTarget<60&&sinceOwn>130?0:2;
            float strafe=selecting?0:gap<4.2?.98f*direction:0;
            return new PeriodicComboClock.Control(key,strafe,!wait,(!selecting&&sinceOwn<100&&q.onGround()));
        }
    }
    record Scenario(DuelEnv env,Fighter opponent,boolean first){
        double game(Fighter fighter){return round(fighter).reward();}
        DuelEnv.Game round(Fighter fighter){
            var config=env.initial.config;
            DuelEnv.Game game=env.new Game(first,fighter.create(first?env.rtt:env.otherRtt,0,config.mmc,config.sumoDiameter),
                opponent.create(first?env.otherRtt:env.rtt,0,config.mmc,config.sumoDiameter));
            game.finish();return game;
        }
    }
    static List<DuelEnv> envs(List<Path> files,String profile,boolean sumo)throws IOException{
        var result=new ArrayList<DuelEnv>();
        for(Path path:files){
            var tape=DuelEnv.Tape.read(path,sumo?DuelEnv.LIMIT:20000);
            for(int rtt:new int[]{0,80})result.add(new DuelEnv(tape,profile,rtt,sumo));
        }
        return result;
    }
}
