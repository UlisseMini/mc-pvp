package practice.core;
import java.util.*;

public final class JsonData {
    private JsonData(){}
    public static Map<String,Object> map(Object... pairs){
        Map<String,Object> m=new LinkedHashMap<>();for(int i=0;i<pairs.length;i+=2)m.put((String)pairs[i],pairs[i+1]);return m;
    }
    public static String encode(Object value){StringBuilder out=new StringBuilder();write(out,value);return out.toString();}
    private static void write(StringBuilder out,Object v){
        if(v==null){out.append("null");return;}
        if(v instanceof Number){out.append(Double.isFinite(((Number)v).doubleValue())?v:"null");return;}
        if(v instanceof Boolean){out.append(v);return;}
        if(v instanceof Map){out.append('{');boolean comma=false;for(Map.Entry<?,?> e:((Map<?,?>)v).entrySet()){
            if(comma)out.append(',');comma=true;write(out,e.getKey().toString());out.append(':');write(out,e.getValue());}out.append('}');return;}
        if(v instanceof Iterable){out.append('[');boolean comma=false;for(Object x:(Iterable<?>)v){if(comma)out.append(',');comma=true;write(out,x);}out.append(']');return;}
        out.append('"');for(char c:v.toString().toCharArray())switch(c){
            case '"':out.append("\\\"");break;case '\\':out.append("\\\\");break;
            case '\n':out.append("\\n");break;case '\r':out.append("\\r");break;case '\t':out.append("\\t");break;
            default:if(c<32)out.append(String.format(Locale.ROOT,"\\u%04x",(int)c));else out.append(c);
        }out.append('"');
    }
}
