package net.raphimc.viabedrock.experimental;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Logs every frame on the Xbox RPC signaling socket, both directions.
 *
 * A refused realm surfaces as a single integer - {@code CONNECTERROR <id> 37} -
 * and nothing else in the stack explains it. But that frame is one message on a
 * JSON-RPC channel that also carries method results, delivery notifications and
 * error objects with actual reason strings ({@code Player not registered},
 * {@code MissingOrExpiredIdentity}), none of which were ever surfaced: the
 * library consumes them internally and the compat shim only logged frames that
 * happened to contain the word CONNECTERROR.
 *
 * So the whole conversation gets logged. If the service or the host says
 * anything more specific than a number, it is on this socket.
 *
 * Credentials are stripped: the outbound offer embeds the identity token, and
 * these logs get pasted into bug reports.
 */
public final class VppSignalingTap {

    private static final Logger LOGGER = Logger.getLogger("ViaProxyPlus");
    /** A JWT: three base64url runs, the first of which is a JSON header. */
    private static final String JWT = "eyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]+";
    /**
     * The identity attribute, as it appears both raw and JSON-escaped.
     *
     * The offer is carried as a string inside a JSON-RPC envelope, so by the
     * time it reaches the socket every '=' is "\u003d" - matching only the raw
     * form let the token through in the one place it actually mattered.
     */
    private static final String IDENTITY = "a(?:=|\\\\u003d)identity:[A-Za-z0-9+/=]+(?:\\\\u003d)*";

    private VppSignalingTap() {
    }

    /** Replaces {@code TextWebSocketFrame.text()} at the inbound read. */
    public static String inbound(final TextWebSocketFrame frame) {
        final String text = frame.text();
        log("<--", text);
        return text;
    }

    /** Replaces {@code Gson.toJson(JsonElement)} at the outbound writes. */
    public static String outbound(final Gson gson, final JsonElement element) {
        final String text = gson.toJson(element);
        log("-->", text);
        return text;
    }

    private static void log(final String direction, final String text) {
        try {
            LOGGER.log(Level.INFO, "[VP+] signaling {0} {1}",
                    new Object[]{direction, redact(text)});
        } catch (Throwable ignored) {
            // never let logging break the socket
        }
    }

    static String redact(final String text) {
        if (text == null) {
            return "(null)";
        }
        // Identity first: it is base64 of JSON wrapping the token, so redacting
        // the attribute as a whole is what actually removes the credential.
        String safe = text.replaceAll(IDENTITY, "a=identity:<redacted>");
        safe = safe.replaceAll(JWT, "<jwt redacted>");
        return safe;
    }

}
