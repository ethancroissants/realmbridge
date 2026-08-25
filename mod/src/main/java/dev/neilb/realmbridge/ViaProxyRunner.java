package dev.neilb.realmbridge;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import net.minecraft.network.chat.Component;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Manages the headless ViaProxy bridge. Fully self-bootstrapping: downloads
 * the patched ViaProxy + ViaProxyPlus plugin from the RealmBridge release if
 * missing, preseeds config, and injects the mod's Microsoft sign-in as the
 * ViaProxy account (no ViaProxy GUI, ever).
 */
public final class ViaProxyRunner {

    public static final String BIND = "127.0.0.1:25568";
    /** Bridge artifacts are published here by .github/workflows/build.yml. */
    private static final String RELEASE_BASE = "https://github.com/ethancroissants/realmbridge/releases/latest/download/";
    private static final String BEDROCK_ACCOUNT_TYPE = "net.raphimc.viaproxy.saves.impl.accounts.BedrockAccount";
    private static final String LOG_NAME = "realmbridge-viaproxy.log";
    private static final Pattern ANSI = Pattern.compile("\u001B\\[[;\\d]*[ -/]*[@-~]");
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private final Path installDir = Path.of(System.getProperty("user.home"), ".bedrock-realm-bridge");
    private volatile Process process;
    private volatile String currentTarget;

    public boolean isRunning() {
        final Process p = this.process;
        return p != null && p.isAlive();
    }

    /** Persist the realm-name filter for the ViaProxyPlus auto-refresh plugin. */
    public void setRealmFilter(final String realmName) throws IOException {
        final Path filter = this.installDir.resolve("plugins").resolve("ViaProxyPlus").resolve("realm.txt");
        Files.createDirectories(filter.getParent());
        Files.writeString(filter, realmName, StandardCharsets.UTF_8);
    }

    /** Absolute path of the bridge install, logs included. */
    public Path installDir() {
        return this.installDir;
    }

    /**
     * The tail of the bridge's own log.
     *
     * ViaProxy's kick reason never reaches the game - the client just sees
     * "Could not connect to the backend server!" - and under a sandboxed
     * launcher (Flatpak/Snap) this file lives in a mount namespace the user
     * cannot even reach from a shell. So the mod reads it back itself.
     */
    public List<String> logTail(final int maxLines) {
        final Path log = this.installDir.resolve("logs").resolve(LOG_NAME);
        try {
            if (!Files.exists(log)) {
                return List.of();
            }
            final List<String> lines = Files.readAllLines(log, StandardCharsets.UTF_8);
            return lines.size() <= maxLines ? lines : lines.subList(lines.size() - maxLines, lines.size());
        } catch (Exception e) {
            RealmBridgeCore.LOGGER.warn("Could not read {}", log, e);
            return List.of();
        }
    }

    /**
     * The lines from {@link #logTail} that explain a refused connection, most
     * recent last. Kept to the handful of markers ViaProxy and the NetherNet
     * stack use when a realm host turns us away.
     */
    public List<String> failureLines() {
        return this.logTail(400).stream()
                .filter(line -> line.contains("CONNECTERROR")
                        || line.contains("SIGNAL_CONNECT_ERROR")
                        || line.contains("PROXY KICK")
                        || line.contains("signaling rejection")
                        || line.contains("Realm auto-refresh failed"))
                .toList();
    }

    /** Downloads the bridge components from the RealmBridge release if missing. */
    public void ensureInstalled(final Consumer<Component> status) throws Exception {
        RealmBridgeCore.LOGGER.info("Bridge install directory: {}", this.installDir.toAbsolutePath());
        Files.createDirectories(this.installDir.resolve("plugins"));
        final Path jar = this.installDir.resolve("ViaProxy.jar");
        if (!Files.exists(jar)) {
            status.accept(Component.translatable("realmbridge.status.downloading"));
            this.download(RELEASE_BASE + "ViaProxy-patched.jar", jar);
        }
        final Path plugin = this.installDir.resolve("plugins").resolve("ViaProxyPlus.jar");
        if (!Files.exists(plugin)) {
            status.accept(Component.translatable("realmbridge.status.downloading_plugin"));
            this.download(RELEASE_BASE + "ViaProxyPlus.jar", plugin);
        }
        final Path vbConfig = this.installDir.resolve("viabedrock.yml");
        if (!Files.exists(vbConfig)) {
            Files.writeString(vbConfig, "enable-experimental-features: true\n", StandardCharsets.UTF_8);
        }
    }

