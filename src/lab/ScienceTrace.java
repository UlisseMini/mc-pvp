package lab;

import java.util.*;

/** Read-only native transition probes. Data is written to disk, never fed to a policy. */
public final class ScienceTrace {
    private static Map<String,Object> movement, interpolation, attack;
    private static long sequence;
    static boolean client() {
        return "client".equals(System.getProperty("lab.role")) && Client.active()
            && Client.controls.getProperty("science_trace","0").equals("1");
    }
    static Map<String,Object> state(Object entity) {
        Map<String,Object> s=new LinkedHashMap<>();
        for(String k:List.of("posX","posY","posZ","motionX","motionY","motionZ","rotationYaw","rotationYawHead","prevRotationYawHead","rotationPitch","hurtResistantTime","fallDistance","lastDamage"))s.put(k,R.num(entity,k));
        s.put("ground",R.get(entity,"onGround"));s.put("sprint",R.call(entity,"isSprinting"));
        return s;
    }
    static Map<String,Object> event(String type) {
        Map<String,Object> e=new LinkedHashMap<>();e.put("type",type);e.put("time",System.currentTimeMillis());
        e.put("monotonic_ns",System.nanoTime());e.put("sequence",++sequence);e.put("tick",Client.ticks);e.put("match",Client.match);return e;
    }
    public static void movementBefore(Object self,float strafe,float forward) {
        if(!client() || self!=Client.player)return;
        try {
            movement=event("science_move");movement.put("before",state(self));
            movement.put("strafe",strafe);movement.put("forward",forward);
            movement.put("ground_acceleration",R.call(self,"getAIMoveSpeed"));
            movement.put("air_acceleration",R.num(self,"jumpMovementFactor"));
            movement.put("water",R.call(self,"isInWater"));movement.put("lava",R.call(self,"isInLava"));
            movement.put("ladder",R.call(self,"isOnLadder"));
        } catch(Throwable e){Client.fail(e);}
    }
    public static void movementAfter(Object self) {
        if(!client() || self!=Client.player || movement==null)return;
        try {movement.put("after",state(self));Client.log(movement);movement=null;}catch(Throwable e){Client.fail(e);}
    }
    public static void interpolationBefore(Object self) {
        if(!client() || self!=Client.opponent)return;
        try {
            interpolation=event("science_interpolation");interpolation.put("before",state(self));
            interpolation.put("steps",R.num(self,"otherPlayerMPPosRotationIncrements"));
            interpolation.put("goal",List.of(R.num(self,"otherPlayerMPX"),R.num(self,"otherPlayerMPY"),R.num(self,"otherPlayerMPZ")));
        }catch(Throwable e){Client.fail(e);}
    }
    public static void interpolationAfter(Object self) {
        if(!client() || self!=Client.opponent || interpolation==null)return;
        try {interpolation.put("after",state(self));Client.log(interpolation);interpolation=null;}catch(Throwable e){Client.fail(e);}
    }
    public static void remoteUpdate(Object self) {
        if(!client() || self!=Client.opponent)return;
        try {Map<String,Object> e=event("science_remote_update");e.put("steps",R.num(self,"otherPlayerMPPosRotationIncrements"));
            e.put("goal",List.of(R.num(self,"otherPlayerMPX"),R.num(self,"otherPlayerMPY"),R.num(self,"otherPlayerMPZ")));Client.log(e);
        }catch(Throwable e){Client.fail(e);}
    }
    static Referee.Match tracedMatch(Object player) {
        if(!"server".equals(System.getProperty("lab.role")) || Referee.server==null)return null;
        Referee.Match m=Referee.find(R.name(player));
        return m!=null && (m.configA.getProperty("science_trace","0").equals("1") || m.configB.getProperty("science_trace","0").equals("1")) ? m:null;
    }
    public static void attackBefore(Object self,Object target) {
        if(!"server".equals(System.getProperty("lab.role")))return;
        try {
            attack=null;Referee.Match m=tracedMatch(self);if(m==null || System.currentTimeMillis()<m.start)return;
            attack=new LinkedHashMap<>();attack.put("type","science_server_attack");attack.put("time",System.currentTimeMillis());
            attack.put("server_tick",Referee.ticks);attack.put("attacker",R.name(self));attack.put("target",R.name(target));
            attack.put("attacker_before",state(self));attack.put("target_before",state(target));
            attack.put("score_before",List.of(m.ha,m.hb));
        }catch(Throwable e){if(Referee.errors++<10)e.printStackTrace();}
    }
    public static void attackAfter(Object self,Object target) {
        if(!"server".equals(System.getProperty("lab.role")) || attack==null)return;
        try {
            Referee.Match m=tracedMatch(self);if(m==null)return;
            attack.put("attacker_after",state(self));attack.put("target_after",state(target));
            attack.put("score_after",List.of(m.ha,m.hb));Referee.append(m.replay,attack);attack=null;
        }catch(Throwable e){if(Referee.errors++<10)e.printStackTrace();}
    }
    public static void velocityCreated(Object packet,Object target) {
        if(!"server".equals(System.getProperty("lab.role")))return;
        try {
            Referee.Match m=tracedMatch(target);if(m==null || System.currentTimeMillis()<m.start)return;
            Map<String,Object> e=new LinkedHashMap<>();e.put("type","science_velocity_created");e.put("time",System.currentTimeMillis());
            e.put("server_tick",Referee.ticks);e.put("target",R.name(target));e.put("entity_id",R.call(target,"getEntityId"));
            e.put("velocity",List.of(R.num(packet,"motionX")/8000,R.num(packet,"motionY")/8000,R.num(packet,"motionZ")/8000));
            e.put("source_motion",state(target));if(attack!=null){e.put("attack_time",attack.get("time"));attack.put("velocity_packet",e.get("velocity"));}Referee.append(m.replay,e);
        }catch(Throwable e){if(Referee.errors++<10)e.printStackTrace();}
    }
}
