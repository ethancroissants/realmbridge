package net.raphimc.viabedrock.experimental;

import dev.kastle.webrtc.PeerConnectionFactory;
import dev.kastle.webrtc.PeerConnectionObserver;
import dev.kastle.webrtc.RTCConfiguration;
import dev.kastle.webrtc.RTCDataChannel;
import dev.kastle.webrtc.RTCIceCandidate;
import dev.kastle.webrtc.RTCIceConnectionState;
import dev.kastle.webrtc.RTCIceGatheringState;
import dev.kastle.webrtc.RTCIceServer;
import dev.kastle.webrtc.RTCPeerConnection;
import dev.kastle.webrtc.RTCPeerConnectionIceErrorEvent;
import dev.kastle.webrtc.RTCPeerConnectionState;
import dev.kastle.webrtc.RTCSessionDescription;
import dev.kastle.webrtc.RTCSignalingState;
import dev.kastle.webrtc.SetSessionDescriptionObserver;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Makes ICE negotiation observable, and gives it a candidate it can actually use.
 *
 * Once a realm host accepts the identity assertion it answers with CONNECTRESPONSE
 * and the connection moves into ICE - where the library goes completely silent.
 * Three separate failures are discarded without a trace:
 *
 *   NetherNetClientChannel$3         setRemoteDescription observer, onSuccess and
 *                                    onFailure both empty. A rejected answer looks
 *                                    exactly like an accepted one.
 *   NetherNetClientChannel$1         implements onIceCandidate only, so
 *                                    onIceCandidateError, onIceGatheringChange and
 *                                    onIceConnectionChange all fall through to the
 *                                    interface defaults, which do nothing. A TURN
 *                                    allocation that is refused says nothing at all.
 *   (no state logging)               onConnectionChange logs at trace, and only
 *                                    FAILED is treated as an event.
 *
 * The visible result is a connection that answers, then stalls for the whole
 * handshake budget and is killed by "Could not connect to the backend server".
 *
 * The substantive fix is what gets added to the ICE configuration. The signaling
 * service hands out relay.communication.microsoft.com for both STUN and TURN over
 * UDP, and nothing else. When no UDP response ever comes back the only candidate
 * offered is a LAN address - useless to a realm host, which lives behind Azure NAT
 * and can only reach a peer whose public mapping it has been told about. Two
 * things are appended, never substituted, so Microsoft's own relay stays first and
 * is still preferred whenever it works:
 *
 *   public STUN         guarantees a server-reflexive candidate as long as any
 *                       UDP round trip to the internet succeeds at all.
 *   TURN over TCP/TLS   the same Microsoft relay and the same credentials, reached
 *                       over TCP 3478 and TLS 5349. This is the path that survives
 *                       UDP being dropped outright: media is relayed over a TCP
 *                       connection the firewall treats like any other outbound
 *                       stream. It requires clearing PORTALLOCATOR_DISABLE_TCP,
 *                       which the library sets and which would otherwise discard
 *                       TCP relay ports before they are ever allocated.
 */
public final class VppIce {

    private static final Logger LOGGER = Logger.getLogger("ViaProxyPlus");

    /**
     * Unauthenticated STUN, used only to learn our own public mapping.
     *
     * Set {@code -Dvpp.stun.fallback=false} to send exactly what the signaling
     * service handed us, which is what an unpatched build does.
     */
    private static final List<String> FALLBACK_STUN = List.of(
            "stun:stun.l.google.com:19302",
            "stun:stun.cloudflare.com:3478");

    /**
     * The TURN transports the signaling service never offers.
     *
     * Same host, same credentials - only the transport differs. {@code %s} is the
     * relay hostname taken from the UDP url the service did hand us, so this
     * follows Microsoft's own relay rather than hardcoding one.
     */
    private static final List<String> RELAY_TCP_TEMPLATES = List.of(
            "turn:%s:3478?transport=tcp",
            "turns:%s:5349?transport=tcp");

    private VppIce() {
    }

    /** Replaces {@code PeerConnectionFactory.createPeerConnection} in NetherNetClientChannel. */
    public static RTCPeerConnection createPeerConnection(final PeerConnectionFactory factory,
                                                        final RTCConfiguration config,
                                                        final PeerConnectionObserver observer) {
        try {
            describe(config);
            addFallbackStun(config);
            addTcpRelay(config);
            allowTcpPorts(config);
            VppNetCheck.runOnce();
        } catch (Throwable e) {
            LOGGER.log(Level.WARNING, "[VP+] ice: could not prepare the ICE configuration", e);
        }
        final Tap tap = new Tap(observer);
        tap.reportWhenGatheringShouldBeDone();
        return factory.createPeerConnection(config, tap);
    }

