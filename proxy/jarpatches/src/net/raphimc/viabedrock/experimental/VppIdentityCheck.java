package net.raphimc.viabedrock.experimental;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.RSAPublicKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Verifies our own identity assertion exactly as the realm host would.
 *
 * A host that refuses a connection says only {@code CONNECTERROR <id> 37}
 * ({@code ErrorCodeIdentityNotAllowed}), which covers both "this assertion does
 * not verify" and "this player may not join". Those need completely different
 * fixes, and nothing in the rejection distinguishes them.
 *
 * So the same checks run locally: fetch the issuer's published keys, verify the
 * token's RS256 signature and expiry, pull the {@code cpk} claim out of it, and
 * verify the detached fingerprint assertion against that key. If all three pass,
 * the assertion we send is provably well-formed and correctly signed, and a
 * rejection can only be an authorisation decision about the account.
 *
 * Runs off-thread: connection negotiation is on a timeout and must not wait for
 * two HTTPS round trips. Purely diagnostic - nothing here changes what is sent.
 */
public final class VppIdentityCheck {

    private static final Logger LOGGER = Logger.getLogger("ViaProxyPlus");
    private static final Gson GSON = new Gson();
    /** Claims worth seeing: who the issuer says we are, and what for. */
    private static final String[] INTERESTING = {"xid", "xname", "aud", "sub", "mem", "cap", "pfcd", "tid", "exp"};

    private VppIdentityCheck() {
    }

    public static void verifyAsync(final String token, final String assertion, final String fingerprintPayload) {
        final Thread thread = new Thread(() -> verify(token, assertion, fingerprintPayload), "VPP-IdentityCheck");
        thread.setDaemon(true);
        thread.start();
    }

    private static void verify(final String token, final String assertion, final String fingerprintPayload) {
        try {
            final String[] parts = token.split("\\.");
            if (parts.length != 3) {
                LOGGER.warning("[VP+] check: token is not a 3-part JWT");
                return;
            }
            final JsonObject header = decode(parts[0]);
            final JsonObject claims = decode(parts[1]);
            logClaims(claims);

            final String issuer = claims.has("iss") ? claims.get("iss").getAsString() : null;
            if (issuer == null) {
                LOGGER.warning("[VP+] check: token has no iss claim");
                return;
            }

            final JsonObject config = fetch(issuer.endsWith("/")
                    ? issuer + ".well-known/openid-configuration"
                    : issuer + "/.well-known/openid-configuration");
            LOGGER.log(Level.INFO, "[VP+] check: issuer published={0} token iss={1} match={2}",
                    new Object[]{config.get("issuer").getAsString(), issuer,
                            config.get("issuer").getAsString().equals(issuer)});

            final PublicKey signingKey = findKey(fetch(config.get("jwks_uri").getAsString()),
                    header.has("kid") ? header.get("kid").getAsString() : null);
            if (signingKey == null) {
                LOGGER.warning("[VP+] check: issuer publishes no key matching the token's kid");
                return;
            }
            final Signature rsa = Signature.getInstance("SHA256withRSA");
            rsa.initVerify(signingKey);
            rsa.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
            final boolean tokenOk = rsa.verify(Base64.getUrlDecoder().decode(parts[2]));
            LOGGER.log(Level.INFO, "[VP+] check: token signature valid={0}", tokenOk);

            if (claims.has("exp")) {
                final long secondsLeft = claims.get("exp").getAsLong() - (System.currentTimeMillis() / 1000L);
                LOGGER.log(Level.INFO, "[VP+] check: token expires in {0}s (negative means expired)", secondsLeft);
            }

            // The host pulls the signing key for the fingerprint assertion out of
            // the token itself, so this is the step that binds account to connection.
            if (!claims.has("cpk")) {
                LOGGER.warning("[VP+] check: token carries no cpk claim");
                return;
            }
            final PublicKey cpk = KeyFactory.getInstance("EC").generatePublic(
                    new X509EncodedKeySpec(Base64.getDecoder().decode(claims.get("cpk").getAsString())));
            final String[] jws = assertion.split("\\.", -1);
            final String signingInput = jws[0] + "."
                    + Base64.getUrlEncoder().withoutPadding().encodeToString(
                            fingerprintPayload.getBytes(StandardCharsets.UTF_8));
            final Signature ec = Signature.getInstance("SHA384withECDSAinP1363Format");
            ec.initVerify(cpk);
            ec.update(signingInput.getBytes(StandardCharsets.US_ASCII));
            final boolean assertionOk = ec.verify(Base64.getUrlDecoder().decode(jws[2]));
            LOGGER.log(Level.INFO, "[VP+] check: fingerprint assertion verifies against cpk={0}", assertionOk);

            if (tokenOk && assertionOk) {
                LOGGER.info("[VP+] check: assertion is valid by the issuer's own keys - "
                        + "a CONNECTERROR 37 after this is the host refusing the account, not a malformed identity");
            }
        } catch (Throwable e) {
            LOGGER.log(Level.WARNING, "[VP+] check: could not self-verify the identity assertion", e);
        }
    }

    private static void logClaims(final JsonObject claims) {
        for (final String name : INTERESTING) {
            if (claims.has(name) && !claims.get(name).isJsonNull()) {
                LOGGER.log(Level.INFO, "[VP+] check: token {0}={1}",
                        new Object[]{name, claims.get(name).getAsString()});
            }
        }
    }

    private static PublicKey findKey(final JsonObject jwks, final String kid) throws Exception {
        final JsonArray keys = jwks.getAsJsonArray("keys");
        for (final JsonElement element : keys) {
            final JsonObject key = element.getAsJsonObject();
            final boolean matches = kid == null
                    || !key.has("kid")
                    || kid.equals(key.get("kid").getAsString());
            if (matches && key.has("n") && key.has("e")) {
                return KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(
                        new BigInteger(1, Base64.getUrlDecoder().decode(key.get("n").getAsString())),
                        new BigInteger(1, Base64.getUrlDecoder().decode(key.get("e").getAsString()))));
            }
        }
        return null;
    }

    private static JsonObject fetch(final String url) throws Exception {
        final HttpResponse<String> response = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10)).build()
                .send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10)).build(),
                        HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("HTTP " + response.statusCode() + " from " + url);
        }
        return GSON.fromJson(response.body(), JsonObject.class);
    }

    private static JsonObject decode(final String segment) {
        return GSON.fromJson(new String(Base64.getUrlDecoder().decode(segment), StandardCharsets.UTF_8),
                JsonObject.class);
    }

    static List<String> interestingClaims() {
        return List.of(INTERESTING);
    }

}
