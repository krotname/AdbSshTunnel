package name.krot.adbsshtunnel;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.location.LocationManager;
import android.net.*;
import android.os.*;
import android.provider.Settings;
import java.util.*;

/** Runs only inside the enabled non-root SSH foreground service. */
public final class WirelessDebuggingHold implements AutoCloseable {
    private static final String ADB_WIFI = "adb_wifi_enabled";
    public static final String PERMISSION = "android.permission.WRITE_SECURE_SETTINGS";
    private final Context context;
    private final java.util.function.Consumer<Boolean> listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final WirelessHoldPolicy policy;
    private List<NetworkMonitor.Entry> networks = Collections.emptyList();
    private Boolean lastEnabled;
    private boolean closed;
    private int lastStatus;
    private final Runnable recheck = this::evaluate;
    private final ContentObserver observer = new ContentObserver(main) {
        @Override public void onChange(boolean selfChange) { evaluate(); }
    };
    public WirelessDebuggingHold(Context context, java.util.function.Consumer<Boolean> listener) {
        this.context = context; this.listener = listener;
        policy = new WirelessHoldPolicy(name.krot.adbsshtunnel.Settings.prefs(context)
            .getBoolean("wireless_hold_armed", false));
    }
    public static boolean permitted(Context context) {
        return context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED;
    }
    public static boolean selected(Context context) {
        return name.krot.adbsshtunnel.Settings.prefs(context).getBoolean("wireless_hold", false);
    }
    public static int status(Context context) {
        if (!selected(context)) return R.string.wireless_hold_off;
        if (!permitted(context)) return R.string.wireless_hold_permission;
        if (name.krot.adbsshtunnel.Settings.prefs(context).getBoolean("wireless_hold_rejected", false))
            return R.string.wireless_hold_rejected;
        return name.krot.adbsshtunnel.Settings.prefs(context).getBoolean("wireless_hold_armed", false)
            ? R.string.wireless_hold_armed : R.string.wireless_hold_wait;
    }
    public void start() {
        context.getContentResolver().registerContentObserver(Settings.Global.getUriFor(ADB_WIFI), false, observer);
        evaluate();
    }
    public void networksChanged(List<NetworkMonitor.Entry> entries) { networks = entries; evaluate(); }
    private boolean trustedWifi() {
        return "trusted".equals(trustedWifiState());
    }
    private String trustedWifiState() {
        try {
            LocationManager location = context.getSystemService(LocationManager.class);
            if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
                return "location_permission_missing";
            if (location == null || !location.isLocationEnabled()) return "location_disabled";
            ConnectivityManager manager = context.getSystemService(ConnectivityManager.class);
            if (manager == null) return "connectivity_unavailable";
            String reason = "no_wifi_entry";
            for (NetworkMonitor.Entry entry : networks) {
                // Identity comes from the location-aware NetworkCallback, as it
                // does for SSH admission. Recheck the live handle and addresses
                // instead of depending on legacy WifiManager connection info.
                if (entry.kind != NetworkSettings.Kind.WIFI) continue;
                if (!entry.decision.allowed
                    || !NetworkSettings.read(context, NetworkSettings.Kind.WIFI).decide(entry.identity).allowed) {
                    reason = "wifi_policy_denied"; continue;
                }
                if (entry.addresses.isEmpty()) { reason = "wifi_addresses_unavailable"; continue; }
                Network network = Network.fromNetworkHandle(entry.handle);
                NetworkCapabilities caps = manager.getNetworkCapabilities(network);
                LinkProperties links = manager.getLinkProperties(network);
                if (caps == null || links == null) { reason = "wifi_handle_unavailable"; continue; }
                if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                    || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    || caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                    || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                    || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED)) {
                    reason = "wifi_capabilities_unavailable"; continue;
                }
                if (links.getLinkAddresses().stream().anyMatch(link -> entry.addresses.contains(link.getAddress().getHostAddress())))
                    return "trusted";
                reason = "wifi_addresses_changed";
            }
            return reason;
        } catch (RuntimeException exception) { return "wifi_check_" + exception.getClass().getSimpleName(); }
    }
    public void dump(java.io.PrintWriter writer) {
        writer.println("wireless_hold: selected=" + selected(context)
            + " permission=" + permitted(context) + " armed=" + policy.armed()
            + " developer=" + Settings.Global.getInt(context.getContentResolver(), Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0)
            + " enabled=" + Settings.Global.getInt(context.getContentResolver(), ADB_WIFI, 0)
            + " networks=" + networks.size() + " wifi=" + trustedWifiState());
    }
    private void evaluate() {
        if (closed) return;
        main.removeCallbacks(recheck);
        boolean enabled = Settings.Global.getInt(context.getContentResolver(), ADB_WIFI, 0) == 1;
        if (!Objects.equals(lastEnabled, enabled)) { lastEnabled = enabled; listener.accept(enabled); }
        boolean selected = selected(context) && name.krot.adbsshtunnel.Settings.enabled(context);
        boolean before = policy.armed();
        boolean developer = Settings.Global.getInt(context.getContentResolver(),
            Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1;
        long delay = policy.update(selected, permitted(context) && developer, trustedWifi(), enabled, SystemClock.elapsedRealtime());
        if (before != policy.armed()) name.krot.adbsshtunnel.Settings.prefs(context).edit()
            .putBoolean("wireless_hold_armed", policy.armed()).apply();
        if (enabled || !selected) name.krot.adbsshtunnel.Settings.prefs(context).edit()
            .putBoolean("wireless_hold_rejected", false).apply();
        if (delay == 0) {
            boolean restored = false;
            try { restored = Settings.Global.putInt(context.getContentResolver(), ADB_WIFI, 1); }
            catch (SecurityException ignored) { }
            name.krot.adbsshtunnel.Settings.prefs(context).edit().putBoolean("wireless_hold_rejected", !restored).apply();
            delay = 1_000;
        }
        if (delay > 0) main.postDelayed(recheck, delay);
        int status = status(context);
        if (lastStatus != status) {
            lastStatus = status;
            context.sendBroadcast(new Intent("name.krot.adbsshtunnel.STATE").setPackage(context.getPackageName()));
        }
    }
    @Override public void close() {
        closed = true; main.removeCallbacks(recheck);
        context.getContentResolver().unregisterContentObserver(observer);
    }
}
