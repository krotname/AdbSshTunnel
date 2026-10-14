/* Host-only pure tests. No privileged implementation is compiled in, even
 * when this executable is accidentally run as root. Suggested Linux command:
 * cc -std=c11 -Wall -Wextra -Werror tools/test-root-controller.c -o /tmp/test-root-controller
 * /tmp/test-root-controller
 * Compile the production translation unit separately with the same warnings
 * on Linux and with the Android NDK; these tests do not exercise /system/bin.
 */
#define ROOT_CONTROLLER_TEST
#include "../app/src/main/cpp/root-controller.c"

static int failures;
#define CHECK(expr) do { if (!(expr)) { fprintf(stderr, "line %d: %s\n", __LINE__, #expr); ++failures; } } while (0)

static const char policy_text[] =
    "version=1\nenabled=1\nwifi_prefix=486f6d65\nhotspot_ssid=506978656c\nwireguard_iface=wg0\n";

static bool has_iface(const struct interfaces *a, const char *s) {
    for (size_t i = 0; i < a->count; ++i) if (!strcmp(a->name[i], s)) return true;
    return false;
}

static void parser_tests(void) {
    struct policy p, roundtrip;
    CHECK(parse_policy(policy_text, strlen(policy_text), &p));
    CHECK(p.enabled && p.wifi_len == 4 && !memcmp(p.wifi, "Home", 4));
    CHECK(p.hotspot_len == 5 && !memcmp(p.hotspot, "Pixel", 5) && !strcmp(p.wg, "wg0"));
    char canonical[1024];
    CHECK(serialize_policy(&p, canonical, sizeof(canonical)));
    CHECK(!strcmp(canonical, policy_text));
    CHECK(parse_policy(canonical, strlen(canonical), &roundtrip));
    CHECK(roundtrip.enabled == p.enabled && roundtrip.wifi_len == p.wifi_len &&
          roundtrip.hotspot_len == p.hotspot_len && !strcmp(roundtrip.wg, p.wg));
    const char reordered[] = "wireguard_iface=\nhotspot_ssid=\nwifi_prefix=\nenabled=0\nversion=1";
    CHECK(parse_policy(reordered, strlen(reordered), &roundtrip));
    CHECK(!roundtrip.enabled && !roundtrip.wifi_len && !roundtrip.hotspot_len && !*roundtrip.wg);
    const char *bad[] = {
        "", "version=1\n", "version=2\nenabled=1\nwifi_prefix=\nhotspot_ssid=\nwireguard_iface=\n",
        "version=1\nenabled=true\nwifi_prefix=\nhotspot_ssid=\nwireguard_iface=\n",
        "version=1\nenabled=1 \nwifi_prefix=\nhotspot_ssid=\nwireguard_iface=\n",
        "version=1\nenabled=1\nwifi_prefix=4A\nhotspot_ssid=\nwireguard_iface=\n",
        "version=1\nenabled=1\nwifi_prefix=1\nhotspot_ssid=\nwireguard_iface=\n",
        "version=1\nenabled=1\nwifi_prefix=gg\nhotspot_ssid=\nwireguard_iface=\n",
        "version=1\nenabled=1\nwifi_prefix=c080\nhotspot_ssid=\nwireguard_iface=\n",
        "version=1\nenabled=1\nwifi_prefix=eda080\nhotspot_ssid=\nwireguard_iface=\n",
        "version=1\nenabled=1\nwifi_prefix=f4908080\nhotspot_ssid=\nwireguard_iface=\n",
        "version=1\nenabled=1\nwifi_prefix=e2\nhotspot_ssid=\nwireguard_iface=\n",
        "version=1\nenabled=1\nwifi_prefix=\nhotspot_ssid=\nwireguard_iface=wg+\n",
        "version=1\nenabled=1\nwifi_prefix=\nhotspot_ssid=\nwireguard_iface=wg0;id\n",
        "version=1\nenabled=1\nwifi_prefix=\nhotspot_ssid=\nwireguard_iface=$(id)\n",
        "version=1\nenabled=1\nwifi_prefix=\nhotspot_ssid=\nwireguard_iface=../../x\n",
        "version=1\nenabled=1\nwifi_prefix=\nhotspot_ssid=\nwireguard_iface=wg0\nunknown=1\n",
        "version=1\nenabled=1\nwifi_prefix=\nhotspot_ssid=\nwireguard_iface=wg0\nenabled=0\n",
        "version=1\nenabled=1\nwifi_prefix=\nhotspot_ssid=\nwireguard_iface=wg0\n\n",
        "version=1\r\nenabled=1\nwifi_prefix=\nhotspot_ssid=\nwireguard_iface=wg0\n",
        "version=1\nenabled=1\nwifi_prefix=\nhotspot_ssid=\nwireguard_iface=wg0\n# comment\n"
    };
    for (size_t i = 0; i < sizeof(bad) / sizeof(bad[0]); ++i) {
        struct policy unchanged = p;
        CHECK(!parse_policy(bad[i], strlen(bad[i]), &unchanged));
        char retained[1024];
        CHECK(serialize_policy(&unchanged, retained, sizeof(retained)) && !strcmp(retained, policy_text));
    }
    char oversized[POLICY_MAX + 1]; memset(oversized, 'x', sizeof(oversized));
    CHECK(!parse_policy(oversized, sizeof(oversized), &roundtrip));
    char nul[sizeof(policy_text)]; memcpy(nul, policy_text, sizeof(nul)); nul[10] = 0;
    CHECK(!parse_policy(nul, sizeof(nul) - 1, &roundtrip));
    unsigned char decoded[SSID_MAX + 1]; size_t len;
    CHECK(decode_ssid("d094d0bed0bc", decoded, &len) && len == 6); /* Дом */
    CHECK(decode_ssid("000a", decoded, &len) && len == 2); /* Safe hex data, never shell text. */
    CHECK(!decode_ssid("80", decoded, &len));
    char long_hex[SSID_MAX * 2 + 3]; memset(long_hex, '6', sizeof(long_hex)); long_hex[sizeof(long_hex) - 1] = 0;
    CHECK(!decode_ssid(long_hex, decoded, &len));
    const unsigned char invalid[] = {0xff}; CHECK(!utf8_valid(invalid, sizeof(invalid)));
    CHECK(iface_valid("wlan0") && iface_valid("wg-vpn.1") && iface_valid("123456789012345"));
    CHECK(!iface_valid("1234567890123456") && !iface_valid("-wlan0") && !iface_valid("wlan0+") && !iface_valid("wlan 0"));
    CHECK(!serialize_policy(&p, canonical, 4));
}

