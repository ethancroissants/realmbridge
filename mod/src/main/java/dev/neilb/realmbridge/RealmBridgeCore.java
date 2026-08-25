package dev.neilb.realmbridge;

import net.minecraft.network.chat.Component;
import net.raphimc.minecraftauth.extra.realms.service.impl.BedrockRealmsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Shared services: auth, bridge process, background worker.
 *
 * A singleton because the entry points into RealmBridge are scattered - the
 * client initializer, the Realms screen button (a mixin, which cannot be handed
 * an instance) and the chat commands all need the same auth state and the same
 * bridge process.
 */
public final class RealmBridgeCore {

    public static final Logger LOGGER = LoggerFactory.getLogger("realmbridge");

    private static final RealmBridgeCore INSTANCE = new RealmBridgeCore();

    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        final Thread t = new Thread(r, "RealmBridge-Worker");
        t.setDaemon(true);
        return t;
    });
    private final BridgeAuth auth = new BridgeAuth();
    private final ViaProxyRunner runner = new ViaProxyRunner();

    private RealmBridgeCore() {
        Runtime.getRuntime().addShutdownHook(new Thread(this.runner::stop, "RealmBridge-Shutdown"));
    }

    public static RealmBridgeCore get() {
        return INSTANCE;
    }

    public BridgeAuth auth() {
        return this.auth;
    }

    public ViaProxyRunner runner() {
        return this.runner;
    }

    /** Realms service; runs the device-code flow if not signed in (call off-thread). */
    public BedrockRealmsService realms() throws Exception {
        return this.auth.realmsService(code -> LOGGER.info("Device code: {} at {}", code.getUserCode(), code.getVerificationUri()));
    }

    public void async(final ThrowingRunnable task, final Consumer<Throwable> onError) {
        this.worker.submit(() -> {
            try {
                task.run();
            } catch (Throwable e) {
                LOGGER.error("RealmBridge task failed", e);
                onError.accept(e);
            }
        });
    }

    /** Logs a status line; the fallback when no screen is listening. */
    public static void logStatus(final Component message) {
        LOGGER.info("[RealmBridge] {}", message.getString());
    }

    /**
     * The innermost cause's message. Failures here are almost always wrapped -
     * an IOException inside a CompletionException inside a RuntimeException -
     * and only the innermost one says anything the player can act on.
     */
    public static String rootMessage(Throwable e) {
        while (e.getCause() != null) {
            e = e.getCause();
        }
        final String message = e.getMessage();
        return message == null ? e.getClass().getSimpleName() : message;
    }

    @FunctionalInterface
    public interface ThrowingRunnable {
        void run() throws Exception;
    }

}
