/*
 * Standalone C11 executable. No JNI, app state, shell command construction or
 * network service. Build this translation unit as a PIE and package the binary
 * as an asset; integration deliberately lives outside this file.
 *
 * CLI (exactly one argument): install/apply consume <=8192 bytes on stdin;
 * guard/check/enable/disable/status/boot/watch consume no policy arguments.
 * All five policy keys are mandatory, unique, order independent. Empty SSIDs
 * disable that exception. Wi-Fi is a byte-prefix match; hotspot is exact.
 * Status is flat key=value; enabled/listener=-1 means unavailable evidence,
 * watcher_pid=0/watcher_version=none means no verified live lock holder.
 * Exit: 0 success, 2 usage/policy, 3 not root, 4 unsafe/storage failure,
 * 5 guard failure (TCP shutdown attempted), 6 property/restart failure,
 * 7 lock busy/timeout, 8 watcher failure. Status exits 5 if not guarded.
 *
 * ROOT_CONTROLLER_TEST removes ALL privileged/runtime code, including main;
 * tools/test-root-controller.c includes only the pure implementation below.
 */
#ifndef _GNU_SOURCE
#define _GNU_SOURCE
#endif
#include <ctype.h>
#include <errno.h>
#include <stdarg.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define RC_VERSION "1.1.0"
#define POLICY_MAX 8192u
#define SSID_MAX 32u
#define IFACE_MAX 15u
#define PORT_MAX 256u
#define ALLOW_MAX 18u
#define TEXT_MAX 262144u
#define RULE_MAX 65536u
#define IN_CHAIN "AST_ADB_IN"
#define OUT_CHAIN "AST_ADB_OUT"

struct policy {
    bool enabled;
    unsigned char wifi[SSID_MAX + 1], hotspot[SSID_MAX + 1];
    size_t wifi_len, hotspot_len;
    char wg[IFACE_MAX + 1];
};
struct interfaces { char name[ALLOW_MAX][IFACE_MAX + 1]; size_t count; };
struct ports { unsigned short value[PORT_MAX]; size_t count; bool listener; };
struct text { char *data; size_t len, cap; bool ok; };

static void append(struct text *b, const char *fmt, ...) {
    if (!b->ok || b->len >= b->cap) { b->ok = false; return; }
    va_list args;
    va_start(args, fmt);
    int n = vsnprintf(b->data + b->len, b->cap - b->len, fmt, args);
    va_end(args);
    if (n < 0 || (size_t)n >= b->cap - b->len) b->ok = false;
    else b->len += (size_t)n;
}

static bool iface_valid(const char *s) {
    size_t n = strlen(s);
    if (!n || n > IFACE_MAX || !isalnum((unsigned char)s[0])) return false;
    for (size_t i = 0; i < n; ++i)
        if (!(s[i] >= 'a' && s[i] <= 'z') && !(s[i] >= 'A' && s[i] <= 'Z') &&
            !(s[i] >= '0' && s[i] <= '9') && s[i] != '_' && s[i] != '-' && s[i] != '.') return false;
    /* '+' is the iptables interface wildcard and is intentionally forbidden. */
    return true;
}

static bool utf8_valid(const unsigned char *s, size_t n) {
    for (size_t i = 0; i < n;) {
        uint32_t c = s[i++], min;
        unsigned int more;
        if (c < 0x80) continue;
        if (c >= 0xc2 && c <= 0xdf) { more = 1; min = 0x80; c &= 0x1f; }
        else if (c >= 0xe0 && c <= 0xef) { more = 2; min = 0x800; c &= 0x0f; }
        else if (c >= 0xf0 && c <= 0xf4) { more = 3; min = 0x10000; c &= 7; }
        else return false;
        if (n - i < more) return false;
        while (more--) {
            if ((s[i] & 0xc0) != 0x80) return false;
            c = (c << 6) | (s[i++] & 0x3f);
        }
        if (c < min || c > 0x10ffff || (c >= 0xd800 && c <= 0xdfff)) return false;
    }
    return true;
}

static int hex_digit(unsigned char c) {
    if (c >= '0' && c <= '9') return c - '0';
    if (c >= 'a' && c <= 'f') return c - 'a' + 10;
    return -1;
}

static bool decode_ssid(const char *s, unsigned char *out, size_t *len) {
    size_t n = strlen(s);
    if (n > SSID_MAX * 2 || (n & 1)) return false;
    for (size_t i = 0; i < n; i += 2) {
        int a = hex_digit((unsigned char)s[i]), b = hex_digit((unsigned char)s[i + 1]);
        if (a < 0 || b < 0) return false;
        out[i / 2] = (unsigned char)((a << 4) | b);
    }
    *len = n / 2;
    out[*len] = 0;
    return utf8_valid(out, *len);
}

static bool parse_policy(const char *bytes, size_t n, struct policy *out) {
    if (!n || n > POLICY_MAX || memchr(bytes, 0, n)) return false;
    char copy[POLICY_MAX + 1];
    memcpy(copy, bytes, n); copy[n] = 0;
    struct policy p = {0};
    unsigned int seen = 0;
    char *line = copy;
    while (*line) {
        char *end = strchr(line, '\n');
        if (end) *end = 0;
        char *eq = strchr(line, '=');
        if (!eq) return false;
        *eq++ = 0;
        unsigned int key;
        if (!strcmp(line, "version")) { key = 1; if (strcmp(eq, "1")) return false; }
        else if (!strcmp(line, "enabled")) {
            key = 2;
            if (strcmp(eq, "0") && strcmp(eq, "1")) return false;
            p.enabled = eq[0] == '1';
        } else if (!strcmp(line, "wifi_prefix")) {
            key = 4; if (!decode_ssid(eq, p.wifi, &p.wifi_len)) return false;
        } else if (!strcmp(line, "hotspot_ssid")) {
            key = 8; if (!decode_ssid(eq, p.hotspot, &p.hotspot_len)) return false;
        } else if (!strcmp(line, "wireguard_iface")) {
            key = 16;
            if (*eq && !iface_valid(eq)) return false;
            memcpy(p.wg, eq, strlen(eq) + 1);
        } else return false;
        if (seen & key) return false;
        seen |= key;
        if (!end) break;
        line = end + 1;
    }
    if (seen != 31) return false;
    *out = p;
    return true;
}

static bool serialize_policy(const struct policy *p, char *out, size_t cap) {
    struct text b = {out, 0, cap, true};
    append(&b, "version=1\nenabled=%d\nwifi_prefix=", p->enabled ? 1 : 0);
    for (size_t i = 0; i < p->wifi_len; ++i) append(&b, "%02x", (unsigned int)p->wifi[i]);
    append(&b, "\nhotspot_ssid=");
    for (size_t i = 0; i < p->hotspot_len; ++i) append(&b, "%02x", (unsigned int)p->hotspot[i]);
    append(&b, "\nwireguard_iface=%s\n", p->wg);
    return b.ok;
}

static bool add_iface(struct interfaces *a, const char *s) {
    if (!iface_valid(s)) return false;
    for (size_t i = 0; i < a->count; ++i) if (!strcmp(a->name[i], s)) return true;
    if (a->count == ALLOW_MAX) return false;
    memcpy(a->name[a->count++], s, strlen(s) + 1);
    return true;
}

static void base_ifaces(const struct policy *p, struct interfaces *a) {
    memset(a, 0, sizeof(*a));
    (void)add_iface(a, "lo");
    if (*p->wg) (void)add_iface(a, p->wg);
}

static bool add_port(struct ports *p, unsigned int port) {
    if (!port || port > 65535) return false;
    size_t at = 0;
    while (at < p->count && p->value[at] < port) ++at;
    if (at < p->count && p->value[at] == port) return true;
    if (p->count == PORT_MAX) return false;
    memmove(p->value + at + 1, p->value + at, (p->count - at) * sizeof(p->value[0]));
    p->value[at] = (unsigned short)port; ++p->count;
    return true;
}

/* Read only a recognized active record. Never search an entire dump for a
 * configured SSID: saved networks and historical log records are untrusted.
 * Ambiguous/escaped/unsupported Android diagnostic layouts fail closed.
 */
