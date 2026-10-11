package name.krot.adbsshtunnel;

import android.content.Context;
import android.view.View;
import android.widget.*;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.*;
import java.io.IOException;
import java.util.*;

/** Edits one transport independently; every saved change revokes existing SSH sessions. */
public final class NetworkPolicyView extends LinearLayout {
    private final NetworkSettings.Kind kind;
    private final Runnable changed;
    private final TextView current;
    private final LinearLayout lists;
    private NetworkPolicy policy;
    public NetworkPolicyView(Context context, NetworkSettings.Kind kind, Runnable changed) {
        super(context); this.kind = kind; this.changed = changed;
        setOrientation(VERTICAL); policy = NetworkSettings.read(context, kind);
        TextView title = new TextView(context); title.setText(context.getString(kind == NetworkSettings.Kind.WIFI ? R.string.wifi_networks : R.string.mobile_operators)); title.setTextSize(20); addView(title);
        current = new TextView(context); current.setTextIsSelectable(true); addView(current);
        RadioGroup modes = new RadioGroup(context);
        String[] labels = {context.getString(R.string.mode_allow), context.getString(R.string.mode_deny), context.getString(R.string.mode_disabled)};
        NetworkPolicy.Mode[] values = NetworkPolicy.Mode.values();
        for (int i = 0; i < values.length; i++) {
            RadioButton button = new RadioButton(context); button.setId(View.generateViewId()); button.setText(labels[i]); button.setTag(values[i]); modes.addView(button);
            if (policy.mode == values[i]) modes.check(button.getId());
        }
        modes.setOnCheckedChangeListener((group, checked) -> {
            RadioButton selected = group.findViewById(checked);
            if (selected != null && !save((NetworkPolicy.Mode) selected.getTag(), policy.allow, policy.deny)) {
                for (int i = 0; i < group.getChildCount(); i++) {
                    RadioButton button = (RadioButton) group.getChildAt(i);
                    if (button.getTag() == policy.mode) group.check(button.getId());
                }
            }
        });
        addView(modes);
        TextView help = new TextView(context);
        help.setText(kind == NetworkSettings.Kind.WIFI
            ? context.getString(R.string.wifi_help)
            : context.getString(R.string.mobile_help));
        addView(help);
        TextInputLayout input = new TextInputLayout(context); input.setHint(context.getString(kind == NetworkSettings.Kind.WIFI ? R.string.ssid : R.string.plmn));
        TextInputEditText id = new TextInputEditText(input.getContext()); id.setSingleLine(true);
        if (kind == NetworkSettings.Kind.MOBILE) id.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        input.addView(id); addView(input);
        action(context.getString(R.string.add_allow), () -> add(id, true));
        action(context.getString(R.string.add_deny), () -> add(id, false));
        action(context.getString(R.string.allow_current), () -> {
            List<NetworkMonitor.Entry> entries = visibleEntries;
            List<String> identities = new ArrayList<>();
            for (NetworkMonitor.Entry entry : entries) if (entry.kind == kind && entry.identity != null && !identities.contains(entry.identity)) identities.add(entry.identity);
            if (identities.size() == 1) { id.setText(identities.get(0)); add(id, true); }
            else Toast.makeText(context, context.getString(R.string.no_single_network), Toast.LENGTH_LONG).show();
        });
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
        current.setText(value.length() == 0 ? getContext().getString(R.string.no_network) : value.toString());
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
            changed.run(); return true;
        } catch (IOException e) { Toast.makeText(getContext(), UiText.status(getContext(), e.getMessage()), Toast.LENGTH_LONG).show(); return false; }
    }
    private void action(String label, Runnable run) {
        MaterialButton button = new MaterialButton(getContext()); button.setText(label); button.setOnClickListener(v -> run.run()); addView(button);
    }
    private void renderLists() {
        lists.removeAllViews(); renderList(getContext().getString(R.string.allowlist), policy.allow, true); renderList(getContext().getString(R.string.blocklist), policy.deny, false);
    }
    private void renderList(String label, Set<String> identities, boolean allowed) {
        TextView heading = new TextView(getContext()); heading.setText(label + (identities.isEmpty() ? ": " + getContext().getString(R.string.empty) : "")); lists.addView(heading);
        for (String id : new TreeSet<>(identities)) {
            LinearLayout row = new LinearLayout(getContext());
            TextView value = new TextView(getContext()); value.setText(id); value.setTextIsSelectable(true); row.addView(value, new LinearLayout.LayoutParams(0, -2, 1));
            MaterialButton remove = new MaterialButton(getContext()); remove.setText(R.string.remove); remove.setContentDescription(getContext().getString(R.string.remove_description, id, label));
            remove.setOnClickListener(v -> {
                Set<String> allow = new HashSet<>(policy.allow), deny = new HashSet<>(policy.deny);
                (allowed ? allow : deny).remove(id); save(policy.mode, allow, deny);
            });
            row.addView(remove); lists.addView(row);
        }
    }
}
