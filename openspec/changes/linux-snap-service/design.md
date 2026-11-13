# Design: Linux Snap service backend

## Backend selection

`service.in_snap()` (the `SNAP` environment variable, always set by
`snapd` inside confinement) selects the backend. Debian behavior is the
`systemd` path and is byte-for-byte preserved.

## Snap supervision (`service.SnapSupervisor`)

- Owns one `serve` child per user: PID file in the Snap runtime dir
  (`SNAP_USER_DATA/runtime`, namespaced because `XDG_RUNTIME_DIR` is
  shared with the Debian package on the same machine).
- `start` spawns `[SNAP/bin/adb-ssh-tunnel, serve]` with stdio on
  `SNAP_USER_DATA/serve.log`; no new session (`setsid` is denied by the
  Snap sandbox, and the child must stay in the GUI scope by design).
- `stop` sends SIGTERM, waits, then SIGKILL; `restart` throttles respawns.
- The GUI marks the child managed on button/hidden-autostart start and
  adopts an already running child; `refresh()` revives a managed child
  that died while the tunnel is enabled.

## Resident GUI (`ui.Application`)

- `hidden` constructor flag (CLI `--hidden`): build everything, skip
  `present()`. A hidden autostart launch starts the serve child when the
  tunnel is enabled; a visible launch never starts it by itself.
- `close-request` hides the window instead of destroying it (Snap only);
  a second activation presents it again (single instance per app ID).
- `quit_app()` stops the child and quits (Snap only, More page).

## Start at sign-in (`service` autostart helpers)

- The toggle writes/removes `$SNAP_USER_DATA/.config/autostart/
  adb-ssh-tunnel.desktop` with `Exec=adb-ssh-tunnel --hidden`; the
  basename matches the snap `autostart:` key so `snap userd` launches it.
- Presence of the file is the switch state, mirroring
  `systemctl is-enabled`.

## Network probing (`core.snap_network`)

- Gio D-Bus `org.freedesktop.NetworkManager`: primary connection,
  connection settings (type/UUID), first device (interface/type),
  wireless access-point SSID, IPv4 address data; ioctl fallback for the
  address. Pure mapping in `service.nm_connection_identity` and
  `core.nm_build_network` with fail-closed `Network()` on any error.
- Unsupported kinds (VPN/tunnel/bridge) yield no identity, like the
  Debian path ignoring non-physical routes.

## Hints (`messages.classify(..., snap=)`)

- Service/stopped problems get Snap repair texts (quit from More, reopen;
  Snap log file in Diagnostics) instead of systemctl/journalctl commands.
- Debian texts are unchanged.
