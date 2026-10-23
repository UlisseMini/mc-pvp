package lab;
import java.lang.reflect.Array;
import java.util.*;
public final class Json {
    public static String encode(Object value) {
        if (value == null) return "null";
        if (value instanceof Number) return Double.isFinite(((Number)value).doubleValue()) ? value.toString() : "null";
        if (value instanceof Boolean) return value.toString();
        if (value instanceof Map) {
            List<String> entries = new ArrayList<>();
            for (Object o : ((Map<?,?>)value).entrySet()) { Map.Entry<?,?> e=(Map.Entry<?,?>)o; entries.add(encode(e.getKey().toString())+":"+encode(e.getValue())); }
            return "{"+String.join(",",entries)+"}";
        }
        if (value instanceof Iterable) { List<String> entries=new ArrayList<>(); for(Object o:(Iterable<?>)value)entries.add(encode(o)); return "["+String.join(",",entries)+"]"; }
        if (value.getClass().isArray()) { List<String> entries=new ArrayList<>(); for(int i=0;i<Array.getLength(value);i++)entries.add(encode(Array.get(value,i))); return "["+String.join(",",entries)+"]"; }
        return "\""+value.toString().replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n").replace("\r","\\r").replace("\t","\\t")+"\"";
    }
}
