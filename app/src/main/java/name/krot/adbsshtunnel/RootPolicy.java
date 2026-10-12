package name.krot.adbsshtunnel;

import java.nio.charset.StandardCharsets;

/** Direct ADB trust is independent of the SSH network policy. Empty trust is closed. */
public final class RootPolicy {
    public final boolean enabled;
    public final String wifiPrefix, hotspotSsid, wireguardInterface;
    public RootPolicy(boolean enabled, String wifiPrefix, String hotspotSsid, String wireguardInterface) {
        this.enabled = enabled;
        this.wifiPrefix = ssid(wifiPrefix);
        this.hotspotSsid = ssid(hotspotSsid);
        String iface = wireguardInterface.trim();
        if (!iface.isEmpty() && (!iface.matches("[A-Za-z0-9_][A-Za-z0-9_.-]{0,14}")
                || iface.equals("lo"))) throw new IllegalArgumentException("Invalid WireGuard interface");
        this.wireguardInterface = iface;
    }
    private static String ssid(String value) {
        if (value.getBytes(StandardCharsets.UTF_8).length > 32 || value.indexOf('\u0000') >= 0
                || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0)
            throw new IllegalArgumentException("SSID must contain at most 32 UTF-8 bytes");
        return value;
    }
    private static String hex(String value) {
        StringBuilder result = new StringBuilder();
        for (byte b : value.getBytes(StandardCharsets.UTF_8)) {
            result.append(Character.forDigit((b >>> 4) & 15, 16));
            result.append(Character.forDigit(b & 15, 16));
        }
        return result.toString();
    }
    public String encode() {
        return "version=1\nenabled=" + (enabled ? "1" : "0") + "\nwifi_prefix=" + hex(wifiPrefix)
            + "\nhotspot_ssid=" + hex(hotspotSsid) + "\nwireguard_iface=" + wireguardInterface + "\n";
    }
    public RootPolicy withEnabled(boolean value) {
        return new RootPolicy(value, wifiPrefix, hotspotSsid, wireguardInterface);
    }
}
