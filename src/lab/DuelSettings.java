package lab;

import java.util.*;

/** Per-duel overrides. Parsing never changes a shared policy or player preference. */
public final class DuelSettings {
    static final int DEFAULT_HIT_TICKS=9, VANILLA_HIT_TICKS=10;
    /** A tick sampled before the declared start cannot activate an optional acquisition policy. */
    public static boolean inOpening(long elapsedMs,double durationMs){return elapsedMs>=0 && elapsedMs<durationMs;}
    public static final String USAGE="/duel <bot> [mode] [bot RTT] [your RTT] [preset=lock8|hypixel|mmc] [reach=3.0] [cps=20] [map=mmc-129] [diameter=13] [ping=100] [myping=80] [kb=vanilla] [kb_horizontal=1] [kb_vertical=1] [hit_ticks="+DEFAULT_HIT_TICKS+"] [sprint_vertical=.1]";
    public final String mode;
    public final double botPing,humanPing;
    public final boolean humanPingExplicit;
    public final Properties overrides;
    private DuelSettings(String mode,double botPing,double humanPing,Properties overrides,boolean humanPingExplicit){
        this.mode=mode;this.botPing=botPing;this.humanPing=humanPing;this.overrides=overrides;this.humanPingExplicit=humanPingExplicit;
    }
    public static boolean mouseInput(Properties options){
        return "mouse".equals(options.getProperty("aim_input",options.containsKey("league_stage")?"mouse":"ideal"));
    }
    public static DuelSettings parse(String[] args,int offset,String mode,double botPing,double humanPing,Properties inherited){
        Properties overrides=new Properties();overrides.putAll(inherited);
        if(args.length>offset&&Compat.list("boxing","diamond","diamond_box","rod_diamond","sumo").contains(args[offset]))mode=args[offset++];
        String preset=null;
        for(int i=offset;i<args.length;i++){
            String[] pair=args[i].split("=",-1);
            if(pair.length==2&&pair[0].equalsIgnoreCase("preset")){
                if(preset!=null)throw new IllegalArgumentException("Duplicate setting: preset");
                preset=PracticePresets.normalize(pair[1]);
            }
        }
        if(preset!=null){
            overrides.remove(PracticePresets.KB_MODEL);
            overrides.putAll(PracticePresets.defaults(preset));
            if(PracticePresets.overridesDuelRtt(preset)){
                botPing=PracticePresets.defaultControllerRtt(preset);
                humanPing=PracticePresets.defaultOpponentRtt(preset);
            }
        }
        Set<String> seen=new HashSet<>();int positional=0;
        for(int i=offset;i<args.length;i++){
            String[] pair=args[i].split("=",-1);String key,value;
            if(pair.length==1){
                if(positional>=2)throw new IllegalArgumentException(USAGE);
                key=positional++==0?"ping":"myping";value=pair[0];
            }else if(pair.length==2){key=pair[0].toLowerCase(Locale.ROOT);value=pair[1];}
            else throw new IllegalArgumentException(USAGE);
            if(key.equals("botping"))key="ping";
            if(key.equals("combat_sprint_vertical"))key="sprint_vertical";
            if(!seen.add(key))throw new IllegalArgumentException("Duplicate setting: "+key);
            switch(key){
                case "preset": break; // Defaults were applied before every explicit option.
                case "ping": botPing=ping(value);break;
                case "myping": humanPing=ping(value);break;
                case "reach": overrides.setProperty("attack_reach",Double.toString(reach(value)));break;
                case "cps": {
                    int cps;
                    try{cps=Integer.parseInt(value);}catch(NumberFormatException e){throw new IllegalArgumentException("CPS must be a whole number from 1 to 30");}
                    if(cps<1||cps>30)throw new IllegalArgumentException("CPS must be 1 through 30");
                    overrides.setProperty("click_cps",Integer.toString(cps));
                    break;
                }
                case "map": overrides.setProperty("sumo_map",RecordedSumoMap.load(value).name);break;
                case "diameter": overrides.setProperty("sumo_diameter",Integer.toString(sumoDiameter(value)));break;
                case "aiminput":
                    if(!value.equals("mouse") && !value.equals("ideal"))throw new IllegalArgumentException("Choose aiminput=mouse or aiminput=ideal");
                    overrides.setProperty("aim_input",value);break;
                case "aimdelay": overrides.setProperty("aim_delay_ms",Double.toString(bounded(value,0,500,"Aim delay (ms)")));break;
                case "aimnoise": overrides.setProperty("aim_noise_deg",Double.toString(bounded(value,0,10,"Aim noise (degrees)")));break;
                case "aimrate": {
                    double rate=bounded(value,0,1440,"Aim turn rate (degrees/second)");
                    if(rate!=0&&rate<30)throw new IllegalArgumentException("Aim rate must be 0 (unlimited), or 30–1440 degrees/second");
                    overrides.setProperty("aim_turn_rate_dps",Double.toString(rate));break;
                }
                case "tapdelay": overrides.setProperty("reaction_ms",Double.toString(bounded(value,0,1000,"W-tap observation delay (ms)")));break;
                case "kb":
                    if(!value.equals("vanilla"))throw new IllegalArgumentException("Use kb=vanilla, or explicit kb_horizontal/kb_vertical/hit_ticks/sprint_vertical settings");
                    overrides.setProperty("combat_kb_horizontal","1.0");
                    overrides.setProperty("combat_kb_vertical","1.0");
                    overrides.setProperty("combat_hit_ticks",Integer.toString(VANILLA_HIT_TICKS));
                    overrides.setProperty("combat_sprint_vertical","0.1");
                    overrides.setProperty(PracticePresets.KB_MODEL,"vanilla");
                    overrides.setProperty("hurt_window_ticks",Integer.toString(VANILLA_HIT_TICKS));
                    break;
                case "sprint_vertical":
                    overrides.setProperty("combat_sprint_vertical",Double.toString(bounded(value,0,1,"Sprint extra Y")));break;
                case "kb_horizontal": case "kb_vertical":
                    overrides.setProperty("combat_"+key,Double.toString(bounded(value,0,2,"Knockback multiplier")));break;
                case "hit_ticks":
                    int ticks;
                    try{ticks=Integer.parseInt(value);}catch(NumberFormatException e){throw new IllegalArgumentException("hit_ticks must be a whole number from 1 to 20");}
                    if(ticks<1||ticks>20)throw new IllegalArgumentException("hit_ticks must be 1–20; vanilla is 10");
                    overrides.setProperty("combat_hit_ticks",Integer.toString(ticks));
                    overrides.setProperty("hurt_window_ticks",Integer.toString(ticks));
                    break;
                default: throw new IllegalArgumentException("Unknown setting '"+key+"'. Use /help settings for all options.");
            }
        }
        if(seen.contains("kb")&&(seen.contains("kb_horizontal")||seen.contains("kb_vertical")||seen.contains("hit_ticks")||seen.contains("sprint_vertical")))
            throw new IllegalArgumentException("Use kb=vanilla alone to reset combat settings, or give explicit multipliers/delay");
        if(seen.contains("map"))overrides.remove("sumo_diameter");
        if(seen.contains("diameter"))overrides.remove("sumo_map");
        if(seen.contains("map")&&seen.contains("diameter"))throw new IllegalArgumentException("Choose map= or diameter= for a sumo match");
        String label=overrides.getProperty(PracticePresets.LABEL);
        if(label!=null&&!PracticePresets.combatMatches(label,overrides))overrides.remove(PracticePresets.LABEL);
        return new DuelSettings(mode,botPing,humanPing,overrides,seen.contains("myping"));
    }
    public static int sumoDiameter(String value){
        int size;
        try{size=Integer.parseInt(value);}catch(NumberFormatException e){throw new IllegalArgumentException("Choose the MMC 13-block map or an even diameter from 8 to 16");}
        if(size!=13 && (size<8||size>16||size%2!=0))throw new IllegalArgumentException("Choose the MMC 13-block map or an even diameter from 8 to 16");
        return size;
    }
    static int hurtWindowTicks(Properties p){
        String ticks=p.getProperty("hurt_window_ticks");
        return ticks==null?DEFAULT_HIT_TICKS:(int)Double.parseDouble(ticks);
    }
    public static double ping(String value){return bounded(value,0,500,"Ping (added RTT in ms)");}
    public static double reach(String value){return bounded(value,1,5,"Reach (blocks)");}
    public static double reach(Properties p){return reach(p.getProperty("attack_reach","3.0"));}
    private static double bounded(String value,double min,double max,String label){
        double number;
        try{number=Double.parseDouble(value);}catch(NumberFormatException e){throw new IllegalArgumentException(label+" must be a number from "+min+" to "+max);}
        if(!Double.isFinite(number)||number<min||number>max)throw new IllegalArgumentException(label+" must be "+min+" through "+max);
        return number;
    }
}
