import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Redirects a static or virtual call inside a shipped class to a replacement in
 * the ViaProxyPlus experimental package. Each patch keeps the original stack
 * effect - same argument types, same return type - so the existing stack map
 * frames stay valid and the class needs no recomputation.
 *
 * Usage: {@code AsmPatcher <patch> <class file>}
 *
 *   signaling  JsonObject.getAsJsonArray -> VppSignalingCompat.paramsAsArray
 *              The signaling service now sends a bare message object where the
 *              library expects an array of them, so every inbound frame is
 *              dropped, the WebRTC answer included.
 *
 *   identity   NetherNetConstants.buildSignalConnectRequest
 *                -> VppIdentityAssertion.buildSignalConnectRequest
 *              Adds the a=identity assertion realm hosts require. Without it the
 *              host refuses the offer with CONNECTERROR <id> 37.
 */
public final class AsmPatcher {

    private static final String COMPAT = "net/raphimc/viabedrock/experimental/VppSignalingCompat";
    private static final String IDENTITY = "net/raphimc/viabedrock/experimental/VppIdentityAssertion";
    private static final String TAP = "net/raphimc/viabedrock/experimental/VppSignalingTap";

    public static void main(final String[] args) throws Exception {
        if (args.length != 2) {
            System.err.println("usage: AsmPatcher <signaling|identity|signalingtap> <class file>");
            System.exit(2);
        }
        final String patch = args[0];
        final Path classFile = Path.of(args[1]);

        final List<Redirect> redirects = switch (patch) {
            case "signaling" -> List.of(new Redirect(
                    Opcodes.INVOKEVIRTUAL, "com/google/gson/JsonObject", "getAsJsonArray",
                    "(Ljava/lang/String;)Lcom/google/gson/JsonArray;",
                    COMPAT, "paramsAsArray",
                    "(Lcom/google/gson/JsonObject;Ljava/lang/String;)Lcom/google/gson/JsonArray;"));
            case "identity" -> List.of(new Redirect(
                    Opcodes.INVOKESTATIC, "dev/kastle/netty/channel/nethernet/NetherNetConstants",
                    "buildSignalConnectRequest", "(JLjava/lang/String;)Ljava/lang/String;",
                    IDENTITY, "buildSignalConnectRequest", "(JLjava/lang/String;)Ljava/lang/String;"));
            // Both directions of the signaling socket, so a refusal can be read
            // in the context of the conversation that produced it.
            case "signalingtap" -> List.of(
                    new Redirect(Opcodes.INVOKEVIRTUAL,
                            "io/netty/handler/codec/http/websocketx/TextWebSocketFrame", "text",
                            "()Ljava/lang/String;", TAP, "inbound",
                            "(Lio/netty/handler/codec/http/websocketx/TextWebSocketFrame;)Ljava/lang/String;"),
                    new Redirect(Opcodes.INVOKEVIRTUAL, "com/google/gson/Gson", "toJson",
                            "(Lcom/google/gson/JsonElement;)Ljava/lang/String;", TAP, "outbound",
                            "(Lcom/google/gson/Gson;Lcom/google/gson/JsonElement;)Ljava/lang/String;"));
            default -> throw new IllegalArgumentException("unknown patch: " + patch);
        };

        final ClassReader reader = new ClassReader(Files.readAllBytes(classFile));
        final ClassWriter writer = new ClassWriter(0);
        final int[] hits = {0};

        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(final int access, final String name, final String descriptor,
                                             final String signature, final String[] exceptions) {
                final MethodVisitor delegate = super.visitMethod(access, name, descriptor, signature, exceptions);
                return new MethodVisitor(Opcodes.ASM9, delegate) {
                    @Override
                    public void visitMethodInsn(final int opcode, final String owner, final String methodName,
                                                final String methodDescriptor, final boolean isInterface) {
                        for (final Redirect redirect : redirects) {
                            if (opcode == redirect.opcode
                                    && redirect.owner.equals(owner)
                                    && redirect.name.equals(methodName)
                                    && redirect.descriptor.equals(methodDescriptor)) {
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, redirect.newOwner, redirect.newName,
                                        redirect.newDescriptor, false);
                                hits[0]++;
                                return;
                            }
                        }
                        super.visitMethodInsn(opcode, owner, methodName, methodDescriptor, isInterface);
                    }
                };
            }
        }, 0);

        if (hits[0] == 0) {
            System.err.println("AsmPatcher: '" + patch + "' target call not found in " + classFile);
            System.exit(1);
        }
        Files.write(classFile, writer.toByteArray());
        System.out.println("AsmPatcher: applied '" + patch + "' to " + classFile.getFileName()
                + " (" + hits[0] + " call site" + (hits[0] == 1 ? "" : "s") + ")");
    }

    private record Redirect(int opcode, String owner, String name, String descriptor,
                            String newOwner, String newName, String newDescriptor) {
    }

}
