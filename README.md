# ADB SSH Tunnel

An Android SSH tunnel restricted to this phone’s local ADB. Key-only SSH,
no shell, PTY, file transfer, agent/X11 or reverse forwarding.

Package: `name.krot.adbsshtunnel`. Version: `1.2.2`. See the release profile and device acceptance before deployment.

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

Android can stop Wireless Debugging on reboot or network change. The optional
**Keep Wireless Debugging enabled** switch observes Android's debugging setting
and restores a previously enabled session on allowed Wi-Fi while non-root SSH
is enabled. It requires a one-time grant from a trusted ADB computer:

```sh
adb shell pm grant name.krot.adbsshtunnel android.permission.WRITE_SECURE_SETTINGS
```

First enable Wireless Debugging yourself on allowed Wi-Fi to arm recovery.
Unknown or blocked networks and missing permissions never trigger recovery.
Disable keep-enabled before turning debugging off yourself. Stopping SSH also
stops recovery. Failed restoration backs off up to one minute; the app does not
keep the CPU or Wi-Fi awake. Android can still require network confirmation or
a new pairing after reboot. Keep pairing and SSH host-key verification separate.
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

On the ADB tab, install the APK's native root controller and select persistent protected ADB. The controller lives in a root-owned directory and receives only fixed operations and a validated policy. SSH stays under the app UID.

Persistent ADB is independent of the SSH switch and can be disabled explicitly. Its own trust settings contain a Wi-Fi SSID prefix, an exact personal hotspot SSID and a WireGuard interface. Empty settings permit loopback only; unknown Wi-Fi and mobile direct ADB remain closed. SSH retains separate allowlists and blocklists. USB debugging and authorization keys are preserved.

One minimal post-fs-data hook applies protection before Android finishes booting and starts the controller. Netlink events and bounded checks update only AST_ADB_IN / AST_ADB_OUT in both firewall families, including dynamic Wireless Debugging ports. TCP ADB is disabled if protection fails. Healthy adbd is not restarted. A separate periodic Android job can restore a failed watcher even when SSH is off.

Use the verified migration procedure in [root-controller acceptance](docs/root-controller-acceptance.md) when replacing the legacy helper or PhoneDebug watchdog. Retire old chains only after the new guard passes. AdAway remains independent with its existing recovery budget and 15-minute checks. Other root modules and firewall rules remain outside this migration.

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
