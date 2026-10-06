# ADB SSH Tunnel

An Android SSH tunnel restricted to this phone’s local ADB. Key-only SSH,
no shell, PTY, file transfer, agent/X11 or reverse forwarding.

Package: `name.krot.adbsshtunnel`. Initial release candidate: `1.0.0` (1).
This repository is in development; device acceptance and a signed release are pending.

## Without root

Enable Android Wireless Debugging on Wi-Fi and pair your computer using
`adb pair PHONE:PAIR_PORT`. The application discovers the local connect port.
Import your computer’s OpenSSH public key and enable the tunnel.

```sh
ssh -N -L 15555:127.0.0.1:ADB_PORT -p 19191 -i YOUR_KEY USER@PHONE
adb connect 127.0.0.1:15555
```

Android can stop Wireless Debugging on reboot or network change. The application
does not silently enable it. Keep pairing and SSH host-key verification separate.
Pin the SSH host key using a physical USB forward before trusting WAN.

## With root

Install `helper/helper.sh` as `/data/adb/adb-ssh-tunnel/helper.sh`, root-owned,
mode 700 through a trusted USB connection. The helper applies its own protection
before enabling ADB port 5555. The SSH service itself is never elevated.
An existing PhoneDebug guard retains responsibility for its trusted LAN policy.
Do not remove or overwrite other applications’ firewall rules.

Incoming mobile connections require a routable public IPv4 and operator support;
an interface address or open TCP socket alone does not establish reachability.

## Build

Java 17, Gradle 8.11.1, AGP 8.10.1, SDK 36, NDK 27.2.12479018,
CMake 3.22.1. JNI/Dropbear is compiled from Sshd4a commit
`897f9064a7279bff89538ec87729c43888f3b83d`.

```sh
git clone https://github.com/tfonteyn/Sshd4a.git vendor/Sshd4a
git -C vendor/Sshd4a checkout --detach 897f9064a7279bff89538ec87729c43888f3b83d
python3 tools/prepare-native.py
gradle --offline --no-daemon testReleaseUnitTest lintRelease assembleRelease
```

Use an isolated unprivileged environment without user secrets for compilation.
Prepare SDK/dependency caches separately, then compile with no network. Sign
the reviewed APK in a separate process. One permanent signing key is used for
GitHub and every future store, including the Play App Signing key.

## Licenses

Application code: GPL-3.0. Native code originates from
[Sshd4a](https://github.com/tfonteyn/Sshd4a/tree/897f9064a7279bff89538ec87729c43888f3b83d).
Its Dropbear, libtommath and libtomcrypt notices remain in the pinned source.
No upstream SCP, SFTP or rsync executable is built or packaged.
