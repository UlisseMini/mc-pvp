package practice.bridge;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.zip.ZipFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

public final class SneakAnimationCheck {
    private static final String RENDERER = "net.minecraft.client.renderer.EntityRenderer";
    private static final String HOOK = "practice/bridge/OldSneakAnimation";

    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }

    private static void near(float actual, float expected) {
        check(Math.abs(actual - expected) < 1e-6f, "height " + actual + ", expected " + expected);
    }

    private static void curve() {
        OldSneakAnimation.Height height = new OldSneakAnimation.Height();
        height.reset(1.62f);
        near(height.render(.5f), 1.62f);
        height.tick(1.54f);
        near(height.render(0), 1.62f);
        near(height.render(.5f), 1.58f);
        near(height.render(1), 1.54f);
        height.tick(1.54f);
        near(height.render(.5f), 1.54f);
        height.tick(1.62f);
        near(height.render(0), 1.54f);
        near(height.render(.5f), 1.564f);
        near(height.render(1), 1.588f);
        height.tick(1.62f);
        near(height.render(1), 1.6072f);
        height.tick(1.54f);
        near(height.render(0), 1.6072f);
        near(height.render(1), 1.54f);
        height.reset(1.62f);
        near(height.render(0), 1.62f);
        near(OldSneakAnimation.eyeHeight(new Object(), .2f, .5f), .2f);
    }

    private static void verify(String jar, String entry, String camera, String directions, String eyeName)
            throws Exception {
        byte[] bytes;
        try (ZipFile zip = new ZipFile(jar); InputStream in = zip.getInputStream(zip.getEntry(entry))) {
            ByteArrayOutputStream data = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            for (int n; (n = in.read(buffer)) != -1;) data.write(buffer, 0, n);
            bytes = data.toByteArray();
        }
        ClassNode renderer = new ClassNode();
        new ClassReader(new PracticeTransformer().transform(entry, RENDERER, bytes)).accept(renderer, 0);
        int cameraHooks = 0, directionHooks = 0;
        for (MethodNode method : renderer.methods) {
            for (AbstractInsnNode insn : method.instructions.toArray()) {
                if (!(insn instanceof MethodInsnNode) || !HOOK.equals(((MethodInsnNode) insn).owner)) continue;
                MethodInsnNode call = (MethodInsnNode) insn;
                check("eyeHeight".equals(call.name) && "(Ljava/lang/Object;FF)F".equals(call.desc), "bad hook signature");
                if (camera.equals(method.name) && "(F)V".equals(method.desc)) cameraHooks++;
                else if (directions.equals(method.name) && "(F)V".equals(method.desc)) directionHooks++;
                else throw new AssertionError("sneak hook outside camera rendering: " + method.name + method.desc);
                AbstractInsnNode tick = insn.getPrevious(), original = tick.getPrevious();
                check(tick instanceof VarInsnNode && tick.getOpcode() == Opcodes.FLOAD &&
                        ((VarInsnNode) tick).var == 1, "must use current partial tick");
                check(original instanceof MethodInsnNode && eyeName.equals(((MethodInsnNode) original).name)
                        && "()F".equals(((MethodInsnNode) original).desc), "must retain vanilla eye read");
                check(original.getPrevious().getOpcode() == Opcodes.DUP, "must pass the eye-height receiver");
            }
        }
        check(cameraHooks == 1 && directionHooks == 1, "missing camera or debug direction hook");
    }

    public static void main(String[] args) throws Exception {
        curve();
        verify(args[0], "bfk.class", "f", "h", "aS");
        verify(args[1], RENDERER.replace('.', '/') + ".class", "func_78467_g", "func_175067_i", "func_70047_e");
        System.out.println("PASS 1.7 sneak interpolation, release, re-sneak, and camera-only hooks");
    }
}
