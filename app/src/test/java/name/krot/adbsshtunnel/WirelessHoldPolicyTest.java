package name.krot.adbsshtunnel;

import org.junit.Test;
import static org.junit.Assert.*;

public final class WirelessHoldPolicyTest {
    @Test public void waitsForFirstTrustedUserSession() {
        WirelessHoldPolicy policy = new WirelessHoldPolicy(false);
        assertEquals(-1, policy.update(true, true, true, false, 0));
        policy.update(true, true, false, true, 1);
        assertFalse(policy.armed());
        policy.update(true, true, true, true, 2);
        assertTrue(policy.armed());
        assertEquals(0, policy.update(true, true, true, false, 3));
    }
    @Test public void missingPermissionOrUntrustedNetworkNeverRestores() {
        WirelessHoldPolicy policy = new WirelessHoldPolicy(true);
        assertEquals(-1, policy.update(true, false, true, false, 0));
        assertEquals(-1, policy.update(true, true, false, false, 1));
        assertEquals(0, policy.update(true, true, true, false, 2));
    }
    @Test public void disablingDisarmsRecovery() {
        WirelessHoldPolicy policy = new WirelessHoldPolicy(true);
        assertEquals(-1, policy.update(false, true, true, true, 0));
        assertFalse(policy.armed());
        assertEquals(-1, policy.update(true, true, true, false, 1));
    }
    @Test public void repeatedAndroidShutdownsCannotCauseWriteLoop() {
        WirelessHoldPolicy policy = new WirelessHoldPolicy(true);
        long now = 0;
        for (int i = 0; i < 12; ++i) {
            assertEquals(0, policy.update(true, true, true, false, now));
            policy.update(true, true, true, true, now + 1);
            long wait = policy.update(true, true, true, false, now + 2);
            assertTrue(wait >= 1_998 && wait <= 60_000);
            now += wait + 2;
        }
    }
    @Test public void roamingRetainsIntentUntilTrustedWifiReturns() {
        WirelessHoldPolicy policy = new WirelessHoldPolicy(true);
        assertEquals(-1, policy.update(true, true, false, false, 0));
        assertTrue(policy.armed());
        assertEquals(0, policy.update(true, true, true, false, 10_000));
    }
}