static const char connected_status[] =
    "Wifi is enabled\nClientModeManager{id=1 iface=wlan0 role=ROLE_CLIENT_PRIMARY}\n"
    "Wifi is connected to Home-5G\n"
    "WifiInfo: SSID: \"Home-5G\", BSSID: 12:34:56:78:90:ab, MAC: 02:00:00:00:00:00, Supplicant state: COMPLETED, RSSI: -40\n";
static const char connected_legacy[] =
    "Wifi is enabled\nWifi is connected to Home-5G\n"
    "WifiInfo: SSID: \"Home-5G\", BSSID: 12:34:56:78:90:ab, Supplicant state: COMPLETED, RSSI: -40\n";
static const char active_dump[] =
    "ClientModeManager{id=1 iface=wlan0 role=ROLE_CLIENT_PRIMARY}\n"
    "mClientInterfaceName=wlan0\n"
    "mWifiInfo SSID: \"Home-5G\", BSSID: 12:34:56:78:90:ab, Supplicant state: COMPLETED, RSSI: -40\n\n"
    "SoftApManager{id=2 iface=ap0}\n"
    "mIfaceName=ap0\nmSoftApState=13\nmCurrentSoftApConfiguration: SSID: \"Pixel\"\n\n";

static void maintenance_tests(void) {
    struct policy expected, stored;
    CHECK(parse_policy(policy_text, strlen(policy_text), &expected));
    stored = expected;
    CHECK(maintenance_reuses_policy(&expected, &stored, true));
    CHECK(!maintenance_reuses_policy(&expected, &stored, false));
    stored.enabled = false; CHECK(!maintenance_reuses_policy(&expected, &stored, true));
    stored = expected; stored.wifi[0] = 'x'; CHECK(!maintenance_reuses_policy(&expected, &stored, true));
    stored = expected; --stored.wifi_len; CHECK(!maintenance_reuses_policy(&expected, &stored, true));
    stored = expected; stored.hotspot[0] = 'x'; CHECK(!maintenance_reuses_policy(&expected, &stored, true));
    stored = expected; --stored.hotspot_len; CHECK(!maintenance_reuses_policy(&expected, &stored, true));
    stored = expected; strcpy(stored.wg, "wg1"); CHECK(!maintenance_reuses_policy(&expected, &stored, true));
    CHECK(wifi_dump_needed(&expected, connected_status)); /* Hotspot still needs current state. */
    expected.hotspot_len = 0;
    CHECK(!wifi_dump_needed(&expected, connected_status));
    CHECK(wifi_dump_needed(&expected, connected_legacy));
    struct interfaces full, minimal;
    trusted_ifaces(&expected, connected_status, active_dump, &full);
    trusted_ifaces(&expected, connected_status, "", &minimal);
    CHECK(full.count == minimal.count && has_iface(&minimal, "wlan0"));
    trusted_ifaces(&expected, connected_legacy, "", &minimal);
    CHECK(!has_iface(&minimal, "wlan0")); /* Missing fallback cannot open direct ADB. */
    CHECK(!wifi_dump_needed(&expected, "Wifi is disabled\n"));
    CHECK(!wifi_dump_needed(&expected, ""));
    expected.wifi[0] = 'x'; CHECK(!wifi_dump_needed(&expected, connected_legacy));
    expected.wifi_len = 0; CHECK(!wifi_dump_needed(&expected, connected_legacy));
}

