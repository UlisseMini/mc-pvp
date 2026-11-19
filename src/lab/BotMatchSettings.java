package lab;

import java.util.*;

/** Shared or per-side options for manual bot matches; does not modify policies. */
public final class BotMatchSettings {
    public final double pingA,pingB;
    public final String request;
    public final Properties a,b;
    private BotMatchSettings(DuelSettings first,DuelSettings second,String request){
        pingA=first.botPing;pingB=second.botPing;a=first.overrides;b=second.overrides;this.request=request;
    }
    public static BotMatchSettings parse(String[] args,int offset,String request){
        String preset=null;List<String> tokens=new ArrayList<>();
        for(int i=offset;i<args.length;i++){
            String[] pair=args[i].split("=",-1);
            if(pair.length==2&&pair[0].equalsIgnoreCase("preset")){
                if(preset!=null)throw new IllegalArgumentException("Duplicate setting: preset");
                preset=PracticePresets.normalize(pair[1]);
            }else tokens.add(args[i]);
        }
        args=tokens.toArray(new String[tokens.size()]);offset=0;
        double pa=preset==null?0:PracticePresets.defaultControllerRtt(preset);
        double pb=preset==null?0:PracticePresets.defaultOpponentRtt(preset);
        if(offset<args.length&&!args[offset].contains("="))pa=DuelSettings.ping(args[offset++]);
        if(offset<args.length&&!args[offset].contains("="))pb=DuelSettings.ping(args[offset++]);
        if(offset<args.length&&!args[offset].contains("="))request=args[offset++];
        Map<String,String> common=new LinkedHashMap<>(),a=new LinkedHashMap<>(),b=new LinkedHashMap<>();
        for(;offset<args.length;offset++){
            String[] pair=args[offset].split("=",-1);
            if(pair.length!=2)throw new IllegalArgumentException("Use setting=value, a.setting=value, or b.setting=value");
            String key=pair[0].toLowerCase(Locale.ROOT);Map<String,String> dest=common;
            if(key.startsWith("a.")){dest=a;key=key.substring(2);}
            else if(key.startsWith("b.")){dest=b;key=key.substring(2);}
            if(key.equals("combat_sprint_vertical"))key="sprint_vertical";
            if(dest!=common&&key.equals("diameter"))throw new IllegalArgumentException("The arena is shared. Use diameter= for both fighters");
            if(dest!=common&&key.equals("preset"))throw new IllegalArgumentException("Practice presets apply to the whole match; omit a./b. for preset");
            if(dest!=common&&(key.equals("kb")||key.equals("kb_horizontal")||key.equals("kb_vertical")||key.equals("hit_ticks")||key.equals("sprint_vertical")))
                throw new IllegalArgumentException("Combat settings apply to the whole match; omit a./b. for "+key);
            if(key.equals("myping"))throw new IllegalArgumentException("Use a.ping= and b.ping= for bot matches");
            if(key.equals("botping"))key="ping";
            if(dest.put(key,pair[1])!=null)throw new IllegalArgumentException("Duplicate setting: "+pair[0]);
        }
        Map<String,String> ca=new LinkedHashMap<>(common),cb=new LinkedHashMap<>(common);ca.putAll(a);cb.putAll(b);
        return new BotMatchSettings(side(ca,pa,preset),side(cb,pb,preset),request);
    }
    private static DuelSettings side(Map<String,String> values,double ping,String preset){
        String[] args=new String[values.size()];int i=0;
        for(Map.Entry<String,String> entry:values.entrySet())args[i++]=entry.getKey()+"="+entry.getValue();
        return DuelSettings.parse(args,0,"boxing",ping,0,preset==null?new Properties():PracticePresets.defaults(preset));
    }
}
