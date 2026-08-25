package net.raphimc.viabedrock.experimental;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import dev.kastle.netty.channel.nethernet.NetherNetConstants;
import net.raphimc.minecraftauth.bedrock.BedrockAuthManager;
import net.raphimc.viaproxy.ViaProxy;
import net.raphimc.viaproxy.saves.impl.accounts.Account;
import net.raphimc.viaproxy.saves.impl.accounts.BedrockAccount;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.Signature;
import java.security.interfaces.ECPrivateKey;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Attaches the {@code a=identity} assertion that realm hosts require on a
 * NetherNet connect offer.
 *
 * The signaling library sends libwebrtc's offer SDP verbatim, with no identity
 * attribute. Bedrock hosts now authenticate the peer from that attribute, and an
 * offer without one is refused during negotiation with
 * {@code CONNECTERROR <connectionId> 37} - {@code ErrorCodeIdentityNotAllowed},
 * "the remote identity token or its DTLS fingerprint assertion failed
 * validation". Signaling succeeds, the host answers, and the connection dies
 * there, which surfaces to the player as ViaProxy's generic "Could not connect
 * to the backend server!".
 *
 * The attribute is base64'd JSON:
 * <pre>
 * {"assertion":{"fingerprints":"&lt;detached JWS&gt;","token":"&lt;multiplayer token&gt;"},
 *  "idp":{"domain":"https://authorization.franchise.minecraft-services.net","protocol":"default"}}
 * </pre>
 * where {@code fingerprints} is a detached ES384 JWS over the offer's own DTLS
 * fingerprints, signed with the private half of the key the multiplayer token
 * carries in its {@code cpk} claim. That binds the authenticated account to this
 * specific peer connection, so the assertion cannot be replayed onto another.
 *
 * MinecraftAuth already holds both halves: the session key pair is secp384r1
 * (what ES384 wants) and the multiplayer token comes from
 * authorization.franchise.minecraft-services.net, the issuer the domain names.
 *
 * Every failure path returns the SDP untouched. A missing assertion is the
 * behaviour we are replacing, so falling back to it can only leave things as
 * they were - never worse.
 */
public final class VppIdentityAssertion {

    private static final Logger LOGGER = Logger.getLogger("ViaProxyPlus");
    private static final Gson GSON = new Gson();
    private static final Base64.Encoder B64_URL = Base64.getUrlEncoder().withoutPadding();
    private static final String IDP_DOMAIN = "https://authorization.franchise.minecraft-services.net";
    private static final String FINGERPRINT_PREFIX = "a=fingerprint:";
    private static final String IDENTITY_PREFIX = "a=identity:";

    private VppIdentityAssertion() {
    }

    /**
     * Drop-in replacement for {@link NetherNetConstants#buildSignalConnectRequest(long, String)}.
     * The descriptor matches the original exactly, so the call site can be
     * redirected here by a straight INVOKESTATIC rewrite.
     */
    public static String buildSignalConnectRequest(final long connectionId, final String sdp) {
        return NetherNetConstants.buildSignalConnectRequest(connectionId, withIdentity(sdp));
    }

    static String withIdentity(final String sdp) {
        try {
            if (sdp == null || sdp.contains(IDENTITY_PREFIX)) {
                return sdp;
            }
            final Account account = ViaProxy.getConfig().getAccount();
            if (!(account instanceof BedrockAccount)) {
                return sdp; // offline or Java account: nothing to assert with
            }
            final BedrockAuthManager auth = ((BedrockAccount) account).getAuthManager();
            final KeyPair sessionKeyPair = auth.getSessionKeyPair();
            if (!(sessionKeyPair.getPrivate() instanceof ECPrivateKey)) {
                LOGGER.warning("[VP+] session key is not an EC key; skipping identity assertion");
                return sdp;
            }
            final String token = auth.getMinecraftMultiplayerToken().getUpToDate().getToken();

            final List<String[]> fingerprints = parseFingerprints(sdp);
            if (fingerprints.isEmpty()) {
                LOGGER.warning("[VP+] offer SDP has no DTLS fingerprint; skipping identity assertion");
                return sdp;
            }

            final String assertion = signFingerprints(fingerprints, (ECPrivateKey) sessionKeyPair.getPrivate());
            final String attribute = encodeIdentity(assertion, token);
            LOGGER.log(Level.INFO, "[VP+] attached identity assertion ({0} fingerprint(s))", fingerprints.size());
            return insertSessionAttribute(sdp, IDENTITY_PREFIX + attribute);
        } catch (Throwable e) {
            LOGGER.log(Level.WARNING, "[VP+] could not build the identity assertion; "
                    + "connecting without one (realms will refuse with CONNECTERROR 37)", e);
            return sdp;
        }
    }

