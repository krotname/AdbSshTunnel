package name.krot.adbsshtunnel;

import android.content.Context;
import java.io.IOException;

public final class RootSettings {
    private RootSettings() { }
    public static RootPolicy read(Context context) {
        android.content.SharedPreferences prefs = Settings.prefs(context);
        return new RootPolicy(prefs.getBoolean("root_adb_enabled", false),
            prefs.getString("adb_wifi_prefix", ""), prefs.getString("adb_hotspot_ssid", ""),
            prefs.getString("adb_wireguard_iface", ""));
    }
    public static boolean configured(Context context) {
        return Settings.prefs(context).getBoolean("root_controller_configured", false);
    }
    static void save(Context context, RootPolicy policy) throws IOException {
        if (!Settings.prefs(context).edit().putBoolean("root_controller_configured", true)
                .putBoolean("root_adb_enabled", policy.enabled).putString("adb_wifi_prefix", policy.wifiPrefix)
                .putString("adb_hotspot_ssid", policy.hotspotSsid)
                .putString("adb_wireguard_iface", policy.wireguardInterface).commit())
            throw new IOException("Cannot save direct ADB policy");
    }
}
