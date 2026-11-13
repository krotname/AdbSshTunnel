# ADB SSH Tunnel for Linux

Version **2.6.0**. A native, adaptive GTK 4 / Libadwaita application for Linux
phones and desktops, with the same look as the Android app: a large round
power button with an animated glow, four colour tiles and plain-language
problem screens that say what happened and what to do. ARM64 and amd64 Debian packages are published separately
from the Android APKs. Kali/Phosh ARM64 is the mobile test platform.

## Install and connect

Download the package for your architecture from the
[Linux release](https://github.com/krotname/AdbSshTunnel/releases/tag/linux-v2.6.0).
On Kali or a Debian-based system providing GTK >= 4.12, Libadwaita >= 1.4,
Python >= 3.11 and `python3-asyncssh` >= 2.24 in its configured repositories:

```sh
sudo apt install ./adb-ssh-tunnel_2.6.0_arm64.deb
adb start-server
adb-ssh-tunnel
```

The desktop launcher is **ADB SSH Tunnel**. In **Networks & keys**, allow the
current network and import your computer's **OpenSSH public key** (`.pub`), then
tap the large round button to start the tunnel. Never copy the private key to the Linux phone. Passwords, authorized-key
options and private-key imports are rejected. RSA keys need at least 3072 bits.

Verify the server's SHA256 host fingerprint in the app through a trusted
connection. From the computer, substitute your own address and private-key path:

```sh
ssh -N -L 15037:127.0.0.1:5037 -p 19191 -i YOUR_KEY adb@LINUX_IP
```

Leave SSH open; use another terminal:

```sh
adb -H 127.0.0.1 -P 15037 devices -l
adb -H 127.0.0.1 -P 15037 -s ANDROID_SERIAL shell
```

This forwards the **Linux SDK ADB server protocol**. It does not create Android
`adbd` on Linux and does not accept `adb connect` to the forwarded server port.
Only Android devices already attached/paired to the Linux ADB server are
available. An empty device list proves the server path, not Android commands.
ADB's own device authorization is still required. Access to this ADB server
allows ADB operations on its attached devices, including their Android shell.
The **SSH** server itself never accepts a shell or command.

Keep the SDK server bound only to loopback: never use `adb -a` or expose port
5037 on LAN/WAN. The app does not reconfigure or start the SDK server, system
sshd, firewalls, VPNs, or Android debugging. If ADB is unavailable, the SSH
listener stays closed. The app does not provide an Internet relay.

## Network and key policy

- New installations are stopped and have an empty allowlist.
- Blocked entries win. Unknown or missing identities are always denied.
- Wi-Fi rules match the SSID. Ethernet and mobile rules use NetworkManager
  connection UUIDs; a Linux mobile profile is not an Android operator ID.
- The app selects the IPv4 default-route interface and binds only its current
  address, never all interfaces. IPv6 is not used. VPN routes without a supported
  physical NetworkManager profile stay blocked.
- Network/configuration changes are polled. A changed address, denied network,
  key revocation, unavailable ADB server or invalid config closes the listener
  and existing sessions. Resume is automatic when an enabled tunnel is allowed.
- Only username `adb`, imported keys and exact target `127.0.0.1:5037` work.
  SSH shell, exec, PTY, SFTP/SCP, agent/X11, UNIX socket, remote/reverse and all
  other forwarding targets are rejected. The existing system SSH is independent.

The SSH host key is generated once in `~/.config/adb-ssh-tunnel/host_ed25519`
with mode 0600. Settings and imported public keys are in the same private
directory. Package upgrades preserve them. No account, telemetry or cloud sync.
Changing keys or rules does not change the host key.

## Background operation and diagnostics

The app controls `adb-ssh-tunnel.service` in your **user** systemd session.
Closing the window keeps an enabled tunnel running; tap the round button to stop.
**Start at sign-in** is optional and disabled by default. It does not enable
lingering or change system services. After a package upgrade, stop and start
the tunnel to use the new backend. No root is needed after installation.

```sh
adb-ssh-tunnel --version
adb-ssh-tunnel status
systemctl --user status adb-ssh-tunnel.service
journalctl --user -u adb-ssh-tunnel.service
```

If no user systemd session is available, the UI cannot control the daemon.
For headless use configure the same JSON file and run `adb-ssh-tunnel serve`.
Only one daemon per user is permitted. There is no boot/root mode, Android
Wireless Debugging switch, Quick Settings tile, or Android USB-key retention.
English and Russian are available in **More**.

## Snap package (strict confinement)

The Snap release carries the same 2.6.0 code under strict confinement.
Install it from the Snap Store (`latest/stable` channel, published as
`adb-ssh-tunnel` by Kreout):

    snap install adb-ssh-tunnel

It is also discoverable through GNOME Software search. `snap info
adb-ssh-tunnel` shows the installed revision and tracking channel.
Inside the Snap, `systemctl` is
unavailable, so the app supervises its own background `serve` child
instead of a systemd unit:

- Closing the window hides the app instead of quitting: an enabled
  tunnel keeps running. Reopen it from the app menu; use **More → Quit
  application** to stop the tunnel and fully close the app.
- **Start at sign-in** writes the app's own autostart entry, launched by
  the system at the next login. A hidden launch brings an enabled tunnel
  up without showing a window.
- The tunnel still binds only the current approved address, allows only
  known networks and keys, and stays fail-closed. Network identity is
  read from NetworkManager over the system bus, which needs the
  `network-manager-observe` connection (`snap connect
  adb-ssh-tunnel:network-manager-observe` if it is not connected
  automatically).
- Settings, keys and host key live separately under
  `~/snap/adb-ssh-tunnel/` and are never migrated from the Debian
  package. Diagnostics shows the Snap log file path instead of
  `journalctl`.

## Development

Run the meaningful protocol and policy tests on an isolated Linux build host:

```sh
python3 -m unittest discover -s linux/tests -v
python3 linux/build-deb.py --arch arm64 --output dist
python3 linux/build-deb.py --arch amd64 --output dist
```

The tests use real SSH connections and a separate ADB-protocol fixture; the
hardware acceptance additionally uses the actual local SDK server. GTK layout
must be checked at 360 logical pixels in Phosh and in a desktop session.
