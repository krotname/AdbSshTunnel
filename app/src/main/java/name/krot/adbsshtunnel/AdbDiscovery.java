package name.krot.adbsshtunnel;

import android.content.Context;
import android.net.nsd.*;
import java.net.*;
import java.util.concurrent.*;

/** Android's connect service, not its pairing service. No root or private API. */
public final class AdbDiscovery implements AutoCloseable {
    public interface Listener { void onPort(int port, String state); }
    private final NsdManager manager;
    private final Listener callback;
    private final ExecutorService resolver = Executors.newSingleThreadExecutor();
    private final java.util.Set<String> localServices = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;
    private volatile String activeName;
    private final NsdManager.DiscoveryListener discovery = new NsdManager.DiscoveryListener() {
        public void onDiscoveryStarted(String type) { }
        public void onDiscoveryStopped(String type) { }
        public void onStartDiscoveryFailed(String type, int code) { callback.onPort(0, "ADB discovery unavailable: " + code); }
        public void onStopDiscoveryFailed(String type, int code) { }
        public void onServiceFound(NsdServiceInfo service) {
            manager.resolveService(service, new NsdManager.ResolveListener() {
                public void onResolveFailed(NsdServiceInfo s, int code) { }
                public void onServiceResolved(NsdServiceInfo s) {
                    if (closed) return;
                    try {
                    resolver.execute(() -> {
                        if (closed) return;
                        try {
                            InetAddress host = s.getHost();
                            if (host == null || !(host.isLoopbackAddress() || NetworkInterface.getByInetAddress(host) != null)) return;
                            int port = s.getPort();
                            try (Socket socket = new Socket()) { socket.connect(new InetSocketAddress("127.0.0.1", port), 750); }
                            localServices.add(s.getServiceName());
                            activeName = s.getServiceName();
                            if (!closed) callback.onPort(port, "Wireless Debugging: " + port);
                        } catch (Exception ignored) { }
                    });
                    } catch (RejectedExecutionException ignored) { }
                }
            });
        }
        public void onServiceLost(NsdServiceInfo s) {
            if (localServices.remove(s.getServiceName()) && s.getServiceName().equals(activeName) && !closed) callback.onPort(0, "Wireless Debugging stopped; enable it in Developer options");
        }
    };
    public AdbDiscovery(Context c, Listener callback) { manager = c.getSystemService(NsdManager.class); this.callback = callback; }
    public void start() { manager.discoverServices("_adb-tls-connect._tcp.", NsdManager.PROTOCOL_DNS_SD, discovery); }
    public void close() { closed = true; try { manager.stopServiceDiscovery(discovery); } catch (IllegalArgumentException ignored) { } resolver.shutdownNow(); }
}
