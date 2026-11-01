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
        double pa=0,pb=0;
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
            if(key.equals("myping"))throw new IllegalArgumentException("Use a.ping= and b.ping= for bot matches");
            if(key.equals("botping"))key="ping";
            if(dest.put(key,pair[1])!=null)throw new IllegalArgumentException("Duplicate setting: "+pair[0]);
        }
        Map<String,String> ca=new LinkedHashMap<>(common),cb=new LinkedHashMap<>(common);ca.putAll(a);cb.putAll(b);
        return new BotMatchSettings(side(ca,pa),side(cb,pb),request);
    }
    private static DuelSettings side(Map<String,String> values,double ping){
        String[] args=new String[values.size()];int i=0;
        for(Map.Entry<String,String> entry:values.entrySet())args[i++]=entry.getKey()+"="+entry.getValue();
        return DuelSettings.parse(args,0,"boxing",ping,0,new Properties());
    }
}
