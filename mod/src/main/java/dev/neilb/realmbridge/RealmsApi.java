package dev.neilb.realmbridge;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Direct calls to the Bedrock Realms API for the parts MinecraftAuth does not wrap.
 *
 * MinecraftAuth covers listing worlds, accepting an invite link and joining. It
 * has nothing for <em>pending</em> invites, which is the gap that matters here: a
 * realm the owner has added you to still reports {@code member: false} until the
 * invitation is accepted, and an unaccepted membership is invisible from every
 * other endpoint - the realm shows up in the world list and hands out sessions
 * regardless, and only the realm host refuses.
 */
public final class RealmsApi {

    private static final String HOST = "https://pocket.realms.minecraft.net";
    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    private final BridgeAuth auth;

    public RealmsApi(final BridgeAuth auth) {
        this.auth = auth;
    }

    public Response get(final String path) throws Exception {
        return this.send(path, "GET", HttpRequest.BodyPublishers.noBody());
    }

    public Response put(final String path) throws Exception {
        return this.send(path, "PUT", HttpRequest.BodyPublishers.noBody());
    }

    private Response send(final String path, final String method,
                          final HttpRequest.BodyPublisher body) throws Exception {
        final HttpRequest request = HttpRequest.newBuilder(URI.create(HOST + path))
                .timeout(TIMEOUT)
                .header("Authorization", this.auth.realmsAuthorizationHeader())
                .header("Client-Version", BridgeAuth.BEDROCK_VERSION)
                .header("Accept", "application/json")
                .method(method, body)
                .build();
        final HttpResponse<String> response = HttpClient.newBuilder()
                .connectTimeout(TIMEOUT).build()
                .send(request, HttpResponse.BodyHandlers.ofString());
        return new Response(response.statusCode(), response.body());
    }

    public record Response(int status, String body) {

        public boolean ok() {
            return this.status >= 200 && this.status < 300;
        }

        @Override
        public String toString() {
            return this.status + " " + (this.body == null || this.body.isBlank() ? "(empty)" : this.body);
        }
    }

}
