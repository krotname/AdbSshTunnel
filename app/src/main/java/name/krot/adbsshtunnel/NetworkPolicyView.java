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
        TextView title = new TextView(context); title.setText(kind == NetworkSettings.Kind.WIFI ? "Wi-Fi networks" : "Mobile operators"); title.setTextSize(20); addView(title);
        current = new TextView(context); current.setTextIsSelectable(true); addView(current);
        RadioGroup modes = new RadioGroup(context);
        String[] labels = {"Allowlist: listed networks only", "Blocklist: all known networks except blocked", "Disabled: no access on this transport"};
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
            ? "Exact SSID, including case and spaces. No wildcards. A blocked entry always wins. Unknown SSIDs are closed."
            : "PLMN: 5 or 6 digits (MCC + MNC) of the connected operator, including roaming. IMS and VPN interfaces are excluded. Unknown operators are closed.");
        addView(help);
        TextInputLayout input = new TextInputLayout(context); input.setHint(kind == NetworkSettings.Kind.WIFI ? "Exact SSID" : "Operator PLMN");
        TextInputEditText id = new TextInputEditText(input.getContext()); id.setSingleLine(true);
        if (kind == NetworkSettings.Kind.MOBILE) id.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        input.addView(id); addView(input);
        action("Add to allowlist", () -> add(id, true));
        action("Add to blocklist", () -> add(id, false));
        action("Allow current network", () -> {
            List<NetworkMonitor.Entry> entries = visibleEntries;
            List<String> identities = new ArrayList<>();
            for (NetworkMonitor.Entry entry : entries) if (entry.kind == kind && entry.identity != null && !identities.contains(entry.identity)) identities.add(entry.identity);
            if (identities.size() == 1) { id.setText(identities.get(0)); add(id, true); }
            else Toast.makeText(context, "Enter the exact identity above; no single current network is identified", Toast.LENGTH_LONG).show();
        });
        lists = new LinearLayout(context); lists.setOrientation(VERTICAL); addView(lists); renderLists();
    }
    private List<NetworkMonitor.Entry> visibleEntries = Collections.emptyList();
    public void showNetworks(List<NetworkMonitor.Entry> entries) {
        visibleEntries = entries;
        StringBuilder value = new StringBuilder();
        for (NetworkMonitor.Entry entry : entries) {
            if (entry.kind != kind) continue;
            value.append(entry.label).append(" · ").append(entry.identity == null ? "identity unavailable" : entry.identity)
                .append("\n").append(entry.decision.allowed && entry.addresses.isEmpty() ? "No usable IPv4 / blocked by Android" : NetworkSettings.reason(entry.decision.reason));
            for (String address : entry.addresses) {
                value.append("\n").append(entry.interfaceName).append(": ").append(address);
                if (entry.decision.allowed) value.append(":19191");
            }
            value.append("\n");
        }
        current.setText(value.length() == 0 ? "No physical Internet network of this type" : value.toString());
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
        } catch (IOException e) { Toast.makeText(getContext(), e.getMessage(), Toast.LENGTH_LONG).show(); return false; }
    }
    private void action(String label, Runnable run) {
        MaterialButton button = new MaterialButton(getContext()); button.setText(label); button.setOnClickListener(v -> run.run()); addView(button);
    }
    private void renderLists() {
        lists.removeAllViews(); renderList("Allowlist", policy.allow, true); renderList("Blocklist", policy.deny, false);
    }
    private void renderList(String label, Set<String> identities, boolean allowed) {
        TextView heading = new TextView(getContext()); heading.setText(label + (identities.isEmpty() ? ": empty" : "")); lists.addView(heading);
        for (String id : new TreeSet<>(identities)) {
            LinearLayout row = new LinearLayout(getContext());
            TextView value = new TextView(getContext()); value.setText(id); value.setTextIsSelectable(true); row.addView(value, new LinearLayout.LayoutParams(0, -2, 1));
            MaterialButton remove = new MaterialButton(getContext()); remove.setText("Remove"); remove.setContentDescription("Remove " + id + " from " + label);
            remove.setOnClickListener(v -> {
                Set<String> allow = new HashSet<>(policy.allow), deny = new HashSet<>(policy.deny);
                (allowed ? allow : deny).remove(id); save(policy.mode, allow, deny);
            });
            row.addView(remove); lists.addView(row);
        }
    }
}
