# Linux Snap service backend

## Why

The Debian package manages the gateway through `adb-ssh-tunnel.service`
in the user's systemd session. Inside a strictly confined Snap,
`systemctl` and `systemd-run` cannot be executed, nested `snap run`
sessions are denied, Snap user daemons require an experimental flag the
store does not set, and a detached child does not outlive its Snap scope.
Without a service backend the tunnel cannot survive the window closing or
rise at login, which the store release requires.

## What changes

- The Linux GUI gains a service backend abstraction: systemd on Debian
  (unchanged behavior), in-process supervision of its own `serve` child
  inside the Snap.
- The Snap GUI is resident: closing the window hides it instead of
  quitting, so a managed tunnel keeps running. An explicit Quit action
  stops the tunnel and closes the app (Snap only).
- Hidden start (`--hidden`) for the Snap autostart entry: the GUI starts
  without a window and brings the tunnel up when it was enabled.
- Start at sign-in in Snap mode writes/removes the app's own autostart
  desktop copy; `snap userd` launches it at the next login.
- Network probing in Snap mode reads NetworkManager over the system bus
  (the `network-manager-observe` interface) with a procfs/ioctl fallback
  for the address. Debian probing with `ip`/`nmcli` is unchanged.
- Repair hints avoid `systemctl`/`journalctl` in Snap mode and point at
  quitting/reopening the app and the Snap log file instead.

## What does not change

- Tunnel protocol, key policy, network policy semantics, fail-closed
  behavior, port defaults and the Debian user service are untouched.
- No migration: Snap settings live separately under `~/snap/adb-ssh-tunnel`.