    /** Replaces {@code RTCPeerConnection.setRemoteDescription} in NetherNetClientChannel. */
    public static void setRemoteDescription(final RTCPeerConnection peerConnection,
                                            final RTCSessionDescription description,
                                            final SetSessionDescriptionObserver observer) {
        peerConnection.setRemoteDescription(description, new SetSessionDescriptionObserver() {
            @Override
            public void onSuccess() {
                LOGGER.info("[VP+] ice: remote answer applied");
                observer.onSuccess();
            }

            @Override
            public void onFailure(final String error) {
                // The library drops this on the floor, so a rejected answer is
                // indistinguishable from an accepted one until the handshake times out.
                LOGGER.log(Level.WARNING, "[VP+] ice: remote answer REJECTED: {0}", error);
                observer.onFailure(error);
            }
        });
    }

    private static void describe(final RTCConfiguration config) {
        if (config.iceServers == null || config.iceServers.isEmpty()) {
            LOGGER.warning("[VP+] ice: signaling supplied NO ice servers - only host candidates can be gathered");
            return;
        }
        for (final RTCIceServer server : config.iceServers) {
            LOGGER.log(Level.INFO, "[VP+] ice: server urls={0} credentials={1}",
                    new Object[]{server.urls, server.username != null && !server.username.isEmpty()});
        }
    }

    private static void addFallbackStun(final RTCConfiguration config) {
        if (!Boolean.parseBoolean(System.getProperty("vpp.stun.fallback", "true"))) {
            LOGGER.info("[VP+] ice: fallback STUN disabled by vpp.stun.fallback=false");
            return;
        }
        if (config.iceServers == null) {
            config.iceServers = new ArrayList<>();
        }
        final List<String> configured = new ArrayList<>();
        for (final RTCIceServer server : config.iceServers) {
            if (server.urls != null) {
                configured.addAll(server.urls);
            }
        }
        final List<String> added = new ArrayList<>();
        for (final String url : FALLBACK_STUN) {
            if (!configured.contains(url)) {
                final RTCIceServer server = new RTCIceServer();
                server.urls = List.of(url);
                config.iceServers.add(server);
                added.add(url);
            }
        }
        if (!added.isEmpty()) {
            LOGGER.log(Level.INFO, "[VP+] ice: added fallback STUN {0}", added);
        }
    }

    /**
     * Adds TURN over TCP and TLS against whatever relay the service named.
     *
     * A relay candidate is the only kind that still works when UDP never comes
     * back, because the media rides a TCP connection to the relay and the relay
     * speaks UDP to the peer on our behalf.
     */
    private static void addTcpRelay(final RTCConfiguration config) {
        if (!Boolean.parseBoolean(System.getProperty("vpp.relay.tcp", "true"))) {
            LOGGER.info("[VP+] ice: TCP relay disabled by vpp.relay.tcp=false");
            return;
        }
        final List<RTCIceServer> existing = new ArrayList<>(config.iceServers);
        for (final RTCIceServer server : existing) {
            if (server.urls == null || server.username == null || server.username.isEmpty()) {
                continue;   // no credentials means it is a bare STUN entry, not a relay
            }
            final String host = relayHost(server.urls);
            if (host == null) {
                continue;
            }
            final List<String> urls = new ArrayList<>();
            for (final String template : RELAY_TCP_TEMPLATES) {
                urls.add(String.format(template, host));
            }
            final RTCIceServer relay = new RTCIceServer();
            relay.urls = urls;
            relay.username = server.username;
            relay.password = server.password;
            config.iceServers.add(relay);
            LOGGER.log(Level.INFO, "[VP+] ice: added TCP relay {0}", urls);
            return;   // one relay is enough; more only slows gathering down
        }
    }

    /** The hostname out of a {@code turn:host:port} url, ignoring any stun: entries. */
    private static String relayHost(final List<String> urls) {
        for (final String url : urls) {
            if (!url.startsWith("turn:") && !url.startsWith("turns:")) {
                continue;
            }
            final String withoutScheme = url.substring(url.indexOf(':') + 1);
            final int end = Math.min(
                    indexOrLength(withoutScheme, ':'),
                    indexOrLength(withoutScheme, '?'));
            return withoutScheme.substring(0, end);
        }
        return null;
    }

    private static int indexOrLength(final String value, final char character) {
        final int index = value.indexOf(character);
        return index < 0 ? value.length() : index;
    }

