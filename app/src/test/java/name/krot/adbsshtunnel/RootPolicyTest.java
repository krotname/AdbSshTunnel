package name.krot.adbsshtunnel;

import org.junit.Test;
import static org.junit.Assert.*;

public final class RootPolicyTest {
    @Test public void encodesUnicodeWithoutCommandOrPolicyInjection() {
        String policy = new RootPolicy(true, "Дом", "A=;$(x)", "wg0").encode();
        assertTrue(policy.contains("wifi_prefix=d094d0bed0bc\n"));
        assertTrue(policy.contains("hotspot_ssid=413d3b24287829\n"));
        assertEquals(6, policy.lines().count());
    }
    @Test public void usbHoldIsIndependentAndSurvivesTcpToggle() {
        RootPolicy policy = new RootPolicy(false, "Home", "AP", "wg0", true).withEnabled(true);
        assertTrue(policy.usbEnabled);
        assertTrue(policy.encode().contains("usb_enabled=1\n"));
        RootPolicy off = policy.withUsbEnabled(false);
        assertTrue(off.enabled);
        assertEquals("Home", off.wifiPrefix);
        assertTrue(off.encode().contains("usb_enabled=0\n"));
    }
    @Test public void limitsUtf8BytesRatherThanCharacters() {
        new RootPolicy(false, "я".repeat(16), "", "");
        try { new RootPolicy(false, "я".repeat(17), "", ""); fail(); }
        catch (IllegalArgumentException expected) { }
    }
    @Test public void rejectsInterfaceAndLineInjection() {
        for (String iface : new String[]{"wg0;id", "wg+", "lo", "abcdefghijklmnop", "wg0\nenabled=1"}) {
            try { new RootPolicy(true, "", "", iface); fail(iface); }
            catch (IllegalArgumentException expected) { }
        }
        try { new RootPolicy(true, "ssid\nenabled=1", "", ""); fail(); }
        catch (IllegalArgumentException expected) { }
    }
}
