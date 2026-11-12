package name.krot.adbsshtunnel;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.textfield.*;
import java.io.IOException;
import java.util.*;

/** Edits one transport independently; every saved change revokes existing SSH sessions. */
public final class NetworkPolicyView extends LinearLayout {
    private final NetworkSettings.Kind kind;
    private final Runnable changed;
    private final TextView current;
    private final LinearLayout lists;
    private TextView error;
    private NetworkPolicy policy;
    private boolean reverting;
    public NetworkPolicyView(Context context, NetworkSettings.Kind kind, Runnable changed) {
        super(context); this.kind = kind; this.changed = changed;
        setOrientation(VERTICAL); policy = NetworkSettings.read(context, kind);
        heading(context.getString(R.string.network_now));
        current = new TextView(context);
        current.setTextIsSelectable(true); current.setTextSize(13); current.setTypeface(Typeface.MONOSPACE);
        current.setTextColor(color(R.color.ink));
        current.setBackground(rounded(R.color.code, 14));
        current.setPadding(dp(14), dp(12), dp(14), dp(12));
        addView(current, spaced(4, 8));
        heading(context.getString(R.string.choose_mode));
        MaterialButtonToggleGroup modes = new MaterialButtonToggleGroup(context);
        modes.setOrientation(VERTICAL);
        modes.setSingleSelection(true); modes.setSelectionRequired(true);
        modes.setContentDescription(context.getString(R.string.network_mode));
        String[] labels = {context.getString(R.string.network_mode_allow), context.getString(R.string.network_mode_deny), context.getString(R.string.network_mode_disabled)};
        NetworkPolicy.Mode[] values = NetworkPolicy.Mode.values();
        int[] ids = new int[labels.length];
        for (int i = 0; i < labels.length; i++) {
            MaterialButton choice = new MaterialButton(context, null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle);
            choice.setId(View.generateViewId());
            choice.setText(labels[i]); choice.setAllCaps(false); choice.setTextSize(15);
            choice.setMinHeight(dp(52));
            ids[i] = choice.getId();
            modes.addView(choice, new LinearLayout.LayoutParams(-1, -2));
        }
        modes.check(ids[policy.mode.ordinal()]);
        modes.addOnButtonCheckedListener((group, checkedId, checked) -> {
            if (!checked || reverting) return;
            int position = -1;
            for (int i = 0; i < ids.length; i++) if (ids[i] == checkedId) position = i;
            if (position < 0) return;
            if (values[position] != policy.mode && !save(values[position], policy.allow, policy.deny)) {
                reverting = true;
                group.check(ids[policy.mode.ordinal()]);
                reverting = false;
            }
        });
        addView(modes, spaced(4, 8));
        TextView help = new TextView(context);
        help.setText(kind == NetworkSettings.Kind.WIFI
            ? context.getString(R.string.wifi_help)
            : context.getString(R.string.mobile_help));
        help.setTextSize(14); help.setTextColor(color(R.color.muted)); help.setLineSpacing(0, 1.15f);
        addView(help, spaced(8, 4));
        TextInputLayout input = new TextInputLayout(context);
        input.setHint(context.getString(kind == NetworkSettings.Kind.WIFI ? R.string.ssid : R.string.plmn));
        input.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);
        float radius = dp(14);
        input.setBoxCornerRadii(radius, radius, radius, radius);
        TextInputEditText id = new TextInputEditText(input.getContext()); id.setSingleLine(true);
        if (kind == NetworkSettings.Kind.MOBILE) id.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        input.addView(id); addView(input, spaced(4, 0));
        error = new TextView(context);
        error.setTextSize(14); error.setTextColor(color(R.color.tone_attention));
        error.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_warning, 0, 0, 0);
        error.setCompoundDrawablePadding(dp(10));
        error.setCompoundDrawableTintList(ColorStateList.valueOf(color(R.color.tone_attention)));
        error.setGravity(Gravity.CENTER_VERTICAL);
        error.setBackground(rounded(R.color.problem_bg, 14));
        error.setPadding(dp(14), dp(10), dp(14), dp(10));
        error.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        error.setVisibility(GONE);
        addView(error, spaced(8, 0));
        LinearLayout buttons = new LinearLayout(context);
        buttons.setOrientation(HORIZONTAL);
        MaterialButton allow = new MaterialButton(context);
        allow.setText(R.string.add_allow); allow.setAllCaps(false); allow.setTextSize(15);
        allow.setBackgroundTintList(ColorStateList.valueOf(color(R.color.tone_ready)));
        allow.setTextColor(color(R.color.screen));
        allow.setCornerRadius(dp(16)); allow.setMinHeight(dp(52));
        allow.setOnClickListener(v -> add(id, true));
        LinearLayout.LayoutParams left = new LinearLayout.LayoutParams(0, -2, 1);
        left.rightMargin = dp(4);
        buttons.addView(allow, left);
        MaterialButton deny = new MaterialButton(context, null,
            com.google.android.material.R.attr.materialButtonOutlinedStyle);
        deny.setText(R.string.add_deny); deny.setAllCaps(false); deny.setTextSize(15);
        deny.setTextColor(color(R.color.tone_attention));
        deny.setStrokeColor(ColorStateList.valueOf(color(R.color.tone_attention)));
        deny.setCornerRadius(dp(16)); deny.setMinHeight(dp(52));
        deny.setOnClickListener(v -> add(id, false));
        LinearLayout.LayoutParams right = new LinearLayout.LayoutParams(0, -2, 1);
        right.leftMargin = dp(4);
        buttons.addView(deny, right);
        addView(buttons, spaced(8, 0));
        MaterialButton pick = new MaterialButton(context);
        pick.setText(R.string.allow_current); pick.setAllCaps(false); pick.setTextSize(16);
        pick.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        pick.setBackgroundTintList(ColorStateList.valueOf(color(R.color.row)));
        pick.setTextColor(color(R.color.ink));
        pick.setCornerRadius(dp(16)); pick.setMinHeight(dp(56));
        pick.setPadding(dp(20), dp(12), dp(20), dp(12));
        pick.setOnClickListener(v -> {
            List<NetworkMonitor.Entry> entries = visibleEntries;
            List<String> identities = new ArrayList<>();
            for (NetworkMonitor.Entry entry : entries) if (entry.kind == kind && entry.identity != null && !identities.contains(entry.identity)) identities.add(entry.identity);
            if (identities.size() == 1) { id.setText(identities.get(0)); add(id, true); }
            else showError(context.getString(R.string.no_single_network));
        });
        addView(pick, spaced(4, 4));
        lists = new LinearLayout(context); lists.setOrientation(VERTICAL); addView(lists); renderLists();
    }
    private List<NetworkMonitor.Entry> visibleEntries = Collections.emptyList();
    public void showNetworks(List<NetworkMonitor.Entry> entries) {
        visibleEntries = entries;
        StringBuilder value = new StringBuilder();
        for (NetworkMonitor.Entry entry : entries) {
            if (entry.kind != kind) continue;
            value.append(entry.label).append(" · ").append(entry.identity == null ? getContext().getString(R.string.identity_unknown) : entry.identity)
                .append("\n").append(entry.decision.allowed && entry.addresses.isEmpty() ? getContext().getString(R.string.no_ipv4) : UiText.reason(getContext(), entry.decision.reason));
            for (String address : entry.addresses) {
                value.append("\n").append(entry.interfaceName).append(": ").append(address);
                if (entry.decision.allowed) value.append(":19191");
            }
            value.append("\n");
        }
        current.setText(value.length() == 0 ? getContext().getString(R.string.no_network) : value.toString().trim());
    }
    private void add(TextInputEditText input, boolean allowed) {
        String id = input.getText() == null ? "" : input.getText().toString();
        Set<String> allow = new HashSet<>(policy.allow), deny = new HashSet<>(policy.deny);
        (allowed ? allow : deny).add(id);
        if (save(policy.mode, allow, deny)) input.setText("");
    }
    private boolean save(NetworkPolicy.Mode mode, Set<String> allow, Set<String> deny) {
        try {
            NetworkSettings.save(getContext(), kind, mode, allow, deny);
            policy = NetworkSettings.read(getContext(), kind);
            if (lists != null) renderLists();
            if (error != null) error.setVisibility(GONE);
            changed.run(); return true;
        } catch (IOException e) { showError(UiText.status(getContext(), e.getMessage())); return false; }
    }
    private void showError(String value) {
        if (error == null) return;
        error.setText(value);
        error.setVisibility(VISIBLE);
    }
    private void renderLists() {
        lists.removeAllViews(); renderList(getContext().getString(R.string.allowlist), policy.allow, true); renderList(getContext().getString(R.string.blocklist), policy.deny, false);
    }
    private void renderList(String label, Set<String> identities, boolean allowed) {
        TextView heading = heading(label + (identities.isEmpty() ? ": " + getContext().getString(R.string.empty) : ""), lists);
        heading.setTextColor(color(allowed ? R.color.tone_ready : R.color.tone_attention));
        for (String id : new TreeSet<>(identities)) {
            LinearLayout row = new LinearLayout(getContext());
            row.setOrientation(HORIZONTAL); row.setGravity(Gravity.CENTER_VERTICAL);
            row.setBackground(rounded(R.color.row, 14));
            row.setPadding(dp(14), dp(4), dp(4), dp(4));
            TextView value = new TextView(getContext()); value.setText(id); value.setTextIsSelectable(true);
            value.setTextSize(15); value.setTextColor(color(R.color.ink));
            row.addView(value, new LinearLayout.LayoutParams(0, -2, 1));
            MaterialButton remove = new MaterialButton(getContext());
            remove.setText(R.string.remove); remove.setAllCaps(false);
            remove.setContentDescription(getContext().getString(R.string.remove_description, id, label));
            remove.setBackgroundTintList(ColorStateList.valueOf(Color.TRANSPARENT));
            remove.setTextColor(color(R.color.tone_attention));
            remove.setRippleColor(ColorStateList.valueOf((color(R.color.tone_attention) & 0x00FFFFFF) | 0x22000000));
            remove.setElevation(0);
            remove.setOnClickListener(v -> {
                Set<String> allow = new HashSet<>(policy.allow), deny = new HashSet<>(policy.deny);
                (allowed ? allow : deny).remove(id); save(policy.mode, allow, deny);
            });
            row.addView(remove, new LinearLayout.LayoutParams(-2, -2));
            lists.addView(row, spaced(2, 2));
        }
    }
    private TextView heading(String value) { return heading(value, this); }
    private TextView heading(String value, LinearLayout parent) {
        TextView view = new TextView(getContext());
        view.setText(value); view.setTextSize(14); view.setTextColor(color(R.color.tile_green));
        view.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        ViewCompat.setAccessibilityHeading(view, true);
        parent.addView(view, spaced(12, 4));
        return view;
    }
    private GradientDrawable rounded(int colorRes, int radius) {
        GradientDrawable box = new GradientDrawable();
        box.setColor(color(colorRes)); box.setCornerRadius(dp(radius));
        return box;
    }
    private LinearLayout.LayoutParams spaced(int top, int bottom) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(top); params.bottomMargin = dp(bottom);
        return params;
    }
    private int color(int resource) { return ContextCompat.getColor(getContext(), resource); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
