package lab;

import java.io.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.objectweb.asm.Type;

/** Version-pinned reflection using data-only MCP mappings; no downloaded code. */
public final class R {
    static final Properties MAP = new Properties();
    static final Map<String, Field> FIELDS = new ConcurrentHashMap<>();
    static final Map<String, Method> METHODS = new ConcurrentHashMap<>();
    public static final Path ROOT = Paths.get(System.getProperty("lab.root")).toAbsolutePath();
    static {
        try (InputStream in = R.class.getResourceAsStream("mappings.properties")) { MAP.load(in); }
        catch (IOException e) { throw new ExceptionInInitializerError(e); }
    }
    public static Class<?> cls(String name) {
        try { return Class.forName(MAP.getProperty("C." + name.replace('.', '/'), name).replace('/', '.')); }
        catch (Exception e) { throw new RuntimeException(name, e); }
    }
    public static Object get(Object object, String name) {
        try { return field(object instanceof Class ? (Class<?>) object : object.getClass(), name).get(object instanceof Class ? null : object); }
        catch (Exception e) { throw new RuntimeException("get " + name, e); }
    }
    public static void set(Object object, String name, Object value) {
        try { field(object.getClass(), name).set(object, value); }
        catch (Exception e) { throw new RuntimeException("set " + name, e); }
    }
    public static double num(Object object, String name) { return ((Number) get(object, name)).doubleValue(); }
    private static Field field(Class<?> type, String name) {
        String key = type.getName() + "#" + name;
        return FIELDS.computeIfAbsent(key, k -> {
            for (Class<?> c = type; c != null; c = c.getSuperclass()) for (Field f : c.getDeclaredFields()) {
                String mapped = MAP.getProperty("F." + c.getName().replace('.', '/') + "." + f.getName(), f.getName());
                if (name.equals(mapped) || name.equals(f.getName())) { f.setAccessible(true); return f; }
            }
            throw new IllegalArgumentException(key);
        });
    }
    public static Object call(Object object, String name, Object... args) {
        Class<?> type = object instanceof Class ? (Class<?>) object : object.getClass();
        StringBuilder key = new StringBuilder(type.getName()).append('#').append(name);
        for (Object a : args) key.append(':').append(a == null ? "null" : a.getClass().getName());
        Method m = METHODS.computeIfAbsent(key.toString(), k -> {
            for (Class<?> c = type; c != null; c = c.getSuperclass()) for (Method candidate : c.getDeclaredMethods()) {
                String mapped = MAP.getProperty("M." + c.getName().replace('.', '/') + "." + candidate.getName() + Type.getMethodDescriptor(candidate), candidate.getName());
                if ((mapped.equals(name) || candidate.getName().equals(name)) && accepts(candidate.getParameterTypes(), args)) {
                    candidate.setAccessible(true); return candidate;
                }
            }
            throw new IllegalArgumentException(k);
        });
        try { return m.invoke(object instanceof Class ? null : object, args); }
        catch (Exception e) { throw new RuntimeException("call " + key, e); }
    }
    private static boolean accepts(Class<?>[] types, Object[] args) {
        if (types.length != args.length) return false;
        for (int i = 0; i < types.length; i++) {
            if (args[i] == null) { if (types[i].isPrimitive()) return false; continue; }
            Class<?> t = types[i];
            if (t.isPrimitive()) t = t == int.class ? Integer.class : t == float.class ? Float.class : t == double.class ? Double.class : t == boolean.class ? Boolean.class : t == long.class ? Long.class : t == byte.class ? Byte.class : t;
            if (!t.isInstance(args[i])) return false;
        }
        return true;
    }
    public static Object construct(String type, Object... args) {
        for (Constructor<?> c : cls(type).getDeclaredConstructors()) if (accepts(c.getParameterTypes(), args)) {
            try { c.setAccessible(true); return c.newInstance(args); }
            catch (Exception e) { throw new RuntimeException(type, e); }
        }
        throw new IllegalArgumentException("No constructor: " + type);
    }
    public static Properties properties(Path path) {
        Properties p = new Properties();
        if (Files.exists(path)) try (Reader reader = Files.newBufferedReader(path)) { p.load(reader); }
        catch (IOException e) { throw new RuntimeException(e); }
        return p;
    }
    public static void writeProperties(Path path, Properties p) {
        try {
            Files.createDirectories(path.getParent()); Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
            try (Writer out = Files.newBufferedWriter(tmp)) { p.store(out, "PvP Lab controls"); }
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) { throw new RuntimeException(e); }
    }
    public static String name(Object player) { return (String) call(player, "getName"); }
}