    /**
     * Writes the mod's current sign-in into ViaProxy's account store and returns
     * the 0-based index of that Bedrock account (ViaProxy's
     * --minecraft-account-index is 0-indexed).
     *
     * The entry is rewritten on every launch, never reused. The mod and ViaProxy
     * are separate processes refreshing the same Microsoft sign-in, and MSA
     * rotates the refresh token on every use: whoever refreshes second is left
     * holding a revoked token. Writing this once at first sign-in - which is
     * what this used to do - forks the two chains and the realm host eventually
     * rejects ViaProxy's identity assertion with
     * {@code CONNECTERROR <id> 37} (ErrorCodeIdentityVerificationFailed), long
     * after everything appeared to work. The mod's copy is the live one, so it
     * wins every time.
     */
    public int ensureAccount(final JsonObject serializedAuth) throws IOException {
        final Path savesFile = this.installDir.resolve("saves.json");
        final JsonObject root = Files.exists(savesFile)
                ? GSON.fromJson(Files.readString(savesFile, StandardCharsets.UTF_8), JsonObject.class)
                : new JsonObject();
        final JsonArray accounts = root.has("accountsV4") ? root.getAsJsonArray("accountsV4") : new JsonArray();

        int index = -1;
        for (int i = 0; i < accounts.size(); i++) {
            final JsonObject account = accounts.get(i).getAsJsonObject();
            if (BEDROCK_ACCOUNT_TYPE.equals(account.has("accountType") ? account.get("accountType").getAsString() : "")) {
                index = i;
                break;
            }
        }
        if (serializedAuth == null) {
            if (index >= 0) {
                return index; // signed out in the mod; the stored account is all we have
            }
            throw new IllegalStateException("Not signed in");
        }

        final JsonObject entry = serializedAuth.deepCopy();
        entry.addProperty("accountType", BEDROCK_ACCOUNT_TYPE);
        if (index >= 0) {
            accounts.set(index, entry);
        } else {
            accounts.add(entry);
            index = accounts.size() - 1;
        }
        root.add("accountsV4", accounts);
        Files.writeString(savesFile, GSON.toJson(root), StandardCharsets.UTF_8);
        return index;
    }

    /** Launches the bridge against a realm's NetherNet session. */
    public synchronized void start(final String netherNetAddress, final int accountIndex) throws Exception {
        this.startTarget("nethernet-rpc://" + netherNetAddress, netherNetAddress, accountIndex);
    }

    /**
     * Launches the bridge against a plain Bedrock server instead of a realm.
     *
     * Only useful for telling two failures apart: a realm join exercises auth,
     * the Realms API, NetherNet signaling and the protocol translation all at
     * once, and when it fails there is nothing to say which of them broke. A
     * RakNet server uses everything except NetherNet, so if it connects, the
     * fault is in the realm path specifically.
     */
    public synchronized void startServer(final String hostAndPort, final int accountIndex) throws Exception {
        this.startTarget(hostAndPort, hostAndPort, accountIndex);
    }

