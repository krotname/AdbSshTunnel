# Linux tunnel (delta: linux-snap-service)

## MODIFIED Requirements

### Requirement: Background service preserves user control

On Debian, Linux SHALL manage the gateway through
`adb-ssh-tunnel.service` in the user's systemd session. Inside the
strictly confined Snap, Linux SHALL instead supervise its own `serve`
child in the resident GUI process: closing the window hides it and
preserves an enabled tunnel; an explicit Quit action stops the tunnel
and closes the app. A hidden autostart launch (`--hidden`, started by
`snap userd` when Start at sign-in is on) SHALL bring up an enabled
tunnel without showing a window. Explicit stop SHALL close the tunnel.
The app SHALL leave the SDK server, system sshd and routing under the
user's control.

#### Scenario: User closes and reopens the window

- **WHEN** the UI window closes while the service is enabled
- **THEN** the background process continues subject to policy and
  reopening the UI shows its current state; in Snap mode the process
  that owns the window keeps running hidden

#### Scenario: Snap starts at sign-in

- **WHEN** Start at sign-in is enabled and the user logs in
- **THEN** the hidden app starts the serve child when the tunnel is
  enabled and stays idle otherwise; a visible launch never starts the
  tunnel by itself

## ADDED Requirements

### Requirement: Snap repair hints avoid host service commands

In Snap mode the tunnel states SHALL point at quitting the app from
More and reopening it, and Diagnostics SHALL show the Snap log file
path, instead of `systemctl`/`journalctl` commands that are unavailable
in confinement.

#### Scenario: Snap background process fails

- **WHEN** the Snap serve child cannot start or stops unexpectedly
- **THEN** the problem screen explains the failure without host
  service commands and retry restarts the child

### Requirement: Snap network identity uses NetworkManager D-Bus

In Snap mode Linux SHALL resolve the current network identity over the
NetworkManager system bus (the `network-manager-observe` interface),
falling back to procfs/ioctl only for the local address. Any failure
SHALL stay fail-closed with no identity. Wi-Fi SHALL use the SSID,
Ethernet and mobile SHALL use connection UUIDs, as on Debian.

#### Scenario: Snap cannot observe NetworkManager

- **WHEN** the `network-manager-observe` interface is disconnected or
  the bus call fails
- **THEN** the listener remains closed with an unknown-network problem
  instead of guessing a policy identity
