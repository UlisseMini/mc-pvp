package practice.smoke;

import java.lang.instrument.Instrumentation;
import io.github.headlesshq.headlessmc.lwjgl.agent.*;

/** Test harness only; never installed in the user's Minecraft mods folder. */
public final class HeadlessAgent {
    public static void premain(String args,Instrumentation instrumentation){
        System.setProperty("java.awt.headless","true");
        LwjglAgent.premain("",instrumentation);MacosMenuAgent.premain("",instrumentation);
    }
}
