#ifndef ADB_TUNNEL_TARGET_H
#define ADB_TUNNEL_TARGET_H
#include <stddef.h>
#include <string.h>

/* The SSH destination is virtual. Never use an untrusted channel's actual port. */
static unsigned int adb_tunnel_target(const char *host, unsigned int host_len,
                                     unsigned int requested, const char *target) {
    if (!host || host_len != 9 || memcmp(host, "127.0.0.1", 9) != 0 ||
        requested != 5555 || !target || !*target) return 0;
    unsigned int port = 0, length = 0;
    for (const char *p = target; *p; ++p) {
        if (*p < '0' || *p > '9' || ++length > 5) return 0;
        port = port * 10 + (unsigned int)(*p - '0');
    }
    return port > 0 && port <= 65535 ? port : 0;
}
#endif
