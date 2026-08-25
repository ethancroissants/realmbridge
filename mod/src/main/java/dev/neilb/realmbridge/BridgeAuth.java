package dev.neilb.realmbridge;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.fabricmc.loader.api.FabricLoader;
import net.lenni0451.commons.httpclient.HttpClient;
import net.raphimc.minecraftauth.bedrock.BedrockAuthManager;
import net.raphimc.minecraftauth.MinecraftAuth;
import net.raphimc.minecraftauth.extra.realms.service.impl.BedrockRealmsService;
import net.raphimc.minecraftauth.msa.service.impl.DeviceCodeMsaAuthService;
import net.raphimc.minecraftauth.msa.model.MsaDeviceCode;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * Microsoft/Xbox auth + Bedrock Realms API access for the bridge.
 * Tokens persist in config/realmbridge/auth.json and refresh automatically.
 */
public final class BridgeAuth {

    /**
     * Bedrock game version this client claims to be.
     *
     * It is not cosmetic. MinecraftAuth sends it as {@code device.gameVersion}
     * when starting a session with Minecraft's authorization service, so it ends
     * up baked into the identity tokens that service mints - including the
     * multiplayer token a realm host authenticates us with. It must therefore be
     * a real Bedrock version, and the same one ViaBedrock speaks.
     */
    public static final String BEDROCK_VERSION = "1.26.30";

    private static final Gson GSON = new Gson();
    private final HttpClient httpClient = MinecraftAuth.createHttpClient("RealmBridge");
    private final Path authFile = FabricLoader.getInstance().getConfigDir().resolve("realmbridge").resolve("auth.json");
    /** Which game version the stored tokens were minted under. */
    private final Path versionFile = this.authFile.resolveSibling("auth-version.txt");
    private volatile BedrockAuthManager authManager;

    public boolean isLoggedIn() {
        this.discardTokensFromAnotherVersion();
        return this.authManager != null || Files.exists(this.authFile);
    }

    /** Loads persisted tokens or runs the device-code login (blocking; call off-thread). */
    public synchronized BedrockAuthManager authManager(final Consumer<MsaDeviceCode> deviceCodeCallback) throws Exception {
        if (this.authManager != null) {
            return this.authManager;
        }
        this.discardTokensFromAnotherVersion();
        if (Files.exists(this.authFile)) {
            final JsonObject json = GSON.fromJson(Files.readString(this.authFile, StandardCharsets.UTF_8), JsonObject.class);
            this.authManager = BedrockAuthManager.fromJson(this.httpClient, BEDROCK_VERSION, json);
        } else {
            this.authManager = BedrockAuthManager.create(this.httpClient, BEDROCK_VERSION)
                    .login(DeviceCodeMsaAuthService::new, deviceCodeCallback);
        }
        this.authManager.getChangeListeners().add(this::save);
        this.save();
        return this.authManager;
    }

    /**
     * Throws away tokens that were minted claiming a different game version.
     *
     * The version is embedded in the session the authorization service issues,
     * and every token derived from it inherits that context - so tokens minted
     * under a wrong or stale version stay wrong until the chain is rebuilt from
     * a fresh sign-in. Refreshing does not fix them.
     */
    private void discardTokensFromAnotherVersion() {
        try {
            if (!Files.exists(this.authFile)) {
                return;
            }
            final String mintedUnder = Files.exists(this.versionFile)
                    ? Files.readString(this.versionFile, StandardCharsets.UTF_8).trim()
                    : "(unknown)";
            if (BEDROCK_VERSION.equals(mintedUnder)) {
                return;
            }
            RealmBridgeCore.LOGGER.warn("Stored sign-in was minted as '{}', not '{}'; discarding it so the "
                    + "next sign-in mints tokens a realm host will accept", mintedUnder, BEDROCK_VERSION);
            Files.deleteIfExists(this.authFile);
            Files.deleteIfExists(this.versionFile);
        } catch (Exception e) {
            RealmBridgeCore.LOGGER.warn("Could not check the stored sign-in's game version", e);
        }
    }

    public BedrockRealmsService realmsService(final Consumer<MsaDeviceCode> deviceCodeCallback) throws Exception {
        final BedrockAuthManager manager = this.authManager(deviceCodeCallback);
        return new BedrockRealmsService(this.httpClient, BEDROCK_VERSION, manager.getRealmsXstsToken());
    }

    /** Serialized auth-manager state (same shape ViaProxy stores), or null if not signed in. */
    public synchronized JsonObject serialized() {
        if (this.authManager == null) {
            try {
                if (Files.exists(this.authFile)) {
                    return GSON.fromJson(Files.readString(this.authFile, StandardCharsets.UTF_8), JsonObject.class);
                }
            } catch (Exception e) {
                RealmBridgeCore.LOGGER.error("Failed to read persisted auth tokens", e);
            }
            return null;
        }
        return BedrockAuthManager.toJson(this.authManager);
    }

    /**
     * The {@code Authorization} header the Realms API expects, for calls
     * MinecraftAuth does not wrap.
     */
    public String realmsAuthorizationHeader() throws Exception {
        return this.authManager(code -> {
            throw new IllegalStateException("Not signed in");
        }).getRealmsXstsToken().getUpToDate().getAuthorizationHeader();
    }

    /**
     * Who the mod is actually signed in as, for the log.
     *
     * The Microsoft account behind the device-code sign-in has nothing to do
     * with the Java account the game is launched with, so "signed in" is not
     * the same as "signed in as the person who was invited to the realm".
     * The XUID here is directly comparable to a realm's {@code ownerUUID}.
     */
    public synchronized String identity() {
        if (this.authManager == null) {
            return "not loaded";
        }
        try {
            final var chain = this.authManager.getMinecraftCertificateChain().getUpToDate();
            return chain.getIdentityDisplayName() + " (xuid=" + chain.getIdentityXuid()
                    + ", uuid=" + chain.getIdentityUuid() + ")";
        } catch (Exception e) {
            return "unavailable: " + RealmBridgeCore.rootMessage(e);
        }
    }

    public synchronized void logout() throws Exception {
        this.authManager = null;
        Files.deleteIfExists(this.authFile);
        Files.deleteIfExists(this.versionFile);
    }

    private void save() {
        try {
            Files.createDirectories(this.authFile.getParent());
            Files.writeString(this.authFile, GSON.toJson(BedrockAuthManager.toJson(this.authManager)), StandardCharsets.UTF_8);
            Files.writeString(this.versionFile, BEDROCK_VERSION, StandardCharsets.UTF_8);
        } catch (Exception e) {
            RealmBridgeCore.LOGGER.error("Failed to persist auth tokens", e);
        }
    }

}