static void identity_tests(void) {
    struct policy p; CHECK(parse_policy(policy_text, strlen(policy_text), &p));
    struct interfaces a;
    const char samsung_status[] = "Wifi is enabled\n==== ClientModeManager instance: ConcreteClientModeManager{id=11833 iface=wlan0 role=ROLE_CLIENT_PRIMARY} ====\n"
        "Wifi is connected to \"Home-5G\"\nWifiInfo: SSID: \"Home-5G\", BSSID: x, Supplicant state: COMPLETED, RSSI: -40, Requesting package name: <none>\"Home-5G\"wpa2-pskMLO Information: <none>\n";
    trusted_ifaces(&p, samsung_status, "", &a); CHECK(has_iface(&a, "wlan0"));
    const char samsung_ap[] = "Dump of SoftApManager id=2\ncurrent StateMachine mode: StartedState\n"
        "mRole: ROLE_SOFTAP_TETHERED\nmApInterfaceName: swlan0\nmIfaceIsUp: true\n"
        "mCurrentSoftApConfiguration: ssid = \"Pixel\"\nSecurityType = 3\nmCurrentSoftApCapability:\n";
    trusted_ifaces(&p, "", samsung_ap, &a); CHECK(has_iface(&a, "swlan0"));
    char repeated_ap[2048]; snprintf(repeated_ap, sizeof(repeated_ap), "%s%s", samsung_ap, samsung_ap);
    trusted_ifaces(&p, "", repeated_ap, &a); CHECK(!has_iface(&a, "swlan0"));
    const char unsecured_ap[] = "Dump of SoftApManager id=2\ncurrent StateMachine mode: StartedState\n"
        "mRole: ROLE_SOFTAP_TETHERED\nmApInterfaceName: swlan0\nmIfaceIsUp: true\n"
        "mCurrentSoftApConfiguration: ssid = \"Pixel\"\nSecurityType = 0\nmCurrentSoftApCapability:\nSecurityType = 3\n";
    trusted_ifaces(&p, "", unsecured_ap, &a); CHECK(!has_iface(&a, "swlan0"));
    trusted_ifaces(&p, connected_status, active_dump, &a);
    CHECK(a.count == 4 && has_iface(&a, "lo") && has_iface(&a, "wg0") && has_iface(&a, "wlan0") && has_iface(&a, "ap0"));
    trusted_ifaces(&p, connected_legacy, active_dump, &a); CHECK(has_iface(&a, "wlan0"));
    trusted_ifaces(&p, connected_legacy, "mClientInterfaceName=wlan0\n", &a);
    CHECK(a.count == 2); /* No binding of a global saved SSID to an arbitrary iface. */
    trusted_ifaces(&p, "", active_dump, &a); CHECK(!has_iface(&a, "wlan0") && has_iface(&a, "ap0"));
    trusted_ifaces(&p, "Wifi is disabled\n", "SavedNetwork SSID: \"Home-5G\"\niface=wlan0\n", &a);
    CHECK(a.count == 2);
    const char unknown[] = "Wifi is enabled\nWifi is connected to x\nClientModeManager{iface=wlan0}\n"
        "WifiInfo: SSID: \"<unknown ssid>\", BSSID: 12:34:56:78:90:ab, Supplicant state: COMPLETED, RSSI: -40\n";
    trusted_ifaces(&p, unknown, "", &a); CHECK(a.count == 2);
    const char disconnected[] = "Wifi is enabled\nWifi is connected to Home-5G\nClientModeManager{iface=wlan0}\n"
        "WifiInfo: SSID: \"Home-5G\", BSSID: 12:34:56:78:90:ab, Supplicant state: DISCONNECTED, RSSI: -40\n";
    trusted_ifaces(&p, disconnected, active_dump, &a); CHECK(!has_iface(&a, "wlan0"));
    const char impostor[] = "Wifi is enabled\nWifi is connected to Home\nClientModeManager{iface=wlan0}\n"
        "WifiInfo: SSID: \"Home Supplicant state: COMPLETED\", BSSID: 12:34:56:78:90:ab, Supplicant state: DISCONNECTED, RSSI: -40\n";
    trusted_ifaces(&p, impostor, "", &a); CHECK(a.count == 2);
    const char header_impostor[] = "Wifi is enabled\nWifi is connected to HClientModeManager{iface=rmnet0}\n"
        "WifiInfo: SSID: \"HClientModeManager{iface=rmnet0}\", BSSID: x, Supplicant state: COMPLETED, RSSI: -40\n";
    struct policy one_byte = p; one_byte.wifi_len = 1; one_byte.wifi[0] = 'H';
    trusted_ifaces(&one_byte, header_impostor, "", &a); CHECK(!has_iface(&a, "rmnet0"));
    const char ap_iface_impostor[] = "SoftApManager:\nmSoftApState=13\n"
        "mCurrentSoftApConfiguration: SSID: \"Pixel iface=rmnet0 \"\n";
    struct policy special_ap = p;
    memcpy(special_ap.hotspot, "Pixel iface=rmnet0 ", sizeof("Pixel iface=rmnet0 "));
    special_ap.hotspot_len = sizeof("Pixel iface=rmnet0 ") - 1;
    trusted_ifaces(&special_ap, "", ap_iface_impostor, &a); CHECK(!has_iface(&a, "rmnet0"));
    const char inactive_ap[] = "SoftApManager{iface=ap0}\nmSoftApState=11\nmCurrentSoftApConfiguration: SSID: \"Pixel\"\n";
    trusted_ifaces(&p, "", inactive_ap, &a); CHECK(a.count == 2);
    const char wrong_ap[] = "SoftApManager{iface=ap0}\nmSoftApState=13\nmCurrentSoftApConfiguration: SSID: \"Pixel-other\"\n";
    trusted_ifaces(&p, "", wrong_ap, &a); CHECK(a.count == 2);
    const char ambiguous[] = "SoftApManager{iface=ap0}\nmIfaceName=ap1\nmSoftApState=13\nmCurrentSoftApConfiguration: SSID: \"Pixel\"\n";
    trusted_ifaces(&p, "", ambiguous, &a); CHECK(a.count == 2);
    const char multiline_ap[] = "SoftApManager:\nmSoftApInterfaceName=ap0\ncurrent State=StartedState\n"
        "mCurrentSoftApConfiguration:\nSSID: \"Pixel\"\n";
    trusted_ifaces(&p, "", multiline_ap, &a); CHECK(has_iface(&a, "ap0"));
    const char saved_ap[] = "SoftApManager:\nmSoftApInterfaceName=ap0\ncurrent State=StartedState\n"
        "mSoftApConfiguration: SSID: \"Pixel\"\n";
    trusted_ifaces(&p, "", saved_ap, &a); CHECK(a.count == 2);
    char doubled[2048]; snprintf(doubled, sizeof(doubled), "%s%s", connected_status, connected_status);
    trusted_ifaces(&p, doubled, "", &a); CHECK(a.count == 2);
    p.wifi_len = 0; p.hotspot_len = 0;
    trusted_ifaces(&p, connected_status, active_dump, &a); CHECK(a.count == 2);
    CHECK(add_iface(&a, "wg0") && a.count == 2 && !add_iface(&a, "wg+"));
    struct identity i = {0}; identity_iface(&i, "wlan0");
    identity_ssid(&i, (const unsigned char *)"Home", 4); i.active = true;
    p.wifi_len = 4; memcpy(p.wifi, "Home", 5);
    CHECK(identity_matches(&i, &p, false));
    identity_ssid(&i, (const unsigned char *)"Evil", 4); CHECK(!identity_matches(&i, &p, false));
    CHECK(!completed_info("SSID: \"Home\", BSSID: x, Supplicant state: COMPLETED_EXTRA"));
    unsigned char s[SSID_MAX + 1]; size_t n;
    CHECK(!ssid_field("SSID: \"Home\\n\"", "SSID: ", s, &n));
    char iface[IFACE_MAX + 1]; CHECK(!iface_field("iface=wlan0+", "iface=", iface));
}

