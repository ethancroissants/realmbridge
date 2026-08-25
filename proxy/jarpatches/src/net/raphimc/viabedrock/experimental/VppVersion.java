package net.raphimc.viabedrock.experimental;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The Bedrock version this bridge claims to be.
 *
 * ViaBedrock targets exactly one Bedrock release and bakes its protocol number
 * in as a compile-time constant. Realms update themselves, so the moment Mojang
 * ships a release the library has not caught up to, every realm answers the
 * login with {@code LOGIN_FAILED_CLIENT_OLD} - "Outdated client!" - and there is
 * no way to reach the world at all.
 *
 * The number is the entire version gate: the host compares it and nothing else
 * before deciding. Claiming the current one gets past that gate, which is worth
 * doing even though it is only half an answer - it does not implement whatever
 * changed in the newer release. Where the wire format is unchanged the session
 * proceeds normally; where it is not, it fails further in, which is both more
 * progress and more information than being refused at the door.
 *
 * Overridable so a mismatch can be corrected without rebuilding:
 *   {@code -Dvpp.bedrock.protocol=1001} and {@code -Dvpp.bedrock.version=1.26.30}
 * restore whatever the library was compiled against.
 */
public final class VppVersion {

    private static final Logger LOGGER = Logger.getLogger("ViaProxyPlus");

    /** Bedrock 1.26.40. The release ViaBedrock is compiled against is 1.26.30 / 1001. */
    private static final int DEFAULT_PROTOCOL = 2168;
    private static final String DEFAULT_VERSION = "1.26.40";

    private static final int PROTOCOL = Integer.getInteger("vpp.bedrock.protocol", DEFAULT_PROTOCOL);
    private static final String VERSION = System.getProperty("vpp.bedrock.version", DEFAULT_VERSION);

    static {
        LOGGER.log(Level.INFO, "[VP+] version: claiming Bedrock {0} (protocol {1})",
                new Object[]{VERSION, PROTOCOL});
    }

    private VppVersion() {
    }

    /** Replaces the inlined protocol constant in {@code BedrockProtocolVersion}. */
    public static int protocolVersion() {
        return PROTOCOL;
    }

    /** Replaces the inlined {@code "Bedrock <version>"} display name. */
    public static String displayName() {
        return "Bedrock " + VERSION;
    }

    /** Replaces the bare version string used in the login chain and skin payload. */
    public static String versionName() {
        return VERSION;
    }

}
