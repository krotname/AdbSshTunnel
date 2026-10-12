# ADB SSH Tunnel

An Android SSH tunnel restricted to this phone’s local ADB. Key-only SSH,
no shell, PTY, file transfer, agent/X11 or reverse forwarding.

Package: `name.krot.adbsshtunnel`. Initial release candidate: `1.0.0` (1).
This repository is in development; device acceptance and a signed release are pending.

## Without root

Enable Android Wireless Debugging on Wi-Fi and pair your computer using
`adb pair PHONE:PAIR_PORT`. The application discovers the local connect port.
Import your computer’s OpenSSH public key, allow the current network, and enable
the tunnel. Android location permission and its location toggle are needed to
read the exact Wi-Fi SSID. For Wi-Fi access after reboot, grant background
location permission in the app's Android settings. The app does not request GPS
coordinates. Mobile operator identity requires phone-state permission.

```sh
ssh -N -L 15555:127.0.0.1:5555 -p 19191 -i YOUR_KEY USER@PHONE
adb connect 127.0.0.1:15555
```

The client target is always `127.0.0.1:5555`. Inside SSH, the server maps it to
Android's current local Wireless Debugging connect port. It does not expose a new
plain ADB listener. A port change closes existing SSH connections; reconnect with
the same command. Other forwarding targets remain rejected.

Android can stop Wireless Debugging on reboot or network change. The application
does not silently enable it. Keep pairing and SSH host-key verification separate.
Pin the SSH host key using a physical USB forward before trusting WAN.

## Network rules

Wi-Fi SSIDs and mobile operator PLMN codes (MCC + MNC) have independent allowlists
and blocklists. Matching is exact, including SSID case and spaces. A blocklist
entry always wins. Choose listed networks only, known networks except blocked,
or disable that transport. New installations and upgrades start with empty
allowlists, so access remains closed until a network is explicitly allowed.

Unknown network identities, unavailable permissions, suspended or blocked
networks cannot open SSH. Mobile identity refers to the connected operator,
including roaming. IMS and VPN interfaces are not incoming Internet endpoints.
SSH listens on the allowed physical IPv4 addresses and loopback while a network
is allowed. WireGuard's configuration and status are independent. Changing a rule
or network identity closes existing sessions before the listener is replaced.
See [acceptance scenarios](docs/network-policy-acceptance.md).

## With root

Install `helper/helper.sh` as `/data/adb/adb-ssh-tunnel/helper.sh`, root-owned,
mode 700 through a trusted USB connection. The helper applies its own protection
before enabling ADB port 5555. The SSH service itself is never elevated.
An existing PhoneDebug guard retains responsibility for its trusted LAN policy.
Do not remove or overwrite other applications’ firewall rules.

For persistence across reboot on Magisk, install the repository's boot scripts
through the same trusted USB connection:

| Source | Destination |
|---|---|
| `helper/boot-guard.sh` | `/data/adb/post-fs-data.d/adb-ssh-tunnel-guard.sh` |
| `helper/boot-enable.sh` | `/data/adb/service.d/adb-ssh-tunnel-enable.sh` |

Both scripts must be owned by root with mode 700. Check existing files before
installing; do not overwrite another setup. The early script establishes the
guard before a persisted TCP ADB listener can start. The late script enables
root ADB only after that guard succeeds. Without these boot hooks, runtime
firewall rules disappear on reboot and the app refuses unguarded root ADB.
If an owner-managed PhoneDebug guard is present, its own boot setup must restore
its rules before the helper can accept them.

The boot scripts enable root ADB independently of the SSH toggle. Install them
only when persistent root ADB is intended. After reboot, verify `helper.sh check`
and a key-authenticated SSH/ADB connection on an allowed physical network.
Turning off the SSH tunnel alone does not disable the system's root ADB listener.

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
The Red ARC workflow uses the repository's persistent toolchain/dependency cache
and its existing network isolation from home services. Sign
the reviewed APK in a separate process. One permanent signing key is used for
GitHub and every future store, including the Play App Signing key.

## Licenses

Application code: GPL-3.0. Native code originates from
[Sshd4a](https://github.com/tfonteyn/Sshd4a/tree/897f9064a7279bff89538ec87729c43888f3b83d).
Its Dropbear, libtommath and libtomcrypt notices remain in the pinned source.
No upstream SCP, SFTP or rsync executable is built or packaged.
