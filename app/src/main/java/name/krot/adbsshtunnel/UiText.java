package name.krot.adbsshtunnel;

import android.content.Context;

/** Presentation of backend states; never changes tunnel or network decisions. */
public final class UiText {
    private UiText() { }
    public static int mode(NetworkPolicy.Mode mode) {
        switch (mode) {
            case ALLOWLIST: return R.string.mode_allow;
            case BLOCKLIST: return R.string.mode_deny;
            default: return R.string.mode_disabled;
        }
    }
    public static String reason(Context context, NetworkPolicy.Reason reason) {
        int id;
        switch (reason) {
            case DENIED: id = R.string.reason_denied; break;
            case DISABLED: id = R.string.reason_disabled; break;
            case UNKNOWN: id = R.string.reason_unknown; break;
            case NOT_LISTED: id = R.string.reason_not_listed; break;
            case ALLOWED: id = R.string.reason_allowed; break;
            default: id = R.string.reason_not_blocked;
        }
        return context.getString(id);
    }
    public static String status(Context context, String state) {
        if (state == null) return "";
        int id = 0;
        switch (state) {
            case "Stopped": id = R.string.stopped; break;
            case "Starting": id = R.string.starting; break;
            case "Waiting for Wireless Debugging": id = R.string.waiting_wireless; break;
            case "Checking root ADB protection": id = R.string.checking_root; break;
            case "Open the app to identify Wi-Fi": id = R.string.open_wifi; break;
            case "No available Wi-Fi or mobile Internet network": id = R.string.no_internet_network; break;
            case "SSH closed: no allowed network. Check lists and Android permissions": id = R.string.ssh_closed; break;
            case "Waiting for local ADB: configure the selected mode": id = R.string.waiting_adb; break;
            case "Local ADB unavailable; configure debugging first": id = R.string.adb_unavailable; break;
            case "ADB discovery unavailable": id = R.string.discovery_unavailable; break;
            case "Wireless Debugging stopped; enable it in Developer options": id = R.string.discovery_stopped; break;
            case "Import between 1 and 32 public keys": id = R.string.key_range; break;
            case "Use ssh-ed25519 or ssh-rsa public keys without options": id = R.string.key_format; break;
            case "Invalid Base64 public key": id = R.string.key_base64; break;
            case "Enter the exact network identity": id = R.string.network_exact; break;
            case "Operator PLMN: 5 or 6 digits (MCC + MNC)": id = R.string.plmn_invalid; break;
            default: break;
        }
        if (id != 0) return context.getString(id);
        String prefix = "Checking local ADB ";
        if (state.startsWith(prefix)) return context.getString(R.string.checking_adb, state.substring(prefix.length()));
        prefix = "SSH :19191 → ADB :";
        int end = state.indexOf(" · client target 127.0.0.1:5555");
        if (state.startsWith(prefix) && end > prefix.length())
            return context.getString(R.string.ssh_ready, state.substring(prefix.length(), end));
        return state;
    }
    public static String diagnostics(Context context, String value) {
        value = value.replace("External IPv4: ", context.getString(R.string.diag_external));
        value = value.replace("Active interface IPv4: ", context.getString(R.string.diag_interface));
        value = value.replace("none", context.getString(R.string.diag_none));
        value = value.replace("VPN is active. The external address may identify its exit; incoming access to this phone requires a separate route.", context.getString(R.string.diag_vpn));
        value = value.replace("The interface uses carrier NAT space (100.64.0.0/10). Direct incoming access needs a public IPv4 from the operator or another tunnel.", context.getString(R.string.diag_cgnat));
        value = value.replace("External and interface addresses match. Operator filtering or a firewall may still block incoming SSH.", context.getString(R.string.diag_match));
        value = value.replace("The external address differs from Wi-Fi. Incoming WAN access may require router port forwarding; this app does not change the router.", context.getString(R.string.diag_wifi));
        value = value.replace("External and interface addresses differ. NAT is present; a public address alone does not provide an incoming mapping.", context.getString(R.string.diag_nat));
        value = value.replace("Incoming SSH reachability must be tested from another network.", context.getString(R.string.diag_test));
        return value;
    }
}
