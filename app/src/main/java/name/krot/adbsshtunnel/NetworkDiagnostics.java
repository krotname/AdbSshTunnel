package name.krot.adbsshtunnel;

import android.content.Context;
import android.net.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import javax.net.ssl.HttpsURLConnection;

/** User-requested external address lookup; never declares inbound reachability. */
public final class NetworkDiagnostics {
    private NetworkDiagnostics() { }
    public static String inspect(Context context) throws IOException {
        ConnectivityManager manager = context.getSystemService(ConnectivityManager.class);
        Network network = manager.getActiveNetwork();
        if (network == null) throw new IOException("No active network");
        NetworkCapabilities capabilities = manager.getNetworkCapabilities(network);
        LinkProperties links = manager.getLinkProperties(network);
        List<Inet4Address> local = new ArrayList<>();
        if (links != null) for (LinkAddress link : links.getLinkAddresses())
            if (link.getAddress() instanceof Inet4Address) local.add((Inet4Address) link.getAddress());
        HttpsURLConnection connection = (HttpsURLConnection) network.openConnection(new URL("https://api.ipify.org"));
        connection.setConnectTimeout(4000); connection.setReadTimeout(4000);
        connection.setInstanceFollowRedirects(false);
        String external;
        try {
            if (connection.getResponseCode() != 200) throw new IOException("Public IPv4 service returned HTTP " + connection.getResponseCode());
            try (InputStream stream = connection.getInputStream(); ByteArrayOutputStream buffer = new ByteArrayOutputStream()) {
                for (int b; buffer.size() < 128 && (b = stream.read()) != -1;) buffer.write(b);
                external = new String(buffer.toByteArray(), StandardCharsets.US_ASCII).trim();
            }
        } finally { connection.disconnect(); }
        if (!external.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}")) throw new IOException("Invalid public IPv4 response");
        InetAddress publicAddress = InetAddress.getByName(external);
        if (!(publicAddress instanceof Inet4Address) || publicAddress.isAnyLocalAddress() || publicAddress.isLoopbackAddress()
                || publicAddress.isSiteLocalAddress() || carrierNat(publicAddress)) throw new IOException("Public IPv4 service returned a non-public address");
        if (!network.equals(manager.getActiveNetwork())) throw new IOException("Network changed; refresh again");
        StringBuilder result = new StringBuilder("External IPv4: ").append(external).append("\nActive interface IPv4: ");
        for (Inet4Address address : local) result.append(address.getHostAddress()).append(' ');
        if (local.isEmpty()) result.append("none");
        result.append('\n');
        if (capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN))
            result.append("VPN is active. The external address may identify its exit; incoming access to this phone requires a separate route.");
        else if (local.stream().anyMatch(NetworkDiagnostics::carrierNat))
            result.append("The interface uses carrier NAT space (100.64.0.0/10). Direct incoming access needs a public IPv4 from the operator or another tunnel.");
        else if (local.contains(publicAddress))
            result.append("External and interface addresses match. Operator filtering or a firewall may still block incoming SSH.");
        else if (capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))
            result.append("The external address differs from Wi-Fi. Incoming WAN access may require router port forwarding; this app does not change the router.");
        else result.append("External and interface addresses differ. NAT is present; a public address alone does not provide an incoming mapping.");
        return result.append("\nIncoming SSH reachability must be tested from another network.").toString();
    }
    private static boolean carrierNat(InetAddress address) {
        byte[] bytes = address.getAddress();
        return bytes.length == 4 && (bytes[0] & 255) == 100 && (bytes[1] & 255) >= 64 && (bytes[1] & 255) <= 127;
    }
}
