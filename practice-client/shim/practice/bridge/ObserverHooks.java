package practice.bridge;

/** Calls injected at stable game boundaries; observers never cancel game work. */
public final class ObserverHooks {
    public interface Sink {
        void connected(Object manager,Object context);
        void wireBefore(Object context,Object buffer);
        void wireAfter(Object packets);
        void wireOut(Object context,Object packet,Object buffer);
        void apply(Object packet,boolean after);
        void input(String kind,boolean after);
    }
    public static volatile Sink sink;
    public static volatile long errors;
    public static void connected(Object m,Object c){try{if(sink!=null)sink.connected(m,c);}catch(Exception|LinkageError e){errors++;}}
    public static void wireBefore(Object c,Object b){try{if(sink!=null)sink.wireBefore(c,b);}catch(Exception|LinkageError e){errors++;}}
    public static void wireAfter(Object p){try{if(sink!=null)sink.wireAfter(p);}catch(Exception|LinkageError e){errors++;}}
    public static void wireOut(Object c,Object p,Object b){try{if(sink!=null)sink.wireOut(c,p,b);}catch(Exception|LinkageError e){errors++;}}
    public static void apply(Object p,boolean a){try{if(sink!=null)sink.apply(p,a);}catch(Exception|LinkageError e){errors++;}}
    public static void input(String k,boolean a){try{if(sink!=null)sink.input(k,a);}catch(Exception|LinkageError e){errors++;}}
}
