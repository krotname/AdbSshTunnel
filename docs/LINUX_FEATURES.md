# Android → Linux feature contract

Linux version 2.3.1 is a separate implementation with the Android 2.3.0 look; Android is 2.3.0.

| Android feature | Linux implementation |
|---|---|
| Public SSH key import; key-only auth | OpenSSH public-key import/paste/file; no private keys/options/passwords; revoke keys in UI |
| Per-network allowlist/blocklist; deny wins | Default-route IPv4 interface, Wi-Fi SSID, Ethernet/mobile NM UUID; unknown blocked; disabled mode |
| Limited SSH forwarding | Only exact `127.0.0.1:5037` SDK ADB server; no SSH sessions/exec/PTY/files/agent/X11/reverse/UNIX targets |
| Android Wireless Debugging + mDNS | Not applicable to the Linux host; use its local SDK ADB server and `adb -H/-P` |
| Root adbd/controller | Not applicable; does not create adbd or change system sshd |
| USB-debugging and key-expiry retention | Not applicable; manage attached Android debugging on Android itself |
| Status, diagnostics and start/stop | Adaptive GTK UI with the Android power button, glow and tiles; actual SDK version/devices, SSH connections, host fingerprint; plain-language errors with technical details |
| Background service/boot | Per-user systemd service, explicit start/stop and optional sign-in autostart; no root/linger change |
| Quick Settings tile | Native Linux desktop/mobile launcher with stable app ID; no Android tile |
| Russian/English | System language or explicit RU/EN selection |
| Private local settings and stable host key | Mode 0700 directory, mode 0600 files, persistent Ed25519 host key; preserved on upgrade |

The product is an ADB gateway. SSH management of the Linux host is a separate
system service. ADB access can operate on Android devices already attached to
the SDK server, subject to their authorization. Without one, a successful empty
device-list response must not be reported as Android device-command acceptance.
