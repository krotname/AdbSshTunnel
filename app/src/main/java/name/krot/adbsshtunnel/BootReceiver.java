package name.krot.adbsshtunnel;
import android.content.*;
import com.hardbacknutter.sshd.SshdService;
public final class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent intent) {
        String action = intent.getAction();
        if ((Intent.ACTION_BOOT_COMPLETED.equals(action) || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) && Settings.enabled(c))
            c.startForegroundService(new Intent(c, SshdService.class));
    }
}
