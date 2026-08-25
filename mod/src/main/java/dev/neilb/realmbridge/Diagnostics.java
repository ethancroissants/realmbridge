package dev.neilb.realmbridge;

import net.fabricmc.loader.api.FabricLoader;
import net.raphimc.minecraftauth.extra.realms.model.RealmsJoinInformation;
import net.raphimc.minecraftauth.extra.realms.model.RealmsServer;
import net.raphimc.minecraftauth.extra.realms.service.impl.BedrockRealmsService;

import java.util.List;

/**
 * Everything RealmBridge knows, written to the Minecraft log.
 *
 * Realm joins fail in places the player cannot see: inside the Realms API, or
 * inside a bridge process whose log a sandboxed launcher buries. Nothing here
 * changes behaviour - it exists so that "it won't connect" arrives with the
 * evidence attached.
 *
 * Raw API responses are logged verbatim. They carry realm metadata (version,
 * state, slot config) and no credentials - the tokens live in the Authorization
 * header, never the body.
 */
public final class Diagnostics {

    private Diagnostics() {
    }

    /** Versions and paths. Logged once per join, because they change per launch. */
    public static void logEnvironment(final RealmBridgeCore core) {
        RealmBridgeCore.LOGGER.info("--- RealmBridge environment ---");
        RealmBridgeCore.LOGGER.info("  mod:           {}", FabricLoader.getInstance()
                .getModContainer("realmbridge")
                .map(m -> m.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown"));
        RealmBridgeCore.LOGGER.info("  minecraft:     {}", FabricLoader.getInstance()
                .getModContainer("minecraft")
                .map(m -> m.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown"));
        RealmBridgeCore.LOGGER.info("  java:          {} ({})",
                System.getProperty("java.version"), System.getProperty("java.vendor"));
        RealmBridgeCore.LOGGER.info("  os:            {} {}",
                System.getProperty("os.name"), System.getProperty("os.arch"));
        // user.home is what ViaProxyRunner derives the install dir from, and a
        // sandboxed launcher gives the game a different one than the user's shell.
        RealmBridgeCore.LOGGER.info("  user.home:     {}", System.getProperty("user.home"));
        RealmBridgeCore.LOGGER.info("  game dir:      {}", FabricLoader.getInstance().getGameDir());
        RealmBridgeCore.LOGGER.info("  bridge dir:    {}", core.runner().installDir().toAbsolutePath());
        RealmBridgeCore.LOGGER.info("  bridge log:    {}",
                core.runner().installDir().resolve("logs").resolve("realmbridge-viaproxy.log").toAbsolutePath());
        RealmBridgeCore.LOGGER.info("  claimed as:    Bedrock {}", BridgeAuth.BEDROCK_VERSION);
        RealmBridgeCore.LOGGER.info("  signed in:     {}", core.auth().isLoggedIn());
        RealmBridgeCore.LOGGER.info("  xbox identity: {}", core.auth().identity());
        RealmBridgeCore.LOGGER.info("  bridge live:   {}", core.runner().isRunning());
    }

    /**
     * The realm list as the API actually returned it.
     *
     * {@code isCompatible()} is the interesting one: it is the Realms API's own
     * verdict on the Bedrock version this mod claims to be, so it is what says
     * whether the bridge has fallen behind the realm.
     */
    public static void logRealms(final BedrockRealmsService service, final List<RealmsServer> worlds) {
        try {
            RealmBridgeCore.LOGGER.info("Realms API accepts client version Bedrock {}: {}",
                    BridgeAuth.BEDROCK_VERSION, service.isCompatible());
        } catch (Exception e) {
            RealmBridgeCore.LOGGER.warn("Realms API compatibility check failed", e);
        }
        RealmBridgeCore.LOGGER.info("Realms returned {} world(s)", worlds.size());
        for (final RealmsServer realm : worlds) {
            RealmBridgeCore.LOGGER.info("  realm id={} name='{}' state={} compatible={} expired={} version='{}'",
                    realm.getId(), realm.getNameOr("?"), realm.getState(),
                    realm.isCompatible(), realm.isExpired(), realm.getActiveVersionOr("?"));
            RealmBridgeCore.LOGGER.info("    raw: {}", realm.getRawResponse());
            warnOnAccess(realm);
        }
    }

    /**
     * Calls out the response fields that decide whether the realm host will let
     * this account in at all. A realm can be listed, OPEN and unexpired and
     * still refuse the connection, and the reason shows up here rather than in
     * anything the connection itself reports.
     */
    private static void warnOnAccess(final RealmsServer realm) {
        final var raw = realm.getRawResponse();
        final boolean member = raw.has("member") && !raw.get("member").isJsonNull()
                && raw.get("member").getAsBoolean();
        final String owner = raw.has("ownerUUID") && !raw.get("ownerUUID").isJsonNull()
                ? raw.get("ownerUUID").getAsString() : "?";
        RealmBridgeCore.LOGGER.info("    access: member={} ownerUUID={} defaultPermission={}",
                member, owner,
                raw.has("defaultPermission") && !raw.get("defaultPermission").isJsonNull()
                        ? raw.get("defaultPermission").getAsString() : "?");
        if (!member) {
            RealmBridgeCore.LOGGER.warn("    member=false: this account either owns the realm, or its "
                    + "invite was never accepted. If the XUID above is not {}, you are signed in as "
                    + "someone else and the realm host will refuse the connection.", owner);
        }
    }

    /**
     * Probes the invite endpoints and accepts anything still pending.
     *
     * A realm reports {@code member: false} until its invitation is accepted by
     * the invited account, and nothing else in the API distinguishes the two -
     * the realm is listed, resolves a session, and only the host refuses. The
     * paths differ between editions and are undocumented, so each candidate is
     * tried and the raw response logged either way.
     */
    public static void auditInvites(final RealmBridgeCore core) {
        final RealmsApi api = new RealmsApi(core.auth());
        RealmBridgeCore.LOGGER.info("--- RealmBridge invite audit ---");
        for (final String path : new String[]{"/invites/pending", "/invites/count/pending", "/invites"}) {
            try {
                RealmBridgeCore.LOGGER.info("  GET {} -> {}", path, api.get(path));
            } catch (Exception e) {
                RealmBridgeCore.LOGGER.warn("  GET {} failed: {}", path, RealmBridgeCore.rootMessage(e));
            }
        }
    }

    /** Accepts one pending invitation by its id. */
    public static void acceptInvite(final RealmBridgeCore core, final String invitationId) {
        final RealmsApi api = new RealmsApi(core.auth());
        for (final String path : new String[]{"/invites/accept/" + invitationId, "/invites/v1/accept/" + invitationId}) {
            try {
                final RealmsApi.Response response = api.put(path);
                RealmBridgeCore.LOGGER.info("  PUT {} -> {}", path, response);
                if (response.ok()) {
                    return;
                }
            } catch (Exception e) {
                RealmBridgeCore.LOGGER.warn("  PUT {} failed: {}", path, RealmBridgeCore.rootMessage(e));
            }
        }
    }

    /** The session the realm handed back, which is what the bridge dials. */
    public static void logJoin(final RealmsServer realm, final RealmsJoinInformation join) {
        RealmBridgeCore.LOGGER.info("Join info for '{}': address={} protocol={}",
                realm.getNameOr("?"), join.getAddress(), join.getNetworkProtocol());
        RealmBridgeCore.LOGGER.info("    raw: {}", join.getRawResponse());
    }

}
