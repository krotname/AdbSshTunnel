package name.krot.adbsshtunnel;

import static org.junit.Assert.*;
import java.util.Set;
import org.junit.Test;

public final class NetworkPolicyTest {
    private NetworkPolicy policy(NetworkPolicy.Mode mode, Set<String> allow, Set<String> deny) {
        return new NetworkPolicy(mode, allow, deny);
    }
    @Test public void newInstallationRequiresAnExplicitNetwork() {
        assertFalse(policy(NetworkPolicy.Mode.ALLOWLIST, Set.of(), Set.of()).decide("Home").allowed);
    }
    @Test public void denialWinsOverAnAllowEntryInBothModes() {
        for (NetworkPolicy.Mode mode : NetworkPolicy.Mode.values()) {
            NetworkPolicy.Decision result = policy(mode, Set.of("Home"), Set.of("Home")).decide("Home");
            assertFalse(result.allowed);
            assertEquals(NetworkPolicy.Reason.DENIED, result.reason);
        }
    }
    @Test public void ssidsAreExactAndCaseSensitive() {
        NetworkPolicy p = policy(NetworkPolicy.Mode.ALLOWLIST, Set.of("Home", "Office*"), Set.of());
        assertTrue(p.decide("Home").allowed);
        assertFalse(p.decide("home").allowed);
        assertFalse(p.decide("Office guest").allowed);
        assertFalse(p.decide("Home ").allowed);
    }
    @Test public void blacklistAllowsOnlyAnIdentifiedNetwork() {
        NetworkPolicy p = policy(NetworkPolicy.Mode.BLOCKLIST, Set.of(), Set.of("Guest"));
        assertTrue(p.decide("Home").allowed);
        assertFalse(p.decide("Guest").allowed);
        for (String unknown : new String[] {null, "", "<unknown ssid>"}) {
            assertEquals(NetworkPolicy.Reason.UNKNOWN, p.decide(unknown).reason);
            assertFalse(p.decide(unknown).allowed);
        }
    }
    @Test public void disabledTransportCannotBeEnabledByAListEntry() {
        assertFalse(policy(NetworkPolicy.Mode.DISABLED, Set.of("Home"), Set.of()).decide("Home").allowed);
    }
    @Test public void wifiAndMobileHaveIndependentLists() {
        NetworkPolicy wifi = policy(NetworkPolicy.Mode.ALLOWLIST, Set.of("25001"), Set.of());
        NetworkPolicy mobile = policy(NetworkPolicy.Mode.ALLOWLIST, Set.of(), Set.of());
        assertTrue(wifi.decide("25001").allowed);
        assertFalse(mobile.decide("25001").allowed);
    }
    @Test public void callerCannotMutateSavedDecisionRules() {
        java.util.HashSet<String> allow = new java.util.HashSet<>(Set.of("Home"));
        NetworkPolicy p = policy(NetworkPolicy.Mode.ALLOWLIST, allow, Set.of());
        allow.add("Guest");
        assertFalse(p.decide("Guest").allowed);
    }
}
