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

            verifyKeyBinding(token, sessionKeyPair.getPublic());
            final String assertion = signFingerprints(fingerprints, (ECPrivateKey) sessionKeyPair.getPrivate());
            final String attribute = encodeIdentity(assertion, token);
            final String result = insertSessionAttribute(sdp, IDENTITY_PREFIX + attribute);
            describe(sdp, fingerprints, assertion, token, attribute, result);
            return result;
        } catch (Throwable e) {
            LOGGER.log(Level.WARNING, "[VP+] could not build the identity assertion; "
                    + "connecting without one (realms will refuse with CONNECTERROR 37)", e);
            return sdp;
        }
    }

    /**
     * Dumps the shape of what we are about to send.
     *
     * The assertion is built from data that only exists at runtime - libwebrtc's
     * offer, a live token - so this is the only way to see whether it looks like
     * what a real client sends. The token is a bearer credential and is never
     * logged: only its length and header, which is all that is diagnostic.
     * The signaling message carries the whole SDP, so its size matters too - an
     * identity attribute is kilobytes larger than anything else in the offer.
     */
    private static void describe(final String original, final List<String[]> fingerprints,
                                 final String assertion, final String token,
                                 final String attribute, final String result) {
        LOGGER.log(Level.INFO, "[VP+] identity: sdp {0} -> {1} bytes, attribute {2} bytes, "
                        + "token {3} bytes, assertion {4} bytes, {5} fingerprint(s)",
                new Object[]{original.length(), result.length(), attribute.length(),
                        token.length(), assertion.length(), fingerprints.size()});
        for (final String[] fingerprint : fingerprints) {
            LOGGER.log(Level.INFO, "[VP+] identity: signed over algorithm={0} digest={1}",
                    new Object[]{fingerprint[0], fingerprint[1]});
        }
        LOGGER.log(Level.INFO, "[VP+] identity: assertion segments={0} (detached JWS needs 3, middle empty)",
                assertion.split("\\.", -1).length);
        try {
            final String[] parts = token.split("\\.");
            LOGGER.log(Level.INFO, "[VP+] identity: token header={0} segments={1}",
                    new Object[]{new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8),
                            parts.length});
        } catch (Throwable ignored) {
            // header shape is a nicety, not worth failing over
        }
        // The offer itself is not secret - it is sent to the host verbatim - but
        // the identity line embeds the token, so it is elided.
        final StringBuilder redacted = new StringBuilder();
        for (final String line : result.split("\r\n|\n|\r")) {
            redacted.append("\n    ").append(line.startsWith(IDENTITY_PREFIX)
                    ? IDENTITY_PREFIX + "<" + (line.length() - IDENTITY_PREFIX.length()) + " bytes elided>"
                    : line);
        }
        LOGGER.log(Level.INFO, "[VP+] identity: offer SDP as sent:{0}", redacted);
    }

    /**
     * Checks that the token really is bound to the key we sign with.
     *
     * The host verifies the fingerprint signature using the public key in the
     * token's {@code cpk} claim, so if that claim is not the session key, every
     * assertion is rejected no matter how well-formed it is. Logging the two
     * side by side separates "our assertion is wrong" from "this account is not
     * allowed on this realm" - both of which surface as CONNECTERROR 37.
     */
    private static void verifyKeyBinding(final String token, final java.security.PublicKey sessionPublicKey) {
        try {
            final String[] parts = token.split("\\.");
            if (parts.length < 2) {
                LOGGER.warning("[VP+] multiplayer token is not a JWT; cannot check key binding");
                return;
            }
            final String json = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
            final JsonObject payload = GSON.fromJson(json, JsonObject.class);
            if (payload == null || !payload.has("cpk")) {
                LOGGER.warning("[VP+] multiplayer token has no 'cpk' claim; the host cannot bind our assertion");
                return;
            }
            final String claimed = payload.get("cpk").getAsString();
            final String ours = Base64.getEncoder().encodeToString(sessionPublicKey.getEncoded());
            if (claimed.equals(ours)) {
                LOGGER.info("[VP+] identity key binding OK: token cpk matches the session key");
            } else {
                LOGGER.warning("[VP+] token cpk does NOT match the session key we sign with"
                        + " - either the encodings differ or the assertion cannot verify."
                        + " cpk=" + abbreviate(claimed) + " session=" + abbreviate(ours));
            }
            for (final String claim : new String[]{"xuid", "sub", "iss", "exp"}) {
                if (payload.has(claim) && !payload.get(claim).isJsonNull()) {
                    LOGGER.log(Level.INFO, "[VP+] token {0}={1}",
                            new Object[]{claim, payload.get(claim).getAsString()});
                }
            }
        } catch (Throwable e) {
            LOGGER.log(Level.WARNING, "[VP+] could not inspect the multiplayer token", e);
        }
    }

    private static String abbreviate(final String value) {
        return value.length() <= 28 ? value : value.substring(0, 28) + "...(" + value.length() + ")";
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
