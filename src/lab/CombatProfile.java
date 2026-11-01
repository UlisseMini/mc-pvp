package lab;

import java.util.*;

/** Explicit experimental server settings. Vanilla is an exact no-op. */
public record CombatProfile(double horizontal, double vertical, int freshTicks, double sprintVertical) {
    public static final CombatProfile VANILLA = new CombatProfile(1,1,10);
    /** Preserve every existing three-argument caller's native sprint lift. */
    public CombatProfile(double horizontal,double vertical,int freshTicks){this(horizontal,vertical,freshTicks,.1);}
    public CombatProfile {
        if(!Double.isFinite(horizontal)||horizontal<0||horizontal>2 ||
           !Double.isFinite(vertical)||vertical<0||vertical>2 || freshTicks<1||freshTicks>20 || !Double.isFinite(sprintVertical)||sprintVertical<0||sprintVertical>1)
            throw new IllegalArgumentException("KB multipliers must be 0–2; hit_ticks must be 1–20 (vanilla 10); sprint_vertical must be 0–1 (vanilla .1)");
    }
    public boolean vanilla(){return horizontal==1&&vertical==1&&freshTicks==10&&sprintVertical==.1;}
    public Map<String,Object> state(){return Map.of("kb_horizontal",horizontal,"kb_vertical",vertical,
        "sprint_vertical",sprintVertical,"fresh_hit_ticks",freshTicks,"max_hurt_resistant_ticks",2*freshTicks,
        "modified",!vanilla(),"scope","both players in this match",
        "kb_semantics","replace pre-attack sprint extra Y, then scale native fresh melee velocity before S12 quantization; restored server motion unchanged");}
    /** For captured sprint attacks, native velocity already contains .1; replace that contribution. */
    public double freshVertical(double nativeY,boolean preAttackSprint){
        double adjusted=preAttackSprint&&sprintVertical!=.1?nativeY+(sprintVertical-.1):nativeY;
        return adjusted*vertical;
    }
    public static CombatProfile fromOverrides(Properties p,CombatProfile fallback){
        return new CombatProfile(Double.parseDouble(p.getProperty("combat_kb_horizontal",Double.toString(fallback.horizontal))),
            Double.parseDouble(p.getProperty("combat_kb_vertical",Double.toString(fallback.vertical))),
            Integer.parseInt(p.getProperty("combat_hit_ticks",Integer.toString(fallback.freshTicks))),
            Double.parseDouble(p.getProperty("combat_sprint_vertical",Double.toString(fallback.sprintVertical))));
    }
    public static CombatProfile parse(String[] args,int from){
        double horizontal=1,vertical=1,sprintVertical=.1;int ticks=10;Set<String> seen=new HashSet<>();
        for(int i=from;i<args.length;i++){
            String[] p=args[i].split("=",-1);
            if(p.length==2&&p[0].equals("combat_sprint_vertical"))p[0]="sprint_vertical";
            if(p.length!=2||!seen.add(p[0]))throw new IllegalArgumentException("Use kb_horizontal=1 kb_vertical=1 hit_ticks=10 sprint_vertical=.1; no duplicates");
            switch(p[0]){
                case "kb_horizontal":horizontal=Double.parseDouble(p[1]);break;
                case "kb_vertical":vertical=Double.parseDouble(p[1]);break;
                case "sprint_vertical":sprintVertical=Double.parseDouble(p[1]);break;
                case "hit_ticks":ticks=Integer.parseInt(p[1]);break;
                default:throw new IllegalArgumentException("Unknown combat setting: "+p[0]);
            }
        }
        return new CombatProfile(horizontal,vertical,ticks,sprintVertical);
    }
}