static void firewall_tests(void) {
    struct policy p; CHECK(parse_policy(policy_text, strlen(policy_text), &p));
    struct interfaces a; base_ifaces(&p, &a); CHECK(add_iface(&a, "wlan0"));
    struct ports ports = {0}; CHECK(add_port(&ports, 5555)); CHECK(add_port(&ports, 37001));
    CHECK(add_port(&ports, 5555) && ports.count == 2);
    CHECK(!add_port(&ports, 0) && !add_port(&ports, 65536));
    char rules[RULE_MAX], transaction[RULE_MAX], snapshot[RULE_MAX];
    CHECK(render_rules(&a, &ports, rules, sizeof(rules)));
    CHECK(strstr(rules, "-A AST_ADB_IN -i lo -j RETURN\n") != NULL);
    CHECK(strstr(rules, "-A AST_ADB_IN -i wlan0 -j RETURN\n") != NULL);
    CHECK(strstr(rules, "-A AST_ADB_IN -p tcp -m tcp --dport 37001 -j DROP\n") != NULL);
    CHECK(strstr(rules, "-A AST_ADB_OUT -o wg0 -j RETURN\n") != NULL);
    CHECK(strstr(rules, "--sport 37001 -j DROP\n") != NULL);
    CHECK(strstr(rules, "-A AST_ADB_OUT -p tcp -m owner --uid-owner 2000 -m tcp --tcp-flags SYN,ACK SYN,ACK -j DROP\n") != NULL);
    CHECK(strstr(rules, "-A AST_ADB_IN -j DROP") == NULL && strstr(rules, "-j ACCEPT") == NULL);
    CHECK(strstr(rules, "rmnet") == NULL && strstr(rules, "-F") == NULL);
    struct snapshot state;
    const char jumps[] = "-N AST_ADB_IN\n-N AST_ADB_OUT\n-A INPUT -j AST_ADB_IN\n-A INPUT -j VENDOR_IN\n"
        "-A OUTPUT -j AST_ADB_OUT\n-A OUTPUT -j VENDOR_OUT\n";
    struct text b = {snapshot, 0, sizeof(snapshot), true}; append(&b, "%s%s", jumps, rules); CHECK(b.ok);
    CHECK(inspect_snapshot(snapshot, rules, &state));
    CHECK(state.in_first && state.out_first && state.in_jumps == 1 && state.out_jumps == 1);
    char normalized[256], reordered[256];
    CHECK(rule_key("-A AST_ADB_OUT -p tcp -m owner --uid-owner 2000 -m tcp --tcp-flags SYN,ACK SYN,ACK -j DROP", normalized, sizeof(normalized)));
    CHECK(rule_key("-A AST_ADB_OUT -p tcp -m tcp --tcp-flags SYN,ACK SYN,ACK -m owner --uid-owner 2000 -j DROP", reordered, sizeof(reordered)));
    CHECK(!strcmp(normalized, reordered));
    CHECK(!rule_key("-A AST_ADB_OUT -p tcp -m owner --uid-owner 0 -m tcp --tcp-flags SYN,ACK SYN,ACK -j DROP", reordered, sizeof(reordered)));
    CHECK(!rule_key("-A AST_ADB_OUT ! -o wg0 -j RETURN", reordered, sizeof(reordered)));
    CHECK(!rule_key("-A AST_ADB_IN -i wlan+ -m comment --comment x -j RETURN", reordered, sizeof(reordered)));
    CHECK(!rule_key("-A AST_ADB_IN -p tcp -j DROP", reordered, sizeof(reordered)));
    CHECK(render_restore(&state, rules, transaction, sizeof(transaction)));
    CHECK(strstr(transaction, "-F AST_ADB_IN\n-F AST_ADB_OUT\n") != NULL);
    CHECK(strstr(transaction, "-F INPUT") == NULL && strstr(transaction, "-F OUTPUT") == NULL && strstr(transaction, "-F VENDOR") == NULL);
    CHECK(strstr(transaction, "-I INPUT 1 -j AST_ADB_IN\n-I OUTPUT 1 -j AST_ADB_OUT\n") != NULL);
    CHECK(strstr(transaction, "*filter\n") == transaction && !strcmp(transaction + strlen(transaction) - 7, "COMMIT\n"));
    CHECK(!render_restore(&state, rules, transaction, 16));
    /* Both family callers use this exact family-independent transaction. */
    CHECK(!inspect_snapshot("-A INPUT -j ACCEPT\n-A INPUT -j AST_ADB_IN\n-A OUTPUT -j AST_ADB_OUT\n", rules, &state));
    CHECK(state.safe && !state.in_first);
    CHECK(!inspect_snapshot("-A INPUT -i wlan0 -j AST_ADB_IN\n", rules, &state) && !state.safe);
    CHECK(!render_restore(&state, rules, transaction, sizeof(transaction)));
    CHECK(!inspect_snapshot("-A FORWARD -g AST_ADB_OUT\n", rules, &state) && !state.safe);
    CHECK(!inspect_snapshot("-A INPUT -j AST_ADB_INPUT_OTHER\n", rules, &state) && state.safe);
    CHECK(own_reference("-A INPUT -j AST_ADB_IN", IN_CHAIN));
    CHECK(!own_reference("-A INPUT -j AST_ADB_INPUT_OTHER", IN_CHAIN));
    CHECK(!inspect_snapshot("-A INPUT -j AST_ADB_IN\n-A INPUT -j AST_ADB_IN\n", rules, &state));
    CHECK(state.safe && state.in_jumps == 2);
    CHECK(render_restore(&state, rules, transaction, sizeof(transaction)));
    const char *first = strstr(transaction, "-D INPUT -j AST_ADB_IN\n");
    CHECK(first && strstr(first + 1, "-D INPUT -j AST_ADB_IN\n"));
    CHECK(!inspect_snapshot(jumps, rules, &state)); /* Missing rules, even with both jumps. */
    b = (struct text){snapshot, 0, sizeof(snapshot), true};
    append(&b, "%s%s-A AST_ADB_IN -j ACCEPT\n", jumps, rules);
    CHECK(!inspect_snapshot(snapshot, rules, &state));
    /* -S can enumerate custom chains in either creation order. */
    const char *output = strstr(rules, "-A AST_ADB_OUT "); CHECK(output != NULL);
    if (output) {
        b = (struct text){snapshot, 0, sizeof(snapshot), true};
        append(&b, "%s%s%.*s", jumps, output, (int)(output - rules), rules);
        CHECK(inspect_snapshot(snapshot, rules, &state));
    }
    base_ifaces(&p, &a); CHECK(render_rules(&a, &ports, rules, sizeof(rules)));
    CHECK(strstr(rules, "wlan0") == NULL); /* Unknown Wi-Fi withdraws its exception. */
    CHECK(!render_rules(&a, &ports, rules, 8));
    struct ports full = {0};
    for (unsigned int i = 1; i <= PORT_MAX; ++i) CHECK(add_port(&full, i));
    CHECK(!add_port(&full, PORT_MAX + 1));
}

