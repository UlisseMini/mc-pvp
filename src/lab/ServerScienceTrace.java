package lab;

import java.util.*;

/** Server-only half of passive science probes. */
public final class ServerScienceTrace {
    private static Map<String,Object> attack;
    static Map<String,Object> state(Object e){Map<String,Object> s=ScienceTrace.state(e);s.put("maxHurtResistantTime",R.num(e,"maxHurtResistantTime"));return s;}
    static Referee.Match tracedMatch(Object player) {
        if(!"server".equals(System.getProperty("lab.role")) || Referee.server==null)return null;
        Referee.Match m=Referee.find(R.name(player));
        return m!=null && (m.configA.getProperty("science_trace","0").equals("1") || m.configB.getProperty("science_trace","0").equals("1")) ? m:null;
    }
    public static void attackBefore(Object self,Object target) {
        if(!"server".equals(System.getProperty("lab.role")))return;
        long ns=System.nanoTime(),ms=System.currentTimeMillis();
        try {
            attack=null;Referee.Match m=tracedMatch(self);if(m==null || System.currentTimeMillis()<m.start)return;
            attack=new LinkedHashMap<>();attack.put("type","science_server_attack");attack.put("time",ms);
            attack.put("monotonic_ns",ns);attack.put("packet_id",ServerReplicationTrace.currentPacketId());
            attack.put("server_tick",Referee.ticks);attack.put("attacker",R.name(self));attack.put("target",R.name(target));
            attack.put("attacker_before",state(self));attack.put("target_before",state(target));
            attack.put("score_before",List.of(m.ha,m.hb));
        }catch(Throwable e){if(Referee.errors++<10)e.printStackTrace();}
    }
    public static void attackAfter(Object self,Object target) {
        if(!"server".equals(System.getProperty("lab.role")) || attack==null)return;
        long ns=System.nanoTime(),ms=System.currentTimeMillis();
        try {
            Referee.Match m=tracedMatch(self);if(m==null)return;
            attack.put("monotonic_end_ns",ns);attack.put("return_time",ms);
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
