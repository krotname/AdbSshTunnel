package name.krot.adbsshtunnel;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.drawable.Icon;
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
        text("ssh -N -L 15555:127.0.0.1:ADB_PORT -p 19191 -i YOUR_KEY USER@PHONE\nadb connect 127.0.0.1:15555\n\nPair ADB first when using Wireless Debugging. Pin the SSH host key through a physical USB connection before using WAN.", 15);
        TextView addresses = text(addresses(), 15);
        text("A public routable mobile IPv4 is required for incoming WAN connections. An interface address does not prove public reachability. CGNAT, operator filtering and firewall rules can prevent connections.", 15);
        TextView networkDiagnosis = text("External IPv4 has not been checked. Refresh sends an HTTPS request to api.ipify.org.", 15);
        button("Refresh addresses, external IPv4 and fingerprint", () -> {
            addresses.setText(addresses()); refresh();
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
            try (DataInputStream stream = new DataInputStream(new FileInputStream(file))) {
                int typeLength = stream.readInt();
                if (typeLength != 11) return "Unexpected server key format";
                byte[] type = new byte[typeLength]; stream.readFully(type);
                if (!new String(type, java.nio.charset.StandardCharsets.US_ASCII).equals("ssh-ed25519")) return "Unexpected server key type";
                int publicLength = stream.readInt(); if (publicLength != 32) return "Invalid server public key";
                byte[] publicBytes = new byte[publicLength]; stream.readFully(publicBytes);
                ByteArrayOutputStream blob = new ByteArrayOutputStream(); DataOutputStream out = new DataOutputStream(blob);
                out.writeInt(type.length); out.write(type); out.writeInt(publicBytes.length); out.write(publicBytes);
                String key = "ssh-ed25519 " + Base64.getEncoder().encodeToString(blob.toByteArray());
                return KeyPolicy.fingerprint(key) + "\n" + key;
            }
        } catch (Exception e) { return "Cannot read server fingerprint: " + e.getMessage(); }
    }
    private String addresses() {
        StringBuilder result = new StringBuilder();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface iface = interfaces.nextElement(); if (!iface.isUp()) continue;
                Enumeration<InetAddress> addrs = iface.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress addr = addrs.nextElement();
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) result.append(iface.getName()).append(": ").append(addr.getHostAddress()).append(":19191\n");
                }
            }
        } catch (SocketException e) { return "Network addresses unavailable"; }
        return result.length() > 0 ? result.toString() : "No network address";
    }
    private void refresh() { status.setText(SshdService.state); toggle.setChecked(name.krot.adbsshtunnel.Settings.enabled(this)); if (fingerprint != null) fingerprint.setText(hostKey()); }
    @Override protected void onStart() {
        super.onStart();
        ContextCompat.registerReceiver(this, receiver,
                new IntentFilter("name.krot.adbsshtunnel.STATE"), ContextCompat.RECEIVER_NOT_EXPORTED);
        refresh();
    }
    @Override protected void onStop() { unregisterReceiver(receiver); super.onStop(); }
}
