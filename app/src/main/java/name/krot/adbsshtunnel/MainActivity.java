package name.krot.adbsshtunnel;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.view.*;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.google.android.material.textfield.*;
import com.hardbacknutter.sshd.SshdService;
import java.io.*;
import java.net.*;
import java.util.*;

public final class MainActivity extends AppCompatActivity {
    private LinearLayout content;
    private TextView status;
    private SwitchMaterial toggle;
    private TextView fingerprint;
    private boolean checkingAddress;
    private NetworkMonitor networkMonitor;
    private NetworkPolicyView wifiPolicy, mobilePolicy;
    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { refresh(); }
    };
    private TextView text(String value, int size) {
        TextView view = new TextView(this); view.setText(value); view.setTextSize(size); view.setTextIsSelectable(true);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2); params.bottomMargin = dp(12);
        content.addView(view, params); return view;
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private void button(String label, Runnable action) {
        MaterialButton button = new MaterialButton(this); button.setText(label); button.setOnClickListener(v -> action.run());
        content.addView(button, new LinearLayout.LayoutParams(-1, -2));
    }
    private void error(String message) { new androidx.appcompat.app.AlertDialog.Builder(this).setMessage(message).setPositiveButton("OK", null).show(); }
    @Override public void onCreate(Bundle state) {
        super.onCreate(state); WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true);
        content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2)); setContentView(scroll);
        ViewCompat.setOnApplyWindowInsetsListener(scroll, (v, insets) -> {
            androidx.core.graphics.Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout() | WindowInsetsCompat.Type.ime());
            content.setPadding(bars.left + dp(20), bars.top + dp(20), bars.right + dp(20), bars.bottom + dp(20)); return insets;
        });
        text("ADB SSH Tunnel", 28);
        text("Private-key clients. Forwarding only to this phone’s local ADB.", 16);
        status = text("Stopped", 18);
        toggle = new SwitchMaterial(this); toggle.setText("SSH tunnel"); content.addView(toggle);
        toggle.setOnCheckedChangeListener((v, checked) -> {
            if (checked == name.krot.adbsshtunnel.Settings.enabled(this)) return;
            try { if (!SshdService.select(this, checked)) { error(SshdService.state); toggle.setChecked(false); } }
            catch (Exception e) { error(e.getMessage()); toggle.setChecked(false); }
        });
        button("Add to Quick Settings", () -> {
            if (Build.VERSION.SDK_INT >= 33) getSystemService(StatusBarManager.class).requestAddTileService(
                new ComponentName(this, TunnelTile.class), "ADB SSH Tunnel", Icon.createWithResource(this, R.drawable.ic_tunnel), getMainExecutor(), result -> {
                    if (result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED || result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED)
                        error("Tile added. Move it to the first row in Quick Settings edit mode.");
                    else error("Tile was not added. Use Quick Settings edit mode.");
                });
            else error("Open Quick Settings edit mode and add ADB SSH Tunnel.");
        });
        text("Network access", 22);
        text("Wi-Fi and mobile rules are independent of WireGuard. New lists start closed. Saving a rule closes existing SSH connections. Android permissions identify the network; unavailable identities stay closed.", 15);
        button("Grant Wi-Fi and mobile identity permissions", () -> requestPermissions(new String[] {
            Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.READ_PHONE_STATE}, 2));
        button("Background Wi-Fi identity permission", () -> {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())));
            Toast.makeText(this, "For Wi-Fi rules after reboot: Location → Allow all the time. The app reads SSID, not GPS coordinates.", Toast.LENGTH_LONG).show();
        });
        button("Open Android location settings", () -> startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)));
        Runnable rulesChanged = () -> {
            if (name.krot.adbsshtunnel.Settings.enabled(this)) SshdService.select(this, true);
            if (networkMonitor != null) networkMonitor.refresh();
        };
        wifiPolicy = new NetworkPolicyView(this, NetworkSettings.Kind.WIFI, rulesChanged); content.addView(wifiPolicy);
        mobilePolicy = new NetworkPolicyView(this, NetworkSettings.Kind.MOBILE, rulesChanged); content.addView(mobilePolicy);
        text("ADB setup", 22);
        SwitchMaterial root = new SwitchMaterial(this); root.setText("Root ADB (port 5555)");
        root.setChecked(name.krot.adbsshtunnel.Settings.prefs(this).getBoolean("root_mode", false)); content.addView(root);
        root.setOnCheckedChangeListener((view, selected) -> {
            name.krot.adbsshtunnel.Settings.prefs(this).edit().putBoolean("root_mode", selected).commit();
            if (name.krot.adbsshtunnel.Settings.enabled(this)) SshdService.select(this, true);
        });
        button("Configure installed root helper", () -> {
            new Thread(() -> {
                try { String result = RootHelper.configure(); runOnUiThread(() -> error(result)); }
                catch (Exception e) { runOnUiThread(() -> error(e.getMessage())); }
            }, "root-helper").start();
        });
        text("Without root: enable Wireless Debugging on Wi-Fi, then Pair device with pairing code. Run adb pair PHONE:PAIR_PORT on your trusted computer. The app discovers the connect port. Android may require re-enabling debugging after reboot or network changes.", 15);
        button("Open Developer options", () -> {
            try { startActivity(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)); }
            catch (ActivityNotFoundException e) { startActivity(new Intent(Settings.ACTION_SETTINGS)); }
        });
        text("Trusted SSH keys", 22);
        TextInputLayout keyInput = new TextInputLayout(this); keyInput.setHint("OpenSSH public keys (one per line)");
        TextInputEditText keys = new TextInputEditText(keyInput.getContext()); keys.setMinLines(3); keys.setMaxLines(8);
        keys.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        keys.setText(name.krot.adbsshtunnel.Settings.keys(this)); keyInput.addView(keys); content.addView(keyInput);
        button("Save trusted keys", () -> {
            try {
                name.krot.adbsshtunnel.Settings.saveKeys(this, Objects.requireNonNull(keys.getText()).toString());
                if (name.krot.adbsshtunnel.Settings.enabled(this)) SshdService.select(this, true);
                error("Keys saved. Existing SSH connections have been closed.");
            } catch (IOException e) { error(e.getMessage()); }
        });
        text("Connect", 22);
        text("ssh -N -L 15555:127.0.0.1:5555 -p 19191 -i YOUR_KEY USER@PHONE\nadb connect 127.0.0.1:15555\n\nThe target stays 127.0.0.1:5555 when Android changes its Wireless Debugging port. Reconnect SSH and ADB after a port or network change. Pair ADB first. Pin the SSH host key through physical USB before WAN use.", 15);
        text("A public routable mobile IPv4 is required for incoming WAN connections. An interface address does not prove public reachability. CGNAT, operator filtering and firewall rules can prevent connections.", 15);
        TextView networkDiagnosis = text("External IPv4 has not been checked. Refresh sends an HTTPS request to api.ipify.org.", 15);
        button("Refresh addresses, external IPv4 and fingerprint", () -> {
            if (networkMonitor != null) networkMonitor.refresh(); refresh();
            if (checkingAddress) return;
            checkingAddress = true; networkDiagnosis.setText("Checking external IPv4…");
            new Thread(() -> {
                String result;
                try { result = NetworkDiagnostics.inspect(this); }
                catch (Exception e) { result = "External IPv4 check failed: " + e.getClass().getSimpleName() + ": " + e.getMessage(); }
                final String message = result;
                runOnUiThread(() -> { checkingAddress = false; if (!isFinishing() && !isDestroyed()) networkDiagnosis.setText(message); });
            }, "network-diagnostics").start();
        });
        text("Server public key", 22);
        fingerprint = text(hostKey(), 14);
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, 1);
        refresh();
    }
    private String hostKey() {
        try {
            File file = new File(name.krot.adbsshtunnel.Settings.config(this), "dropbear_ed25519_host_key");
            if (!file.isFile()) return "Created on first SSH startup. Verify via USB before trusting it.";
            try (InputStream stream = new FileInputStream(file)) {
                String key = HostKeyPublic.read(stream);
                return KeyPolicy.fingerprint(key) + "\n" + key;
            }
        } catch (Exception e) { return "Cannot read server fingerprint: " + e.getMessage(); }
    }
    private void watchNetworks() {
        if (networkMonitor != null) networkMonitor.close();
        networkMonitor = new NetworkMonitor(this, entries -> { wifiPolicy.showNetworks(entries); mobilePolicy.showNetworks(entries); });
        networkMonitor.start();
    }
    private void refresh() { status.setText(SshdService.state); toggle.setChecked(name.krot.adbsshtunnel.Settings.enabled(this)); if (fingerprint != null) fingerprint.setText(hostKey()); }
    @Override protected void onStart() {
        super.onStart();
        ContextCompat.registerReceiver(this, receiver,
                new IntentFilter("name.krot.adbsshtunnel.STATE"), ContextCompat.RECEIVER_NOT_EXPORTED);
        watchNetworks(); refresh();
    }
    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request == 2) {
            watchNetworks();
            if (name.krot.adbsshtunnel.Settings.enabled(this)) SshdService.select(this, true);
        }
    }
    @Override protected void onStop() { if (networkMonitor != null) networkMonitor.close(); networkMonitor = null; unregisterReceiver(receiver); super.onStop(); }
}