    /**
     * Lets TCP relay ports be allocated at all.
     *
     * The library sets PORTALLOCATOR_DISABLE_TCP, which drops every TCP port
     * before allocation - including the TURN-over-TCP port that is the whole
     * point of {@link #addTcpRelay}. Clearing it also brings back TCP host
     * candidates, which are harmless: the realm host offers UDP only, so they
     * are never paired.
     */
    private static void allowTcpPorts(final RTCConfiguration config) {
        if (config.portAllocatorConfig == null) {
            return;
        }
        if (config.portAllocatorConfig.isTcpDisabled()) {
            config.portAllocatorConfig.setDisableTcp(false);
            LOGGER.info("[VP+] ice: enabled TCP ports so a TCP relay candidate can be allocated");
        }
    }

    /** Delegating observer that logs the parts the library discards. */
    private static final class Tap implements PeerConnectionObserver {

        private final PeerConnectionObserver delegate;
        private int candidates;
        private int reflexive;

        private Tap(final PeerConnectionObserver delegate) {
            this.delegate = delegate;
        }

        /**
         * Says what was gathered before the handshake budget runs out.
         *
         * Gathering that stalls never reaches COMPLETE, so the warning there
         * never fires and the log simply stops. This reports the tally on a
         * timer instead, while there is still a connection to explain.
         */
        private void reportWhenGatheringShouldBeDone() {
            final Thread thread = new Thread(() -> {
                try {
                    Thread.sleep(5000L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (this.reflexive == 0) {
                    LOGGER.log(Level.WARNING, "[VP+] ice: 5s after gathering started there are still "
                            + "only host candidates ({0} total) - no STUN or TURN server has answered, "
                            + "so the realm host has no address it can reach us on", this.candidates);
                }
            }, "VPP-IceSummary");
            thread.setDaemon(true);
            thread.start();
        }

        @Override
        public void onIceCandidate(final RTCIceCandidate candidate) {
            this.candidates++;
            final String type = typeOf(candidate.sdp);
            if (!"host".equals(type)) {
                this.reflexive++;
            }
            LOGGER.log(Level.INFO, "[VP+] ice: local candidate #{0} typ {1}: {2}",
                    new Object[]{this.candidates, type, candidate.sdp});
            this.delegate.onIceCandidate(candidate);
        }

        @Override
        public void onIceCandidateError(final RTCPeerConnectionIceErrorEvent event) {
            // Where a refused TURN allocation or an unreachable STUN server shows up.
            LOGGER.log(Level.WARNING, "[VP+] ice: candidate error from {0} ({1}:{2}) code={3} {4}",
                    new Object[]{event.getUrl(), event.getAddress(), event.getPort(),
                            event.getErrorCode(), event.getErrorText()});
            this.delegate.onIceCandidateError(event);
        }

        @Override
        public void onIceGatheringChange(final RTCIceGatheringState state) {
            LOGGER.log(Level.INFO, "[VP+] ice: gathering {0}", state);
            if (state == RTCIceGatheringState.COMPLETE && this.reflexive == 0) {
                LOGGER.warning("[VP+] ice: gathering finished with only host candidates - "
                        + "a realm host behind Azure NAT has no address it can reach us on");
            }
            this.delegate.onIceGatheringChange(state);
        }

        @Override
        public void onIceConnectionChange(final RTCIceConnectionState state) {
            LOGGER.log(Level.INFO, "[VP+] ice: connection {0}", state);
            this.delegate.onIceConnectionChange(state);
        }

        @Override
        public void onConnectionChange(final RTCPeerConnectionState state) {
            LOGGER.log(Level.INFO, "[VP+] ice: peer connection {0}", state);
            this.delegate.onConnectionChange(state);
        }

        @Override
        public void onSignalingChange(final RTCSignalingState state) {
            LOGGER.log(Level.INFO, "[VP+] ice: signaling state {0}", state);
            this.delegate.onSignalingChange(state);
        }

        @Override
        public void onStandardizedIceConnectionChange(final RTCIceConnectionState state) {
            this.delegate.onStandardizedIceConnectionChange(state);
        }

        @Override
        public void onIceConnectionReceivingChange(final boolean receiving) {
            this.delegate.onIceConnectionReceivingChange(receiving);
        }

        @Override
        public void onIceCandidatesRemoved(final RTCIceCandidate[] removed) {
            this.delegate.onIceCandidatesRemoved(removed);
        }

        @Override
        public void onDataChannel(final RTCDataChannel channel) {
            this.delegate.onDataChannel(channel);
        }

        @Override
        public void onRenegotiationNeeded() {
            this.delegate.onRenegotiationNeeded();
        }

        private static String typeOf(final String sdp) {
            if (sdp == null) {
                return "?";
            }
            final int index = sdp.indexOf("typ ");
            if (index < 0) {
                return "?";
            }
            final String rest = sdp.substring(index + 4);
            final int space = rest.indexOf(' ');
            return space < 0 ? rest : rest.substring(0, space);
        }
    }

}
