package name.krot.adbsshtunnel;
import android.content.*;
import com.hardbacknutter.sshd.SshdService;
public final class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent intent) {
        String action = intent.getAction();
        if (RootSettings.configured(c)) RootMaintenanceJob.schedule(c);
        if ((Intent.ACTION_BOOT_COMPLETED.equals(action) || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) && Settings.enabled(c))
            c.startForegroundService(new Intent(c, SshdService.class));
        if (Intent.ACTION_MY_PACKAGE_REPLACED.equals(action) && RootSettings.configured(c)) {
            PendingResult pending = goAsync();
            new Thread(() -> {
                try { RootHelper.configure(c, RootSettings.read(c)); }
                catch (Exception e) {
                    Settings.prefs(c).edit().putString("root_controller_error", e.getMessage()).apply();
                } finally { pending.finish(); }
            }, "controller-update").start();
        }
    }
}