    /** {@code a=fingerprint:<algorithm> <digest>}, deduplicated and in SDP order. */
    private static List<String[]> parseFingerprints(final String sdp) {
        final Set<String> seen = new LinkedHashSet<>();
        final List<String[]> fingerprints = new ArrayList<>();
        for (final String rawLine : sdp.split("\r\n|\n|\r")) {
            final String line = rawLine.trim();
            if (!line.startsWith(FINGERPRINT_PREFIX)) {
                continue;
            }
            final String[] parts = line.substring(FINGERPRINT_PREFIX.length()).split(" ", 2);
            if (parts.length == 2 && seen.add(line)) {
                fingerprints.add(new String[]{parts[0].trim(), parts[1].trim()});
            }
        }
        return fingerprints;
    }

    /**
     * A detached ES384 JWS over the canonical fingerprint document. Detached
     * means the payload is omitted from the serialization (the empty middle
     * segment) - the verifier reconstructs it from the SDP it already has.
     */
    private static String signFingerprints(final List<String[]> fingerprints, final ECPrivateKey privateKey)
            throws Exception {
        final StringBuilder payload = new StringBuilder("{\"fingerprint\":[");
        for (int i = 0; i < fingerprints.size(); i++) {
            if (i > 0) {
                payload.append(',');
            }
            payload.append("{\"algorithm\":\"").append(fingerprints.get(i)[0])
                    .append("\",\"digest\":\"").append(fingerprints.get(i)[1]).append("\"}");
        }
        payload.append("]}");

        final String header = B64_URL.encodeToString("{\"alg\":\"ES384\"}".getBytes(StandardCharsets.UTF_8));
        final String body = B64_URL.encodeToString(payload.toString().getBytes(StandardCharsets.UTF_8));
        // JOSE wants the raw r||s pair, not the DER sequence Signature emits by default.
        final Signature signature = Signature.getInstance("SHA384withECDSAinP1363Format");
        signature.initSign(privateKey);
        signature.update((header + "." + body).getBytes(StandardCharsets.US_ASCII));
        return header + ".." + B64_URL.encodeToString(signature.sign());
    }

    private static String encodeIdentity(final String fingerprintAssertion, final String token) {
        final JsonObject assertion = new JsonObject();
        assertion.addProperty("fingerprints", fingerprintAssertion);
        assertion.addProperty("token", token);
        final JsonObject idp = new JsonObject();
        idp.addProperty("domain", IDP_DOMAIN);
        idp.addProperty("protocol", "default");
        final JsonObject identity = new JsonObject();
        identity.add("assertion", assertion);
        identity.add("idp", idp);
        // Standard base64 with padding: what the receiving end decodes with.
        return Base64.getEncoder().encodeToString(GSON.toJson(identity).getBytes(StandardCharsets.UTF_8));
    }

    /** Inserts a session-level attribute, i.e. ahead of the first media section. */
    private static String insertSessionAttribute(final String sdp, final String attribute) {
        final String eol = sdp.contains("\r\n") ? "\r\n" : "\n";
        final int media = sdp.indexOf(eol + "m=");
        if (media < 0) {
            return sdp.endsWith(eol) ? sdp + attribute + eol : sdp + eol + attribute + eol;
        }
        final int insertAt = media + eol.length();
        return sdp.substring(0, insertAt) + attribute + eol + sdp.substring(insertAt);
    }

}
