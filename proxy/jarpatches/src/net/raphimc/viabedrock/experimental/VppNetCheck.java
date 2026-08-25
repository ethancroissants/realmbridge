package net.raphimc.viabedrock.experimental;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Asks, in plain Java, whether this machine can do UDP to the internet at all.
 *
 * When ICE gathers a host candidate and then nothing - no server-reflexive
 * candidate, no error, no COMPLETE - there are two very different explanations:
 * the WebRTC stack is misconfigured, or the STUN responses are simply not coming
 * back. Nothing inside libwebrtc distinguishes them, and the difference decides
 * whether the fix belongs in this repo or in the player's firewall.
 *
 * So the same question gets asked without libwebrtc in the way: a hand-built
 * STUN binding request on a {@link DatagramSocket}, to several independent
 * servers. If these succeed while ICE gathers nothing, the stack is at fault.
 * If these fail too, UDP is being dropped and no amount of patching helps -
 * the connection has to be relayed over TCP instead.
 *
 * Cheap, off-thread, once per process. Purely diagnostic.
 */
public final class VppNetCheck {

    private static final Logger LOGGER = Logger.getLogger("ViaProxyPlus");
    private static final AtomicBoolean RUN = new AtomicBoolean();
    private static final SecureRandom RANDOM = new SecureRandom();

    /** The same servers ICE is given, plus two unrelated ones. */
    private static final List<InetSocketAddress> SERVERS = List.of(
            InetSocketAddress.createUnresolved("relay.communication.microsoft.com", 3478),
            InetSocketAddress.createUnresolved("stun.l.google.com", 19302),
            InetSocketAddress.createUnresolved("stun.cloudflare.com", 3478));

    private static final int TIMEOUT_MS = 3000;

    private VppNetCheck() {
    }

    public static void runOnce() {
        if (!RUN.compareAndSet(false, true)) {
            return;
        }
        final Thread thread = new Thread(VppNetCheck::probeAll, "VPP-NetCheck");
        thread.setDaemon(true);
        thread.start();
    }

    private static void probeAll() {
        listInterfaces();
        int reachable = 0;
        for (final InetSocketAddress server : SERVERS) {
            if (probe(server.getHostName(), server.getPort())) {
                reachable++;
            }
        }
        if (reachable == 0) {
            LOGGER.warning("[VP+] netcheck: no STUN server answered over UDP. "
                    + "This machine cannot complete a UDP round trip to the internet from this process, "
                    + "so ICE can never gather a public candidate and a peer-to-peer realm connection "
                    + "cannot be established. Allow this Java executable through the firewall, or the "
                    + "connection has to be relayed over TCP.");
        } else {
            LOGGER.log(Level.INFO, "[VP+] netcheck: {0} of {1} STUN servers answered over UDP",
                    new Object[]{reachable, SERVERS.size()});
        }
    }

    /**
     * Names every interface the machine has.
     *
     * ICE binds per adapter, so which adapters exist - and which one carries the
     * default route - decides whether its packets can be answered. A tunnel that
     * the allocator never enumerated is invisible in the ICE log but obvious here.
     */
    private static void listInterfaces() {
        try {
            final Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                final NetworkInterface nic = interfaces.nextElement();
                if (!nic.isUp() || nic.isLoopback()) {
                    continue;
                }
                final StringBuilder addresses = new StringBuilder();
                for (final InterfaceAddress address : nic.getInterfaceAddresses()) {
                    if (addresses.length() > 0) {
                        addresses.append(", ");
                    }
                    addresses.append(address.getAddress().getHostAddress());
                }
                LOGGER.log(Level.INFO, "[VP+] netcheck: interface {0} ({1}){2} [{3}]",
                        new Object[]{nic.getName(), nic.getDisplayName(),
                                nic.isPointToPoint() ? " point-to-point" : "", addresses});
            }
            LOGGER.log(Level.INFO, "[VP+] netcheck: default route leaves by {0}", defaultRouteAddress());
        } catch (Throwable e) {
            LOGGER.log(Level.WARNING, "[VP+] netcheck: could not list network interfaces", e);
        }
    }

    /** The source address the OS picks for internet-bound traffic, without sending anything. */
    private static String defaultRouteAddress() {
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.connect(InetAddress.getByName("8.8.8.8"), 53);
            return socket.getLocalAddress().getHostAddress();
        } catch (Throwable e) {
            return "(unknown)";
        }
    }

    private static boolean probe(final String host, final int port) {
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout(TIMEOUT_MS);
            final byte[] transactionId = new byte[12];
            RANDOM.nextBytes(transactionId);

            final ByteBuffer request = ByteBuffer.allocate(20);
            request.putShort((short) 0x0001);   // Binding Request
            request.putShort((short) 0);        // no attributes
            request.putInt(0x2112A442);         // magic cookie
            request.put(transactionId);

            final InetAddress address = InetAddress.getByName(host);
            final long start = System.nanoTime();
            socket.send(new DatagramPacket(request.array(), request.capacity(), address, port));

            final byte[] buffer = new byte[512];
            final DatagramPacket response = new DatagramPacket(buffer, buffer.length);
            socket.receive(response);
            final long millis = (System.nanoTime() - start) / 1_000_000L;

            LOGGER.log(Level.INFO, "[VP+] netcheck: {0}:{1} answered in {2}ms, mapped address {3}",
                    new Object[]{host, port, millis, mappedAddress(buffer, response.getLength(), transactionId)});
            return true;
        } catch (Throwable e) {
            LOGGER.log(Level.WARNING, "[VP+] netcheck: {0}:{1} did not answer ({2})",
                    new Object[]{host, port, e.getClass().getSimpleName()
                            + (e.getMessage() == null ? "" : ": " + e.getMessage())});
            return false;
        }
    }

    /** Pulls XOR-MAPPED-ADDRESS out of the response, so the log names our public IP. */
    private static String mappedAddress(final byte[] data, final int length, final byte[] transactionId) {
        try {
            final ByteBuffer buffer = ByteBuffer.wrap(data, 0, length);
            buffer.position(20);
            while (buffer.remaining() >= 4) {
                final int type = buffer.getShort() & 0xFFFF;
                final int size = buffer.getShort() & 0xFFFF;
                if (buffer.remaining() < size) {
                    break;
                }
                if (type == 0x0020 && size >= 8) {          // XOR-MAPPED-ADDRESS
                    buffer.get();                           // reserved
                    final int family = buffer.get() & 0xFF;
                    final int xorPort = (buffer.getShort() & 0xFFFF) ^ 0x2112;
                    if (family != 0x01) {
                        return "(non-IPv4)";
                    }
                    final byte[] addressBytes = new byte[4];
                    buffer.get(addressBytes);
                    final int xorAddress = ByteBuffer.wrap(addressBytes).getInt() ^ 0x2112A442;
                    return ((xorAddress >>> 24) & 0xFF) + "." + ((xorAddress >>> 16) & 0xFF) + "."
                            + ((xorAddress >>> 8) & 0xFF) + "." + (xorAddress & 0xFF) + ":" + xorPort;
                }
                buffer.position(buffer.position() + size + ((4 - (size % 4)) % 4));
            }
            return "(not reported)";
        } catch (Throwable e) {
            return "(unparsed)";
        }
    }

}
