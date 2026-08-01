import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Redirects the signaling library's strict {@code JsonObject.getAsJsonArray("params")}
 * call to VppSignalingCompat, which tolerates both the array and object frame shapes.
 * The stack effect is identical (receiver+arg -> static call with two args), so the
 * original frames stay valid.
 */
public final class AsmPatcher {

    private static final String COMPAT = "net/raphimc/viabedrock/experimental/VppSignalingCompat";

    public static void main(final String[] args) throws Exception {
        final Path classFile = Path.of(args[0]);
        final ClassReader reader = new ClassReader(Files.readAllBytes(classFile));
        final ClassWriter writer = new ClassWriter(0);
        final boolean[] patched = {false};

        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(final int access, final String name, final String descriptor,
                                             final String signature, final String[] exceptions) {
                final MethodVisitor delegate = super.visitMethod(access, name, descriptor, signature, exceptions);
                return new MethodVisitor(Opcodes.ASM9, delegate) {
                    @Override
                    public void visitMethodInsn(final int opcode, final String owner, final String methodName,
                                                final String methodDescriptor, final boolean isInterface) {
                        if (opcode == Opcodes.INVOKEVIRTUAL
                                && "com/google/gson/JsonObject".equals(owner)
                                && "getAsJsonArray".equals(methodName)
                                && "(Ljava/lang/String;)Lcom/google/gson/JsonArray;".equals(methodDescriptor)) {
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, COMPAT, "paramsAsArray",
                                    "(Lcom/google/gson/JsonObject;Ljava/lang/String;)Lcom/google/gson/JsonArray;", false);
                            patched[0] = true;
                            return;
                        }
                        super.visitMethodInsn(opcode, owner, methodName, methodDescriptor, isInterface);
                    }
                };
            }
        }, 0);

        if (!patched[0]) {
            System.err.println("AsmPatcher: target call not found in " + classFile);
            System.exit(1);
        }
        Files.write(classFile, writer.toByteArray());
        System.out.println("AsmPatcher: patched " + classFile.getFileName());
    }

}
