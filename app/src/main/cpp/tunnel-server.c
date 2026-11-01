/* A fresh native image keeps the Android runtime out of the SSH daemon. */
#include "jni-dropbear.c"
#include <limits.h>

int main(int argc, char **argv) {
    if (argc < 8) return 125;
    char *end = NULL;
    errno = 0;
    long parent = strtol(argv[1], &end, 10);
    if (errno || !end || *end || parent <= 1 || parent > INT_MAX) return 125;
    adb_tunnel_bind_parent((pid_t) parent);

    lib_path = argv[2];
    conf_path = argv[3];
    sshd4a_home_path = argv[4];
    sshd4a_shell_exe = "/system/bin/false";
    env_var_list = "";
    enable_service_shell_access = JNI_FALSE;
    enable_public_key_auth = JNI_TRUE;
    enable_single_use_passwords = JNI_FALSE;
    if (setenv("ADB_TUNNEL_PORT", argv[5], 1) != 0) return 125;
    dropbear_main(argc - 6, argv + 6, NULL);
    return 0;
}
