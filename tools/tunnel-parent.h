#ifndef ADB_TUNNEL_PARENT_H
#define ADB_TUNNEL_PARENT_H

#include <signal.h>
#include <sys/prctl.h>
#include <sys/types.h>
#include <unistd.h>

/* Do not let an authenticated forwarding process outlive its server. */
static void adb_tunnel_bind_parent(pid_t expected_parent) {
    if (expected_parent <= 1 || prctl(PR_SET_PDEATHSIG, SIGKILL) != 0 ||
        getppid() != expected_parent) {
        _exit(126);
    }
}

#endif
