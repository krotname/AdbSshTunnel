package name.krot.adbsshtunnel;

import android.content.Context;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

public final class NetworkSettings {
    public enum Kind { WIFI, MOBILE }
    private NetworkSettings() { }
    public static NetworkPolicy read(Context context, Kind kind) {
        String prefix = "network_" + kind.name() + "_";
        android.content.SharedPreferences prefs = Settings.prefs(context);
        NetworkPolicy.Mode mode;
        try { mode = NetworkPolicy.Mode.valueOf(prefs.getString(prefix + "mode", "ALLOWLIST")); }
        catch (IllegalArgumentException e) { mode = NetworkPolicy.Mode.DISABLED; }
        return new NetworkPolicy(mode,
            prefs.getStringSet(prefix + "allow", Set.of()), prefs.getStringSet(prefix + "deny", Set.of()));
    }
    public static void save(Context context, Kind kind, NetworkPolicy.Mode mode,
                            Set<String> allow, Set<String> deny) throws IOException {
        for (String id : allow) validate(kind, id);
        for (String id : deny) validate(kind, id);
        String prefix = "network_" + kind.name() + "_";
        if (!Settings.prefs(context).edit().putString(prefix + "mode", mode.name())
            .putStringSet(prefix + "allow", new HashSet<>(allow))
            .putStringSet(prefix + "deny", new HashSet<>(deny)).commit())
            throw new IOException("Cannot save network rules");
    }
    public static void validate(Kind kind, String id) throws IOException {
        if (id == null || id.isEmpty() || id.equals("<unknown ssid>") || id.indexOf('\0') >= 0)
            throw new IOException("Enter the exact network identity");
        if (kind == Kind.MOBILE && !id.matches("[0-9]{5,6}"))
            throw new IOException("Operator PLMN: 5 or 6 digits (MCC + MNC)");
    }
    public static String reason(NetworkPolicy.Reason reason) {
        switch (reason) {
            case DENIED: return "Blocked: in blocklist";
            case DISABLED: return "Blocked: transport disabled";
            case UNKNOWN: return "Blocked: identity unavailable";
            case NOT_LISTED: return "Blocked: not in allowlist";
            case ALLOWED: return "Allowed: in allowlist";
            case NOT_BLOCKED: return "Allowed: not in blocklist";
            default: return "Blocked";
        }
    }
}