    private synchronized void startTarget(final String targetAddress, final String targetKey,
                                          final int accountIndex) throws Exception {
        if (this.isRunning() && targetKey.equals(this.currentTarget)) {
            // Already bridging this exact realm session: reuse it. Respawning would
            // abandon the established signaling session, and the realm host starts
            // refusing connections (CONNECTERROR) when those pile up.
            RealmBridgeCore.LOGGER.info("Reusing the running bridge for {}", targetKey);
            return;
        }
        if (this.isRunning()) {
            this.stop(); // different realm session - restart cleanly
            Thread.sleep(1000); // let it close its signaling session
        }
        // A bridge from a previous game session may still hold the port with a
        // dead realm target; it would make us report ready while joins time out.
        try (Socket probe = new Socket()) {
            probe.connect(new InetSocketAddress("127.0.0.1", 25568), 300);
            RealmBridgeCore.LOGGER.warn("Stale bridge holding {} - terminating it", BIND);
            killPortListeners();
            Thread.sleep(500);
        } catch (IOException ignored) {
            // port free - the normal case
        }
        final String javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Files.createDirectories(this.installDir.resolve("logs"));
        final ProcessBuilder builder = new ProcessBuilder(
                javaBin, "-jar", this.installDir.resolve("ViaProxy.jar").toString(), "cli",
                "--bind-address", BIND,
                "--target-address", targetAddress,
                "--target-version", "Bedrock " + BridgeAuth.BEDROCK_VERSION,
                "--auth-method", "ACCOUNT",
                "--minecraft-account-index", String.valueOf(accountIndex));
        builder.directory(this.installDir.toFile());
        builder.redirectErrorStream(true);
        RealmBridgeCore.LOGGER.info("Starting bridge: {}", String.join(" ", builder.command()));
        this.process = builder.start();
        this.currentTarget = targetKey;
        this.pumpOutput(this.process);

        final long deadline = System.currentTimeMillis() + 60_000;
        while (System.currentTimeMillis() < deadline) {
            if (!this.process.isAlive()) {
                throw new IllegalStateException("ViaProxy exited with code " + this.process.exitValue()
                        + " (see " + this.installDir.resolve("logs").resolve(LOG_NAME) + ")");
            }
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", 25568), 500);
                return;
            } catch (IOException ignored) {
                Thread.sleep(500);
            }
        }
        this.stop();
        throw new IllegalStateException("ViaProxy did not open " + BIND + " within 60s");
    }

    /**
     * Mirrors the bridge's console output into the Minecraft log, line by line,
     * as well as to its own file.
     *
     * Piping rather than {@code redirectOutput} is deliberate: under a sandboxed
     * launcher the log file sits in a mount namespace the player cannot open, so
     * the game log is the only copy they can actually read. Every line is worth
     * having - the interesting ones (a NetherNet CONNECTERROR, a signaling
     * rejection) are exactly the ones that never reach the client otherwise.
     */
    private void pumpOutput(final Process bridge) {
        final Path logFile = this.installDir.resolve("logs").resolve(LOG_NAME);
        final Thread pump = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                         new InputStreamReader(bridge.getInputStream(), StandardCharsets.UTF_8));
                 BufferedWriter file = Files.newBufferedWriter(logFile, StandardCharsets.UTF_8)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    // ViaProxy colours its console; the escapes are noise in a log file
                    // and make the reason lines painful to read when pasted anywhere.
                    line = ANSI.matcher(line).replaceAll("");
                    RealmBridgeCore.LOGGER.info("[bridge] {}", line);
                    file.write(line);
                    file.newLine();
                    file.flush(); // a crash must not cost us the last lines
                }
            } catch (IOException e) {
                RealmBridgeCore.LOGGER.warn("Bridge output stream ended", e);
            }
            RealmBridgeCore.LOGGER.info("[bridge] process ended with exit code {}",
                    bridge.isAlive() ? "(still running)" : String.valueOf(bridge.exitValue()));
        }, "RealmBridge-BridgeLog");
        pump.setDaemon(true);
        pump.start();
    }

    /**
     * Terminates processes *listening* on the bridge port. Only listeners are
     * considered: the game client also holds a socket to this port while it is
     * connected, and killing that kills Minecraft itself. Our own process is
     * skipped as a second safety net.
     */
    private static void killPortListeners() {
        if (System.getProperty("os.name", "").toLowerCase().contains("win")) {
            return; // lsof is unix-only; a stale bridge is reported instead
        }
        try {
            final Process lsof = new ProcessBuilder("sh", "-c", "lsof -ti tcp:25568 -sTCP:LISTEN").start();
            final String output = new String(lsof.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            lsof.waitFor();
            final long ownPid = ProcessHandle.current().pid();
            for (final String token : output.split("\\s+")) {
                if (token.isBlank()) continue;
                final long pid;
                try {
                    pid = Long.parseLong(token.trim());
                } catch (NumberFormatException ignored) {
                    continue;
                }
                if (pid == ownPid) continue; // never the game
                ProcessHandle.of(pid).ifPresent(handle -> {
                    RealmBridgeCore.LOGGER.info("Terminating stale bridge process {}", pid);
                    handle.destroyForcibly();
                });
            }
        } catch (Exception e) {
            RealmBridgeCore.LOGGER.warn("Could not clean up the stale bridge", e);
        }
    }

    public synchronized void stop() {
        final Process p = this.process;
        if (p != null) {
            p.destroy(); // SIGTERM: lets it close the signaling session cleanly
            this.process = null;
            this.currentTarget = null;
        }
    }

    private void download(final String url, final Path target) throws Exception {
        final HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS).build();
        final HttpResponse<InputStream> response = client.send(
                HttpRequest.newBuilder(URI.create(url)).build(), HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            throw new IOException("Download failed (" + response.statusCode() + "): " + url);
        }
        final Path tmp = target.resolveSibling(target.getFileName() + ".part");
        try (InputStream in = response.body()) {
            Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
        }
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
    }

}
