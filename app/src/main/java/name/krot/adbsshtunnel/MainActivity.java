package name.krot.adbsshtunnel;

import android.Manifest;
import android.app.StatusBarManager;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.content.ContextCompat;
import androidx.core.os.LocaleListCompat;
import androidx.core.view.*;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.google.android.material.textfield.*;
import com.hardbacknutter.sshd.SshdService;
import java.io.*;
import java.util.*;

public final class MainActivity extends AppCompatActivity {
    private final Set<String> expandedSections = new HashSet<>();
    private LinearLayout page;
    private ScrollView scroller;
    private TextView status, wifiSummary, mobileSummary, rootStatus;
    private TextView wirelessHoldStatus;
    private TextView adbSummary;
    private SwitchMaterial toggle;
    private SwitchMaterial persistentAdb;
    private boolean rootRequest;
    private NetworkMonitor networkMonitor;
    private NetworkPolicyView editor;
    private List<NetworkMonitor.Entry> networks = Collections.emptyList();
    private boolean compact, checkingAddress;
    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { refresh(); }
    };

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private String s(int resource) { return getString(resource); }
    private TextView text(LinearLayout parent, String value, int size) {
        TextView view = new TextView(this);
        view.setText(value); view.setTextSize(size);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = dp(compact ? 4 : 8);
        parent.addView(view, params);
        return view;
    }
    private MaterialButton button(LinearLayout parent, int label, Runnable action) {
        MaterialButton view = new MaterialButton(this);
        view.setText(label); view.setAllCaps(false);
        view.setOnClickListener(v -> action.run());
        parent.addView(view, new LinearLayout.LayoutParams(-1, -2));
        return view;
    }
    private void message(String message) {
        new androidx.appcompat.app.AlertDialog.Builder(this).setMessage(UiText.status(this, message))
            .setPositiveButton(R.string.close, null).show();
    }
    private void detail(int title, String value) {
        TextView body = new TextView(this);
        body.setText(value); body.setTextSize(15); body.setTextIsSelectable(true);
        body.setPadding(dp(20), dp(12), dp(20), dp(12));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(body);
        new androidx.appcompat.app.AlertDialog.Builder(this).setTitle(title).setView(scroll)
            .setPositiveButton(R.string.close, null).show();
    }
    private LinearLayout card(LinearLayout row) {
        MaterialCardView card = new MaterialCardView(this);
        card.setRadius(dp(20)); card.setCardElevation(0);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(dp(4), dp(4), dp(4), dp(4));
        row.addView(card, params);
        LinearLayout inner = new LinearLayout(this);
        inner.setOrientation(LinearLayout.VERTICAL);
        inner.setPadding(dp(12), dp(compact ? 8 : 16), dp(12), dp(compact ? 8 : 16));
        card.addView(inner);
        return inner;
    }
    private MaterialButton action(LinearLayout parent, int label, Runnable run) {
        MaterialButton view = new MaterialButton(this, null,
            androidx.appcompat.R.attr.borderlessButtonStyle);
        view.setText(label); view.setAllCaps(false); view.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        view.setOnClickListener(v -> run.run());
        parent.addView(view, new LinearLayout.LayoutParams(-1, -2));
        return view;
    }
    private LinearLayout section(String id, int title, String summary) {
        LinearLayout group = card(page);
        MaterialButton heading = action(group, title, () -> {});
        heading.setIconGravity(MaterialButton.ICON_GRAVITY_END);
        TextView subtitle = text(group, summary, 14);
        if ("adb".equals(id)) adbSummary = subtitle;
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        group.addView(body);
        Runnable render = () -> {
            boolean expanded = expandedSections.contains(id);
            body.setVisibility(expanded ? View.VISIBLE : View.GONE);
            heading.setIconResource(expanded ? R.drawable.ic_expand_less : R.drawable.ic_expand_more);
            ViewCompat.setStateDescription(heading, s(expanded ? R.string.expanded : R.string.collapsed));
        };
        heading.setOnClickListener(v -> {
            if (!expandedSections.remove(id)) expandedSections.add(id);
            render.run();
        });
        render.run();
        return body;
    }
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if (state != null) {
            ArrayList<String> expanded = state.getStringArrayList("expanded_sections");
            if (expanded != null) expandedSections.addAll(expanded);
        }
        compact = getResources().getConfiguration().screenHeightDp < 650;
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        LinearLayout shell = new LinearLayout(this);
        shell.setOrientation(LinearLayout.VERTICAL);
        TextView heading = text(shell, s(R.string.app_name), 20);
        heading.setTypeface(null, Typeface.BOLD);
        heading.setPadding(dp(20), dp(8), dp(20), 0);
        page = new LinearLayout(this) {
            @Override protected void onMeasure(int widthSpec, int heightSpec) {
                int width = Math.min(View.MeasureSpec.getSize(widthSpec), dp(720));
                super.onMeasure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), heightSpec);
            }
        };
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(12), 0, dp(12), dp(16));
        scroller = new ScrollView(this);
        scroller.setClipToPadding(false);
        scroller.addView(page, new android.widget.FrameLayout.LayoutParams(-1, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL));
        shell.addView(scroller, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(shell);
        ViewCompat.setOnApplyWindowInsetsListener(shell, (v, insets) -> {
            androidx.core.graphics.Insets bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            shell.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return WindowInsetsCompat.CONSUMED;
        });
        showPage();
        if (state != null) scroller.post(() -> scroller.scrollTo(0, state.getInt("scroll", 0)));
        if (Build.VERSION.SDK_INT >= 33
            && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        state.putStringArrayList("expanded_sections", new ArrayList<>(expandedSections));
        state.putInt("scroll", scroller.getScrollY());
        super.onSaveInstanceState(state);
    }
    private void showPage() {
        int scroll = scroller.getScrollY();
        page.removeAllViews(); status = null; toggle = null; wifiSummary = null; mobileSummary = null;
        rootStatus = null; persistentAdb = null; wirelessHoldStatus = null; adbSummary = null;
        tunnelPage(); accessSettings(); adbSettings(); advancedSettings();
        refresh();
        scroller.post(() -> scroller.scrollTo(0, scroll));
    }
    private void tunnelPage() {
        LinearLayout primary = card(page);
        toggle = new SwitchMaterial(this);
        toggle.setText(R.string.ssh_tunnel);
        primary.addView(toggle);
        toggle.setOnCheckedChangeListener((v, checked) -> {
            if (checked == name.krot.adbsshtunnel.Settings.enabled(this)) return;
            try {
                if (!SshdService.select(this, checked)) { message(UiText.status(this, SshdService.state)); toggle.setChecked(false); }
            } catch (Exception e) { message(e.getMessage()); toggle.setChecked(false); }
            refresh();
        });
        status = text(primary, "", 16);
        status.setOnClickListener(v -> detail(R.string.status_details, UiText.status(this, SshdService.state)));
        button(primary, R.string.connection_details, this::connectionDetails);
    }
    private void connectionDetails() {
        LinearLayout body = new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(20), dp(8), dp(20), dp(8));
        text(body, s(R.string.connect_command) + "\n\n" + s(R.string.connect_help), 15).setTextIsSelectable(true);
        text(body, networkDescription(null) + "\n\n" + hostKey(), 14).setTextIsSelectable(true);
        action(body, R.string.address_check, this::checkAddress);
        ScrollView scroll = new ScrollView(this); scroll.addView(body);
        new androidx.appcompat.app.AlertDialog.Builder(this).setTitle(R.string.connection_details).setView(scroll)
            .setPositiveButton(R.string.close, null).show();
    }
    private void accessSettings() {
        long count = Arrays.stream(name.krot.adbsshtunnel.Settings.keys(this).split("\n"))
            .filter(line -> !line.trim().isEmpty()).count();
        LinearLayout access = section("access", R.string.access_settings, getString(R.string.key_count, count));
        action(access, R.string.wifi_networks, () -> editNetwork(NetworkSettings.Kind.WIFI));
        wifiSummary = text(access, "", 14);
        action(access, R.string.mobile_operators, () -> editNetwork(NetworkSettings.Kind.MOBILE));
        mobileSummary = text(access, "", 14);
        action(access, R.string.trusted_keys, this::editKeys);
        action(access, R.string.permissions, this::permissions);
    }
    private boolean rootMode() {
        return name.krot.adbsshtunnel.Settings.prefs(this).getBoolean("root_mode", false);
    }
    private void adbSettings() {
        LinearLayout adb = section("adb", R.string.adb_mode, s(rootMode() ? R.string.root_adb : R.string.wireless_title));
        Spinner mode = new Spinner(this);
        mode.setContentDescription(s(R.string.adb_mode));
        ArrayAdapter<String> choices = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item,
            new String[]{s(R.string.wireless_mode), s(R.string.root_adb)});
        choices.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        mode.setAdapter(choices); mode.setSelection(rootMode() ? 1 : 0);
        adb.addView(mode, new LinearLayout.LayoutParams(-1, dp(56)));
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); adb.addView(root);
        LinearLayout wireless = new LinearLayout(this); wireless.setOrientation(LinearLayout.VERTICAL); adb.addView(wireless);
        Runnable displayMode = () -> {
            root.setVisibility(rootMode() ? View.VISIBLE : View.GONE);
            wireless.setVisibility(rootMode() ? View.GONE : View.VISIBLE);
        };
        mode.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                boolean selected = position == 1;
                if (selected != rootMode()) {
                    name.krot.adbsshtunnel.Settings.prefs(MainActivity.this).edit().putBoolean("root_mode", selected).commit();
                    if (name.krot.adbsshtunnel.Settings.enabled(MainActivity.this)) SshdService.select(MainActivity.this, true);
                }
                displayMode.run(); refresh();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
        displayMode.run();
        persistentAdb = new SwitchMaterial(this);
        persistentAdb.setText(R.string.persistent_adb);
        persistentAdb.setChecked(RootSettings.read(this).enabled);
        root.addView(persistentAdb);
        persistentAdb.setOnCheckedChangeListener((v, enabled) -> {
            if (enabled == RootSettings.read(this).enabled || rootRequest) return;
            configureRoot(RootSettings.read(this).withEnabled(enabled));
        });
        rootStatus = text(root, s(R.string.root_unconfigured), 14);
        action(root, R.string.root_helper, () -> configureRoot(RootSettings.read(this)));
        action(root, R.string.direct_adb_rules, this::editDirectAdb);
        action(root, R.string.root_status, this::showRootStatus);
        action(root, R.string.adb_help, () -> detail(R.string.root_adb, s(R.string.root_help)));
        text(wireless, s(R.string.wireless_short), 15);
        SwitchMaterial hold = new SwitchMaterial(this);
        hold.setText(R.string.wireless_hold);
        hold.setChecked(WirelessDebuggingHold.selected(this));
        wireless.addView(hold);
        hold.setOnCheckedChangeListener((v, selected) -> {
            if (selected && !WirelessDebuggingHold.permitted(this)) {
                hold.setChecked(false);
                detail(R.string.wireless_hold, s(R.string.wireless_hold_setup));
                return;
            }
            android.content.SharedPreferences.Editor edit = name.krot.adbsshtunnel.Settings.prefs(this)
                .edit().putBoolean("wireless_hold", selected);
            if (!selected) edit.putBoolean("wireless_hold_armed", false);
            edit.commit();
            if (name.krot.adbsshtunnel.Settings.enabled(this)) SshdService.select(this, true);
            refresh();
        });
        wirelessHoldStatus = text(wireless, s(WirelessDebuggingHold.status(this)), 14);
        action(wireless, R.string.developer_options, () -> {
            try { startActivity(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)); }
            catch (ActivityNotFoundException e) { startActivity(new Intent(Settings.ACTION_SETTINGS)); }
        });
        action(wireless, R.string.adb_help, () -> detail(R.string.wireless_title,
            s(R.string.wireless_help) + "\n\n" + s(R.string.wireless_hold_setup)));
    }
    private void configureRoot(RootPolicy policy) {
        if (rootRequest) return;
        rootRequest = true;
        if (persistentAdb != null) persistentAdb.setEnabled(false);
        if (rootStatus != null) rootStatus.setText(R.string.checking_root);
        new Thread(() -> {
            String result; boolean success;
            try {
                result = RootHelper.configure(this, policy);
                name.krot.adbsshtunnel.Settings.prefs(this).edit().remove("root_controller_error").apply();
                success = true;
            } catch (Exception e) {
                result = e.getMessage(); success = false;
                name.krot.adbsshtunnel.Settings.prefs(this).edit().putString("root_controller_error", result).apply();
            }
            final String value = UiText.rootError(this, result); final boolean configured = success;
            runOnUiThread(() -> {
                rootRequest = false;
                if (isFinishing() || isDestroyed()) return;
                if (persistentAdb != null) {
                    persistentAdb.setChecked(RootSettings.read(this).enabled);
                    persistentAdb.setEnabled(true);
                }
                if (rootStatus != null) rootStatus.setText(configured ? s(R.string.root_guard_verified) : value);
                detail(R.string.root_status, value);
            });
        }, "root-controller").start();
    }
    private void showRootStatus() {
        if (!RootSettings.configured(this)) { detail(R.string.root_status, s(R.string.root_unconfigured)); return; }
        new Thread(() -> {
            String result;
            try { result = RootHelper.status(); }
            catch (Exception e) { result = e.getMessage(); }
            final String value = UiText.rootError(this, result);
            runOnUiThread(() -> { if (!isFinishing() && !isDestroyed()) detail(R.string.root_status, value); });
        }, "root-status").start();
    }
    private TextInputEditText rootField(LinearLayout body, int hint, String value) {
        TextInputLayout layout = new TextInputLayout(this); layout.setHint(s(hint));
        TextInputEditText input = new TextInputEditText(layout.getContext());
        input.setSingleLine(true); input.setText(value); layout.addView(input); body.addView(layout);
        return input;
    }
    private void editDirectAdb() {
        RootPolicy policy = RootSettings.read(this);
        LinearLayout body = new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(20), dp(8), dp(20), 0);
        text(body, s(R.string.direct_adb_help), 14);
        TextInputEditText wifi = rootField(body, R.string.adb_wifi_prefix, policy.wifiPrefix);
        TextInputEditText hotspot = rootField(body, R.string.adb_hotspot_ssid, policy.hotspotSsid);
        TextInputEditText wireguard = rootField(body, R.string.adb_wireguard_iface, policy.wireguardInterface);
        ScrollView scroll = new ScrollView(this); scroll.addView(body);
        androidx.appcompat.app.AlertDialog dialog = new androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.direct_adb_rules).setView(scroll).setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save, null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener(v -> {
            try {
                RootPolicy updated = new RootPolicy(policy.enabled, String.valueOf(wifi.getText()),
                    String.valueOf(hotspot.getText()), String.valueOf(wireguard.getText()));
                if (rootRequest) return;
                dialog.dismiss(); configureRoot(updated);
            } catch (IllegalArgumentException e) { message(e.getMessage()); }
        }));
        dialog.show();
    }
    private void advancedSettings() {
        LinearLayout preferences = section("advanced", R.string.advanced_settings, s(R.string.advanced_summary));
        action(preferences, R.string.language, this::language);
        String selected = AppCompatDelegate.getApplicationLocales().toLanguageTags();
        text(preferences, selected.isEmpty() ? s(R.string.system_language) : ("ru".equals(selected) ? "Русский" : "English"), 15);
        action(preferences, R.string.quick_settings, this::addTile);
        action(preferences, R.string.host_key, () -> detail(R.string.host_key, hostKey()));
        action(preferences, R.string.status_details, () -> detail(R.string.status_details, UiText.status(this, SshdService.state)));
        action(preferences, R.string.about_title, () -> detail(R.string.about_title,
            getString(R.string.about_version, versionName()) + "\n\n" + s(R.string.about)));
    }
    private String versionName() {
        try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; }
        catch (PackageManager.NameNotFoundException e) { return ""; }
    }
    private void language() {
        String tags = AppCompatDelegate.getApplicationLocales().toLanguageTags();
        int selected = tags.isEmpty() ? 0 : ("ru".equals(tags) ? 1 : 2);
        String[] labels = {s(R.string.system_language), "Русский", "English"};
        new androidx.appcompat.app.AlertDialog.Builder(this).setTitle(R.string.language)
            .setSingleChoiceItems(labels, selected, (dialog, choice) -> {
                dialog.dismiss();
                AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(
                    choice == 0 ? "" : choice == 1 ? "ru" : "en"));
            }).setNegativeButton(R.string.cancel, null).show();
    }
    private void editKeys() {
        TextInputLayout input = new TextInputLayout(this); input.setHint(s(R.string.key_hint));
        TextInputEditText keys = new TextInputEditText(input.getContext());
        keys.setMinLines(3); keys.setMaxLines(8);
        keys.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        keys.setText(name.krot.adbsshtunnel.Settings.keys(this));
        input.addView(keys);
        LinearLayout body = new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(20), dp(8), dp(20), 0);
        text(body, s(R.string.key_warning), 15); body.addView(input);
        androidx.appcompat.app.AlertDialog dialog = new androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.trusted_keys).setView(body)
            .setPositiveButton(R.string.save, null).setNegativeButton(R.string.cancel, null).create();
        dialog.setOnShowListener(v -> dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener(b -> {
            try {
                name.krot.adbsshtunnel.Settings.saveKeys(this, Objects.requireNonNull(keys.getText()).toString());
                if (name.krot.adbsshtunnel.Settings.enabled(this)) SshdService.select(this, true);
                dialog.dismiss(); showPage(); message(s(R.string.keys_saved));
            } catch (IOException e) { message(e.getMessage()); }
        }));
        dialog.show();
    }
    private void editNetwork(NetworkSettings.Kind kind) {
        ScrollView scroll = new ScrollView(this);
        NetworkPolicyView view = new NetworkPolicyView(this, kind, this::rulesChanged);
        view.setPadding(dp(16), dp(8), dp(16), dp(8));
        editor = view; view.showNetworks(networks); scroll.addView(view);
        androidx.appcompat.app.AlertDialog dialog = new androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(kind == NetworkSettings.Kind.WIFI ? R.string.wifi_networks : R.string.mobile_operators)
            .setView(scroll).setPositiveButton(R.string.close, null).create();
        dialog.setOnDismissListener(d -> { if (editor == view) editor = null; });
        dialog.show();
    }
    private void permissions() {
        LinearLayout body = new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(20), dp(8), dp(20), dp(8));
        text(body, s(R.string.network_help) + "\n\n" + s(R.string.permissions_help), 15);
        button(body, R.string.grant_identity, () -> requestPermissions(new String[]{
            Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.READ_PHONE_STATE}, 2));
        button(body, R.string.background_identity, () -> startActivity(new Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()))));
        button(body, R.string.location_settings, () -> startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)));
        ScrollView scroll = new ScrollView(this); scroll.addView(body);
        new androidx.appcompat.app.AlertDialog.Builder(this).setTitle(R.string.permissions)
            .setView(scroll).setPositiveButton(R.string.close, null).show();
    }
    private void addTile() {
        if (Build.VERSION.SDK_INT >= 33) getSystemService(StatusBarManager.class).requestAddTileService(
            new ComponentName(this, TunnelTile.class), s(R.string.app_name),
            Icon.createWithResource(this, R.drawable.ic_tunnel), getMainExecutor(), result -> {
                if (!isFinishing() && !isDestroyed()) message(s(
                    result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED
                    || result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED ? R.string.tile_added : R.string.tile_not_added));
            });
        else message(s(R.string.tile_manual));
    }
    private void checkAddress() {
        new androidx.appcompat.app.AlertDialog.Builder(this).setTitle(R.string.address_check)
            .setMessage(R.string.address_help).setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.check_now, (d, v) -> {
                if (checkingAddress) return;
                checkingAddress = true;
                if (networkMonitor != null) networkMonitor.refresh();
                TextView result = new TextView(this); result.setText(R.string.checking);
                result.setPadding(dp(20), dp(12), dp(20), dp(12)); result.setTextIsSelectable(true);
                ScrollView scroll = new ScrollView(this); scroll.addView(result);
                new androidx.appcompat.app.AlertDialog.Builder(this).setTitle(R.string.address_check).setView(scroll)
                    .setPositiveButton(R.string.close, null).show();
                new Thread(() -> {
                    String value;
                    try { value = UiText.diagnostics(this, NetworkDiagnostics.inspect(this)); }
                    catch (Exception e) { value = getString(R.string.check_failed, e.getMessage()); }
                    final String message = value;
                    runOnUiThread(() -> {
                        checkingAddress = false;
                        if (!isFinishing() && !isDestroyed()) result.setText(message);
                    });
                }, "network-diagnostics").start();
            }).show();
    }
    private void rulesChanged() {
        if (name.krot.adbsshtunnel.Settings.enabled(this)) SshdService.select(this, true);
        if (networkMonitor != null) networkMonitor.refresh();
        refresh();
    }
    private String hostKey() {
        try {
            File file = new File(name.krot.adbsshtunnel.Settings.config(this), "dropbear_ed25519_host_key");
            if (!file.isFile()) return s(R.string.host_missing);
            try (InputStream stream = new FileInputStream(file)) {
                String key = HostKeyPublic.read(stream);
                return KeyPolicy.fingerprint(key) + "\n" + key;
            }
        } catch (Exception e) { return getString(R.string.host_failed, e.getMessage()); }
    }
    private String networkDescription(NetworkSettings.Kind kind) {
        StringBuilder value = new StringBuilder();
        for (NetworkMonitor.Entry entry : networks) {
            if (kind != null && entry.kind != kind) continue;
            value.append(entry.identity == null ? s(R.string.identity_unknown) : entry.identity)
                .append(" · ").append(UiText.reason(this, entry.decision.reason)).append("\n");
            for (String address : entry.addresses) value.append(entry.interfaceName).append(": ").append(address)
                .append(entry.decision.allowed ? ":19191" : "").append("\n");
        }
        return value.length() == 0 ? s(R.string.no_network) : value.toString().trim();
    }
    private String summary(NetworkSettings.Kind kind) {
        NetworkPolicy policy = NetworkSettings.read(this, kind);
        return getString(R.string.mode_summary, s(UiText.mode(policy.mode)), policy.allow.size(), policy.deny.size())
            + "\n" + networkDescription(kind);
    }
    private void watchNetworks() {
        if (networkMonitor != null) networkMonitor.close();
        networkMonitor = new NetworkMonitor(this, entries -> {
            networks = entries;
            if (editor != null) editor.showNetworks(entries);
            refresh();
        });
        networkMonitor.start();
    }
    private void refresh() {
        if (adbSummary != null) adbSummary.setText(s(rootMode() ? R.string.root_adb : R.string.wireless_title));
        if (wirelessHoldStatus != null) wirelessHoldStatus.setText(WirelessDebuggingHold.status(this));
        if (status != null) status.setText(UiText.status(this, SshdService.state));
        if (toggle != null) toggle.setChecked(name.krot.adbsshtunnel.Settings.enabled(this));
        if (wifiSummary != null) wifiSummary.setText(summary(NetworkSettings.Kind.WIFI));
        if (mobileSummary != null) mobileSummary.setText(summary(NetworkSettings.Kind.MOBILE));
        if (rootStatus != null && !rootRequest) {
            String error = name.krot.adbsshtunnel.Settings.prefs(this).getString("root_controller_error", "");
            rootStatus.setText(!error.isEmpty() ? UiText.rootError(this, error) : s(RootSettings.configured(this)
                ? R.string.root_configured : R.string.root_unconfigured));
        }
    }
    @Override protected void onStart() {
        super.onStart();
        ContextCompat.registerReceiver(this, receiver, new IntentFilter("name.krot.adbsshtunnel.STATE"),
            ContextCompat.RECEIVER_NOT_EXPORTED);
        watchNetworks(); refresh();
    }
    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request == 2) { watchNetworks(); rulesChanged(); }
    }
    @Override protected void onStop() {
        if (networkMonitor != null) networkMonitor.close();
        networkMonitor = null; unregisterReceiver(receiver); super.onStop();
    }
}