static bool ssid_field(const char *line, const char *label, unsigned char *s, size_t *n) {
    const char *v = strstr(line, label);
    if (!v) return false;
    v += strlen(label);
    while (*v == ' ') ++v;
    const char *end;
    if (*v == '"') {
        ++v; end = strchr(v, '"');
        if (!end || (end[1] && end[1] != ',' && end[1] != ' ' && end[1] != '}')) return false;
    } else {
        end = strstr(v, ", BSSID:");
        if (!end) return false;
    }
    *n = (size_t)(end - v);
    if (!*n || *n > SSID_MAX || memchr(v, '\\', *n)) return false;
    for (size_t i = 0; i < *n; ++i) if ((unsigned char)v[i] < 0x20 || v[i] == 0x7f) return false;
    memcpy(s, v, *n); s[*n] = 0;
    return utf8_valid(s, *n) && strcmp((const char *)s, "<unknown ssid>") != 0;
}

static bool iface_field(const char *line, const char *key, char *out) {
    const char *v = strstr(line, key);
    if (!v) return false;
    v += strlen(key);
    size_t n = strcspn(v, " ,}\t\r\n");
    if (!n || n > IFACE_MAX) return false;
    memcpy(out, v, n); out[n] = 0;
    return iface_valid(out);
}

static bool completed_info(const char *line) {
    const char *ssid = strstr(line, "SSID: ");
    if (!ssid) return false;
    ssid += strlen("SSID: ");
    const char *rest;
    if (*ssid == '"') {
        rest = strchr(ssid + 1, '"');
        if (!rest || strncmp(rest + 1, ", BSSID:", 8)) return false;
        ++rest;
    } else rest = strstr(ssid, ", BSSID:");
    if (!rest) return false;
    const char *state = strstr(rest, ", Supplicant state: ");
    if (!state || strstr(state + 1, ", Supplicant state: ")) return false;
    state += strlen(", Supplicant state: ");
    return !strncmp(state, "COMPLETED", 9) && (!state[9] || state[9] == ',');
}

struct identity {
    char iface[IFACE_MAX + 1];
    unsigned char ssid[SSID_MAX + 1];
    size_t len;
    bool active, has_ssid, invalid;
};

static bool identity_matches(const struct identity *i, const struct policy *p, bool ap) {
    size_t n = ap ? p->hotspot_len : p->wifi_len;
    const unsigned char *s = ap ? p->hotspot : p->wifi;
    return !i->invalid && i->active && i->has_ssid && iface_valid(i->iface) && n &&
        (ap ? i->len == n : i->len >= n) && !memcmp(i->ssid, s, n);
}

static void identity_iface(struct identity *i, const char *s) {
    if (*i->iface && strcmp(i->iface, s)) i->invalid = true;
    else memcpy(i->iface, s, strlen(s) + 1);
}

static void identity_ssid(struct identity *i, const unsigned char *s, size_t n) {
    if (i->has_ssid && (i->len != n || memcmp(i->ssid, s, n))) i->invalid = true;
    else { memcpy(i->ssid, s, n + 1); i->len = n; i->has_ssid = true; }
}

/* cmd wifi status may omit iface. Its one completed WifiInfo is corroborated
 * with the same SSID in a completed, explicitly named ClientMode dump block.
 * Modern status with ClientModeManager{... iface=...} needs no fallback.
 * AP exception needs mSoftApState=13 AND current config within SoftApManager.
 */
static void trusted_ifaces(const struct policy *p, const char *status, const char *dump,
                          struct interfaces *allowed) {
    base_ifaces(p, allowed);
    struct identity station = {0}, block = {0};
    bool wifi_enabled = false, connected = false, station_section = false;
    bool ap_section = false, client_section = false, ap_config_next = false;
    bool samsung_ap = false, tethered = false, iface_up = false, secured = false, ap_config = false;
    struct identity ap_candidate = {0}; unsigned int ap_count = 0;
    char line[2048];
    const char *cursor = status;
    while (*cursor) {
        size_t n = strcspn(cursor, "\n");
        if (n >= sizeof(line)) { station.invalid = true; break; }
        memcpy(line, cursor, n); line[n] = 0; cursor += n; if (*cursor) ++cursor;
        const char *s = line; while (*s == ' ') ++s;
        if (!strcmp(s, "Wifi is enabled")) wifi_enabled = true;
        if (!strncmp(s, "Wifi is connected to ", 21)) connected = true;
        const char *header = s;
        while (*header == '=') ++header;
        while (*header == ' ') ++header;
        if (!strncmp(header, "ClientModeManager{", 18) || !strncmp(header, "ClientModeManager instance: ConcreteClientModeManager{", 54)) {
            if (station_section || station.has_ssid) station.invalid = true;
            station_section = true;
            char iface[IFACE_MAX + 1];
            if (iface_field(header, "iface=", iface)) identity_iface(&station, iface);
        }
        if (!strncmp(s, "WifiInfo: ", 10)) {
            unsigned char ssid[SSID_MAX + 1]; size_t size;
            if (ssid_field(s, "SSID: ", ssid, &size)) identity_ssid(&station, ssid, size);
            else station.invalid = true;
            station.active = completed_info(s);
        }
    }
    station.active = station.active && wifi_enabled && connected;
    if (identity_matches(&station, p, false)) (void)add_iface(allowed, station.iface);
    cursor = dump;
    for (;;) {
        size_t n = strcspn(cursor, "\n");
        if (n >= sizeof(line)) { block.invalid = true; break; }
        memcpy(line, cursor, n); line[n] = 0;
        const char *s = line; while (*s == ' ') ++s;
        bool new_ap = !strncmp(s, "SoftApManager{", 14) || !strcmp(s, "Dump of SoftApManager") || !strncmp(s, "Dump of SoftApManager id=", 25) || !strcmp(s, "SoftApManager:");
        bool new_client = !strncmp(s, "ClientModeManager{", 18) || !strcmp(s, "Dump of ClientModeManager") || !strcmp(s, "ClientModeManager:");
        /* A blank line ends a block: deny on layouts with split diagnostics. */
        if (!*cursor || !*s || new_ap || new_client) {
            if (ap_section && identity_matches(&block, p, true) &&
                (!samsung_ap || (tethered && iface_up && secured))) ap_candidate = block;
            if (client_section && !*station.iface && !station.invalid && station.active && station.has_ssid &&
                block.has_ssid && block.len == station.len && !memcmp(block.ssid, station.ssid, block.len) &&
                identity_matches(&block, p, false)) (void)add_iface(allowed, block.iface);
            memset(&block, 0, sizeof(block));
            ap_section = new_ap; client_section = new_client; ap_config_next = false;
            samsung_ap = new_ap && !strncmp(s, "Dump of SoftApManager id=", 25);
            tethered = iface_up = secured = ap_config = false;
            if (new_ap) ++ap_count;
        }
        if (ap_section || client_section) {
            char iface[IFACE_MAX + 1];
            const char *iface_key = NULL;
            if (new_ap || new_client) iface_key = "iface=";
            else if (ap_section && !strncmp(s, "mIfaceName=", 11)) iface_key = "mIfaceName=";
            else if (ap_section && !strncmp(s, "mSoftApInterfaceName=", 21)) iface_key = "mSoftApInterfaceName=";
            else if (ap_section && !strncmp(s, "mApInterfaceName: ", 18)) iface_key = "mApInterfaceName: ";
            else if (client_section && !strncmp(s, "mClientInterfaceName=", 21)) iface_key = "mClientInterfaceName=";
            else if (!strncmp(s, "mInterfaceName=", 15)) iface_key = "mInterfaceName=";
            if (iface_key && iface_field(s, iface_key, iface)) identity_iface(&block, iface);
            if (ap_section && (!strcmp(s, "mSoftApState=13") || !strcmp(s, "current State=StartedState") ||
                !strcmp(s, "current StateMachine mode: StartedState"))) block.active = true;
            if (ap_section && !strcmp(s, "mRole: ROLE_SOFTAP_TETHERED")) tethered = true;
            if (ap_section && !strcmp(s, "mIfaceIsUp: true")) iface_up = true;
            if (ap_section && ap_config && !strcmp(s, "SecurityType = 3")) secured = true;
            if (ap_section && !strncmp(s, "mCurrentSoftApCapability", 23)) ap_config = false;
            unsigned char ssid[SSID_MAX + 1]; size_t size;
            if (ap_section && ap_config_next) {
                if ((!strncmp(s, "SSID: ", 6) || !strncmp(s, "ssid: ", 6)) &&
                    (ssid_field(s, "SSID: ", ssid, &size) || ssid_field(s, "ssid: ", ssid, &size)))
                    identity_ssid(&block, ssid, size);
                else block.invalid = true;
                ap_config_next = false;
            }
            if (ap_section && !strncmp(s, "mCurrentSoftApConfiguration", 27)) {
                ap_config = true;
                if (ssid_field(s, "SSID: ", ssid, &size) || ssid_field(s, "ssid=", ssid, &size) ||
                    ssid_field(s, "ssid: ", ssid, &size) || ssid_field(s, "ssid = ", ssid, &size))
                    identity_ssid(&block, ssid, size);
                else if (!strcmp(s + 27, ":") || !strcmp(s + 27, "=")) ap_config_next = true;
                else block.invalid = true;
            }
            if (client_section && (!strncmp(s, "mWifiInfo ", 10) || !strncmp(s, "WifiInfo: ", 10))) {
                if (ssid_field(s, "SSID: ", ssid, &size)) identity_ssid(&block, ssid, size);
                else block.invalid = true;
                block.active = completed_info(s);
            }
        }
        if (!*cursor) break;
        cursor += n; if (*cursor) ++cursor;
    }
    if (ap_count == 1 && identity_matches(&ap_candidate, p, true)) (void)add_iface(allowed, ap_candidate.iface);
}

