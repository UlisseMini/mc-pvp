package practice.smoke;
import java.lang.instrument.Instrumentation;
/** Keep pinned legacy logging hardened, without replacing any OpenGL calls. */
public final class ReplayGraphicsAgent {
    public static void premain(String ignored,Instrumentation i){i.addTransformer(new security.LegacyLoggingTransformer());}
}
