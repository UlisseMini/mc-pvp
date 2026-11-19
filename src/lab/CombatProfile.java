package lab;

import java.util.*;

/** Explicit experimental server settings. Vanilla is an exact no-op. */
public record CombatProfile(double horizontal, double vertical, int freshTicks, double sprintVertical, boolean mmc, boolean recordedMmc, boolean rangeMmc) {
    public CombatProfile(double h,double v,int ticks,double sprintY,boolean mmc,boolean recorded){this(h,v,ticks,sprintY,mmc,recorded,false);}
    public CombatProfile(double h,double v,int ticks,double sprintY,boolean mmc){this(h,v,ticks,sprintY,mmc,false);}
    public static final double RECORDED_BASE=.5275, RECORDED_EXTRA=.422, RECORDED_Y=.361375;
    static final double MMC_HORIZONTAL=.5274, MMC_VERTICAL=.361375, MMC_SPRINT_EXTRA=.327, MMC_HEIGHT_LIMIT=.675;
    public static final CombatProfile DEFAULT = new CombatProfile(1,1,DuelSettings.DEFAULT_HIT_TICKS);
    public static final CombatProfile VANILLA = new CombatProfile(1,1,DuelSettings.VANILLA_HIT_TICKS);
    /** Preserve every existing three-argument caller's native sprint lift. */
    public CombatProfile(double horizontal,double vertical,int freshTicks){this(horizontal,vertical,freshTicks,.1);}
    public CombatProfile(double horizontal,double vertical,int freshTicks,double sprintVertical){
        this(horizontal,vertical,freshTicks,sprintVertical,false);
    }
    public CombatProfile {
        if(!Double.isFinite(horizontal)||horizontal<0||horizontal>2 ||
           !Double.isFinite(vertical)||vertical<0||vertical>2 || freshTicks<1||freshTicks>20 || !Double.isFinite(sprintVertical)||sprintVertical<0||sprintVertical>1)
            throw new IllegalArgumentException("KB multipliers must be 0–2; hit_ticks must be 1–20 (vanilla 10); sprint_vertical must be 0–1 (vanilla .1)");
    }
    /** Measured sumo geometry. The raw direction average lets opposite directions cancel. */
    public static double rangeHorizontal(double separation,int extraLevels) {
        double attenuation=extraLevels==0?0:Math.min(.4,.35*Math.max(0,separation-3));
        return (RECORDED_BASE+extraLevels*RECORDED_EXTRA)*(1-attenuation);
    }
    public boolean vanilla(){return equals(VANILLA);}
    public Map<String,Object> state(){
        Map<String,Object> state=Map.of("kb_horizontal",horizontal,"kb_vertical",vertical,
        "sprint_vertical",sprintVertical,"fresh_hit_ticks",freshTicks,"max_hurt_resistant_ticks",2*freshTicks,
        "modified",!vanilla(),"scope","both players in this match",
        "kb_semantics",recordedMmc?"Recorded sumo fresh melee impulse and pre-attack sprint bonus":mmc?"MMC-style replacement fresh melee velocity with height cap and predicted landing":
            "replace pre-attack sprint extra Y, then scale native fresh melee velocity before S12 quantization; restored server motion unchanged");
        if(!mmc)return state;
        Map<String,Object> details=new LinkedHashMap<>(state);
        details.put("knockback_model",rangeMmc?PracticePresets.RANGE_MODEL:recordedMmc?PracticePresets.RECORDED_MODEL:"mmc_pre_attack_sprint_v2");
        details.put("mmc_sprint_rule","server_sprint_at_attack_entry");
        details.put("mmc_base_horizontal",recordedMmc?RECORDED_BASE:MMC_HORIZONTAL);
        details.put("mmc_base_vertical",MMC_VERTICAL);
        details.put("mmc_sprint_horizontal",recordedMmc?RECORDED_EXTRA:MMC_SPRINT_EXTRA);
        if(rangeMmc)details.put("mmc_range_rule","equal radial and facing components; sprint attenuation starts at 3 horizontal blocks, 0.35/block, capped at 40%");
        if(!recordedMmc)details.put("mmc_height_threshold",MMC_HEIGHT_LIMIT);
        return details;
    }
    /** For captured sprint attacks, native velocity already contains .1; replace that contribution. */
    public double freshVertical(double nativeY,boolean preAttackSprint){
        double adjusted=preAttackSprint&&sprintVertical!=.1?nativeY+(sprintVertical-.1):nativeY;
        return adjusted*vertical;
    }
    public static CombatProfile fromOverrides(Properties p,CombatProfile fallback){
        String model=p.getProperty(PracticePresets.KB_MODEL,fallback.rangeMmc?PracticePresets.RANGE_MODEL:fallback.recordedMmc?PracticePresets.RECORDED_MODEL:fallback.mmc?"mmc":"vanilla");
        boolean range=PracticePresets.RANGE_MODEL.equals(model);
        boolean recorded=range||PracticePresets.RECORDED_MODEL.equals(model);
        return new CombatProfile(Double.parseDouble(p.getProperty("combat_kb_horizontal",Double.toString(fallback.horizontal))),
            Double.parseDouble(p.getProperty("combat_kb_vertical",Double.toString(fallback.vertical))),
            Integer.parseInt(p.getProperty("combat_hit_ticks",Integer.toString(fallback.freshTicks))),
            Double.parseDouble(p.getProperty("combat_sprint_vertical",Double.toString(fallback.sprintVertical))),
            recorded||"mmc".equals(model),recorded,range);
    }
    public static CombatProfile parse(String[] args,int from){
        double horizontal=1,vertical=1,sprintVertical=.1;int ticks=DuelSettings.DEFAULT_HIT_TICKS;Set<String> seen=new HashSet<>();
        for(int i=from;i<args.length;i++){
            String[] p=args[i].split("=",-1);
            if(p.length==2&&p[0].equals("combat_sprint_vertical"))p[0]="sprint_vertical";
            if(p.length!=2||!seen.add(p[0]))throw new IllegalArgumentException("Use kb_horizontal=1 kb_vertical=1 hit_ticks="+DuelSettings.DEFAULT_HIT_TICKS+" sprint_vertical=.1; no duplicates");
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