static bool render_rules(const struct interfaces *a, const struct ports *ports,
                         char *out, size_t cap) {
    struct text b = {out, 0, cap, true};
    for (size_t i = 0; i < a->count; ++i) {
        if (!iface_valid(a->name[i])) return false;
        append(&b, "-A " IN_CHAIN " -i %s -j RETURN\n", a->name[i]);
    }
    for (size_t i = 0; i < ports->count; ++i)
        append(&b, "-A " IN_CHAIN " -p tcp -m tcp --dport %u -j DROP\n", (unsigned int)ports->value[i]);
    for (size_t i = 0; i < a->count; ++i)
        append(&b, "-A " OUT_CHAIN " -o %s -j RETURN\n", a->name[i]);
    for (size_t i = 0; i < ports->count; ++i)
        append(&b, "-A " OUT_CHAIN " -p tcp -m tcp --sport %u -j DROP\n", (unsigned int)ports->value[i]);
    /* This rule catches newly created wireless TLS listeners before /proc scan. */
    append(&b, "-A " OUT_CHAIN " -p tcp -m owner --uid-owner 2000 -m tcp --tcp-flags SYN,ACK SYN,ACK -j DROP\n");
    return b.ok;
}

/* Normalize -S/-save output without depending on extension print ordering.
 * Any additional condition, negation, counter, ACCEPT or extra chain rule
 * makes verification fail. No general firewall parser or shell interpreter.
 */
static bool rule_key(const char *line, char *out, size_t cap) {
    char copy[2048];
    if (strlen(line) >= sizeof(copy)) return false;
    strcpy(copy, line);
    char *save = NULL, *token = strtok_r(copy, " \t", &save);
    if (!token || strcmp(token, "-A")) return false;
    char *chain = strtok_r(NULL, " \t", &save);
    if (!chain) return false;
    const char *iface = "", *direction = "", *port = "", *port_dir = "", *target = "";
    bool tcp = false, owner = false, flags = false, tcp_module = false, owner_module = false;
    while ((token = strtok_r(NULL, " \t", &save))) {
        char *v = strtok_r(NULL, " \t", &save);
        if (!v) return false;
        if (!strcmp(token, "-j") && !*target) target = v;
        else if ((!strcmp(token, "-i") || !strcmp(token, "-o")) && !*iface) {
            iface = v; direction = token;
        } else if (!strcmp(token, "-p") && !tcp && !strcmp(v, "tcp")) tcp = true;
        else if (!strcmp(token, "-m") && !strcmp(v, "tcp") && !tcp_module) tcp_module = true;
        else if (!strcmp(token, "-m") && !strcmp(v, "owner") && !owner_module) owner_module = true;
        else if ((!strcmp(token, "--sport") || !strcmp(token, "--dport")) && !*port) {
            port = v; port_dir = token;
        } else if (!strcmp(token, "--uid-owner") && !owner && !strcmp(v, "2000")) owner = true;
        else if (!strcmp(token, "--tcp-flags") && !flags && !strcmp(v, "SYN,ACK")) {
            char *mask = strtok_r(NULL, " \t", &save);
            if (!mask || strcmp(mask, "SYN,ACK")) return false;
            flags = true;
        } else return false;
    }
    if (!*target || tcp != tcp_module || owner != owner_module || flags != owner) return false;
    int n = snprintf(out, cap, "%s|%s|%s|%d|%s|%s|%d|%d|%s", chain, direction, iface,
                     tcp ? 1 : 0, port_dir, port, owner ? 1 : 0, flags ? 1 : 0, target);
    return n >= 0 && (size_t)n < cap;
}

struct snapshot { unsigned int in_jumps, out_jumps; bool in_first, out_first, safe; };

static bool own_reference(const char *line, const char *target) {
    size_t n = strlen(target);
    const char *at = line;
    while ((at = strstr(at, target))) {
        if (at >= line + 3 && at[-3] == '-' && (at[-2] == 'j' || at[-2] == 'g') && at[-1] == ' ' &&
            (!at[n] || at[n] == ' ' || at[n] == '\t')) return true;
        ++at;
    }
    return false;
}

/* Only exact, unconditional jumps may be replaced. Conditional references to
 * our chains are refused rather than deleting somebody else's firewall rule.
 */
static bool inspect_snapshot(const char *s, const char *rules, struct snapshot *state) {
    *state = (struct snapshot){0, 0, false, false, true};
    bool saw_in = false, saw_out = false, valid = true;
    const char *expected_in = rules;
    const char *out_start = strstr(rules, "-A " OUT_CHAIN " ");
    const char *expected_out = out_start ? out_start : rules + strlen(rules);
    while (*s) {
        size_t n = strcspn(s, "\n");
        char line[2048];
        if (n >= sizeof(line)) { state->safe = false; return false; }
        memcpy(line, s, n); line[n] = 0;
        if (!strncmp(line, "-A INPUT ", 9)) {
            bool jump = !strcmp(line, "-A INPUT -j " IN_CHAIN);
            if (!saw_in) state->in_first = jump;
            saw_in = true; if (jump) ++state->in_jumps;
        }
        if (!strncmp(line, "-A OUTPUT ", 10)) {
            bool jump = !strcmp(line, "-A OUTPUT -j " OUT_CHAIN);
            if (!saw_out) state->out_first = jump;
            saw_out = true; if (jump) ++state->out_jumps;
        }
        bool own = !strncmp(line, "-A " IN_CHAIN " ", sizeof("-A " IN_CHAIN " ") - 1) ||
                   !strncmp(line, "-A " OUT_CHAIN " ", sizeof("-A " OUT_CHAIN " ") - 1);
        if (own) {
            bool input = !strncmp(line, "-A " IN_CHAIN " ", sizeof("-A " IN_CHAIN " ") - 1);
            const char *expected = input ? expected_in : expected_out;
            char want[2048], actual_key[2048], want_key[2048];
            size_t m = strcspn(expected, "\n");
            if (!m || m >= sizeof(want) || (input && expected == out_start)) valid = false;
            else {
                memcpy(want, expected, m); want[m] = 0;
                if (!rule_key(line, actual_key, sizeof(actual_key)) ||
                    !rule_key(want, want_key, sizeof(want_key)) || strcmp(actual_key, want_key)) valid = false;
                expected += m; if (*expected) ++expected;
                if (input) expected_in = expected; else expected_out = expected;
            }
        } else if (own_reference(line, IN_CHAIN) || own_reference(line, OUT_CHAIN)) {
            if (strcmp(line, "-A INPUT -j " IN_CHAIN) && strcmp(line, "-A OUTPUT -j " OUT_CHAIN))
                state->safe = false;
        }
        s += n; if (*s) ++s;
    }
    return valid && expected_in == out_start && !*expected_out && state->safe && state->in_first && state->out_first &&
           state->in_jumps == 1 && state->out_jumps == 1;
}

