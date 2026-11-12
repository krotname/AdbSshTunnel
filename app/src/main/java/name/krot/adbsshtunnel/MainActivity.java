package name.krot.adbsshtunnel;

import android.Manifest;
import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.app.StatusBarManager;
import android.content.*;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Icon;
import android.graphics.drawable.InsetDrawable;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.*;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
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
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.google.android.material.textfield.*;
import com.hardbacknutter.sshd.SshdService;
import java.io.*;
import java.util.*;

public final class MainActivity extends AppCompatActivity {
    private static final String SHEET_CONNECT = "connect", SHEET_ACCESS = "access",
        SHEET_ADB = "adb", SHEET_MORE = "more";
    /** Colour family of a sheet or pane: strong accent, soft background, filled button. */
    private enum Look {
        BLUE(R.color.tile_blue, R.color.tile_blue_bg, R.color.tile_blue),
        GREEN(R.color.tile_green, R.color.tile_green_bg, R.color.tone_ready),
        VIOLET(R.color.tile_violet, R.color.tile_violet_bg, R.color.tile_violet),
        ORANGE(R.color.tile_orange, R.color.tile_orange_bg, R.color.tone_working),
        ROSE(R.color.tone_attention, R.color.problem_bg, R.color.tone_attention);
        final int strong, soft, button;
        Look(int strong, int soft, int button) { this.strong = strong; this.soft = soft; this.button = button; }
    }
    private String openSheet;
    private BottomSheetDialog sheet;
    private final List<BottomSheetDialog> panes = new ArrayList<>();
    private PowerGlow glow;
    private GradientDrawable fill;
    private FrameLayout power;
    private ValueAnimator tint;
    private int[] shownColors;
    private UiText.Tone shownTone;
    /** Between onStart and onStop; late background callbacks must not restart the glow. */
    private boolean visible;
    private UiText.Problem shownProblem;
    /** A starting tunnel passes through short waits; a problem shows only after it lasts this long. */
    private static final long ATTENTION_GRACE_MS = 1500;
    private long attentionSince;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable attentionRecheck = this::refresh;
    private TextView headline, status, hint;
    private MaterialButton problemChip;
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
    private static int fade(int color, int alpha) { return (color & 0x00FFFFFF) | (alpha << 24); }
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
    /** Large rounded row button used inside the bottom sheets. */
    private MaterialButton action(LinearLayout parent, int label, Runnable run) {
        return action(parent, s(label), run);
    }
    private MaterialButton action(LinearLayout parent, CharSequence label, Runnable run) {
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

    /** Filled main button of a pane. */
    private MaterialButton primary(LinearLayout parent, Look look, CharSequence label, Runnable run) {
        MaterialButton view = new MaterialButton(this);
        view.setText(label); view.setAllCaps(false); view.setTextSize(16);
        view.setBackgroundTintList(ColorStateList.valueOf(color(look.button)));
        view.setTextColor(color(R.color.screen));
        view.setCornerRadius(dp(16)); view.setMinHeight(dp(56));
        view.setOnClickListener(v -> run.run());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(12);
        parent.addView(view, params);
        return view;
    }
    /** Outlined second button of a pane. */
    private MaterialButton secondary(LinearLayout parent, Look look, CharSequence label, Runnable run) {
        MaterialButton view = new MaterialButton(this, null,
            com.google.android.material.R.attr.materialButtonOutlinedStyle);
        view.setText(label); view.setAllCaps(false); view.setTextSize(16);
        view.setTextColor(color(look.button));
        view.setStrokeColor(ColorStateList.valueOf(color(look.button)));
        view.setStrokeWidth(dp(1));
        view.setRippleColor(ColorStateList.valueOf(fade(color(look.button), 0x22)));
        view.setCornerRadius(dp(16)); view.setMinHeight(dp(56));
        view.setOnClickListener(v -> run.run());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(4);
        parent.addView(view, params);
        return view;
    }
    private TextView para(LinearLayout parent, CharSequence value) {
        TextView view = new TextView(this);
        view.setText(value); view.setTextSize(15); view.setTextColor(color(R.color.ink));
        view.setLineSpacing(0, 1.15f); view.setTextIsSelectable(true);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = dp(8);
        parent.addView(view, params);
        return view;
    }
    private TextView section(LinearLayout parent, int title, Look look) {
        TextView view = new TextView(this);
        view.setText(title); view.setTextSize(14); view.setTextColor(color(look.strong));
        view.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        ViewCompat.setAccessibilityHeading(view, true);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(8); params.bottomMargin = dp(4);
        parent.addView(view, params);
        return view;
    }
    private TextView codeBox(LinearLayout parent, String value) {
        TextView view = new TextView(this);
        view.setText(value); view.setTextSize(13); view.setTypeface(Typeface.MONOSPACE);
        view.setTextColor(color(R.color.ink)); view.setTextIsSelectable(true);
        GradientDrawable box = new GradientDrawable();
        box.setColor(color(R.color.code)); box.setCornerRadius(dp(14));
        view.setBackground(box);
        view.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(4); params.bottomMargin = dp(8);
        parent.addView(view, params);
        return view;
    }
    /** Red inline note for a mistake inside a pane; hidden until there is something to say. */
    private TextView errorLine(LinearLayout parent) {
        TextView view = new TextView(this);
        view.setTextSize(14); view.setTextColor(color(R.color.tone_attention));
        view.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_warning, 0, 0, 0);
        view.setCompoundDrawablePadding(dp(10));
        view.setCompoundDrawableTintList(ColorStateList.valueOf(color(R.color.tone_attention)));
        view.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable box = new GradientDrawable();
        box.setColor(color(R.color.problem_bg)); box.setCornerRadius(dp(14));
        view.setBackground(box);
        view.setPadding(dp(14), dp(10), dp(14), dp(10));
        view.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        view.setVisibility(View.GONE);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(8);
        parent.addView(view, params);
        return view;
    }
    /** Raw backend text, folded away under "Technical details". */
    private void details(LinearLayout parent, String raw) {
        if (raw == null || raw.trim().isEmpty()) return;
        MaterialButton toggle = new MaterialButton(this);
        toggle.setText(R.string.technical_details); toggle.setAllCaps(false); toggle.setTextSize(14);
        toggle.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        toggle.setBackgroundTintList(ColorStateList.valueOf(Color.TRANSPARENT));
        toggle.setRippleColor(ColorStateList.valueOf(fade(color(R.color.muted), 0x22)));
        toggle.setTextColor(color(R.color.muted));
        toggle.setIconResource(R.drawable.ic_expand);
        toggle.setIconTint(ColorStateList.valueOf(color(R.color.muted)));
        toggle.setIconGravity(MaterialButton.ICON_GRAVITY_TEXT_START);
        toggle.setElevation(0);
        ViewCompat.setStateDescription(toggle, s(R.string.collapsed));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(8);
        parent.addView(toggle, params);
        TextView code = codeBox(parent, raw);
        code.setVisibility(View.GONE);
        toggle.setOnClickListener(v -> {
            boolean open = code.getVisibility() != View.VISIBLE;
            code.setVisibility(open ? View.VISIBLE : View.GONE);
            toggle.setIconResource(open ? R.drawable.ic_collapse : R.drawable.ic_expand);
            ViewCompat.setStateDescription(toggle, s(open ? R.string.expanded : R.string.collapsed));
        });
    }