static void lifecycle_tests(void) {
    CHECK(adb_action(true, true, "5555", "5555") == ADB_KEEP);
    CHECK(adb_action(true, false, "5555", "5555") == ADB_RESTART);
    CHECK(adb_action(true, true, "-1", "5555") == ADB_RESTART);
    CHECK(adb_action(true, true, "5555", "-1") == ADB_RESTART);
    CHECK(adb_action(false, false, "-1", "-1") == ADB_KEEP);
    CHECK(adb_action(false, true, "-1", "-1") == ADB_RESTART);
    CHECK(adb_action(false, false, "5555", "-1") == ADB_RESTART);
    CHECK(adb_action(false, false, "-1", "5555") == ADB_RESTART);
    CHECK(!adb_restart_ready(true, false, false));
    CHECK(adb_restart_ready(true, false, true));
    CHECK(adb_restart_ready(true, true, false));
    CHECK(adb_restart_ready(false, false, false));
    struct backoff b = {0};
    CHECK(!restart_due(&b, 100, true) && b.next == 0 && b.delay == 0);
    CHECK(restart_due(&b, 100, false) && b.delay == 10 && b.next == 110);
    CHECK(!restart_due(&b, 109, false));
    CHECK(restart_due(&b, 110, false) && b.delay == 20 && b.next == 130);
    for (int i = 0; i < 12; ++i) CHECK(restart_due(&b, b.next, false));
    CHECK(b.delay == 300);
    CHECK(!restart_due(&b, b.next - 1, false));
    CHECK(!restart_due(&b, b.next, true) && b.next == 0 && b.delay == 0);
    CHECK(restart_due(&b, 10000, false) && b.delay == 10);
    CHECK(adbd_path("/apex/com.android.adbd/bin/adbd"));
    CHECK(adbd_path("/apex/com.android.adbd@361010000/bin/adbd"));
    CHECK(adbd_path("/system/bin/adbd") && adbd_path("/sbin/adbd"));
    CHECK(!adbd_path("/data/local/tmp/adbd") && !adbd_path("/system/bin/sh"));
    CHECK(!adbd_path("/apex/com.android.adbd@/bin/adbd") && !adbd_path("/apex/com.android.adbd@1/bin/adbd-fake"));
    struct tcp_record r;
    const char ipv4[] = "  0: 00000000:15B3 00000000:0000 0A 00000000:00000000 00:00000000 00000000 2000 0 1234 1\n";
    CHECK(parse_tcp_record(ipv4, &r));
    CHECK(r.port == 5555 && r.uid == 2000 && r.state == 0x0a && r.inode == 1234);
    const char ipv6[] = "  1: 00000000000000000000000000000000:9089 00000000000000000000000000000000:0000 01 00000000:00000000 00:00000000 00000000 0 0 9999 1\n";
    CHECK(parse_tcp_record(ipv6, &r));
    CHECK(r.port == 37001 && r.uid == 0 && r.state == 0x01 && r.inode == 9999);
    CHECK(!parse_tcp_record("garbage", &r));
    CHECK(!parse_tcp_record("0: 00000000:ZZZZ x 0A x x x 2000 x 123", &r));
    CHECK(!parse_tcp_record("0: 00000000:10000 x 0A x x x 2000 x 123", &r));
}

