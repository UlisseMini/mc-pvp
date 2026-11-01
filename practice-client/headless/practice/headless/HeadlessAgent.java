package practice.headless;

import java.lang.instrument.Instrumentation;
import io.github.headlesshq.headlessmc.lwjgl.agent.*;
import security.LegacyLoggingTransformer;

/** Graphics substitution and logging hardening only: all gameplay is in the shared mod. */
public final class HeadlessAgent {
    public static void premain(String args,Instrumentation instrumentation){
        System.setProperty("java.awt.headless","true");
        instrumentation.addTransformer(new LegacyLoggingTransformer());
        LwjglAgent.premain("",instrumentation);MacosMenuAgent.premain("",instrumentation);
    }
}
