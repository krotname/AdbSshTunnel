package name.krot.adbsshtunnel;

import android.Manifest;
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.animation.ValueAnimator;
import android.app.StatusBarManager;
import android.content.*;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Icon;
import android.graphics.drawable.InsetDrawable;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.*;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.content.ContextCompat;
import androidx.core.os.LocaleListCompat;
import androidx.core.view.*;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import androidx.core.widget.NestedScrollView;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.google.android.material.textfield.*;
import com.hardbacknutter.sshd.SshdService;
import java.io.*;
import java.util.*;

public final class MainActivity extends AppCompatActivity {
    private static final String SHEET_CONNECT = "connect", SHEET_ACCESS = "access",
        SHEET_ADB = "adb", SHEET_MORE = "more";
    private String openSheet;
    private BottomSheetDialog sheet;
    private View halo, core;
    private FrameLayout power;
    private ValueAnimator pulse;
    private UiText.Tone shownTone;
    private TextView headline, status, hint;
    private TextView accessTile, adbTile, moreTile;
    private TextView wifiSummary, mobileSummary, rootStatus, wirelessHoldStatus, networkNow;
    private SwitchMaterial persistentAdb, persistentUsb;
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
    private int color(int resource) { return ContextCompat.getColor(this, resource); }
    private TextView text(LinearLayout parent, String value, int size) {
        TextView view = new TextView(this);
        view.setText(value); view.setTextSize(size);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = dp(compact ? 4 : 8);
        parent.addView(view, params);
        return view;
    }
    private TextView label(LinearLayout parent, String value, int size, boolean bold, int colorRes, int lines) {
        TextView view = new TextView(this);
        view.setText(value); view.setTextSize(size); view.setTextColor(color(colorRes));
        if (bold) view.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        view.setMaxLines(lines); view.setEllipsize(TextUtils.TruncateAt.END);
        parent.addView(view, new LinearLayout.LayoutParams(-1, -2));
        return view;
    }
    private MaterialButton button(LinearLayout parent, int label, Runnable action) {
        MaterialButton view = new MaterialButton(this);
        view.setText(label); view.setAllCaps(false);
        view.setCornerRadius(dp(16));
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
    /** Large rounded row button used inside the bottom sheets. */
    private MaterialButton action(LinearLayout parent, int label, Runnable run) {
        MaterialButton view = new MaterialButton(this);
        view.setText(label); view.setAllCaps(false); view.setTextSize(16);
        view.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        view.setBackgroundTintList(ColorStateList.valueOf(color(R.color.row)));
        view.setTextColor(color(R.color.ink));
        view.setCornerRadius(dp(16)); view.setMinHeight(dp(56));
        view.setPadding(dp(20), dp(12), dp(20), dp(12));
        view.setOnClickListener(v -> run.run());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(2); params.bottomMargin = dp(2);
        parent.addView(view, params);
        return view;
    }
    private ImageView iconBubble(int icon, int tint, int background, int size) {
        ImageView view = new ImageView(this);
        view.setImageResource(icon);
        view.setImageTintList(ColorStateList.valueOf(color(tint)));
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL); circle.setColor(color(background));
        view.setBackground(circle);
        int padding = size / 4;
        view.setPadding(padding, padding, padding, padding);
        view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        view.setLayoutParams(new LinearLayout.LayoutParams(size, size));
        return view;
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if (state != null) openSheet = state.getString("open_sheet");
        Configuration config = getResources().getConfiguration();
        compact = config.screenHeightDp < 650;
        boolean wide = config.screenWidthDp > config.screenHeightDp && config.screenHeightDp < 600;
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        FrameLayout shell = new FrameLayout(this);
        shell.setBackgroundColor(color(R.color.screen));
        int maxWidth = dp(wide ? 960 : 720);
        LinearLayout content = new LinearLayout(this) {
            @Override protected void onMeasure(int widthSpec, int heightSpec) {
                int width = Math.min(View.MeasureSpec.getSize(widthSpec), maxWidth);
                super.onMeasure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), heightSpec);
            }
        };
        content.setOrientation(LinearLayout.VERTICAL);
        int side = dp(compact ? 16 : 20);
        content.setPadding(side, dp(compact ? 8 : 16), side, dp(compact ? 12 : 20));
        shell.addView(content, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER_HORIZONTAL));
        label(content, s(R.string.app_name), compact ? 20 : 24, true, R.color.ink, 1);
        label(content, s(R.string.tunnel_intro), 14, false, R.color.muted, 2);
        LinearLayout hero = new LinearLayout(this);
        hero.setOrientation(LinearLayout.VERTICAL);
        hero.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout tiles = new LinearLayout(this);
        tiles.setOrientation(LinearLayout.VERTICAL);
        if (wide) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            content.addView(row, new LinearLayout.LayoutParams(-1, 0, 1));
            LinearLayout.LayoutParams heroParams = new LinearLayout.LayoutParams(0, -1, 1);
            heroParams.rightMargin = dp(16);
            row.addView(hero, heroParams);
            row.addView(tiles, new LinearLayout.LayoutParams(0, -2, 1));
        } else {
            content.addView(hero, new LinearLayout.LayoutParams(-1, 0, 1));
            content.addView(tiles, new LinearLayout.LayoutParams(-1, -2));
        }
        powerButton(hero);
        tileGrid(tiles);
        setContentView(shell);
        ViewCompat.setOnApplyWindowInsetsListener(shell, (v, insets) -> {
            androidx.core.graphics.Insets bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            shell.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return WindowInsetsCompat.CONSUMED;
        });
        refresh();
        if (openSheet != null) showSheet(openSheet);
        if (Build.VERSION.SDK_INT >= 33
            && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        state.putString("open_sheet", openSheet);
        super.onSaveInstanceState(state);
    }
    @Override protected void onDestroy() {
        if (pulse != null) { pulse.cancel(); pulse = null; }
        if (sheet != null) { sheet.setOnDismissListener(null); sheet.dismiss(); sheet = null; }
        super.onDestroy();
    }

    /** One big round on/off button that fills the free space without scrolling. */
    private void powerButton(LinearLayout hero) {
        FrameLayout stage = new FrameLayout(this);
        hero.addView(stage, new LinearLayout.LayoutParams(-1, 0, 1));
        int cap = dp(compact ? 196 : 232);
        power = new FrameLayout(this) {
            @Override protected void onMeasure(int widthSpec, int heightSpec) {
                int size = Math.min(Math.min(View.MeasureSpec.getSize(widthSpec),
                    View.MeasureSpec.getSize(heightSpec)), cap);
                int spec = View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY);
                super.onMeasure(spec, spec);
            }
        };
        stage.addView(power, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        halo = new View(this);
        power.addView(halo, new FrameLayout.LayoutParams(-1, -1));
        core = new View(this);
        core.setElevation(dp(6));
        power.addView(core, new FrameLayout.LayoutParams(-1, -1));
        ImageView icon = new ImageView(this);
        icon.setImageDrawable(new InsetDrawable(ContextCompat.getDrawable(this, R.drawable.ic_power), 0.33f));
        icon.setImageTintList(ColorStateList.valueOf(color(R.color.on_power)));
        icon.setScaleType(ImageView.ScaleType.FIT_XY);
        icon.setElevation(dp(7));
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        power.addView(icon, new FrameLayout.LayoutParams(-1, -1));
        power.setClickable(true); power.setFocusable(true);
        power.setContentDescription(s(R.string.ssh_tunnel));
        power.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK);
            setTunnel(!name.krot.adbsshtunnel.Settings.enabled(this));
        });
        ViewCompat.setAccessibilityDelegate(power, new AccessibilityDelegateCompat() {
            @Override public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfoCompat info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setClassName(Switch.class.getName());
                info.setCheckable(true);
                info.setChecked(name.krot.adbsshtunnel.Settings.enabled(MainActivity.this));
            }
        });
        headline = label(hero, "", compact ? 22 : 26, true, R.color.ink, 1);
        headline.setGravity(Gravity.CENTER);
        ((LinearLayout.LayoutParams) headline.getLayoutParams()).topMargin = dp(compact ? 8 : 16);
        status = label(hero, "", 15, false, R.color.muted, 2);
        status.setGravity(Gravity.CENTER);
        status.setPadding(dp(8), dp(4), dp(8), dp(4));
        status.setOnClickListener(v -> detail(R.string.status_details, UiText.status(this, SshdService.state)));
        hint = label(hero, "", 13, false, R.color.muted, 2);
        hint.setGravity(Gravity.CENTER);
        ((LinearLayout.LayoutParams) hint.getLayoutParams()).bottomMargin = dp(compact ? 8 : 16);
    }
    private void setTunnel(boolean checked) {
        if (checked != name.krot.adbsshtunnel.Settings.enabled(this)) {
            try {
                if (!SshdService.select(this, checked)) message(UiText.status(this, SshdService.state));
            } catch (Exception e) { message(e.getMessage()); }
        }
        refresh();
    }
    private void paintPower(UiText.Tone tone) {
        if (power == null || tone == shownTone) return;
        shownTone = tone;
        int start, end, text;
        switch (tone) {
            case READY: start = R.color.power_ready_start; end = R.color.power_ready_end; text = R.color.tone_ready; break;
            case WORKING: start = R.color.power_working_start; end = R.color.power_working_end; text = R.color.tone_working; break;
            case ATTENTION: start = R.color.power_attention_start; end = R.color.power_attention_end; text = R.color.tone_attention; break;
            default: start = R.color.power_off_start; end = R.color.power_off_end; text = R.color.tone_off;
        }
        GradientDrawable fill = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
            new int[]{color(start), color(end)});
        fill.setShape(GradientDrawable.OVAL);
        core.setBackground(new InsetDrawable(fill, 0.1f));
        GradientDrawable ring = new GradientDrawable();
        ring.setShape(GradientDrawable.OVAL);
        ring.setColor((color(end) & 0x00FFFFFF) | 0x33000000);
        halo.setBackground(ring);
        headline.setTextColor(color(text));
        if (pulse != null) { pulse.cancel(); pulse = null; }
        halo.setScaleX(1f); halo.setScaleY(1f); halo.setAlpha(1f);
        if (tone == UiText.Tone.READY || tone == UiText.Tone.WORKING) {
            pulse = ObjectAnimator.ofPropertyValuesHolder(halo,
                PropertyValuesHolder.ofFloat(View.SCALE_X, 0.9f, 1f),
                PropertyValuesHolder.ofFloat(View.SCALE_Y, 0.9f, 1f),
                PropertyValuesHolder.ofFloat(View.ALPHA, 1f, 0.35f));
            pulse.setDuration(tone == UiText.Tone.READY ? 1600 : 700);
            pulse.setRepeatMode(ValueAnimator.REVERSE);
            pulse.setRepeatCount(ValueAnimator.INFINITE);
            pulse.start();
        }
    }

    /** Four big colourful tiles: every former section is one tap away. */
    private void tileGrid(LinearLayout tiles) {
        LinearLayout top = new LinearLayout(this), bottom = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL); bottom.setOrientation(LinearLayout.HORIZONTAL);
        tiles.addView(top, new LinearLayout.LayoutParams(-1, -2));
        tiles.addView(bottom, new LinearLayout.LayoutParams(-1, -2));
        tile(top, R.drawable.ic_link, R.color.tile_blue, R.color.tile_blue_bg,
            R.string.connection_details, () -> showSheet(SHEET_CONNECT)).setText(R.string.tile_connect_summary);
        accessTile = tile(top, R.drawable.ic_network, R.color.tile_green, R.color.tile_green_bg,
            R.string.tile_access, () -> showSheet(SHEET_ACCESS));
        adbTile = tile(bottom, R.drawable.ic_adb, R.color.tile_violet, R.color.tile_violet_bg,
            R.string.adb_mode, () -> showSheet(SHEET_ADB));
        moreTile = tile(bottom, R.drawable.ic_settings, R.color.tile_orange, R.color.tile_orange_bg,
            R.string.advanced_settings, () -> showSheet(SHEET_MORE));
    }
    private TextView tile(LinearLayout row, int icon, int tint, int background, int title, Runnable open) {
        MaterialCardView card = new MaterialCardView(this);
        card.setRadius(dp(24)); card.setCardElevation(0); card.setStrokeWidth(0);
        card.setCardBackgroundColor(color(background));
        card.setClickable(true); card.setFocusable(true);
        card.setOnClickListener(v -> open.run());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1);
        params.setMargins(dp(5), dp(5), dp(5), dp(5));
        row.addView(card, params);
        LinearLayout inner = new LinearLayout(this);
        inner.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(compact ? 12 : 16);
        inner.setPadding(pad, pad, pad, pad);
        card.addView(inner);
        inner.addView(iconBubble(icon, background, tint, dp(compact ? 36 : 44)));
        TextView name = label(inner, s(title), compact ? 15 : 17, true, R.color.ink, 1);
        ((LinearLayout.LayoutParams) name.getLayoutParams()).topMargin = dp(compact ? 6 : 10);
        return label(inner, "", 13, false, R.color.muted, 1);
    }

    /** Bottom sheet with a friendly header; long content scrolls inside the sheet only. */
    private LinearLayout sheetBody(String id, int icon, int tint, int background, int title) {
        if (sheet != null) { sheet.setOnDismissListener(null); sheet.dismiss(); }
        clearSheetViews();
        BottomSheetDialog dialog = new BottomSheetDialog(this);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(20), dp(8), dp(20), dp(24));
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(iconBubble(icon, background, tint, dp(44)));
        TextView name = new TextView(this);
        name.setText(title); name.setTextSize(22); name.setTextColor(color(R.color.ink));
        name.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        name.setPadding(dp(14), 0, 0, 0);
        ViewCompat.setAccessibilityHeading(name, true);
        header.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams headerParams = new LinearLayout.LayoutParams(-1, -2);
        headerParams.bottomMargin = dp(16);
        body.addView(header, headerParams);
        NestedScrollView scroll = new NestedScrollView(this);
        scroll.addView(body);
        dialog.setContentView(scroll);
        dialog.getBehavior().setState(BottomSheetBehavior.STATE_EXPANDED);
        dialog.getBehavior().setSkipCollapsed(true);
        dialog.setOnDismissListener(d -> {
            if (sheet != dialog) return;
            sheet = null; openSheet = null; clearSheetViews();
        });
        sheet = dialog; openSheet = id;
        return body;
    }
    private void clearSheetViews() {
        wifiSummary = null; mobileSummary = null; rootStatus = null; wirelessHoldStatus = null;
        networkNow = null; persistentAdb = null; persistentUsb = null;
    }
    private void showSheet(String id) {
        switch (id) {
            case SHEET_CONNECT: connectSheet(); break;
            case SHEET_ACCESS: accessSettings(); break;
            case SHEET_ADB: adbSettings(); break;
            case SHEET_MORE: advancedSettings(); break;
            default: openSheet = null; return;
        }
        refresh();
        if (sheet != null) sheet.show();
    }
    private void connectSheet() {
        LinearLayout body = sheetBody(SHEET_CONNECT, R.drawable.ic_link, R.color.tile_blue,
            R.color.tile_blue_bg, R.string.connection_details);
        label(body, s(R.string.connect_step_title), 15, true, R.color.ink, 2);
        TextView command = text(body, s(R.string.connect_command), 14);
        command.setTypeface(Typeface.MONOSPACE); command.setTextIsSelectable(true);
        command.setTextColor(color(R.color.ink));
        GradientDrawable box = new GradientDrawable();
        box.setColor(color(R.color.code)); box.setCornerRadius(dp(14));
        command.setBackground(box);
        command.setPadding(dp(14), dp(12), dp(14), dp(12));
        ((LinearLayout.LayoutParams) command.getLayoutParams()).topMargin = dp(8);
        text(body, s(R.string.connect_help), 14).setTextIsSelectable(true);
        label(body, s(R.string.network_now), 15, true, R.color.ink, 2);
        networkNow = text(body, "", 14);
        networkNow.setTextIsSelectable(true);
        text(body, hostKey(), 13).setTextIsSelectable(true);
        action(body, R.string.address_check, this::checkAddress);
    }
    private void accessSettings() {
        LinearLayout access = sheetBody(SHEET_ACCESS, R.drawable.ic_network, R.color.tile_green,
            R.color.tile_green_bg, R.string.access_settings);
        action(access, R.string.wifi_networks, () -> editNetwork(NetworkSettings.Kind.WIFI));
        wifiSummary = text(access, "", 14);
        action(access, R.string.mobile_operators, () -> editNetwork(NetworkSettings.Kind.MOBILE));
        mobileSummary = text(access, "", 14);
        action(access, R.string.trusted_keys, this::editKeys);
        action(access, R.string.permissions, this::permissions);
    }
    private long keyCount() {
        return Arrays.stream(name.krot.adbsshtunnel.Settings.keys(this).split("\n"))
            .filter(line -> !line.trim().isEmpty()).count();
    }
    private boolean rootMode() {
        return name.krot.adbsshtunnel.Settings.prefs(this).getBoolean("root_mode", false);
    }
    private MaterialButton modeChoice(MaterialButtonToggleGroup group, int label) {
        MaterialButton choice = new MaterialButton(this, null,
            com.google.android.material.R.attr.materialButtonOutlinedStyle);
        choice.setId(View.generateViewId());
        choice.setText(label); choice.setAllCaps(false); choice.setTextSize(15);
        choice.setMinHeight(dp(52));
        group.addView(choice, new LinearLayout.LayoutParams(-1, -2));
        return choice;
    }
    private void adbSettings() {
        LinearLayout adb = sheetBody(SHEET_ADB, R.drawable.ic_adb, R.color.tile_violet,
            R.color.tile_violet_bg, R.string.adb_mode);
        MaterialButtonToggleGroup mode = new MaterialButtonToggleGroup(this);
        mode.setOrientation(LinearLayout.VERTICAL);
        mode.setSingleSelection(true); mode.setSelectionRequired(true);
        mode.setContentDescription(s(R.string.adb_mode));
        MaterialButton wirelessChoice = modeChoice(mode, R.string.wireless_mode);
        MaterialButton rootChoice = modeChoice(mode, R.string.root_adb);
        mode.check(rootMode() ? rootChoice.getId() : wirelessChoice.getId());
        LinearLayout.LayoutParams modeParams = new LinearLayout.LayoutParams(-1, -2);
        modeParams.bottomMargin = dp(12);
        adb.addView(mode, modeParams);
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); adb.addView(root);
        LinearLayout wireless = new LinearLayout(this); wireless.setOrientation(LinearLayout.VERTICAL); adb.addView(wireless);
        Runnable displayMode = () -> {
            root.setVisibility(rootMode() ? View.VISIBLE : View.GONE);
            wireless.setVisibility(rootMode() ? View.GONE : View.VISIBLE);
        };
        mode.addOnButtonCheckedListener((group, checkedId, checked) -> {
            if (!checked) return;
            boolean selected = checkedId == rootChoice.getId();
            if (selected != rootMode()) {
                name.krot.adbsshtunnel.Settings.prefs(MainActivity.this).edit().putBoolean("root_mode", selected).commit();
                if (name.krot.adbsshtunnel.Settings.enabled(MainActivity.this)) SshdService.select(MainActivity.this, true);
            }
            displayMode.run(); refresh();
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
        persistentUsb = new SwitchMaterial(this);
        persistentUsb.setText(R.string.persistent_usb);
        persistentUsb.setChecked(RootSettings.read(this).usbEnabled);
        root.addView(persistentUsb);
        persistentUsb.setOnCheckedChangeListener((v, enabled) -> {
            if (enabled == RootSettings.read(this).usbEnabled || rootRequest) return;
            configureRoot(RootSettings.read(this).withUsbEnabled(enabled));
        });
        text(root, s(R.string.persistent_usb_help), 14);
        rootStatus = text(root, s(R.string.root_unconfigured), 14);
        action(root, R.string.root_helper, () -> configureRoot(RootSettings.read(this)));
        action(root, R.string.direct_adb_rules, this::editDirectAdb);
        action(root, R.string.root_status, this::showRootStatus);
        action(root, R.string.adb_help, () -> detail(R.string.root_adb, s(R.string.root_help)));
        text(wireless, s(R.string.wireless_short), 15);
        if (BuildConfig.WIRELESS_HOLD_SUPPORTED) {
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
        }
        wirelessHoldStatus = text(wireless, s(WirelessDebuggingHold.status(this)), 14);
        action(wireless, R.string.developer_options, () -> {
            try { startActivity(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)); }
            catch (ActivityNotFoundException e) { startActivity(new Intent(Settings.ACTION_SETTINGS)); }
        });
        action(wireless, R.string.adb_help, () -> detail(R.string.wireless_title,
            BuildConfig.WIRELESS_HOLD_SUPPORTED
                ? s(R.string.wireless_help) + "\n\n" + s(R.string.wireless_hold_setup)
                : s(R.string.wireless_manual_help)));
    }
    private void configureRoot(RootPolicy policy) {
        if (rootRequest) return;
        rootRequest = true;
        if (persistentAdb != null) persistentAdb.setEnabled(false);
        if (persistentUsb != null) persistentUsb.setEnabled(false);
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
                if (persistentUsb != null) {
                    persistentUsb.setChecked(RootSettings.read(this).usbEnabled);
                    persistentUsb.setEnabled(true);
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
                    String.valueOf(hotspot.getText()), String.valueOf(wireguard.getText()), policy.usbEnabled);
                if (rootRequest) return;
                dialog.dismiss(); configureRoot(updated);
            } catch (IllegalArgumentException e) { message(e.getMessage()); }
        }));
        dialog.show();
    }
    private void advancedSettings() {
        LinearLayout preferences = sheetBody(SHEET_MORE, R.drawable.ic_settings, R.color.tile_orange,
            R.color.tile_orange_bg, R.string.advanced_settings);
        action(preferences, R.string.language, this::language);
        text(preferences, languageName(), 15);
        action(preferences, R.string.quick_settings, this::addTile);
        action(preferences, R.string.host_key, () -> detail(R.string.host_key, hostKey()));
        action(preferences, R.string.status_details, () -> detail(R.string.status_details, UiText.status(this, SshdService.state)));
        action(preferences, R.string.about_title, () -> detail(R.string.about_title,
            getString(R.string.about_version, versionName()) + "\n\n" + s(R.string.about)));
    }
    private String languageName() {
        String selected = AppCompatDelegate.getApplicationLocales().toLanguageTags();
        return selected.isEmpty() ? s(R.string.system_language) : ("ru".equals(selected) ? "Русский" : "English");
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
                dialog.dismiss(); refresh(); message(s(R.string.keys_saved));
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
        boolean enabled = name.krot.adbsshtunnel.Settings.enabled(this);
        UiText.Tone tone = UiText.tone(enabled, SshdService.state);
        long keys = keyCount();
        if (headline != null) {
            int title, tip;
            switch (tone) {
                case READY: title = R.string.power_on_title; tip = R.string.hint_turn_off; break;
                case WORKING: title = R.string.power_working_title; tip = R.string.hint_turn_off; break;
                case ATTENTION: title = R.string.power_attention_title; tip = R.string.hint_attention; break;
                default: title = R.string.power_off_title; tip = keys == 0 ? R.string.hint_need_key : R.string.hint_turn_on;
            }
            headline.setText(title); hint.setText(tip);
            paintPower(tone);
            ViewCompat.setStateDescription(power, s(enabled ? R.string.state_on : R.string.state_off));
        }
        if (accessTile != null) accessTile.setText(getString(R.string.key_count, keys));
        if (adbTile != null) adbTile.setText(s(rootMode() ? R.string.root_adb : R.string.wireless_title));
        if (moreTile != null) moreTile.setText(languageName());
        if (networkNow != null) networkNow.setText(networkDescription(null));
        if (wirelessHoldStatus != null) wirelessHoldStatus.setText(WirelessDebuggingHold.status(this));
        if (status != null) status.setText(UiText.status(this, SshdService.state));
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
        networkMonitor = null; unregisterReceiver(receiver);
        if (pulse != null) { pulse.cancel(); pulse = null; }
        shownTone = null;
        super.onStop();
    }
}