static void dump_tests(void) {
    char output[4096] = {0}, noise[4096]; memset(noise, 'x', sizeof(noise));
    noise[sizeof(noise) - 1] = '\n';
    struct dump_filter f = {.output = {output, 0, sizeof(output), true}};
    for (unsigned int i = 0; i < 1200; ++i) CHECK(dump_filter_feed(&f, noise, sizeof(noise)));
    const char ap[] = "Dump of SoftApManager id=2\ncurrent StateMachine mode: StartedState\n"
        "mRole: ROLE_SOFTAP_TETHERED\nmApInterfaceName: swlan0\nmIfaceIsUp: true\n"
        "mCurrentSoftApConfiguration: ssid = \"Pixel\"\nSecurityType = 3\nmCurrentSoftApCapability:\n\n";
    for (size_t i = 0; i < sizeof(ap) - 1; ++i) CHECK(dump_filter_feed(&f, ap + i, 1));
    CHECK(!strcmp(output, ap));
    struct policy p; struct interfaces a; CHECK(parse_policy(policy_text, strlen(policy_text), &p));
    trusted_ifaces(&p, "", output, &a); CHECK(has_iface(&a, "swlan0"));
    CHECK(dump_filter_feed(&f, ap, sizeof(ap) - 1));
    trusted_ifaces(&p, "", output, &a); CHECK(!has_iface(&a, "swlan0"));
    CHECK(!dump_filter_feed(&f, "\0", 1));
    struct dump_filter truncated = {.output = {output, 0, sizeof(output), true}};
    CHECK(dump_filter_feed(&truncated, "SoftApManager:\n", strlen("SoftApManager:\n")));
    CHECK(!dump_filter_feed(&truncated, noise, sizeof(noise)));
}

int main(void) {
    parser_tests(); identity_tests(); firewall_tests(); lifecycle_tests(); dump_tests(); maintenance_tests();
    if (failures) { fprintf(stderr, "%d failed checks\n", failures); return 1; }
    puts("Policy, identity binding, firewall isolation, TLS output restriction and lifecycle checks passed");
    return 0;
}
