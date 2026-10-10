"""Prepare the pinned Sshd4a backend with only the tunnel server target."""
from pathlib import Path
import re
import subprocess
import shutil

ROOT = Path(__file__).resolve().parents[1]
PIN = "897f9064a7279bff89538ec87729c43888f3b83d"
source = ROOT / "vendor" / "Sshd4a"
if subprocess.check_output(["git", "-C", str(source), "rev-parse", "HEAD"], text=True).strip() != PIN:
    raise SystemExit("Unexpected upstream revision")
# Match Dropbear's ifndef_wrapper.sh without requiring a Unix shell on Windows.
# The upstream CMake target expects this generated header before compilation.
dropbear = source / "app/src/main/cpp/dropbear"
defaults = (dropbear / "src/default_options.h").read_text()
guarded = re.sub(
    r"^( *#define ([^ \n]+) .*)$",
    lambda match: f"#ifndef {match.group(2)}\n{match.group(1)}\n#endif",
    defaults,
    flags=re.MULTILINE,
)
if guarded == defaults:
    raise SystemExit("Dropbear default option definitions changed upstream")
(dropbear / "src/default_options_guard.h").write_text(guarded)
cmake = (source / "app/CMakeLists.txt").read_text()
cmake = cmake.replace("cmake_minimum_required(VERSION 4.1.2)", "cmake_minimum_required(VERSION 3.22.1)")
cmake = cmake[:cmake.index("# build scp executable")]
cmake = cmake.replace("src/main/cpp", "${CMAKE_CURRENT_LIST_DIR}/Sshd4a/app/src/main/cpp")
cmake += "\ntarget_compile_definitions(dropbear PRIVATE DROPBEAR_SVR_REMOTETCPFWD=0 DROPBEAR_SVR_LOCALSTREAMFWD=0 DROPBEAR_SVR_REMOTESTREAMFWD=0 DROPBEAR_SVR_AGENTFWD=0 DROPBEAR_X11FWD=0)\n"
(ROOT / "vendor/native.cmake").write_text(cmake)
# Only direct TCP channels are registered. Session requests are rejected by
# Dropbear's existing unknown-channel path, before allocating session state.
channel = source / "app/src/main/cpp/dropbear/src/svr-session.c"
text = channel.read_text()
needle = "\t&svrchansess,"
marker = "\t/* ADB SSH Tunnel: no session channels. */"
if marker not in text:
    if text.count(needle) != 1:
        raise SystemExit("Session channel table changed upstream")
    channel.write_text(text.replace(needle, marker, 1))
# Android restricts /proc enumeration, so Java cannot find all forked sessions.
# Bind both native generations to their actual parent before doing any work.
jni = source / "app/src/main/cpp/jni-dropbear.c"
shutil.copyfile(ROOT / "tools/tunnel-parent.h", jni.parent / "tunnel-parent.h")
text = jni.read_text()
if '#include "tunnel-parent.h"' not in text:
    needle = "    pid_t pid = fork();\n    if (pid == 0) {\n        /* child */"
    if text.count(needle) != 1:
        raise SystemExit("JNI server fork changed upstream")
    text = '#include "tunnel-parent.h"\n' + text.replace(
        needle,
        "    pid_t tunnel_parent = getpid();\n    pid_t pid = fork();\n"
        "    if (pid == 0) {\n        adb_tunnel_bind_parent(tunnel_parent);\n        /* child */",
        1,
    )
    jni.write_text(text)
# Set only the server child's environment; forwarding channels cannot change it.
text = jni.read_text()
env_needle = "        env_var_list = from_java_string(env, j_env_var_list);"
if "/* Tunnel target environment */" not in text:
    if text.count(env_needle) != 1:
        raise SystemExit("JNI environment setup changed upstream")
    jni.write_text(text.replace(env_needle, env_needle + "\n        sshd4a_set_env(); /* Tunnel target environment */", 1))
forward = dropbear / "src/svr-tcpfwd.c"
text = forward.read_text()
shutil.copyfile(ROOT / "tools/tunnel-target.h", jni.parent / "tunnel-target.h")
if '#include "../../tunnel-target.h"' not in text:
    needle = "    snprintf(portstring, sizeof(portstring), \"%u\", destport);"
    host_needle = "    destport = buf_getint(ses.payload);"
    if text.count(needle) != 1 or text.count(host_needle) != 1:
        raise SystemExit("Direct forwarding target changed upstream")
    text = '#include "../../tunnel-target.h"\n' + text.replace(
        host_needle, "    unsigned int tunnel_host_len = len;\n" + host_needle, 1,
    ).replace(needle,
        '    destport = adb_tunnel_target(desthost, tunnel_host_len, destport, getenv("ADB_TUNNEL_PORT"));\n'
        '    if (!destport) goto out;\n' + needle, 1)
    forward.write_text(text)
server = dropbear / "src/svr-main.c"
text = server.read_text()
if '#include "../../tunnel-parent.h"' not in text:
    fork_needle = "\t\t\tfork_ret = fork();"
    child_needle = "#if !DEBUG_NOFORK\n\t\t\t\tif (setsid() < 0) {"
    if text.count(fork_needle) != 1 or text.count(child_needle) != 1:
        raise SystemExit("Dropbear connection fork changed upstream")
    text = '#include "../../tunnel-parent.h"\n' + text.replace(
        fork_needle, "\t\t\tpid_t tunnel_parent = getpid();\n" + fork_needle, 1,
    ).replace(
        child_needle,
        "#if !DEBUG_NOFORK\n\t\t\t\tadb_tunnel_bind_parent(tunnel_parent);\n\t\t\t\tif (setsid() < 0) {",
        1,
    )
    server.write_text(text)
print("Prepared tunnel-only JNI from", PIN)
