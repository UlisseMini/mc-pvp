package lab;

import java.util.*;

/** Server-only, match-scoped research intervention; default path changes nothing. */
public final class CombatTuning {
    static CombatProfile nextScenario=CombatProfile.VANILLA;
    private static Object target;
    private static Referee.Match match;
    private static int previousScore;
    private static boolean fresh,preAttackSprint;
    public static void before(Object attacker,Object victim){
        target=null;match=null;
        if(!"server".equals(System.getProperty("lab.role"))||Referee.server==null)return;
        Referee.Match m=Referee.find(R.name(attacker));
        if(m==null||m.combat.vanilla()||m.finish!=0||System.currentTimeMillis()<m.start||
                !(R.name(victim).equals(m.a)||R.name(victim).equals(m.b)))return;
        target=victim;match=m;previousScore=m.ha+m.hb;
        preAttackSprint=Boolean.TRUE.equals(R.call(attacker,"isSprinting"));
        fresh=R.num(victim,"hurtResistantTime")<=R.num(victim,"maxHurtResistantTime")/2;
    }
    /** Called at the single vanilla player-hit S12 construction site. */
    public static void outgoing(Object victim){
        if(victim!=target||match==null||!fresh||match.ha+match.hb==previousScore)return;
        CombatProfile p=match.combat;
        if(p.horizontal()==1&&p.vertical()==1&&(!preAttackSprint||p.sprintVertical()==.1))return;
        double x=R.num(victim,"motionX"),y=R.num(victim,"motionY"),z=R.num(victim,"motionZ");
        R.set(victim,"motionX",x*p.horizontal());R.set(victim,"motionY",p.freshVertical(y,preAttackSprint));R.set(victim,"motionZ",z*p.horizontal());
        Referee.append(match.replay,Map.of("type","combat_velocity_scale","time",System.currentTimeMillis(),
            "server_tick",Referee.ticks,"target",R.name(victim),"before",List.of(x,y,z),
            "after",List.of(x*p.horizontal(),p.freshVertical(y,preAttackSprint),z*p.horizontal()),
            "pre_attack_server_sprint",preAttackSprint,"sprint_vertical",p.sprintVertical()));
    }
    public static void after(){target=null;match=null;}
    static void configurePlayer(Object player,CombatProfile profile){
        R.set(player,"maxHurtResistantTime",profile.freshTicks()*2);
        R.set(player,"hurtResistantTime",0);
    }
    static void command(Object sender,String[] args){
        if(args.length>2)nextScenario=args.length==3&&args[2].equals("vanilla")?CombatProfile.VANILLA:CombatProfile.parse(args,2);
        Referee.message(sender,"§dNext bot timed scenarios: KB horizontal ×"+nextScenario.horizontal()+", vertical ×"+nextScenario.vertical()+", sprint extra Y "+nextScenario.sprintVertical()+", fresh delay "+nextScenario.freshTicks()+" ticks. §7Existing games and ordinary duels unchanged. /lab combat vanilla resets.");
    }
}
