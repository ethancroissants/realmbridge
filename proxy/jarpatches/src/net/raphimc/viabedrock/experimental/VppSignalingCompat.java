package net.raphimc.viabedrock.experimental;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Compatibility shim for the NetherNet Xbox RPC signaling frame format.
 *
 * The signaling library reads {@code params} of a Signaling_ReceiveMessage_v1_0
 * frame as a JSON array of messages. The service now sends a single message
 * object instead, so the cast throws and every inbound frame is dropped -
 * including the WebRTC answer, which makes realm connections fail outright.
 * This accepts both shapes.
 */
public final class VppSignalingCompat {

    private VppSignalingCompat() {
    }

    public static JsonArray paramsAsArray(final JsonObject frame, final String key) {
        final JsonElement element = frame == null ? null : frame.get(key);
        if (element == null || element.isJsonNull()) {
            return new JsonArray();
        }
        if (element.isJsonArray()) {
            return element.getAsJsonArray();
        }
        final JsonArray wrapped = new JsonArray(); // single message object
        wrapped.add(element);
        return wrapped;
    }

}
