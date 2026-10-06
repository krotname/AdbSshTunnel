"""Prepare the pinned Sshd4a backend with only the tunnel server target."""
from pathlib import Path
import re
import subprocess

ROOT = Path(__file__).resolve().parents[1]
PIN = "897f9064a7279bff89538ec87729c43888f3b83d"
source = ROOT / "vendor" / "Sshd4a"
if subprocess.check_output(["git", "-C", str(source), "rev-parse", "HEAD"], text=True).strip() != PIN:
    raise SystemExit("Unexpected upstream revision")
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
print("Prepared tunnel-only JNI from", PIN)
