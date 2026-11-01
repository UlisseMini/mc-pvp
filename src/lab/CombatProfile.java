package lab;

import java.util.*;

/** Explicit experimental server settings. Vanilla is an exact no-op. */
public record CombatProfile(double horizontal, double vertical, int freshTicks) {
    public static final CombatProfile VANILLA = new CombatProfile(1,1,10);
    public CombatProfile {
        if(!Double.isFinite(horizontal)||horizontal<0||horizontal>2 ||
           !Double.isFinite(vertical)||vertical<0||vertical>2 || freshTicks<1||freshTicks>20)
            throw new IllegalArgumentException("KB multipliers must be 0–2; hit_ticks must be 1–20 (vanilla 10)");
    }
    public boolean vanilla(){return horizontal==1&&vertical==1&&freshTicks==10;}
    public Map<String,Object> state(){return Map.of("kb_horizontal",horizontal,"kb_vertical",vertical,
        "fresh_hit_ticks",freshTicks,"max_hurt_resistant_ticks",2*freshTicks,
        "modified",!vanilla(),"scope","bot timed scenarios only",
        "kb_semantics","scale final native fresh melee velocity before S12 quantization; native restored server motion unchanged");}
    public static CombatProfile parse(String[] args,int from){
        double horizontal=1,vertical=1;int ticks=10;Set<String> seen=new HashSet<>();
        for(int i=from;i<args.length;i++){
            String[] p=args[i].split("=",-1);
            if(p.length!=2||!seen.add(p[0]))throw new IllegalArgumentException("Use kb_horizontal=1 kb_vertical=1 hit_ticks=10; no duplicates");
            switch(p[0]){
                case "kb_horizontal":horizontal=Double.parseDouble(p[1]);break;
                case "kb_vertical":vertical=Double.parseDouble(p[1]);break;
                case "hit_ticks":ticks=Integer.parseInt(p[1]);break;
                default:throw new IllegalArgumentException("Unknown combat setting: "+p[0]);
            }
        }
        return new CombatProfile(horizontal,vertical,ticks);
    }
}