    /** Rounded sheet with a coloured header; long content scrolls inside the sheet only. */
    private LinearLayout frame(BottomSheetDialog dialog, int icon, Look look, CharSequence title) {
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(20), dp(8), dp(20), dp(24));
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(iconBubble(icon, look.soft, look.strong, dp(44)));
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
        return body;
    }
    /** A nested sheet opened from the main screen or another sheet: messages, editors, choices. */
    private final class Pane {
        final BottomSheetDialog dialog = new BottomSheetDialog(MainActivity.this);
        final LinearLayout body;
        Runnable after;
        Pane(int icon, Look look, CharSequence title) {
            body = frame(dialog, icon, look, title);
            panes.add(dialog);
            dialog.setOnDismissListener(d -> {
                panes.remove(dialog);
                if (after != null) after.run();
            });
        }
        void show() { if (!isFinishing() && !isDestroyed()) dialog.show(); }
        void close() { dialog.dismiss(); }
    }
    private void info(int icon, Look look, CharSequence title, CharSequence value) {
        Pane pane = new Pane(icon, look, title);
        para(pane.body, value);
        primary(pane.body, look, s(R.string.close), pane::close);
        pane.show();
    }
    private void done(String value) {
        info(R.drawable.ic_check, Look.GREEN, s(R.string.done_title), value);
    }
    private void error(String raw) {
        Pane pane = new Pane(R.drawable.ic_warning, Look.ROSE, s(R.string.error_title));
        String shown = UiText.status(this, raw);
        para(pane.body, shown.isEmpty() ? s(R.string.problem_unknown_what) : shown);
        primary(pane.body, Look.ROSE, s(R.string.close), pane::close);
        if (raw != null && !raw.equals(shown)) details(pane.body, raw);
        pane.show();
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
        // Fills the window exactly; scrolls only as a fallback when large fonts on a very short window
        // cannot fit the minimum power button and tiles.
        NestedScrollView fit = new NestedScrollView(this);
        fit.setFillViewport(true);
        fit.addView(content, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER_HORIZONTAL));
        shell.addView(fit, new FrameLayout.LayoutParams(-1, -1));
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
        if (tint != null) { tint.cancel(); tint = null; }
        if (glow != null) glow.stop();
        for (BottomSheetDialog pane : new ArrayList<>(panes)) { pane.setOnDismissListener(null); pane.dismiss(); }
        panes.clear();
        if (sheet != null) { sheet.setOnDismissListener(null); sheet.dismiss(); sheet = null; }
        super.onDestroy();
    }

    /** One big round on/off button that fills the free space without scrolling. */
    private void powerButton(LinearLayout hero) {
        FrameLayout stage = new FrameLayout(this);
        int minimum = dp(104);
        stage.setMinimumHeight(minimum);
        hero.addView(stage, new LinearLayout.LayoutParams(-1, 0, 1));
        int cap = dp(compact ? 196 : 232);
        power = new FrameLayout(this) {
            @Override protected void onMeasure(int widthSpec, int heightSpec) {
                int height = View.MeasureSpec.getMode(heightSpec) == View.MeasureSpec.UNSPECIFIED
                    ? minimum : View.MeasureSpec.getSize(heightSpec);
                int size = Math.max(minimum, Math.min(Math.min(View.MeasureSpec.getSize(widthSpec), height), cap));
                int spec = View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY);
                super.onMeasure(spec, spec);
            }
        };
        stage.addView(power, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        glow = new PowerGlow(this);
        power.addView(glow, new FrameLayout.LayoutParams(-1, -1));
        View core = new View(this);
        core.setElevation(dp(6));
        fill = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{Color.GRAY, Color.DKGRAY});
        fill.setShape(GradientDrawable.OVAL);
        core.setBackground(new InsetDrawable(fill, 0.1f));
        core.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
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
            bounce(v);
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
        status.setOnClickListener(v -> showStatus());
        problemChip = new MaterialButton(this);
        problemChip.setAllCaps(false); problemChip.setTextSize(15);
        problemChip.setMaxLines(2); problemChip.setEllipsize(TextUtils.TruncateAt.END);
        problemChip.setCornerRadius(dp(24)); problemChip.setMinHeight(dp(48));
        problemChip.setBackgroundTintList(ColorStateList.valueOf(color(R.color.problem_bg)));
        problemChip.setTextColor(color(R.color.tone_attention));
        problemChip.setRippleColor(ColorStateList.valueOf(fade(color(R.color.tone_attention), 0x33)));
        problemChip.setIconResource(R.drawable.ic_warning);
        problemChip.setIconTint(ColorStateList.valueOf(color(R.color.tone_attention)));
        problemChip.setIconSize(dp(20));
        problemChip.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        problemChip.setVisibility(View.GONE);
        problemChip.setOnClickListener(v -> showProblem());
        LinearLayout.LayoutParams chipParams = new LinearLayout.LayoutParams(-2, -2);
        chipParams.gravity = Gravity.CENTER_HORIZONTAL;
        chipParams.topMargin = dp(2);
        hero.addView(problemChip, chipParams);
        hint = label(hero, "", 13, false, R.color.muted, 2);
        hint.setGravity(Gravity.CENTER);
        ((LinearLayout.LayoutParams) hint.getLayoutParams()).bottomMargin = dp(compact ? 8 : 16);
    }
    /** Short press-in and spring back; skipped when system animations are off. */
    private void bounce(View view) {
        if (!ValueAnimator.areAnimatorsEnabled()) return;
        view.animate().cancel();
        view.animate().scaleX(0.94f).scaleY(0.94f).setDuration(90).setInterpolator(new DecelerateInterpolator())
            .withEndAction(() -> view.animate().scaleX(1f).scaleY(1f).setDuration(220)
                .setInterpolator(new OvershootInterpolator(3f)).start())
            .start();
    }
    private void setTunnel(boolean checked) {
        if (checked != name.krot.adbsshtunnel.Settings.enabled(this)) {
            try {
                if (!SshdService.select(this, checked)) showProblem();
            } catch (Exception e) { error(e.getMessage()); }
        }
        refresh();
    }
    /** Starts the tunnel again with the current settings; used by "Try again". */
    private void retry() {
        try {
            if (!SshdService.select(this, true)) showProblem();
        } catch (Exception e) { error(e.getMessage()); }
        refresh();
    }
    private int[] toneColors(UiText.Tone tone) {
        int start, end, text;
        switch (tone) {
            case READY: start = R.color.power_ready_start; end = R.color.power_ready_end; text = R.color.tone_ready; break;
            case WORKING: start = R.color.power_working_start; end = R.color.power_working_end; text = R.color.tone_working; break;
            case ATTENTION: start = R.color.power_attention_start; end = R.color.power_attention_end; text = R.color.tone_attention; break;
            default: start = R.color.power_off_start; end = R.color.power_off_end; text = R.color.tone_off;
        }
        return new int[]{color(start), color(end), color(text)};
    }
    private void paintPower(UiText.Tone tone) {
        if (power == null || !visible || tone == shownTone) return;
        boolean animate = shownTone != null && shownColors != null && ValueAnimator.areAnimatorsEnabled();
        shownTone = tone;
        int[] target = toneColors(tone);
        glow.show(tone, animate);
        if (tint != null) { tint.cancel(); tint = null; }
        if (!animate) { applyTint(target); return; }
        int[] from = shownColors.clone();
        ArgbEvaluator evaluator = new ArgbEvaluator();
        tint = ValueAnimator.ofFloat(0f, 1f);
        tint.setDuration(450);
        tint.addUpdateListener(animation -> {
            float fraction = animation.getAnimatedFraction();
            int[] now = new int[from.length];
            for (int i = 0; i < now.length; i++) now[i] = (int) evaluator.evaluate(fraction, from[i], target[i]);
            applyTint(now);
        });
        tint.start();
    }
    private void applyTint(int[] colors) {
        shownColors = colors;
        fill.setColors(new int[]{colors[0], colors[1]});
        headline.setTextColor(colors[2]);
        glow.setColors(colors[0], colors[1]);
    }

    /** The problem the main screen shows for the current tunnel state, if any. */
    private UiText.Problem currentProblem(boolean enabled, String state) {
        UiText.Problem problem = UiText.visibleProblem(enabled, state);
        // A rejected key stays in the last state until the next start; once the saved keys are fixed it is resolved.
        if (!enabled && problem == UiText.Problem.KEYS && keysValid()) return UiText.Problem.NONE;
        return problem;
    }
    private boolean keysValid() {
        try { KeyPolicy.parse(name.krot.adbsshtunnel.Settings.keys(this)); return true; }
        catch (IOException | RuntimeException e) { return false; }
    }
    private void showProblemChip(UiText.Problem problem) {
        if (problemChip == null || problem == shownProblem) return;
        shownProblem = problem;
        boolean shown = problem != UiText.Problem.NONE;
        if (shown) {
            problemChip.setText(problem.title);
            if (problemChip.getVisibility() != View.VISIBLE && ValueAnimator.areAnimatorsEnabled()) {
                problemChip.setAlpha(0f);
                problemChip.animate().alpha(1f).setDuration(250).start();
            }
        }
        problemChip.setVisibility(shown ? View.VISIBLE : View.GONE);
        status.setVisibility(shown ? View.GONE : View.VISIBLE);
    }
    private void showStatus() {
        String state = SshdService.state;
        if (currentProblem(name.krot.adbsshtunnel.Settings.enabled(this), state) != UiText.Problem.NONE) {
            showProblem();
            return;
        }
        info(R.drawable.ic_info, Look.BLUE, s(R.string.status_details), UiText.status(this, state));
    }
    /** What happened, what to do and a button that opens the right place. */
    private void showProblem() {
        String raw = SshdService.state;
        UiText.Problem problem = currentProblem(name.krot.adbsshtunnel.Settings.enabled(this), raw);
        if (problem == UiText.Problem.NONE) {
            info(R.drawable.ic_info, Look.BLUE, s(R.string.status_details), UiText.status(this, raw));
            return;
        }
        Pane pane = problemPane(problem);
        MaterialButton main = primary(pane.body, Look.ROSE, s(fixLabel(problem.fix)), () -> {
            pane.close();
            fix(problem.fix);
        });
        if (problem.fix == UiText.Fix.RETRY) main.setIconResource(R.drawable.ic_refresh);
        if (problem.retry && problem.fix != UiText.Fix.RETRY)
            secondary(pane.body, Look.ROSE, s(R.string.try_again), () -> { pane.close(); retry(); })
                .setIconResource(R.drawable.ic_refresh);
        else secondary(pane.body, Look.ROSE, s(R.string.close), pane::close);
        details(pane.body, raw);
        pane.show();
    }
    private Pane problemPane(UiText.Problem problem) {
        Pane pane = new Pane(problem.icon, Look.ROSE, s(problem.title));
        section(pane.body, R.string.what_happened, Look.ROSE);
        para(pane.body, s(problem.what));
        section(pane.body, R.string.what_to_do, Look.ROSE);
        para(pane.body, s(problem.todo));
        return pane;
    }
    private int fixLabel(UiText.Fix fix) {
        switch (fix) {
            case KEYS: return R.string.open_keys;
            case PERMISSIONS: return R.string.open_permissions;
            case INTERNET: return R.string.internet_settings;
            case ACCESS: return R.string.open_access;
            case DEVELOPER: return R.string.open_developer;
            case ADB: return R.string.open_adb;
            default: return R.string.try_again;
        }
    }
    private void fix(UiText.Fix fix) {
        switch (fix) {
            case KEYS: editKeys(); break;
            case PERMISSIONS: permissions(); break;
            case INTERNET: internetSettings(); break;
            case ACCESS: showSheet(SHEET_ACCESS); break;
            case DEVELOPER: developerOptions(); break;
            case ADB: showSheet(SHEET_ADB); break;
            default: retry();
        }
    }
    private void internetSettings() {
        for (String action : new String[]{Settings.Panel.ACTION_INTERNET_CONNECTIVITY,
            Settings.ACTION_WIRELESS_SETTINGS, Settings.ACTION_SETTINGS}) {
            try { startActivity(new Intent(action)); return; }
            catch (ActivityNotFoundException ignored) { }
        }
    }
    private void developerOptions() {
        try { startActivity(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)); }
        catch (ActivityNotFoundException e) { startActivity(new Intent(Settings.ACTION_SETTINGS)); }
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

    /** One of the four main bottom sheets; it is restored after rotation. */
    private LinearLayout sheetBody(String id, int icon, Look look, int title) {
        if (sheet != null) { sheet.setOnDismissListener(null); sheet.dismiss(); }
        clearSheetViews();
        BottomSheetDialog dialog = new BottomSheetDialog(this);
        LinearLayout body = frame(dialog, icon, look, s(title));
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
        LinearLayout body = sheetBody(SHEET_CONNECT, R.drawable.ic_link, Look.BLUE, R.string.connection_details);
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
        label(body, s(R.string.host_key), 15, true, R.color.ink, 2);
        codeBox(body, hostKey());
        action(body, R.string.address_check, this::checkAddress);
    }
    private void accessSettings() {
        LinearLayout access = sheetBody(SHEET_ACCESS, R.drawable.ic_network, Look.GREEN, R.string.access_settings);
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
        LinearLayout adb = sheetBody(SHEET_ADB, R.drawable.ic_adb, Look.VIOLET, R.string.adb_mode);
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
        action(root, R.string.adb_help, () -> info(R.drawable.ic_shield, Look.VIOLET, s(R.string.root_adb), s(R.string.root_help)));
        text(wireless, s(R.string.wireless_short), 15);
        if (BuildConfig.WIRELESS_HOLD_SUPPORTED) {
            SwitchMaterial hold = new SwitchMaterial(this);
            hold.setText(R.string.wireless_hold);
            hold.setChecked(WirelessDebuggingHold.selected(this));
            wireless.addView(hold);
            hold.setOnCheckedChangeListener((v, selected) -> {
                if (selected && !WirelessDebuggingHold.permitted(this)) {
                    hold.setChecked(false);
                    info(R.drawable.ic_adb, Look.VIOLET, s(R.string.wireless_hold), s(R.string.wireless_hold_setup));
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
        action(wireless, R.string.developer_options, this::developerOptions);
        action(wireless, R.string.adb_help, () -> info(R.drawable.ic_adb, Look.VIOLET, s(R.string.wireless_title),
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
            final String raw = result, value = UiText.rootError(this, result); final boolean configured = success;
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
                if (configured) info(R.drawable.ic_shield, Look.GREEN, s(R.string.root_status), value);
                else rootProblem(raw, policy);
            });
        }, "root-controller").start();
    }
    /** A failed root set-up: explained in plain words, with the exact error under details. */
    private void rootProblem(String raw, RootPolicy policy) {
        boolean missing = UiText.isSuMissing(raw);
        Pane pane = problemPane(missing ? UiText.Problem.ROOT_MISSING : UiText.Problem.ROOT_FAILED);
        if (missing) primary(pane.body, Look.ROSE, s(R.string.close), pane::close);
        else {
            primary(pane.body, Look.ROSE, s(R.string.try_again), () -> { pane.close(); configureRoot(policy); })
                .setIconResource(R.drawable.ic_refresh);
            secondary(pane.body, Look.ROSE, s(R.string.close), pane::close);
        }
        details(pane.body, raw);
        pane.show();
    }
    private void showRootStatus() {
        if (!RootSettings.configured(this)) {
            info(R.drawable.ic_shield, Look.VIOLET, s(R.string.root_status), s(R.string.root_unconfigured));
            return;
        }
        new Thread(() -> {
            String result;
            try { result = RootHelper.status(); }
            catch (Exception e) { result = e.getMessage(); }
            final String value = UiText.rootError(this, result);
            runOnUiThread(() -> {
                if (!isFinishing() && !isDestroyed())
                    info(R.drawable.ic_shield, Look.VIOLET, s(R.string.root_status), value);
            });
        }, "root-status").start();
    }
    private TextInputLayout field(LinearLayout body, int hint) {
        TextInputLayout layout = new TextInputLayout(this);
        layout.setHint(s(hint));
        layout.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);
        float radius = dp(14);
        layout.setBoxCornerRadii(radius, radius, radius, radius);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(8);
        body.addView(layout, params);
        return layout;
    }
    private TextInputEditText rootField(LinearLayout body, int hint, String value) {
        TextInputLayout layout = field(body, hint);
        TextInputEditText input = new TextInputEditText(layout.getContext());
        input.setSingleLine(true); input.setText(value); layout.addView(input);
        return input;
    }
    private void editDirectAdb() {
        RootPolicy policy = RootSettings.read(this);
        Pane pane = new Pane(R.drawable.ic_adb, Look.VIOLET, s(R.string.direct_adb_rules));
        para(pane.body, s(R.string.direct_adb_help));
        TextInputEditText wifi = rootField(pane.body, R.string.adb_wifi_prefix, policy.wifiPrefix);
        TextInputEditText hotspot = rootField(pane.body, R.string.adb_hotspot_ssid, policy.hotspotSsid);
        TextInputEditText wireguard = rootField(pane.body, R.string.adb_wireguard_iface, policy.wireguardInterface);
        TextView problem = errorLine(pane.body);
        primary(pane.body, Look.VIOLET, s(R.string.save), () -> {
            try {
                RootPolicy updated = new RootPolicy(policy.enabled, String.valueOf(wifi.getText()),
                    String.valueOf(hotspot.getText()), String.valueOf(wireguard.getText()), policy.usbEnabled);
                if (rootRequest) return;
                pane.close(); configureRoot(updated);
            } catch (IllegalArgumentException e) {
                problem.setText(UiText.status(this, e.getMessage()));
                problem.setVisibility(View.VISIBLE);
            }
        });
        secondary(pane.body, Look.VIOLET, s(R.string.cancel), pane::close);
        pane.show();
    }
    private void advancedSettings() {
        LinearLayout preferences = sheetBody(SHEET_MORE, R.drawable.ic_settings, Look.ORANGE, R.string.advanced_settings);
        action(preferences, R.string.language, this::language);
        text(preferences, languageName(), 15);
        action(preferences, R.string.quick_settings, this::addTile);
        action(preferences, R.string.host_key, this::showHostKey);
        action(preferences, R.string.status_details, this::showStatus);
        action(preferences, R.string.about_title, () -> info(R.drawable.ic_info, Look.ORANGE, s(R.string.about_title),
            getString(R.string.about_version, versionName()) + "\n\n" + s(R.string.about)));
    }
    private void showHostKey() {
        Pane pane = new Pane(R.drawable.ic_lock, Look.ORANGE, s(R.string.host_key));
        codeBox(pane.body, hostKey());
        primary(pane.body, Look.ORANGE, s(R.string.close), pane::close);
        pane.show();
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
        Pane pane = new Pane(R.drawable.ic_language, Look.ORANGE, s(R.string.language));
        for (int i = 0; i < labels.length; i++) {
            int choice = i;
            boolean checked = i == selected;
            MaterialButton row = action(pane.body, labels[i], () -> {
                pane.close();
                AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(
                    choice == 0 ? "" : choice == 1 ? "ru" : "en"));
            });
            if (checked) {
                row.setBackgroundTintList(ColorStateList.valueOf(color(Look.ORANGE.soft)));
                row.setIconResource(R.drawable.ic_check);
                row.setIconTint(ColorStateList.valueOf(color(Look.ORANGE.strong)));
                row.setIconGravity(MaterialButton.ICON_GRAVITY_END);
            }
            ViewCompat.setAccessibilityDelegate(row, new AccessibilityDelegateCompat() {
                @Override public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfoCompat info) {
                    super.onInitializeAccessibilityNodeInfo(host, info);
                    info.setClassName(RadioButton.class.getName());
                    info.setCheckable(true);
                    info.setChecked(checked);
                }
            });
        }
        secondary(pane.body, Look.ORANGE, s(R.string.cancel), pane::close);
        pane.show();
    }
    private void editKeys() {
        Pane pane = new Pane(R.drawable.ic_key, Look.GREEN, s(R.string.trusted_keys));
        para(pane.body, s(R.string.key_warning));
        TextInputLayout input = field(pane.body, R.string.key_hint);
        TextInputEditText keys = new TextInputEditText(input.getContext());
        keys.setMinLines(3); keys.setMaxLines(8);
        keys.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        keys.setText(name.krot.adbsshtunnel.Settings.keys(this));
        input.addView(keys);
        keys.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }
            @Override public void afterTextChanged(Editable s) { input.setError(null); }
        });
        primary(pane.body, Look.GREEN, s(R.string.save), () -> {
            try {
                name.krot.adbsshtunnel.Settings.saveKeys(this, Objects.requireNonNull(keys.getText()).toString());
                if (name.krot.adbsshtunnel.Settings.enabled(this)) SshdService.select(this, true);
                pane.close(); refresh(); done(s(R.string.keys_saved));
            } catch (IOException e) { input.setError(UiText.status(this, e.getMessage())); }
        });
        secondary(pane.body, Look.GREEN, s(R.string.cancel), pane::close);
        pane.show();
    }
    private void editNetwork(NetworkSettings.Kind kind) {
        Pane pane = new Pane(R.drawable.ic_network, Look.GREEN,
            s(kind == NetworkSettings.Kind.WIFI ? R.string.wifi_networks : R.string.mobile_operators));
        NetworkPolicyView view = new NetworkPolicyView(this, kind, this::rulesChanged);
        editor = view; view.showNetworks(networks);
        pane.body.addView(view, new LinearLayout.LayoutParams(-1, -2));
        secondary(pane.body, Look.GREEN, s(R.string.close), pane::close);
        pane.after = () -> { if (editor == view) editor = null; };
        pane.show();
    }
    private void permissions() {
        Pane pane = new Pane(R.drawable.ic_lock, Look.GREEN, s(R.string.permissions));
        para(pane.body, s(R.string.network_help));
        para(pane.body, s(R.string.permissions_help));
        primary(pane.body, Look.GREEN, s(R.string.grant_identity), () -> requestPermissions(new String[]{
            Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.READ_PHONE_STATE}, 2));
        LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(-1, dp(8));
        pane.body.addView(new Space(this), gap);
        action(pane.body, R.string.background_identity, () -> startActivity(new Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()))));
        action(pane.body, R.string.location_settings, () -> startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)));
        secondary(pane.body, Look.GREEN, s(R.string.close), pane::close);
        pane.show();
    }
    private void addTile() {
        if (Build.VERSION.SDK_INT >= 33) getSystemService(StatusBarManager.class).requestAddTileService(
            new ComponentName(this, TunnelTile.class), s(R.string.app_name),
            Icon.createWithResource(this, R.drawable.ic_tunnel), getMainExecutor(), result -> {
                if (isFinishing() || isDestroyed()) return;
                if (result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED
                    || result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED) done(s(R.string.tile_added));
                else info(R.drawable.ic_tile, Look.ORANGE, s(R.string.quick_settings), s(R.string.tile_not_added));
            });
        else info(R.drawable.ic_tile, Look.ORANGE, s(R.string.quick_settings), s(R.string.tile_manual));
    }
    private void checkAddress() {
        Pane pane = new Pane(R.drawable.ic_network, Look.BLUE, s(R.string.address_check));
        TextView note = para(pane.body, s(R.string.address_help));
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.VERTICAL);
        pane.body.addView(actions, new LinearLayout.LayoutParams(-1, -2));
        primary(actions, Look.BLUE, s(R.string.check_now), () -> {
            if (checkingAddress) return;
            checkingAddress = true;
            if (networkMonitor != null) networkMonitor.refresh();
            actions.removeAllViews();
            note.setText(R.string.checking_address);
            LinearProgressIndicator progress = new LinearProgressIndicator(this);
            progress.setIndeterminate(true);
            progress.setIndicatorColor(color(Look.BLUE.strong));
            progress.setTrackColor(color(Look.BLUE.soft));
            progress.setTrackCornerRadius(dp(4));
            actions.addView(progress, new LinearLayout.LayoutParams(-1, -2));
            new Thread(() -> {
                String value;
                try { value = UiText.diagnostics(this, NetworkDiagnostics.inspect(this)); }
                catch (Exception e) { value = getString(R.string.check_failed, e.getMessage()); }
                final String message = value;
                runOnUiThread(() -> {
                    checkingAddress = false;
                    if (isFinishing() || isDestroyed()) return;
                    actions.removeAllViews();
                    note.setVisibility(View.GONE);
                    codeBox(actions, message);
                    primary(actions, Look.BLUE, s(R.string.close), pane::close);
                });
            }, "network-diagnostics").start();
        });
        secondary(actions, Look.BLUE, s(R.string.cancel), pane::close);
        pane.show();
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
        String state = SshdService.state;
        UiText.Problem problem = currentProblem(enabled, state);
        boolean settling = settling(enabled, problem);
        if (settling) problem = UiText.Problem.NONE;
        UiText.Tone tone = problem != UiText.Problem.NONE ? UiText.Tone.ATTENTION
            : settling ? UiText.Tone.WORKING : UiText.tone(enabled, state);
        long keys = keyCount();
        if (headline != null) {
            int title, tip;
            switch (tone) {
                case READY: title = R.string.power_on_title; tip = R.string.hint_turn_off; break;
                case WORKING: title = R.string.power_working_title; tip = R.string.hint_turn_off; break;
                case ATTENTION: title = R.string.power_attention_title;
                    tip = enabled ? R.string.hint_attention : R.string.hint_problem_off; break;
                default: title = R.string.power_off_title; tip = keys == 0 ? R.string.hint_need_key : R.string.hint_turn_on;
            }
            headline.setText(title); hint.setText(tip);
            paintPower(tone);
            showProblemChip(problem);
            ViewCompat.setStateDescription(power, s(enabled ? R.string.state_on : R.string.state_off));
        }
        if (accessTile != null) accessTile.setText(getString(R.string.key_count, keys));
        if (adbTile != null) adbTile.setText(s(rootMode() ? R.string.root_adb : R.string.wireless_title));
        if (moreTile != null) moreTile.setText(languageName());
        if (networkNow != null) networkNow.setText(networkDescription(null));
        if (wirelessHoldStatus != null) wirelessHoldStatus.setText(WirelessDebuggingHold.status(this));
        if (status != null) status.setText(UiText.status(this, state));
        if (wifiSummary != null) wifiSummary.setText(summary(NetworkSettings.Kind.WIFI));
        if (mobileSummary != null) mobileSummary.setText(summary(NetworkSettings.Kind.MOBILE));
        if (rootStatus != null && !rootRequest) {
            String error = name.krot.adbsshtunnel.Settings.prefs(this).getString("root_controller_error", "");
            rootStatus.setText(!error.isEmpty() ? UiText.rootError(this, error) : s(RootSettings.configured(this)
                ? R.string.root_configured : R.string.root_unconfigured));
        }
    }
    /** True while an enabled tunnel's problem is too fresh to show; refresh runs again when the grace ends. */
    private boolean settling(boolean enabled, UiText.Problem problem) {
        if (!enabled || problem == UiText.Problem.NONE) {
            attentionSince = 0;
            ui.removeCallbacks(attentionRecheck);
            return false;
        }
        // Already on screen (e.g. after the problem changed): keep showing it without a new pause.
        if (shownProblem != null && shownProblem != UiText.Problem.NONE) return false;
        long now = SystemClock.uptimeMillis();
        if (attentionSince == 0) attentionSince = now;
        long left = attentionSince + ATTENTION_GRACE_MS - now;
        if (left <= 0) return false;
        ui.removeCallbacks(attentionRecheck);
        if (visible) ui.postDelayed(attentionRecheck, left);
        return true;
    }
    @Override protected void onStart() {
        super.onStart();
        visible = true;
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
        visible = false;
        ui.removeCallbacks(attentionRecheck);
        attentionSince = 0;
        if (tint != null) { tint.cancel(); tint = null; }
        if (glow != null) glow.stop();
        if (power != null) { power.animate().cancel(); power.setScaleX(1f); power.setScaleY(1f); }
        shownTone = null;
        super.onStop();
    }
}
