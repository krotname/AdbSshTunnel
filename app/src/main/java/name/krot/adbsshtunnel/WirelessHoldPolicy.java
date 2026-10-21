package name.krot.adbsshtunnel;

/** Recovery is armed by a user-enabled debugging session on trusted Wi-Fi. */
public final class WirelessHoldPolicy {
    public static final long IDLE = -1;
    private boolean armed;
    private long nextAttempt;
    private int attempts;
    public WirelessHoldPolicy(boolean armed) { this.armed = armed; }
    public boolean armed() { return armed; }
    /** -1: await an event; 0: restore now; positive: retry delay in milliseconds. */
    public long update(boolean selected, boolean permission, boolean trusted,
                       boolean enabled, long now) {
        if (!selected) { armed = false; attempts = 0; nextAttempt = 0; return IDLE; }
        if (enabled) {
            if (permission && trusted) armed = true;
            if (now >= nextAttempt + 60_000) { attempts = 0; nextAttempt = 0; }
            return IDLE;
        }
        if (!armed || !permission || !trusted) return IDLE;
        if (now < nextAttempt) return nextAttempt - now;
        nextAttempt = now + Math.min(60_000L, 2_000L << Math.min(attempts++, 5));
        return 0;
    }
}
