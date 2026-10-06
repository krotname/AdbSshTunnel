package com.hardbacknutter.sshd;

import android.app.*;
import android.content.*;
import android.os.*;
import android.service.quicksettings.TileService;
import android.system.Os;
import java.io.*;
import java.net.*;
import java.util.concurrent.*;
import name.krot.adbsshtunnel.*;

/** JNI keeps its upstream class name. The server always runs under the app UID. */
public final class SshdService extends Service {
    static { System.loadLibrary("jni-dropbear"); }
    public static volatile boolean running;
    public static volatile String state = "Stopped";
    private int serverPid;
    private volatile int generation;
    private boolean startupFailed;
    private int activePort;
    private CountDownLatch serverStopped = new CountDownLatch(0);
    private AdbDiscovery discovery;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService probes = Executors.newSingleThreadExecutor();
    private native int start_sshd(String lib, String[] args, String conf, String home, String shell,
                                  String env, boolean shellAccess, boolean publicKeys, boolean singleUsePasswords);
    private native void kill(int pid);
    private native int waitpid(int pid);
    public static native String getDropbearVersion();
    public static void select(Context c, boolean selected) {
        Settings.setEnabled(c, selected);
        if (selected) c.startForegroundService(new Intent(c, SshdService.class));
        else { c.stopService(new Intent(c, SshdService.class)); killOtherAppProcesses(); }
        TileService.requestListeningState(c, new ComponentName(c, TunnelTile.class));
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
        state = "Starting"; startForeground(19191, notification());
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (!Settings.enabled(this)) { stopSelf(); return START_NOT_STICKY; }
        stopNative();
        if (discovery != null) { discovery.close(); discovery = null; }
        startupFailed = false;
        try { KeyPolicy.parse(Settings.keys(this)); } catch (IOException e) { startupFailed = true; publish(e.getMessage()); stopSelf(); return START_NOT_STICKY; }
        boolean rootMode = Settings.prefs(this).getBoolean("root_mode", false);
        if (rootMode) checkRootGuard();
        else {
            publish("Waiting for Wireless Debugging");
            discovery = new AdbDiscovery(this, (port, message) -> main.post(() -> {
                if (!Settings.enabled(this)) return;
                if (port == 0) { stopNative(); publish(message); }
                else if (port != activePort || !running) startForPort(port);
            }));
            discovery.start();
        }
        return START_STICKY;
    }
    private void checkRootGuard() {
        int token = ++generation;
        publish("Checking root ADB protection");
        probes.execute(() -> {
            String failure = null;
            try { RootHelper.verifyGuard(); }
            catch (IOException e) { failure = e.getMessage(); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); failure = "Root check interrupted"; }
            final String error = failure;
            main.post(() -> {
                if (token != generation || !Settings.enabled(this)) return;
                if (error != null) { publish(error); return; }
                startForPort(5555);
            });
        });
    }
    private void startForPort(int port) {
        stopNative();
        int token = ++generation;
        publish("Checking local ADB " + port);
        probes.execute(() -> {
            String failure = null;
            try (Socket socket = new Socket()) { socket.connect(new InetSocketAddress("127.0.0.1", port), 1000); }
            catch (IOException e) { failure = "Local ADB unavailable; configure debugging first"; }
            final String error = failure;
            main.post(() -> {
                if (token != generation || !Settings.enabled(this)) return;
                if (error != null) { publish(error); return; }
                try {
                    Settings.writePolicy(this, port);
                    File config = Settings.config(this);
                    String[] args = {"sshd", "-e", "-R", "-F", "-s", "-k", "-T", "3", "-p", "0.0.0.0:19191", "-c", "/system/bin/false"};
                    serverPid = start_sshd(getApplicationInfo().nativeLibraryDir, args, config.getPath(), getFilesDir().getPath(), "/system/bin/false", "", false, true, false);
                    if (serverPid <= 0) throw new IOException("SSH startup failed");
                    int startedPid = serverPid;
                    CountDownLatch stopped = new CountDownLatch(1); serverStopped = stopped;
                    activePort = port;
                    Settings.prefs(this).edit().putInt("adb_port", port).apply();
                    new Thread(() -> {
                        try { waitpid(startedPid); } finally { stopped.countDown(); }
                        main.post(() -> {
                            if (serverPid == startedPid) { serverPid = 0; running = false; publish("SSH exited"); }
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
                            running = listening;
                            if (!listening) stopNative();
                            publish(listening ? "SSH :19191 → ADB :" + port : "SSH listener failed");
                        });
                    });
                } catch (IOException e) { stopNative(); publish(e.getMessage()); }
            });
        });
    }
    private void stopNative() {
        ++generation; running = false; activePort = 0;
        if (serverPid > 0) {
            kill(serverPid); serverPid = 0;
            try { serverStopped.await(1, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        killOtherAppProcesses();
    }
    @Override public void onDestroy() {
        stopNative(); if (discovery != null) discovery.close(); discovery = null;
        probes.shutdownNow(); main.removeCallbacksAndMessages(null);
        if (!startupFailed) state = "Stopped";
        TileService.requestListeningState(this, new ComponentName(this, TunnelTile.class));
        sendBroadcast(new Intent("name.krot.adbsshtunnel.STATE").setPackage(getPackageName()));
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
