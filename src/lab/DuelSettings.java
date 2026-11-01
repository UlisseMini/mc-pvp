package lab;

import java.util.*;

/** Per-duel overrides. Parsing never changes a shared policy or player preference. */
public final class DuelSettings {
    public static final String USAGE="/duel <bot> [mode] [bot RTT] [your RTT] [reach=3.0] [cps=10] [ping=100] [myping=80]";
    public final String mode;
    public final double botPing,humanPing;
    public final Properties overrides;
    private DuelSettings(String mode,double botPing,double humanPing,Properties overrides){
        this.mode=mode;this.botPing=botPing;this.humanPing=humanPing;this.overrides=overrides;
    }
    public static DuelSettings parse(String[] args,int offset,String mode,double botPing,double humanPing,Properties inherited){
        Properties overrides=new Properties();overrides.putAll(inherited);
        if(args.length>offset&&List.of("boxing","diamond","diamond_box","rod_diamond","sumo").contains(args[offset]))mode=args[offset++];
        Set<String> seen=new HashSet<>();int positional=0;
        for(int i=offset;i<args.length;i++){
            String[] pair=args[i].split("=",-1);String key,value;
            if(pair.length==1){
                if(positional>=2)throw new IllegalArgumentException(USAGE);
                key=positional++==0?"ping":"myping";value=pair[0];
            }else if(pair.length==2){key=pair[0].toLowerCase(Locale.ROOT);value=pair[1];}
            else throw new IllegalArgumentException(USAGE);
            if(key.equals("botping"))key="ping";
            if(!seen.add(key))throw new IllegalArgumentException("Duplicate setting: "+key);
            switch(key){
                case "ping" -> botPing=ping(value);
                case "myping" -> humanPing=ping(value);
                case "reach" -> overrides.setProperty("attack_reach",Double.toString(reach(value)));
                case "cps" -> {
                    int cps;
                    try{cps=Integer.parseInt(value);}catch(NumberFormatException e){throw new IllegalArgumentException("CPS must be a whole number from 1 to 30");}
                    if(cps<1||cps>30)throw new IllegalArgumentException("CPS must be 1 through 30");
                    overrides.setProperty("click_cps",Integer.toString(cps));
                }
                default -> throw new IllegalArgumentException("Unknown setting '"+key+"'. Use reach=, cps=, ping=, or myping=.");
            }
        }
        return new DuelSettings(mode,botPing,humanPing,overrides);
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
