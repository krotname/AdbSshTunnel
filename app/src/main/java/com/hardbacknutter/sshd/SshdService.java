package com.hardbacknutter.sshd;

import android.app.*;
import android.content.*;
import android.os.*;
import android.service.quicksettings.TileService;
import android.system.Os;
import android.Manifest;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import java.io.*;
import java.net.*;
import java.util.concurrent.*;
import java.util.*;
import name.krot.adbsshtunnel.*;

/** JNI keeps its upstream class name. The server always runs under the app UID. */
public final class SshdService extends Service {
    static { System.loadLibrary("jni-dropbear"); }
    public static volatile boolean running;
    public static volatile String state = "Stopped";
    private int serverPid;
    private volatile int generation;
    private int discoveryGeneration;
    private boolean startupFailed;
    private int activePort;
    private int desiredPort, pendingPort;
    private NetworkMonitor networkMonitor;
    public static volatile List<NetworkMonitor.Entry> networks = Collections.emptyList();
    private String networkSignature = "";
    private CountDownLatch serverStopped = new CountDownLatch(0);
    private AdbDiscovery discovery;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService probes = Executors.newSingleThreadExecutor();
    private native int start_sshd(String lib, String[] args, String conf, String home, String shell,
                                  String env, boolean shellAccess, boolean publicKeys, boolean singleUsePasswords);
    private native void kill(int pid);
    private native int waitpid(int pid);
    public static native String getDropbearVersion();
    public static boolean select(Context c, boolean selected) {
        if (selected) {
            try { KeyPolicy.parse(Settings.keys(c)); }
            catch (IOException e) {
                Settings.setEnabled(c, false);
                c.stopService(new Intent(c, SshdService.class)); killOtherAppProcesses();
                state = e.getMessage();
                TileService.requestListeningState(c, new ComponentName(c, TunnelTile.class));
                c.sendBroadcast(new Intent("name.krot.adbsshtunnel.STATE").setPackage(c.getPackageName()));
                return false;
            }
        }
        Settings.setEnabled(c, selected);
        if (selected) c.startForegroundService(new Intent(c, SshdService.class).putExtra("from_activity", c instanceof Activity));
        else { c.stopService(new Intent(c, SshdService.class)); killOtherAppProcesses(); }
        TileService.requestListeningState(c, new ComponentName(c, TunnelTile.class));
        return true;
    }
    private static void killOtherAppProcesses() {
        File[] entries = new File("/proc").listFiles(); if (entries == null) return;
        for (File entry : entries) {
            try {
                int pid = Integer.parseInt(entry.getName());
                if (pid != android.os.Process.myPid() && Os.stat(entry.getPath()).st_uid == android.os.Process.myUid())
                    android.os.Process.killProcess(pid);
            } catch (Exception ignored) { }
        }
    }
    private Notification notification() {
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, "tunnel").setContentTitle("ADB SSH Tunnel")
            .setContentText(state).setSmallIcon(name.krot.adbsshtunnel.R.drawable.ic_tunnel)
            .setContentIntent(open).setOngoing(true).build();
    }
    private void publish(String text) {
        state = text;
        getSystemService(NotificationManager.class).notify(19191, notification());
        TileService.requestListeningState(this, new ComponentName(this, TunnelTile.class));
        sendBroadcast(new Intent("name.krot.adbsshtunnel.STATE").setPackage(getPackageName()));
    }
    @Override public void onCreate() {
        super.onCreate(); killOtherAppProcesses();
        getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel("tunnel", "ADB SSH Tunnel", NotificationManager.IMPORTANCE_LOW));
        state = "Starting";
        startForeground(19191, notification(), Build.VERSION.SDK_INT >= 34 ? ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE : 0);
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (!Settings.enabled(this)) { stopSelf(); return START_NOT_STICKY; }
        stopNative();
        int discoveryToken = ++discoveryGeneration;
        if (discovery != null) { discovery.close(); discovery = null; }
        if (networkMonitor != null) networkMonitor.close();
        desiredPort = 0; networks = Collections.emptyList(); networkSignature = "";
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            && ((intent != null && intent.getBooleanExtra("from_activity", false))
                || checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED)) {
            try { startForeground(19191, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                | (Build.VERSION.SDK_INT >= 34 ? ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE : 0)); }
            catch (SecurityException e) { publish("Open the app to identify Wi-Fi"); }
        }
        networkMonitor = new NetworkMonitor(this, entries -> { networks = entries; reconcile(); });
        networkMonitor.start();
        startupFailed = false;
        try { KeyPolicy.parse(Settings.keys(this)); } catch (IOException e) { Settings.setEnabled(this, false); startupFailed = true; publish(e.getMessage()); stopSelf(); return START_NOT_STICKY; }
        boolean rootMode = Settings.prefs(this).getBoolean("root_mode", false);
        if (rootMode) checkRootGuard();
        else {
            publish("Waiting for Wireless Debugging");
            discovery = new AdbDiscovery(this, (port, message) -> main.post(() -> {
                if (discoveryToken != discoveryGeneration || !Settings.enabled(this) || Settings.prefs(this).getBoolean("root_mode", false)) return;
                desiredPort = port;
                if (port == 0) { stopNative(); publish(message); }
                else reconcile();
            }));
            discovery.start();
        }
        return START_STICKY;
    }
    private void checkRootGuard() {
        int token = discoveryGeneration;
        publish("Checking root ADB protection");
        probes.execute(() -> {
            String failure = null;
            try { RootHelper.verifyGuard(); }
            catch (IOException e) { failure = e.getMessage(); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); failure = "Root check interrupted"; }
            final String error = failure;
            main.post(() -> {
                if (token != discoveryGeneration || !Settings.enabled(this) || !Settings.prefs(this).getBoolean("root_mode", false)) return;
                if (error != null) { publish(error); return; }
                desiredPort = 5555; reconcile();
            });
        });
    }
    private void reconcile() {
        if (!Settings.enabled(this)) return;
        SortedSet<String> addresses = new TreeSet<>();
        StringBuilder signature = new StringBuilder();
        for (NetworkMonitor.Entry entry : networks) {
            signature.append(entry.handle).append('/').append(entry.kind).append('/')
                .append(entry.identity == null ? -1 : entry.identity.length()).append(':').append(entry.identity)
                .append('/').append(entry.decision.allowed).append('/').append(entry.addresses).append(';');
            if (entry.decision.allowed) addresses.addAll(entry.addresses);
        }
        String updated = signature.toString();
        if (!updated.equals(networkSignature)) { stopNative(); networkSignature = updated; }
        if (addresses.isEmpty()) {
            stopNative();
            publish(networks.isEmpty() ? "No available Wi-Fi or mobile Internet network"
                : "SSH closed: no allowed network. Check lists and Android permissions");
            return;
        }
        if (desiredPort == 0) { publish("Waiting for local ADB: configure the selected mode"); return; }
        if (activePort == desiredPort && (running || pendingPort == desiredPort)) return;
        if (pendingPort == desiredPort) return;
        startForPort(desiredPort, !Settings.prefs(this).getBoolean("root_mode", false), addresses);
    }
    private void startForPort(int port, boolean wireless, SortedSet<String> addresses) {
        stopNative();
        int token = ++generation;
        pendingPort = port;
        publish("Checking local ADB " + port);
        probes.execute(() -> {
            String failure = null;
            try {
                if (wireless) AdbTlsProbe.verify(port);
                else AdbTlsProbe.verifyPlain(port);
            }
            catch (IOException e) { failure = "Local ADB unavailable; configure debugging first"; }
            final String error = failure;
            main.post(() -> {
                if (token != generation || !Settings.enabled(this) || wireless == Settings.prefs(this).getBoolean("root_mode", false)) return;
                if (error != null) { pendingPort = 0; publish(error); return; }
                try {
                    Settings.writePolicy(this, 5555);
                    File config = Settings.config(this);
                    List<String> arguments = new ArrayList<>(Arrays.asList("sshd", "-e", "-R", "-F", "-s", "-k", "-T", "3", "-c", "/system/bin/false"));
                    arguments.add("-p"); arguments.add("127.0.0.1:19191");
                    for (String address : addresses) { arguments.add("-p"); arguments.add(address + ":19191"); }
                    serverPid = start_sshd(getApplicationInfo().nativeLibraryDir, arguments.toArray(new String[0]), config.getPath(), getFilesDir().getPath(), "/system/bin/false", "ADB_TUNNEL_PORT=" + port, false, true, false);
                    if (serverPid <= 0) throw new IOException("SSH startup failed");
                    int startedPid = serverPid;
                    CountDownLatch stopped = new CountDownLatch(1); serverStopped = stopped;
                    activePort = port;
                    Settings.prefs(this).edit().putInt("adb_port", port).apply();
                    new Thread(() -> {
                        try { waitpid(startedPid); } finally { stopped.countDown(); }
                        main.post(() -> {
                            if (serverPid == startedPid && token == generation) { serverPid = 0; failAndStop("SSH exited; enable the tunnel to retry"); }
                        });
                    }, "ssh-exit").start();
                    probes.execute(() -> {
                        boolean ready = false;
                        for (int attempt = 0; attempt < 10 && token == generation; attempt++) {
                            try (Socket socket = new Socket()) {
                                socket.connect(new InetSocketAddress("127.0.0.1", 19191), 300); socket.setSoTimeout(300);
                                String banner = new BufferedReader(new InputStreamReader(socket.getInputStream())).readLine();
                                ready = banner != null && banner.startsWith("SSH-2.0-dropbear");
                                if (ready) break;
                            } catch (IOException ignored) { }
                            try { Thread.sleep(150); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
                        }
                        final boolean listening = ready;
                        main.post(() -> {
                            if (token != generation) return;
                            if (!listening) { failAndStop("SSH listener failed"); return; }
                            running = true;
                            pendingPort = 0;
                            publish("SSH :19191 → ADB :" + port + " · client target 127.0.0.1:5555");
                        });
                    });
                } catch (IOException e) { failAndStop(e.getMessage()); }
            });
        });
    }
    private void failAndStop(String message) {
        Settings.setEnabled(this, false); startupFailed = true;
        stopNative(); publish(message); stopSelf();
    }
    private void stopNative() {
        ++generation; running = false; activePort = 0; pendingPort = 0;
        if (serverPid > 0) {
            kill(serverPid); serverPid = 0;
            try { serverStopped.await(1, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        killOtherAppProcesses();
    }
    @Override public void onDestroy() {
        ++discoveryGeneration;
        stopNative(); if (discovery != null) discovery.close(); discovery = null;
        if (networkMonitor != null) networkMonitor.close(); networkMonitor = null; networks = Collections.emptyList();
        probes.shutdownNow(); main.removeCallbacksAndMessages(null);
        if (!startupFailed) state = "Stopped";
        TileService.requestListeningState(this, new ComponentName(this, TunnelTile.class));
        sendBroadcast(new Intent("name.krot.adbsshtunnel.STATE").setPackage(getPackageName()));
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
