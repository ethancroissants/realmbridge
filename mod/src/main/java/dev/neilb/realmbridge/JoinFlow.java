package dev.neilb.realmbridge;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.Component;
import net.raphimc.minecraftauth.extra.realms.model.RealmsJoinInformation;
import net.raphimc.minecraftauth.extra.realms.model.RealmsServer;

import java.util.List;
import java.util.function.Consumer;

/**
 * The join sequence, shared by the screen and the chat commands.
 *
 * A realm that was asleep answers the Realms API before its host has registered
 * with the NetherNet signaling service, and connecting in that window fails with
 * SIGNAL_CONNECT_ERROR. So the address is polled until it settles, and a failed
 * connection is retried automatically a couple of times.
 */
public final class JoinFlow {

    private static final int MAX_ATTEMPTS = 2;
    private static final long RETRY_BACKOFF_MS = 15_000;
    private static final long RETRY_WINDOW_MS = 120_000;

    private static volatile RealmsServer realm;
    private static volatile Screen parentScreen;
    private static volatile long attemptedAt;
    private static volatile int attempt;

    private JoinFlow() {
    }

    public static void start(final RealmBridgeCore core, final RealmsServer target,
                             final Screen parent, final Consumer<Component> status) {
        realm = target;
        parentScreen = parent;
        attempt = 1;
        run(core, status);
    }

    private static void run(final RealmBridgeCore core, final Consumer<Component> status) {
        final RealmsServer target = realm;
        core.async(() -> {
            Diagnostics.logEnvironment(core);
            core.runner().ensureInstalled(status);
            final String name = target.getNameOr("realm");
            status.accept(attempt > 1
                    ? Component.translatable("realmbridge.status.waking_attempt", name, attempt)
                    : Component.translatable("realmbridge.status.waking", name));
            final String address = resolveOnce(core, target);
            // Hand ViaProxy the account only now: resolving the realm above forces
            // the Microsoft tokens to refresh, and stale ones make the realm host
            // reject the connection with CONNECTERROR 37 (identity verification).
            final int accountIndex = core.runner().ensureAccount(core.auth().serialized());
            core.runner().setRealmFilter(target.getName());
            status.accept(Component.translatable("realmbridge.status.starting"));
            core.runner().start(address, accountIndex);
            attemptedAt = System.currentTimeMillis();

            final Minecraft minecraft = Minecraft.getInstance();
            minecraft.execute(() -> ConnectScreen.startConnecting(
                    parentScreen, minecraft,
                    ServerAddress.parseString(ViaProxyRunner.BIND),
                    new ServerData(name + " (Bedrock)", ViaProxyRunner.BIND, ServerData.Type.OTHER),
                    false, null));
        }, e -> status.accept(Component.translatable("realmbridge.status.failed",
                RealmBridgeCore.rootMessage(e))));
    }

    /**
     * Resolves the realm session exactly once.
     *
     * This used to poll joinWorld until the address stopped changing, to ride
     * out a realm that answers before its host has registered with signaling.
     * But every call is the client telling Realms "I am joining now", and a real
     * Bedrock client says that once. Between the poll loop and the bridge
     * plugin's own refresh we were saying it four or five times per attempt,
     * which is behaviour no real client produces.
     *
     * The plugin re-resolves immediately before the WebRTC connect anyway, so
     * this value only has to be good enough to decide whether a running bridge
     * can be reused.
     */
    private static String resolveOnce(final RealmBridgeCore core, final RealmsServer target) throws Exception {
        final RealmsJoinInformation join = core.realms().joinWorld(target);
        Diagnostics.logJoin(target, join);
        return join.getAddress();
    }

    /**
     * Dumps why the bridge refused the connection into the game log and chat.
     *
     * The client only ever sees ViaProxy's generic "Could not connect to the
     * backend server!", while the reason - a NetherNet CONNECTERROR code, say -
     * is in the bridge's own log, which a sandboxed launcher can bury somewhere
     * the player cannot reach. So it comes to them instead.
     */
    private static void reportFailure(final RealmBridgeCore core) {
        final List<String> reasons = core.runner().failureLines();
        if (reasons.isEmpty()) {
            RealmBridgeCore.LOGGER.warn("Realm connection failed; no reason found in {}",
                    core.runner().installDir().resolve("logs"));
            return;
        }
        RealmBridgeCore.LOGGER.warn("Realm connection refused by the bridge. Reason lines follow "
                + "(full log: {}):", core.runner().installDir().resolve("logs"));
        for (final String line : reasons) {
            RealmBridgeCore.LOGGER.warn("  {}", line);
        }
        final String last = reasons.get(reasons.size() - 1);
        final Minecraft minecraft = Minecraft.getInstance();
        minecraft.execute(() -> minecraft.gui.hud.getChat().addClientSystemMessage(
                Component.literal("[RealmBridge] ").append(
                        Component.translatable("realmbridge.status.refused", abbreviate(last)))));
    }

    /** Chat lines are not log lines; keep it to the part that identifies the failure. */
    private static String abbreviate(final String line) {
        final int marker = line.indexOf("CONNECTERROR");
        final String trimmed = marker >= 0 ? line.substring(marker) : line.trim();
        return trimmed.length() <= 120 ? trimmed : trimmed.substring(0, 117) + "...";
    }

    /** Called when a disconnect screen appears; retries a failed realm connection. */
    public static boolean retryIfOurs(final RealmBridgeCore core) {
        if (realm == null) return false;
        reportFailure(core);
        if (attempt >= MAX_ATTEMPTS) return false;
        if (System.currentTimeMillis() - attemptedAt > RETRY_WINDOW_MS) return false;
        attempt++;
        RealmBridgeCore.LOGGER.info("Realm connection failed, retrying ({}/{}) after a short wait",
                attempt, MAX_ATTEMPTS);
        core.async(() -> {
            // Back off: hammering a realm that just refused us makes it refuse harder.
            Thread.sleep(RETRY_BACKOFF_MS);
            run(core, RealmBridgeCore::logStatus);
        }, e -> RealmBridgeCore.LOGGER.warn("Retry failed", e));
        return true;
    }

    /** Stops the retry logic from firing for unrelated disconnects. */
    public static void clear() {
        realm = null;
    }

}
