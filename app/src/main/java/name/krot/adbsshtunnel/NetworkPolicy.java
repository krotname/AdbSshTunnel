package name.krot.adbsshtunnel;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/** Decisions contain no Android state; callers must supply the current network identity. */
public final class NetworkPolicy {
    public enum Mode { ALLOWLIST, BLOCKLIST, DISABLED }
    public enum Reason { DENIED, DISABLED, UNKNOWN, NOT_LISTED, ALLOWED, NOT_BLOCKED }
    public static final class Decision {
        public final boolean allowed;
        public final Reason reason;
        private Decision(boolean allowed, Reason reason) { this.allowed = allowed; this.reason = reason; }
    }
    public final Mode mode;
    public final Set<String> allow, deny;
    public NetworkPolicy(Mode mode, Set<String> allow, Set<String> deny) {
        this.mode = mode;
        this.allow = Collections.unmodifiableSet(new HashSet<>(allow));
        this.deny = Collections.unmodifiableSet(new HashSet<>(deny));
    }
    public Decision decide(String identity) {
        if (deny.contains(identity)) return new Decision(false, Reason.DENIED);
        if (mode == Mode.DISABLED) return new Decision(false, Reason.DISABLED);
        if (identity == null || identity.isEmpty() || identity.equals("<unknown ssid>"))
            return new Decision(false, Reason.UNKNOWN);
        if (mode == Mode.ALLOWLIST) return allow.contains(identity)
            ? new Decision(true, Reason.ALLOWED) : new Decision(false, Reason.NOT_LISTED);
        return new Decision(true, Reason.NOT_BLOCKED);
    }
}
