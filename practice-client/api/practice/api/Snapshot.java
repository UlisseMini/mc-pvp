package practice.api;

import java.util.*;

public final class Snapshot {
    public final long timeMillis, timeNanos, tick;
    public final boolean playing, screenOpen, sprintPressed;
    public final Player self, target;
    public final List<String> effects;

    public Snapshot(long millis,long nanos,long tick,boolean playing,boolean screenOpen,
                    boolean sprintPressed,Player self,Player target,List<String> effects) {
        this.timeMillis=millis;this.timeNanos=nanos;this.tick=tick;this.playing=playing;
        this.screenOpen=screenOpen;this.sprintPressed=sprintPressed;this.self=self;this.target=target;
        this.effects=Collections.unmodifiableList(new ArrayList<>(effects));
    }
    public static final class Player {
        public final int id, hurtTicks;
        public final double x,y,z,dx,dy,dz,eyeY,health;
        public final boolean grounded,blocking,sprinting;
        public Player(int id,double x,double y,double z,double dx,double dy,double dz,
                      double eyeY,double health,int hurtTicks,boolean grounded,boolean blocking,boolean sprinting) {
            this.id=id;this.x=x;this.y=y;this.z=z;this.dx=dx;this.dy=dy;this.dz=dz;
            this.eyeY=eyeY;this.health=health;this.hurtTicks=hurtTicks;
            this.grounded=grounded;this.blocking=blocking;this.sprinting=sprinting;
        }
        public double speed(){return Math.hypot(dx,dz)*20.0;}
    }
}