static bool render_restore(const struct snapshot *s, const char *rules, char *out, size_t cap) {
    if (!s->safe || s->in_jumps > 1024 || s->out_jumps > 1024) return false;
    struct text b = {out, 0, cap, true};
    append(&b, "*filter\n:" IN_CHAIN " - [0:0]\n:" OUT_CHAIN " - [0:0]\n-F " IN_CHAIN "\n-F " OUT_CHAIN "\n");
    for (unsigned int i = 0; i < s->in_jumps; ++i) append(&b, "-D INPUT -j " IN_CHAIN "\n");
    for (unsigned int i = 0; i < s->out_jumps; ++i) append(&b, "-D OUTPUT -j " OUT_CHAIN "\n");
    append(&b, "-I INPUT 1 -j " IN_CHAIN "\n-I OUTPUT 1 -j " OUT_CHAIN "\n%sCOMMIT\n", rules);
    return b.ok;
}

enum adb_action { ADB_KEEP, ADB_RESTART };
static enum adb_action adb_action(bool enabled, bool listener, const char *service, const char *persist) {
    if (enabled) return listener && !strcmp(service, "5555") && !strcmp(persist, "5555") ? ADB_KEEP : ADB_RESTART;
    return !listener && !strcmp(service, "-1") && !strcmp(persist, "-1") ? ADB_KEEP : ADB_RESTART;
}

struct backoff { uint64_t next; unsigned int delay; };
static bool restart_due(struct backoff *b, uint64_t now, bool healthy) {
    if (healthy) { b->delay = 0; b->next = 0; return false; }
    if (now < b->next) return false;
    b->delay = b->delay ? (b->delay >= 150 ? 300 : b->delay * 2) : 10;
    b->next = now + b->delay;
    return true;
}

static bool adbd_path(const char *path) {
    if (!strcmp(path, "/system/bin/adbd") || !strcmp(path, "/sbin/adbd") ||
        !strcmp(path, "/apex/com.android.adbd/bin/adbd")) return true;
    const char prefix[] = "/apex/com.android.adbd@";
    if (strncmp(path, prefix, sizeof(prefix) - 1)) return false;
    const char *p = path + sizeof(prefix) - 1;
    const char *start = p;
    while (*p >= '0' && *p <= '9') ++p;
    return p != start && !strcmp(p, "/bin/adbd");
}

struct tcp_record { unsigned int port, uid, state; unsigned long long inode; };
static bool parse_tcp_record(const char *line, struct tcp_record *r) {
    char local[80], remote[80];
    if (sscanf(line, "%*s %79s %79s %x %*s %*s %*s %u %*s %llu", local, remote,
               &r->state, &r->uid, &r->inode) != 5) return false;
    const char *colon = strrchr(local, ':');
    if (!colon || strlen(colon + 1) != 4) return false;
    for (const char *s = colon + 1; *s; ++s) if (!isxdigit((unsigned char)*s)) return false;
    char *end; unsigned long port = strtoul(colon + 1, &end, 16);
    if (*end || !port || port > 65535) return false;
    r->port = (unsigned int)port;
    return true;
}

#ifndef ROOT_CONTROLLER_TEST
#include <dirent.h>
#include <fcntl.h>
#include <linux/netlink.h>
#include <linux/rtnetlink.h>
#include <poll.h>
#include <signal.h>
#include <sys/file.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

#define ROOT_PATH "/data/adb/adb-ssh-tunnel"
#define EXEC_PATH ROOT_PATH "/root-controller"
#define IO_TIMEOUT 5000u
#define LOCK_TIMEOUT 12000u
#define WATCH_INTERVAL 30u
#define SOCKET_MAX 4096u
enum exits { RC_OK, RC_POLICY = 2, RC_ROOT = 3, RC_STORAGE = 4, RC_GUARD = 5,
             RC_ADB = 6, RC_LOCK = 7, RC_WATCH = 8 };
static volatile sig_atomic_t stopping;
static volatile sig_atomic_t upgrading;
static void stop_signal(int signum) { (void)signum; stopping = 1; }
static void upgrade_signal(int signum) { (void)signum; upgrading = 1; stopping = 1; }

static uint64_t clock_ms(void) {
    struct timespec t;
    if (clock_gettime(CLOCK_MONOTONIC, &t)) return 0;
    return (uint64_t)t.tv_sec * 1000 + (uint64_t)t.tv_nsec / 1000000;
}

static void log_failure(const char *what) {
    /* Never log subprocess output, SSIDs, stdin, environment or arbitrary data. */
    fprintf(stderr, "root-controller: %s\n", what);
}

static bool ensure_stdio(void) {
    for (int fd = 0; fd < 3; ++fd) {
        if (fcntl(fd, F_GETFD) >= 0) continue;
        if (errno != EBADF) return false;
        int null_fd = open("/dev/null", O_RDWR | O_NOFOLLOW | O_CLOEXEC);
        if (null_fd < 0) return false;
        if (null_fd != fd) {
            bool ok = dup2(null_fd, fd) >= 0; close(null_fd);
            if (!ok) return false;
        }
        if (fcntl(fd, F_SETFD, 0)) return false;
    }
    return true;
}

static bool write_all(int fd, const void *buffer, size_t n) {
    const unsigned char *p = buffer;
    while (n) {
        ssize_t count = write(fd, p, n);
        if (count < 0 && errno == EINTR) continue;
        if (count <= 0) return false;
        p += count; n -= (size_t)count;
    }
    return true;
}

/* Only callers below supply argv; PATH lookup and shell interpretation never
 * occur. Drain stdout/stderr while supplying input, with a single deadline.
 * Oversized/truncated diagnostics are failures, not partially trusted state.
 */
static bool command(const char *const argv[], const char *input, char *out, size_t cap) {
    int in_pipe[2], out_pipe[2];
    if (!cap || pipe2(in_pipe, O_CLOEXEC)) return false;
    if (pipe2(out_pipe, O_CLOEXEC)) { close(in_pipe[0]); close(in_pipe[1]); return false; }
    pid_t child = fork();
    if (child < 0) {
        close(in_pipe[0]); close(in_pipe[1]); close(out_pipe[0]); close(out_pipe[1]); return false;
    }
    if (!child) {
        (void)setpgid(0, 0);
        signal(SIGPIPE, SIG_DFL); signal(SIGTERM, SIG_DFL); signal(SIGINT, SIG_DFL);
        if (dup2(in_pipe[0], STDIN_FILENO) < 0 || dup2(out_pipe[1], STDOUT_FILENO) < 0 ||
            dup2(out_pipe[1], STDERR_FILENO) < 0) _exit(126);
        close(in_pipe[0]); close(in_pipe[1]); close(out_pipe[0]); close(out_pipe[1]);
        char *const environment[] = {"PATH=/system/bin", "LANG=C", "LC_ALL=C", NULL};
        execve(argv[0], (char *const *)argv, environment);
        _exit(127);
    }
    (void)setpgid(child, child);
    close(in_pipe[0]); close(out_pipe[1]);
    int write_fd = in_pipe[1], read_fd = out_pipe[0];
    bool ok = fcntl(write_fd, F_SETFL, O_NONBLOCK) == 0 && fcntl(read_fd, F_SETFL, O_NONBLOCK) == 0;
    size_t sent = 0, length = input ? strlen(input) : 0, used = 0;
    uint64_t deadline = clock_ms() + IO_TIMEOUT;
    int status = 0; bool reaped = false;
    out[0] = 0;
    while (ok && (!reaped || read_fd >= 0)) {
        if (write_fd >= 0 && sent == length) { close(write_fd); write_fd = -1; }
        if (clock_ms() >= deadline || stopping) { ok = false; break; }
        struct pollfd fds[2] = {{read_fd, POLLIN, 0}, {write_fd, POLLOUT, 0}};
        int result = poll(fds, 2, 50);
        if (result < 0 && errno != EINTR) { ok = false; break; }
        if (read_fd >= 0 && fds[0].revents) {
            char buf[4096];
            ssize_t n = read(read_fd, buf, sizeof(buf));
            if (n > 0) {
                if ((size_t)n >= cap - used || memchr(buf, 0, (size_t)n)) { ok = false; break; }
                memcpy(out + used, buf, (size_t)n); used += (size_t)n; out[used] = 0;
            } else if (!n) { close(read_fd); read_fd = -1; }
            else if (errno != EAGAIN && errno != EINTR) ok = false;
        }
        if (write_fd >= 0 && fds[1].revents) {
            ssize_t n = write(write_fd, input + sent, length - sent);
            if (n > 0) sent += (size_t)n;
            else if (n < 0 && errno != EAGAIN && errno != EINTR) ok = false;
        }
        /* Preserve the PID until inherited output pipes close. A timeout must
         * never kill a recycled PID after reaping the command leader. */
        if (!reaped && read_fd < 0) {
            pid_t r = waitpid(child, &status, WNOHANG);
            if (r == child) reaped = true;
            else if (r < 0 && errno != EINTR) ok = false;
        }
    }
    if (write_fd >= 0) close(write_fd);
    if (read_fd >= 0) close(read_fd);
    if (!ok) { (void)kill(-child, SIGKILL); (void)kill(child, SIGKILL); }
    if (!reaped) while (waitpid(child, &status, 0) < 0 && errno == EINTR) { }
    return ok && sent == length && WIFEXITED(status) && WEXITSTATUS(status) == 0;
}

