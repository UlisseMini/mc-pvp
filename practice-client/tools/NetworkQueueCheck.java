import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.ZipFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import practice.bridge.PracticeTransformer;

/** Both names of the native drain must hold the enqueue lock through dispatch. */
public final class NetworkQueueCheck {
    private static final String MANAGER = "net.minecraft.network.NetworkManager";
    private static final String LOCK = "java/util/concurrent/locks/ReentrantReadWriteLock";

    private static MethodNode drain(byte[] bytes, String name) {
        ClassNode type = new ClassNode();
        new ClassReader(bytes).accept(type, 0);
        for (MethodNode method : type.methods) {
            if (method.name.equals(name) && method.desc.equals("()V")) return method;
        }
        throw new AssertionError("Native outbound drain missing: " + name);
    }

    private static void verify(Path jar, String entry, String method) throws Exception {
        byte[] original;
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            original = PlayerLabelsCheck.read(zip.getInputStream(zip.getEntry(entry)));
        }
        byte[] transformed = new PracticeTransformer().transform(entry.substring(0, entry.length() - 6), MANAGER, original);
        MethodNode patched = drain(transformed, method);
        List<String> lockCalls = new ArrayList<>();
        for (AbstractInsnNode instruction : patched.instructions.toArray()) {
            if (!(instruction instanceof MethodInsnNode)) continue;
            MethodInsnNode call = (MethodInsnNode) instruction;
            if (call.owner.equals(LOCK) && call.name.equals("writeLock") && call.desc.equals("()L" + LOCK + "$WriteLock;")) {
                lockCalls.add(call.name);
                call.name = "readLock";
                call.desc = "()L" + LOCK + "$ReadLock;";
            } else if (call.owner.equals(LOCK + "$WriteLock")) {
                lockCalls.add(call.name);
                call.owner = LOCK + "$ReadLock";
            }
        }
        PlayerLabelsCheck.check(lockCalls.equals(Arrays.asList("writeLock", "lock", "writeLock", "unlock", "writeLock", "unlock")),
                "Drain must use the write lock on both normal and exceptional exits: " + lockCalls);
        PlayerLabelsCheck.check(Arrays.equals(PlayerLabelsCheck.methodBytes(drain(original, method)), PlayerLabelsCheck.methodBytes(patched)),
                "Outbound drain changed beyond its lock");
    }

    public static void main(String[] args) throws Exception {
        verify(Paths.get(args[0]), "ek.class", "m");
        verify(Paths.get(args[1]), MANAGER.replace('.', '/') + ".class", "func_150733_h");
        System.out.println("PASS native outbound queue: exclusive drain with both unlock paths preserved");
    }
}
