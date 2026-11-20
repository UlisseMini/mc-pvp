import java.io.InputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.zip.ZipFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import practice.bridge.PracticeTransformer;

/** Pin the four item-use swing arguments in both vanilla renderer namespaces. */
public final class ItemUseAnimationCheck {
    private static final String RENDERER = "net.minecraft.client.renderer.ItemRenderer";
    private static final String ANIMATION = "practice/bridge/OldBlockAnimation";

    private static byte[] methodBytes(MethodNode method) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "Fixture", null, "java/lang/Object", null);
        method.accept(writer);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void verify(Path jar, String entry, String firstPerson, String transform) throws Exception {
        byte[] original;
        try (ZipFile zip = new ZipFile(jar.toFile()); InputStream stream = zip.getInputStream(zip.getEntry(entry))) {
            original = PlayerLabelsCheck.read(stream);
        }
        ClassNode before = new ClassNode(), after = new ClassNode();
        new ClassReader(original).accept(before, 0);
        new ClassReader(new PracticeTransformer().transform(entry, RENDERER, original)).accept(after, 0);
        check(before.methods.size() == after.methods.size(), "renderer methods changed");
        int hooks = 0, handHooks = 0;
        for (int index = 0; index < before.methods.size(); ++index) {
            MethodNode method = after.methods.get(index);
            for (AbstractInsnNode instruction : method.instructions.toArray()) {
                if (!(instruction instanceof MethodInsnNode)) continue;
                MethodInsnNode call = (MethodInsnNode) instruction;
                if ("practice/bridge/ObserverHooks".equals(call.owner) && "hideEmptyHand".equals(call.name)) {
                    check(firstPerson.equals(method.name) && "(F)V".equals(method.desc), "hand hook outside first-person rendering");
                    MethodInsnNode invisible = (MethodInsnNode) call.getPrevious();
                    check("(Z)Z".equals(call.desc) && "()Z".equals(invisible.desc)
                        && ("ax".equals(invisible.name) || "func_82150_aj".equals(invisible.name)), "hand hook does not check invisibility");
                    method.instructions.remove(call);
                    ++handHooks;
                    continue;
                }
                if (!ANIMATION.equals(call.owner)) continue;
                check(firstPerson.equals(method.name) && "(F)V".equals(method.desc), "hook outside first-person rendering");
                check("swing".equals(call.name) && "(FF)F".equals(call.desc), "wrong hook signature");
                AbstractInsnNode partial = call.getPrevious(), vanilla = partial.getPrevious();
                check(partial instanceof VarInsnNode && partial.getOpcode() == Opcodes.FLOAD &&
                    ((VarInsnNode) partial).var == 1, "hook did not load the render partial tick");
                check(vanilla.getOpcode() == Opcodes.FCONST_0, "hook replaced an existing swing");
                check(call.getNext() instanceof MethodInsnNode, "hook result does not feed the held item transform");
                MethodInsnNode pose = (MethodInsnNode) call.getNext();
                check(after.name.equals(pose.owner) && transform.equals(pose.name) && "(FF)V".equals(pose.desc),
                    "hook result does not feed the held item transform");
                method.instructions.remove(partial);
                method.instructions.remove(call);
                ++hooks;
            }
            check(Arrays.equals(methodBytes(before.methods.get(index)), methodBytes(method)),
                "unrelated renderer instructions changed in " + method.name);
        }
        check(hooks == 4, "expected four item-use poses, got " + hooks);
        check(handHooks == 1, "expected one offline hand hook, got " + handHooks);
    }

    public static void main(String[] args) throws Exception {
        verify(Paths.get(args[0]), "bfn.class", "a", "b");
        verify(Paths.get(args[1]), RENDERER.replace('.', '/') + ".class", "func_78440_a", "func_178096_b");
        if (args.length == 3) verify(Paths.get(args[2]), "bfn.class", "a", "b");
        System.out.println("PASS first-person swing for none, eat/drink, block and bow");
    }
}