static bool property_get(const char *name, char *out, size_t cap) {
    const char *args[] = {"/system/bin/getprop", name, NULL};
    if (!command(args, NULL, out, cap)) return false;
    size_t n = strlen(out);
    if (n && out[n - 1] == '\n') out[--n] = 0;
    return !strchr(out, '\n') && !strchr(out, '\r');
}

static bool property_set(const char *name, const char *value) {
    const char *args[] = {"/system/bin/setprop", name, value, NULL};
    char output[1024];
    return command(args, NULL, output, sizeof(output));
}

/* Descend component by component; /data is Android's system-owned directory,
 * /data/adb and both destination directories must be root-owned, non-writable
 * by group/other. Never follow destination symlinks or fix hostile ownership.
 */
static bool directory_ok(int fd, bool data) {
    struct stat s;
    return !fstat(fd, &s) && S_ISDIR(s.st_mode) &&
        (s.st_uid == 0 || (data && s.st_uid == 1000)) &&
        (data ? !(s.st_mode & 0002) && (!(s.st_mode & 0020) || s.st_gid == 0 || s.st_gid == 1000) :
                !(s.st_mode & 0022));
}

static int adb_directory(void) {
    int root = open("/", O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
    if (root < 0 || !directory_ok(root, false)) { if (root >= 0) close(root); return -1; }
    int data = openat(root, "data", O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC); close(root);
    if (data < 0 || !directory_ok(data, true)) { if (data >= 0) close(data); return -1; }
    int adb = openat(data, "adb", O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC); close(data);
    if (adb < 0 || !directory_ok(adb, false)) { if (adb >= 0) close(adb); return -1; }
    return adb;
}

static int root_directory(bool create) {
    int adb = adb_directory();
    if (adb < 0) return -1;
    if (create && mkdirat(adb, "adb-ssh-tunnel", 0700) && errno != EEXIST) { close(adb); return -1; }
    int dir = openat(adb, "adb-ssh-tunnel", O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC); close(adb);
    if (dir < 0 || !directory_ok(dir, false)) { if (dir >= 0) close(dir); return -1; }
    struct stat s;
    if (fstat(dir, &s) || (!create && (s.st_mode & 07777) != 0700)) {
        close(dir); return -1;
    }
    return dir;
}

static bool file_ok(int fd, mode_t mode) {
    struct stat s;
    return !fstat(fd, &s) && S_ISREG(s.st_mode) && s.st_uid == 0 && s.st_nlink == 1 &&
        (s.st_mode & 07777) == mode;
}

static bool destination_ok(int dir, const char *name) {
    struct stat s;
    if (fstatat(dir, name, &s, AT_SYMLINK_NOFOLLOW)) return errno == ENOENT;
    return S_ISREG(s.st_mode) && s.st_uid == 0 && s.st_nlink == 1 && !(s.st_mode & 0022);
}

/* Only unlink the O_EXCL temporary file we created in a verified directory. */
static bool atomic_file(int dir, const char *name, mode_t mode, const char *bytes, size_t length, int source) {
    if (!destination_ok(dir, name)) return false;
    static unsigned int sequence;
    char temp[96];
    int n = snprintf(temp, sizeof(temp), ".rc-%ld-%u.tmp", (long)getpid(), ++sequence);
    if (n < 0 || (size_t)n >= sizeof(temp)) return false;
    int fd = openat(dir, temp, O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW | O_CLOEXEC, 0600);
    if (fd < 0) return false;
    bool ok = true;
    if (source >= 0) {
        struct stat before, after;
        ok = !fstat(source, &before) && S_ISREG(before.st_mode) && before.st_size > 0 && before.st_size <= 64 * 1024 * 1024;
        off_t copied = 0;
        char buffer[16384];
        while (ok) {
            ssize_t count = read(source, buffer, sizeof(buffer));
            if (count < 0 && errno == EINTR) continue;
            if (count < 0) { ok = false; break; }
            if (!count) break;
            copied += count;
            if (copied > before.st_size || !write_all(fd, buffer, (size_t)count)) ok = false;
        }
        ok = ok && !fstat(source, &after) && copied == before.st_size && before.st_size == after.st_size &&
            before.st_mtim.tv_sec == after.st_mtim.tv_sec && before.st_mtim.tv_nsec == after.st_mtim.tv_nsec;
    } else ok = write_all(fd, bytes, length);
    ok = ok && !fchown(fd, 0, 0) && !fchmod(fd, mode) && !fsync(fd);
    if (close(fd)) ok = false;
    if (ok) ok = destination_ok(dir, name) && !renameat(dir, temp, dir, name);
    if (!ok) (void)unlinkat(dir, temp, 0);
    return ok && !fsync(dir);
}

static int open_lock(int dir, const char *name, bool create) {
    int fd = openat(dir, name, O_RDWR | O_NOFOLLOW | O_CLOEXEC | (create ? O_CREAT : 0), 0600);
    if (fd < 0 || !file_ok(fd, 0600)) { if (fd >= 0) close(fd); return -1; }
    return fd;
}

static bool lock_wait(int fd) {
    uint64_t deadline = clock_ms() + LOCK_TIMEOUT;
    do {
        if (!flock(fd, LOCK_EX | LOCK_NB)) return true;
        if (errno != EWOULDBLOCK && errno != EINTR) return false;
        struct timespec delay = {0, 50000000}; nanosleep(&delay, NULL);
    } while (!stopping && clock_ms() < deadline);
    return false;
}

static bool read_policy_fd(int fd, struct policy *p, bool timed) {
    char bytes[POLICY_MAX + 1]; size_t used = 0;
    uint64_t deadline = clock_ms() + IO_TIMEOUT;
    for (;;) {
        if (timed) {
            uint64_t now = clock_ms();
            if (now >= deadline) return false;
            struct pollfd f = {fd, POLLIN, 0};
            int r = poll(&f, 1, (int)(deadline - now));
            if (r < 0 && errno == EINTR) continue;
            if (r <= 0) return false;
        }
        ssize_t n = read(fd, bytes + used, sizeof(bytes) - used);
        if (n < 0 && errno == EINTR) continue;
        if (n < 0) return false;
        if (!n) break;
        used += (size_t)n;
        if (used > POLICY_MAX) return false;
    }
    return parse_policy(bytes, used, p);
}

static bool load_policy(int dir, struct policy *p) {
    int fd = openat(dir, "policy.conf", O_RDONLY | O_NOFOLLOW | O_CLOEXEC | O_NONBLOCK);
    if (fd < 0) return false;
    bool ok = file_ok(fd, 0600) && read_policy_fd(fd, p, false);
    close(fd); return ok;
}

static bool save_policy(int dir, const struct policy *p) {
    char bytes[1024];
    return serialize_policy(p, bytes, sizeof(bytes)) && atomic_file(dir, "policy.conf", 0600, bytes, strlen(bytes), -1);
}

struct socket_set { unsigned long long inode[SOCKET_MAX]; size_t count; };
static bool decimal_name(const char *s) {
    if (!*s) return false;
    for (; *s; ++s) if (*s < '0' || *s > '9') return false;
    return true;
}

static bool adbd_sockets(struct socket_set *set) {
    memset(set, 0, sizeof(*set));
    DIR *proc = opendir("/proc");
    if (!proc) return false;
    bool ok = true; unsigned int scanned = 0;
    struct dirent *entry;
    while ((entry = readdir(proc))) {
        if (!decimal_name(entry->d_name)) continue;
        if (++scanned > 32768) { ok = false; break; }
        int pidfd = openat(dirfd(proc), entry->d_name, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
        if (pidfd < 0) { if (errno != ENOENT && errno != ESRCH) ok = false; continue; }
        int comm = openat(pidfd, "comm", O_RDONLY | O_NOFOLLOW | O_CLOEXEC);
        char name[32] = {0};
        ssize_t n = comm < 0 ? -1 : read(comm, name, sizeof(name) - 1);
        if (comm >= 0) close(comm);
        if (n < 0 && errno != ENOENT && errno != ESRCH) ok = false;
        if (strcmp(name, "adbd\n")) { close(pidfd); continue; }
        struct stat process, executable;
        char executable_path[256];
        ssize_t path_len = readlinkat(pidfd, "exe", executable_path, sizeof(executable_path) - 1);
        if (path_len < 0) {
            if (errno != ENOENT && errno != ESRCH) ok = false;
            close(pidfd); continue;
        }
        executable_path[path_len] = 0;
        /* comm is writable by any process. It cannot establish listener
         * health without root/shell process ownership and the real ADB exe. */
        if (fstat(pidfd, &process) || fstatat(pidfd, "exe", &executable, 0)) {
            if (errno != ENOENT && errno != ESRCH) ok = false;
            close(pidfd); continue;
        }
        if ((process.st_uid != 0 && process.st_uid != 2000) || executable.st_uid != 0 ||
            !S_ISREG(executable.st_mode)) { close(pidfd); continue; }
        if (!adbd_path(executable_path)) { close(pidfd); ok = false; break; }
        int fds = openat(pidfd, "fd", O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC); close(pidfd);
        if (fds < 0) { if (errno != ENOENT && errno != ESRCH) ok = false; continue; }
        DIR *directory = fdopendir(fds);
        if (!directory) { close(fds); ok = false; break; }
        struct dirent *fd_entry;
        unsigned int fd_count = 0;
        while ((fd_entry = readdir(directory))) {
            if (!decimal_name(fd_entry->d_name)) continue;
            if (++fd_count > 8192) { ok = false; break; }
            char link[96];
            ssize_t len = readlinkat(dirfd(directory), fd_entry->d_name, link, sizeof(link) - 1);
            if (len < 0) { if (errno != ENOENT) ok = false; continue; }
            link[len] = 0;
            unsigned long long inode; char tail;
            if (sscanf(link, "socket:[%llu]%c", &inode, &tail) == 1) {
                if (set->count == SOCKET_MAX) { ok = false; break; }
                set->inode[set->count++] = inode;
            }
        }
        closedir(directory);
        if (!ok) break;
    }
    closedir(proc); return ok;
}

static bool owns_socket(const struct socket_set *s, unsigned long long inode) {
    for (size_t i = 0; i < s->count; ++i) if (s->inode[i] == inode) return true;
    return false;
}

static bool scan_tcp(const char *path, const struct socket_set *s, struct ports *p, bool *any) {
    FILE *file = fopen(path, "re");
    if (!file) return false;
    char line[2048]; bool ok = fgets(line, sizeof(line), file) != NULL; unsigned int rows = 0;
    while (ok && fgets(line, sizeof(line), file)) {
        if (++rows > 65536 || !strchr(line, '\n')) { ok = false; break; }
        struct tcp_record record;
        if (!parse_tcp_record(line, &record)) { ok = false; break; }
        if (record.state != 0x0a && record.state != 0x01) continue;
        bool owned = owns_socket(s, record.inode);
        if (!owned && record.uid != 2000) continue;
        if (!add_port(p, record.port)) { ok = false; break; }
        if (owned) {
            *any = true;
            if (record.state == 0x0a && record.port == 5555) p->listener = true;
        }
    }
    if (ferror(file)) ok = false;
    fclose(file); return ok;
}

static bool collect_ports(struct ports *p, bool *any) {
    struct socket_set s;
    memset(p, 0, sizeof(*p)); *any = false;
    if (!add_port(p, 5555) || !adbd_sockets(&s)) return false;
    return scan_tcp("/proc/net/tcp", &s, p, any) && scan_tcp("/proc/net/tcp6", &s, p, any);
}

static void current_ifaces(const struct policy *p, bool binder, struct interfaces *a) {
    base_ifaces(p, a);
    if (!binder || (!p->wifi_len && !p->hotspot_len)) return;
    char *status = calloc(1, TEXT_MAX), *dump = calloc(1, TEXT_MAX);
    if (!status || !dump) { free(status); free(dump); return; }
    const char *cmd[] = {"/system/bin/cmd", "wifi", "status", NULL};
    const char *dumpsys[] = {"/system/bin/dumpsys", "wifi", NULL};
    bool status_ok = command(cmd, NULL, status, TEXT_MAX);
    bool dump_ok = command(dumpsys, NULL, dump, TEXT_MAX);
    /* A failed query is unknown, even if it returned plausible partial data. */
    trusted_ifaces(p, status_ok ? status : "", dump_ok ? dump : "", a);
    free(status); free(dump);
}

static bool binder_ready(void) {
    char boot[128];
    return property_get("sys.boot_completed", boot, sizeof(boot)) && !strcmp(boot, "1");
}

static bool firewall_family(bool ipv6, const char *rules, bool mutate) {
    const char *args[] = {ipv6 ? "/system/bin/ip6tables" : "/system/bin/iptables",
                          "-w", "2", "-t", "filter", "-S", NULL};
    char *snapshot = malloc(TEXT_MAX), *transaction = malloc(RULE_MAX), response[2048];
    if (!snapshot || !transaction) { free(snapshot); free(transaction); return false; }
    struct snapshot state;
    bool queried = command(args, NULL, snapshot, TEXT_MAX);
    bool verified = queried && inspect_snapshot(snapshot, rules, &state);
    bool ok = verified;
    if (!verified && mutate && queried && state.safe && render_restore(&state, rules, transaction, RULE_MAX)) {
        const char *restore[] = {ipv6 ? "/system/bin/ip6tables-restore" : "/system/bin/iptables-restore",
                                 "-w", "2", "--noflush", NULL};
        ok = command(restore, transaction, response, sizeof(response)) &&
             command(args, NULL, snapshot, TEXT_MAX) && inspect_snapshot(snapshot, rules, &state);
    }
    free(snapshot); free(transaction); return ok;
}

static bool guard_policy(const struct policy *p, bool binder, bool mutate) {
    struct interfaces a;
    current_ifaces(p, binder, &a);
    struct ports ports; bool any;
    if (!collect_ports(&ports, &any)) return false;
    char *rules = malloc(RULE_MAX);
    if (!rules) return false;
    bool ok = render_rules(&a, &ports, rules, RULE_MAX);
    /* Always attempt both families, including after one restore failed. */
    bool v4 = ok && firewall_family(false, rules, mutate);
    bool v6 = ok && firewall_family(true, rules, mutate);
    free(rules); return v4 && v6;
}

static bool shutdown_tcp(void) {
    /* Fail-closed shutdown is bounded but must complete even if the operation
     * that discovered a broken guard was interrupted by SIGTERM/SIGINT. */
    sigset_t blocked, previous;
    sigemptyset(&blocked); sigaddset(&blocked, SIGTERM); sigaddset(&blocked, SIGINT);
    (void)sigprocmask(SIG_BLOCK, &blocked, &previous);
    sig_atomic_t was_stopping = stopping; stopping = 0;
    char service[128], persist[128], tls[128];
    struct ports p; bool any;
    bool observed = collect_ports(&p, &any);
    bool properties = property_get("service.adb.tcp.port", service, sizeof(service)) &&
                      property_get("persist.adb.tcp.port", persist, sizeof(persist)) &&
                      property_get("persist.adb.tls_server.enable", tls, sizeof(tls));
    bool restart = !observed || !properties || any || strcmp(tls, "0") ||
                   adb_action(false, p.listener, service, persist) == ADB_RESTART;
    bool a = property_set("service.adb.tcp.port", "-1");
    bool b = property_set("persist.adb.tcp.port", "-1");
    /* Wireless debugging is another independent source of TLS listeners.
     * Its init property can be disabled without Binder or USB config writes.
     */
    bool d = property_set("persist.adb.tls_server.enable", "0");
    bool e = property_set("service.adb.tls.port", "-1");
    bool c = !restart || property_set("ctl.restart", "adbd");
    bool verified = false;
    uint64_t deadline = clock_ms() + 3000;
    do {
        if (collect_ports(&p, &any) && !any && !p.listener &&
            property_get("service.adb.tcp.port", service, sizeof(service)) && !strcmp(service, "-1") &&
            property_get("persist.adb.tcp.port", persist, sizeof(persist)) && !strcmp(persist, "-1") &&
            property_get("persist.adb.tls_server.enable", tls, sizeof(tls)) && !strcmp(tls, "0")) {
            verified = true; break;
        }
        struct timespec delay = {0, 100000000}; nanosleep(&delay, NULL);
    } while (clock_ms() < deadline);
    stopping = was_stopping;
    (void)sigprocmask(SIG_SETMASK, &previous, NULL);
    return a && b && c && d && e && verified;
}

static int guard_failed(void) {
    log_failure("guard failed; shutting down TCP ADB");
    if (!shutdown_tcp()) log_failure("TCP shutdown incomplete; USB properties were preserved");
    return RC_GUARD;
}

static int reconcile(const struct policy *p, struct backoff *backoff) {
    struct ports ports; bool any;
    char service[128], persist[128];
    if (!collect_ports(&ports, &any) || !property_get("service.adb.tcp.port", service, sizeof(service)) ||
        !property_get("persist.adb.tcp.port", persist, sizeof(persist))) return RC_ADB;
    bool healthy = adb_action(p->enabled, ports.listener, service, persist) == ADB_KEEP;
    bool restart = backoff ? restart_due(backoff, clock_ms() / 1000, healthy) : !healthy;
    if (healthy || !restart) return RC_OK;
    const char *value = p->enabled ? "5555" : "-1";
    bool a = !strcmp(service, value) || property_set("service.adb.tcp.port", value);
    bool b = !strcmp(persist, value) || property_set("persist.adb.tcp.port", value);
    if (!a || !b || !property_set("ctl.restart", "adbd")) return RC_ADB;
    /* Restart requests are asynchronous; allow init a bounded settle period,
     * read back both properties and the real adbd-owned listening socket.
     */
    uint64_t deadline = clock_ms() + 3000;
    do {
        if (collect_ports(&ports, &any) && property_get("service.adb.tcp.port", service, sizeof(service)) &&
            property_get("persist.adb.tcp.port", persist, sizeof(persist)) &&
            adb_action(p->enabled, ports.listener, service, persist) == ADB_KEEP) return RC_OK;
        struct timespec delay = {0, 100000000}; nanosleep(&delay, NULL);
    } while (!stopping && clock_ms() < deadline);
    return RC_ADB;
}

static bool install_files(int dir) {
    /* /proc/self/exe is the only deliberately followed magic symlink. No path
     * from stdin, environment, caller argv or app-private extraction is used.
     */
    int source = open("/proc/self/exe", O_RDONLY | O_CLOEXEC);
    if (source < 0) return false;
    bool ok = atomic_file(dir, "root-controller", 0700, NULL, 0, source); close(source);
    if (!ok) return false;
    int adb = adb_directory();
    if (adb < 0) return false;
    if (mkdirat(adb, "post-fs-data.d", 0755) && errno != EEXIST) { close(adb); return false; }
    int scripts = openat(adb, "post-fs-data.d", O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC); close(adb);
    if (scripts < 0 || !directory_ok(scripts, false)) { if (scripts >= 0) close(scripts); return false; }
    const char script[] = "#!/system/bin/sh\n"
        EXEC_PATH " guard || exit $?\n"
        EXEC_PATH " watch </dev/null >/dev/null 2>&1 &\n";
    ok = atomic_file(scripts, "adb-ssh-tunnel-guard.sh", 0700, script, sizeof(script) - 1, -1);
    close(scripts); return ok;
}

static bool process_ticks(pid_t pid, unsigned long long *ticks) {
    char path[64], line[4096];
    int n = snprintf(path, sizeof(path), "/proc/%ld/stat", (long)pid);
    if (n < 0 || (size_t)n >= sizeof(path)) return false;
    int fd = open(path, O_RDONLY | O_NOFOLLOW | O_CLOEXEC);
    if (fd < 0) return false;
    ssize_t size = read(fd, line, sizeof(line) - 1); close(fd);
    if (size <= 0) return false;
    line[size] = 0;
    char *at = strrchr(line, ')');
    if (!at || at[1] != ' ') return false;
    at += 2;
    for (unsigned int field = 3; field < 22; ++field) {
        at = strchr(at, ' '); if (!at) return false; ++at;
    }
    char *end; errno = 0; *ticks = strtoull(at, &end, 10);
    return !errno && end != at && *end == ' ';
}

static bool watcher_record(int dir) {
    unsigned long long ticks; char bytes[128];
    if (!process_ticks(getpid(), &ticks)) return false;
    int n = snprintf(bytes, sizeof(bytes), "%ld %llu %s\n", (long)getpid(), ticks, RC_VERSION);
    return n > 0 && (size_t)n < sizeof(bytes) && atomic_file(dir, "watcher.state", 0600, bytes, (size_t)n, -1);
}

static bool watcher_status(int dir, long *pid, char *version, size_t cap) {
    *pid = 0; if (cap) version[0] = 0;
    int lock = open_lock(dir, "watch.lock", false);
    if (lock < 0) return false;
    if (!flock(lock, LOCK_EX | LOCK_NB)) { close(lock); return false; }
    bool locked = errno == EWOULDBLOCK; close(lock);
    if (!locked || cap < 32) return false;
    int fd = openat(dir, "watcher.state", O_RDONLY | O_NOFOLLOW | O_CLOEXEC | O_NONBLOCK);
    if (fd < 0) return false;
    char bytes[128] = {0};
    ssize_t n = file_ok(fd, 0600) ? read(fd, bytes, sizeof(bytes) - 1) : -1; close(fd);
    unsigned long long saved, actual; char extra;
    if (n <= 0 || sscanf(bytes, "%ld %llu %31s %c", pid, &saved, version, &extra) != 3 ||
        *pid <= 1 || *pid > 4194304 || !process_ticks((pid_t)*pid, &actual) || actual != saved) {
        *pid = 0; version[0] = 0; return false;
    }
    char path[64]; struct stat executable;
    int count = snprintf(path, sizeof(path), "/proc/%ld/exe", *pid);
    /* Exact installed path, not a same-name process or a PID reused later. */
    char link[256];
    ssize_t length = count > 0 && (size_t)count < sizeof(path) ? readlink(path, link, sizeof(link) - 1) : -1;
    if (length <= 0) { *pid = 0; version[0] = 0; return false; }
    link[length] = 0;
    if (strcmp(link, EXEC_PATH) && strcmp(link, EXEC_PATH " (deleted)")) { *pid = 0; version[0] = 0; return false; }
    if (stat(path, &executable) || executable.st_uid != 0) { *pid = 0; version[0] = 0; return false; }
    return true;
}

static int route_socket(void) {
    int fd = socket(AF_NETLINK, SOCK_RAW | SOCK_CLOEXEC | SOCK_NONBLOCK, NETLINK_ROUTE);
    if (fd < 0) return -1;
    struct sockaddr_nl address = {0}; address.nl_family = AF_NETLINK;
    address.nl_groups = RTMGRP_LINK | RTMGRP_IPV4_IFADDR | RTMGRP_IPV6_IFADDR |
                        RTMGRP_IPV4_ROUTE | RTMGRP_IPV6_ROUTE;
    if (bind(fd, (struct sockaddr *)&address, sizeof(address))) { close(fd); return -1; }
    return fd;
}

static int watch_loop(int dir) {
    int watch_lock = open_lock(dir, "watch.lock", true);
    if (watch_lock < 0) return RC_STORAGE;
    if (flock(watch_lock, LOCK_EX | LOCK_NB)) {
        bool busy = errno == EWOULDBLOCK; close(watch_lock); return busy ? RC_OK : RC_LOCK;
    }
    int lock = open_lock(dir, "controller.lock", true), netlink = route_socket();
    if (lock < 0 || netlink < 0 || !watcher_record(dir)) {
        if (lock >= 0 && lock_wait(lock)) { (void)guard_failed(); (void)flock(lock, LOCK_UN); }
        if (lock >= 0) close(lock);
        if (netlink >= 0) close(netlink);
        close(watch_lock); return RC_WATCH;
    }
    struct backoff retry = {0};
    bool changed = true;
    while (!stopping) {
        if (!lock_wait(lock)) { log_failure("watch mutation lock timeout"); }
        else {
            struct policy p;
            if (!load_policy(dir, &p)) { (void)guard_failed(); }
            else {
                /* Withdraw Wi-Fi exceptions before querying identity after an
                 * event. The early-boot path never invokes cmd or dumpsys.
                 */
                bool ok = !changed || guard_policy(&p, false, true);
                if (ok) ok = guard_policy(&p, binder_ready(), true);
                if (!ok) (void)guard_failed();
                else if (reconcile(&p, &retry) != RC_OK) log_failure("watch ADB not yet reconciled");
            }
            (void)flock(lock, LOCK_UN);
        }
        if (stopping) break;
        struct pollfd f = {netlink, POLLIN, 0};
        int result = poll(&f, 1, (int)(WATCH_INTERVAL * 1000));
        changed = result > 0;
        if (result < 0 && errno != EINTR) { log_failure("netlink poll failure"); break; }
        if (result > 0) {
            unsigned int count = 0; char packet[8192];
            while (recv(netlink, packet, sizeof(packet), MSG_DONTWAIT) > 0 && ++count < 64) { }
        }
    }
    /* A dead watchdog cannot continue vouching for a changing wlan identity. */
    sig_atomic_t interrupted = stopping; stopping = 0;
    if (!upgrading && lock_wait(lock)) { (void)shutdown_tcp(); (void)flock(lock, LOCK_UN); }
    stopping = interrupted;
    close(netlink); close(lock); close(watch_lock);
    return stopping ? RC_OK : RC_WATCH;
}

static bool start_watch(int dir) {
    long pid; char version[32];
    if (watcher_status(dir, &pid, version, sizeof(version))) {
        char path[64]; struct stat running, installed;
        int n = snprintf(path, sizeof(path), "/proc/%ld/exe", pid);
        if (n <= 0 || (size_t)n >= sizeof(path) || stat(path, &running) ||
            fstatat(dir, "root-controller", &installed, AT_SYMLINK_NOFOLLOW)) return false;
        if (running.st_dev == installed.st_dev && running.st_ino == installed.st_ino && !strcmp(version, RC_VERSION)) return true;
        /* The exact verified old executable retains its guard during handover. */
        int mutation_lock = open_lock(dir, "controller.lock", false);
        if (mutation_lock < 0) return false;
        if (!lock_wait(mutation_lock)) { close(mutation_lock); return false; }
        if (kill((pid_t)pid, SIGHUP)) { close(mutation_lock); return false; }
        int old_lock = open_lock(dir, "watch.lock", false);
        if (old_lock < 0) { close(mutation_lock); return false; }
        bool exited = lock_wait(old_lock);
        if (exited) (void)flock(old_lock, LOCK_UN);
        close(old_lock);
        (void)flock(mutation_lock, LOCK_UN); close(mutation_lock);
        if (!exited) return false;
    }
    pid_t child = fork();
    if (child < 0) return false;
    if (!child) {
        if (setsid() < 0) _exit(RC_WATCH);
        pid_t daemon = fork();
        if (daemon < 0) _exit(RC_WATCH);
        if (daemon > 0) _exit(0);
        int null_fd = open("/dev/null", O_RDWR | O_CLOEXEC);
        if (null_fd < 0 || dup2(null_fd, 0) < 0 || dup2(null_fd, 1) < 0 || dup2(null_fd, 2) < 0) _exit(RC_WATCH);
        if (null_fd > 2) close(null_fd);
        const char *args[] = {EXEC_PATH, "watch", NULL};
        char *const environment[] = {"PATH=/system/bin", "LANG=C", "LC_ALL=C", NULL};
        execve(EXEC_PATH, (char *const *)args, environment); _exit(RC_WATCH);
    }
    int status = 0;
    while (waitpid(child, &status, 0) < 0) if (errno != EINTR) return false;
    if (!WIFEXITED(status) || WEXITSTATUS(status)) return false;
    uint64_t deadline = clock_ms() + 2000;
    do {
        if (watcher_status(dir, &pid, version, sizeof(version))) return true;
        struct timespec delay = {0, 50000000}; nanosleep(&delay, NULL);
    } while (!stopping && clock_ms() < deadline);
    return false;
}

static void report_status(int dir, const struct policy *p, bool loaded, bool guarded) {
    struct ports ports; bool any; long pid = 0; char version[32] = {0};
    bool observed = collect_ports(&ports, &any);
    bool running = dir >= 0 && watcher_status(dir, &pid, version, sizeof(version));
    printf("enabled=%d\nguarded=%d\nlistener=%d\nwatcher_pid=%ld\nwatcher_version=%s\nversion=%s\n",
           loaded ? (p->enabled ? 1 : 0) : -1, guarded ? 1 : 0, observed ? (ports.listener ? 1 : 0) : -1,
           running ? pid : 0, running ? version : "none", RC_VERSION);
}

int main(int argc, char **argv) {
    if (argc != 2) { log_failure("expected one fixed operation"); return RC_POLICY; }
    const char *op = argv[1];
    bool install = !strcmp(op, "install"), apply = !strcmp(op, "apply");
    bool guard = !strcmp(op, "guard"), check = !strcmp(op, "check"), enable = !strcmp(op, "enable");
    bool disable = !strcmp(op, "disable"), status = !strcmp(op, "status"), boot = !strcmp(op, "boot");
    bool watch = !strcmp(op, "watch");
    if (!install && !apply && !guard && !check && !enable && !disable && !status && !boot && !watch) return RC_POLICY;
    if (getuid() != 0 || geteuid() != 0) { log_failure("root required"); return RC_ROOT; }
    if (!ensure_stdio()) return RC_STORAGE;
    umask(0077);
    signal(SIGPIPE, SIG_IGN); signal(SIGTERM, stop_signal); signal(SIGINT, stop_signal); signal(SIGHUP, upgrade_signal);
    struct policy p;
    if ((install || apply) && !read_policy_fd(STDIN_FILENO, &p, true)) {
        log_failure("invalid or incomplete policy input"); return RC_POLICY;
    }
    int dir = root_directory(install);
    if (dir < 0) {
        log_failure("unsafe or unavailable root directory");
        if (status) report_status(-1, NULL, false, false);
        if (!check && !status) (void)shutdown_tcp();
        return RC_STORAGE;
    }
    if (watch) { int result = watch_loop(dir); close(dir); return result; }
    if (check || status) {
        int read_lock = open_lock(dir, "controller.lock", false);
        if (read_lock < 0) { close(dir); return RC_STORAGE; }
        if (!lock_wait(read_lock)) { close(read_lock); close(dir); return RC_LOCK; }
        bool loaded = load_policy(dir, &p);
        bool guarded = loaded && guard_policy(&p, binder_ready(), false);
        if (status) report_status(dir, &p, loaded, guarded);
        (void)flock(read_lock, LOCK_UN); close(read_lock); close(dir); return guarded ? RC_OK : RC_GUARD;
    }
    int lock = open_lock(dir, "controller.lock", true);
    if (lock < 0) { close(dir); return RC_STORAGE; }
    if (!lock_wait(lock)) { close(lock); close(dir); return RC_LOCK; }
    int result = RC_OK;
    if (install && fchmod(dir, 0700)) result = RC_STORAGE;
    if (!install && !apply && !load_policy(dir, &p)) result = guard_failed();
    if (!result && enable) p.enabled = true;
    if (!result && disable) p.enabled = false;
    if (!result && install && !install_files(dir)) result = RC_STORAGE;
    /* Close direct access synchronously before any Binder query, including a
     * late install over a previously unguarded listener. Each restore itself
     * is atomic; the two IP families intentionally remain separate commits. */
    if (!result && !guard_policy(&p, false, true)) result = guard_failed();
    if (!result && !boot && binder_ready() && !guard_policy(&p, true, true)) result = guard_failed();
    if (!result && (install || apply || enable || disable) && !save_policy(dir, &p)) {
        (void)shutdown_tcp(); result = RC_STORAGE;
    }
    if (!result && !guard) result = reconcile(&p, NULL);
    /* Guard is intentionally firewall-only; boot/install/apply/enable/disable
     * make sure the independently enabled root policy has a persistent watch.
     */
    (void)flock(lock, LOCK_UN); close(lock);
    if ((!result || result == RC_ADB) && !guard && !start_watch(dir)) {
        /* Reacquire the mutation lock for emergency property changes. */
        int emergency = open_lock(dir, "controller.lock", false);
        if (emergency >= 0) {
            if (lock_wait(emergency)) { (void)shutdown_tcp(); (void)flock(emergency, LOCK_UN); }
            close(emergency);
        }
        result = RC_WATCH;
    }
    close(dir);
    if (result) log_failure("operation did not complete");
    return result;
}
#endif
