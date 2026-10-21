package name.krot.adbsshtunnel;

import android.content.Context;
import android.net.nsd.*;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.ext.SdkExtensions;
import java.net.*;
import java.util.concurrent.*;

/** Android's connect service, not its pairing service. No root or private API. */
public final class AdbDiscovery implements AutoCloseable {
    public interface Listener { void onPort(int port, String state); }
    private final NsdManager manager;
    private final Listener callback;
    private final WifiManager.MulticastLock multicastLock;
    private final ScheduledExecutorService resolver = Executors.newSingleThreadScheduledExecutor();
    private final ServiceResolveQueue<ResolveRequest> resolveQueue = new ServiceResolveQueue<>(this::resolve);
    private final java.util.Map<String, Long> sightings = new java.util.HashMap<>();
    private long nextSighting;
    private volatile boolean closed;
    private volatile String activeName;
    private static final class ResolveRequest {
        final NsdServiceInfo service;
        final long sighting;
        final int attempt;
        ResolveRequest(NsdServiceInfo service, long sighting, int attempt) {
            this.service = service; this.sighting = sighting; this.attempt = attempt;
        }
    }
    private final NsdManager.DiscoveryListener discovery = new NsdManager.DiscoveryListener() {
        public void onDiscoveryStarted(String type) { }
        public void onDiscoveryStopped(String type) { releaseMulticast(); }
        public void onStartDiscoveryFailed(String type, int code) { if (!closed) { callback.onPort(0, "ADB discovery unavailable: " + code); close(); } }
        public void onStopDiscoveryFailed(String type, int code) { }
        public void onServiceFound(NsdServiceInfo service) {
            final long sighting;
            synchronized (AdbDiscovery.this) {
                if (closed) return;
                sighting = ++nextSighting;
                sightings.put(service.getServiceName(), sighting);
                resolveQueue.offer(service.getServiceName(), new ResolveRequest(service, sighting, 0));
            }
        }
        public void onServiceLost(NsdServiceInfo s) {
            synchronized (AdbDiscovery.this) {
                sightings.remove(s.getServiceName());
                resolveQueue.remove(s.getServiceName());
                if (s.getServiceName().equals(activeName) && !closed) {
                    activeName = null;
                    callback.onPort(0, "Wireless Debugging stopped; enable it in Developer options");
                }
            }
        }
    };
    private synchronized boolean current(ResolveRequest request) {
        return !closed && Long.valueOf(request.sighting).equals(sightings.get(request.service.getServiceName()));
    }
    private void retry(ResolveRequest request) {
        if (request.attempt != 0 || !current(request)) return;
        try {
            resolver.schedule(() -> {
                synchronized (AdbDiscovery.this) {
                    if (current(request)) resolveQueue.offer(request.service.getServiceName(),
                        new ResolveRequest(request.service, request.sighting, 1));
                }
            }, 1, TimeUnit.SECONDS);
        } catch (RejectedExecutionException ignored) { }
    }
    private void resolve(ResolveRequest request, Runnable completed) {
        if (!current(request)) { completed.run(); return; }
        try {
            manager.resolveService(request.service, new NsdManager.ResolveListener() {
                public void onResolveFailed(NsdServiceInfo s, int code) {
                    completed.run(); retry(request);
                }
                public void onServiceResolved(NsdServiceInfo s) {
                    completed.run();
                    if (!current(request)) return;
                    try {
                    resolver.execute(() -> {
                        if (closed) return;
                        try {
                            InetAddress host = s.getHost();
                            if (host == null || !(host.isLoopbackAddress() || NetworkInterface.getByInetAddress(host) != null)) return;
                            int port = s.getPort();
                            AdbTlsProbe.verify(port);
                            synchronized (AdbDiscovery.this) {
                                if (!current(request)) return;
                                activeName = request.service.getServiceName();
                                callback.onPort(port, "Wireless Debugging: " + port);
                            }
                        } catch (Exception ignored) { retry(request); }
                    });
                    } catch (RejectedExecutionException ignored) { }
                }
            });
        } catch (RuntimeException e) { completed.run(); retry(request); }
    }
    public AdbDiscovery(Context c, Listener callback) {
        manager = c.getSystemService(NsdManager.class); this.callback = callback;
        WifiManager wifi = c.getApplicationContext().getSystemService(WifiManager.class);
        boolean needsLock = Build.VERSION.SDK_INT < 33 || SdkExtensions.getExtensionVersion(Build.VERSION_CODES.TIRAMISU) < 7;
        multicastLock = needsLock && wifi != null ? wifi.createMulticastLock("adb-discovery") : null;
        if (multicastLock != null) multicastLock.setReferenceCounted(false);
    }
    public void start() {
        try {
            if (multicastLock != null) multicastLock.acquire();
            manager.discoverServices("_adb-tls-connect._tcp.", NsdManager.PROTOCOL_DNS_SD, discovery);
        } catch (RuntimeException e) { close(); callback.onPort(0, "ADB discovery unavailable"); }
    }
    private synchronized void releaseMulticast() { if (multicastLock != null && multicastLock.isHeld()) multicastLock.release(); }
    public synchronized void close() {
        closed = true;
        sightings.clear(); activeName = null;
        resolveQueue.close();
        try { manager.stopServiceDiscovery(discovery); }
        catch (IllegalArgumentException ignored) { }
        finally { releaseMulticast(); resolver.shutdownNow(); }
    }
}
