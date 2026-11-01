package lab;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Small Java 8 equivalents used by the one shared controller implementation. */
public final class Compat {
    private Compat() {}
    @SafeVarargs public static <T> List<T> list(T... values) {
        for(T value:values)Objects.requireNonNull(value);
        return Collections.unmodifiableList(Arrays.asList(values.clone()));
    }
    @SafeVarargs public static <T> Set<T> set(T... values) {
        Set<T> out=new LinkedHashSet<>();
        for(T value:values)if(!out.add(Objects.requireNonNull(value)))throw new IllegalArgumentException("Duplicate value");
        return Collections.unmodifiableSet(out);
    }
    @SuppressWarnings("unchecked") public static <K,V> Map<K,V> map(Object... pairs) {
        if(pairs.length%2!=0)throw new IllegalArgumentException("Key/value pairs required");
        Map<K,V> out=new LinkedHashMap<>();
        for(int i=0;i<pairs.length;i+=2){K key=(K)Objects.requireNonNull(pairs[i]);V value=(V)Objects.requireNonNull(pairs[i+1]);
            if(out.put(key,value)!=null)throw new IllegalArgumentException("Duplicate key");}
        return Collections.unmodifiableMap(out);
    }
    public static void writeString(Path path,String value,OpenOption... options)throws IOException {
        Files.write(path,value.getBytes(StandardCharsets.UTF_8),options);
    }
}
