#define _POSIX_C_SOURCE 200809L
#include "tunnel-parent.h"
#include <errno.h>
#include <poll.h>
#include <stdio.h>
#include <stdlib.h>
#include <sys/wait.h>
#include <time.h>

static int report_fd;

static void report(pid_t pid) {
    if (write(report_fd, &pid, sizeof(pid)) != sizeof(pid)) _exit(2);
}

static void listener(pid_t parent) {
    adb_tunnel_bind_parent(parent);
    pid_t listener_pid = getpid();
    pid_t connection = fork();
    if (connection < 0) _exit(2);
    if (connection == 0) {
        adb_tunnel_bind_parent(listener_pid);
        report(getpid());
        for (;;) pause();
    }
    report(listener_pid);
    for (;;) pause();
}

static int reaped_after_kill(pid_t pid) {
    for (int attempt = 0; attempt < 100; ++attempt) {
        int status;
        pid_t result = waitpid(pid, &status, WNOHANG);
        if (result == pid) return WIFSIGNALED(status) && WTERMSIG(status) == SIGKILL;
        if (result < 0 && errno != ECHILD) return 0;
        struct timespec delay = {0, 10000000};
        nanosleep(&delay, NULL);
    }
    kill(pid, SIGKILL);
    waitpid(pid, NULL, 0);
    return 0;
}

static int check_tree(int kill_app) {
    int pipe_fds[2];
    if (pipe(pipe_fds) != 0) return 0;
    report_fd = pipe_fds[1];
    pid_t app_parent = getpid();
    pid_t app = fork();
    if (app < 0) return 0;
    if (app == 0) {
        close(pipe_fds[0]);
        adb_tunnel_bind_parent(app_parent);
        if (kill_app) {
            pid_t owner = getpid();
            pid_t server = fork();
            if (server < 0) _exit(2);
            if (server == 0) listener(owner);
            for (;;) pause();
        }
        listener(app_parent);
    }
    close(pipe_fds[1]);
    pid_t child_pids[2] = {0, 0};
    for (int i = 0; i < 2; ++i) {
        struct pollfd fd = {pipe_fds[0], POLLIN, 0};
        if (poll(&fd, 1, 3000) <= 0 ||
            read(pipe_fds[0], &child_pids[i], sizeof(pid_t)) != sizeof(pid_t)) {
            kill(app, SIGKILL);
            waitpid(app, NULL, 0);
            close(pipe_fds[0]);
            return 0;
        }
    }
    close(pipe_fds[0]);
    kill(app, SIGKILL);
    int ok = reaped_after_kill(app);
    for (int i = 0; i < 2; ++i) {
        if (child_pids[i] != app) ok = reaped_after_kill(child_pids[i]) && ok;
    }
    return ok;
}

int main(void) {
    if (prctl(PR_SET_CHILD_SUBREAPER, 1) != 0) return 2;
    if (!check_tree(0)) { fputs("Server stop left a connection alive\n", stderr); return 1; }
    if (!check_tree(1)) { fputs("App death left a server or connection alive\n", stderr); return 1; }
    pid_t child = fork();
    if (child < 0) return 2;
    if (child == 0) { adb_tunnel_bind_parent(getpid()); _exit(0); }
    int status;
    if (waitpid(child, &status, 0) != child || !WIFEXITED(status) || WEXITSTATUS(status) != 126) {
        fputs("Changed parent was accepted\n", stderr); return 1;
    }
    puts("Server stop, app death and changed-parent checks passed");
    return 0;
}
