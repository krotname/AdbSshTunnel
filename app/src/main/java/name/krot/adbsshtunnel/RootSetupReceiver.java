package name.krot.adbsshtunnel;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Private provisioning entry point for an existing root/USB administration session. */
public final class RootSetupReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (!"name.krot.adbsshtunnel.CONFIGURE_ROOT".equals(intent.getAction())) return;
        PendingResult pending = goAsync();
        new Thread(() -> {
            try {
                RootPolicy policy = new RootPolicy(intent.getBooleanExtra("enabled", false),
                    value(intent, "wifi_prefix"), value(intent, "hotspot_ssid"), value(intent, "wireguard_iface"));
                RootHelper.configure(context, policy);
                Settings.prefs(context).edit().remove("root_controller_error").apply();
            } catch (Exception e) {
                Settings.prefs(context).edit().putString("root_controller_error", e.getMessage()).apply();
            } finally {
                context.sendBroadcast(new Intent("name.krot.adbsshtunnel.STATE").setPackage(context.getPackageName()));
                pending.finish();
            }
        }, "root-provision").start();
    }
    private static String value(Intent intent, String name) {
        String value = intent.getStringExtra(name);
        return value == null ? "" : value;
    }
}
