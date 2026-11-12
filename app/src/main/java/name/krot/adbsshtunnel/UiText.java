package name.krot.adbsshtunnel;

import android.content.Context;

/** Presentation of backend states; never changes tunnel or network decisions. */
public final class UiText {
    private UiText() { }
    /** Simple traffic-light state for the main screen; derived only from existing backend states. */
    public enum Tone { OFF, WORKING, READY, ATTENTION }
    public static Tone tone(boolean enabled, String state) {
        if (!enabled) return Tone.OFF;
        if (state == null || state.equals("Starting") || state.equals("Stopped")
            || state.equals("Checking root ADB protection") || state.startsWith("Checking local ADB "))
            return Tone.WORKING;
        if (state.startsWith("SSH :19191 → ADB :")) return Tone.READY;
        return Tone.ATTENTION;
    }
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
        if (isSuMissing(state)) return context.getString(R.string.root_su_missing);
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
            case "SSH listener failed": id = R.string.listener_failed; break;
            case "SSH exited; enable the tunnel to retry": id = R.string.ssh_exited; break;
            case "Import between 1 and 32 public keys": id = R.string.key_range; break;
            case "Use ssh-ed25519 or ssh-rsa public keys without options": id = R.string.key_format; break;
            case "Invalid Base64 public key": id = R.string.key_base64; break;
            case "Enter the exact network identity": id = R.string.network_exact; break;
            case "Operator PLMN: 5 or 6 digits (MCC + MNC)": id = R.string.plmn_invalid; break;
            case "Key input too large": id = R.string.key_too_large; break;
            case "RSA key must be at least 2048 bits": id = R.string.key_rsa_short; break;
            case "Invalid WireGuard interface": id = R.string.wireguard_invalid; break;
            case "SSID must contain at most 32 UTF-8 bytes": id = R.string.ssid_too_long; break;
            default: break;
        }
        if (id == 0 && KEY_DAMAGE.contains(state)) id = R.string.key_damaged;
        if (id == 0 && (state.startsWith("Cannot save ") || state.equals("Cannot create SSH configuration")))
            id = R.string.save_failed;
        if (id != 0) return context.getString(id);
        String prefix = "Checking local ADB ";
        if (state.startsWith(prefix)) return context.getString(R.string.checking_adb, state.substring(prefix.length()));
        prefix = "ADB discovery unavailable: ";
        if (state.startsWith(prefix)) return context.getString(R.string.discovery_error, state.substring(prefix.length()));
        prefix = "SSH :19191 → ADB :";
        int end = state.indexOf(" · client target 127.0.0.1:5555");
        if (state.startsWith(prefix) && end > prefix.length())
            return context.getString(R.string.ssh_ready, state.substring(prefix.length(), end));
        return state;
    }
    /** Key parser messages that mean the pasted text is not a usable public key. */
    private static final java.util.Set<String> KEY_DAMAGE = new java.util.HashSet<>(java.util.Arrays.asList(
        "Key type mismatch", "Trailing key data", "Invalid key field", "Invalid Ed25519 key length",
        "Noncanonical Ed25519 key", "Invalid Ed25519 point", "Small-order Ed25519 key",
        "RSA fields must be positive canonical mpints", "RSA fields must be positive"));
    private static final java.util.Set<String> KEY_ERRORS = new java.util.HashSet<>(java.util.Arrays.asList(
        "Key input too large", "RSA key must be at least 2048 bits", "Import between 1 and 32 public keys",
        "Use ssh-ed25519 or ssh-rsa public keys without options", "Invalid Base64 public key"));

    /** The screen that fixes a problem. */
    public enum Fix { KEYS, PERMISSIONS, INTERNET, ACCESS, DEVELOPER, ADB, RETRY }

    /** A backend state explained in plain words: what happened, what to do, and where. */
    public enum Problem {
        NONE(0, 0, 0, 0, Fix.RETRY, false),
        KEYS(R.drawable.ic_key, R.string.problem_keys_title, R.string.problem_keys_what,
            R.string.problem_keys_todo, Fix.KEYS, false),
        WIFI_IDENTITY(R.drawable.ic_lock, R.string.problem_wifi_title, R.string.problem_wifi_what,
            R.string.problem_wifi_todo, Fix.PERMISSIONS, false),
        NO_NETWORK(R.drawable.ic_network, R.string.problem_offline_title, R.string.problem_offline_what,
            R.string.problem_offline_todo, Fix.INTERNET, false),
        NETWORK_BLOCKED(R.drawable.ic_network, R.string.problem_blocked_title, R.string.problem_blocked_what,
            R.string.problem_blocked_todo, Fix.ACCESS, false),
        WIRELESS(R.drawable.ic_adb, R.string.problem_wireless_title, R.string.problem_wireless_what,
            R.string.problem_wireless_todo, Fix.DEVELOPER, false),
        ADB_NOT_READY(R.drawable.ic_adb, R.string.problem_adb_title, R.string.problem_adb_what,
            R.string.problem_adb_todo, Fix.ADB, true),
        DISCOVERY(R.drawable.ic_adb, R.string.problem_discovery_title, R.string.problem_discovery_what,
            R.string.problem_discovery_todo, Fix.DEVELOPER, true),
        ROOT_MISSING(R.drawable.ic_shield, R.string.problem_noroot_title, R.string.problem_noroot_what,
            R.string.problem_noroot_todo, Fix.ADB, false),
        ROOT_FAILED(R.drawable.ic_shield, R.string.problem_root_title, R.string.problem_root_what,
            R.string.problem_root_todo, Fix.ADB, true),
        SSH_FAILED(R.drawable.ic_warning, R.string.problem_ssh_title, R.string.problem_ssh_what,
            R.string.problem_ssh_todo, Fix.RETRY, false),
        UNKNOWN(R.drawable.ic_warning, R.string.problem_unknown_title, R.string.problem_unknown_what,
            R.string.problem_unknown_todo, Fix.RETRY, false);

        public final int icon, title, what, todo;
        public final Fix fix;
        /** Also offer "Try again" next to the main button. */
        public final boolean retry;

        Problem(int icon, int title, int what, int todo, Fix fix, boolean retry) {
            this.icon = icon; this.title = title; this.what = what; this.todo = todo;
            this.fix = fix; this.retry = retry;
        }
    }

    /** Explains every state that {@link #tone} marks as needing attention; NONE for normal progress. */
    public static Problem problem(String state) {
        if (state == null || state.equals("Starting") || state.equals("Stopped")
            || state.equals("Checking root ADB protection") || state.startsWith("Checking local ADB ")
            || state.startsWith("SSH :19191 → ADB :")) return Problem.NONE;
        if (isSuMissing(state)) return Problem.ROOT_MISSING;
        switch (state) {
            case "Open the app to identify Wi-Fi": return Problem.WIFI_IDENTITY;
            case "No available Wi-Fi or mobile Internet network": return Problem.NO_NETWORK;
            case "SSH closed: no allowed network. Check lists and Android permissions": return Problem.NETWORK_BLOCKED;
            case "Waiting for Wireless Debugging":
            case "Wireless Debugging stopped; enable it in Developer options": return Problem.WIRELESS;
            case "Waiting for local ADB: configure the selected mode":
            case "Local ADB unavailable; configure debugging first": return Problem.ADB_NOT_READY;
            case "SSH listener failed":
            case "SSH exited; enable the tunnel to retry": return Problem.SSH_FAILED;
            case "Unsupported controller ABI":
            case "Cannot prepare root controller": return Problem.ROOT_FAILED;
            default: break;
        }
        if (state.equals("ADB discovery unavailable") || state.startsWith("ADB discovery unavailable: "))
            return Problem.DISCOVERY;
        if (KEY_ERRORS.contains(state) || KEY_DAMAGE.contains(state)) return Problem.KEYS;
        if (state.startsWith("Cannot run program \"su\"") || state.startsWith("Root ")) return Problem.ROOT_FAILED;
        return Problem.UNKNOWN;
    }

    /**
     * The problem to show on the main screen. While the tunnel is on, every attention state is
     * explained. When it is off, only start failures stay visible (a rejected key, a stopped SSH
     * server or an unexpected error), because waiting states describe a tunnel that is no longer running.
     */
    public static Problem visibleProblem(boolean enabled, String state) {
        Problem problem = problem(state);
        if (enabled) return problem;
        return problem == Problem.KEYS || problem == Problem.SSH_FAILED || problem == Problem.UNKNOWN
            ? problem : Problem.NONE;
    }

    static boolean isSuMissing(String error) {
        return error != null && (error.equals("Cannot run program \"su\": error=2")
            || error.startsWith("Cannot run program \"su\": error=2,"));
    }
    public static String rootError(Context context, String error) {
        if (isSuMissing(error)) return context.getString(R.string.root_su_missing);
        return error == null ? "" : error;
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
